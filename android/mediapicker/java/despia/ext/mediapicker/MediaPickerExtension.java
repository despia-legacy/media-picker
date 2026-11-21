package despia.ext.mediapicker;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.despia.nativeext.ExtCall;
import com.despia.nativeext.ExtContext;
import com.despia.nativeext.ExtResult;
import com.despia.nativeext.NativeExtension;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native picker for every kind of file, native video transcoding and durable upload; the Android
 * twin of MediaPickerExtension.swift, with the same routes, events, records and reasons.
 *
 * <p>Pickers, all the platform's own: the system Photo Picker (PickVisualMedia and
 * PickMultipleVisualMedia; no media permission needed, backported by Google Play system updates and
 * falling back to ACTION_OPEN_DOCUMENT where absent), ACTION_OPEN_DOCUMENT (OpenDocument and
 * OpenMultipleDocuments) for files and audio, and the system camera app (TakePicture and
 * CaptureVideo, writing to a CaptureProvider URI). {@code source: auto} shows a stock Material
 * dialog offering the three. Launchers are registered on CREATED under {@link ExtContext#resultKey}.
 *
 * <p>Picked files are copied into files/nativeext/mediapicker/files/&lt;fileId&gt;/ and served on a
 * loopback URL (MediaServer); no byte crosses the bridge. Everything runs on the main thread except
 * the copies, which run on {@link #io} and come back to it.
 */
@UnstableApi
public final class MediaPickerExtension implements NativeExtension {
    private static volatile ExtContext policy;
    private static volatile MediaPickerExtension instance;

    /** The declared route table, available from onCreate (application_create) in every process. */
    static ExtContext policy() {
        return policy;
    }

    private ExtContext ctx;
    private Context app;
    private File filesRoot;
    private MediaServer server;
    private int port = -1;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "nx-mediapicker-io"));
    private final SecureRandom random = new SecureRandom();

    private final Map<String, JSONObject> files = new LinkedHashMap<>();
    private final Map<String, JSONObject> picks = new LinkedHashMap<>();
    private final Map<String, JSONObject> transcodes = new LinkedHashMap<>();
    /** fileId → token; the token is never shown except inside the file's URL. */
    private final Map<String, String> tokens = new java.util.HashMap<>();
    private Transcoder transcoder;
    private String runningTranscode;

    private ComponentActivity registeredOn;
    private ComponentActivity resumed;
    private ActivityResultLauncher<PickVisualMediaRequest> pickOne;
    private ActivityResultLauncher<PickVisualMediaRequest> pickMany;
    private ActivityResultLauncher<String[]> openOne;
    private ActivityResultLauncher<String[]> openMany;
    private ActivityResultLauncher<Uri> takePicture;
    private ActivityResultLauncher<Uri> captureVideo;
    private ActivityResultLauncher<String> cameraPermission;

    /** The pick waiting for its picker; one at a time, as on iOS. */
    private Pending pending;

    private static final class Pending {
        String pickId;
        String source;
        MediaCore.Types types;
        int limit;
        String cameraMode;
        int maxDurationS;
        String quality;
        File capture;
        String captureMime;
        ExtCall call;
        ExtResult.Callback reply;
        /** Set while a camera chosen from the sheet waits for the CAMERA grant: true for video. */
        Boolean sheetCamera;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate(ExtContext context) {
        ctx = context;
        policy = context;
        instance = this;
        app = context.app();
        filesRoot = MediaStore.filesRoot(app);
        server = new MediaServer(this::portChanged);
        pruneSettledUploads();
        cleanUpAtLaunch();
        readoptUploadFiles();
        reconcileUploads();
    }

    @Override
    public void onActivityEvent(Activity activity, ActivityEvent event) {
        if (!(activity instanceof ComponentActivity)) return;
        ComponentActivity a = (ComponentActivity) activity;
        switch (event) {
            case CREATED:
                register(a);
                break;
            case RESUMED:
                resumed = a;
                server.ensureRunning();
                break;
            case PAUSED:
                if (resumed == a) resumed = null;
                break;
            case DESTROYED:
                if (registeredOn == a) {
                    unregister();
                    // Recreated (configuration, or the system reclaimed it): the answer reaches the
                    // launchers the new activity registers under the same keys, so the pick waits.
                    // Finishing: the answer can no longer arrive; the pick ends, never left presenting.
                    if (pending != null && a.isFinishing()) {
                        failPick(pending, "activity_destroyed");
                        pending = null;
                    }
                }
                break;
            default:
                break;
        }
    }

    private void register(ComponentActivity a) {
        unregister();
        registeredOn = a;
        androidx.activity.result.ActivityResultRegistry reg = a.getActivityResultRegistry();
        pickOne = reg.register(ctx.resultKey("pick_one"), new PickVisualMedia(),
                uri -> picked(uri == null ? Collections.emptyList() : Collections.singletonList(uri), "pick"));
        pickMany = reg.register(ctx.resultKey("pick_many"), new ActivityResultContracts.PickMultipleVisualMedia(),
                uris -> picked(uris, "pick"));
        openOne = reg.register(ctx.resultKey("open_one"), new ActivityResultContracts.OpenDocument(),
                uri -> picked(uri == null ? Collections.emptyList() : Collections.singletonList(uri), "pick"));
        openMany = reg.register(ctx.resultKey("open_many"), new ActivityResultContracts.OpenMultipleDocuments(),
                uris -> picked(uris, "pick"));
        takePicture = reg.register(ctx.resultKey("take_picture"), new ActivityResultContracts.TakePicture(), this::captured);
        captureVideo = reg.register(ctx.resultKey("capture_video"), new ActivityResultContracts.CaptureVideo() {
            @NonNull
            @Override
            public Intent createIntent(@NonNull Context c, @NonNull Uri input) {
                Intent i = super.createIntent(c, input);
                Pending p = pending;
                if (p != null && p.maxDurationS > 0) i.putExtra(android.provider.MediaStore.EXTRA_DURATION_LIMIT, p.maxDurationS);
                // EXTRA_VIDEO_QUALITY takes 0 (low) or 1 (high); medium has no platform value and maps to high.
                if (p != null) i.putExtra(android.provider.MediaStore.EXTRA_VIDEO_QUALITY, "low".equals(p.quality) ? 0 : 1);
                return i;
            }
        }, this::captured);
        cameraPermission = reg.register(ctx.resultKey("camera_permission"), new ActivityResultContracts.RequestPermission(), granted -> {
            Pending p = pending;
            if (p != null && p.sheetCamera != null) {
                // The camera was chosen from the sheet: the pick was already answered, so the
                // outcome is the pick's own end.
                boolean video = p.sheetCamera;
                p.sheetCamera = null;
                if (!granted) {
                    pending = null;
                    endPick(p.pickId, "failed", "permission_denied");
                    return;
                }
                startCamera(p, video);
                return;
            }
            if (p == null || p.reply == null) return;
            if (!p.call.isActive()) {
                // The route already timed out: its answer was sent; the pick never existed for the page.
                pending = null;
                picks.remove(p.pickId);
                return;
            }
            if (!granted) {
                pending = null;
                p.reply.reply(ExtResult.error("permission_denied", "camera access was denied"));
                return;
            }
            present(p);
        });
    }

    private void unregister() {
        for (ActivityResultLauncher<?> l : Arrays.asList(pickOne, pickMany, openOne, openMany, takePicture, captureVideo, cameraPermission)) {
            if (l != null) l.unregister();
        }
        pickOne = pickMany = null;
        openOne = openMany = null;
        takePicture = captureVideo = null;
        cameraPermission = null;
        registeredOn = null;
    }

    // ------------------------------------------------------------------ routes

    @Override
    public void handle(ExtCall call, ExtResult.Callback reply) {
        Map<String, Object> p = call.params;
        switch (call.route) {
            case "pick":
                pick(call, reply);
                return;
            case "result":
                reply.reply(result(str(p, "pick", "")));
                return;
            case "files":
                reply.reply(listFiles(str(p, "id", null)));
                return;
            case "release":
                reply.reply(release(str(p, "id", null), bool(p, "all", false)));
                return;
            case "transcode":
                transcode(call, reply);
                return;
            case "upload":
                reply.reply(upload(p));
                return;
            case "jobs":
                reply.reply(jobs(str(p, "id", null)));
                return;
            case "cancel":
                reply.reply(cancel(str(p, "id", "")));
                return;
            default:
                reply.reply(ExtResult.error("not_registered", call.route));
        }
    }

    private static String str(Map<String, Object> p, String k, String d) {
        Object v = p.get(k);
        return v instanceof String ? (String) v : d;
    }

    private static int num(Map<String, Object> p, String k, int d) {
        Object v = p.get(k);
        return v instanceof Number ? ((Number) v).intValue() : d;
    }

    private static boolean bool(Map<String, Object> p, String k, boolean d) {
        Object v = p.get(k);
        return v instanceof Boolean ? (Boolean) v : d;
    }

    private void pick(ExtCall call, ExtResult.Callback reply) {
        Map<String, Object> p = call.params;
        if (pending != null) {
            reply.reply(ExtResult.error("busy", "a picker is already open"));
            return;
        }
        MediaCore.Types types = MediaCore.parseTypes(str(p, "types", null));
        if (types == null) {
            reply.reply(ExtResult.error("bad_value", "types is a comma list of image, video, audio, document, any and MIME types"));
            return;
        }
        String source = MediaCore.source(str(p, "source", "auto"), types);
        if (source == null) {
            reply.reply(ExtResult.error("bad_value", "that source cannot offer these types"));
            return;
        }
        String cameraMode = MediaCore.cameraMode(types, str(p, "camera", "both"));
        boolean hasCamera = app.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        if ("camera".equals(source)) {
            if (!hasCamera) {
                reply.reply(ExtResult.error("camera_unavailable", "this device has no camera"));
                return;
            }
            if (cameraMode == null) {
                reply.reply(ExtResult.error("bad_value", "camera does not match types"));
                return;
            }
        }
        if (registeredOn == null || resumed == null) {
            reply.reply(ExtResult.error("unavailable", "the app is not in the foreground"));
            return;
        }
        int platformMax = 0;
        if ("library".equals(source) && Build.VERSION.SDK_INT >= 33) platformMax = android.provider.MediaStore.getPickImagesMaxLimit();
        int[] limit = MediaCore.limit(num(p, "limit", 0), bool(p, "multiple", false), platformMax);
        Pending pd = new Pending();
        pd.pickId = UUID.randomUUID().toString();
        pd.source = source;
        pd.types = types;
        pd.limit = limit[0];
        pd.cameraMode = hasCamera ? cameraMode : null;
        pd.maxDurationS = num(p, "max_duration_s", 0);
        pd.quality = str(p, "camera_quality", "high");
        JSONObject rec = new JSONObject();
        put(rec, "pickId", pd.pickId);
        put(rec, "state", "presenting");
        put(rec, "source", source);
        put(rec, "maxFileMB", num(p, "max_file_mb", 0));
        put(rec, "maxTotalMB", num(p, "max_total_mb", 0));
        put(rec, "limit", pd.limit);
        put(rec, "fileIds", new JSONArray());
        put(rec, "representation", str(p, "representation", "current"));
        put(rec, "ordered", bool(p, "ordered", false));
        put(rec, "tab", str(p, "tab", "photos"));
        String accent = str(p, "accent_color", null);
        if (accent != null) {
            Long c = color(accent);
            if (c == null) {
                reply.reply(ExtResult.error("bad_value", "accent_color is #RRGGBB"));
                return;
            }
            put(rec, "accent", c);
        }
        picks.put(pd.pickId, rec);
        JSONObject out = new JSONObject();
        put(out, "pickId", pd.pickId);
        put(out, "state", "presenting");
        put(out, "source", source);
        put(out, "limit", pd.limit);
        put(out, "limitLowered", limit[1] == 1);
        if ("camera".equals(source) && needsCameraPermission()) {
            // Answered when the permission is settled: allowed → presented; denied → error.
            pd.call = call;
            pd.reply = (ExtResult r) -> {
                if (!r.isOk()) picks.remove(pd.pickId);
                reply.reply(r.isOk() ? ExtResult.ok(out) : r);
            };
            pending = pd;
            cameraPermission.launch(Manifest.permission.CAMERA);
            return;
        }
        pending = pd;
        if (!present(pd)) {
            pending = null;
            picks.remove(pd.pickId);
            reply.reply(ExtResult.error("unavailable", "the picker could not be shown"));
            return;
        }
        reply.reply(ExtResult.ok(out));
    }

    /**
     * When the app declares CAMERA (requires.permissions: the dashboard owns it), the system camera
     * intent is refused until it is granted (MediaStore.ACTION_IMAGE_CAPTURE reference), so it is asked
     * first; an app that does not declare it needs nothing.
     */
    private boolean needsCameraPermission() {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return false;
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), PackageManager.GET_PERMISSIONS);
            return info.requestedPermissions != null && Arrays.asList(info.requestedPermissions).contains(Manifest.permission.CAMERA);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Shows the picker for the pending pick; false when nothing could be shown. */
    private boolean present(Pending pd) {
        if (pd.reply != null) {
            ExtResult.Callback r = pd.reply;
            pd.reply = null;
            pd.call = null;
            boolean ok = launch(pd);
            r.reply(ok ? ExtResult.ok(null) : ExtResult.error("unavailable", "the picker could not be shown"));
            if (!ok) pending = null;
            return ok;
        }
        return launch(pd);
    }

    private boolean launch(Pending pd) {
        try {
            switch (pd.source) {
                case "library":
                    library(pd);
                    return true;
                case "files":
                    documents(pd);
                    return true;
                case "camera":
                    if ("both".equals(pd.cameraMode)) {
                        sheet(pd, false);
                    } else {
                        camera(pd, "video".equals(pd.cameraMode));
                    }
                    return true;
                default:
                    sheet(pd, true);
                    return true;
            }
        } catch (RuntimeException e) {
            ctx.log("picker not shown: " + e);
            return false;
        }
    }

    private void library(Pending pd) {
        JSONObject rec = picks.get(pd.pickId);
        PickVisualMedia.VisualMediaType type = visualType(pd.types);
        PickVisualMediaRequest.Builder b = new PickVisualMediaRequest.Builder().setMediaType(type);
        if (pd.limit != 1 && pd.limit >= 2) b.setMaxItems(pd.limit);
        if (rec != null) {
            if (rec.optBoolean("ordered") && pd.limit != 1) b.setOrderedSelection(true);
            b.setDefaultTab("albums".equals(rec.optString("tab")) ? PickVisualMedia.DefaultTab.AlbumsTab.INSTANCE
                    : PickVisualMedia.DefaultTab.PhotosTab.INSTANCE);
            if (rec.has("accent")) b.setAccentColor(rec.optLong("accent"));
            // compatible: declare no HDR capability, so the picker hands over an SDR transcode of HDR
            // video (API 33+, where the picker supports it); current/automatic: the original file.
            if ("compatible".equals(rec.optString("representation")) && Build.VERSION.SDK_INT >= 33) {
                b.setMediaCapabilitiesForTranscoding(new PickVisualMedia.MediaCapabilities.Builder().build());
            }
        }
        if (pd.limit == 1) pickOne.launch(b.build()); else pickMany.launch(b.build());
    }

    static PickVisualMedia.VisualMediaType visualType(MediaCore.Types t) {
        boolean img = t.wantsImages(), vid = t.wantsVideos();
        if (!t.isAny() && t.mimes.size() == 1 && t.categories.isEmpty() && !t.mimes.get(0).endsWith("/*")) {
            return new PickVisualMedia.SingleMimeType(t.mimes.get(0));
        }
        if (img && !vid) return PickVisualMedia.ImageOnly.INSTANCE;
        if (vid && !img) return PickVisualMedia.VideoOnly.INSTANCE;
        return PickVisualMedia.ImageAndVideo.INSTANCE;
    }

    /** ACTION_OPEN_DOCUMENT MIME filters for the types; the twin of iOS contentTypes. */
    static String[] documentTypes(MediaCore.Types t) {
        if (t.isAny() || t.categories.contains("document")) return new String[]{"*/*"};
        List<String> out = new ArrayList<>();
        if (t.categories.contains("image")) out.add("image/*");
        if (t.categories.contains("video")) out.add("video/*");
        if (t.categories.contains("audio")) out.add("audio/*");
        out.addAll(t.mimes);
        return out.isEmpty() ? new String[]{"*/*"} : out.toArray(new String[0]);
    }

    private void documents(Pending pd) {
        String[] mimes = documentTypes(pd.types);
        if (pd.limit == 1) openOne.launch(mimes); else openMany.launch(mimes);
    }

    private void camera(Pending pd, boolean video) {
        File dir = new File(MediaStore.dir(app), "capture");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String ext = video ? ".mp4" : ".jpg";
        File f = new File(dir, UUID.randomUUID() + ext);
        pd.capture = f;
        pd.captureMime = video ? "video/mp4" : "image/jpeg";
        Uri uri = FileProvider.getUriForFile(app, app.getPackageName() + ".nx.mediapicker.capture", f);
        if (video) captureVideo.launch(uri); else takePicture.launch(uri);
    }

    /**
     * A camera chosen from the sheet (source auto, or camera with mode both) gets the same CAMERA
     * check as source camera: an app that declares CAMERA cannot start the capture intents until
     * it is granted, and they would throw inside the dialog's click listener.
     */
    private void cameraFromSheet(Pending pd, boolean video) {
        if (needsCameraPermission()) {
            pd.sheetCamera = video;
            try {
                cameraPermission.launch(Manifest.permission.CAMERA);
            } catch (RuntimeException e) {
                pd.sheetCamera = null;
                ctx.log("camera permission not asked: " + e);
                if (pending == pd) pending = null;
                endPick(pd.pickId, "failed", "camera_unavailable");
            }
            return;
        }
        startCamera(pd, video);
    }

    /** Starts a capture after the dialog or the permission answer; a refusal ends the pick. */
    private void startCamera(Pending pd, boolean video) {
        try {
            camera(pd, video);
        } catch (RuntimeException e) {
            ctx.log("camera not started: " + e);
            if (pending == pd) pending = null;
            endPick(pd.pickId, "failed", "camera_unavailable");
        }
    }

    /** Stock Material dialog listing the sources this pick can use; Cancel ends the pick. */
    private void sheet(Pending pd, boolean withLibraryAndFiles) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        boolean visual = pd.types.wantsImages() || pd.types.wantsVideos();
        if (withLibraryAndFiles && visual) {
            labels.add(app.getString(R.string.nx_mediapicker_photo_library));
            actions.add(() -> library(pd));
        }
        if (pd.cameraMode != null && (visual || !withLibraryAndFiles)) {
            if (!"video".equals(pd.cameraMode)) {
                labels.add(app.getString(R.string.nx_mediapicker_camera_photo));
                actions.add(() -> cameraFromSheet(pd, false));
            }
            if (!"photo".equals(pd.cameraMode)) {
                labels.add(app.getString(R.string.nx_mediapicker_camera_video));
                actions.add(() -> cameraFromSheet(pd, true));
            }
        }
        if (withLibraryAndFiles) {
            labels.add(app.getString(R.string.nx_mediapicker_files));
            actions.add(() -> documents(pd));
        }
        final boolean[] chosen = {false};
        new MaterialAlertDialogBuilder(resumed)
                .setTitle(R.string.nx_mediapicker_choose)
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                    chosen[0] = true;
                    actions.get(which).run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> {
                    if (!chosen[0] && pending == pd) {
                        pending = null;
                        endPick(pd.pickId, "cancelled", null);
                    }
                })
                .show();
    }

    private void captured(Boolean ok) {
        Pending pd = pending;
        if (pd == null || pd.capture == null) return;
        pending = null;
        File f = pd.capture;
        if (ok == null || !ok || !f.isFile() || f.length() == 0) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            endPick(pd.pickId, "cancelled", null);
            return;
        }
        accept(pd, Collections.singletonList(new Incoming(null, f, pd.captureMime)), "camera");
    }

    private void picked(List<Uri> uris, String origin) {
        Pending pd = pending;
        if (pd == null) return;
        pending = null;
        if (uris == null || uris.isEmpty()) {
            endPick(pd.pickId, "cancelled", null);
            return;
        }
        List<Incoming> in = new ArrayList<>();
        for (Uri u : uris) in.add(new Incoming(u, null, null));
        accept(pd, in, origin);
    }

    private static final class Incoming {
        final Uri uri;
        final File file;
        final String mime;

        Incoming(Uri uri, File file, String mime) {
            this.uri = uri;
            this.file = file;
            this.mime = mime;
        }
    }

    private void accept(Pending pd, List<Incoming> items, String origin) {
        JSONObject pick = picks.get(pd.pickId);
        if (pick == null) return;
        JSONArray ids = pick.optJSONArray("fileIds");
        List<Incoming> accepted = items;
        int over = 0;
        // The document picker on Android has no limit of its own: the rest fail limit_exceeded.
        if (pd.limit > 0 && items.size() > pd.limit) {
            accepted = items.subList(0, pd.limit);
            over = items.size() - pd.limit;
        }
        for (Incoming i : accepted) {
            JSONObject f = newFile(pd.pickId, origin, "file", "application/octet-stream");
            files.put(f.optString("fileId"), f);
            ids.put(f.optString("fileId"));
            importItem(i, f.optString("fileId"));
        }
        for (int k = 0; k < over; k++) {
            JSONObject f = newFile(pd.pickId, origin, "file", "application/octet-stream");
            put(f, "state", "failed");
            put(f, "reason", "limit_exceeded");
            files.put(f.optString("fileId"), f);
            ids.put(f.optString("fileId"));
        }
        put(pick, "state", "loading");
        ctx.emit("pick", pickSnapshot(pick));
        settlePickIfDone(pd.pickId);
    }

    private JSONObject newFile(String pickId, String origin, String name, String mime) {
        JSONObject f = new JSONObject();
        String id = UUID.randomUUID().toString();
        byte[] b = new byte[32];
        random.nextBytes(b);
        tokens.put(id, MediaCore.token(b));
        put(f, "fileId", id);
        if (pickId != null) put(f, "pickId", pickId);
        put(f, "origin", origin);
        put(f, "name", name);
        put(f, "mime", mime);
        put(f, "kind", MediaCore.kind(mime));
        put(f, "bytes", 0L);
        put(f, "state", "loading");
        return f;
    }

    private File dir(String id) {
        return new File(filesRoot, id);
    }

    /** Copies one item into its own directory on {@link #io}; the result comes back on main. */
    private void importItem(Incoming item, String id) {
        File d = dir(id);
        io.execute(() -> {
            String name, mime, error = null;
            File dest = null;
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
            ContentResolver cr = app.getContentResolver();
            if (item.file != null) {
                mime = item.mime;
                name = MediaCore.safeName(item.file.getName(), "");
                dest = new File(d, name);
                if (!item.file.renameTo(dest)) error = "import_failed";
            } else {
                String type = cr.getType(item.uri);
                mime = type == null ? "application/octet-stream" : type.toLowerCase(Locale.ROOT);
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                String raw = displayName(cr, item.uri);
                name = MediaCore.safeName(raw == null ? "file" : raw, ext == null ? "" : "." + ext);
                dest = new File(d, name);
                try (InputStream in = cr.openInputStream(item.uri); OutputStream out = new FileOutputStream(dest)) {
                    if (in == null) throw new IOException("no stream");
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } catch (IOException | SecurityException e) {
                    error = "import_failed";
                }
            }
            // Size and duration are read here, off the main thread: MediaMetadataRetriever and the
            // bounds decode can take long on a large file.
            JSONObject meta = new JSONObject();
            if (error == null) {
                String kind = MediaCore.kind(mime);
                put(meta, "kind", kind);
                if ("image".equals(kind)) imageSize(dest, meta);
                if ("video".equals(kind) || "audio".equals(kind)) mediaInfo(dest, meta);
            }
            final String fName = name, fMime = mime, fError = error;
            final File fDest = dest;
            main.post(() -> imported(id, fDest, fName, fMime, fError, meta));
        });
    }

    private static String displayName(ContentResolver cr, Uri u) {
        try (Cursor c = cr.query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        return u.getLastPathSegment();
    }

    private void imported(String id, File dest, String name, String mime, String error, JSONObject meta) {
        JSONObject f = files.get(id);
        if (f == null || !"loading".equals(f.optString("state"))) {
            // Released while loading: whatever arrived goes.
            deleteTree(dir(id));
            return;
        }
        if (error != null) {
            put(f, "state", "failed");
            put(f, "reason", error);
            deleteTree(dir(id));
        } else {
            put(f, "name", name);
            put(f, "mime", mime);
            put(f, "kind", MediaCore.kind(mime));
            put(f, "bytes", dest.length());
            JSONObject pick = picks.get(f.optString("pickId"));
            int maxFile = pick == null ? 0 : pick.optInt("maxFileMB");
            String verdict = MediaCore.admit(Collections.singletonList(dest.length()), maxFile, 0).get(0);
            if (verdict != null) {
                put(f, "state", "failed");
                put(f, "reason", verdict);
                deleteTree(dir(id));
            } else {
                boolean holdForTotal = pick != null && pick.optInt("maxTotalMB") > 0;
                put(f, "state", holdForTotal ? "loaded" : "ready");
                for (String k : new String[]{"width", "height", "durationMs"}) if (meta.has(k)) put(f, k, meta.opt(k));
                if (!holdForTotal) server.publish(tokens.get(id), new MediaServer.Entry(dest, mime));
            }
        }
        if (!"loaded".equals(f.optString("state"))) ctx.emit("file", fileSnapshot(f));
        String pid = f.optString("pickId", null);
        if (pid != null) settlePickIfDone(pid);
    }

    private void settlePickIfDone(String pickId) {
        JSONObject pick = picks.get(pickId);
        if (pick == null || !"loading".equals(pick.optString("state"))) return;
        JSONArray ids = pick.optJSONArray("fileIds");
        List<JSONObject> held = new ArrayList<>();
        for (int i = 0; i < ids.length(); i++) {
            JSONObject f = files.get(ids.optString(i));
            if (f == null) continue;
            if ("loading".equals(f.optString("state"))) return;
            if ("loaded".equals(f.optString("state"))) held.add(f);
        }
        if (pick.optInt("maxTotalMB") > 0) {
            List<Long> sizes = new ArrayList<>();
            for (JSONObject f : held) sizes.add(f.optLong("bytes"));
            List<String> verdicts = MediaCore.admit(sizes, 0, pick.optInt("maxTotalMB"));
            for (int i = 0; i < held.size(); i++) {
                JSONObject f = held.get(i);
                String id = f.optString("fileId");
                if (verdicts.get(i) != null) {
                    put(f, "state", "failed");
                    put(f, "reason", verdicts.get(i));
                    deleteTree(dir(id));
                } else {
                    put(f, "state", "ready");
                    server.publish(tokens.get(id), new MediaServer.Entry(new File(dir(id), f.optString("name")), f.optString("mime")));
                }
                ctx.emit("file", fileSnapshot(f));
            }
        }
        put(pick, "state", "done");
        ctx.emit("pick", pickSnapshot(pick));
    }

    private void endPick(String pickId, String state, String reason) {
        JSONObject pick = picks.get(pickId);
        if (pick == null) return;
        put(pick, "state", state);
        if (reason != null) put(pick, "reason", reason);
        ctx.emit("pick", pickSnapshot(pick));
    }

    private void failPick(Pending pd, String reason) {
        if (pd.reply != null) {
            ExtResult.Callback r = pd.reply;
            pd.reply = null;
            r.reply(ExtResult.error("unavailable", "the activity ended before the camera was allowed"));
            return;
        }
        endPick(pd.pickId, "failed", reason);
    }

    private static void imageSize(File f, JSONObject rec) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return;
        int w = o.outWidth, h = o.outHeight;
        try {
            int orientation = new ExifInterface(f.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            // EXIF 5 to 8 rotate by 90 degrees: the size as displayed, as on iOS.
            if (orientation >= 5 && orientation <= 8) {
                int t = w;
                w = h;
                h = t;
            }
        } catch (IOException ignored) {
        }
        put(rec, "width", w);
        put(rec, "height", h);
    }

    private static void mediaInfo(File f, JSONObject rec) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) put(rec, "durationMs", Long.parseLong(d.trim()));
        } catch (RuntimeException ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        if ("video".equals(rec.optString("kind"))) {
            double[] info = Transcoder.probe(null, f);
            if (info != null) {
                put(rec, "width", (int) info[0]);
                put(rec, "height", (int) info[1]);
            }
        }
    }

    private ExtResult result(String pickId) {
        JSONObject p = picks.get(pickId);
        return p == null ? ExtResult.error("not_found", "unknown pick") : ExtResult.ok(pickSnapshot(p));
    }

    private ExtResult listFiles(String id) {
        if (id != null) {
            JSONObject f = MediaCore.isId(id) ? files.get(id) : null;
            return f == null ? ExtResult.error("not_found", "unknown file") : ExtResult.ok(fileSnapshot(f));
        }
        JSONArray list = new JSONArray();
        for (JSONObject f : new TreeMap<>(files).values()) {
            if (!"released".equals(f.optString("state"))) list.put(fileSnapshot(f));
        }
        JSONObject out = new JSONObject();
        put(out, "files", list);
        if (port > 0) put(out, "base", base());
        return ExtResult.ok(out);
    }

    /** Whether a running transcode or an unfinished upload still needs this file. */
    private boolean inUse(String id) {
        for (JSONObject t : transcodes.values()) {
            String s = t.optString("state");
            if ((id.equals(t.optString("fileId")) || id.equals(t.optString("outputFileId")))
                    && ("queued".equals(s) || "transcoding".equals(s))) {
                return true;
            }
        }
        try {
            for (JSONObject u : MediaStore.records(app).values()) {
                if (id.equals(u.optString("fileId")) && "uploading".equals(u.optString("state"))) return true;
            }
        } catch (MediaStore.StorageException e) {
            // Unknown: kept, never deleted under an upload.
            return true;
        }
        return false;
    }

    private ExtResult release(String id, boolean all) {
        List<String> ids = new ArrayList<>();
        if (all) {
            for (JSONObject f : files.values()) {
                String fid = f.optString("fileId");
                if (!"released".equals(f.optString("state")) && !inUse(fid)) ids.add(fid);
            }
        } else {
            JSONObject f = id != null && MediaCore.isId(id) ? files.get(id) : null;
            if (f == null || "released".equals(f.optString("state"))) return ExtResult.error("not_found", "unknown file");
            if (inUse(id)) return ExtResult.error("busy", "a transcode or an upload still needs this file; cancel it first");
            ids.add(id);
        }
        Collections.sort(ids);
        int failed = 0;
        for (String fid : ids) if (!releaseFile(fid)) failed++;
        if (failed > 0) {
            return ExtResult.error("release_failed", failed + " file(s) could not be deleted; the next launch deletes them");
        }
        JSONObject out = new JSONObject();
        put(out, "released", new JSONArray(ids));
        return ExtResult.ok(out);
    }

    private boolean releaseFile(String id) {
        JSONObject f = files.get(id);
        if (f == null) return true;
        String token = tokens.get(id);
        if (token != null) server.withdraw(token);
        put(f, "state", "released");
        f.remove("reason");
        boolean ok = deleteTree(dir(id));
        ctx.emit("file", fileSnapshot(f));
        return ok;
    }

    private static boolean deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        return f.delete() || !f.exists();
    }

    // ------------------------------------------------------------------ transcode

    /** Checks the call on main, probes the source on {@link #io}, then answers from main. */
    private void transcode(ExtCall call, ExtResult.Callback reply) {
        Map<String, Object> p = call.params;
        String id = str(p, "id", "");
        JSONObject src = MediaCore.isId(id) ? files.get(id) : null;
        if (src == null) {
            reply.reply(ExtResult.error("not_found", "unknown file"));
            return;
        }
        if (!"ready".equals(src.optString("state")) || !"video".equals(src.optString("kind"))) {
            reply.reply(ExtResult.error("bad_value", "only a ready video can be transcoded"));
            return;
        }
        Integer bitrate = MediaCore.bitrate(str(p, "bitrate_kbps", null));
        if (bitrate == null) {
            reply.reply(ExtResult.error("bad_value", "bitrate_kbps is 100 to 400000"));
            return;
        }
        File source = new File(dir(id), src.optString("name"));
        io.execute(() -> {
            // MediaMetadataRetriever can take long on a large file: never on the main thread.
            double[] info = Transcoder.probe(app, source);
            main.post(() -> {
                if (!call.isActive()) return;
                reply.reply(transcodeProbed(p, id, info));
            });
        });
    }

    private ExtResult transcodeProbed(Map<String, Object> p, String id, double[] info) {
        JSONObject src = files.get(id);
        if (src == null || !"ready".equals(src.optString("state"))) return ExtResult.error("not_found", "the file was released");
        Integer bitrate = MediaCore.bitrate(str(p, "bitrate_kbps", null));
        String codec = str(p, "codec", "h264");
        String preset = str(p, "preset", "720p");
        int frameRate = num(p, "frame_rate", 0);
        if (info == null) return ExtResult.error("unreadable", "the video's tracks cannot be read");
        int[] target = MediaCore.target((int) info[0], (int) info[1], preset, codec, bitrate, (int) info[2],
                frameRate > 0 ? frameRate : info[3]);
        if (target == null) return ExtResult.error("bad_value", "unknown preset or a video without a size");
        if (!Transcoder.supports(codec, target[0], target[1])) {
            return ExtResult.error("not_supported_on_device", "this device cannot encode " + codec + " at " + target[0] + "x" + target[1]);
        }
        JSONObject out = newFile(null, "transcode", "video-" + UUID.randomUUID().toString().substring(0, 8) + ".mp4", "video/mp4");
        put(out, "progress", 0.0);
        files.put(out.optString("fileId"), out);
        JSONObject job = new JSONObject();
        put(job, "jobId", UUID.randomUUID().toString());
        put(job, "kind", "transcode");
        put(job, "state", "queued");
        put(job, "progress", 0.0);
        put(job, "fileId", id);
        put(job, "outputFileId", out.optString("fileId"));
        put(job, "width", target[0]);
        put(job, "height", target[1]);
        put(job, "bitrateKbps", target[2]);
        put(job, "codec", codec);
        put(job, "frameRate", frameRate);
        put(job, "keepAudio", "keep".equals(str(p, "audio", "keep")));
        put(job, "audioKbps", num(p, "audio_bitrate_kbps", 128));
        transcodes.put(job.optString("jobId"), job);
        nextTranscode();
        return ExtResult.ok(jobSnapshot(job));
    }

    private void nextTranscode() {
        if (runningTranscode != null) return;
        JSONObject job = null;
        for (JSONObject j : transcodes.values()) {
            if ("queued".equals(j.optString("state"))) {
                job = j;
                break;
            }
        }
        if (job == null) {
            TranscodeService.end(app);
            return;
        }
        JSONObject src = files.get(job.optString("fileId"));
        JSONObject out = files.get(job.optString("outputFileId"));
        if (src == null || out == null) {
            put(job, "state", "failed");
            put(job, "reason", "unreadable");
            nextTranscode();
            return;
        }
        String jobId = job.optString("jobId");
        File outDir = dir(out.optString("fileId"));
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
        put(job, "state", "transcoding");
        runningTranscode = jobId;
        transcoder = new Transcoder();
        TranscodeService.begin(app, 0);
        ctx.emit("job", jobSnapshot(job));
        final JSONObject j = job;
        transcoder.start(app, new File(dir(src.optString("fileId")), src.optString("name")), new File(outDir, out.optString("name")),
                new int[]{j.optInt("width"), j.optInt("height"), j.optInt("bitrateKbps")}, j.optString("codec"), j.optInt("frameRate"),
                j.optBoolean("keepAudio"), j.optInt("audioKbps"), new Transcoder.Listener() {
                    int lastPercent = -1;

                    @Override
                    public void progress(double fraction) {
                        if (!"transcoding".equals(j.optString("state"))) return;
                        put(j, "progress", fraction);
                        ctx.emit("job", jobSnapshot(j));
                        int pc = (int) (fraction * 100);
                        if (pc != lastPercent) {
                            lastPercent = pc;
                            TranscodeService.begin(app, pc);
                        }
                    }

                    @Override
                    public void done(String reason, int width, int height, long durationMs) {
                        transcodeDone(jobId, reason, width, height, durationMs);
                    }
                });
    }

    private void transcodeDone(String jobId, String reason, int width, int height, long durationMs) {
        runningTranscode = null;
        transcoder = null;
        JSONObject j = transcodes.get(jobId);
        if (j == null) {
            nextTranscode();
            return;
        }
        JSONObject out = files.get(j.optString("outputFileId"));
        if (reason == null) {
            put(j, "state", "done");
            put(j, "progress", 1.0);
            if (out != null && "loading".equals(out.optString("state"))) {
                File f = new File(dir(out.optString("fileId")), out.optString("name"));
                put(out, "state", "ready");
                out.remove("progress");
                put(out, "bytes", f.length());
                put(out, "width", width);
                put(out, "height", height);
                put(out, "durationMs", durationMs);
                server.publish(tokens.get(out.optString("fileId")), new MediaServer.Entry(f, "video/mp4"));
            }
        } else {
            // A cancel or the foreground time limit already set the state; keep it.
            String s = j.optString("state");
            if (!"cancelled".equals(s) && !"failed".equals(s)) put(j, "state", "cancelled".equals(reason) ? "cancelled" : "failed");
            if (!j.has("reason")) put(j, "reason", reason);
            if (out != null && "loading".equals(out.optString("state"))) {
                put(out, "state", "failed");
                put(out, "reason", j.optString("reason"));
                out.remove("progress");
                deleteTree(dir(out.optString("fileId")));
            }
        }
        if (out != null) ctx.emit("file", fileSnapshot(out));
        ctx.emit("job", jobSnapshot(j));
        nextTranscode();
    }

    /** TranscodeService.onTimeout: the platform's foreground time for media processing is used up. */
    static void transcodeTimedOut() {
        MediaPickerExtension e = instance;
        if (e == null) return;
        e.main.post(() -> {
            if (e.runningTranscode == null || e.transcoder == null) return;
            JSONObject j = e.transcodes.get(e.runningTranscode);
            if (j != null) {
                put(j, "state", "failed");
                put(j, "reason", "background_expired");
            }
            e.transcoder.cancel();
        });
    }

    // ------------------------------------------------------------------ upload

    private ExtResult upload(Map<String, Object> p) {
        String id = str(p, "id", "");
        JSONObject f = MediaCore.isId(id) ? files.get(id) : null;
        if (f == null) return ExtResult.error("not_found", "unknown file");
        if (!"ready".equals(f.optString("state"))) return ExtResult.error("bad_value", "only a ready file can be uploaded");
        String url = str(p, "url", null);
        if (url == null) return ExtResult.error("bad_request", "url");
        String field = str(p, "form_field", null);
        if (field != null && !MediaCore.formName(field)) return ExtResult.error("bad_value", "form_field is 1 to 64 of A-Z a-z 0-9 . _ -");
        String type = MediaCore.contentType(str(p, "content_type", null), f.optString("mime"));
        if (type == null) return ExtResult.error("bad_value", "content_type is a MIME type without parameters");
        JSONObject r = new JSONObject();
        String jobId = UUID.randomUUID().toString();
        String gen = UUID.randomUUID().toString();
        put(r, "jobId", jobId);
        put(r, "kind", "upload");
        put(r, "fileId", id);
        put(r, "path", id + "/" + f.optString("name"));
        put(r, "name", f.optString("name"));
        put(r, "mime", f.optString("mime"));
        put(r, "bytes", f.optLong("bytes"));
        put(r, "url", url);
        put(r, "route", "upload");
        put(r, "method", str(p, "method", field == null ? "PUT" : "POST"));
        if (field != null) put(r, "field", field);
        put(r, "contentType", type);
        put(r, "state", "uploading");
        put(r, "attempts", 0);
        put(r, "releaseAfter", bool(p, "release_after", false));
        put(r, "createdAt", System.currentTimeMillis());
        put(r, "gen", gen);
        // Durable before it is promised: nothing is queued when the index cannot be written.
        try {
            MediaStore.put(app, r);
        } catch (MediaStore.StorageException e) {
            return ExtResult.error("storage_failed", "the upload index could not be saved; nothing was queued");
        }
        UploadWorker.schedule(app, jobId, gen);
        return ExtResult.ok(uploadSnapshot(r));
    }

    /** From a worker thread or main: the settled record; the page hears it on main. */
    static void uploadChanged(JSONObject r) {
        MediaPickerExtension e = instance;
        if (e == null) return;
        e.main.post(() -> {
            if ("uploaded".equals(r.optString("state")) && r.optBoolean("releaseAfter")) {
                String fid = r.optString("fileId");
                if (e.files.containsKey(fid) && !e.inUse(fid)) e.releaseFile(fid);
            }
            e.ctx.emit("job", e.uploadSnapshot(r));
        });
    }

    static void uploadProgress(String id, long sent, long total) {
        MediaPickerExtension e = instance;
        if (e == null) return;
        e.main.post(() -> {
            JSONObject r;
            try {
                r = MediaStore.get(e.app, id);
            } catch (MediaStore.StorageException se) {
                return;
            }
            if (r == null || !"uploading".equals(r.optString("state"))) return;
            JSONObject s = e.uploadSnapshot(r);
            put(s, "sentBytes", sent);
            put(s, "progress", total > 0 ? Math.min(1.0, (double) sent / total) : 0.0);
            e.ctx.emit("job", s);
        });
    }

    private void pruneSettledUploads() {
        long cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        try {
            for (JSONObject r : MediaStore.records(app).values()) {
                if (!"uploading".equals(r.optString("state")) && r.optLong("createdAt") < cutoff) MediaStore.remove(app, r.optString("jobId"));
            }
        } catch (MediaStore.StorageException e) {
            ctx.log("upload index not pruned: " + e.getMessage());
        }
    }

    /** Everything under files/ goes, except the files an unfinished upload still needs; capture/ too. */
    private void cleanUpAtLaunch() {
        Set<String> keep = new HashSet<>();
        try {
            for (JSONObject r : MediaStore.records(app).values()) if ("uploading".equals(r.optString("state"))) keep.add(r.optString("fileId"));
        } catch (MediaStore.StorageException e) {
            // The index cannot be read: nothing is deleted, so no upload loses its file.
            return;
        }
        String[] names = filesRoot.list();
        for (String n : MediaCore.cleanup(names == null ? Collections.emptyList() : Arrays.asList(names), keep)) {
            if (!deleteTree(new File(filesRoot, n))) ctx.log("launch clean-up could not delete a file directory");
        }
        deleteTree(new File(MediaStore.dir(app), "capture"));
    }

    private void readoptUploadFiles() {
        try {
            for (JSONObject u : MediaStore.records(app).values()) {
                String fid = u.optString("fileId");
                if (!"uploading".equals(u.optString("state")) || files.containsKey(fid)) continue;
                File path = new File(filesRoot, u.optString("path"));
                if (!path.isFile()) continue;
                JSONObject f = newFile(null, "upload", u.optString("name"), u.optString("mime"));
                String token = tokens.remove(f.optString("fileId"));
                put(f, "fileId", fid);
                tokens.put(fid, token);
                put(f, "bytes", path.length());
                put(f, "state", "ready");
                files.put(fid, f);
                server.publish(token, new MediaServer.Entry(path, u.optString("mime")));
            }
        } catch (MediaStore.StorageException e) {
            ctx.log("upload files not re-listed: " + e.getMessage());
        }
    }

    /**
     * Launch reconciliation: an "uploading" record whose
     * generation has no unfinished work (its request was never committed, or the work was lost)
     * is scheduled again with the same generation.
     */
    private void reconcileUploads() {
        Map<String, JSONObject> recs;
        try {
            recs = MediaStore.records(app);
        } catch (MediaStore.StorageException e) {
            return;
        }
        for (JSONObject r : recs.values()) {
            if (!"uploading".equals(r.optString("state"))) continue;
            String id = r.optString("jobId"), gen = r.optString("gen");
            ListenableFuture<List<WorkInfo>> infos = WorkManager.getInstance(app).getWorkInfosForUniqueWork(UploadWorker.uniqueName(id));
            infos.addListener(() -> {
                List<WorkInfo> list;
                try {
                    list = infos.get();
                } catch (Exception failed) {
                    list = Collections.emptyList();
                }
                for (WorkInfo w : list) if (!w.getState().isFinished() && w.getTags().contains(UploadWorker.GEN_TAG + gen)) return;
                UploadWorker.schedule(app, id, gen);
            }, ContextCompat.getMainExecutor(app));
        }
    }

    // ------------------------------------------------------------------ jobs

    private ExtResult jobs(String id) {
        if (id != null) {
            if (!MediaCore.isId(id)) return ExtResult.error("not_found", "unknown job");
            JSONObject t = transcodes.get(id);
            if (t != null) return ExtResult.ok(jobSnapshot(t));
            try {
                JSONObject u = MediaStore.get(app, id);
                if (u != null) return ExtResult.ok(uploadSnapshot(u));
            } catch (MediaStore.StorageException e) {
                return ExtResult.error("storage_failed", "the upload index could not be read");
            }
            return ExtResult.error("not_found", "unknown job");
        }
        JSONArray list = new JSONArray();
        for (JSONObject t : new TreeMap<>(transcodes).values()) list.put(jobSnapshot(t));
        try {
            List<JSONObject> ups = new ArrayList<>(MediaStore.records(app).values());
            ups.sort((a, b) -> a.optLong("createdAt") != b.optLong("createdAt") ? Long.compare(a.optLong("createdAt"), b.optLong("createdAt"))
                    : a.optString("jobId").compareTo(b.optString("jobId")));
            for (JSONObject u : ups) list.put(uploadSnapshot(u));
        } catch (MediaStore.StorageException e) {
            return ExtResult.error("storage_failed", "the upload index could not be read");
        }
        JSONObject out = new JSONObject();
        put(out, "jobs", list);
        if (MediaStore.recovered()) put(out, "indexRecovered", true);
        return ExtResult.ok(out);
    }

    private ExtResult cancel(String id) {
        if (!MediaCore.isId(id)) return ExtResult.error("not_found", "unknown job");
        JSONObject t = transcodes.get(id);
        if (t != null) {
            String s = t.optString("state");
            if (!"queued".equals(s) && !"transcoding".equals(s)) return ExtResult.ok(jobSnapshot(t));
            put(t, "state", "cancelled");
            put(t, "reason", "cancelled");
            if ("transcoding".equals(s) && transcoder != null) {
                transcoder.cancel();
            } else {
                JSONObject out = files.get(t.optString("outputFileId"));
                if (out != null) {
                    put(out, "state", "failed");
                    put(out, "reason", "cancelled");
                    ctx.emit("file", fileSnapshot(out));
                }
                ctx.emit("job", jobSnapshot(t));
            }
            return ExtResult.ok(jobSnapshot(t));
        }
        JSONObject u;
        try {
            u = MediaStore.get(app, id);
            if (u == null) return ExtResult.error("not_found", "unknown job");
            if (!"uploading".equals(u.optString("state"))) return ExtResult.ok(uploadSnapshot(u));
            put(u, "state", "cancelled");
            // A new generation: a run already sending can no longer settle this record.
            put(u, "gen", UUID.randomUUID().toString());
            MediaStore.put(app, u);
        } catch (MediaStore.StorageException e) {
            return ExtResult.error("storage_failed", "the cancel could not be saved");
        }
        UploadWorker.cancel(app, id);
        ctx.emit("job", uploadSnapshot(u));
        return ExtResult.ok(uploadSnapshot(u));
    }

    // ------------------------------------------------------------------ snapshots

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private JSONObject fileSnapshot(JSONObject f) {
        JSONObject o = new JSONObject();
        String state = f.optString("state");
        for (String k : new String[]{"fileId", "pickId", "name", "mime", "kind", "bytes", "origin", "reason", "progress", "width", "height", "durationMs"}) {
            if (f.has(k)) put(o, k, f.opt(k));
        }
        put(o, "state", "loaded".equals(state) ? "loading" : state);
        String token = tokens.get(f.optString("fileId"));
        if ("ready".equals(state) && port > 0 && token != null) {
            put(o, "url", base() + "/f/" + token + "/" + Uri.encode(f.optString("name")));
        }
        return o;
    }

    private JSONObject pickSnapshot(JSONObject p) {
        JSONObject o = new JSONObject();
        put(o, "pickId", p.optString("pickId"));
        put(o, "state", p.optString("state"));
        put(o, "source", p.optString("source"));
        JSONArray list = new JSONArray();
        JSONArray ids = p.optJSONArray("fileIds");
        for (int i = 0; ids != null && i < ids.length(); i++) {
            JSONObject f = files.get(ids.optString(i));
            if (f != null) list.put(fileSnapshot(f));
        }
        put(o, "files", list);
        if (p.has("reason")) put(o, "reason", p.optString("reason"));
        return o;
    }

    private static JSONObject jobSnapshot(JSONObject j) {
        JSONObject o = new JSONObject();
        for (String k : new String[]{"jobId", "kind", "state", "progress", "fileId", "outputFileId", "width", "height", "bitrateKbps", "codec", "reason"}) {
            if (j.has(k)) put(o, k, j.opt(k));
        }
        return o;
    }

    private JSONObject uploadSnapshot(JSONObject u) {
        JSONObject o = new JSONObject();
        put(o, "jobId", u.optString("jobId"));
        put(o, "kind", "upload");
        for (String k : new String[]{"state", "fileId", "bytes", "attempts", "method", "reason", "httpStatus"}) {
            if (u.has(k) && !u.isNull(k)) put(o, k, u.opt(k));
        }
        return o;
    }

    private void portChanged(int p) {
        if (p == port) return;
        port = p;
        JSONObject o = new JSONObject();
        put(o, "running", p > 0);
        if (p > 0) put(o, "base", base());
        ctx.emit("server", o);
    }

    static Long color(String hex) {
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        if (s.length() != 6) return null;
        try {
            long v = Long.parseLong(s, 16);
            // android.graphics.Color.pack(int) form for an opaque sRGB colour: ARGB in the high 32 bits.
            return (0xFF000000L | v) << 32;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException ignored) {
        }
    }
}
