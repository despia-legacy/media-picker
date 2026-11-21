package despia.ext.mediapicker;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.FrameDropEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.AudioEncoderSettings;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import com.google.common.collect.ImmutableList;

import java.io.File;

/**
 * One video transcode with Media3 Transformer: scaled to the target size (never up), H.264 or HEVC
 * at the target bitrate, optional frame-rate cap, AAC audio at the given bitrate or no audio.
 * HDR is kept for HEVC when the device can encode it and tone-mapped to SDR for H.264 (OpenGL).
 *
 * <p>Version: media3-transformer 1.10.1, the version the base app already resolves for
 * media3-exoplayer (app/build.gradle). The latest stable is 1.11.1 (developer.android.com/jetpack/
 * androidx/releases/media3, read 2026-11-20); an extension may not change the media3 version the
 * base app resolves, so this follows when the base app moves every media3 artifact to 1.11.1.
 *
 * <p>Main thread only: Transformer runs on the thread that built it; progress is polled.
 */
@UnstableApi
final class Transcoder {
    interface Listener {
        void progress(double fraction);

        /** {@code reason} null on success. */
        void done(String reason, int width, int height, long durationMs);
    }

    static final long POLL_MS = 500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Transformer transformer;
    private boolean finished;
    private boolean cancelled;
    private Listener listener;

    /** Width, height (as displayed), source kbps, fps and duration in ms; null when unreadable. */
    static double[] probe(Context c, File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            int w = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rot = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (rot == 90 || rot == 270) {
                int t = w;
                w = h;
                h = t;
            }
            long kbps = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)) / 1000L;
            long dur = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            double fps = 30;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                String frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
                if (frames != null) {
                    try {
                        fps = Double.parseDouble(frames);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (w <= 0 || h <= 0) return null;
            return new double[]{w, h, kbps, fps, dur};
        } catch (RuntimeException e) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static int parse(String s) {
        if (s == null) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Whether any hardware or software encoder of the codec takes this size. */
    static boolean supports(String codec, int width, int height) {
        String mime = "hevc".equals(codec) ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String t : info.getSupportedTypes()) {
                if (!t.equalsIgnoreCase(mime)) continue;
                MediaCodecInfo.VideoCapabilities v = info.getCapabilitiesForType(t).getVideoCapabilities();
                if (v != null && (v.isSizeSupported(width, height) || v.isSizeSupported(height, width))) return true;
            }
        }
        return false;
    }

    void start(Context c, File source, File output, int[] target, String codec, int frameRate, boolean keepAudio,
               int audioKbps, Listener l) {
        listener = l;
        int width = target[0], height = target[1], kbps = target[2];
        boolean hevc = "hevc".equals(codec);
        DefaultEncoderFactory encoders = new DefaultEncoderFactory.Builder(c.getApplicationContext())
                .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate(kbps * 1000).build())
                .setRequestedAudioEncoderSettings(new AudioEncoderSettings.Builder().setBitrate(audioKbps * 1000).build())
                .build();
        transformer = new Transformer.Builder(c.getApplicationContext())
                .setVideoMimeType(hevc ? MimeTypes.VIDEO_H265 : MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoders)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult result) {
                        finish(l, null, width, height, result.durationMs);
                    }

                    @Override
                    public void onError(Composition composition, ExportResult result, ExportException e) {
                        output.delete();
                        finish(l, cancelled ? "cancelled" : reason(e), 0, 0, 0);
                    }
                })
                .build();
        ImmutableList.Builder<androidx.media3.common.Effect> video = ImmutableList.builder();
        if (frameRate > 0) video.add(FrameDropEffect.createDefaultFrameDropEffect(frameRate));
        video.add(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT));
        EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                .setRemoveAudio(!keepAudio)
                .setEffects(new Effects(ImmutableList.of(), video.build()))
                .build();
        Composition composition = new Composition.Builder(new EditedMediaItemSequence.Builder(item).build())
                .setHdrMode(hevc ? Composition.HDR_MODE_KEEP_HDR : Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build();
        transformer.start(composition, output.getAbsolutePath());
        poll(l);
    }

    private void poll(Listener l) {
        main.postDelayed(() -> {
            if (finished || transformer == null) return;
            ProgressHolder p = new ProgressHolder();
            if (transformer.getProgress(p) == Transformer.PROGRESS_STATE_AVAILABLE) l.progress(Math.min(0.99, p.progress / 100.0));
            poll(l);
        }, POLL_MS);
    }

    void cancel() {
        if (finished || transformer == null) return;
        cancelled = true;
        transformer.cancel();
        // Transformer.cancel() calls no listener: settle here.
        finish(null, "cancelled", 0, 0, 0);
    }

    private void finish(Listener l, String reason, int w, int h, long durationMs) {
        if (finished) return;
        finished = true;
        Listener to = l != null ? l : listener;
        if (to != null) to.done(reason, w, h, durationMs);
    }

    private static String reason(ExportException e) {
        int code = e.errorCode;
        if (code == ExportException.ERROR_CODE_ENCODER_INIT_FAILED || code == ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED) {
            return "not_supported_on_device";
        }
        if (code == ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED || code == ExportException.ERROR_CODE_IO_FILE_NOT_FOUND) {
            return "unreadable";
        }
        return "encode_failed";
    }
}
