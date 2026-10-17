import Foundation

/// Read-only projection of the engine's scoped detail response. No price or
/// availability inference belongs in the presentation layer.
public struct ModelIntelligence: Sendable {
    public let matchesSelection: Bool
    public let scope: ModelScope
    public let local: JSONValue
    public let history: [IntelligenceObservation]
    public let lastGood: [IntelligenceObservation]
    public let pools: [IntelligenceObservation]
    public let provider: [IntelligenceObservation]
    public let external: [JSONValue]

    public init(detail: JSONValue, scope: ModelScope) {
        self.scope = scope
        matchesSelection = ModelScope(detail["selection"]) == scope
        guard matchesSelection else {
            local = .null; history = []; lastGood = []; pools = []; provider = []; external = []; return
        }
        local = detail["local"]["source"].string == "MY_KEY_LOCAL" ? detail["local"] : .null
        history = detail["history"].array.filter { ModelScope($0) == scope }.map(IntelligenceObservation.init)
        lastGood = detail["last_good"].fields.values.filter { ModelScope($0) == scope && $0["status"].string == "SUCCESS" }
            .map(IntelligenceObservation.init).sorted { $0.at > $1.at }
        var quota = lastGood.filter { $0.kind.contains("QUOTA") || $0.kind == "BALANCE" }
        for row in detail["resource_pools"].array {
            let embedded = row["scope"]
            if embedded != .null {
                let candidate = ModelScope(embedded)
                guard Self.poolApplies(candidate, to: scope) else { continue }
            } else {
                guard row["provider_id"].string == scope.providerID, row["account_id"].string == scope.accountID,
                      row["role"].string == "ACCOUNT_PAT" || row["credential_id"].string == scope.credentialID else { continue }
            }
            let facts = row["facts"]
            let keys = Set(facts.fields.keys).union(facts["values"].fields.keys)
            guard !keys.isDisjoint(with: Set(Self.quotaFields.map(\.0))) else { continue }
            quota.append(IntelligenceObservation(row))
        }
        pools = Self.latestPools(quota)
        provider = (detail["provider_facts"].array + detail["account_facts"].array).filter {
            let candidate = ModelScope($0)
            return candidate.providerID == scope.providerID && candidate.modelID == scope.modelID &&
                candidate.endpoint == scope.endpoint && (candidate.accountID.isEmpty || candidate.accountID == scope.accountID) &&
                candidate.credentialID.isEmpty && (candidate.group.isEmpty || candidate.group == scope.group)
        }.map(IntelligenceObservation.init)
        external = detail["external"].array
    }

    public static func poolApplies(_ candidate: ModelScope, to selected: ModelScope) -> Bool {
        candidate.providerID == selected.providerID && candidate.accountID == selected.accountID &&
        (candidate.credentialID.isEmpty || candidate.credentialID == selected.credentialID) &&
        (candidate.modelID.isEmpty || candidate.modelID == selected.modelID) &&
        (candidate.group.isEmpty || candidate.group == selected.group) &&
        (candidate.endpoint.isEmpty || candidate.endpoint == selected.endpoint)
    }

    private static func latestPools(_ rows: [IntelligenceObservation]) -> [IntelligenceObservation] {
        var groups: [String: [IntelligenceObservation]] = [:]
        for row in rows { groups[row.seriesID, default: []].append(row) }
        return groups.values.compactMap { values in
            let sorted = values.sorted { $0.at > $1.at }
            // Keep a last-good value alongside the failed attempt, never turn a
            // failed refresh into a new zero-valued quota.
            return sorted.first(where: { $0.status == "SUCCESS" || $0.status == "SNAPSHOT" }) ?? sorted.first
        }.sorted { ($0.source, $0.kind, $0.id) < ($1.source, $1.kind, $1.id) }
    }

    public var samples: [IntelligenceSample] {
        local["history"].array.enumerated().compactMap { index, row in
            guard let at = Self.number(row["at"]), at >= 0,
                  !["RUNNING", "CANCELLED", "INTERRUPTED"].contains(row["outcome"].string) else { return nil }
            return IntelligenceSample(id: String(index), at: at, outcome: row["outcome"].string,
                httpStatus: row["http_status"], ttft: Self.number(row["ttft_ms"]),
                latency: Self.number(row["latency_ms"]), tps: Self.number(row["tps"]))
        }
    }

