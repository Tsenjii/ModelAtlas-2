import AtlasCore
import SwiftUI

@MainActor struct ProviderAccountsView: View {
    @ObservedObject var store: AtlasStore
    @ObservedObject var writes: ProviderWriteController
    private var accounts: [JSONValue] { store.accounts.filter { $0["provider_id"].string == writes.providerID } }
    private var keys: [JSONValue] { store.credentials.filter { $0["provider_id"].string == writes.providerID } }
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("帳戶與 Key").font(.title3.weight(.semibold))
                    Text("\(accounts.count) 個帳戶 · \(keys.count) 把 Key").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button("新增帳戶") { writes.accountOpen = true }
                Button { if writes.key.accountID.isEmpty { writes.key.accountID = accounts.first?.id ?? "" }; writes.keyOpen = true } label: { Label("新增 Key", systemImage: "plus") }
                    .buttonStyle(AtlasActionStyle(prominent: true)).disabled(accounts.isEmpty)
            }
            ProviderWriteStatus(writes: writes, store: store, form: "new-account")
            if !writes.account.label.isEmpty, let status = writes.feedback["new-account"], status.phase == .saved || status.phase == .refreshNeeded { Text("上一份已儲存，目前這份帳戶草稿尚未儲存。").font(.caption).foregroundStyle(.secondary) }
            ProviderWriteStatus(writes: writes, store: store, form: "new-key")
            if accounts.isEmpty { Text("先建立帳戶，再加入對應的推論 Key 或帳戶 PAT。").foregroundStyle(.secondary) }
            ForEach(accounts) { account in
                ProviderAccountCard(store: store, writes: writes, account: account)
            }
            AtlasLoadedMarker(name: "provider-accounts-ready").frame(height: 0)
        }
        .sheet(isPresented: $writes.accountOpen) { ProviderAccountEditorView(store: store, writes: writes).atlasModal(store) }
        .sheet(isPresented: $writes.keyOpen) { ProviderKeyEditorView(store: store, writes: writes).atlasModal(store) }
    }
}

@MainActor private struct ProviderAccountCard: View {
    @ObservedObject var store: AtlasStore
    @ObservedObject var writes: ProviderWriteController
    let account: JSONValue
    private var keys: [JSONValue] { store.credentials.filter { $0["provider_id"].string == writes.providerID && $0["account_id"].string == account.id } }
    private var form: String { "account-" + account.id }
    var body: some View {
        AtlasCard {
            HStack {
                Label(account["label"].string.isEmpty ? "未命名帳戶" : account["label"].string, systemImage: "person.crop.circle").font(.headline)
                Spacer()
                Text("\(keys.filter { $0["enabled"].boolean }.count)／\(keys.count) 啟用").font(.caption).foregroundStyle(.secondary)
                Menu {
                    Button("啟用這個帳戶全部 Key") { Task { await writes.toggle(store: store, accountID: account.id, credentials: keys, enabled: true, form: form) } }.disabled(!keys.contains { $0["enabled"] == .bool(false) })
                    Button("停用這個帳戶全部 Key") { Task { await writes.toggle(store: store, accountID: account.id, credentials: keys, enabled: false, form: form) } }.disabled(!keys.contains { $0["enabled"] == .bool(true) })
                } label: { Image(systemName: "ellipsis.circle") }.menuStyle(.borderlessButton).fixedSize().help("這個帳戶的 Key 操作").disabled(writes.busy(accountID: account.id))
            }
            ProviderWriteStatus(writes: writes, store: store, form: form)
            if keys.isEmpty { Text("這個帳戶還沒有 Key。").font(.callout).foregroundStyle(.secondary) }
            ForEach(keys) { credential in
                if credential.id != keys.first?.id { Divider() }
                CredentialRow(store: store, writes: writes, credential: credential)
            }
            Text("啟用代表允許使用；是否能呼叫模型，仍依這把 Key 的權限與實際請求結果。").font(.caption).foregroundStyle(.secondary)
        }
    }
}

