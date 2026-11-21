import Foundation

// Pure decisions of the media picker (Foundation only, no UIKit, AVFoundation or Network).
// Every rule here has a twin in android/.../MediaCore.java, and both follow the same
// shared rules. The platform files only translate.

/// What the page asked to pick: categories (`image`, `video`, `audio`, `document`) and MIME types
/// (`application/pdf`, `image/*`). Open form: any MIME type the platform knows is accepted.
struct MPTypes: Equatable {
    var categories: Set<String>
    var mimes: [String]

    var isAny: Bool { categories.contains("any") || (categories.isEmpty && mimes.isEmpty) }

    /// Everything asked for can be shown by the photo library (images and videos only).
    var visualOnly: Bool {
        guard !isAny else { return false }
        let catsVisual = categories.allSatisfy { $0 == "image" || $0 == "video" }
        let mimesVisual = mimes.allSatisfy { $0.hasPrefix("image/") || $0.hasPrefix("video/") }
        return catsVisual && mimesVisual
    }

    var wantsImages: Bool { isAny || categories.contains("image") || mimes.contains { $0.hasPrefix("image/") } }
    var wantsVideos: Bool { isAny || categories.contains("video") || mimes.contains { $0.hasPrefix("video/") } }
}

struct MPRange: Equatable {
    let status: Int
    let start: Int64
    let end: Int64
}

struct MPRequest: Equatable {
    let method: String
    let target: String
    let headers: [String: String]
}

enum MPHead: Equatable {
    case needMore
    case bad(Int)
    case request(MPRequest, consumed: Int)
}

struct MPTarget: Equatable {
    let width: Int
    let height: Int
    let bitrateKbps: Int
}

enum MPOutcome: Equatable {
    case uploaded
    case failed(String)
    case requeue
    case retry(String)
}

enum MPCore {
    static let categoryWords: Set<String> = ["image", "video", "audio", "document", "any"]
    static let maxTypeTokens = 32
    static let maxHead = 16 * 1024
    static let maxNameScalars = 120
    /// Space and horizontal tab only (the same set as MediaCore.java), never Unicode spaces.
    static let spaces = CharacterSet(charactersIn: " \t")

    // MARK: Types and sources

