import Foundation

/// One durable upload. Persisted in `uploads.json` so it survives suspension and termination; its
/// file stays on disk (launch clean-up keeps it) until the upload is settled or cancelled.
struct MPUpload: Codable, Equatable {
    var id: String
    var fileId: String
    /// Relative to the files root: "<fileId>/<name>".
    var path: String
    var name: String
    var mime: String
    var bytes: Int64
    var url: String
    var route: String
    var method: String
    var field: String?
    var contentType: String
    var state: String
    var attempts: Int
    var nextAttemptAt: Date?
    var reason: String?
    var httpStatus: Int?
    var releaseAfter: Bool
    var createdAt: Date
}

/// The upload index. A write that fails leaves the state in memory and visible as `durable: false`;
/// an index that does not decode is copied aside and replaced, and `recovered` says so. An index
/// that exists but cannot be read (before the first unlock, or an I/O error) is `unread`: it is
/// never written over and its records are not known, so nothing that depends on them may run
/// (MPCore.indexPlan) until `load()` reads it.
final class MPUploadStore {
    private let file: URL
    private let directory: URL
    private let read: (URL) throws -> Data?
    private(set) var records: [String: MPUpload] = [:]
    private(set) var durable = true
    private(set) var recovered = false
    /// missing, loaded, recovered or unread.
    private(set) var index = "unread"

    /// `read` returns nil when the file does not exist and throws when it exists but cannot be read.
    init(directory: URL, read: @escaping (URL) throws -> Data? = MPUploadStore.readIfPresent) {
        self.directory = directory
        self.read = read
        file = directory.appendingPathComponent("uploads.json")
        load()
    }

    static func readIfPresent(_ url: URL) throws -> Data? {
        do {
            return try Data(contentsOf: url)
        } catch let e as CocoaError where e.code == .fileReadNoSuchFile {
            return nil
        } catch let e as NSError where e.domain == NSPOSIXErrorDomain && e.code == Int(ENOENT) {
            return nil
        }
    }

    /// Reads the index when it is not read yet; true once it is known (missing, loaded or recovered).
    @discardableResult
    func load() -> Bool {
        guard index == "unread" else { return true }
        let data: Data?
        do {
            data = try read(file)
        } catch {
            return false
        }
        guard let data = data else {
            index = "missing"
            return true
        }
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .millisecondsSince1970
        if let list = try? dec.decode([MPUpload].self, from: data) {
            for r in list where MPCore.isId(r.id) && MPCore.isId(r.fileId) { records[r.id] = r }
            index = "loaded"
        } else {
            let aside = directory.appendingPathComponent("uploads.unreadable-\(Int(Date().timeIntervalSince1970)).json")
            try? FileManager.default.moveItem(at: file, to: aside)
            recovered = true
            index = "recovered"
        }
        return true
    }

    func allows(_ action: String) -> Bool { MPCore.indexPlan(index).contains(action) }

    subscript(id: String) -> MPUpload? { records[id] }

    func set(_ r: MPUpload) { records[r.id] = r }

    func remove(_ id: String) { records[id] = nil }

    @discardableResult
    func persist() -> Bool {
        // An unread index is never written over: it holds uploads this run does not know.
        guard allows("persist") else {
            durable = false
            return false
        }
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .millisecondsSince1970
        enc.outputFormatting = [.sortedKeys]
        let list = records.values.sorted { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }
        do {
            let data = try enc.encode(list)
            try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            durable = true
        } catch {
            durable = false
        }
        return durable
    }
}

/// Streams a multipart/form-data body (prefix, the file, suffix) through a bound stream pair, so a
/// large video is never copied or held in memory.
final class MPMultipartBody {
    let length: Int64
    private let prefix: Data
    private let suffix: Data
    private let file: URL

    init(file: URL, bytes: Int64, prefix: String, suffix: String) {
        self.file = file
        self.prefix = Data(prefix.utf8)
        self.suffix = Data(suffix.utf8)
        length = Int64(self.prefix.count) + bytes + Int64(self.suffix.count)
    }