@MainActor struct ProviderAccountEditorView: View {
    @ObservedObject var store: AtlasStore
    @ObservedObject var writes: ProviderWriteController
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack { Text("新增帳戶").font(.title2.weight(.semibold)); Spacer(); Button("關閉") { dismiss() }.keyboardShortcut(.cancelAction) }.padding(24)
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text(store.providerName(writes.providerID)).foregroundStyle(.secondary)
                    FormField(title: "帳戶名稱", text: $writes.account.label)
                    ProviderWriteStatus(writes: writes, store: store, form: "new-account")
                    if !writes.account.label.isEmpty, let status = writes.feedback["new-account"], status.phase == .saved || status.phase == .refreshNeeded { Text("上一份已儲存，目前這份帳戶草稿尚未儲存。").font(.caption).foregroundStyle(.secondary) }
                    AtlasLoadedMarker(name: "provider-account-editor-ready").frame(height: 0)
                }.padding(24).frame(maxWidth: .infinity, alignment: .leading)
            }
            Divider()
            HStack {
                Button("清空草稿") { writes.clearAccount() }.disabled(writes.busy(form: "new-account"))
                Spacer()
                Button("加入帳戶") { Task { await writes.addAccount(store: store) } }.buttonStyle(AtlasActionStyle(prominent: true)).disabled(writes.busy(form: "new-account") || writes.account.label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }.padding(20)
        }.frame(width: 570, height: 400)
    }
}

@MainActor struct ProviderKeyEditorView: View {
    @ObservedObject var store: AtlasStore
    @ObservedObject var writes: ProviderWriteController
    @Environment(\.dismiss) private var dismiss
    private var accounts: [JSONValue] { store.accounts.filter { $0["provider_id"].string == writes.providerID } }
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 4) { Text("新增 Key").font(.title2.weight(.semibold)); Text(store.providerName(writes.providerID)).foregroundStyle(.secondary) }
                Spacer(); Button("關閉") { dismiss() }.keyboardShortcut(.cancelAction)
            }.padding(24)
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Picker("所屬帳戶", selection: $writes.key.accountID) { Text("選擇帳戶").tag(""); ForEach(accounts) { Text($0["label"].display).tag($0.id) } }
                    Picker("用途", selection: $writes.key.role) { Text("模型推論").tag("INFERENCE"); Text("帳戶 PAT").tag("ACCOUNT_PAT") }.pickerStyle(.segmented)
                    Text(writes.key.role == "ACCOUNT_PAT" ? "帳戶 PAT 用來讀取站點的私人帳戶資料，不會用來發送模型請求。" : "推論 Key 用來呼叫模型；公開公告與效能不需要這把 Key。").font(.callout).foregroundStyle(.secondary)
                    FormField(title: "Key 名稱（選填）", text: $writes.key.label)
                    FormField(title: "秘密值", text: $writes.key.secret, secure: true)
                    FormField(title: "群組（選填）", text: $writes.key.group)
                    DisclosureGroup("來源與進階設定") {
                        VStack(alignment: .leading, spacing: 14) {
                            FormField(title: "綁定 Base URL（留空沿用供應商）", text: $writes.key.origin)
                            Picker("認證方式", selection: $writes.key.auth) { Text("沿用供應商設定").tag(""); ForEach(["BEARER", "API_KEY", "CUSTOM_HEADER", "NONE"], id: \.self) { Text($0).tag($0) } }
                            FormField(title: "備註", text: $writes.key.note)
                            FormField(title: "優先序（0–1000）", text: $writes.key.priority)
                            SecretFieldsEditor(rows: $writes.key.authFields)
                        }.padding(.top, 12)
                    }
                    AtlasLoadedMarker(name: "provider-key-editor-ready").frame(height: 0)
                }.padding(24)
            }
            Divider()
            VStack(alignment: .leading, spacing: 10) {
                ProviderWriteStatus(writes: writes, store: store, form: "new-key")
                if !writes.key.label.isEmpty || !writes.key.secret.isEmpty || !writes.key.authFields.isEmpty, let status = writes.feedback["new-key"], status.phase == .saved || status.phase == .refreshNeeded { Text("上一份已儲存，目前這份 Key 草稿尚未儲存。").font(.caption).foregroundStyle(.secondary) }
                HStack { Text("儲存只加入憑證，不會自動發出模型測試。").font(.caption).foregroundStyle(.secondary); Spacer(); Button("儲存 Key") { Task { await writes.saveKey(store: store) } }.buttonStyle(AtlasActionStyle(prominent: true)).disabled(writes.busy(form: "new-key") || writes.busy(accountID: writes.key.accountID) || writes.key.accountID.isEmpty) }
                Button("清空草稿") { writes.clearKey() }.font(.caption).disabled(writes.busy(form: "new-key"))
            }.padding(20)
        }.frame(width: 650, height: 700)
    }
}

