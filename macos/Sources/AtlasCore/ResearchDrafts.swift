import Foundation

public struct FieldMapping: Identifiable, Equatable, Sendable {
    public let id = UUID()
    public var name: String, pointer: String
    public init(name: String = "", pointer: String = "") { self.name = name; self.pointer = pointer }
    public static func values(_ json: JSONValue) -> [FieldMapping] { json.fields.sorted { $0.key < $1.key }.map { FieldMapping(name: $0.key, pointer: $0.value.string) } }
    public static func json(_ mappings: [FieldMapping]) throws -> JSONValue {
        var fields: [String: JSONValue] = [:]
        for mapping in mappings {
            guard !mapping.name.isEmpty, fields[mapping.name] == nil, mapping.pointer.isEmpty || mapping.pointer.hasPrefix("/") else { throw DraftProblem("欄位名稱不能重複，JSON Pointer 請以 / 開頭。") }
            fields[mapping.name] = .string(mapping.pointer)
        }
        return .object(fields)
    }
}

public struct ConnectorDraft: Equatable, Sendable {
    public var provider = "", name = "quota", kind = "QUOTA", path = "/v1/quota", role = "INFERENCE", pat = "", scope = "MODEL_KEY_GROUP", root = "", unit = "REQUESTS", reason = "", evidence = ""
    public var fixedAbsent = false, effective = false
    public var mappings = [FieldMapping(name: "model_id", pointer: "/model"), FieldMapping(name: "group", pointer: "/group"), FieldMapping(name: "rpd", pointer: "/limits/rpd"), FieldMapping(name: "tpd", pointer: "/limits/tpd")]
    public init() {}
    public init(_ version: JSONValue) {
        self.init(); let config = version["config"]
        provider = version["provider_id"].string; name = version["name"].string; kind = config["kind"].string; path = config["path"].string
        role = config["credential_role"].string; pat = config["pat_credential_id"].string; scope = config["scope"].string; root = config["root_pointer"].string; unit = config["unit"].string
        fixedAbsent = config["fixed_charge_absent"].boolean; effective = config["prices_are_effective"].boolean
        mappings = FieldMapping.values(config["fields"]); evidence = version["evidence_ids"].array.map(\.string).joined(separator: ", ")
    }
    public func request() throws -> JSONValue {
        guard !provider.isEmpty, !name.isEmpty, !reason.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw DraftProblem("請選擇供應商，並填入名稱與判斷理由。") }
        guard path.hasPrefix("/"), !path.hasPrefix("//"), !path.contains(".."), !path.contains("?"), !path.contains("#") else { throw DraftProblem("端點需為相對於已綁定來源的唯讀 Path，例如 /v1/quota。") }
        guard !effective || (kind == "PRICING" && scope == "MODEL_KEY_GROUP" && role == "INFERENCE") else { throw DraftProblem("實際費率只適用推論 Key 的模型／群組價格證據。") }
        let fields = try FieldMapping.json(mappings)
        return .object(["provider_id": .string(provider), "name": .string(name), "reason": .string(reason), "evidence_ids": .array(Self.ids(evidence)), "config": .object(["kind": .string(kind), "path": .string(path), "credential_role": .string(role), "pat_credential_id": .string(role == "ACCOUNT_PAT" ? pat : ""), "scope": .string(scope), "root_pointer": .string(root), "fields": fields, "unit": .string(unit), "fixed_charge_absent": .bool(fixedAbsent), "prices_are_effective": .bool(effective)])])
    }
    public static func ids(_ text: String) -> [JSONValue] { text.split(whereSeparator: { $0 == "," || $0.isNewline }).map { .string($0.trimmingCharacters(in: .whitespacesAndNewlines)) }.filter { $0 != .string("") } }
}
