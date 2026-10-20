import Foundation

public struct ModelFilters: Equatable, Sendable {
    public var provider = "", credential = "", group = "", category = "", capability = "", modality = "", vendor = "", price = "", favorite = "", change = ""
    public var usable = false
    public var source = "local", sort = "name"
    public var numbers: [String: String] = [:]
    public init() {}
    public static func credentialID(_ model: JSONValue) -> String { JSONValue.array([model["provider_id"], model["account_id"], model["credential_id"]]).pretty }
    public static func known(_ value: JSONValue) -> Double? {
        let number = value.numeric ?? Double(value.string)
        return number.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil }
    }
    public func metric(_ model: JSONValue, _ name: String) -> Double? {
        if source == "provider" {
            let fact = model["offer"]["provider_performance"]["facts"][name == "ttft" ? "ttft_ms" : name == "latency" ? "latency_ms" : name]
            return Self.known(fact["mean"]) ?? Self.known(fact)
        }
        if source == "external" {
            let fact = model["offer"]["external_performance"]
            if name == "tps", fact["units"].string == "TPS" { return Self.known(fact["average_tokens_per_second"]) }
            if name == "ttft", fact["units"].string == "MS" { return Self.known(fact["average_first_token_latency"]) }
            return nil
        }
        return Self.known(model["performance"][name]["mean"])
    }
    public func unitPrice(_ model: JSONValue, _ leg: String) -> Double? {
        let legs = model["offer"]["price"]["legs"]
        guard legs["unit"].string == "USD/1K tokens" else { return nil }
        return Self.known(legs[leg]).map { $0 * 1000 }
    }
    public func quota(_ m: JSONValue) -> Double? {
        guard let r = Self.known(m["offer"]["quota"]["rpd"]), let t = Self.known(m["offer"]["quota"]["tpd"]) else { return nil }
        return min(r / (r + 100), t / (t + 1_000_000))
    }
    public func matches(_ m: JSONValue, now: Double = Date().timeIntervalSince1970 * 1000) -> Bool {
        let meta = m["metadata"]
        guard (provider.isEmpty || provider == m["provider_id"].string), (credential.isEmpty || credential == Self.credentialID(m)),
              (group.isEmpty || (group == "__DEFAULT__" ? "" : group) == m["group"].string),
              (category.isEmpty || category == meta["primary_category"].string), (capability.isEmpty || m["capabilities"].array.contains(.string(capability))),
              (modality.isEmpty || meta["modalities"].array.contains(.string(modality))), (vendor.isEmpty || meta["vendor"].string.localizedCaseInsensitiveContains(vendor)),
              (price.isEmpty || price == m["price_state"].string), (favorite.isEmpty || m[favorite].boolean), (!usable || m["enabled"].boolean) else { return false }
        if !change.isEmpty {
            if change == "recently_used" { guard let last = Self.known(m["performance"]["last_request_at"]), last > now - 7 * 86_400_000 else { return false } }
            else if !m["offer"][change].boolean { return false }
        }
        let values: [String: Double?] = ["context": Self.known(meta["context_tokens"]), "tps": metric(m, "tps"), "ttft": metric(m, "ttft"), "latency": metric(m, "latency"), "success1h": Self.known(m["reliability"]["1h"]["success_rate"]).map { $0 * 100 }, "success24h": Self.known(m["reliability"]["24h"]["success_rate"]).map { $0 * 100 }, "samples": Self.known(m["performance"]["samples"]), "input": unitPrice(m, "input"), "output": unitPrice(m, "output"), "cache": unitPrice(m, "cache")]
        for (key, text) in numbers where !text.isEmpty {
            guard let threshold = Double(text), threshold.isFinite, threshold >= 0, let wrapped = values[key], let value = wrapped else { return false }
            if ["ttft", "latency", "input", "output", "cache"].contains(key) ? value > threshold : value < threshold { return false }
        }
        return true
    }
    public func ranking(_ m: JSONValue, weights: JSONValue) -> (score: Double?, coverage: Double) {
        let strength = Self.known(m["offer"]["strength"]["score"]).map { min(1, $0 / 100) }
        let speed = metric(m, "tps").map { $0 / ($0 + 100) }
        let values = ["strength": strength, "speed": speed, "quota": quota(m)]
        var score = 0.0, used = 0.0, enabled = 0.0
        for key in ["strength", "speed", "quota"] {
            let weight = Self.known(weights[key]) ?? 1
            enabled += weight
            if let wrapped = values[key], let value = wrapped { score += weight * value; used += weight }
        }
        guard m["enabled"].boolean, used > 0 else { return (nil, 0) }
        let reliability = Self.known(m["performance"]["success_rate"]).map { pow($0, Self.known(weights["reliability"]) ?? 1) } ?? 1
        return (score / used * reliability, enabled > 0 ? used / enabled : 0)
    }
    public func ordered(_ models: [JSONValue], weights: JSONValue) -> [JSONValue] {
        func value(_ m: JSONValue) -> Double? {
            switch sort {
            case "tps", "ttft", "latency": return metric(m, sort)
            case "strength": return Self.known(m["offer"]["strength"]["score"])
            case "price": return unitPrice(m, "input")
            case "quota": return quota(m)
            case "recent": return Self.known(m["performance"]["last_request_at"])
            case "new": return Self.known(m["offer"]["created_at"])
            default: return nil
            }
        }
        return models.sorted { a, b in
            if sort == "smart" {
                let x = ranking(a, weights: weights), y = ranking(b, weights: weights)
                if x.score != nil, y.score != nil, x.coverage != y.coverage { return x.coverage > y.coverage }
                if x.score != y.score { return (x.score ?? -1) > (y.score ?? -1) }
            } else if sort != "name" {
                let x = value(a), y = value(b)
                if x != y { guard let x else { return false }; guard let y else { return true }; return ["ttft", "latency", "price"].contains(sort) ? x < y : x > y }
            }
            let x = a["model_id"].string, y = b["model_id"].string
            return x == y ? ModelScope(a).id < ModelScope(b).id : x.localizedStandardCompare(y) == .orderedAscending
        }
    }
}