    func makeStream() -> InputStream? {
        var input: InputStream?
        var output: OutputStream?
        Stream.getBoundStreams(withBufferSize: 256 * 1024, inputStream: &input, outputStream: &output)
        guard let i = input, let o = output, let source = InputStream(url: file) else { return nil }
        let prefix = self.prefix, suffix = self.suffix
        Thread.detachNewThread {
            o.open()
            source.open()
            defer {
                source.close()
                o.close()
            }
            func write(_ d: Data) -> Bool {
                var off = 0
                while off < d.count {
                    let n = d.withUnsafeBytes { o.write($0.baseAddress!.advanced(by: off).assumingMemoryBound(to: UInt8.self), maxLength: d.count - off) }
                    if n <= 0 { return false }
                    off += n
                }
                return true
            }
            guard write(prefix) else { return }
            var buf = [UInt8](repeating: 0, count: 256 * 1024)
            while true {
                let n = source.read(&buf, maxLength: buf.count)
                if n < 0 { return }
                if n == 0 { break }
                if !write(Data(buf[0..<n])) { return }
            }
            _ = write(suffix)
        }
        return i
    }
}

/// Uploads queued files with an in-process ephemeral session whose delegate refuses every HTTP
/// redirect, so the body reaches only the host of the validated URL: `productionConfiguration`,
/// the strict `destination` parse, the redirect delegate and MPCore.classify's outcome table.
/// A background URLSession is not used: it follows redirects without asking
/// (NativeExtContext.backgroundSessionIdentifier, platform limit).
///
/// Uploads run only while this process runs: in the foreground, during the finite background time
/// iOS may grant, and during a BGProcessingTask launch if iOS grants one. A suspended or terminated
/// upload keeps its file and record; the next run restarts it from byte 0. Resume data (iOS 17
/// resumable uploads) is never kept: a resumed task sends the rest of the body to the upload
/// resource the server named, a URL the bridge's https_url rule never checked.
///
/// Main thread only: every method, the delegate queue (`.main`) and the backoff timer share it.
final class MediaUploader: NSObject, URLSessionTaskDelegate, URLSessionDataDelegate {
    private struct Attempt {
        let id: String
        let token: String
        let task: URLSessionTask
        var spansBackground: Bool
        let body: MPMultipartBody?
    }

    private let store: MPUploadStore
    private let filesRoot: URL
    private let isApproved: (_ url: String, _ route: String) -> Bool
    private let changed: (_ id: String) -> Void
    private let progressed: (_ id: String, _ sent: Int64, _ total: Int64) -> Void
    private let configuration: URLSessionConfiguration
    private let now: () -> Date
    private let maxAttempts = 5
    private var current: Attempt?
    private var refusedToken: String?
    private var inBackground: Bool
    private var timer: Timer?
    private var lastProgress: (Date, Int64) = (.distantPast, 0)
    private lazy var session = URLSession(configuration: configuration, delegate: self, delegateQueue: .main)

    var onIdle: (() -> Void)?

    init(store: MPUploadStore, filesRoot: URL,
         isApproved: @escaping (_ url: String, _ route: String) -> Bool,
         changed: @escaping (_ id: String) -> Void,
         progressed: @escaping (_ id: String, _ sent: Int64, _ total: Int64) -> Void,
         inBackground: Bool, configuration: URLSessionConfiguration? = nil, now: @escaping () -> Date = Date.init) {
        self.store = store
        self.filesRoot = filesRoot
        self.isApproved = isApproved
        self.changed = changed
        self.progressed = progressed
        self.inBackground = inBackground
        self.configuration = configuration ?? MediaUploader.productionConfiguration()
        self.now = now
        super.init()
    }

    /// An ephemeral session with no cookies, cache or credentials, and a resource timeout long enough for a large video.
    static func productionConfiguration() -> URLSessionConfiguration {
        let c = URLSessionConfiguration.ephemeral
        c.waitsForConnectivity = true
        c.timeoutIntervalForRequest = 60
        c.timeoutIntervalForResource = 6 * 3600
        c.httpCookieStorage = nil
        c.httpShouldSetCookies = false
        c.urlCache = nil
        c.urlCredentialStorage = nil
        c.httpMaximumConnectionsPerHost = 1
        return c
    }

