import AtlasCore
import SwiftUI

struct ModelInformationEditor: View {
    @ObservedObject var store: AtlasStore
    let scope: ModelScope
    let metadata: JSONValue
    var didSave: () async -> Void = {}
    @State private var value: JSONValue = .object([:])
    @State private var aliases = ""
    @State private var context = ""
    @State private var savedSnapshot: JSONValue?
    @State private var initialized = false
    var body: some View {
        AtlasCard {
            Text("分類與模型資訊").font(.headline)
            FormField(title: "Canonical ID", text: text("canonical_id"))
            FormField(title: "別名（逗號分隔）", text: $aliases)
            Picker("主要類別", selection: text("primary_category")) { ForEach(["CHAT_REASONING", "CODING", "IMAGE", "AUDIO", "EMBEDDING", "RERANK", "SAFETY", "OTHER"], id: \.self) { Text($0).tag($0) } }
            HStack { FormField(title: "Vendor", text: text("vendor")); FormField(title: "Context tokens（選填）", text: $context) }
            FormField(title: "描述", text: text("description"))
            DisclosureGroup("模態與端點") {
                ForEach(["TEXT_INPUT", "TEXT_OUTPUT", "IMAGE_INPUT", "IMAGE_OUTPUT", "AUDIO_INPUT", "AUDIO_OUTPUT", "FILE_INPUT"], id: \.self) { item in Toggle(item, isOn: member("modalities", item)).toggleStyle(.checkbox) }
                Divider()
                ForEach(endpointOptions, id: \.self) { item in Toggle(item, isOn: member("endpoint_types", item)).toggleStyle(.checkbox) }
            }
            ActionButton(title: "儲存模型資訊") {
                let submitted = snapshot
                var body = value; body["provider_id"] = .string(scope.providerID); body["model_id"] = .string(scope.modelID)
                body["aliases"] = .array(aliases.split(separator: ",").map { .string($0.trimmingCharacters(in: .whitespacesAndNewlines)) }.filter { $0 != .string("") })
                if !context.isEmpty { guard let count = Int(context), (1...16_777_216).contains(count) else { throw DraftProblem("Context tokens 需為 1–16777216 的整數。") }; body["context_tokens"] = .number(Double(count)) } else { body["context_tokens"] = .null }
                _ = try await store.mutate("/models/metadata", body: body); savedSnapshot = submitted; await didSave()
            }
            if let savedSnapshot { Label(savedSnapshot == snapshot ? "模型資訊已儲存" : "上一份已儲存；目前有未儲存變更", systemImage: savedSnapshot == snapshot ? "checkmark.circle" : "pencil").font(.caption) }
        }.task(id: metadata) { guard !initialized, !metadata.fields.isEmpty else { return }; initialized = true; value = metadata; aliases = metadata["aliases"].array.map(\.string).joined(separator: ", "); context = metadata["context_tokens"].numeric.map { String(Int($0)) } ?? "" }
    }
    private var snapshot: JSONValue { .object(["metadata": value, "aliases": .string(aliases), "context": .string(context)]) }
    private func text(_ key: String) -> Binding<String> { Binding(get: { value[key].string }, set: { value[key] = .string($0) }) }
    private func member(_ key: String, _ item: String) -> Binding<Bool> { Binding(get: { value[key].array.contains(.string(item)) }, set: { enabled in var list = value[key].array.filter { $0 != .string(item) }; if enabled { list.append(.string(item)) }; value[key] = .array(list) }) }
}

struct ModelFactsEditor: View {
    @ObservedObject var store: AtlasStore
    let scope: ModelScope
    var didSave: () async -> Void = {}
    @State private var input = ""
    @State private var output = ""
    @State private var cache = ""
    @State private var fixed = ""
    @State private var note = ""
    @State private var unit = "USD/1K tokens"
    @State private var scopeKind = "MODEL"
    @State private var slice = ""
    @State private var absent = false
    @State private var effective = false
    @State private var quota: [String: String] = [:]
    @State private var notice: String?
    var body: some View {
        AtlasCard {
            Text("手動價格與額度").font(.headline)
            Text("留空代表未知；填入 0 才是已知零。標價與這個 Key 的實際費率分開保存。").font(.callout).foregroundStyle(.secondary)
            Picker("計費單位", selection: $unit) { Text("USD／1K tokens").tag("USD/1K tokens"); Text("USD／request").tag("USD/request") }
            if unit == "USD/1K tokens" { HStack { FormField(title: "輸入價格", text: $input); FormField(title: "輸出價格", text: $output); FormField(title: "Cache 價格", text: $cache) } }
            FormField(title: "固定請求費", text: $fixed)
            Toggle("我已確認沒有固定請求費", isOn: $absent)
            Toggle("已確認為這個 Key／Group 的實際費率", isOn: $effective)
            FormField(title: "來源或說明", text: $note)
            ActionButton(title: "儲存手動價格") {
                var body = scope.json
                body["price"] = .object(["input": try decimal(unit == "USD/request" ? "" : input), "output": try decimal(unit == "USD/request" ? "" : output), "cache": try decimal(unit == "USD/request" ? "" : cache), "fixed": try decimal(fixed), "fixed_absent": .bool(absent), "unit": .string(unit)])
                body["confirmed_effective_price"] = .bool(effective); body["note"] = .string(note)
                _ = try await store.mutate("/models/facts", body: body); notice = "手動價格已儲存"; await didSave()
            }
            DisclosureGroup("手動額度資源池") {
                Picker("額度範圍", selection: $scopeKind) { ForEach(["PROVIDER", "ACCOUNT", "CREDENTIAL", "GROUP", "MODEL", "PROJECT_MODEL", "CUSTOM"], id: \.self) { Text($0).tag($0) } }
                Picker("使用方式", selection: $slice) { Text("未指定").tag(""); ForEach(["STANDARD_INTERACTIVE", "PRIORITY", "BATCH"], id: \.self) { Text($0).tag($0) } }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 160))]) {
                    ForEach(["rpm", "rpd", "tpm", "tpd", "remaining", "used", "granted"], id: \.self) { key in FormField(title: key.uppercased(), text: Binding(get: { quota[key] ?? "" }, set: { quota[key] = $0 })) }
                }
                ActionButton(title: "儲存手動額度") {
                    var fields: [String: JSONValue] = [:]
                    for (key, text) in quota { if text.isEmpty { fields[key] = .null } else { guard let n = Int64(text), n >= 0, n <= 9_007_199_254_740_991 else { throw DraftProblem("額度需為非負整數。") }; fields[key] = .number(Double(n)) } }
                    var body = scope.json; body["quota"] = .object(fields); body["scope_kind"] = .string(scopeKind); body["slice"] = .string(slice); body["note"] = .string(note)
                    _ = try await store.mutate("/models/facts", body: body); notice = "手動額度已儲存"; await didSave()
                }
            }
            if let notice { Label(notice, systemImage: "checkmark.circle").font(.caption) }
        }
    }
    private func decimal(_ text: String) throws -> JSONValue {
        if text.isEmpty { return .null }
        guard let value = Double(text), value.isFinite, value >= 0 else { throw DraftProblem("價格需為非負數；留空代表未知。") }
        return .string(text)
    }
}
