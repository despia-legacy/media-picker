package despia.ext.mediapicker;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The upload index, files/nativeext/mediapicker/uploads.json, written atomically (temp file, fsync,
 * rename) so it survives process death; the files themselves live under files/&lt;fileId&gt;/.
 *
 * <p>Authority over an upload record: the generation written when the upload was queued and the
 * run claim taken by the worker run that sends it. WorkManager does not interrupt a stopped Worker's
 * thread and may start the next run while it is still blocked, so only the run holding the claim
 * writes a state.
 *
 * <p>Every method is synchronized on the class: the main thread (routes) and worker threads share it.
 */
final class MediaStore {
    static final class StorageException extends Exception {
        StorageException(String m, Throwable cause) {
            super(m, cause);
        }
    }

    private static Map<String, JSONObject> cache;
    /** id → run currently holding the claim (in this process). */
    private static final Map<String, String> CLAIMS = new ConcurrentHashMap<>();
    /** Whether the last read found an index that did not decode (kept aside as uploads.json.bad). */
    private static boolean recovered;

    private MediaStore() {
    }

    static File dir(Context c) {
        File d = new File(new File(c.getFilesDir(), "nativeext"), "mediapicker");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static File filesRoot(Context c) {
        File d = new File(dir(c), "files");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static File index(Context c) {
        return new File(dir(c), "uploads.json");
    }

    static synchronized boolean recovered() {
        return recovered;
    }

    private static Map<String, JSONObject> load(Context c) throws StorageException {
        if (cache != null) return cache;
        Map<String, JSONObject> out = new LinkedHashMap<>();
        File f = index(c);
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                byte[] b = new byte[(int) f.length()];
                int n = 0;
                while (n < b.length) {
                    int r = in.read(b, n, b.length - n);
                    if (r < 0) break;
                    n += r;
                }
                JSONObject all = new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
                for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                    String k = it.next();
                    out.put(k, all.getJSONObject(k));
                }
            } catch (JSONException bad) {
                // Unreadable: kept aside for inspection, never silently replaced in place.
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(new File(dir(c), "uploads.json.bad"));
                recovered = true;
            } catch (IOException e) {
                throw new StorageException("upload index unreadable", e);
            }
        }
        cache = out;
        return out;
    }

    private static void save(Context c, Map<String, JSONObject> all) throws StorageException {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, JSONObject> e : all.entrySet()) o.put(e.getKey(), e.getValue());
        } catch (JSONException e) {
            throw new StorageException("upload index not JSON", e);
        }
        File tmp = new File(dir(c), "uploads.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            throw new StorageException("upload index not written", e);
        }
        if (!tmp.renameTo(index(c))) throw new StorageException("upload index not replaced", null);
    }

    private static JSONObject copy(JSONObject r) {
        try {
            return r == null ? null : new JSONObject(r.toString());
        } catch (JSONException e) {
            return null;
        }
    }

    static synchronized Map<String, JSONObject> records(Context c) throws StorageException {
        Map<String, JSONObject> out = new LinkedHashMap<>();
        for (Map.Entry<String, JSONObject> e : load(c).entrySet()) out.put(e.getKey(), copy(e.getValue()));
        return out;
    }

    static synchronized JSONObject get(Context c, String id) throws StorageException {
        return copy(load(c).get(id));
    }

    /** Saves a new or replaced record; on a write failure the index in memory is restored. */
    static synchronized void put(Context c, JSONObject r) throws StorageException {
        Map<String, JSONObject> all = load(c);
        String id = r.optString("jobId");
        JSONObject before = all.get(id);
        all.put(id, copy(r));
        try {
            save(c, all);
        } catch (StorageException e) {
            if (before == null) all.remove(id); else all.put(id, before);
            throw e;
        }
    }

    static synchronized void remove(Context c, String id) throws StorageException {
        Map<String, JSONObject> all = load(c);
        JSONObject before = all.remove(id);
        if (before == null) return;
        try {
            save(c, all);
        } catch (StorageException e) {
            all.put(id, before);
            throw e;
        }
    }

    /**
     * The worker's claim: the record when it is "uploading" with this generation and no other live
     * run in this process holds it; null otherwise (nothing to do, nothing written).
     */
    static synchronized JSONObject claim(Context c, String id, String gen, String run) throws StorageException {
        JSONObject r = load(c).get(id);
        if (r == null || !"uploading".equals(r.optString("state")) || !gen.equals(r.optString("gen"))) return null;
        CLAIMS.put(id, run);
        return copy(r);
    }

    static void release(String id, String run) {
        CLAIMS.remove(id, run);
    }

    /**
     * Writes a state for the run holding the claim, merging {@code extra}; null, writing nothing, when
     * the claim or the generation moved on (a cancel, a replacement).
     */
    static synchronized JSONObject settle(Context c, String id, String gen, String run, String state, JSONObject extra)
            throws StorageException {
        Map<String, JSONObject> all = load(c);
        JSONObject r = all.get(id);
        if (r == null || !run.equals(CLAIMS.get(id)) || !gen.equals(r.optString("gen"))) return null;
        if (!"uploading".equals(r.optString("state"))) return null;
        JSONObject next = copy(r);
        try {
            next.put("state", state);
            if (extra != null) {
                for (Iterator<String> it = extra.keys(); it.hasNext(); ) {
                    String k = it.next();
                    next.put(k, extra.get(k));
                }
            }
        } catch (JSONException e) {
            throw new StorageException("record not JSON", e);
        }
        all.put(id, next);
        try {
            save(c, all);
        } catch (StorageException e) {
            all.put(id, r);
            throw e;
        }
        return copy(next);
    }
}