    var hasWork: Bool { store.records.values.contains { $0.state == "uploading" } }
    var isBusy: Bool { current != nil }
    var inFlightId: String? { current?.id }

    func drain() {
        assert(Thread.isMainThread)
        guard current == nil else { return }
        timer?.invalidate()
        timer = nil
        let t = now()
        let queued = store.records.values.filter { $0.state == "uploading" }.sorted { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }
        for candidate in queued {
            guard current == nil else { return }
            guard let r = store[candidate.id], r.state == "uploading" else { continue }
            if let due = r.nextAttemptAt, due > t { continue }
            if start(r) { return }
        }
        guard current == nil else { return }
        scheduleTimer()
        onIdle?()
    }

    func becameActive() {
        inBackground = false
        for r in store.records.values where r.state == "uploading" && r.nextAttemptAt != nil {
            var u = r
            u.nextAttemptAt = nil
            store.set(u)
        }
        drain()
    }

    func didEnterBackground() {
        inBackground = true
        current?.spansBackground = true
    }

    /// Background time ran out or was not granted: the attempt is abandoned uncounted.
    func expireInFlight() {
        guard let a = current else { return }
        current = nil
        a.task.cancel()
        if var r = store[a.id], r.state == "uploading" {
            r.nextAttemptAt = nil
            store.set(r)
        }
        changed(a.id)
    }

    /// The page cancelled: the attempt in flight (if it is this one) stops; the caller settles it.
    func cancel(_ id: String) {
        guard let a = current, a.id == id else { return }
        current = nil
        a.task.cancel()
        drain()
    }

    func fileURL(_ r: MPUpload) -> URL { filesRoot.appendingPathComponent(r.path) }

    private func start(_ r: MPUpload) -> Bool {
        let id = r.id
        guard isApproved(r.url, r.route), let target = MediaUploader.destination(r.url) else {
            settle(id, state: "failed", reason: "url_not_approved", status: nil)
            return false
        }
        let file = fileURL(r)
        let size = ((try? FileManager.default.attributesOfItem(atPath: file.path))?[.size] as? NSNumber)?.int64Value ?? -1
        guard size >= 0 else {
            settle(id, state: "failed", reason: "no_file", status: nil)
            return false
        }
        var request = URLRequest(url: target)
        request.httpMethod = r.method
        let token = id + "#" + UUID().uuidString.lowercased()
        let task: URLSessionTask
        var body: MPMultipartBody?
        if let field = r.field {
            let boundary = "nx-" + UUID().uuidString.lowercased()
            let (p, s) = MPCore.multipart(boundary: boundary, field: field, filename: r.name, contentType: r.contentType)
            let b = MPMultipartBody(file: file, bytes: size, prefix: p, suffix: s)
            request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
            request.setValue(String(b.length), forHTTPHeaderField: "Content-Length")
            body = b
            task = session.uploadTask(withStreamedRequest: request)
        } else {
            request.setValue(r.contentType, forHTTPHeaderField: "Content-Type")
            task = session.uploadTask(with: request, fromFile: file)
        }
        task.taskDescription = token
        refusedToken = nil
        current = Attempt(id: id, token: token, task: task, spansBackground: inBackground, body: body)
        var u = r
        u.bytes = size
        u.nextAttemptAt = nil
        if u != r {
            store.set(u)
            changed(id)
        }
        task.resume()
        return true
    }

    /// Strict destination rule: the URL is used only when its parse agrees with the approved text.
    static func destination(_ s: String) -> URL? {
        guard isStrictURLText(s) else { return nil }
        let hostCharacters = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789.-")
        guard let url = URL(string: s), url.scheme?.lowercased() == "https", url.user == nil, url.password == nil,
              url.fragment == nil, url.port == nil || url.port == 443,
              let host = url.host?.lowercased(), !host.isEmpty,
              host.unicodeScalars.allSatisfy({ hostCharacters.contains($0) }) else { return nil }
        let rest = s.lowercased().dropFirst("https://".count)
        guard s.lowercased().hasPrefix("https://"), rest.hasPrefix(host) else { return nil }
        let after = rest.dropFirst(host.count)
        guard after.isEmpty || after.hasPrefix("/") || after == ":443" || after.hasPrefix(":443/") else { return nil }
        return url
    }

