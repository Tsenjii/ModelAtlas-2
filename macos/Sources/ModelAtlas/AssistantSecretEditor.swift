import AtlasCore
import SwiftUI

struct AssistantSecretEditor: View {
    @ObservedObject var store: AtlasStore
    let saved: (JSONValue) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var url = "", label = "", role = "INFERENCE", secret = ""
    @State private var fields: [SecretFieldDraft] = []
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack { Text("安全輸入 Key").font(.title2.weight(.semibold)); Spacer(); Button("關閉") { dismiss() }.keyboardShortcut(.cancelAction) }
            Text("只把安全參照交給 Assistant。秘密值由引擎加密保存。").foregroundStyle(.secondary)
            ScrollView { VStack(alignment: .leading, spacing: 14) {
                FormField(title: "API Base URL", text: $url); FormField(title: "參照名稱", text: $label)
                Picker("用途", selection: $role) { Text("推論 Key").tag("INFERENCE"); Text("帳戶 PAT").tag("ACCOUNT_PAT") }
                FormField(title: "Key／PAT", text: $secret, secure: true)
                if role == "INFERENCE" { SecretFieldsEditor(rows: $fields) }
                ActionButton(title: "儲存並加入安全參照") {
                    guard let api = store.api else { return }; let submitted = [url, label, role, secret], extra = fields
                    let ref = try await api.call("/assistant/secrets", method: "POST", body: .object(["base_url": .string(url), "label": .string(label), "role": .string(role), "secret": .string(secret), "auth_fields": .array(role == "INFERENCE" ? extra.map(\.json) : [])]))
                    saved(ref)
                    if submitted == [url, label, role, secret], fields == extra { secret = ""; fields = []; dismiss() }
                }
            } }
        }.padding(24).frame(width: 700, height: 600)
    }
}
