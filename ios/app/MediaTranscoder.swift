import AVFoundation
import Foundation
import VideoToolbox

/// Resizes and re-encodes one video with AVAssetReader and AVAssetWriter (not AVAssetExportSession,
/// whose presets fix the bitrate): H.264 or HEVC, the size MPCore.target chose (never upscaled), an
/// average bitrate, an optional lower frame rate, and AAC audio or none.
///
/// Orientation and HDR go through an AVVideoComposition built from the asset: the preferred
/// transform is applied, so the output is upright with an identity transform. H.264 output is SDR
/// (BT.709, 8-bit; the composition tone-maps an HDR source). HEVC output keeps an HDR source's
/// colour (10-bit, Main10, the source's primaries, transfer function and matrix).
///
/// Threading: reading and writing run on `queue`; `progress` and `done` are called on the main queue,
/// `done` exactly once. `cancel()` may be called from the main queue at any time.
final class MediaTranscoder {
    struct Options {
        let target: MPTarget
        let codec: String
        let frameRate: Int
        let keepAudio: Bool
        let audioKbps: Int
    }

    struct Output {
        let width: Int
        let height: Int
        let durationMs: Int
        let codec: String
        let hdr: Bool
    }

    enum Failure: Error {
        case unreadable(String)
        case noVideo
        case notSupported(String)
        case failed(String)
        case cancelled
    }

    private let queue = DispatchQueue(label: "nativeext.mediapicker.transcode")
    private var reader: AVAssetReader?
    private var writer: AVAssetWriter?
    private var cancelled = false
    private var finished = false

    /// Source facts the caller needs before choosing a target: display size, bitrate, frame rate.
    static func probe(_ url: URL) async -> (width: Int, height: Int, kbps: Int, fps: Double, durationMs: Int, hdr: Bool)? {
        let asset = AVURLAsset(url: url)
        guard let track = try? await asset.loadTracks(withMediaType: .video).first,
              let (size, transform, rate, fps) = try? await track.load(.naturalSize, .preferredTransform, .estimatedDataRate, .nominalFrameRate),
              let duration = try? await asset.load(.duration) else { return nil }
        let shown = size.applying(transform)
        let hdr = (try? await track.load(.mediaCharacteristics))?.contains(.containsHDRVideo) ?? false
        return (Int(abs(shown.width).rounded()), Int(abs(shown.height).rounded()), Int(rate / 1000), Double(fps),
                Int(CMTimeGetSeconds(duration) * 1000), hdr)
    }

    /// Whether this device can encode `codec` at the target size (4K needs the 4K output presets).
    static func supports(codec: String, target: MPTarget) -> Bool {
        let presets = AVOutputSettingsAssistant.availableOutputSettingsPresets()
        let short = min(target.width, target.height)
        if codec == "hevc" {
            return short > 1080 ? presets.contains(.hevc3840x2160) : presets.contains(.hevc1920x1080)
        }
        return short > 1080 ? presets.contains(.preset3840x2160) : true
    }

    func start(source: URL, output: URL, options o: Options,
               progress: @escaping (Double) -> Void, done: @escaping (Result<Output, Failure>) -> Void) {
        Task {
            let asset = AVURLAsset(url: source)
            let videoTrack: AVAssetTrack
            let audioTrack: AVAssetTrack?
            let duration: CMTime
            let composition: AVMutableVideoComposition
            let hdr: Bool
            let geometry: (CGSize, CGAffineTransform)
            var audioFormat: (channels: Int, rate: Double) = (2, 44_100)
            do {
                guard let v = try await asset.loadTracks(withMediaType: .video).first else {
                    return self.finish(.failure(.noVideo), done)
                }
                videoTrack = v
                audioTrack = o.keepAudio ? try await asset.loadTracks(withMediaType: .audio).first : nil
                duration = try await asset.load(.duration)
                hdr = (try await v.load(.mediaCharacteristics)).contains(.containsHDRVideo)
                composition = try await AVMutableVideoComposition.videoComposition(withPropertiesOf: asset)
                geometry = try await v.load(.naturalSize, .preferredTransform)
                if let a = audioTrack, let d = try await a.load(.formatDescriptions).first,
                   let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(d) {
                    let r = asbd.pointee.mSampleRate
                    audioFormat = (max(1, Int(asbd.pointee.mChannelsPerFrame)), r == 48_000 || r == 44_100 ? r : 44_100)
                }
            } catch {
                return self.finish(.failure(.unreadable(error.localizedDescription)), done)
            }
            self.queue.async {
                self.run(asset: asset, video: videoTrack, audio: audioTrack, duration: duration, composition: composition,
                         hdr: hdr, geometry: geometry, audioFormat: audioFormat, output: output, options: o, progress: progress, done: done)
            }
        }
    }

