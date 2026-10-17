import Foundation

public enum JSONValue: Codable, Hashable, Sendable {
    case object([String: JSONValue]), array([JSONValue]), string(String), number(Double), bool(Bool), null

    public init(from decoder: Decoder) throws {
        let value = try decoder.singleValueContainer()
        if value.decodeNil() { self = .null }
        else if let v = try? value.decode(Bool.self) { self = .bool(v) }
        else if let v = try? value.decode(String.self) { self = .string(v) }
        else if let v = try? value.decode(Double.self) { self = .number(v) }
        else if let v = try? value.decode([String: JSONValue].self) { self = .object(v) }
        else { self = .array(try value.decode([JSONValue].self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var value = encoder.singleValueContainer()
        switch self {
        case .object(let v): try value.encode(v)
        case .array(let v): try value.encode(v)
        case .string(let v): try value.encode(v)
        case .number(let v): try value.encode(v)
        case .bool(let v): try value.encode(v)
        case .null: try value.encodeNil()
        }
    }

    public subscript(_ key: String) -> JSONValue {
        get { if case .object(let fields) = self { return fields[key] ?? .null }; return .null }
        set { if case .object(var fields) = self { fields[key] = newValue; self = .object(fields) } }
    }
    public var string: String { if case .string(let v) = self { return v }; return "" }
    public var array: [JSONValue] { if case .array(let v) = self { return v }; return [] }
    public var fields: [String: JSONValue] { if case .object(let v) = self { return v }; return [:] }
    public var boolean: Bool { if case .bool(let v) = self { return v }; return false }
    public var numeric: Double? { if case .number(let v) = self { return v }; return nil }
    public var display: String {
        switch self {
        case .null: return "尚無資料"
        case .string(let v): return v.isEmpty ? "—" : v
        case .number(let v): return v.rounded() == v ? String(format: "%.0f", v) : String(v)
        case .bool(let v): return v ? "是" : "否"
        default: return pretty
        }
    }
    public var pretty: String {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        return (try? String(decoding: encoder.encode(self), as: UTF8.self)) ?? "null"
    }
    public static func parse(_ text: String) throws -> JSONValue {
        try JSONDecoder().decode(Self.self, from: Data(text.utf8))
    }
    public func requiredArray() throws -> [JSONValue] {
        guard case .array(let result) = self else { throw AtlasError.invalidResponse }
        return result
    }
}

public struct ModelScope: Hashable, Codable, Sendable, Identifiable {
    public let providerID, accountID, credentialID, modelID, group, endpoint: String
    public init(_ value: JSONValue) {
        providerID = value["provider_id"].string; accountID = value["account_id"].string
        credentialID = value["credential_id"].string; modelID = value["model_id"].string
        group = value["group"].string; endpoint = value["endpoint"].string
    }
    public var id: String { json.pretty }
    public var json: JSONValue { .object([
        "provider_id": .string(providerID), "account_id": .string(accountID), "credential_id": .string(credentialID),
        "model_id": .string(modelID), "group": .string(group), "endpoint": .string(endpoint)
    ]) }
    public var query: [URLQueryItem] { json.fields.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value.string) } }
    enum CodingKeys: String, CodingKey {
        case providerID = "provider_id", accountID = "account_id", credentialID = "credential_id", modelID = "model_id", group, endpoint
    }
}

public enum AtlasError: Error, LocalizedError {
    case invalidResponse, http(Int), invalidEndpoint, engineUnavailable, keychain(Int32), missingVault, invalidJSON
    public var errorDescription: String? {
        switch self {
        case .invalidResponse: return "收到無法辨識的資料，請重試。"
        case .http(let code): return "操作未完成（HTTP \(code)）。請確認連線、所選帳戶與設定後重試。"
        case .invalidEndpoint: return "本機引擎位址無效。"
        case .engineUnavailable: return "本機引擎無法啟動。請確認沒有另一個 ModelAtlas 正在使用相同資料。"
        case .keychain(let status): return "無法使用 macOS 鑰匙圈（\(status)）；未建立明文備援。"
        case .missingVault: return "已有資料庫，但鑰匙圈中找不到原加密金鑰。請恢復原鑰匙圈資料，避免建立無法解密的新金鑰。"
        case .invalidJSON: return "JSON 格式無效，請檢查括號、逗號與引號。"
        }
    }
}

public enum ConversationTools {
    public static func pending(in messages: [JSONValue]) -> [JSONValue] {
        var calls: [String: JSONValue] = [:]
        for message in messages {
            if message["role"].string == "assistant" {
                for call in message["tool_calls"].array { calls[call["id"].string] = call }
            }
            if message["role"].string == "tool" { calls.removeValue(forKey: message["tool_call_id"].string) }
        }
        return calls.values.sorted { ($0["index"].numeric ?? 0, $0["id"].string) < ($1["index"].numeric ?? 0, $1["id"].string) }
    }
}
