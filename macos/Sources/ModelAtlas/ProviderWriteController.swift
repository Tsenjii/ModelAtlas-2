import AtlasCore
import SwiftUI

struct ProviderWriteFeedback {
    enum Phase: Equatable { case pending, saved, refreshNeeded, failed }
    let phase: Phase
    let message: String
}

@MainActor final class ProviderWriteController: ObservableObject {
    let providerID: String
    @Published var account = ProviderAccountDraft()
    @Published var key = ProviderKeyDraft()
    @Published var accountOpen = false
    @Published var keyOpen = false
    @Published var rotations: [String: CredentialUpdateDraft] = [:]
    @Published var expandedRotations = Set<String>()
    @Published private(set) var versions: [String: Int] = [:]
    @Published private(set) var feedback: [String: ProviderWriteFeedback] = [:]
    @Published private var gate = ProviderMutationGate()
    init(providerID: String) { self.providerID = providerID }
    func busy(form: String) -> Bool { gate.busy(form: form) }
    func busy(accountID: String) -> Bool { gate.busy(accountID: accountID) }

    @discardableResult func perform(form: String, accountID: String? = nil, success: String,
        write: () async throws -> JSONValue, committed: (JSONValue) -> Void = { _ in }, refresh: () async throws -> Void) async -> Bool {
        guard let ticket = gate.begin(form: form, accountID: accountID) else { return false }
        feedback[form] = ProviderWriteFeedback(phase: .pending, message: "正在儲存，其他帳戶仍可操作。")
        defer { gate.finish(ticket) }
        do {
            let result = try await write()
            committed(result)
            do {
                try await refresh()
                feedback[form] = ProviderWriteFeedback(phase: .saved, message: success)
            } catch {
                feedback[form] = ProviderWriteFeedback(phase: .refreshNeeded, message: success + " 清單暫時無法更新；重新整理即可，不必再次儲存。")
            }
            return true
        } catch {
            feedback[form] = ProviderWriteFeedback(phase: .failed, message: Self.message(error))
            return false
        }
    }
    func refresh(form: String, store: AtlasStore) async {
        guard let before = feedback[form], let ticket = gate.begin(form: form) else { return }
        defer { gate.finish(ticket) }
        do {
            try await store.reload()
            feedback[form] = ProviderWriteFeedback(phase: .saved, message: "清單已更新，先前的變更已保存。")
        } catch {
            feedback[form] = ProviderWriteFeedback(phase: .refreshNeeded, message: before.message)
        }
    }
    func validation(_ error: Error, form: String) { feedback[form] = ProviderWriteFeedback(phase: .failed, message: Self.message(error)) }
    private static func message(_ error: Error) -> String {
        if let value = error as? DraftProblem { return value.localizedDescription }
        if let value = error as? AtlasError { return value.localizedDescription + " 這份草稿仍保留。" }
        return "未能確認這次儲存結果，草稿仍保留。請先重新整理清單確認，再決定是否重試。"
    }
    func rotation(_ identity: CredentialIdentity) -> Binding<CredentialUpdateDraft> {
        Binding(get: { self.rotations[identity.id] ?? CredentialUpdateDraft() }, set: { self.rotations[identity.id] = $0 })
    }
    func clearAccount() { guard !busy(form: "new-account") else { return }; account = ProviderAccountDraft(); feedback.removeValue(forKey: "new-account") }
    func clearKey() { guard !busy(form: "new-key") else { return }; let accountID = key.accountID; key = ProviderKeyDraft(); key.accountID = accountID; feedback.removeValue(forKey: "new-key") }
    func addAccount(store: AtlasStore) async {
        let form = "new-account", submitted = account
        guard !busy(form: form) else { return }
        do {
            guard let api = store.api, !store.closing else { throw AtlasError.engineUnavailable }
            let body = try submitted.request(providerID: providerID)
            await perform(form: form, success: "帳戶已加入。可以接著加入這個帳戶的 Key。", write: { try await api.call("/accounts", method: "POST", body: body) }, committed: { _ in self.account.committed(submitted) }, refresh: { try await store.reload() })
        } catch { validation(error, form: form) }
    }
    func saveKey(store: AtlasStore) async {
        let form = "new-key", submitted = key
        guard !busy(form: form), !busy(accountID: submitted.accountID) else { return }
        do {
            guard let api = store.api, !store.closing, let provider = store.providers.first(where: { $0.id == providerID }) else { throw AtlasError.engineUnavailable }
            let body = try submitted.request(providerID: providerID, accounts: store.accounts, inheritedOrigin: provider["base_url"].string, inheritedAuth: provider["auth_mode"].string)
            await perform(form: form, accountID: submitted.accountID, success: "Key 已加入這個帳戶。尚未執行模型測試。", write: { try await api.call("/credentials", method: "POST", body: body) }, committed: { _ in self.key.committed(submitted) }, refresh: { try await store.reload() })
        } catch { validation(error, form: form) }
    }
    func toggle(store: AtlasStore, accountID: String, credentials: [JSONValue], enabled: Bool, form: String) async {
        guard !busy(form: form), !busy(accountID: accountID) else { return }
        do {
            guard let api = store.api, !store.closing, store.accounts.contains(where: { $0["provider_id"].string == providerID && $0.id == accountID }) else { throw AtlasError.engineUnavailable }
            guard let body = try ProviderManagementRequest.toggle(providerID: providerID, accountID: accountID, credentials: credentials, enabled: enabled) else { return }
            await perform(form: form, accountID: accountID, success: enabled ? "已啟用選定 Key。" : "已停用選定 Key，模型與歷史仍保留。", write: { try await api.call("/credentials/toggle", method: "POST", body: body) }, refresh: { try await store.reload() })
        } catch { validation(error, form: form) }
    }
    func update(_ identity: CredentialIdentity, store: AtlasStore) async {
        let form = identity.id, submitted = rotations[identity.id] ?? CredentialUpdateDraft()
        guard !busy(form: form), !busy(accountID: identity.accountID) else { return }
        do {
            guard identity.providerID == providerID, let api = store.api, !store.closing,
                  store.credentials.contains(where: { (try? CredentialIdentity($0)) == identity }) else { throw DraftProblem("這個帳戶的 Key 已變更，請先重新整理。") }
            let body = try submitted.request(identity: identity)
            await perform(form: form, accountID: identity.accountID, success: submitted.mode == "version" ? "已恢復指定版本，模型與歷史仍保留。" : "Key 已更新，模型與歷史仍保留。", write: { try await api.call("/credentials/rotate", method: "POST", body: body) }, committed: { result in
                if let n = result["version"].numeric, n > 0, n.rounded() == n, n < Double(Int.max) { self.versions[identity.id] = Int(n) }
                if (self.rotations[identity.id] ?? CredentialUpdateDraft()) == submitted { self.rotations.removeValue(forKey: identity.id); self.expandedRotations.remove(identity.id) }
            }, refresh: { try await store.reload() })
        } catch { validation(error, form: form) }
    }
}

@MainActor struct ProviderWriteStatus: View {
    @ObservedObject var writes: ProviderWriteController
    @ObservedObject var store: AtlasStore
    let form: String
    var body: some View {
        if let value = writes.feedback[form] {
            HStack(alignment: .top, spacing: 8) {
                if value.phase == .pending { ProgressView().controlSize(.small) }
                else { Image(systemName: value.phase == .failed ? "exclamationmark.circle" : value.phase == .refreshNeeded ? "arrow.clockwise.circle" : "checkmark.circle") }
                Text(value.message).font(.callout).textSelection(.enabled)
                Spacer(minLength: 0)
                if value.phase == .refreshNeeded { Button("重新整理清單") { Task { await writes.refresh(form: form, store: store) } } }
            }.foregroundStyle(value.phase == .failed ? Color.red : Color.secondary)
                .accessibilityElement(children: .contain).accessibilityLabel("儲存狀態")
                .padding(.vertical, 3)
        }
    }
}