    /// nil: the page's `types` is not a list of categories and MIME types (`bad_value`).
    static func parseTypes(_ raw: String?) -> MPTypes? {
        var out = MPTypes(categories: [], mimes: [])
        guard let raw = raw else { return out }
        let tokens = raw.split(separator: ",", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: MPCore.spaces).lowercased() }
        if tokens.count == 1, tokens[0].isEmpty { return out }
        guard tokens.count <= maxTypeTokens else { return nil }
        for t in tokens {
            if categoryWords.contains(t) {
                out.categories.insert(t)
            } else if isMime(t) {
                if !out.mimes.contains(t) { out.mimes.append(t) }
            } else {
                return nil
            }
        }
        return out
    }

    private static let mimeChars = Set("abcdefghijklmnopqrstuvwxyz0123456789!#$&^_.+-")

    static func isMime(_ s: String) -> Bool {
        let parts = s.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return false }
        func token(_ p: Substring) -> Bool {
            guard let first = p.first, p.count <= 64, first.isLetter || first.isNumber, first.isASCII else { return false }
            return p.allSatisfy { mimeChars.contains($0) }
        }
        return token(parts[0]) && (parts[1] == "*" || token(parts[1]))
    }

    /// The picker to show: `library`, `files`, `camera` or `sheet` (a native choice of the three);
    /// nil when the request cannot be met by that source (`bad_value`).
    static func source(requested: String, types: MPTypes) -> String? {
        switch requested {
        case "auto":
            if types.visualOnly { return "library" }
            if !types.isAny && !types.wantsImages && !types.wantsVideos { return "files" }
            return "sheet"
        case "library":
            return types.isAny || types.visualOnly || types.wantsImages || types.wantsVideos ? "library" : nil
        case "camera":
            return types.wantsImages || types.wantsVideos ? "camera" : nil
        case "files":
            return "files"
        default:
            return nil
        }
    }

    /// `photo`, `video` or `both`: what the camera offers for these types and the page's choice.
    static func cameraMode(types: MPTypes, requested: String) -> String? {
        let canPhoto = types.wantsImages, canVideo = types.wantsVideos
        switch requested {
        case "photo": return canPhoto ? "photo" : nil
        case "video": return canVideo ? "video" : nil
        default: return canPhoto && canVideo ? "both" : (canPhoto ? "photo" : (canVideo ? "video" : nil))
        }
    }

    static func kind(mime: String) -> String {
        let m = mime.lowercased()
        if m.hasPrefix("image/") { return "image" }
        if m.hasPrefix("video/") { return "video" }
        if m.hasPrefix("audio/") { return "audio" }
        return "document"
    }

    /// The selection limit handed to the platform picker, and whether it was lowered to the
    /// platform's own maximum (`platformMax` 0: the platform has none).
    static func limit(requested: Int, multiple: Bool, platformMax: Int) -> (Int, Bool) {
        guard multiple else { return (1, false) }
        if requested <= 0 { return (platformMax, false) }
        if platformMax > 0 && requested > platformMax { return (platformMax, true) }
        return (requested, false)
    }

    /// Per file, in pick order: nil (admitted), `too_large` or `total_exceeded`. 0 MiB is no limit.
    static func admit(sizes: [Int64], maxFileMB: Int, maxTotalMB: Int) -> [String?] {
        let mib: Int64 = 1024 * 1024
        var total: Int64 = 0
        return sizes.map { s in
            if maxFileMB > 0 && s > Int64(maxFileMB) * mib { return "too_large" }
            if maxTotalMB > 0 && total + s > Int64(maxTotalMB) * mib { return "total_exceeded" }
            total += s
            return nil
        }
    }

    // MARK: Names and ids

    private static func isHidden(_ v: UInt32) -> Bool {
        v < 0x20 || (0x7F...0x9F).contains(v) || (0x200B...0x200F).contains(v) || (0x202A...0x202E).contains(v)
            || (0x2066...0x2069).contains(v) || v == 0xFEFF
    }

    /// The display name the page sees and the server path ends with: the last path component of
    /// what the platform reported, without control, invisible or bidi code points, no leading dots
    /// or spaces, at most 120 code points with the extension kept. Never empty.
    static func safeName(_ raw: String, fallbackExtension: String) -> String {
        var s = raw
        if let i = s.lastIndex(where: { $0 == "/" || $0 == "\\" }) { s = String(s[s.index(after: i)...]) }
        var scalars = String.UnicodeScalarView()
        for u in s.unicodeScalars where !isHidden(u.value) { scalars.append(u) }
        s = String(scalars)
        while let f = s.first, f == "." || f == " " { s.removeFirst() }
        while let l = s.last, l == " " { s.removeLast() }
        if s.isEmpty { return "file" + fallbackExtension }
        let all = Array(s.unicodeScalars)
        if all.count <= maxNameScalars { return s }
        var ext: [Unicode.Scalar] = []
        if let dot = all.lastIndex(of: "."), all.count - dot <= 16, dot > 0 { ext = Array(all[dot...]) }
        var view = String.UnicodeScalarView()
        view.append(contentsOf: all[0..<(maxNameScalars - ext.count)])
        view.append(contentsOf: ext)
        return String(view)
    }

    static func isId(_ s: String) -> Bool {
        let u = Array(s.utf8)
        guard u.count == 36 else { return false }
        for (i, c) in u.enumerated() {
            if i == 8 || i == 13 || i == 18 || i == 23 {
                if c != UInt8(ascii: "-") { return false }
            } else if !((c >= 0x30 && c <= 0x39) || (c >= 0x61 && c <= 0x66)) {
                return false
            }
        }
        return true
    }

    /// A file token: 43 characters of URL-safe base64 (32 random bytes, no padding).
    static func isToken(_ s: String) -> Bool {
        let u = Array(s.utf8)
        return u.count == 43 && u.allSatisfy {
            ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x41 && $0 <= 0x5A) || ($0 >= 0x61 && $0 <= 0x7A) || $0 == 0x2D || $0 == 0x5F
        }
    }

    static func token(fromBytes b: [UInt8]) -> String {
        Data(b).base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    // MARK: Loopback HTTP

    /// One request head from the bytes read so far. A head without its blank line is `needMore`
    /// until it reaches 16 KiB (431). Methods and headers are checked strictly; anything this server
    /// does not understand is 400.
    static func parseHead(_ bytes: [UInt8]) -> MPHead {
        var end = -1
        if bytes.count >= 4 {
            for i in 0...(bytes.count - 4) where bytes[i] == 13 && bytes[i + 1] == 10 && bytes[i + 2] == 13 && bytes[i + 3] == 10 {
                end = i
                break
            }
        }
        if end < 0 { return bytes.count >= maxHead ? .bad(431) : .needMore }
        if end + 4 > maxHead { return .bad(431) }
        // ASCII only; CR and LF only as a CRLF pair; no other control but HT.
        for i in 0..<end {
            let b = bytes[i]
            if b >= 0x7F { return .bad(400) }
            if b == 13 && bytes[i + 1] != 10 { return .bad(400) }
            if b == 10 && (i == 0 || bytes[i - 1] != 13) { return .bad(400) }
            if b < 0x20 && b != 9 && b != 10 && b != 13 { return .bad(400) }
        }
        guard let text = String(bytes: bytes[0..<end], encoding: .ascii) else { return .bad(400) }
        let lines = text.components(separatedBy: "\r\n")
        let first = lines[0].split(separator: " ", omittingEmptySubsequences: false)
        guard first.count == 3 else { return .bad(400) }
        let method = String(first[0]), target = String(first[1]), version = String(first[2])
        guard !method.isEmpty, method.utf8.allSatisfy({ $0 >= 0x41 && $0 <= 0x5A }),
              target.hasPrefix("/"), version == "HTTP/1.1" || version == "HTTP/1.0" else { return .bad(400) }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":"), colon != line.startIndex,
                  let f = line.first, f != " ", f != "\t" else { return .bad(400) }
            let name = line[..<colon].lowercased()
            guard name.utf8.allSatisfy({ $0 > 0x20 && $0 < 0x7F && $0 != 0x3A }) else { return .bad(400) }
            let value = line[line.index(after: colon)...].trimmingCharacters(in: MPCore.spaces)
            if name == "range", headers[name] != nil { return .bad(400) }
            if headers[name] == nil { headers[name] = value }
        }
        return .request(MPRequest(method: method, target: target, headers: headers), consumed: end + 4)
    }

    /// The token of `/f/<token>` or `/f/<token>/<name>`; the query, if any, is ignored.
    static func token(fromTarget target: String) -> String? {
        var path = Substring(target)
        if let q = path.firstIndex(where: { $0 == "?" || $0 == "#" }) { path = path[..<q] }
        let parts = path.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 3 || parts.count == 4, parts[0].isEmpty, parts[1] == "f" else { return nil }
        let t = String(parts[2])
        return isToken(t) ? t : nil
    }

    /// One byte range of a file of `size` bytes (RFC 9110 14.2): 206 with the range, 416 when
    /// unsatisfiable, 200 for the whole file when there is no Range or one this server ignores
    /// (another unit, several ranges, or a malformed one, which the RFC allows ignoring).
    static func range(_ header: String?, size: Int64) -> MPRange {
        let whole = MPRange(status: 200, start: 0, end: size - 1)
        guard let h = header?.trimmingCharacters(in: MPCore.spaces), !h.isEmpty else { return whole }
        guard h.lowercased().hasPrefix("bytes=") else { return whole }
        let spec = h.dropFirst(6).trimmingCharacters(in: MPCore.spaces)
        guard !spec.contains(","), let dash = spec.firstIndex(of: "-") else { return whole }
        let a = String(spec[..<dash]).trimmingCharacters(in: MPCore.spaces)
        let b = String(spec[spec.index(after: dash)...]).trimmingCharacters(in: MPCore.spaces)
        func num(_ s: String) -> Int64? {
            guard !s.isEmpty, s.count <= 18, s.utf8.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }) else { return nil }
            return Int64(s)
        }
        let unsatisfiable = MPRange(status: 416, start: 0, end: -1)
        if a.isEmpty {
            guard let n = num(b) else { return whole }
            if n == 0 || size == 0 { return unsatisfiable }
            return MPRange(status: 206, start: max(0, size - n), end: size - 1)
        }
        guard let first = num(a) else { return whole }
        if b.isEmpty {
            return first >= size ? unsatisfiable : MPRange(status: 206, start: first, end: size - 1)
        }
        guard let last = num(b), last >= first else { return whole }
        if first >= size { return unsatisfiable }
        return MPRange(status: 206, start: first, end: min(last, size - 1))
    }

    static let reasons: [Int: String] = [200: "OK", 204: "No Content", 206: "Partial Content", 400: "Bad Request",
                                         404: "Not Found", 405: "Method Not Allowed", 416: "Range Not Satisfiable",
                                         431: "Request Header Fields Too Large", 503: "Service Unavailable"]

    /// The response head. The URL is the capability (no cookie or credential is ever read), so any
    /// origin may read a file whose URL it holds; nothing is cached; every response closes.
    static func responseHead(status: Int, mime: String?, size: Int64, range: MPRange?, method: String,
                             privateNetwork: Bool) -> String {
        var h: [(String, String)] = []
        var length: Int64 = 0
        if status == 200 || status == 206 {
            h.append(("Content-Type", mime ?? "application/octet-stream"))
            h.append(("Accept-Ranges", "bytes"))
            if status == 206, let r = range {
                h.append(("Content-Range", "bytes \(r.start)-\(r.end)/\(size)"))
                length = r.end - r.start + 1
            } else {
                length = size
            }
        } else if status == 416 {
            h.append(("Content-Range", "bytes */\(size)"))
        }
        if status == 204 {
            h.append(("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS"))
            h.append(("Access-Control-Allow-Headers", "Range"))
            h.append(("Access-Control-Max-Age", "600"))
            if privateNetwork { h.append(("Access-Control-Allow-Private-Network", "true")) }
        } else {
            h.append(("Content-Length", String(length)))
        }
        h.append(("Access-Control-Allow-Origin", "*"))
        if status == 200 || status == 206 {
            h.append(("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges"))
        }
        h.append(("Cache-Control", "no-store"))
        h.append(("Cross-Origin-Resource-Policy", "cross-origin"))
        h.append(("X-Content-Type-Options", "nosniff"))
        if status == 405 { h.append(("Allow", "GET, HEAD, OPTIONS")) }
        h.append(("Connection", "close"))
        var out = "HTTP/1.1 \(status) \(reasons[status] ?? "Error")\r\n"
        for (k, v) in h { out += "\(k): \(v)\r\n" }
        return out + "\r\n"
    }

    /// Status for a parsed request against the file table: 204 preflight, 405 other methods,
    /// 404 unknown token, else the range status. HEAD gets the same head as GET without a body.
    static func status(method: String, known: Bool, range r: MPRange) -> Int {
        if method == "OPTIONS" { return 204 }
        guard method == "GET" || method == "HEAD" else { return 405 }
        guard known else { return 404 }
        return r.status
    }

    // MARK: Video targets

    static let presetShortSide: [String: Int] = ["480p": 480, "540p": 540, "720p": 720, "1080p": 1080,
                                                  "1440p": 1440, "2160p": 2160]

    /// Output size and bitrate for a source of `width` x `height` (as displayed). Never upscales:
    /// the short side is the preset's or the source's, whichever is smaller; the long side keeps the
    /// aspect ratio; both are even. A given bitrate is used as is; the default comes from the table
    /// and never exceeds the source's own bitrate when that is known. nil for an unknown preset or
    /// a source without a size.
    static func target(width: Int, height: Int, preset: String, codec: String, bitrateKbps: Int,
                       sourceKbps: Int, frameRate: Double) -> MPTarget? {
        guard width > 0, height > 0 else { return nil }
        let srcShort = min(width, height), srcLong = max(width, height)
        let want: Int
        if preset == "source" { want = srcShort } else {
            guard let p = presetShortSide[preset] else { return nil }
            want = p
        }
        let short = min(srcShort, want)
        let outShort = max(2, short - short % 2)
        let longD = Double(srcLong) * Double(outShort) / Double(srcShort)
        var outLong = Int(longD.rounded())
        outLong = max(2, outLong - outLong % 2)
        let w = width >= height ? outLong : outShort
        let h = width >= height ? outShort : outLong
        var kbps = bitrateKbps
        if kbps <= 0 {
            let table: [(Int, Int)] = [(480, 2000), (540, 2500), (720, 5000), (1080, 8000), (1440, 16000)]
            var base = 35000
            for (side, rate) in table where outShort <= side { base = rate; break }
            if codec == "hevc" { base = Int((Double(base) * 0.6).rounded()) }
            if frameRate > 31 { base = Int((Double(base) * 1.5).rounded()) }
            if sourceKbps > 0 { base = min(base, sourceKbps) }
            kbps = base
        }
        return MPTarget(width: w, height: h, bitrateKbps: kbps)
    }

    /// `bitrate_kbps` arrives as text (the schema bounds a declared int maximum at 4096): digits only,
    /// 100 to 400000, or empty for the default (0).
    static func bitrate(_ raw: String?) -> Int? {
        guard let s = raw, !s.isEmpty else { return 0 }
        guard s.count <= 6, s.utf8.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }), let n = Int(s), n >= 100, n <= 400_000 else { return nil }
        return n
    }

    // MARK: Upload

    /// The upload outcome rules:
    /// 2xx uploaded; 3xx final (redirect_refused when the delegate refused it); 4xx final; a
    /// transport error that spans background is requeued uncounted; else a counted retry.
    static func classify(status: Int?, transportError: Bool, refused: Bool, spansBackground: Bool) -> MPOutcome {
        if let c = status, (300..<400).contains(c) { return .failed(refused ? "redirect_refused" : "http") }
        if let c = status, (400..<500).contains(c) { return .failed("http") }
        if !transportError, let c = status, (200..<300).contains(c) { return .uploaded }
        if transportError && spansBackground { return .requeue }
        return .retry(transportError ? "network" : "http")
    }

    static func backoff(attempt: Int, base: Double, cap: Double) -> Double {
        min(cap, base * pow(2, Double(max(attempt, 1) - 1)))
    }

    static func formName(_ s: String) -> Bool {
        let u = Array(s.utf8)
        return !u.isEmpty && u.count <= 64 && u.allSatisfy {
            ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x41 && $0 <= 0x5A) || ($0 >= 0x61 && $0 <= 0x7A) || $0 == 0x2D || $0 == 0x5F || $0 == 0x2E
        }
    }

    /// multipart/form-data framing around the file bytes (RFC 7578). The field name is checked by
    /// `formName`; in the file name, quote, backslash and control characters become "_".
    static func multipart(boundary: String, field: String, filename: String, contentType: String) -> (String, String) {
        var safe = String.UnicodeScalarView()
        for u in filename.unicodeScalars {
            safe.append(u.value < 0x20 || u.value == 0x7F || u == "\"" || u == "\\" ? "_" : u)
        }
        let prefix = "--\(boundary)\r\nContent-Disposition: form-data; name=\"\(field)\"; filename=\"\(String(safe))\"\r\n"
            + "Content-Type: \(contentType)\r\n\r\n"
        return (prefix, "\r\n--\(boundary)--\r\n")
    }

    /// `content_type` from the page: a MIME type with no parameters, or nil (`bad_value`).
    static func contentType(_ raw: String?, fallback: String) -> String? {
        guard let s = raw, !s.isEmpty else { return fallback }
        return isMime(s.lowercased()) && !s.hasSuffix("/*") ? s.lowercased() : nil
    }

    // MARK: Clean-up

    /// Directory names under the files root to delete at launch: everything except the files an
    /// unfinished upload still needs.
    static func cleanup(entries: [String], keep: Set<String>) -> [String] {
        entries.filter { !keep.contains($0) }.sorted()
    }

    /// The end state of a transcode. One that already ended while the transcoder stopped (a
    /// background expiry set failed/background_expired, the cancel route set cancelled) keeps that
    /// state and reason; otherwise the transcoder's outcome (done, cancelled, failed) decides.
    static func settle(state: String, reason: String?, outcome: String, outcomeReason: String?) -> (state: String, reason: String?) {
        if state == "failed" || state == "cancelled" { return (state, reason ?? outcomeReason ?? state) }
        switch outcome {
        case "done": return ("done", nil)
        case "cancelled": return ("cancelled", "cancelled")
        default: return ("failed", outcomeReason ?? "failed")
        }
    }

    /// What may be done with the upload index as it was read: missing (a first run), loaded,
    /// recovered (it did not decode and was copied aside), or unread (it exists but could not be
    /// read, as before the first unlock). An unread index is never pruned, written over or used to
    /// clean up files, and takes no new upload, until a later read succeeds: the uploads it holds
    /// still need their files.
    static func indexPlan(_ index: String) -> [String] {
        index == "unread" ? [] : ["prune", "persist", "cleanUp", "accept"]
    }
}
