import Foundation
import Network

/// Serves picked files to the page over HTTP on the loopback interface only, so `<img>`, `<video>`,
/// `<audio>` and `fetch()` read them straight from disk with byte ranges, never through the bridge.
///
/// Why loopback: the extension API gives no access to the web view (no WKURLSchemeHandler can be
/// registered after the web view exists, and NativeExtContext offers none), and a bridge reply is a
/// JavaScript string, which would mean base64 for every byte. The base app's ATS dictionary already
/// sets NSAllowsLocalNetworking (WebView/Info.plist), and loopback is not subject to iOS local
/// network privacy.
///
/// Security: bound to 127.0.0.1 and to the loopback interface; read-only (GET, HEAD, OPTIONS); every
/// URL carries a 256-bit random token that exists only while its file is held; no cookie or
/// credential is read or set, so the URL itself is the capability and `Access-Control-Allow-Origin: *`
/// grants nothing a page without the URL could use. Any app on the device could connect to the
/// port, but without a token it gets 404.
///
/// Threading: the table and the listener live on `queue`; `onPort` is called on the main queue.
final class MediaFileServer {
    struct Entry {
        let path: URL
        let mime: String
    }

    private let queue = DispatchQueue(label: "nativeext.mediapicker.server")
    private var listener: NWListener?
    private var table: [String: Entry] = [:]
    private var boundPort: UInt16 = 0
    private var preferredPort: UInt16 = 0
    private let log: (String) -> Void
    /// Main queue: the port now serving (nil while none is bound).
    var onPort: ((UInt16?) -> Void)?

    static let chunk = 256 * 1024
    /// A connection that sends no complete head in this time is closed (as on Android).
    static let headTimeout: TimeInterval = 10
    /// Concurrent connections; a media element opens a few, more are closed at once (as on Android).
    static let maxConnections = 16
    private var open: [ObjectIdentifier: NWConnection] = [:]

    init(log: @escaping (String) -> Void) {
        self.log = log
    }

    /// Starts listening if not already; tries the last port first so URLs the page holds stay valid
    /// after iOS reclaimed the socket during suspension.
    func ensureRunning() {
        queue.async { self.startLocked() }
    }

    private func startLocked() {
        if let l = listener, l.state == .ready || l.state == .setup { return }
        listener?.cancel()
        listener = nil
        let params = NWParameters.tcp
        params.requiredInterfaceType = .loopback
        params.acceptLocalOnly = true
        params.allowLocalEndpointReuse = true
        let port = NWEndpoint.Port(rawValue: preferredPort) ?? .any
        params.requiredLocalEndpoint = NWEndpoint.hostPort(host: .ipv4(.loopback), port: port)
        let l: NWListener
        do {
            l = try NWListener(using: params)
        } catch {
            log("file server not started: \(error.localizedDescription)")
            if preferredPort != 0 {
                preferredPort = 0
                startLocked()
            } else {
                report(nil)
            }
            return
        }
        l.newConnectionHandler = { [weak self] c in self?.accept(c) }
        l.stateUpdateHandler = { [weak self, weak l] state in
            guard let self = self, let l = l, self.listener === l else { return }
            switch state {
            case .ready:
                self.boundPort = l.port?.rawValue ?? 0
                self.preferredPort = self.boundPort
                self.report(self.boundPort)
            case .failed(let e):
                self.log("file server stopped: \(e.localizedDescription)")
                l.cancel()
                self.listener = nil
                self.boundPort = 0
                self.report(nil)
                // The preferred port may be taken by now: the next ensureRunning tries it, then any.
                if case .posix(let code) = e, code == .EADDRINUSE { self.preferredPort = 0 }
            case .cancelled:
                if self.listener === l { self.listener = nil }
            default:
                break
            }
        }
        listener = l
        l.start(queue: queue)
    }

    private func report(_ port: UInt16?) {
        DispatchQueue.main.async { [weak self] in self?.onPort?(port) }
    }

    func publish(token: String, entry: Entry) {
        precondition(MPCore.isToken(token))
        queue.async { self.table[token] = entry }
    }

    func withdraw(token: String) {
        queue.async { self.table[token] = nil }
    }

    func stop() {
        queue.async {
            self.listener?.cancel()
            self.listener = nil
            self.table.removeAll()
            for c in self.open.values { c.cancel() }
        }
    }

    // MARK: Connections

