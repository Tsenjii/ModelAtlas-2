import Foundation

public struct SecretFieldDraft: Identifiable, Equatable, Sendable {
    public let id = UUID()
    public var name = "", secret = "", query = false
    public init() {}
    public var json: JSONValue { .object(["name": .string(name), "secret": .string(secret), "query": .bool(query)]) }
}

public struct CredentialIdentity: Hashable, Sendable, Identifiable {
    public let providerID, accountID, credentialID: String
    public init(_ value: JSONValue) throws {
        providerID = value["provider_id"].string; accountID = value["account_id"].string; credentialID = value["id"].string
        guard !providerID.isEmpty, !accountID.isEmpty, !credentialID.isEmpty else { throw DraftProblem("憑證範圍不完整，請重新整理帳戶清單。") }
    }
    public var json: JSONValue { .object(["provider_id": .string(providerID), "account_id": .string(accountID), "credential_id": .string(credentialID)]) }
    public var id: String { json.pretty }
}

/// A single form and every writer for the same account share a lease. A late
/// completion cannot release a newer operation's lease.
public struct ProviderMutationGate: Sendable {
    public struct Ticket: Sendable { fileprivate let id: UUID; fileprivate let slots: Set<String> }
    private var owners: [String: UUID] = [:]
    public init() {}
    public func busy(form: String) -> Bool { owners["form:" + form] != nil }
    public func busy(accountID: String) -> Bool { owners["account:" + accountID] != nil }
    public mutating func begin(form: String, accountID: String? = nil) -> Ticket? {
        var slots: Set<String> = ["form:" + form]
        if let accountID { slots.insert("account:" + accountID) }
        guard slots.allSatisfy({ owners[$0] == nil }) else { return nil }
        let ticket = Ticket(id: UUID(), slots: slots)
        for slot in slots { owners[slot] = ticket.id }
        return ticket
    }
    public mutating func finish(_ ticket: Ticket) {
        for slot in ticket.slots where owners[slot] == ticket.id { owners.removeValue(forKey: slot) }
    }
}

public struct ProviderAccountDraft: Equatable, Sendable {
    public private(set) var id = UUID().uuidString.lowercased()
    public var label = ""
    public init() {}
    public func request(providerID: String) throws -> JSONValue {
        let name = label.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !providerID.isEmpty, !name.isEmpty else { throw DraftProblem("請先填入帳戶名稱。") }
        guard name.utf8.count <= 120 else { throw DraftProblem("帳戶名稱過長，請使用較短的名稱。") }
        return .object(["provider_id": .string(providerID), "id": .string(id), "label": .string(name)])
    }
    public mutating func committed(_ submitted: Self) {
        guard id == submitted.id else { return }
        if self == submitted { label = "" }
        id = UUID().uuidString.lowercased()
    }
}

public struct ProviderKeyDraft: Equatable, Sendable {
    public private(set) var id = UUID().uuidString.lowercased()
    public var accountID = "", label = "", secret = "", group = "", role = "INFERENCE"
    public var origin = "", auth = "", note = "", priority = "0"
    public var authFields: [SecretFieldDraft] = []
    public init() {}
    public func request(providerID: String, accounts: [JSONValue], inheritedOrigin: String, inheritedAuth: String) throws -> JSONValue {
        guard accounts.contains(where: { $0["provider_id"].string == providerID && $0["id"].string == accountID }), !providerID.isEmpty else { throw DraftProblem("請選擇這個供應商的帳戶。") }
        guard ["INFERENCE", "ACCOUNT_PAT"].contains(role), ["", "BEARER", "API_KEY", "CUSTOM_HEADER", "NONE"].contains(auth) else { throw DraftProblem("請選擇有效的用途與認證方式。") }
        let effectiveAuth = auth.isEmpty ? inheritedAuth : auth
        let fieldsOnly = role == "INFERENCE" && effectiveAuth == "CUSTOM_HEADER" && !authFields.isEmpty
        guard Self.validSecret(secret), (!secret.isEmpty || fieldsOnly || (role == "INFERENCE" && effectiveAuth == "NONE")) else { throw DraftProblem("請貼上有效的秘密值，且不要包含換行。") }
        if role == "INFERENCE" && effectiveAuth == "NONE" && (!secret.isEmpty || !authFields.isEmpty) { throw DraftProblem("匿名連線不需要秘密值或額外認證欄位。") }
        if role == "ACCOUNT_PAT" && !authFields.isEmpty { throw DraftProblem("帳戶 PAT 不使用額外的模型推論認證欄位。") }
        let source = origin.isEmpty ? inheritedOrigin : origin
        guard let url = URL(string: source), url.scheme == "https", url.host != nil, url.user == nil, url.password == nil, url.query == nil, url.fragment == nil else { throw DraftProblem("綁定來源需為有效的 HTTPS Base URL。") }
        guard let rank = Int(priority), (0...1000).contains(rank) else { throw DraftProblem("優先序需為 0–1000 的整數。") }
        return .object(["id": .string(id), "provider_id": .string(providerID), "account_id": .string(accountID), "label": .string(label), "secret": .string(secret), "group": .string(group), "role": .string(role), "base_url": .string(source), "auth_mode": .string(auth), "note": .string(note), "priority": .number(Double(rank)), "auth_fields": .array(authFields.map(\.json))])
    }
    public mutating func committed(_ submitted: Self) {
        guard id == submitted.id else { return }
        if self == submitted { label = ""; secret = ""; authFields = [] }
        // A draft typed during a successful save is a new Key, never an update
        // of the just-created credential. Failed attempts keep their identity.
        id = UUID().uuidString.lowercased()
    }
    public static func validSecret(_ value: String) -> Bool { value.utf8.count <= 8192 && !value.unicodeScalars.contains(where: { $0.value == 13 || $0.value == 10 || $0.value == 0 }) }
}

public struct CredentialUpdateDraft: Equatable, Sendable {
    public var mode = "secret", secret = "", version = ""
    public init() {}
    public func request(identity: CredentialIdentity) throws -> JSONValue {
        var body = identity.json; body["confirm"] = .bool(true)
        if mode == "version" {
            guard let number = Int(version), number > 0 else { throw DraftProblem("請填入要恢復的正整數版本。") }
            body["version"] = .number(Double(number))
        } else {
            guard mode == "secret", !secret.isEmpty, ProviderKeyDraft.validSecret(secret) else { throw DraftProblem("請貼上新的秘密值，且不要包含換行。") }
            body["secret"] = .string(secret)
        }
        return body
    }
}

public enum ProviderManagementRequest {
    public static func toggle(providerID: String, accountID: String, credentials: [JSONValue], enabled: Bool) throws -> JSONValue? {
        guard !providerID.isEmpty, !accountID.isEmpty else { throw DraftProblem("請先選擇完整帳戶。") }
        let ids = Set(credentials.filter { $0["provider_id"].string == providerID && $0["account_id"].string == accountID && $0["enabled"] == .bool(!enabled) }.map { $0["id"].string }.filter { !$0.isEmpty }).sorted()
        guard !ids.isEmpty else { return nil }
        guard ids.count <= 100 else { throw DraftProblem("一次最多調整 100 把 Key，請分別操作清單中的 Key。") }
        return .object(["provider_id": .string(providerID), "account_id": .string(accountID), "ids": .array(ids.map(JSONValue.string)), "enabled": .bool(enabled)])
    }
}