    func cancel() {
        queue.async {
            self.cancelled = true
            self.reader?.cancelReading()
            self.writer?.cancelWriting()
        }
    }

    private func finish(_ r: Result<Output, Failure>, _ done: @escaping (Result<Output, Failure>) -> Void) {
        queue.async {
            guard !self.finished else { return }
            self.finished = true
            let result: Result<Output, Failure> = self.cancelled ? .failure(.cancelled) : r
            DispatchQueue.main.async { done(result) }
        }
    }

    private func run(asset: AVAsset, video: AVAssetTrack, audio: AVAssetTrack?, duration: CMTime,
                     composition: AVMutableVideoComposition, hdr: Bool, geometry: (CGSize, CGAffineTransform),
                     audioFormat: (channels: Int, rate: Double), output: URL, options o: Options,
                     progress: @escaping (Double) -> Void, done: @escaping (Result<Output, Failure>) -> Void) {
        guard !cancelled else { return finish(.failure(.cancelled), done) }
        let keepHDR = hdr && o.codec == "hevc"
        try? FileManager.default.removeItem(at: output)
        let reader: AVAssetReader
        let writer: AVAssetWriter
        do {
            reader = try AVAssetReader(asset: asset)
            writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
        } catch {
            return finish(.failure(.failed(error.localizedDescription)), done)
        }
        self.reader = reader
        self.writer = writer
        writer.shouldOptimizeForNetworkUse = true

        composition.renderSize = CGSize(width: o.target.width, height: o.target.height)
        if o.frameRate > 0 {
            let wanted = CMTime(value: 1, timescale: CMTimeScale(o.frameRate))
            if CMTimeCompare(wanted, composition.frameDuration) > 0 { composition.frameDuration = wanted }
        }
        // Scale every instruction's layer to the render size (the composition kept the source's).
        let natural = geometry.0.applying(geometry.1)
        let sx = CGFloat(o.target.width) / abs(natural.width), sy = CGFloat(o.target.height) / abs(natural.height)
        let instructions: [AVMutableVideoCompositionInstruction] = composition.instructions.compactMap { ins in
            guard let i = ins as? AVMutableVideoCompositionInstruction else { return nil }
            for case let layer as AVMutableVideoCompositionLayerInstruction in i.layerInstructions {
                layer.setTransform(geometry.1.concatenating(CGAffineTransform(scaleX: sx, y: sy)), at: .zero)
            }
            return i
        }
        if !instructions.isEmpty { composition.instructions = instructions }
        if keepHDR {
            composition.colorPrimaries = nil
        } else {
            composition.colorPrimaries = AVVideoColorPrimaries_ITU_R_709_2
            composition.colorTransferFunction = AVVideoTransferFunction_ITU_R_709_2
            composition.colorYCbCrMatrix = AVVideoYCbCrMatrix_ITU_R_709_2
        }

        let pixel = keepHDR ? kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange : kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        let vOut = AVAssetReaderVideoCompositionOutput(videoTracks: [video],
                                                       videoSettings: [kCVPixelBufferPixelFormatTypeKey as String: pixel])
        vOut.videoComposition = composition
        vOut.alwaysCopiesSampleData = false
        guard reader.canAdd(vOut) else { return finish(.failure(.failed("the video track cannot be read")), done) }
        reader.add(vOut)

        var compression: [String: Any] = [
            AVVideoAverageBitRateKey: o.target.bitrateKbps * 1000,
            AVVideoMaxKeyFrameIntervalDurationKey: 2,
            AVVideoAllowFrameReorderingKey: true,
        ]
        if o.frameRate > 0 { compression[AVVideoExpectedSourceFrameRateKey] = o.frameRate }
        var vSettings: [String: Any] = [
            AVVideoWidthKey: o.target.width,
            AVVideoHeightKey: o.target.height,
        ]
        if o.codec == "hevc" {
            vSettings[AVVideoCodecKey] = AVVideoCodecType.hevc
            compression[AVVideoProfileLevelKey] = keepHDR ? kVTProfileLevel_HEVC_Main10_AutoLevel as String
                : kVTProfileLevel_HEVC_Main_AutoLevel as String
        } else {
            vSettings[AVVideoCodecKey] = AVVideoCodecType.h264
            compression[AVVideoProfileLevelKey] = AVVideoProfileLevelH264HighAutoLevel
            compression[AVVideoH264EntropyModeKey] = AVVideoH264EntropyModeCABAC
        }
        if !keepHDR {
            vSettings[AVVideoColorPropertiesKey] = [
                AVVideoColorPrimariesKey: AVVideoColorPrimaries_ITU_R_709_2,
                AVVideoTransferFunctionKey: AVVideoTransferFunction_ITU_R_709_2,
                AVVideoYCbCrMatrixKey: AVVideoYCbCrMatrix_ITU_R_709_2,
            ]
        }
        vSettings[AVVideoCompressionPropertiesKey] = compression
        let vIn = AVAssetWriterInput(mediaType: .video, outputSettings: vSettings)
        vIn.expectsMediaDataInRealTime = false
        guard writer.canAdd(vIn) else { return finish(.failure(.notSupported("the encoder refused these video settings")), done) }
        writer.add(vIn)

        var aOut: AVAssetReaderTrackOutput?
        var aIn: AVAssetWriterInput?
        if let a = audio {
            let out = AVAssetReaderTrackOutput(track: a, outputSettings: [AVFormatIDKey: kAudioFormatLinearPCM])
            out.alwaysCopiesSampleData = false
            _ = a
            let channels = min(2, audioFormat.channels)
            let input = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVNumberOfChannelsKey: channels,
                AVSampleRateKey: audioFormat.rate,
                AVEncoderBitRateKey: max(32, o.audioKbps) * 1000,
            ])
            input.expectsMediaDataInRealTime = false
            if reader.canAdd(out), writer.canAdd(input) {
                reader.add(out)
                writer.add(input)
                aOut = out
                aIn = input
            }
        }

        guard reader.startReading() else {
            return finish(.failure(.unreadable(reader.error?.localizedDescription ?? "the video cannot be read")), done)
        }
        guard writer.startWriting() else {
            reader.cancelReading()
            return finish(.failure(.failed(writer.error?.localizedDescription ?? "the encoder did not start")), done)
        }
        writer.startSession(atSourceTime: .zero)

        let total = max(CMTimeGetSeconds(duration), 0.001)
        let group = DispatchGroup()
        var lastReported = -1.0
        group.enter()
        vIn.requestMediaDataWhenReady(on: queue) {
            while vIn.isReadyForMoreMediaData {
                guard !self.cancelled, let sample = vOut.copyNextSampleBuffer() else {
                    vIn.markAsFinished()
                    group.leave()
                    return
                }
                let t = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
                if !vIn.append(sample) {
                    reader.cancelReading()
                    vIn.markAsFinished()
                    group.leave()
                    return
                }
                let p = min(0.99, max(0, t / total))
                if p - lastReported >= 0.01 {
                    lastReported = p
                    DispatchQueue.main.async { progress(p) }
                }
            }
        }
        if let aIn = aIn, let aOut = aOut {
            group.enter()
            aIn.requestMediaDataWhenReady(on: queue) {
                while aIn.isReadyForMoreMediaData {
                    guard !self.cancelled, let sample = aOut.copyNextSampleBuffer(), aIn.append(sample) else {
                        aIn.markAsFinished()
                        group.leave()
                        return
                    }
                }
            }
        }
        group.notify(queue: queue) {
            if self.cancelled || reader.status == .failed || writer.status == .failed {
                let why = writer.error?.localizedDescription ?? reader.error?.localizedDescription ?? "the transcode failed"
                writer.cancelWriting()
                try? FileManager.default.removeItem(at: output)
                return self.finish(self.cancelled ? .failure(.cancelled) : .failure(.failed(why)), done)
            }
            writer.finishWriting {
                self.queue.async {
                    if writer.status == .completed {
                        self.finish(.success(Output(width: o.target.width, height: o.target.height,
                                                    durationMs: Int(total * 1000), codec: o.codec, hdr: keepHDR)), done)
                    } else {
                        try? FileManager.default.removeItem(at: output)
                        self.finish(.failure(.failed(writer.error?.localizedDescription ?? "the output was not finalized")), done)
                    }
                }
            }
        }
    }
}