    public var priceTracks: [IntelligencePriceTrack] {
        var groups: [String: [IntelligenceObservation]] = [:]
        var legKeys: [String: String] = [:]
        for row in history where row.status == "SUCCESS" {
            for key in ["price", "effective_charge"] where !row.facts[key].fields.isEmpty {
                let id = row.seriesID + "|" + key + "|" + row.facts[key]["unit"].string
                groups[id, default: []].append(row)
                legKeys[id] = key
            }
        }
        return groups.keys.sorted().map { id in
            let rows = groups[id]!.sorted { $0.at < $1.at }
            let key = legKeys[id]!
            let first = rows[0]
            return IntelligencePriceTrack(id: id, label: first.source + " · " + (key == "price" ? "標價" : "實際扣款") + " · " + first.facts[key]["unit"].display,
                legKey: key, observations: rows)
        }
    }

    /// Decimal text remains exact in tables; Double is only a bounded plot value.
    public static func number(_ value: JSONValue) -> Double? {
        let n: Double?
        switch value { case .number(let x): n = x; case .string(let s): n = Double(s); default: n = nil }
        guard let n, n.isFinite, n >= 0 else { return nil }; return n
    }
    public static func amount(_ value: JSONValue) -> String {
        guard number(value) != nil else { return "尚無資料" }; return value.display
    }
    public static let quotaFields: [(String, String)] = [
        ("remaining", "剩餘"), ("used", "已用"), ("granted", "總額度"),
        ("rpd", "每日請求 RPD"), ("tpd", "每日 Token TPD"), ("rpm", "每分鐘請求 RPM"), ("tpm", "每分鐘 Token TPM"),
        ("quota", "帳戶額度"), ("used_quota", "帳戶已用額度"), ("request_count", "請求數"),
        ("total_available", "可用額度"), ("total_granted", "授予額度"), ("total_used", "已用額度")
    ]
}

public struct IntelligenceObservation: Identifiable, Hashable, Sendable {
    public let raw: JSONValue
    public var id: String { raw["id"].string.isEmpty ? raw.pretty : raw["id"].string }
    public var kind: String { raw["kind"].string.isEmpty ? raw["endpoint"].string : raw["kind"].string }
    public var source: String { raw["source"].string }
    public var status: String { raw["status"].string.isEmpty ? "SNAPSHOT" : raw["status"].string }
    public var facts: JSONValue { raw["facts"] }
    public var at: Double { ModelIntelligence.number(raw["observed_at"]) ?? 0 }
    public var selection: JSONValue { raw["scope"] == .null ? raw : raw["scope"] }
    public var seriesID: String {
        JSONValue.array([.string(kind), .string(source), ModelScope(selection).json,
            facts["scope_kind"], facts["slice"], facts["origin"], facts["credential_version"], raw["role"]]).pretty
    }
    public init(_ raw: JSONValue) { self.raw = raw }
    public var scopeDescription: String {
        let v = ModelScope(selection)
        var parts = [facts["scope_kind"].string]
        parts += ["帳戶 " + v.accountID]
        if !v.credentialID.isEmpty { parts.append((raw["role"].string == "ACCOUNT_PAT" ? "管理憑證 " : "Key ") + v.credentialID) }
        if !v.modelID.isEmpty { parts.append("模型 " + v.modelID) }
        if !v.group.isEmpty { parts.append("群組 " + v.group) }
        if !v.endpoint.isEmpty { parts.append("端點 " + v.endpoint) }
        if !facts["slice"].string.isEmpty { parts.append(facts["slice"].string) }
        return parts.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

public struct IntelligenceSample: Identifiable, Sendable {
    public let id: String
    public let at: Double
    public let outcome: String
    public let httpStatus: JSONValue
    public let ttft, latency, tps: Double?
    public func value(_ metric: String) -> Double? {
        switch metric { case "ttft": return ttft; case "tps": return tps; case "latency": return latency; default: return outcome == "SUCCESS" ? 1 : 0 }
    }
}

public struct IntelligencePriceTrack: Identifiable, Sendable {
    public let id, label, legKey: String
    public let observations: [IntelligenceObservation]
}
