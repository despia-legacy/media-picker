package despia.ext.mediapicker;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Serves picked files to the page over HTTP on 127.0.0.1 only, so {@code <img>}, {@code <video>},
 * {@code <audio>} and {@code fetch()} read them straight from disk with byte ranges, never through the
 * bridge (the iOS twin is MediaFileServer.swift; both answer with MediaCore's response heads).
 *
 * <p>Why loopback: the extension API offers no hook into the WebView's request handling
 * (no shouldInterceptRequest is reachable from ExtContext), and a bridge reply is a JavaScript string,
 * which would mean base64 for every byte. The base app allows cleartext and sets
 * MIXED_CONTENT_ALWAYS_ALLOW on its WebView (MainActivity.java), so an http://127.0.0.1 URL loads in
 * an https page.
 *
 * <p>Security: bound to 127.0.0.1; read-only (GET, HEAD, OPTIONS); every URL carries a 256-bit random
 * token that exists only while its file is held; no cookie or credential is read or set, so the URL
 * is the capability and {@code Access-Control-Allow-Origin: *} grants nothing a page without the URL
 * could use. Any app on the device can connect to the port, but without a token it gets 404.
 */
final class MediaServer {
    interface PortListener {
        /** Main thread: the port now serving, or -1 while none is bound. */
        void port(int port);
    }

    static final int CHUNK = 256 * 1024;
    /** A connection that sends no complete head in this time is closed. */
    static final int HEAD_TIMEOUT_MS = 10_000;
    /** Concurrent connections; a media element opens a few, more are refused at once. */
    static final int MAX_CONNECTIONS = 16;

    static final class Entry {
        final File file;
        final String mime;

        Entry(File file, String mime) {
            this.file = file;
            this.mime = mime;
        }
    }

    private final Map<String, Entry> table = new ConcurrentHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PortListener listener;
    private final ExecutorService pool = new ThreadPoolExecutor(0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> {
                Thread t = new Thread(r, "nx-mediapicker-http");
                t.setDaemon(true);
                return t;
            });
    private ServerSocket socket;
    private Thread acceptor;
    private int preferredPort;

    MediaServer(PortListener listener) {
        this.listener = listener;
    }

    /** Starts listening if not already; the last port first, so URLs the page holds stay valid. */
    synchronized void ensureRunning() {
        if (socket != null && !socket.isClosed()) return;
        ServerSocket s = null;
        for (int attempt = 0; attempt < 2 && s == null; attempt++) {
            int port = attempt == 0 ? preferredPort : 0;
            try {
                ServerSocket t = new ServerSocket();
                t.setReuseAddress(true);
                t.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port), 16);
                s = t;
            } catch (IOException e) {
                if (port == 0) break;
            }
        }
        if (s == null) {
            report(-1);
            return;
        }
        socket = s;
        preferredPort = s.getLocalPort();
        final ServerSocket bound = s;
        acceptor = new Thread(() -> accept(bound), "nx-mediapicker-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        report(preferredPort);
    }

    synchronized int port() {
        return socket != null && !socket.isClosed() ? socket.getLocalPort() : -1;
    }

    void publish(String token, Entry e) {
        if (!MediaCore.isToken(token)) throw new IllegalArgumentException("token");
        table.put(token, e);
    }

    void withdraw(String token) {
        table.remove(token);
    }

    synchronized void stop() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        socket = null;
        report(-1);
    }

    private void report(int port) {
        main.post(() -> listener.port(port));
    }

    private void accept(ServerSocket s) {
        while (!s.isClosed()) {
            Socket c;
            try {
                c = s.accept();
            } catch (IOException e) {
                break;
            }
            // Belt and braces: the socket is bound to 127.0.0.1; anything else is closed unanswered.
            if (!c.getInetAddress().isLoopbackAddress()) {
                close(c);
                continue;
            }
            try {
                pool.execute(() -> serve(c));
            } catch (java.util.concurrent.RejectedExecutionException full) {
                respond(c, MediaCore.responseHead(503, null, 0, null, "GET", false));
            }
        }
        synchronized (this) {
            if (socket == s) {
                socket = null;
                report(-1);
            }
        }
    }

    private void serve(Socket c) {
        try {
            c.setSoTimeout(HEAD_TIMEOUT_MS);
            InputStream in = c.getInputStream();
            byte[] buf = new byte[MediaCore.MAX_HEAD];
            int n = 0;
            MediaCore.Head head;
            while (true) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) {
                    close(c);
                    return;
                }
                n += r;
                head = MediaCore.parseHead(buf, n);
                if (!head.needMore()) break;
            }
            if (head.request == null) {
                respond(c, MediaCore.responseHead(head.status, null, 0, null, "GET", false));
                return;
            }
            MediaCore.Request req = head.request;
            String token = MediaCore.tokenFromTarget(req.target);
            Entry e = token == null ? null : table.get(token);
            RandomAccessFile f = null;
            long size = 0;
            if (e != null) {
                try {
                    f = new RandomAccessFile(e.file, "r");
                    size = f.length();
                } catch (IOException missing) {
                    f = null;
                }
            }
            long[] range = MediaCore.range(req.headers.get("range"), size);
            int status = MediaCore.status(req.method, f != null, (int) range[0]);
            boolean pn = "true".equals(req.headers.get("access-control-request-private-network"));
            String text = MediaCore.responseHead(status, e == null ? null : e.mime, size, status == 206 ? range : null, req.method, pn);
            if (!"GET".equals(req.method) || (status != 200 && status != 206) || f == null || range[2] < range[1]) {
                if (f != null) f.close();
                respond(c, text);
                return;
            }
            c.setSoTimeout(0);
            try (RandomAccessFile file = f) {
                OutputStream out = c.getOutputStream();
                out.write(text.getBytes(StandardCharsets.US_ASCII));
                file.seek(range[1]);
                long remaining = range[2] - range[1] + 1;
                byte[] chunk = new byte[CHUNK];
                while (remaining > 0) {
                    int r = file.read(chunk, 0, (int) Math.min(chunk.length, remaining));
                    // The file shrank or was released while being read: end the response short.
                    if (r <= 0) break;
                    out.write(chunk, 0, r);
                    remaining -= r;
                }
                out.flush();
            }
        } catch (IOException ignored) {
            // The page closed the connection (a media element seeking does this all the time).
        } finally {
            close(c);
        }
    }

    private static void respond(Socket c, String head) {
        try {
            OutputStream out = c.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException ignored) {
        } finally {
            close(c);
        }
    }

    private static void close(Socket c) {
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
