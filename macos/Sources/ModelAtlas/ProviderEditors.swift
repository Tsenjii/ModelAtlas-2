import AtlasCore
import SwiftUI

struct SecretFieldDraft: Identifiable, Equatable {
    let id = UUID()
    var name = "", secret = "", query = false
    var json: JSONValue { .object(["name": .string(name), "secret": .string(secret), "query": .bool(query)]) }
}

struct SecretFieldsEditor: View {
    @Binding var rows: [SecretFieldDraft]
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach($rows) { $row in
                VStack(alignment: .leading, spacing: 8) {
                    HStack { TextField("欄位名稱", text: $row.name).textFieldStyle(.roundedBorder); Toggle("Query 參數", isOn: $row.query).toggleStyle(.checkbox); Button { rows.removeAll { $0.id == row.id } } label: { Image(systemName: "minus.circle") }.help("移除欄位") }
                    SecureField("秘密值", text: $row.secret).textFieldStyle(.roundedBorder)
                }
            }
            Button { rows.append(SecretFieldDraft()) } label: { Label("加入秘密 Header／Query", systemImage: "plus") }.disabled(rows.count >= 8)
        }
    }
}

struct ProviderIdentityEditor: View {
    @ObservedObject var store: AtlasStore
    let provider: JSONValue
    @State private var name = ""
    @State private var url = ""
    @State private var description = ""
    @State private var review: ActionReviewController?
    var body: some View {
        AtlasCard {
            Text("供應商識別與來源").font(.headline)
            FormField(title: "名稱", text: $name); FormField(title: "主要 API Base URL", text: $url); FormField(title: "短介紹", text: $description)
            Text("每個 Key 保留建立時綁定的來源。切換至其他來源前，請先加入明確綁定新網址的 Key 及端點。").font(.callout).foregroundStyle(.secondary)
            ActionButton(title: "預覽供應商變更") {
                guard let api = store.api else { return }
                let value = try await api.call("/assistant/actions/preview", method: "POST", body: .object(["kind": .string("PROVIDER"), "provider_id": provider["id"], "patch": .object(["name": .string(name), "base_url": .string(url), "description": .string(description)])]))
                review = ActionReviewController(api: api, value: value)
            }
        }.task { name = provider["name"].string; url = provider["base_url"].string; description = provider["description"].string }
        .sheet(item: $review) { ActionReviewView(store: store, model: $0).atlasModal(store) }
    }
}

struct CredentialMetadataEditor: View {
    @ObservedObject var store: AtlasStore
    let credential: JSONValue
    @Environment(\.dismiss) private var dismiss
    @State private var label = ""
    @State private var note = ""
    @State private var priority = "0"
    @State private var enabled = false
    @State private var review: ActionReviewController?
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack { Text("Key 顯示與優先序").font(.title2.weight(.semibold)); Spacer(); Button("關閉") { dismiss() }.keyboardShortcut(.cancelAction) }
            Text(credential["provider_id"].string + " · " + credential["account_id"].string + " · " + credential["id"].string).font(.caption).textSelection(.enabled)
            Text("綁定來源：" + credential["base_url"].string).font(.callout).textSelection(.enabled)
            FormField(title: "顯示名稱", text: $label); FormField(title: "備註", text: $note); FormField(title: "優先序（0–1000）", text: $priority); Toggle("啟用", isOn: $enabled)
            ActionButton(title: "預覽變更") {
                guard let api = store.api, let n = Int(priority), (0...1000).contains(n) else { throw DraftProblem("優先序需為 0–1000 的整數。") }
                let value = try await api.call("/assistant/actions/preview", method: "POST", body: .object(["kind": .string("CREDENTIAL"), "provider_id": credential["provider_id"], "account_id": credential["account_id"], "credential_id": credential["id"], "patch": .object(["label": .string(label), "note": .string(note), "priority": .number(Double(n)), "enabled": .bool(enabled)])]))
                review = ActionReviewController(api: api, value: value)
            }
            Spacer()
        }.padding(24).frame(width: 650, height: 490).task { label = credential["label"].string; note = credential["note"].string; priority = credential["priority"].numeric.map { String(Int($0)) } ?? "0"; enabled = credential["enabled"].boolean }
        .sheet(item: $review) { ActionReviewView(store: store, model: $0).atlasModal(store) }
    }
}

struct OnboardingScopeSummary: View {
    let review: JSONValue
    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            Label(review["account"]["label"].string, systemImage: "person.crop.circle").font(.callout)
            ForEach(review["credentials"].array, id: \.self) { credential in
                Text([credential["label"].string, credential["role"].string, credential["group"].string.isEmpty ? "預設群組" : credential["group"].string].joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                if !credential["base_url"].string.isEmpty { Text("綁定來源：" + credential["base_url"].string).font(.caption).foregroundStyle(.secondary) }
            }
            ForEach(review["endpoint_profiles"].array, id: \.self) { endpoint in Text(operationTitle(endpoint["operation"].string) + " · " + endpoint["url"].string).font(.caption).foregroundStyle(.secondary).textSelection(.enabled) }
        }
    }
}