    private static let urlTextCharacters: Set<UInt8> = Set(
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#[]@!$&'()*+,;=%".utf8)

    static func isStrictURLText(_ s: String) -> Bool {
        guard !s.isEmpty, s.unicodeScalars.allSatisfy({ $0.isASCII }) else { return false }
        let bytes = Array(s.utf8)
        let hex = Set("0123456789ABCDEFabcdef".utf8)
        var i = 0
        while i < bytes.count {
            let b = bytes[i]
            guard urlTextCharacters.contains(b) else { return false }
            if b == UInt8(ascii: "%") {
                guard i + 2 < bytes.count, hex.contains(bytes[i + 1]), hex.contains(bytes[i + 2]) else { return false }
                i += 3
            } else {
                i += 1
            }
        }
        return true
    }

    func complete(token: String?, statusCode: Int?, error: Error?) {
        guard let a = current, token == a.token else { return }
        current = nil
        guard let r = store[a.id], r.state == "uploading" else { return drain() }
        let refused = refusedToken == a.token
        refusedToken = nil
        switch MPCore.classify(status: statusCode, transportError: error != nil, refused: refused, spansBackground: a.spansBackground) {
        case .uploaded:
            settle(a.id, state: "uploaded", reason: nil, status: statusCode)
        case .failed(let why):
            settle(a.id, state: "failed", reason: why, status: statusCode)
        case .requeue:
            var u = r
            u.nextAttemptAt = nil
            store.set(u)
            changed(a.id)
        case .retry(let why):
            var u = r
            u.attempts += 1
            u.httpStatus = error == nil ? statusCode : nil
            if u.attempts >= maxAttempts {
                u.state = "failed"
                u.reason = why
                u.nextAttemptAt = nil
            } else {
                u.nextAttemptAt = now().addingTimeInterval(MPCore.backoff(attempt: u.attempts, base: 30, cap: 15 * 60))
            }
            store.set(u)
            changed(a.id)
        }
        drain()
    }

    private func settle(_ id: String, state: String, reason: String?, status: Int?) {
        guard var r = store[id] else { return }
        r.state = state
        r.reason = reason
        r.httpStatus = status
        r.nextAttemptAt = nil
        store.set(r)
        changed(id)
    }

    private func scheduleTimer() {
        let t = now()
        guard let due = store.records.values.filter({ $0.state == "uploading" }).compactMap({ $0.nextAttemptAt })
            .filter({ $0 > t }).min() else { return }
        let next = Timer(timeInterval: due.timeIntervalSince(t), repeats: false) { [weak self] _ in self?.drain() }
        RunLoop.main.add(next, forMode: .common)
        timer = next
    }

    // MARK: URLSession delegate (main queue)

    /// Every redirect is refused, so the 3xx becomes the task's response.
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        if let a = current, task.taskDescription == a.token { refusedToken = a.token }
        completionHandler(nil)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, needNewBodyStream completionHandler: @escaping (InputStream?) -> Void) {
        guard let a = current, task.taskDescription == a.token, let body = a.body else { return completionHandler(nil) }
        completionHandler(body.makeStream())
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        guard let a = current, task.taskDescription == a.token else { return }
        let t = now()
        // At most every half second or every 1%, so the page is not flooded.
        let total = totalBytesExpectedToSend > 0 ? totalBytesExpectedToSend : (store[a.id]?.bytes ?? 0)
        let step = max(Int64(1), total / 100)
        guard t.timeIntervalSince(lastProgress.0) >= 0.5 || totalBytesSent - lastProgress.1 >= step else { return }
        lastProgress = (t, totalBytesSent)
        progressed(a.id, totalBytesSent, total)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        complete(token: task.taskDescription, statusCode: (task.response as? HTTPURLResponse)?.statusCode, error: error)
    }
}
