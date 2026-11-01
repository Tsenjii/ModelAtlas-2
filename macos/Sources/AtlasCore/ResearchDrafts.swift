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
        guard Self.validIdentifier(name) else { throw DraftProblem("規則 ID 請使用英文字母、數字或 . _ - / :，最多 256 字元。") }
        guard path.hasPrefix("/"), !path.hasPrefix("//"), !path.contains(".."), !path.contains("?"), !path.contains("#") else { throw DraftProblem("端點需為相對於已綁定來源的唯讀 Path，例如 /v1/quota。") }
        guard !effective || (kind == "PRICING" && scope == "MODEL_KEY_GROUP" && role == "INFERENCE") else { throw DraftProblem("實際費率只適用推論 Key 的模型／群組價格證據。") }
        guard (1...12).contains(mappings.count) else { throw DraftProblem("擷取規則需要 1–12 個欄位對照。") }
        let fields = try FieldMapping.json(mappings)
        return .object(["provider_id": .string(provider), "name": .string(name), "reason": .string(reason), "evidence_ids": .array(Self.ids(evidence)), "config": .object(["kind": .string(kind), "path": .string(path), "credential_role": .string(role), "pat_credential_id": .string(role == "ACCOUNT_PAT" ? pat : ""), "scope": .string(scope), "root_pointer": .string(root), "fields": fields, "unit": .string(unit), "fixed_charge_absent": .bool(fixedAbsent), "prices_are_effective": .bool(effective)])])
    }
    private static func validIdentifier(_ text: String) -> Bool {
        let bytes = Array(text.utf8)
        return (1...256).contains(bytes.count) && bytes.allSatisfy { (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || [UInt8(46), 95, 45, 47, 58].contains($0) }
    }
    public static func ids(_ text: String) -> [JSONValue] { text.split(whereSeparator: { $0 == "," || $0.isNewline }).map { .string($0.trimmingCharacters(in: .whitespacesAndNewlines)) }.filter { $0 != .string("") } }
}

public enum ResearchRequest {
    public static func body(sources: [String], selections: [ModelScope], budget: String, probes: String, minimal: Bool, search: Bool, lmspeed: Bool, aa: Bool) throws -> JSONValue {
        guard let http = Int(budget), (1...200).contains(http), let inference = Int(probes), (0...10).contains(inference), !minimal || inference > 0, sources.count <= 32, selections.count <= 32 else { throw DraftProblem("HTTP 預算需為 1–200；啟用推論驗證時請明確設定 1–10 次。") }
        return .object(["source_ids": .array(sources.map(JSONValue.string)), "selections": .array(selections.map(\.json)), "budget": .number(Double(http)), "probe_budget": .number(minimal ? Double(inference) : 0), "minimal_inference": .bool(minimal), "search": .bool(search), "lmspeed_seeds": .bool(lmspeed), "refresh_aa": .bool(aa)])
    }
}
