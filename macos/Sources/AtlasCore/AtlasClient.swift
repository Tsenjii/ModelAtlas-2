import Foundation

public struct StreamEvent: Sendable {
    public let name: String
    public let value: JSONValue
    public init(name: String, value: JSONValue) { self.name = name; self.value = value }
}

// A bounded SSE decoder shared by the native chat and Assistant. It retains
// multiline events, ignores comments, and never treats a truncated stream as done.
public struct EventDecoder {
    private var name = "message"
    private var lines: [String] = []
    private var size = 0
    public init() {}
    public mutating func consume(_ raw: String) throws -> StreamEvent? {
        let line = raw.hasSuffix("\r") ? String(raw.dropLast()) : raw
        if line.isEmpty {
            defer { name = "message"; lines = []; size = 0 }
            guard !lines.isEmpty else { return nil }
            guard let value = try? JSONValue.parse(lines.joined(separator: "\n")) else { throw AtlasError.invalidResponse }
            return StreamEvent(name: name, value: value)
        }
        size += line.utf8.count + 1
        guard size <= 1_048_576 else { throw AtlasError.invalidResponse }
        guard !line.hasPrefix(":") else { return nil }
        let pieces = line.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
        var content = pieces.count > 1 ? String(pieces[1]) : ""
        if content.hasPrefix(" ") { content.removeFirst() }
        if pieces[0] == "event" { name = content }
        if pieces[0] == "data" {
            lines.append(content)
        }
        return nil
    }
}

// Frame bytes ourselves so empty event delimiters, split UTF-8 sequences, and
// CR/LF/CRLF all survive. Bound a line before allocating an unbounded String.
public struct EventFramer {
    private var decoder = EventDecoder()
    private var line: [UInt8] = []
    private var afterCR = false
    public init() {}
    public mutating func consume(_ byte: UInt8) throws -> StreamEvent? {
        if byte == 10, afterCR { afterCR = false; return nil }
        afterCR = byte == 13
        if byte == 10 || byte == 13 {
            guard let value = String(bytes: line, encoding: .utf8) else { throw AtlasError.invalidResponse }
            line.removeAll(keepingCapacity: true)
            return try decoder.consume(value)
        }
        guard line.count < 1_048_576 else { throw AtlasError.invalidResponse }
        line.append(byte)
        return nil
    }
}

private final class NoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

public final class AtlasClient: @unchecked Sendable {
    public let baseURL: URL
    private let token: String
    private let session: URLSession
    private let delegate = NoRedirects()

    public init(baseURL: URL, token: String) throws {
        guard baseURL.scheme == "http", baseURL.host == "127.0.0.1", let port = baseURL.port,
              port > 0, baseURL.user == nil, baseURL.password == nil, baseURL.query == nil,
              baseURL.fragment == nil, baseURL.path.isEmpty || baseURL.path == "/" else { throw AtlasError.invalidEndpoint }
        self.baseURL = baseURL; self.token = token
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil; config.httpCookieStorage = nil; config.httpShouldSetCookies = false
        config.connectionProxyDictionary = [:]; config.timeoutIntervalForRequest = 135; config.timeoutIntervalForResource = 150
        session = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
    }
    deinit { session.invalidateAndCancel() }

    private func request(_ path: String, method: String = "GET", body: JSONValue? = nil,
                         query: [URLQueryItem] = []) throws -> URLRequest {
        guard path.hasPrefix("/"), !path.contains(".."), !path.contains("?"), !path.contains("#") else { throw AtlasError.invalidEndpoint }
        var parts = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)!
        parts.path = "/api/v1" + path
        if !query.isEmpty { parts.queryItems = query }
        var request = URLRequest(url: parts.url!)
        request.httpMethod = method; request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.httpBody = try JSONEncoder().encode(body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        return request
    }
    private func check(_ response: URLResponse, data: Data? = nil) throws {
        guard let response = response as? HTTPURLResponse else { throw AtlasError.invalidResponse }
        guard (200..<300).contains(response.statusCode) else {
            if let data, data.count <= 8192, let value = try? JSONDecoder().decode(JSONValue.self, from: data), let problem = ProviderProblem(value) { throw AtlasError.provider(problem) }
            throw AtlasError.http(response.statusCode)
        }
    }
    public func get(_ path: String, query: [URLQueryItem] = []) async throws -> JSONValue {
        try await call(path, query: query)
    }
    public func call(_ path: String, method: String = "GET", body: JSONValue? = nil,
                     query: [URLQueryItem] = []) async throws -> JSONValue {
        let (data, response) = try await session.data(for: request(path, method: method, body: body, query: query))
        try check(response, data: data)
        guard data.count <= 20 << 20 else { throw AtlasError.invalidResponse }
        return try JSONDecoder().decode(JSONValue.self, from: data)
    }
    public func stream(_ path: String, body: JSONValue, receive: @escaping @MainActor (StreamEvent) -> Void) async throws {
        var request = try request(path, method: "POST", body: body)
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        let (bytes, response) = try await session.bytes(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            var errorData = Data()
            for try await byte in bytes { if errorData.count >= 8192 { break }; errorData.append(byte) }
            try check(response, data: errorData)
        }
        try check(response)
        guard (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Type")?.hasPrefix("text/event-stream") == true else { throw AtlasError.invalidResponse }
        var decoder = EventFramer(), terminal = false
        let delivery = StreamDelivery(receive: receive)
        do {
            for try await byte in bytes {
                try Task.checkCancellation()
                if let event = try decoder.consume(byte) {
                    if !(try delivery.enqueue(event)) {
                        await delivery.finish()
                        try Task.checkCancellation()
                        guard try delivery.enqueue(event) else { throw AtlasError.invalidResponse }
                    }
                    if event.name == "done" || event.name == "error" { terminal = true }
                }
            }
            await delivery.finish()
            guard terminal else { throw AtlasError.invalidResponse }
        } catch {
            delivery.cancel(); await delivery.finish(); throw error
        }
    }
    public func download(_ path: String, query: [URLQueryItem] = [], to destination: URL) async throws {
        let (temporary, response) = try await session.download(for: request(path, query: query))
        defer { try? FileManager.default.removeItem(at: temporary) }
        try check(response)
        // NSSavePanel already owns overwrite confirmation; replacement is atomic.
        let staging = destination.deletingLastPathComponent().appendingPathComponent(".modelatlas-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: staging) }
        try FileManager.default.copyItem(at: temporary, to: staging)
        if FileManager.default.fileExists(atPath: destination.path) {
            _ = try FileManager.default.replaceItemAt(destination, withItemAt: staging)
        } else { try FileManager.default.moveItem(at: staging, to: destination) }
    }
}
