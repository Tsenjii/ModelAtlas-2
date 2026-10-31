import AtlasCore
import SwiftUI

func credentialIdentity(_ key: JSONValue) -> String { JSONValue.array([key["provider_id"], key["account_id"], key["id"]]).pretty }
@MainActor func availableEndpoints(store: AtlasStore, key: JSONValue?) -> [JSONValue] {
    guard let key, let provider = store.providers.first(where: { $0["id"] == key["provider_id"] }) else { return [] }
    let base = key["base_url"].string.isEmpty ? provider["base_url"].string : key["base_url"].string
    func origin(_ text: String) -> String? { guard let u = URL(string: text), let host = u.host, u.scheme == "https" else { return nil }; return "https://" + host.lowercased() + ":" + String(u.port ?? 443) }
    var endpoints = store.endpoints.filter { $0["provider_id"] == key["provider_id"] && origin($0["url"].string) != nil && origin($0["url"].string) == origin(base) }
    if base == provider["base_url"].string, !store.endpoints.contains(where: { $0["provider_id"] == key["provider_id"] && $0["id"].string == "CHAT_COMPLETIONS" }) { endpoints.insert(.object(["id": .string("CHAT_COMPLETIONS"), "operation": .string("CHAT_COMPLETIONS"), "url": .string(base.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/chat/completions")]), at: 0) }
    return endpoints
}

struct CatalogDetailView: View {
    @ObservedObject var store: AtlasStore
    let model: JSONValue
    @Environment(\.dismiss) private var dismiss
    @State private var keyID = ""
    @State private var endpoint = ""
    @State private var member: JSONValue?
    init(store: AtlasStore, model: JSONValue, initialKey: JSONValue? = nil, initialEndpoint: String = "") {
        self.store = store; self.model = model
        if let initialKey { _keyID = State(initialValue: credentialIdentity(initialKey)) }
        _endpoint = State(initialValue: initialEndpoint)
    }
    private var keys: [JSONValue] { store.credentials.filter { $0["provider_id"] == model["provider_id"] && $0["enabled"].boolean && $0["role"].string == "INFERENCE" } }
    private var key: JSONValue? { keys.first { credentialIdentity($0) == keyID } }
    private var selection: JSONValue? {
        guard let key, availableEndpoints(store: store, key: key).contains(where: { $0["id"].string == endpoint }) else { return nil }
        return .object(["provider_id": key["provider_id"], "account_id": key["account_id"], "credential_id": key["id"], "model_id": model["id"], "group": key["group"], "endpoint": .string(endpoint)])
    }
    private var members: [JSONValue] { store.myModels.filter { $0["provider_id"] == model["provider_id"] && $0["model_id"] == model["id"] } }
    private var existing: JSONValue? { guard let selection else { return nil }; return members.first { ModelScope($0) == ModelScope(selection) } }
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            heading
            ScrollView { VStack(alignment: .leading, spacing: 18) { overview; joinForm; joinedConnections } }
        }.padding(24).frame(width: 790, height: 690)
        .onChange(of: keyID) { _ in endpoint = availableEndpoints(store: store, key: key).first?["id"].string ?? "" }
        .sheet(item: $member) { ModelDetailView(store: store, model: $0).atlasModal(store) }
    }
    private var heading: some View {
        HStack {
            VStack(alignment: .leading, spacing: 5) {
                Text(model["display_name"].string.isEmpty ? model["id"].string : model["display_name"].string).font(.title2.weight(.semibold))
                Text(store.providerName(model["provider_id"].string)).foregroundStyle(.secondary)
            }
            Spacer(); Button("關閉") { dismiss() }.keyboardShortcut(.cancelAction)
        }
    }
    private var overview: some View {
        AtlasCard {
            HStack { Text(model["id"].string).textSelection(.enabled); Spacer(); Button("複製 ID") { copyText(model["id"].string) } }
            Text(model["metadata"]["description"].string.isEmpty ? "站點尚未提供這個模型的文字說明。" : model["metadata"]["description"].string).font(.callout).foregroundStyle(.secondary)
            DisclosureGroup("分類與來源") { JSONTree(value: model["metadata"]) }
        }
    }
    private var joinForm: some View {
        AtlasCard {
            Text(existing == nil ? "將模型加入工作區" : "管理這個模型的連線").font(.headline)
            Text("同一模型可使用不同帳戶、Key 或端點。選擇後會顯示這條連線的加入狀態。").font(.callout).foregroundStyle(.secondary)
            keyPicker
            Picker("用途與端點", selection: $endpoint) {
                Text("選擇端點").tag("")
                ForEach(availableEndpoints(store: store, key: key), id: \.self) { entry in Text(operationTitle(entry["operation"].string) + " · " + entry["url"].string).tag(entry["id"].string) }
            }
            ActionButton(title: ConnectionLabels.action(state: existing?["state"].string)) {
                guard let selection else { throw DraftProblem("請選擇這個供應商的 Key 與同來源端點。") }
                if let existing, existing["state"].string == "ACTIVE" { member = existing }
                else { _ = try await store.mutate("/my-models", body: selection) }
            }.disabled(selection == nil)
            if selection != nil { AtlasLoadedMarker(name: "catalog-connection-state-" + (existing?["state"].string ?? "NEW")).frame(height: 0) }
            if keys.isEmpty { Text("先在供應商詳情新增或啟用推論 Key。").foregroundStyle(.secondary) }
        }
    }
    private var keyPicker: some View {
        Picker("帳戶與 Key", selection: $keyID) {
            Text("選擇連線").tag("")
            ForEach(keys, id: \.self) { entry in Text(keyLabel(entry)).tag(credentialIdentity(entry)) }
        }
    }
    private func keyLabel(_ entry: JSONValue) -> String { ConnectionLabels.credential(entry, accounts: store.accounts) }
    @ViewBuilder private var joinedConnections: some View {
        if !members.isEmpty {
            AtlasCard {
                Text("已加入的連線").font(.headline)
                ForEach(members, id: \.self) { row in
                    Button { member = row } label: { HStack { Text(store.scopeTitle(row)); Spacer(); SmallBadge(text: ConnectionLabels.state(row["state"].string)); Text(operationTitle(store.operationName(row))).font(.caption); Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary) } }.buttonStyle(AtlasRowButtonStyle())
                }
            }
        }
    }
}
