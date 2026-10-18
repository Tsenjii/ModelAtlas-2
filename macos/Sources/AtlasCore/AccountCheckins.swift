import Foundation

public struct CheckinAccount: Identifiable, Equatable, Sendable {
    public let providerID, accountID, label, url: String
    public let required: Bool
    public let completedAt: Double?
    public var id: String { "\(providerID.utf8.count):\(providerID)\(accountID.utf8.count):\(accountID)" }
    public var pending: Bool { required && completedAt == nil }
    public init(_ value: JSONValue) throws {
        guard case .string(let provider) = value["provider_id"], !provider.isEmpty,
              case .string(let account) = value["account_id"], !account.isEmpty,
              case .string(let label) = value["label"], case .string(let url) = value["checkin_url"],
              case .bool(let required) = value["required"], case .string(let source) = value["source"] else { throw AtlasError.invalidResponse }
        let at: Double?
        if value["completed_at"] == .null {
            guard source.isEmpty else { throw AtlasError.invalidResponse }; at = nil
        } else {
            guard let number = value["completed_at"].numeric, number.isFinite, number > 0,
                  source == "MANUAL_COMPLETION" else { throw AtlasError.invalidResponse }
            at = number
        }
        providerID = provider; accountID = account; self.label = label; self.url = url; self.required = required; completedAt = at
    }
    private init(_ other: CheckinAccount, completedAt: Double) {
        providerID = other.providerID; accountID = other.accountID; label = other.label; url = other.url; required = other.required; self.completedAt = completedAt
    }
    public func completed(at: Double) -> CheckinAccount { CheckinAccount(self, completedAt: at) }
    public func request(date: String, timezone: String) -> JSONValue {
        .object(["provider_id": .string(providerID), "account_id": .string(accountID), "date": .string(date), "timezone": .string(timezone)])
    }
}

public struct AccountCheckinSnapshot: Sendable {
    public let date, timezone: String
    public private(set) var accounts: [CheckinAccount]
    public init(_ value: JSONValue, date: String, timezone: String) throws {
        guard value["date"].string == date, value["timezone"].string == timezone,
              TimeZone(identifier: timezone) != nil, case .array(let raw) = value["accounts"] else { throw AtlasError.invalidResponse }
        let rows = try raw.map(CheckinAccount.init)
        guard Set(rows.map(\.id)).count == rows.count else { throw AtlasError.invalidResponse }
        self.date = date; self.timezone = timezone; accounts = rows
    }
    public static func localDate(_ at: Date, timezone: String) -> String? {
        guard let zone = TimeZone(identifier: timezone) else { return nil }
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = zone; formatter.dateFormat = "yyyy-MM-dd"; return formatter.string(from: at)
    }
    /// Apply only a receipt for this exact account and civil date. Concurrent
    /// completions preserve every other account; rejected receipts change none.
    public mutating func apply(_ value: JSONValue, account: CheckinAccount) throws {
        guard value["provider_id"].string == account.providerID, value["account_id"].string == account.accountID,
              value["date"].string == date, value["timezone"].string == timezone,
              value["source"].string == "MANUAL_COMPLETION", let at = value["completed_at"].numeric, at.isFinite, at > 0,
              let index = accounts.firstIndex(where: { $0.id == account.id }), accounts[index].required else { throw AtlasError.invalidResponse }
        accounts[index] = accounts[index].completed(at: at)
    }
}
