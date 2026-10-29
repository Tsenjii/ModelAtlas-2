import Foundation

public enum ResearchPolicyKind: String, CaseIterable, Sendable {
    case execution, inference, resource
    public var path: String {
        switch self {
        case .execution: return "/research/execution-policy"
        case .inference: return "/research/inference-budget"
        case .resource: return "/research/resource-budget"
        }
    }
}

public struct ResearchCredentialID: Hashable, Sendable, Identifiable {
    public let provider, account, credential: String
    public init(_ value: JSONValue) throws {
        provider = try ResearchPolicyValue.text(value, "provider_id", nonempty: true)
        account = try ResearchPolicyValue.text(value, "account_id", nonempty: true)
        credential = try ResearchPolicyValue.text(value, "credential_id", nonempty: true)
    }
    public var json: JSONValue { .object(["provider_id": .string(provider), "account_id": .string(account), "credential_id": .string(credential)]) }
    public var id: String { json.pretty }
}

public enum ResearchPolicyValue {
    public static let maximumInteger = 9_007_199_254_740_991
    public static func integer(_ value: JSONValue, minimum: Int = 0, maximum: Int = maximumInteger) throws -> Int {
        guard let number = value.numeric, number.isFinite, number.rounded() == number,
              number >= Double(minimum), number <= Double(maximum) else { throw AtlasError.invalidResponse }
        return Int(number)
    }
    public static func present(_ value: JSONValue, _ key: String) throws -> JSONValue {
        guard case .object(let fields) = value, let field = fields[key] else { throw AtlasError.invalidResponse }
        return field
    }
    public static func text(_ value: JSONValue, _ key: String, nonempty: Bool = false) throws -> String {
        guard case .string(let text) = try present(value, key), !nonempty || !text.isEmpty else { throw AtlasError.invalidResponse }
        return text
    }
    public static func flag(_ value: JSONValue, _ key: String) throws -> Bool {
        guard case .bool(let flag) = try present(value, key) else { throw AtlasError.invalidResponse }
        return flag
    }
    public static func count(_ value: JSONValue, _ key: String, minimum: Int = 0, maximum: Int = maximumInteger) throws -> Int {
        try integer(present(value, key), minimum: minimum, maximum: maximum)
    }
    public static func validDay(_ text: String) -> Bool {
        guard text.range(of: #"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"#, options: .regularExpression) != nil else { return false }
        let parts = text.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3, parts[0] > 0 else { return false }
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let components = DateComponents(year: parts[0], month: parts[1], day: parts[2])
        guard let date = calendar.date(from: components) else { return false }
        let actual = calendar.dateComponents([.year, .month, .day], from: date)
        return actual.year == components.year && actual.month == components.month && actual.day == components.day
    }
    private static func decimalParts(_ text: String, maximumLength: Int) -> (digits: String, power: Int, negative: Bool)? {
        guard text.utf8.count <= maximumLength,
              text.range(of: #"^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$"#, options: .regularExpression) != nil else { return nil }
        let negative = text.hasPrefix("-")
        let unsigned = negative ? String(text.dropFirst()) : text
        let parts = unsigned.lowercased().split(separator: "e", omittingEmptySubsequences: false)
        guard parts.count <= 2, let exponent = parts.count == 2 ? Int(parts[1]) : 0, (-4096...4096).contains(exponent) else { return nil }
        let mantissa = parts[0].split(separator: ".", omittingEmptySubsequences: false)
        let digits = String(mantissa.joined().drop(while: { $0 == "0" }))
        let fraction = mantissa.count == 2 ? mantissa[1].count : 0
        return (digits, exponent - fraction, negative)
    }
    /// Compare decimal strings without binary or Decimal rounding; preserve the
    /// original string when sending it back to the exact-money API.
    public static func validUSD(_ text: String, positive: Bool = false) -> Bool {
        guard let number = decimalParts(text, maximumLength: 64) else { return false }
        if number.digits.isEmpty { return !positive }
        guard !number.negative else { return false }
        let order = number.digits.count + number.power
        if order < 7 { return true }
        if order > 7 { return false }
        return number.digits.first == "1" && number.digits.dropFirst().allSatisfy { $0 == "0" }
    }
    public static func limit(_ value: JSONValue, _ key: String, usd: Bool) throws {
        let field = try present(value, key)
        if field == .null { return }
        if usd {
            guard case .string(let money) = field, validUSD(money) else { throw AtlasError.invalidResponse }
        } else { _ = try integer(field, maximum: 1_000_000_000_000) }
    }
    public static func resourcePolicy(_ value: JSONValue) throws {
        for key in ["daily_tokens", "credential_tokens"] { try limit(value, key, usd: false) }
        for key in ["daily_usd", "credential_usd"] { try limit(value, key, usd: true) }
        _ = try count(value, "reserve_tokens", minimum: 1, maximum: 1_000_000)
        guard validUSD(try text(value, "reserve_usd"), positive: true) else { throw AtlasError.invalidResponse }
    }
    public static func usage(_ value: JSONValue) throws {
        let requests = try count(value, "requests")
        for key in ["pending_requests", "unknown_token_requests", "unknown_usd_requests", "overrun_requests"] {
            _ = try count(value, key, maximum: requests)
        }
        for key in ["accounted_tokens", "known_reported_tokens"] {
            let number = try text(value, key, nonempty: true)
            guard number.utf8.count <= 8192, number.allSatisfy({ $0.isASCII && $0.isNumber }) else { throw AtlasError.invalidResponse }
        }
        for key in ["accounted_usd", "known_reported_usd"] {
            guard let number = decimalParts(try text(value, key), maximumLength: 8192), !number.negative || number.digits.isEmpty else { throw AtlasError.invalidResponse }
        }
        for (key, unknown, usd) in [("reported_tokens", "unknown_token_requests", false), ("reported_usd", "unknown_usd_requests", true)] {
            let field = try present(value, key)
            if try count(value, unknown) > 0 {
                guard field == .null else { throw AtlasError.invalidResponse }
            } else {
                guard case .string(let number) = field else { throw AtlasError.invalidResponse }
                if usd {
                    guard let parsed = decimalParts(number, maximumLength: 8192), !parsed.negative || parsed.digits.isEmpty else { throw AtlasError.invalidResponse }
                } else {
                    guard !number.isEmpty, number.utf8.count <= 8192, number.allSatisfy({ $0.isASCII && $0.isNumber }) else { throw AtlasError.invalidResponse }
                }
            }
        }
    }
    public static func receipt(_ value: JSONValue, request: JSONValue, kind: ResearchPolicyKind) throws -> Int {
        let prior = try count(request, "revision", maximum: maximumInteger - 1)
        let revision = try count(value, "revision", minimum: 1)
        guard revision == prior + 1 else { throw AtlasError.invalidResponse }
        if kind == .execution {
            guard try count(value, "max_parallel_runs", minimum: 1, maximum: 4) == count(request, "max_parallel_runs", minimum: 1, maximum: 4) else { throw AtlasError.invalidResponse }
            _ = try count(value, "updated_at", minimum: 1)
        }
        return revision
    }
}

public struct ResearchPolicySnapshot: Equatable, Sendable {
    public let kind: ResearchPolicyKind
    public let value: JSONValue
    public let revision: Int
    public let credentials: [JSONValue]
    public var nextCursor: String { value["next_cursor"].string }
    public var day: String { value["day"].string }
    public init(kind: ResearchPolicyKind, value: JSONValue) throws {
        self.kind = kind
        if kind == .execution {
            let policy = try ResearchPolicyValue.present(value, "policy")
            revision = try ResearchPolicyValue.count(policy, "revision")
            let parallel = try ResearchPolicyValue.count(policy, "max_parallel_runs", minimum: 1, maximum: 4)
            let updated = try ResearchPolicyValue.count(policy, "updated_at")
            guard revision == 0 ? updated == 0 && parallel == 1 : updated > 0 else { throw AtlasError.invalidResponse }
            _ = try ResearchPolicyValue.count(value, "running_runs")
            _ = try ResearchPolicyValue.count(value, "scheduled_rounds")
            credentials = []
        } else {
            revision = try ResearchPolicyValue.count(value, "revision", minimum: 1)
            _ = try ResearchPolicyValue.count(value, "started_at", minimum: 1)
            _ = try ResearchPolicyValue.count(value, "reset_at", minimum: 1)
            let day = try ResearchPolicyValue.text(value, "day")
            guard ResearchPolicyValue.validDay(day),
                  try ResearchPolicyValue.text(value, "lifecycle") == "THIS_ENGINE" else { throw AtlasError.invalidResponse }
            let cursor = try ResearchPolicyValue.text(value, "next_cursor")
            guard cursor.utf8.count <= 3072 else { throw AtlasError.invalidResponse }
            credentials = try ResearchPolicyValue.present(value, "credentials").requiredArray()
            guard credentials.count <= 100 else { throw AtlasError.invalidResponse }
            var scopes = Set<ResearchCredentialID>()
            for row in credentials {
                guard scopes.insert(try ResearchCredentialID(row)).inserted else { throw AtlasError.invalidResponse }
                for key in ["provider_name", "account_label", "label"] { _ = try ResearchPolicyValue.text(row, key) }
                _ = try ResearchPolicyValue.flag(row, "available"); _ = try ResearchPolicyValue.flag(row, "override")
                if kind == .inference {
                    _ = try ResearchPolicyValue.count(row, "used")
                    _ = try ResearchPolicyValue.count(row, "daily_limit", maximum: 2000)
                } else {
                    let limits = try ResearchPolicyValue.present(row, "limits")
                    try ResearchPolicyValue.limit(limits, "tokens", usd: false)
                    try ResearchPolicyValue.limit(limits, "usd", usd: true)
                    try ResearchPolicyValue.usage(ResearchPolicyValue.present(row, "usage"))
                }
            }
            if kind == .inference {
                _ = try ResearchPolicyValue.count(value, "daily_limit", maximum: 2000)
                _ = try ResearchPolicyValue.count(value, "default_credential_limit", maximum: 2000)
                _ = try ResearchPolicyValue.count(value, "today_used")
                for key in ["token_usage", "currency_cost", "provider_remaining"] {
                    guard try ResearchPolicyValue.present(value, key) == .null else { throw AtlasError.invalidResponse }
                }
            } else {
                guard try ResearchPolicyValue.text(value, "budget_basis") == "LOCAL_RESERVATIONS_AND_REPORTED_USAGE" else { throw AtlasError.invalidResponse }
                _ = try ResearchPolicyValue.count(value, "older_unbudgeted_requests")
                try ResearchPolicyValue.resourcePolicy(ResearchPolicyValue.present(value, "policy"))
                try ResearchPolicyValue.usage(ResearchPolicyValue.present(value, "usage"))
            }
        }
        self.value = value
    }
    private init(kind: ResearchPolicyKind, value: JSONValue, revision: Int, credentials: [JSONValue]) {
        self.kind = kind; self.value = value; self.revision = revision; self.credentials = credentials
    }
    public func row(_ id: ResearchCredentialID) -> JSONValue? { credentials.first { (try? ResearchCredentialID($0)) == id } }
    public func appending(_ page: ResearchPolicySnapshot) throws -> ResearchPolicySnapshot {
        guard kind != .execution, kind == page.kind, revision == page.revision, day == page.day,
              !nextCursor.isEmpty, page.nextCursor.isEmpty || nextCursor != page.nextCursor else { throw DraftProblem("日期、上限或分頁已變更，請重新讀取用量。") }
        let rows = credentials + page.credentials
        guard Set(try rows.map(ResearchCredentialID.init)).count == rows.count else { throw AtlasError.invalidResponse }
        var combined = page.value; combined["credentials"] = .array(rows)
        return ResearchPolicySnapshot(kind: kind, value: combined, revision: revision, credentials: rows)
    }
}

public struct ResearchPolicyDraft: Equatable, Sendable {
    public let kind: ResearchPolicyKind
    public let credential: ResearchCredentialID?
    public private(set) var revision: Int
    public private(set) var fields: [String: String] = [:]
    public private(set) var enabled: Set<String> = []
    public private(set) var dirty = false
    public var keys: [String] {
        switch kind {
        case .execution: return ["max_parallel_runs"]
        case .inference: return credential == nil ? ["daily_limit", "default_credential_limit"] : ["daily_limit"]
        case .resource: return credential == nil ? ["daily_tokens", "daily_usd", "credential_tokens", "credential_usd", "reserve_tokens", "reserve_usd"] : ["tokens", "usd"]
        }
    }
    public var nullable: Set<String> {
        guard kind == .resource else { return [] }
        return Set(credential == nil ? ["daily_tokens", "daily_usd", "credential_tokens", "credential_usd"] : ["tokens", "usd"])
    }
    public init(_ snapshot: ResearchPolicySnapshot, credential: ResearchCredentialID? = nil) throws {
        kind = snapshot.kind; revision = snapshot.revision; self.credential = credential
        let values: JSONValue
        if let credential {
            guard kind != .execution, let row = snapshot.row(credential) else { throw AtlasError.invalidResponse }
            values = kind == .resource ? row["limits"] : row
        } else { values = kind == .inference ? snapshot.value : snapshot.value["policy"] }
        for key in keys {
            let value = try ResearchPolicyValue.present(values, key)
            if value == .null { fields[key] = "" }
            else { enabled.insert(key); fields[key] = value.display }
        }
    }
    public mutating func edit(_ key: String, text: String) { guard keys.contains(key) else { return }; fields[key] = text; dirty = true }
    public mutating func setEnabled(_ key: String, _ value: Bool) {
        guard nullable.contains(key) else { return }
        if value { enabled.insert(key) } else { enabled.remove(key) }
        dirty = true
    }
    public mutating func acknowledged(_ revision: Int) { self.revision = revision; dirty = false }
    public func refreshed(_ snapshot: ResearchPolicySnapshot) throws -> ResearchPolicyDraft {
        guard snapshot.kind == kind else { throw AtlasError.invalidResponse }
        if dirty { return self }
        return try ResearchPolicyDraft(snapshot, credential: credential)
    }
    public func request(reset: Bool = false) throws -> JSONValue {
        guard revision >= (kind == .execution ? 0 : 1), revision < ResearchPolicyValue.maximumInteger else { throw AtlasError.invalidResponse }
        var result: JSONValue = .object(["revision": .number(Double(revision))])
        if kind != .execution {
            result["action"] = .string(reset ? "RESET" : credential == nil ? "POLICY" : "CREDENTIAL")
            if let credential { for (key, value) in credential.json.fields { result[key] = value } }
        }
        if reset { guard credential != nil, kind != .execution else { throw AtlasError.invalidResponse }; return result }
        for key in keys {
            if nullable.contains(key) && !enabled.contains(key) { result[key] = .null; continue }
            let text = fields[key] ?? ""
            if key.contains("usd") {
                guard ResearchPolicyValue.validUSD(text, positive: key == "reserve_usd") else { throw DraftProblem("USD 請填入 0–1,000,000 的精確小數；每次預留須大於 0。") }
                result[key] = .string(text)
            } else {
                let range: ClosedRange<Int> = kind == .execution ? 1...4 : kind == .inference ? 0...2000 : key == "reserve_tokens" ? 1...1_000_000 : 0...1_000_000_000_000
                guard !text.isEmpty, text.allSatisfy({ $0.isASCII && $0.isNumber }), let number = Int(text), range.contains(number) else { throw DraftProblem("請填入欄位允許範圍內的整數；空值不會當作 0。") }
                result[key] = .number(Double(number))
            }
        }
        return result
    }
}

public enum ResearchPolicyHistory {
    public static func page(kind: ResearchPolicyKind, value: JSONValue, before: Int? = nil) throws -> (rows: [JSONValue], next: Int) {
        guard kind != .execution else { throw AtlasError.invalidResponse }
        let rows = try ResearchPolicyValue.present(value, "history").requiredArray()
        guard rows.count <= 50 else { throw AtlasError.invalidResponse }
        var previous = before ?? ResearchPolicyValue.maximumInteger
        for row in rows {
            let revision = try ResearchPolicyValue.count(row, "revision", minimum: 1)
            guard revision < previous else { throw AtlasError.invalidResponse }; previous = revision
            _ = try ResearchPolicyValue.count(row, "changed_at", minimum: 1)
            let action = try ResearchPolicyValue.text(row, "action")
            guard ["POLICY", "CREDENTIAL", "RESET"].contains(action) else { throw AtlasError.invalidResponse }
            if action == "POLICY" {
                for key in ["provider_id", "account_id", "credential_id"] { guard try ResearchPolicyValue.text(row, key).isEmpty else { throw AtlasError.invalidResponse } }
            } else { _ = try ResearchCredentialID(row) }
            if kind == .inference {
                for key in ["daily_limit", "default_credential_limit"] {
                    let field = try ResearchPolicyValue.present(row, key)
                    if action == "RESET" || action == "CREDENTIAL" && key == "default_credential_limit" { guard field == .null else { throw AtlasError.invalidResponse } }
                    else { _ = try ResearchPolicyValue.integer(field, maximum: 2000) }
                }
            } else {
                let field = try ResearchPolicyValue.present(row, "value")
                if action == "RESET" { guard field == .object(["use_default": .bool(true)]) else { throw AtlasError.invalidResponse } }
                else if action == "POLICY" { try ResearchPolicyValue.resourcePolicy(field) }
                else { try ResearchPolicyValue.limit(field, "tokens", usd: false); try ResearchPolicyValue.limit(field, "usd", usd: true) }
            }
        }
        let next = try ResearchPolicyValue.count(value, "next_before")
        guard next == 0 || rows.count == 50 && next == previous else { throw AtlasError.invalidResponse }
        return (rows, next)
    }
}
