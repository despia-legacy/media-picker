import AVFoundation
import BackgroundTasks
import Foundation
import ImageIO
import UIKit
import UniformTypeIdentifiers

/// Native picker for every kind of file, native video transcoding, and durable upload.
///
/// The page never receives file bytes through the bridge. Each picked file gets a loopback URL
/// (MediaFileServer) that `<img>`, `<video>`, `<audio>` and `fetch()` read directly, with byte ranges.
/// Routes answer at once; what the user does in the picker, each file's arrival, transcode and
/// upload progress arrive as events (`pick`, `file`, `job`, `server`) and stay readable through
/// `result`, `files` and `jobs`.
///
/// Files live in Application Support/nativeext/mediapicker/files/<fileId>/ (excluded from backup).
/// A file leaves when the page releases it, when an upload with release_after finishes, or at the
/// next launch, except a file an unfinished upload still needs.
///
/// Main actor, as NativeExtension requires: every route, callback and delegate runs there.
@MainActor
final class MediaPickerExtension: NSObject, NativeExtension {
    struct FileRecord {
        let id: String
        var token: String
        var pickId: String?
        var origin: String
        var name: String
        var mime: String
        var kind: String
        var bytes: Int64
        var state: String
        var reason: String?
        var progress: Double?
        var width: Int?
        var height: Int?
        var durationMs: Int?
        var held = false
    }

    struct PickRecord {
        let id: String
        var state: String
        let source: String
        var fileIds: [String] = []
        var reason: String?
        let maxFileMB: Int
        let maxTotalMB: Int
        let limit: Int
    }

    struct TranscodeJob {
        let id: String
        let sourceId: String
        let outputId: String
        var state: String
        var progress: Double
        var reason: String?
        let target: MPTarget
        let codec: String
        let frameRate: Int
        let keepAudio: Bool
        let audioKbps: Int
    }

    private var ctx: NativeExtContext!
    private var filesRoot: URL!
    private var server: MediaFileServer!
    private var port: UInt16?
    private let presenter = MediaPickerPresenter()
    private var files: [String: FileRecord] = [:]
    private var picks: [String: PickRecord] = [:]
    private var transcodes: [String: TranscodeJob] = [:]
    private var transcodeOrder: [String] = []
    private var transcoder: MediaTranscoder?
    private var runningTranscode: String?
    private var store: MPUploadStore!
    private var uploader: MediaUploader!
    /// Launch clean-up and the restart of unfinished uploads ran (they wait for a readable index).
    private var indexSettled = false
    private var protectedDataObserver: NSObjectProtocol?
    private var assertion: UIBackgroundTaskIdentifier = .invalid
    private var transcodeAssertion: UIBackgroundTaskIdentifier = .invalid

    required override init() { super.init() }

    // MARK: Lifecycle

