import Foundation

public struct WorkspaceDraft: Sendable, CustomStringConvertible {
    public let url: String
    public let label: String
    private let code: String
    public var description: String { "WorkspaceDraft(redacted)" }

    public init(url: String, label: String, code: String) throws {
        let address = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let parts = URLComponents(string: address), parts.scheme?.lowercased() == "https",
              let host = parts.host, !host.isEmpty, parts.user == nil, parts.password == nil,
              parts.query == nil, parts.fragment == nil, parts.path.isEmpty || parts.path == "/",
              parts.port == nil || (1...65535).contains(parts.port!), let endpoint = parts.url else {
            throw WorkspaceDraftError.invalidURL
        }
        let name = label.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name.utf8.count <= 120,
              !name.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            throw WorkspaceDraftError.invalidLabel
        }
        let value = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value.utf8.count == 43, value.utf8.allSatisfy({
            (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 95
        }) else { throw WorkspaceDraftError.invalidCode }
        self.url = endpoint.absoluteString.hasSuffix("/") ? String(endpoint.absoluteString.dropLast()) : endpoint.absoluteString
        self.label = name; self.code = value
    }
    public var body: JSONValue { .object([
        "url": .string(url), "label": .string(label), "code": .string(code), "platform": .string("macOS")
    ]) }
}

public enum WorkspaceDraftError: Error, LocalizedError {
    case invalidURL, invalidLabel, invalidCode
    public var errorDescription: String? {
        switch self {
        case .invalidURL: return "請輸入同步中心的 HTTPS 網址，不含帳密、路徑或查詢參數。"
        case .invalidLabel: return "請填寫較短的裝置名稱，不含換行或控制字元。"
        case .invalidCode: return "請貼上同步中心產生的完整一次性配對碼。"
        }
    }
}
