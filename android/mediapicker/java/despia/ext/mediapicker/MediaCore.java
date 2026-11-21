package despia.ext.mediapicker;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure decisions of the media picker (no android.* import). Every rule here has a twin in
 * ios/app/MediaPickerCore.swift, and both follow the same shared rules.
 * The platform classes only translate.
 */
final class MediaCore {
    private MediaCore() {
    }

    static final int MAX_TYPE_TOKENS = 32;
    static final int MAX_HEAD = 16 * 1024;
    static final int MAX_NAME_CODE_POINTS = 120;
    private static final Set<String> CATEGORY_WORDS = set("image", "video", "audio", "document", "any");
    private static final String MIME_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789!#$&^_.+-";

    private static Set<String> set(String... s) {
        Set<String> out = new LinkedHashSet<>();
        Collections.addAll(out, s);
        return out;
    }

    /** What the page asked to pick: categories and MIME types. Open form: any MIME type is accepted. */
    static final class Types {
        final Set<String> categories = new TreeSet<>();
        final List<String> mimes = new ArrayList<>();

        boolean isAny() {
            return categories.contains("any") || (categories.isEmpty() && mimes.isEmpty());
        }

        boolean visualOnly() {
            if (isAny()) return false;
            for (String c : categories) if (!c.equals("image") && !c.equals("video")) return false;
            for (String m : mimes) if (!m.startsWith("image/") && !m.startsWith("video/")) return false;
            return true;
        }

        boolean wantsImages() {
            if (isAny() || categories.contains("image")) return true;
            for (String m : mimes) if (m.startsWith("image/")) return true;
            return false;
        }

        boolean wantsVideos() {
            if (isAny() || categories.contains("video")) return true;
            for (String m : mimes) if (m.startsWith("video/")) return true;
            return false;
        }

        boolean wantsCategory(String c) {
            return isAny() || categories.contains(c);
        }
    }

    /** Space and horizontal tab only (the same set as MediaPickerCore.swift). */
    static String trimSpaces(String s) {
        int a = 0, b = s.length();
        while (a < b && (s.charAt(a) == ' ' || s.charAt(a) == '\t')) a++;
        while (b > a && (s.charAt(b - 1) == ' ' || s.charAt(b - 1) == '\t')) b--;
        return s.substring(a, b);
    }

    /** null: the page's {@code types} is not a list of categories and MIME types (bad_value). */
    static Types parseTypes(String raw) {
        Types out = new Types();
        if (raw == null) return out;
        String[] tokens = raw.split(",", -1);
        if (tokens.length == 1 && trimSpaces(tokens[0]).isEmpty()) return out;
        if (tokens.length > MAX_TYPE_TOKENS) return null;
        for (String tok : tokens) {
            String t = trimSpaces(tok).toLowerCase(Locale.ROOT);
            if (CATEGORY_WORDS.contains(t)) {
                out.categories.add(t);
            } else if (isMime(t)) {
                if (!out.mimes.contains(t)) out.mimes.add(t);
            } else {
                return null;
            }
        }
        return out;
    }

    private static boolean mimeToken(String p) {
        if (p.isEmpty() || p.length() > 64) return false;
        char f = p.charAt(0);
        if (!((f >= 'a' && f <= 'z') || (f >= '0' && f <= '9'))) return false;
        for (int i = 0; i < p.length(); i++) if (MIME_CHARS.indexOf(p.charAt(i)) < 0) return false;
        return true;
    }

    static boolean isMime(String s) {
        String[] parts = s.split("/", -1);
        if (parts.length != 2) return false;
        return mimeToken(parts[0]) && (parts[1].equals("*") || mimeToken(parts[1]));
    }

    /** library, files, camera or sheet; null when that source cannot meet the request. */
    static String source(String requested, Types t) {
        switch (requested) {
            case "auto":
                if (t.visualOnly()) return "library";
                if (!t.isAny() && !t.wantsImages() && !t.wantsVideos()) return "files";
                return "sheet";
            case "library":
                return t.isAny() || t.visualOnly() || t.wantsImages() || t.wantsVideos() ? "library" : null;
            case "camera":
                return t.wantsImages() || t.wantsVideos() ? "camera" : null;
            case "files":
                return "files";
            default:
                return null;
        }
    }