    func didLaunch(_ context: NativeExtContext) {
        ctx = context
        filesRoot = context.dataDirectory.appendingPathComponent("files", isDirectory: true)
        try? FileManager.default.createDirectory(at: filesRoot, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var root = filesRoot!
        try? root.setResourceValues(values)
        store = MPUploadStore(directory: context.dataDirectory)
        server = MediaFileServer(log: { [weak self] m in self?.ctx.log(m) })
        server.onPort = { [weak self] p in self?.portChanged(p) }
        uploader = MediaUploader(store: store, filesRoot: filesRoot,
                                 isApproved: { [weak self] url, route in self?.ctx.isApprovedURL(url, route: route, param: "url") ?? false },
                                 changed: { [weak self] id in self?.uploadChanged(id) },
                                 progressed: { [weak self] id, sent, total in self?.uploadProgress(id, sent, total) },
                                 inBackground: UIApplication.shared.applicationState == .background)
        uploader.onIdle = { [weak self] in self?.endAssertion() }
        if UIApplication.shared.applicationState != .background { server.ensureRunning() }
        protectedDataObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in _ = self?.indexReady() }
        }
        if !indexReady() { ctx.log("upload index exists but is not readable yet; uploads and launch clean-up wait for it") }
    }

    /// Reads the upload index if it is not read yet, and the first time it is known runs what
    /// waited for it: prune settled records, launch clean-up, re-list and restart unfinished
    /// uploads. An index that exists but cannot be read (before the first unlock) is retried on
    /// unlock, on foreground and on each upload or jobs call; until then nothing deletes a file,
    /// writes the index or takes an upload (MPCore.indexPlan).
    @discardableResult
    private func indexReady() -> Bool {
        guard store.load() else { return false }
        guard !indexSettled else { return true }
        indexSettled = true
        if store.recovered { ctx.log("upload index did not decode; copied aside and replaced") }
        if store.allows("prune") { pruneSettledUploads() }
        if store.allows("cleanUp") { cleanUpAtLaunch() }
        readoptUploadFiles()
        if uploader.hasWork {
            beginAssertion()
            uploader.drain()
        }
        return true
    }

    func sceneDidChange(_ phase: NativeExtScenePhase) {
        switch phase {
        case .willEnterForeground, .didBecomeActive:
            indexReady()
            server.ensureRunning()
            uploader.becameActive()
            if uploader.isBusy { beginAssertion() }
        case .didEnterBackground:
            uploader.didEnterBackground()
            if uploader.isBusy, assertion == .invalid { uploader.expireInFlight() }
            if uploader.hasWork { scheduleProcessing() }
        default:
            break
        }
    }

    func handleBackgroundTask(name: String, task: BGTask) {
        guard name == "upload" else { return task.setTaskCompleted(success: false) }
        indexReady()
        var finished = false
        let previous = uploader.onIdle
        let finish: (Bool) -> Void = { [weak self] ok in
            guard !finished else { return }
            finished = true
            self?.uploader.onIdle = previous
            task.setTaskCompleted(success: ok)
        }
        ctx.onExpiration(of: task) { [weak self] in
            guard !finished, let self = self else { return }
            self.uploader.expireInFlight()
            finish(false)
            if self.uploader.hasWork { self.scheduleProcessing() }
        }
        uploader.onIdle = { [weak self] in
            previous?()
            guard let self = self else { return }
            if !self.uploader.isBusy && !self.uploader.hasWork { finish(true) }
        }
        uploader.drain()
        if !uploader.isBusy && !uploader.hasWork { finish(true) }
    }

    func willTerminate() {
        server?.stop()
    }

    // MARK: Routes

    func handle(_ call: NativeExtCall) async -> NativeExtResult {
        switch call.route {
        case "pick": return await pick(call)
        case "result": return result(call.params["pick"] as? String ?? "")
        case "files": return listFiles(call.params["id"] as? String)
        case "release": return release(call.params["id"] as? String, all: call.params["all"] as? Bool ?? false)
        case "transcode": return await transcode(call)
        case "upload": return upload(call)
        case "jobs": return jobs(call.params["id"] as? String)
        case "cancel": return cancel(call.params["id"] as? String ?? "")
        default: return .error(code: "not_registered", message: call.route)
        }
    }

    private func pick(_ call: NativeExtCall) async -> NativeExtResult {
        let p = call.params
        guard !presenter.isPresenting else { return .error(code: "busy", message: "a picker is already open") }
        guard let types = MPCore.parseTypes(p["types"] as? String) else {
            return .error(code: "bad_value", message: "types is a comma list of image, video, audio, document, any and MIME types")
        }
        guard let source = MPCore.source(requested: p["source"] as? String ?? "auto", types: types) else {
            return .error(code: "bad_value", message: "that source cannot offer these types")
        }
        let cameraMode = MPCore.cameraMode(types: types, requested: p["camera"] as? String ?? "both")
        if source == "camera" {
            guard MediaPickerPresenter.cameraAvailable else { return .error(code: "camera_unavailable", message: "this device has no camera") }
            guard cameraMode != nil else { return .error(code: "bad_value", message: "camera does not match types") }
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .denied, .restricted:
                return .error(code: "permission_denied", message: "camera access is off for this app in Settings")
            case .notDetermined:
                let granted = await AVCaptureDevice.requestAccess(for: .video)
                guard call.isActive else { return .error(code: "timeout", message: "pick timed out before the camera was allowed") }
                guard granted else { return .error(code: "permission_denied", message: "camera access was denied") }
            default:
                break
            }
        }
        let (limit, lowered) = MPCore.limit(requested: p["limit"] as? Int ?? 0, multiple: p["multiple"] as? Bool ?? false, platformMax: 0)
        var tint: UIColor?
        if let hex = p["accent_color"] as? String {
            guard let c = Self.color(hex) else { return .error(code: "bad_value", message: "accent_color is #RRGGBB") }
            tint = c
        }
        let id = UUID().uuidString.lowercased()
        let req = MediaPickerPresenter.Request(source: source, types: types, limit: limit,
                                               representation: p["representation"] as? String ?? "current",
                                               ordered: p["ordered"] as? Bool ?? false, cameraMode: cameraMode,
                                               maxDurationS: p["max_duration_s"] as? Int ?? 0,
                                               quality: p["camera_quality"] as? String ?? "high", tint: tint)
        let record = PickRecord(id: id, state: "presenting", source: source, maxFileMB: p["max_file_mb"] as? Int ?? 0,
                                maxTotalMB: p["max_total_mb"] as? Int ?? 0, limit: limit)
        picks[id] = record
        let shown = presenter.present(req) { [weak self] outcome in self?.picked(id, outcome) }
        guard shown else {
            picks[id] = nil
            return .error(code: "unavailable", message: "the app is not in the foreground")
        }
        var out: [String: Any] = ["pickId": id, "state": "presenting", "source": source, "limit": limit, "limitLowered": lowered]
        // Platform facts for options this platform does not offer, said instead of silently ignored.
        if p["tab"] != nil { out["tabApplied"] = false }
        return .ok(out)
    }

    private func picked(_ pickId: String, _ outcome: MediaPickerPresenter.Outcome) {
        guard var pick = picks[pickId] else { return }
        switch outcome {
        case .cancelled:
            pick.state = "cancelled"
            picks[pickId] = pick
            ctx.emit("pick", pickSnapshot(pick))
        case .failed(let code, let message):
            pick.state = "failed"
            pick.reason = code
            picks[pickId] = pick
            ctx.log("pick failed: \(message)")
            ctx.emit("pick", pickSnapshot(pick))
        case .picked(let items):
            var accepted = items
            var overLimit: [MPIncoming] = []
            if pick.limit > 0, items.count > pick.limit {
                accepted = Array(items.prefix(pick.limit))
                overLimit = Array(items.dropFirst(pick.limit))
            }
            for _ in overLimit {
                let f = newFile(pickId: pickId, origin: "pick", name: "file", mime: "application/octet-stream")
                var r = f
                r.state = "failed"
                r.reason = "limit_exceeded"
                files[r.id] = r
                pick.fileIds.append(r.id)
            }
            for item in accepted {
                let f = newFile(pickId: pickId, origin: item.isCamera ? "camera" : "pick", name: "file", mime: "application/octet-stream")
                files[f.id] = f
                pick.fileIds.insert(f.id, at: pick.fileIds.count - overLimit.count)
                importItem(item, into: f.id)
            }
            for case let .file(u) in overLimit { try? FileManager.default.removeItem(at: u) }
            for case let .movie(u) in overLimit { try? FileManager.default.removeItem(at: u) }
            pick.state = accepted.isEmpty ? "done" : "loading"
            picks[pickId] = pick
            ctx.emit("pick", pickSnapshot(pick))
            settlePickIfDone(pickId)
        }
    }

    private func newFile(pickId: String?, origin: String, name: String, mime: String) -> FileRecord {
        FileRecord(id: UUID().uuidString.lowercased(), token: Self.newToken(), pickId: pickId, origin: origin, name: name,
                   mime: mime, kind: MPCore.kind(mime: mime), bytes: 0, state: "loading")
    }

    static func newToken() -> String {
        var b = [UInt8](repeating: 0, count: 32)
        let ok = SecRandomCopyBytes(kSecRandomDefault, b.count, &b)
        precondition(ok == errSecSuccess, "no system randomness")
        return MPCore.token(fromBytes: b)
    }

    private func dir(_ id: String) -> URL { filesRoot.appendingPathComponent(id, isDirectory: true) }

    /// Copies one picked item into its own directory, off the main actor, then returns here.
    private func importItem(_ item: MPIncoming, into id: String) {
        let d = dir(id)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        let done: (Result<(URL, String, String), Error>) -> Void = { [weak self] r in
            Task { @MainActor [weak self] in self?.imported(id, r) }
        }
        switch item {
        case .provider(let p, let typeId):
            let type = UTType(typeId)
            let ext = type?.preferredFilenameExtension.map { "." + $0 } ?? ""
            let mime = type?.preferredMIMEType ?? "application/octet-stream"
            let suggested = p.suggestedName.map { $0 + ext } ?? ("file" + ext)
            let progress = p.loadFileRepresentation(forTypeIdentifier: typeId) { url, error in
                // The provider's file exists only inside this callback: copy it now.
                guard let url = url else { return done(.failure(error ?? CocoaError(.fileReadUnknown))) }
                let name = MPCore.safeName(suggested, fallbackExtension: ext)
                let dest = d.appendingPathComponent(name)
                do {
                    try FileManager.default.copyItem(at: url, to: dest)
                    done(.success((dest, name, mime)))
                } catch {
                    done(.failure(error))
                }
            }
            observe(progress, id)
        case .file(let url):
            let ext = url.pathExtension.isEmpty ? "" : "." + url.pathExtension
            let name = MPCore.safeName(url.lastPathComponent, fallbackExtension: ext)
            let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
            let dest = d.appendingPathComponent(name)
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    try FileManager.default.moveItem(at: url, to: dest)
                    done(.success((dest, name, mime)))
                } catch {
                    done(.failure(error))
                }
            }
        case .photo(let data, let mime, let ext):
            let name = "photo-" + String(id.prefix(8)) + ext
            let dest = d.appendingPathComponent(name)
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    try data.write(to: dest, options: .atomic)
                    done(.success((dest, name, mime)))
                } catch {
                    done(.failure(error))
                }
            }
        case .movie(let url):
            let ext = url.pathExtension.isEmpty ? ".mov" : "." + url.pathExtension
            let name = "video-" + String(id.prefix(8)) + ext
            let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "video/quicktime"
            let dest = d.appendingPathComponent(name)
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    try FileManager.default.moveItem(at: url, to: dest)
                    done(.success((dest, name, mime)))
                } catch {
                    done(.failure(error))
                }
            }
        }
    }

    private var observers: [String: NSKeyValueObservation] = [:]

    /// iCloud downloads and large copies report progress; the page sees it on `file` events.
    private func observe(_ progress: Progress, _ id: String) {
        var last = 0.0
        observers[id] = progress.observe(\.fractionCompleted) { p, _ in
            let f = p.fractionCompleted
            guard f - last >= 0.02 || f >= 1 else { return }
            last = f
            Task { @MainActor [weak self] in
                guard let self = self, var r = self.files[id], r.state == "loading" else { return }
                r.progress = min(f, 0.99)
                self.files[id] = r
                self.ctx.emit("file", self.fileSnapshot(r))
            }
        }
    }

    private func imported(_ id: String, _ result: Result<(URL, String, String), Error>) {
        observers[id] = nil
        guard var r = files[id], r.state == "loading" else {
            // Released while loading: whatever arrived goes.
            try? FileManager.default.removeItem(at: dir(id))
            return
        }
        switch result {
        case .failure(let e):
            r.state = "failed"
            r.reason = "import_failed"
            ctx.log("import failed: \(e.localizedDescription)")
            try? FileManager.default.removeItem(at: dir(id))
        case .success(let (url, name, mime)):
            r.name = name
            r.mime = mime
            r.kind = MPCore.kind(mime: mime)
            r.bytes = ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber)?.int64Value ?? 0
            r.progress = nil
            let pick = r.pickId.flatMap { picks[$0] }
            if let reason = MPCore.admit(sizes: [r.bytes], maxFileMB: pick?.maxFileMB ?? 0, maxTotalMB: 0)[0] {
                r.state = "failed"
                r.reason = reason
                try? FileManager.default.removeItem(at: dir(id))
            } else {
                r.state = (pick?.maxTotalMB ?? 0) > 0 ? "loaded" : "ready"
                if r.kind == "image" { Self.imageSize(url, &r) }
                if r.state == "ready" { server.publish(token: r.token, entry: .init(path: url, mime: r.mime)) }
            }
        }
        files[id] = r
        if r.state != "loaded" { ctx.emit("file", fileSnapshot(r)) }
        if r.state == "ready", r.kind == "video" || r.kind == "audio" { probeMedia(id) }
        if let p = r.pickId { settlePickIfDone(p) }
    }

    /// The pick ends when no file is loading. With max_total_mb, files were held as "loaded" until
    /// now and are admitted in pick order; the rest fail with total_exceeded.
    private func settlePickIfDone(_ pickId: String) {
        guard var pick = picks[pickId], pick.state == "loading" else { return }
        let own = pick.fileIds.compactMap { files[$0] }
        guard !own.contains(where: { $0.state == "loading" }) else { return }
        if pick.maxTotalMB > 0 {
            let held = own.filter { $0.state == "loaded" }
            let verdicts = MPCore.admit(sizes: held.map { $0.bytes }, maxFileMB: 0, maxTotalMB: pick.maxTotalMB)
            for (f, v) in zip(held, verdicts) {
                var r = f
                if let reason = v {
                    r.state = "failed"
                    r.reason = reason
                    try? FileManager.default.removeItem(at: dir(r.id))
                } else {
                    r.state = "ready"
                    server.publish(token: r.token, entry: .init(path: dir(r.id).appendingPathComponent(r.name), mime: r.mime))
                }
                files[r.id] = r
                ctx.emit("file", fileSnapshot(r))
                if r.state == "ready", r.kind == "video" || r.kind == "audio" { probeMedia(r.id) }
            }
        }
        pick.state = "done"
        picks[pickId] = pick
        ctx.emit("pick", pickSnapshot(pick))
    }

    private static func imageSize(_ url: URL, _ r: inout FileRecord) {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int else { return }
        let o = props[kCGImagePropertyOrientation] as? Int ?? 1
        // EXIF 5 to 8 rotate by 90 degrees: report the size as displayed.
        if o >= 5 { (r.width, r.height) = (h, w) } else { (r.width, r.height) = (w, h) }
    }

    private func probeMedia(_ id: String) {
        guard let r = files[id] else { return }
        let url = dir(id).appendingPathComponent(r.name)
        Task { @MainActor [weak self] in
            let asset = AVURLAsset(url: url)
            let duration = (try? await asset.load(.duration)).map { Int(CMTimeGetSeconds($0) * 1000) }
            var size: (Int, Int)?
            if let v = try? await asset.loadTracks(withMediaType: .video).first,
               let (n, t) = try? await v.load(.naturalSize, .preferredTransform) {
                let s = n.applying(t)
                size = (Int(abs(s.width).rounded()), Int(abs(s.height).rounded()))
            }
            guard let self = self, var now = self.files[id], now.state == "ready" else { return }
            now.durationMs = duration
            if let s = size { (now.width, now.height) = s }
            self.files[id] = now
            self.ctx.emit("file", self.fileSnapshot(now))
        }
    }

    private func result(_ pickId: String) -> NativeExtResult {
        guard let p = picks[pickId] else { return .error(code: "not_found", message: "unknown pick") }
        return .ok(pickSnapshot(p))
    }

    private func listFiles(_ id: String?) -> NativeExtResult {
        if let id = id {
            guard MPCore.isId(id), let r = files[id] else { return .error(code: "not_found", message: "unknown file") }
            return .ok(fileSnapshot(r))
        }
        let list = files.values.filter { $0.state != "released" }.sorted { $0.id < $1.id }.map(fileSnapshot)
        var out: [String: Any] = ["files": list]
        if let b = base() { out["base"] = b }
        return .ok(out)
    }

    /// Whether a running transcode or an unfinished upload still needs this file.
    private func inUse(_ id: String) -> Bool {
        if transcodes.values.contains(where: { ($0.sourceId == id || $0.outputId == id) && ($0.state == "queued" || $0.state == "transcoding") }) {
            return true
        }
        return store.records.values.contains { $0.fileId == id && $0.state == "uploading" }
    }

    private func release(_ id: String?, all: Bool) -> NativeExtResult {
        var ids: [String] = []
        if all {
            ids = files.values.filter { $0.state != "released" && !inUse($0.id) }.map { $0.id }
        } else {
            guard let id = id, MPCore.isId(id), let r = files[id], r.state != "released" else {
                return .error(code: "not_found", message: "unknown file")
            }
            guard !inUse(id) else { return .error(code: "busy", message: "a transcode or an upload still needs this file; cancel it first") }
            ids = [id]
        }
        var failed: [String] = []
        for id in ids.sorted() where !releaseFile(id) { failed.append(id) }
        if !failed.isEmpty {
            return .error(code: "release_failed", message: "\(failed.count) file(s) could not be deleted; the next launch deletes them")
        }
        return .ok(["released": ids.sorted()])
    }

    @discardableResult
    private func releaseFile(_ id: String) -> Bool {
        guard var r = files[id] else { return true }
        server.withdraw(token: r.token)
        r.state = "released"
        r.reason = nil
        files[id] = r
        let ok: Bool
        do {
            try FileManager.default.removeItem(at: dir(id))
            ok = true
        } catch {
            ok = !FileManager.default.fileExists(atPath: dir(id).path)
        }
        ctx.emit("file", fileSnapshot(r))
        return ok
    }

    // MARK: Transcode

    private func transcode(_ call: NativeExtCall) async -> NativeExtResult {
        let p = call.params
        guard let id = p["id"] as? String, MPCore.isId(id), let src = files[id] else { return .error(code: "not_found", message: "unknown file") }
        guard src.state == "ready", src.kind == "video" else { return .error(code: "bad_value", message: "only a ready video can be transcoded") }
        guard let bitrate = MPCore.bitrate(p["bitrate_kbps"] as? String) else {
            return .error(code: "bad_value", message: "bitrate_kbps is 100 to 400000")
        }
        let codec = p["codec"] as? String ?? "h264"
        let preset = p["preset"] as? String ?? "720p"
        let frameRate = p["frame_rate"] as? Int ?? 0
        let path = dir(id).appendingPathComponent(src.name)
        guard let info = await MediaTranscoder.probe(path) else {
            return .error(code: "unreadable", message: "the video's tracks cannot be read")
        }
        guard call.isActive else { return .error(code: "timeout", message: "transcode timed out before it started") }
        guard files[id]?.state == "ready" else { return .error(code: "not_found", message: "the file was released") }
        let fps = frameRate > 0 ? Double(frameRate) : info.fps
        guard let target = MPCore.target(width: info.width, height: info.height, preset: preset, codec: codec,
                                         bitrateKbps: bitrate, sourceKbps: info.kbps, frameRate: fps) else {
            return .error(code: "bad_value", message: "unknown preset or a video without a size")
        }
        guard MediaTranscoder.supports(codec: codec, target: target) else {
            return .error(code: "not_supported_on_device", message: "this device cannot encode \(codec) at \(target.width)x\(target.height)")
        }
        var out = newFile(pickId: nil, origin: "transcode", name: "video-" + UUID().uuidString.prefix(8).lowercased() + ".mp4", mime: "video/mp4")
        out.state = "loading"
        out.progress = 0
        files[out.id] = out
        let job = TranscodeJob(id: UUID().uuidString.lowercased(), sourceId: id, outputId: out.id, state: "queued", progress: 0,
                               reason: nil, target: target, codec: codec, frameRate: frameRate,
                               keepAudio: (p["audio"] as? String ?? "keep") == "keep", audioKbps: p["audio_bitrate_kbps"] as? Int ?? 128)
        transcodes[job.id] = job
        transcodeOrder.append(job.id)
        nextTranscode()
        let now = transcodes[job.id] ?? job
        return .ok(jobSnapshot(now))
    }

    private func nextTranscode() {
        guard runningTranscode == nil, let next = transcodeOrder.first(where: { transcodes[$0]?.state == "queued" }),
              var job = transcodes[next], let src = files[job.sourceId], let out = files[job.outputId] else { return }
        try? FileManager.default.createDirectory(at: dir(out.id), withIntermediateDirectories: true)
        job.state = "transcoding"
        transcodes[next] = job
        runningTranscode = next
        let t = MediaTranscoder()
        transcoder = t
        beginTranscodeAssertion()
        ctx.emit("job", jobSnapshot(job))
        t.start(source: dir(src.id).appendingPathComponent(src.name), output: dir(out.id).appendingPathComponent(out.name),
                options: .init(target: job.target, codec: job.codec, frameRate: job.frameRate, keepAudio: job.keepAudio, audioKbps: job.audioKbps),
                progress: { [weak self] p in self?.transcodeProgress(next, p) },
                done: { [weak self] r in self?.transcodeDone(next, r) })
    }

    private func transcodeProgress(_ jobId: String, _ p: Double) {
        guard var j = transcodes[jobId], j.state == "transcoding" else { return }
        j.progress = p
        transcodes[jobId] = j
        ctx.emit("job", jobSnapshot(j))
    }

    private func transcodeDone(_ jobId: String, _ r: Result<MediaTranscoder.Output, MediaTranscoder.Failure>) {
        runningTranscode = nil
        transcoder = nil
        endTranscodeAssertion()
        guard var j = transcodes[jobId] else { return nextTranscode() }
        var out = files[j.outputId]
        // A job that already ended while the transcoder stopped (background_expired, a cancel)
        // keeps its state and reason: the transcoder reports both as cancelled.
        let outcome: (String, String?)
        switch r {
        case .success: outcome = ("done", nil)
        case .failure(let e): outcome = (Self.isCancel(e) ? "cancelled" : "failed", Self.reason(e))
        }
        let settled = MPCore.settle(state: j.state, reason: j.reason, outcome: outcome.0, outcomeReason: outcome.1)
        switch (r, settled.state) {
        case (.success(let o), "done"):
            j.state = "done"
            j.progress = 1
            if var f = out, f.state == "loading" {
                let path = dir(f.id).appendingPathComponent(f.name)
                f.state = "ready"
                f.progress = nil
                f.bytes = ((try? FileManager.default.attributesOfItem(atPath: path.path))?[.size] as? NSNumber)?.int64Value ?? 0
                (f.width, f.height, f.durationMs) = (o.width, o.height, o.durationMs)
                server.publish(token: f.token, entry: .init(path: path, mime: f.mime))
                out = f
            }
        default:
            j.state = settled.state
            j.reason = settled.reason
            if var f = out, f.state == "loading" {
                f.state = "failed"
                f.reason = j.reason
                f.progress = nil
                try? FileManager.default.removeItem(at: dir(f.id))
                out = f
            }
        }
        transcodes[jobId] = j
        if let f = out {
            files[f.id] = f
            ctx.emit("file", fileSnapshot(f))
        }
        ctx.emit("job", jobSnapshot(j))
        nextTranscode()
    }

    private static func isCancel(_ e: MediaTranscoder.Failure) -> Bool {
        if case .cancelled = e { return true }
        return false
    }

    private static func reason(_ e: MediaTranscoder.Failure) -> String {
        switch e {
        case .unreadable: return "unreadable"
        case .noVideo: return "no_video"
        case .notSupported: return "not_supported_on_device"
        case .failed: return "encode_failed"
        case .cancelled: return "cancelled"
        }
    }

    // MARK: Upload

    private func upload(_ call: NativeExtCall) -> NativeExtResult {
        let p = call.params
        guard let id = p["id"] as? String, MPCore.isId(id), let f = files[id] else { return .error(code: "not_found", message: "unknown file") }
        guard f.state == "ready" else { return .error(code: "bad_value", message: "only a ready file can be uploaded") }
        guard indexReady(), store.allows("accept") else {
            return .error(code: "storage_failed", message: "the upload index is not readable yet (the device has not been unlocked); nothing was queued")
        }
        guard let url = p["url"] as? String else { return .error(code: "bad_request", message: "url") }
        var field: String?
        if let ff = p["form_field"] as? String {
            guard MPCore.formName(ff) else { return .error(code: "bad_value", message: "form_field is 1 to 64 of A-Z a-z 0-9 . _ -") }
            field = ff
        }
        guard let type = MPCore.contentType(p["content_type"] as? String, fallback: f.mime) else {
            return .error(code: "bad_value", message: "content_type is a MIME type without parameters")
        }
        let method = p["method"] as? String ?? (field == nil ? "PUT" : "POST")
        let r = MPUpload(id: UUID().uuidString.lowercased(), fileId: id, path: id + "/" + f.name, name: f.name, mime: f.mime,
                         bytes: f.bytes, url: url, route: "upload", method: method, field: field, contentType: type,
                         state: "uploading", attempts: 0, nextAttemptAt: nil, reason: nil, httpStatus: nil,
                         releaseAfter: p["release_after"] as? Bool ?? false, createdAt: Date())
        store.set(r)
        // Durable before it is promised: nothing is queued when the index cannot be written.
        guard store.persist() else {
            store.remove(r.id)
            store.persist()
            return .error(code: "storage_failed", message: "the upload index could not be saved; nothing was queued")
        }
        beginAssertion()
        uploader.drain()
        return .ok(uploadSnapshot(store[r.id] ?? r))
    }

    private func uploadChanged(_ id: String) {
        store.persist()
        guard let r = store[id] else { return }
        if r.state == "uploaded", r.releaseAfter, files[r.fileId] != nil, !inUse(r.fileId) { releaseFile(r.fileId) }
        ctx.emit("job", uploadSnapshot(r))
    }

    private func uploadProgress(_ id: String, _ sent: Int64, _ total: Int64) {
        guard let r = store[id] else { return }
        var s = uploadSnapshot(r)
        s["sentBytes"] = sent
        s["progress"] = total > 0 ? min(1, Double(sent) / Double(total)) : 0
        ctx.emit("job", s)
    }

    /// Settled upload records from earlier runs are kept a week so the page can read the outcome.
    private func pruneSettledUploads() {
        let cutoff = Date().addingTimeInterval(-7 * 24 * 3600)
        for r in store.records.values where r.state != "uploading" && r.createdAt < cutoff { store.remove(r.id) }
        store.persist()
    }

    /// Everything under files/ goes, except the files an unfinished upload still needs and the
    /// files this run already holds (clean-up can run after picks when the index was read late).
    private func cleanUpAtLaunch() {
        let keep = Set(store.records.values.filter { $0.state == "uploading" }.map { $0.fileId }).union(files.keys)
        let entries = (try? FileManager.default.contentsOfDirectory(atPath: filesRoot.path)) ?? []
        for name in MPCore.cleanup(entries: entries, keep: keep) {
            do {
                try FileManager.default.removeItem(at: filesRoot.appendingPathComponent(name))
            } catch {
                ctx.log("launch clean-up could not delete a file directory: \(error.localizedDescription)")
            }
        }
    }

    /// A file an unfinished upload kept across a restart is listed again, with a new URL token.
    private func readoptUploadFiles() {
        for u in store.records.values where u.state == "uploading" && files[u.fileId] == nil {
            let path = filesRoot.appendingPathComponent(u.path)
            guard FileManager.default.fileExists(atPath: path.path) else { continue }
            let f = FileRecord(id: u.fileId, token: Self.newToken(), pickId: nil, origin: "upload", name: u.name, mime: u.mime,
                               kind: MPCore.kind(mime: u.mime), bytes: u.bytes, state: "ready")
            files[f.id] = f
            server.publish(token: f.token, entry: .init(path: path, mime: f.mime))
        }
    }

    // MARK: Jobs

    private func jobs(_ id: String?) -> NativeExtResult {
        indexReady()
        if let id = id {
            guard MPCore.isId(id) else { return .error(code: "not_found", message: "unknown job") }
            if let t = transcodes[id] { return .ok(jobSnapshot(t)) }
            if let u = store[id] { return .ok(uploadSnapshot(u)) }
            return .error(code: "not_found", message: "unknown job")
        }
        let list = transcodes.values.sorted { $0.id < $1.id }.map(jobSnapshot)
            + store.records.values.sorted { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }.map(uploadSnapshot)
        var out: [String: Any] = ["jobs": list]
        if !store.durable { out["durable"] = false }
        if store.recovered { out["indexRecovered"] = true }
        if store.index == "unread" { out["indexUnread"] = true }
        return .ok(out)
    }

    private func cancel(_ id: String) -> NativeExtResult {
        guard MPCore.isId(id) else { return .error(code: "not_found", message: "unknown job") }
        if var t = transcodes[id] {
            guard t.state == "queued" || t.state == "transcoding" else { return .ok(jobSnapshot(t)) }
            if t.state == "transcoding" {
                t.state = "cancelled"
                transcodes[id] = t
                transcoder?.cancel()
            } else {
                t.state = "cancelled"
                t.reason = "cancelled"
                transcodes[id] = t
                if var f = files[t.outputId] {
                    f.state = "failed"
                    f.reason = "cancelled"
                    files[f.id] = f
                    ctx.emit("file", fileSnapshot(f))
                }
                ctx.emit("job", jobSnapshot(t))
            }
            return .ok(jobSnapshot(t))
        }
        guard var u = store[id] else { return .error(code: "not_found", message: "unknown job") }
        guard u.state == "uploading" else { return .ok(uploadSnapshot(u)) }
        u.state = "cancelled"
        u.nextAttemptAt = nil
        store.set(u)
        uploader.cancel(id)
        uploadChanged(id)
        return .ok(uploadSnapshot(u))
    }

    // MARK: Snapshots

    private func base() -> String? { port.map { "http://127.0.0.1:\($0)" } }

    private func fileURL(_ r: FileRecord) -> String? {
        guard r.state == "ready", let b = base() else { return nil }
        let encoded = r.name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/?#"))) ?? "file"
        return b + "/f/" + r.token + "/" + encoded
    }

    private func fileSnapshot(_ r: FileRecord) -> [String: Any] {
        var o: [String: Any] = ["fileId": r.id, "state": r.state == "loaded" ? "loading" : r.state, "name": r.name,
                                "mime": r.mime, "kind": r.kind, "bytes": r.bytes, "origin": r.origin]
        if let p = r.pickId { o["pickId"] = p }
        if let u = fileURL(r) { o["url"] = u }
        if let v = r.reason { o["reason"] = v }
        if let v = r.progress { o["progress"] = v }
        if let v = r.width { o["width"] = v }
        if let v = r.height { o["height"] = v }
        if let v = r.durationMs { o["durationMs"] = v }
        return o
    }

    private func pickSnapshot(_ p: PickRecord) -> [String: Any] {
        var o: [String: Any] = ["pickId": p.id, "state": p.state, "source": p.source,
                                "files": p.fileIds.compactMap { files[$0] }.map(fileSnapshot)]
        if let r = p.reason { o["reason"] = r }
        return o
    }

    private func jobSnapshot(_ j: TranscodeJob) -> [String: Any] {
        var o: [String: Any] = ["jobId": j.id, "kind": "transcode", "state": j.state, "progress": j.progress,
                                "fileId": j.sourceId, "outputFileId": j.outputId, "width": j.target.width,
                                "height": j.target.height, "bitrateKbps": j.target.bitrateKbps, "codec": j.codec]
        if let r = j.reason { o["reason"] = r }
        return o
    }

    private func uploadSnapshot(_ u: MPUpload) -> [String: Any] {
        var o: [String: Any] = ["jobId": u.id, "kind": "upload", "state": u.state, "fileId": u.fileId, "bytes": u.bytes,
                                "attempts": u.attempts, "method": u.method]
        if let r = u.reason { o["reason"] = r }
        if let s = u.httpStatus { o["httpStatus"] = s }
        if let n = u.nextAttemptAt { o["nextAttemptMs"] = Int(n.timeIntervalSince1970 * 1000) }
        if !store.durable { o["durable"] = false }
        return o
    }

    private func portChanged(_ p: UInt16?) {
        guard p != port else { return }
        port = p
        var o: [String: Any] = ["running": p != nil]
        if let b = base() { o["base"] = b }
        ctx.emit("server", o)
    }

    private static func color(_ hex: String) -> UIColor? {
        let s = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        guard s.count == 6, let v = UInt32(s, radix: 16) else { return nil }
        return UIColor(red: CGFloat((v >> 16) & 0xff) / 255, green: CGFloat((v >> 8) & 0xff) / 255,
                       blue: CGFloat(v & 0xff) / 255, alpha: 1)
    }

    // MARK: Background execution (finite, never guaranteed)

    private func beginAssertion() {
        guard assertion == .invalid else { return }
        assertion = UIApplication.shared.beginBackgroundTask(withName: "nativeext.mediapicker.upload") { [weak self] in
            guard let self = self else { return }
            self.uploader.expireInFlight()
            if self.uploader.hasWork { self.scheduleProcessing() }
            self.endAssertion()
        }
    }

    private func endAssertion() {
        guard assertion != .invalid else { return }
        let a = assertion
        assertion = .invalid
        UIApplication.shared.endBackgroundTask(a)
    }

    /// A transcode keeps running while iOS grants background time; when it ends, the transcode is
    /// cancelled and reported (reason background_expired) rather than suspended half-written.
    private func beginTranscodeAssertion() {
        guard transcodeAssertion == .invalid else { return }
        transcodeAssertion = UIApplication.shared.beginBackgroundTask(withName: "nativeext.mediapicker.transcode") { [weak self] in
            guard let self = self else { return }
            if let id = self.runningTranscode, var j = self.transcodes[id] {
                j.state = "failed"
                j.reason = "background_expired"
                self.transcodes[id] = j
                self.transcoder?.cancel()
            }
            self.endTranscodeAssertion()
        }
    }

    private func endTranscodeAssertion() {
        guard transcodeAssertion != .invalid else { return }
        let a = transcodeAssertion
        transcodeAssertion = .invalid
        UIApplication.shared.endBackgroundTask(a)
    }

    private func scheduleProcessing() {
        let request = BGProcessingTaskRequest(identifier: "nativeext.\(ctx.extensionId).upload")
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            ctx.log("background processing request not accepted: \(error.localizedDescription)")
        }
    }
}

private extension MPIncoming {
    var isCamera: Bool {
        switch self {
        case .photo, .movie: return true
        default: return false
        }
    }
}
