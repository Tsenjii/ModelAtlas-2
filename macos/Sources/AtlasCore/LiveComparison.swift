import Foundation

/// A request contains explicit immutable offerings, never a model-name fallback.
public struct ComparisonOffer: Identifiable, Sendable {
    public let scope: ModelScope
    public let canonicalID, title, context, operation: String
    public let enabled: Bool
    public var id: String { scope.id }
    public init(model: JSONValue, title: String, context: String, operation: String) {
        scope = ModelScope(model); canonicalID = model["metadata"]["canonical_id"].string
        self.title = title; self.context = context; self.operation = operation
        enabled = model["enabled"].boolean && model["state"].string == "ACTIVE"
    }
    public static func problem(_ offers: [ComparisonOffer], prompt: String) -> String? {
        guard (2...4).contains(offers.count) else { return "請選擇 2–4 個連線。" }
        guard Set(offers.map(\.scope)).count == offers.count else { return "每個連線只能選擇一次。" }
        guard offers.allSatisfy(\.enabled) else { return "所選連線已停用、隱藏或封存，請重新選擇。" }
        guard let canonical = offers.first?.canonicalID, !canonical.isEmpty,
              offers.allSatisfy({ $0.canonicalID == canonical }) else { return "即時比較需要同一標準模型；請先確認 Canonical 對應。" }
        guard offers.allSatisfy({ ["CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC_MESSAGES", "GEMINI_NATIVE", "CUSTOM"].contains($0.operation) }) else { return "目前即時比較適用於文字對話連線。" }
        guard prompt.utf8.count <= 1000 else { return "提示詞過長，請縮短後再比較。" }
        return nil
    }
    public static func request(_ offers: [ComparisonOffer], prompt: String) throws -> JSONValue {
        if let problem = problem(offers, prompt: prompt) { throw DraftProblem(problem) }
        return .object(["selections": .array(offers.map { $0.scope.json }), "prompt": .string(prompt)])
    }
}

public struct LiveComparison: Sendable {
    public let raw: JSONValue
    public let items: [ComparisonResult]
    public init(response: JSONValue, scopes: [ModelScope]) throws {
        guard case .array(let rows) = response, rows.count == scopes.count,
              Set(scopes).count == scopes.count, (2...4).contains(scopes.count) else { throw AtlasError.invalidResponse }
        var byScope: [ModelScope: JSONValue] = [:]
        for row in rows {
            let scope = ModelScope(row["selection"])
            guard scopes.contains(scope), byScope[scope] == nil, !row["outcome"].string.isEmpty else { throw AtlasError.invalidResponse }
            byScope[scope] = row
        }
        raw = response
        items = try scopes.map { scope in
            guard let row = byScope[scope] else { throw AtlasError.invalidResponse }
            return ComparisonResult(raw: row, scope: scope)
        }
    }
}

public struct ComparisonResult: Identifiable, Sendable {
    public let raw: JSONValue
    public let scope: ModelScope
    public var id: String { scope.id }
    public var sampleID: String { raw["details"]["sample_id"].string }
    public var receipt: JSONValue { raw["details"]["receipt"] }
    public var outcome: String { raw["outcome"].string }
    public var reportedCost: String {
        let reported = receipt["cost"]["reported_charge"]
        if ModelIntelligence.number(reported["amount"]) != nil, !reported["unit"].string.isEmpty {
            return reported["amount"].display + " " + reported["unit"].string
        }
        return cost("actual")
    }
    public var ttft: Double? { raw["details"]["streaming"] == .bool(false) ? nil : ModelIntelligence.number(raw["details"]["ttft_ms"]) }
    public func metric(_ name: String) -> Double? { name == "ttft_ms" ? ttft : ModelIntelligence.number(raw["details"][name]) }
    public var quota: [ComparisonQuotaSnapshot] {
        var before: [String: JSONValue] = [:], after: [String: JSONValue] = [:]
        for row in receipt["quota_before"].array where ComparisonQuotaSnapshot.applies(row, to: scope) { before[ComparisonQuotaSnapshot.identity(row)] = row }
        for row in receipt["quota_after"].array where ComparisonQuotaSnapshot.applies(row, to: scope) { after[ComparisonQuotaSnapshot.identity(row)] = row }
        return Set(before.keys).union(after.keys).sorted().map { ComparisonQuotaSnapshot(id: $0, before: before[$0], after: after[$0]) }
    }
    public func cost(_ key: String) -> String {
        let value = receipt["cost"][key], unit = receipt["cost"]["currency"].string
        if key == "actual" && receipt["cost"]["state"].string != "PROVIDER_REPORTED" { return "—" }
        guard ModelIntelligence.number(value) != nil, !unit.isEmpty else { return "—" }
        return value.display + " " + unit
    }
}

public struct ComparisonQuotaSnapshot: Identifiable, Sendable {
    public let id: String
    public let before, after: JSONValue?
    static func applies(_ row: JSONValue, to scope: ModelScope) -> Bool {
        var candidate = row["scope"]
        // The engine represents broad account/key receipt pools with UNKNOWN
        // endpoint and no model. A model-specific endpoint must still match.
        if candidate["model_id"].string.isEmpty && candidate["endpoint"].string == "UNKNOWN" { candidate["endpoint"] = .string("") }
        return ModelIntelligence.poolApplies(ModelScope(candidate), to: scope)
    }
    static func identity(_ row: JSONValue) -> String {
        let f = row["facts"]
        return JSONValue.array([ModelScope(row["scope"]).json, row["kind"], row["source"], f["scope_kind"], f["slice"], f["unit"], f["origin"], f["credential_version"]]).pretty
    }
}
