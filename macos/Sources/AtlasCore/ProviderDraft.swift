import Foundation

public struct BatchProviderDraft: Identifiable, Equatable, Sendable {
    public let id = UUID()
    public var name = "", url = "", key = "", account = "主要帳戶", group = "", models = "", auth = "BEARER", adapter = "GENERIC", endpoint = "CHAT_COMPLETIONS"
    public init() {}
    public func request() throws -> JSONValue {
        guard let base = URL(string: url), base.scheme == "https", base.host != nil, base.user == nil, base.password == nil, !name.isEmpty else { throw DraftProblem("每個供應商都需要名稱與有效的 HTTPS Base URL。") }
        guard auth == "NONE" || !key.isEmpty else { throw DraftProblem("請填入 API Key，或明確選擇不需 Key。") }
        return .object(["mode": .string("ADVANCED"), "provider": .object(["name": .string(name), "base_url": .string(url), "auth_mode": .string(auth), "config": .object(["adapter": .string(adapter), "auth_mode": .string(auth)])]), "account": .object(["label": .string(account)]), "group": .string(group), "endpoint": .string(endpoint), "model_ids": .array(ConnectorDraft.ids(models)), "credentials": auth == "NONE" ? .array([]) : .array([.object(["label": .string("主要 Key"), "secret": .string(key), "group": .string(group), "role": .string("INFERENCE")])])])
    }
}
