import AppKit
import AtlasCore
import SwiftUI
import UniformTypeIdentifiers

struct BatchImportView: View {
    @ObservedObject var store: AtlasStore
    let openReview: (JSONValue) -> Void
    @State private var rows = [BatchProviderDraft()]
    @State private var results: [JSONValue] = []
    @State private var problem: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("每批最多 50 個供應商。匯入會建立各自的待確認流程；選擇模型並確認後才會加入工作區。").font(.callout).foregroundStyle(.secondary)
            ForEach($rows) { $row in BatchProviderRow(row: $row) { rows.removeAll { $0.id == row.id } } }
            HStack {
                Button { rows.append(BatchProviderDraft()) } label: { Label("加入供應商", systemImage: "plus") }.disabled(rows.count >= 50)
                Button("從 JSON 檔匯入…") { importFile() }
                Spacer()
                ActionButton(title: "建立待確認流程") {
                    guard let api = store.api else { return }; let submitted = rows
                    guard (1...50).contains(submitted.count) else { throw DraftProblem("每批需要 1–50 個供應商。") }
                    let items = try submitted.map { try $0.request() }
                    results = try await api.call("/onboarding/import", method: "POST", body: .object(["items": .array(items)])).requiredArray()
                    for result in results where result["review"] != .null {
                        if let index = result["index"].numeric.map(Int.init), submitted.indices.contains(index), let current = rows.firstIndex(where: { $0 == submitted[index] }) { rows[current].key = "" }
                    }
                }.disabled(rows.isEmpty)
            }
            if let problem { Text(problem).foregroundStyle(.red) }
            ForEach(results, id: \.self) { result in
                AtlasCard {
                    HStack {
                        let review = result["review"]
                        Text(review["provider"]["name"].string.isEmpty ? "第 " + String(Int(result["index"].numeric ?? 0) + 1) + " 筆" : review["provider"]["name"].string).font(.headline)
                        Spacer()
                        if review != .null { Button("檢查並選擇模型") { openReview(review) } }
                        else { SmallBadge(text: result["status"].string == "INVALID" ? "資料無效，請修改後重試" : "保存失敗，可重試", color: .orange) }
                    }
                }
            }
        }
    }
    private func importFile() {
        let panel = NSOpenPanel(); panel.allowedContentTypes = [.json]; panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        Task {
            do {
                guard let api = store.api, let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= 4 << 20 else { throw DraftProblem("匯入檔案上限為 4 MiB。") }
                let value = try JSONDecoder().decode(JSONValue.self, from: Data(contentsOf: url))
                let items = value.array.isEmpty ? value["items"].array : value.array
                guard (1...50).contains(items.count) else { throw DraftProblem("JSON 檔需包含 1–50 筆 onboarding 定義。") }
                results = try await api.call("/onboarding/import", method: "POST", body: .object(["items": .array(items)])).requiredArray(); problem = nil
            } catch { problem = error.localizedDescription }
        }
    }
}

private struct BatchProviderRow: View {
    @Binding var row: BatchProviderDraft
    let remove: () -> Void
    var body: some View {
        AtlasCard {
            HStack { FormField(title: "供應商名稱", text: $row.name); Button(action: remove) { Image(systemName: "minus.circle") }.help("移除此列") }
            FormField(title: "API Base URL", text: $row.url)
            HStack { FormField(title: "帳戶", text: $row.account); FormField(title: "Group", text: $row.group) }
            Picker("認證方式", selection: $row.auth) { Text("Bearer").tag("BEARER"); Text("API Key").tag("API_KEY"); Text("不需 Key").tag("NONE") }
            if row.auth != "NONE" { FormField(title: "API Key", text: $row.key, secure: true) }
            FormField(title: "模型 ID（逗號分隔）", text: $row.models)
            DisclosureGroup("進階設定") { Picker("Adapter", selection: $row.adapter) { ForEach(adapterOptions, id: \.self) { Text($0).tag($0) } }; Picker("模型用途", selection: $row.endpoint) { ForEach(endpointOptions, id: \.self) { Text($0).tag($0) } } }
        }
    }
}
