package despia.ext.mediapicker;

import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.despia.nativeext.ExtContext;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Uploads one picked or transcoded file to the page's https URL; survives process death and reboot.
 *
 * <p>Design: WorkManager unique work per upload with REPLACE, a generation per enqueue and
 * a run claim in {@link MediaStore#claim}, the URL re-checked with {@link ExtContext#isApprovedUrl}
 * before every attempt and parsed by {@link #destination} only when the parse agrees with the
 * approved text, redirects never followed, 3xx and 4xx final, network errors and 5xx counted up to
 * {@link #MAX_ATTEMPTS} with WorkManager's exponential backoff (30 s base, the same as iOS), and a
 * stop by WorkManager rerun without counting. Delivery is at least once.
 *
 * <p>Platform limit (named, not hidden): WorkManager stops a run after its execution window
 * (10 minutes for a regular worker, developer.android.com/develop/background-work/background-tasks/
 * persistent/how-to/long-running, read 2026-11-20); the stopped run is retried from the first byte,
 * because a plain PUT or POST has no resumable protocol. A long-running foreground worker would lift
 * the window, but it needs WorkManager's own SystemForegroundService declared with a dataSync type,
 * and an extension can only declare components in its own package, so this worker stays a regular
 * one.
 */
public final class UploadWorker extends Worker {
    static final int MAX_ATTEMPTS = 5;
    static final int MAX_POLICY_WAITS = 10;
    static final int CONNECT_TIMEOUT_MS = 30_000;
    static final int READ_TIMEOUT_MS = 60_000;
    static final String TAG = "nx-mediapicker";
    static final String GEN_TAG = "nx-gen:";

    private volatile HttpURLConnection inFlight;

    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    static String uniqueName(String id) {
        return "nx-mediapicker-" + id;
    }

    static Operation schedule(Context c, String id, String gen) {
        Data in = new Data.Builder().putString("id", id).putString("gen", gen).build();
        OneTimeWorkRequest.Builder b = new OneTimeWorkRequest.Builder(UploadWorker.class)
                .setInputData(in)
                .addTag(TAG)
                .addTag(GEN_TAG + gen)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build());
        return WorkManager.getInstance(c).enqueueUniqueWork(uniqueName(id), ExistingWorkPolicy.REPLACE, b.build());
    }

    static void cancel(Context c, String id) {
        WorkManager.getInstance(c).cancelUniqueWork(uniqueName(id));
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        String id = getInputData().getString("id");
        String gen = getInputData().getString("gen");
        if (id == null || gen == null) return Result.failure();
        String run = UUID.randomUUID().toString();
        JSONObject r;
        try {
            r = MediaStore.claim(c, id, gen, run);
        } catch (MediaStore.StorageException e) {
            return Result.retry();
        }
        if (r == null) return Result.failure();
        try {
            return upload(c, id, gen, run, r);
        } finally {
            MediaStore.release(id, run);
        }
    }

    @Override
    public void onStopped() {
        HttpURLConnection conn = inFlight;
        if (conn != null) conn.disconnect();
    }

    private Result upload(Context c, String id, String gen, String run, JSONObject r) {
        ExtContext policy = MediaPickerExtension.policy();
        if (policy == null) {
            if (getRunAttemptCount() < MAX_POLICY_WAITS) return Result.retry();
            return finish(c, id, gen, run, "failed", fields("reason", "policy_unavailable"), Result.failure());
        }
        String target = r.optString("url", "");
        URL url = policy.isApprovedUrl(target, r.optString("route", "upload"), "url") ? destination(target) : null;
        if (url == null) return finish(c, id, gen, run, "failed", fields("reason", "url_not_approved"), Result.failure());
        File f = new File(MediaStore.filesRoot(c), r.optString("path"));
        if (!f.isFile() || f.length() == 0) return finish(c, id, gen, run, "failed", fields("reason", "no_file"), Result.failure());
        if (isStopped()) return Result.retry();
        String field = r.optString("field", "");
        String[] framing = field.isEmpty() ? null
                : MediaCore.multipart("nx-" + UUID.randomUUID(), field, r.optString("name"), r.optString("contentType"));
        byte[] prefix = framing == null ? new byte[0] : framing[0].getBytes(StandardCharsets.UTF_8);
        byte[] suffix = framing == null ? new byte[0] : framing[1].getBytes(StandardCharsets.UTF_8);
        long total = prefix.length + f.length() + suffix.length;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            inFlight = conn;
            if (isStopped()) return Result.retry();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestMethod(r.optString("method", "PUT"));
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(false);
            conn.setFixedLengthStreamingMode(total);
            if (framing == null) {
                conn.setRequestProperty("Content-Type", r.optString("contentType"));
            } else {
                String boundary = framing[1].substring(4, framing[1].length() - 4);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            }
            conn.setRequestProperty("X-Upload-Id", id);
            long sent = 0, lastReport = 0, lastAt = 0;
            try (InputStream in = new FileInputStream(f); OutputStream out = conn.getOutputStream()) {
                out.write(prefix);
                sent += prefix.length;
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sent += n;
                    long now = SystemClock.uptimeMillis();
                    // Throttled: every 1% or half second, whichever comes later.
                    if (sent - lastReport >= total / 100 && now - lastAt >= 500) {
                        lastReport = sent;
                        lastAt = now;
                        setProgressAsync(new Data.Builder().putLong("sent", sent).putLong("total", total).build());
                        MediaPickerExtension.uploadProgress(id, sent, total);
                    }
                }
                out.write(suffix);
            }
            int code = conn.getResponseCode();
            JSONObject status = fields("httpStatus", code);
            String verdict = MediaCore.classify(code, false, code >= 300 && code < 400, false);
            if (verdict.equals("uploaded")) return finish(c, id, gen, run, "uploaded", put(status, "reason", JSONObject.NULL), Result.success());
            if (verdict.startsWith("failed:")) return finish(c, id, gen, run, "failed", put(status, "reason", verdict.substring(7)), Result.failure());
            return counted(c, id, gen, run, r, "http", code);
        } catch (IOException e) {
            if (isStopped()) return Result.retry();
            return counted(c, id, gen, run, r, "network", null);
        } finally {
            inFlight = null;
            if (conn != null) conn.disconnect();
        }
    }

    private Result counted(Context c, String id, String gen, String run, JSONObject r, String reason, Integer code) {
        int attempts = r.optInt("attempts", 0) + 1;
        JSONObject extra = put(fields("attempts", attempts), "httpStatus", code == null ? JSONObject.NULL : code);
        if (attempts < MAX_ATTEMPTS) return finish(c, id, gen, run, "uploading", put(extra, "reason", reason), Result.retry());
        return finish(c, id, gen, run, "failed", put(extra, "reason", reason), Result.failure());
    }

    private static Result finish(Context c, String id, String gen, String run, String state, JSONObject extra, Result saved) {
        JSONObject now;
        try {
            now = MediaStore.settle(c, id, gen, run, state, extra);
        } catch (MediaStore.StorageException e) {
            return Result.retry();
        }
        if (now == null) return Result.failure();
        MediaPickerExtension.uploadChanged(now);
        return saved;
    }

    /** Strict parse: the URL is used only when its parse agrees with the approved text. */
    static URL destination(String s) {
        if (!strictUrlText(s)) return null;
        try {
            URI u = new URI(s);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getRawUserInfo() != null || u.getRawFragment() != null) return null;
            if (u.getPort() != -1 && u.getPort() != 443) return null;
            String host = u.getHost() == null ? null : u.getHost().toLowerCase(Locale.ROOT);
            if (host == null || host.isEmpty() || !host.matches("[a-z0-9.-]+")) return null;
            String lower = s.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("https://" + host)) return null;
            String after = lower.substring(("https://" + host).length());
            if (!(after.isEmpty() || after.startsWith("/") || after.equals(":443") || after.startsWith(":443/"))) return null;
            return u.toURL();
        } catch (URISyntaxException | MalformedURLException | IllegalArgumentException e) {
            return null;
        }
    }

    private static final String URL_TEXT_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#[]@!$&'()*+,;=%";
    private static final String HEX_DIGITS = "0123456789ABCDEFabcdef";

    static boolean strictUrlText(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); ) {
            char ch = s.charAt(i);
            if (ch > 0x7e || URL_TEXT_CHARS.indexOf(ch) < 0) return false;
            if (ch == '%') {
                if (i + 2 >= s.length() || HEX_DIGITS.indexOf(s.charAt(i + 1)) < 0 || HEX_DIGITS.indexOf(s.charAt(i + 2)) < 0) {
                    return false;
                }
                i += 3;
            } else {
                i++;
            }
        }
        return true;
    }

    static JSONObject fields(String k, Object v) {
        return put(new JSONObject(), k, v);
    }

    static JSONObject put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException ignored) {
        }
        return o;
    }
}
