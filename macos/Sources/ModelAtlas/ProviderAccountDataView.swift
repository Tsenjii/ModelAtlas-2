import AtlasCore
import SwiftUI

@MainActor struct ProviderAccountDataView: View {
    @ObservedObject var store: AtlasStore
    let providerID: String
    let manageCredentials: () -> Void
    @State private var accountID = ""
    @State private var credentialID = ""
    @State private var kind = "token"
    @State private var result: JSONValue?
    private var accounts: [JSONValue] { store.accounts.filter { $0["provider_id"].string == providerID } }
    private var role: String { kind == "token" ? "INFERENCE" : "ACCOUNT_PAT" }
    private var credentials: [JSONValue] { store.credentials.filter { $0["provider_id"].string == providerID && $0["account_id"].string == accountID && $0["role"].string == role && $0["enabled"].boolean } }
    var body: some View {
        AtlasCard {
            HStack { Label("帳戶與 Key 用量", systemImage: "person.crop.circle").font(.headline); Spacer(); Button("管理憑證", action: manageCredentials) }
            Text("這裡查詢私人用量；上方的公開資料不需要提供憑證。").font(.caption).foregroundStyle(.secondary)
            Picker("查詢內容", selection: $kind) { Text("Key 額度 · 推論 Key").tag("token"); Text("帳戶用量 · 帳戶 PAT").tag("account") }.pickerStyle(.segmented)
            Picker("帳戶", selection: $accountID) { Text("選擇帳戶").tag(""); ForEach(accounts) { account in Text(account["label"].display).tag(account.id) } }
            if credentials.isEmpty {
                Text(kind == "token" ? "這個帳戶尚未加入啟用中的推論 Key。" : "帳戶用量需要站點後台產生的帳戶 PAT，不能使用推論 Key。請在「帳戶與 Key」新增。").font(.callout).foregroundStyle(.secondary)
            } else {
                Picker(kind == "token" ? "推論 Key" : "帳戶 PAT", selection: $credentialID) { Text("選擇憑證").tag(""); ForEach(credentials) { key in Text(key["label"].display).tag(key.id) } }
            }
            ActionButton(title: kind == "token" ? "更新這把 Key 的額度" : "更新這個帳戶的用量") {
                guard let api = store.api, credentials.contains(where: { $0.id == credentialID }) else { throw DraftProblem("請先選擇此帳戶啟用中的正確憑證。") }
                let expectedAccount = accountID, expectedCredential = credentialID, expectedKind = kind, expectedRole = role
                let raw = try await api.call("/newapi/" + expectedKind, method: "POST", body: .object(["provider_id": .string(providerID), "account_id": .string(expectedAccount), "credential_id": .string(expectedCredential)]))
                guard raw["provider_id"].string == providerID, raw["account_id"].string == expectedAccount, raw["credential_id"].string == expectedCredential, raw["credential_role"].string == expectedRole else { throw AtlasError.invalidResponse }
                if accountID == expectedAccount && credentialID == expectedCredential && kind == expectedKind { result = raw }
            }.id(accountID + "|" + credentialID + "|" + kind).disabled(!credentials.contains(where: { $0.id == credentialID }) || store.api == nil)
            if let result {
                HStack(alignment: .top) {
                    InsightValue(title: "剩餘額度", value: result["facts"]["unlimited_quota"].boolean ? "不限額" : (result["facts"]["total_available"] == .null ? result["facts"]["quota"].display : result["facts"]["total_available"].display))
                    InsightValue(title: "已使用", value: (result["facts"]["total_used"] == .null ? result["facts"]["used_quota"].display : result["facts"]["total_used"].display))
                }
                Text("顯示站方原始額度單位；不推測成美元或免費資格。").font(.caption).foregroundStyle(.secondary)
                DisclosureGroup("查詢來源與完整資料") { JSONTree(value: result) }.font(.caption)
            }
        }.task { if accountID.isEmpty { accountID = accounts.first?.id ?? "" }; chooseCredential() }
        .onChange(of: accountID) { _ in result = nil; chooseCredential() }
        .onChange(of: kind) { _ in result = nil; chooseCredential() }
        .onChange(of: credentialID) { _ in result = nil }
    }
    private func chooseCredential() { credentialID = credentials.count == 1 ? credentials[0].id : "" }
}
