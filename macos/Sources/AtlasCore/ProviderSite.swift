import Foundation

public struct ProviderSiteSnapshot: Sendable {
    public static let endpoints = ["status": "/api/status", "notice": "/api/notice", "pricing": "/api/pricing", "summary": "/api/perf-metrics/summary", "performance": "/api/perf-metrics"]
    public let raw: JSONValue
    public let operation: String
    public var facts: JSONValue { raw["facts"] }
    public var at: Double { raw["observed_at"].numeric ?? 0 }
    public init(_ value: JSONValue, providerID: String, operation: String, modelID: String = "", origin: String? = nil) throws {
        guard let path = Self.endpoints[operation], value["provider_id"].string == providerID,
              value["endpoint"].string == path, value["credential_role"].string == "ANONYMOUS",
              value["account_id"].string.isEmpty, value["credential_id"].string.isEmpty,
              value["source"].string == "NEW_API_OFFICIAL", let at = value["observed_at"].numeric, at > 0,
              case .object = value["facts"], operation != "performance" || value["facts"]["model_name"].string == modelID else { throw AtlasError.invalidResponse }
        if let origin, value["facts"]["origin"].string != origin { throw AtlasError.invalidResponse }
        raw = value; self.operation = operation
    }
    public static func request(providerID: String, operation: String, modelID: String = "", hours: Int = 168) throws -> JSONValue {
        guard endpoints[operation] != nil, !providerID.isEmpty else { throw AtlasError.invalidResponse }
        var body: JSONValue = .object(["provider_id": .string(providerID)])
        if operation == "performance" || operation == "summary" {
            guard (1...720).contains(hours) else { throw AtlasError.invalidResponse }
            body["hours"] = .number(Double(hours))
        }
        if operation == "performance" {
            guard !modelID.isEmpty else { throw AtlasError.invalidResponse }; body["model_id"] = .string(modelID)
        }
        return body
    }
    public var groups: [JSONValue] { facts["groups"].array }
    public func metrics(group: String) -> JSONValue? {
        if group.isEmpty { return facts["summary"] == .null ? nil : facts["summary"] }
        return groups.first { $0["group"].string == group }
    }
    public func chart(metric: String, group: String) -> TrendData? {
        guard operation == "performance", facts["timestamp_unit"].string == "UNIX_SECONDS",
              facts["success_rate_unit"].string == "PERCENT",
              ["avg_ttft_ms", "avg_tps", "avg_latency_ms", "success_rate"].contains(metric) else { return nil }
        let rows: [JSONValue]
        if group.isEmpty { rows = facts["series"].array }
        else { guard let selected = groups.first(where: { $0["group"].string == group }) else { return nil }; rows = selected["series"].array }
        let points = rows.enumerated().compactMap { index, row -> TrendPoint? in
            guard let seconds = row["ts"].numeric, seconds >= 0, seconds.isFinite else { return nil }
            return TrendPoint(id: "\(group.utf8.count):\(group):\(index):\(seconds)", at: seconds * 1000, rawValue: row[metric], note: group.isEmpty ? "站方全模型統計" : "站方群組 · " + group)
        }
        let titles = ["avg_ttft_ms":"站方首 Token 延遲", "avg_tps":"站方生成速度", "avg_latency_ms":"站方總延遲", "success_rate":"站方成功率"]
        return TrendData(points: points, unit: metric == "success_rate" ? "%" : metric == "avg_tps" ? "tokens/s" : "ms", title: titles[metric]!,
            context: .object(["provider_id": raw["provider_id"], "model_id": facts["model_name"], "reference_group": .string(group), "source": .string("PROVIDER_REPORTED"), "endpoint": raw["endpoint"], "observed_at": raw["observed_at"], "window_start_seconds": facts["window_start"], "window_end_seconds": facts["window_end"], "series_schema": facts["series_schema"], "scope": .string("PROVIDER_MODEL_REFERENCE")]))
    }
}

public enum ConnectionLabels {
    public static func role(_ value: String) -> String { ["INFERENCE":"推論 Key", "ACCOUNT_PAT":"帳戶 PAT"][value] ?? "用途未提供" }
    public static func credential(_ key: JSONValue, accounts: [JSONValue]) -> String {
        let account = accounts.first { $0["provider_id"] == key["provider_id"] && $0["id"] == key["account_id"] }
        let name = account?["label"].string
        return [name.flatMap { $0.isEmpty ? nil : $0 } ?? "未命名帳戶", key["label"].string.isEmpty ? "未命名 Key" : key["label"].string,
                key["group"].string.isEmpty ? "未指定群組" : key["group"].string].joined(separator: " · ")
    }
    public static func action(state: String?) -> String {
        switch state { case "ACTIVE": return "查看這個連線"; case "HIDDEN": return "重新顯示這個連線"; case "ARCHIVED": return "取消封存這個連線"; default: return "加入我的模型" }
    }
    public static func state(_ value: String) -> String { ["ACTIVE":"已加入", "HIDDEN":"已隱藏", "ARCHIVED":"已封存"][value] ?? "狀態未提供" }
}