    private func accept(_ c: NWConnection) {
        // Belt and braces: the listener is loopback-only; anything else is closed unanswered.
        if case .hostPort(let host, _) = c.endpoint {
            switch host {
            case .ipv4(let a) where a == .loopback: break
            case .ipv6(let a) where a == .loopback: break
            default:
                c.cancel()
                return
            }
        }
        guard open.count < MediaFileServer.maxConnections else {
            c.cancel()
            return
        }
        let key = ObjectIdentifier(c)
        open[key] = c
        c.stateUpdateHandler = { [weak self] state in
            switch state {
            case .cancelled, .failed:
                self?.open[key] = nil
                self?.heads.remove(key)
            default: break
            }
        }
        // Closed when the head is not complete in time; a served request owns the connection after.
        let deadline = DispatchWorkItem { [weak self, weak c] in
            guard let c = c, self?.heads.contains(ObjectIdentifier(c)) == true else { return }
            c.cancel()
        }
        heads.insert(key)
        queue.asyncAfter(deadline: .now() + MediaFileServer.headTimeout, execute: deadline)
        c.start(queue: queue)
        read(c, [])
    }

    /// Connections still waiting for a complete head.
    private var heads: Set<ObjectIdentifier> = []

    private func read(_ c: NWConnection, _ buffer: [UInt8]) {
        c.receive(minimumIncompleteLength: 1, maximumLength: MPCore.maxHead) { [weak self] data, _, complete, error in
            guard let self = self else { return c.cancel() }
            var buf = buffer
            if let d = data { buf.append(contentsOf: d) }
            switch MPCore.parseHead(buf) {
            case .needMore:
                if complete || error != nil {
                    self.heads.remove(ObjectIdentifier(c))
                    c.cancel()
                } else {
                    self.read(c, buf)
                }
            case .bad(let status):
                self.heads.remove(ObjectIdentifier(c))
                self.respond(c, head: MPCore.responseHead(status: status, mime: nil, size: 0, range: nil, method: "GET",
                                                          privateNetwork: false), body: nil)
            case .request(let req, _):
                self.heads.remove(ObjectIdentifier(c))
                self.serve(c, req)
            }
        }
    }

    private func serve(_ c: NWConnection, _ req: MPRequest) {
        let entry = MPCore.token(fromTarget: req.target).flatMap { table[$0] }
        var size: Int64 = 0
        var handle: FileHandle?
        if let e = entry, let h = try? FileHandle(forReadingFrom: e.path) {
            handle = h
            size = Int64(h.seekToEndOfFile())
        }
        let known = handle != nil
        let r = MPCore.range(req.headers["range"], size: size)
        let status = MPCore.status(method: req.method, known: known, range: r)
        let pn = req.headers["access-control-request-private-network"] == "true"
        let head = MPCore.responseHead(status: status, mime: entry?.mime, size: size, range: status == 206 ? r : nil,
                                       method: req.method, privateNetwork: pn)
        guard req.method == "GET", status == 200 || status == 206, let h = handle, r.end >= r.start else {
            try? handle?.close()
            respond(c, head: head, body: nil)
            return
        }
        h.seek(toFileOffset: UInt64(r.start))
        c.send(content: Data(head.utf8), completion: .contentProcessed { [weak self] err in
            guard err == nil else {
                try? h.close()
                return c.cancel()
            }
            self?.stream(c, h, remaining: r.end - r.start + 1)
        })
    }

    /// Sends the range in chunks, each after the previous one was taken by the connection, so a
    /// 4K video is never held in memory.
    private func stream(_ c: NWConnection, _ h: FileHandle, remaining: Int64) {
        guard remaining > 0 else {
            try? h.close()
            c.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in c.cancel() })
            return
        }
        let n = Int(min(Int64(MediaFileServer.chunk), remaining))
        let data = h.readData(ofLength: n)
        guard !data.isEmpty else {
            // The file shrank or was released while being read: end the response short.
            try? h.close()
            return c.cancel()
        }
        c.send(content: data, completion: .contentProcessed { [weak self] err in
            guard err == nil, let self = self else {
                try? h.close()
                return c.cancel()
            }
            self.stream(c, h, remaining: remaining - Int64(data.count))
        })
    }

    private func respond(_ c: NWConnection, head: String, body: Data?) {
        var out = Data(head.utf8)
        if let b = body { out.append(b) }
        c.send(content: out, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in c.cancel() })
    }
}