@MainActor struct CredentialRow: View {
    @ObservedObject var store: AtlasStore
    @ObservedObject var writes: ProviderWriteController
    let credential: JSONValue
    @State private var editing = false
    private var identity: CredentialIdentity? { try? CredentialIdentity(credential) }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: credential["role"].string == "ACCOUNT_PAT" ? "person.crop.circle" : "key").foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 4) {
                    Text(credential["label"].string.isEmpty ? "未命名 Key" : credential["label"].string).font(.callout.weight(.semibold))
                    Text(ConnectionLabels.role(credential["role"].string) + " · " + (credential["group"].string.isEmpty ? "未指定群組" : credential["group"].string)).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                SmallBadge(text: credential["enabled"].boolean ? "已啟用" : "已停用")
                if let identity {
                    if let version = writes.versions[identity.id] { SmallBadge(text: "版本 \(version)") }
                    Button(credential["enabled"].boolean ? "停用" : "啟用") { Task { await writes.toggle(store: store, accountID: identity.accountID, credentials: [credential], enabled: !credential["enabled"].boolean, form: identity.id) } }
                        .disabled(writes.busy(accountID: identity.accountID))
                    Menu {
                        Button("編輯名稱與備註…") { editing = true }
                        Button("更新或恢復秘密值…") { if writes.expandedRotations.contains(identity.id) { writes.expandedRotations.remove(identity.id) } else { writes.expandedRotations.insert(identity.id) } }
                    } label: { Image(systemName: "ellipsis") }.menuStyle(.borderlessButton).fixedSize().help("這把 Key 的更多操作").disabled(writes.busy(accountID: identity.accountID))
                }
            }
            if let identity {
                ProviderWriteStatus(writes: writes, store: store, form: identity.id)
                if writes.expandedRotations.contains(identity.id) { CredentialUpdateView(store: store, writes: writes, credential: credential, identity: identity) }
            }
        }.padding(.vertical, 5)
        .sheet(isPresented: $editing) { CredentialMetadataEditor(store: store, credential: credential).atlasModal(store) }
    }
}

@MainActor private struct CredentialUpdateView: View {
    @ObservedObject var store: AtlasStore
    @ObservedObject var writes: ProviderWriteController
    let credential: JSONValue
    let identity: CredentialIdentity
    private var draft: Binding<CredentialUpdateDraft> { writes.rotation(identity) }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(ConnectionLabels.credential(credential, accounts: store.accounts)).font(.callout.weight(.medium))
            Picker("更新方式", selection: draft.mode) { Text("更新秘密值").tag("secret"); Text("恢復舊版本").tag("version") }.pickerStyle(.segmented)
            if draft.wrappedValue.mode == "version" {
                FormField(title: "要恢復的版本", text: draft.version)
                Text("只恢復這個帳戶、這把 Key 的版本；會保留既有模型與歷史。").font(.caption).foregroundStyle(.secondary)
            } else { FormField(title: "新的秘密值", text: draft.secret, secure: true) }
            HStack {
                Button("收起") { writes.expandedRotations.remove(identity.id) }
                Spacer()
                Button(draft.wrappedValue.mode == "version" ? "確認恢復這把 Key" : "確認更新這把 Key") { Task { await writes.update(identity, store: store) } }
                    .buttonStyle(AtlasActionStyle(prominent: true)).disabled(writes.busy(accountID: identity.accountID))
            }
        }.padding(16).background(atlasInset).cornerRadius(10)
    }
}