    static String cameraMode(Types t, String requested) {
        boolean photo = t.wantsImages(), video = t.wantsVideos();
        switch (requested) {
            case "photo":
                return photo ? "photo" : null;
            case "video":
                return video ? "video" : null;
            default:
                return photo && video ? "both" : (photo ? "photo" : (video ? "video" : null));
        }
    }

    static String kind(String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("image/")) return "image";
        if (m.startsWith("video/")) return "video";
        if (m.startsWith("audio/")) return "audio";
        return "document";
    }

    /** {limit, 1 when lowered to the platform's maximum}; platformMax 0: the platform has none. */
    static int[] limit(int requested, boolean multiple, int platformMax) {
        if (!multiple) return new int[]{1, 0};
        if (requested <= 0) return new int[]{platformMax, 0};
        if (platformMax > 0 && requested > platformMax) return new int[]{platformMax, 1};
        return new int[]{requested, 0};
    }

    /** Per file, in pick order: null (admitted), too_large or total_exceeded. 0 MiB is no limit. */
    static List<String> admit(List<Long> sizes, int maxFileMB, int maxTotalMB) {
        long mib = 1024L * 1024L;
        long total = 0;
        List<String> out = new ArrayList<>();
        for (long s : sizes) {
            if (maxFileMB > 0 && s > maxFileMB * mib) {
                out.add("too_large");
            } else if (maxTotalMB > 0 && total + s > maxTotalMB * mib) {
                out.add("total_exceeded");
            } else {
                total += s;
                out.add(null);
            }
        }
        return out;
    }

    private static boolean hidden(int v) {
        return v < 0x20 || (v >= 0x7F && v <= 0x9F) || (v >= 0x200B && v <= 0x200F) || (v >= 0x202A && v <= 0x202E)
                || (v >= 0x2066 && v <= 0x2069) || v == 0xFEFF;
    }

    /** Last path component, no hidden code points, no leading dots or spaces, at most 120 code points. */
    static String safeName(String raw, String fallbackExtension) {
        String s = raw == null ? "" : raw;
        int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (cut >= 0) s = s.substring(cut + 1);
        StringBuilder b = new StringBuilder();
        s.codePoints().filter(cp -> !hidden(cp)).forEach(b::appendCodePoint);
        s = b.toString();
        int a = 0;
        while (a < s.length() && (s.charAt(a) == '.' || s.charAt(a) == ' ')) a++;
        int e = s.length();
        while (e > a && s.charAt(e - 1) == ' ') e--;
        s = s.substring(a, e);
        if (s.isEmpty()) return "file" + fallbackExtension;
        int[] all = s.codePoints().toArray();
        if (all.length <= MAX_NAME_CODE_POINTS) return s;
        int dot = -1;
        for (int i = all.length - 1; i >= 0; i--) {
            if (all[i] == '.') {
                dot = i;
                break;
            }
        }
        int extLen = (dot > 0 && all.length - dot <= 16) ? all.length - dot : 0;
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < MAX_NAME_CODE_POINTS - extLen; i++) o.appendCodePoint(all[i]);
        for (int i = all.length - extLen; i < all.length; i++) o.appendCodePoint(all[i]);
        return o.toString();
    }

    static boolean isId(String s) {
        if (s == null || s.length() != 36) return false;
        for (int i = 0; i < 36; i++) {
            char c = s.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') return false;
            } else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /** 43 characters of URL-safe base64 (32 random bytes, no padding). */
    static boolean isToken(String s) {
        if (s == null || s.length() != 43) return false;
        for (int i = 0; i < 43; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '-' || c == '_')) return false;
        }
        return true;
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    /** URL-safe base64 without padding (java.util.Base64 needs API 26; minSdk is 24). */
    static String token(byte[] b) {
        StringBuilder o = new StringBuilder();
        int i = 0;
        for (; i + 2 < b.length; i += 3) {
            int n = ((b[i] & 0xff) << 16) | ((b[i + 1] & 0xff) << 8) | (b[i + 2] & 0xff);
            o.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]).append(B64[(n >> 6) & 63]).append(B64[n & 63]);
        }
        int rest = b.length - i;
        if (rest == 1) {
            int n = (b[i] & 0xff) << 16;
            o.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]);
        } else if (rest == 2) {
            int n = ((b[i] & 0xff) << 16) | ((b[i + 1] & 0xff) << 8);
            o.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]).append(B64[(n >> 6) & 63]);
        }
        return o.toString();
    }

    // ------------------------------------------------------------------ loopback HTTP

    static final class Request {
        final String method;
        final String target;
        final Map<String, String> headers;

        Request(String method, String target, Map<String, String> headers) {
            this.method = method;
            this.target = target;
            this.headers = headers;
        }
    }

    /** needMore (null request, status 0), bad (status), or a request and the bytes it used. */
    static final class Head {
        final int status;
        final Request request;
        final int consumed;

        Head(int status, Request request, int consumed) {
            this.status = status;
            this.request = request;
            this.consumed = consumed;
        }

        boolean needMore() {
            return status == 0 && request == null;
        }
    }

    static Head parseHead(byte[] bytes, int count) {
        int end = -1;
        for (int i = 0; i + 3 < count; i++) {
            if (bytes[i] == 13 && bytes[i + 1] == 10 && bytes[i + 2] == 13 && bytes[i + 3] == 10) {
                end = i;
                break;
            }
        }
        if (end < 0) return count >= MAX_HEAD ? new Head(431, null, 0) : new Head(0, null, 0);
        if (end + 4 > MAX_HEAD) return new Head(431, null, 0);
        for (int i = 0; i < end; i++) {
            int b = bytes[i] & 0xff;
            if (b >= 0x7F) return new Head(400, null, 0);
            if (b == 13 && bytes[i + 1] != 10) return new Head(400, null, 0);
            if (b == 10 && (i == 0 || bytes[i - 1] != 13)) return new Head(400, null, 0);
            if (b < 0x20 && b != 9 && b != 10 && b != 13) return new Head(400, null, 0);
        }
        String text = new String(bytes, 0, end, StandardCharsets.US_ASCII);
        String[] lines = text.split("\r\n", -1);
        String[] first = lines[0].split(" ", -1);
        if (first.length != 3) return new Head(400, null, 0);
        String method = first[0], target = first[1], version = first[2];
        if (method.isEmpty()) return new Head(400, null, 0);
        for (int i = 0; i < method.length(); i++) {
            char c = method.charAt(i);
            if (c < 'A' || c > 'Z') return new Head(400, null, 0);
        }
        if (!target.startsWith("/") || !(version.equals("HTTP/1.1") || version.equals("HTTP/1.0"))) return new Head(400, null, 0);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int li = 1; li < lines.length; li++) {
            String line = lines[li];
            int colon = line.indexOf(':');
            if (colon <= 0 || line.charAt(0) == ' ' || line.charAt(0) == '\t') return new Head(400, null, 0);
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c <= 0x20 || c >= 0x7F || c == ':') return new Head(400, null, 0);
            }
            String value = trimSpaces(line.substring(colon + 1));
            if (name.equals("range") && headers.containsKey(name)) return new Head(400, null, 0);
            if (!headers.containsKey(name)) headers.put(name, value);
        }
        return new Head(0, new Request(method, target, headers), end + 4);
    }

    /** The token of /f/&lt;token&gt; or /f/&lt;token&gt;/&lt;name&gt;; the query is ignored. */
    static String tokenFromTarget(String target) {
        String path = target;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '?' || c == '#') {
                path = path.substring(0, i);
                break;
            }
        }
        String[] parts = path.split("/", -1);
        if ((parts.length != 3 && parts.length != 4) || !parts[0].isEmpty() || !parts[1].equals("f")) return null;
        return isToken(parts[2]) ? parts[2] : null;
    }

    /** {status, start, end}: RFC 9110 14.2 single range; ignored forms answer 200 with the whole file. */
    static long[] range(String header, long size) {
        long[] whole = {200, 0, size - 1};
        if (header == null) return whole;
        String h = trimSpaces(header);
        if (h.isEmpty() || !h.toLowerCase(Locale.ROOT).startsWith("bytes=")) return whole;
        String spec = trimSpaces(h.substring(6));
        int dash = spec.indexOf('-');
        if (spec.indexOf(',') >= 0 || dash < 0) return whole;
        String a = trimSpaces(spec.substring(0, dash));
        String b = trimSpaces(spec.substring(dash + 1));
        long[] unsatisfiable = {416, 0, -1};
        if (a.isEmpty()) {
            Long n = num(b);
            if (n == null) return whole;
            if (n == 0 || size == 0) return unsatisfiable;
            return new long[]{206, Math.max(0, size - n), size - 1};
        }
        Long first = num(a);
        if (first == null) return whole;
        if (b.isEmpty()) return first >= size ? unsatisfiable : new long[]{206, first, size - 1};
        Long last = num(b);
        if (last == null || last < first) return whole;
        if (first >= size) return unsatisfiable;
        return new long[]{206, first, Math.min(last, size - 1)};
    }

    private static Long num(String s) {
        if (s.isEmpty() || s.length() > 18) return null;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return null;
        return Long.parseLong(s);
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 416: return "Range Not Satisfiable";
            case 431: return "Request Header Fields Too Large";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }

    /** The URL is the capability; any origin may read a file whose URL it holds; no cache; close. */
    static String responseHead(int status, String mime, long size, long[] range, String method, boolean privateNetwork) {
        List<String[]> h = new ArrayList<>();
        long length = 0;
        if (status == 200 || status == 206) {
            h.add(new String[]{"Content-Type", mime == null ? "application/octet-stream" : mime});
            h.add(new String[]{"Accept-Ranges", "bytes"});
            if (status == 206 && range != null) {
                h.add(new String[]{"Content-Range", "bytes " + range[1] + "-" + range[2] + "/" + size});
                length = range[2] - range[1] + 1;
            } else {
                length = size;
            }
        } else if (status == 416) {
            h.add(new String[]{"Content-Range", "bytes */" + size});
        }
        if (status == 204) {
            h.add(new String[]{"Access-Control-Allow-Methods", "GET, HEAD, OPTIONS"});
            h.add(new String[]{"Access-Control-Allow-Headers", "Range"});
            h.add(new String[]{"Access-Control-Max-Age", "600"});
            if (privateNetwork) h.add(new String[]{"Access-Control-Allow-Private-Network", "true"});
        } else {
            h.add(new String[]{"Content-Length", String.valueOf(length)});
        }
        h.add(new String[]{"Access-Control-Allow-Origin", "*"});
        if (status == 200 || status == 206) {
            h.add(new String[]{"Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges"});
        }
        h.add(new String[]{"Cache-Control", "no-store"});
        h.add(new String[]{"Cross-Origin-Resource-Policy", "cross-origin"});
        h.add(new String[]{"X-Content-Type-Options", "nosniff"});
        if (status == 405) h.add(new String[]{"Allow", "GET, HEAD, OPTIONS"});
        h.add(new String[]{"Connection", "close"});
        StringBuilder o = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        for (String[] kv : h) o.append(kv[0]).append(": ").append(kv[1]).append("\r\n");
        return o.append("\r\n").toString();
    }

    static int status(String method, boolean known, int rangeStatus) {
        if (method.equals("OPTIONS")) return 204;
        if (!method.equals("GET") && !method.equals("HEAD")) return 405;
        if (!known) return 404;
        return rangeStatus;
    }

    // ------------------------------------------------------------------ video targets

    static int presetShortSide(String preset) {
        switch (preset) {
            case "480p": return 480;
            case "540p": return 540;
            case "720p": return 720;
            case "1080p": return 1080;
            case "1440p": return 1440;
            case "2160p": return 2160;
            default: return -1;
        }
    }

    /** {width, height, kbps} as MediaPickerCore.target: never upscales, even sizes; null when unknown. */
    static int[] target(int width, int height, String preset, String codec, int bitrateKbps, int sourceKbps, double frameRate) {
        if (width <= 0 || height <= 0) return null;
        int srcShort = Math.min(width, height), srcLong = Math.max(width, height);
        int want;
        if (preset.equals("source")) {
            want = srcShort;
        } else {
            want = presetShortSide(preset);
            if (want < 0) return null;
        }
        int s = Math.min(srcShort, want);
        int outShort = Math.max(2, s - s % 2);
        double longD = (double) srcLong * outShort / srcShort;
        int outLong = (int) Math.round(longD);
        outLong = Math.max(2, outLong - outLong % 2);
        int w = width >= height ? outLong : outShort;
        int h = width >= height ? outShort : outLong;
        int kbps = bitrateKbps;
        if (kbps <= 0) {
            int[][] table = {{480, 2000}, {540, 2500}, {720, 5000}, {1080, 8000}, {1440, 16000}};
            int base = 35000;
            for (int[] row : table) {
                if (outShort <= row[0]) {
                    base = row[1];
                    break;
                }
            }
            if (codec.equals("hevc")) base = (int) Math.round(base * 0.6);
            if (frameRate > 31) base = (int) Math.round(base * 1.5);
            if (sourceKbps > 0) base = Math.min(base, sourceKbps);
            kbps = base;
        }
        return new int[]{w, h, kbps};
    }

    /** bitrate_kbps text: digits, 100 to 400000; empty or absent is 0 (default); null is bad_value. */
    static Integer bitrate(String raw) {
        if (raw == null || raw.isEmpty()) return 0;
        if (raw.length() > 6) return null;
        for (int i = 0; i < raw.length(); i++) if (raw.charAt(i) < '0' || raw.charAt(i) > '9') return null;
        int n = Integer.parseInt(raw);
        return n >= 100 && n <= 400_000 ? n : null;
    }

    // ------------------------------------------------------------------ upload

    /** uploaded, failed:&lt;reason&gt;, requeue or retry:&lt;reason&gt; (the upload outcome rules). */
    static String classify(Integer status, boolean transportError, boolean refused, boolean spansBackground) {
        if (status != null && status >= 300 && status < 400) return refused ? "failed:redirect_refused" : "failed:http";
        if (status != null && status >= 400 && status < 500) return "failed:http";
        if (!transportError && status != null && status >= 200 && status < 300) return "uploaded";
        if (transportError && spansBackground) return "requeue";
        return transportError ? "retry:network" : "retry:http";
    }

    static double backoff(int attempt, double base, double cap) {
        return Math.min(cap, base * Math.pow(2, Math.max(attempt, 1) - 1));
    }

    static boolean formName(String s) {
        if (s == null || s.isEmpty() || s.length() > 64) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '-' || c == '_' || c == '.')) return false;
        }
        return true;
    }

    static String[] multipart(String boundary, String field, String filename, String contentType) {
        StringBuilder safe = new StringBuilder();
        filename.codePoints().forEach(cp -> {
            if (cp < 0x20 || cp == 0x7F || cp == '"' || cp == '\\') safe.append('_');
            else safe.appendCodePoint(cp);
        });
        String prefix = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field + "\"; filename=\"" + safe
                + "\"\r\nContent-Type: " + contentType + "\r\n\r\n";
        return new String[]{prefix, "\r\n--" + boundary + "--\r\n"};
    }

    static String contentType(String raw, String fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        String s = raw.toLowerCase(Locale.ROOT);
        return isMime(s) && !s.endsWith("/*") ? s : null;
    }

    // ------------------------------------------------------------------ clean-up

    static List<String> cleanup(List<String> entries, Set<String> keep) {
        List<String> out = new ArrayList<>();
        for (String e : entries) if (!keep.contains(e)) out.add(e);
        Collections.sort(out);
        return out;
    }

    /**
     * The end state of a transcode, as "state:reason" (reason empty for done). One that already
     * ended while the transcoder stopped (a cancel set cancelled, a failure set failed) keeps that
     * state and reason; otherwise the transcoder's outcome (done, cancelled, failed) decides.
     */
    static String settle(String state, String reason, String outcome, String outcomeReason) {
        if ("failed".equals(state) || "cancelled".equals(state)) {
            return state + ":" + (reason != null ? reason : outcomeReason != null ? outcomeReason : state);
        }
        switch (outcome) {
            case "done": return "done:";
            case "cancelled": return "cancelled:cancelled";
            default: return "failed:" + (outcomeReason != null ? outcomeReason : "failed");
        }
    }

    /**
     * What may be done with the upload index as it was read: missing (a first run), loaded,
     * recovered (it did not decode and was set aside), or unread (it exists but could not be read).
     * An unread index is never pruned, written over or used to clean up files, and takes no new
     * upload, until a later read succeeds: the uploads it holds still need their files.
     */
    static List<String> indexPlan(String index) {
        return "unread".equals(index) ? new ArrayList<>() : new ArrayList<>(Arrays.asList("prune", "persist", "cleanUp", "accept"));
    }
}
