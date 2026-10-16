import AtlasCore
import SwiftUI

struct ModelsView: View {
    @ObservedObject var store: AtlasStore
    @State private var tab = "My Models"
    @State private var search = ""
    @State private var category = "ALL"
    @State private var compareSelection: Set<ModelScope> = []
    @State private var comparison: JSONValue?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                PageHeading(eyebrow: "Model Library", title: "只留下，你想用的模型。", subtitle: "Catalog、你的連線與歷史各自保存，價格和可用性各有依據。")
                Picker("模型庫", selection: $tab) { Text("My Models").tag("My Models"); Text("探索 Catalog").tag("Catalog"); Text("隱藏與封存").tag("Hidden") }.pickerStyle(.segmented).frame(maxWidth: 500)
                if tab == "Catalog" { CatalogView(store: store, providerID: nil) }
                else {
                    HStack {
                        TextField("搜尋模型、名稱、別名或供應商", text: $search).textFieldStyle(.roundedBorder)
                        Picker("類別", selection: $category) { Text("所有類別").tag("ALL"); ForEach(["CHAT_REASONING", "CODING", "IMAGE", "AUDIO", "EMBEDDING", "RERANK", "OTHER"], id: \.self) { Text($0).tag($0) } }.frame(width: 220)
                    }
                    if filtered.isEmpty { EmptyCard(icon: "square.stack.3d.up", title: tab == "Hidden" ? "沒有隱藏或封存模型" : "建立你的模型庫", detail: "從 Catalog 主動加入模型，或手動輸入模型 ID。", action: "探索 Catalog") { tab = "Catalog" }.frame(height: 330) }
                    ForEach(filtered, id: \.self) { model in
                        HStack(spacing: 14) {
                            if tab != "Hidden" { Toggle("選擇比較", isOn: Binding(get: { compareSelection.contains(ModelScope(model)) }, set: { if $0 { compareSelection.insert(ModelScope(model)) } else { compareSelection.remove(ModelScope(model)) } })).labelsHidden().toggleStyle(.checkbox) }
                            Button { store.detailModel = model } label: {
                                AtlasCard {
                                    HStack(spacing: 14) {
                                        Text(String((model["metadata"]["vendor"].string.isEmpty ? model["model_id"].string : model["metadata"]["vendor"].string).prefix(1)).uppercased()).font(.title2.weight(.semibold)).frame(width: 46, height: 46).foregroundStyle(atlasAccent).background(atlasAccent.opacity(0.1)).cornerRadius(13)
                                        VStack(alignment: .leading, spacing: 5) { Text(model["model_id"].string).font(.headline).lineLimit(2); Text(store.scopeTitle(model)).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                                        Spacer()
                                        if model["favorite"].boolean { Image(systemName: "star.fill").foregroundStyle(.orange) }
                                        SmallBadge(text: model["price_state"].string == "FREE_NOW" ? "FREE NOW" : model["price_state"].string == "UNKNOWN" ? "價格待確認" : model["price_state"].string, color: model["price_state"].string == "FREE_NOW" ? atlasAccent : .secondary)
                                        Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                                    }
                                    HStack(spacing: 22) {
                                        metric("我的上線率", value: model["performance"]["success_rate"].numeric.map { String(format: "%.1f%%", $0 * 100) } ?? "—")
                                        metric("TTFT", value: model["performance"]["ttft"]["p50"].numeric.map { String(format: "%.0f ms", $0) } ?? "—")
                                        metric("TPS", value: model["performance"]["tps"]["p50"].numeric.map { String(format: "%.1f", $0) } ?? "—")
                                        metric("本機樣本", value: model["performance"]["samples"].display)
                                    }
                                }
                            }.buttonStyle(.plain)
                        }
                    }
                    if !compareSelection.isEmpty {
                        HStack {
                            Text("已選 \(compareSelection.count) 個連線").foregroundStyle(.secondary)
                            Spacer()
                            Button("清除") { compareSelection = [] }
                            ActionButton(title: "Compare Live") {
                                guard let api = store.api else { return }
                                comparison = try await api.call("/models/compare", method: "POST", body: .object(["selections": .array(compareSelection.sorted { $0.id < $1.id }.map(\.json)), "prompt": .string("Reply OK.")]))
                                await store.refresh()
                            }.disabled(!(2...4).contains(compareSelection.count))
                        }
                    }
                    if let comparison { AtlasCard { Text("本次即時比較").font(.headline); JSONTree(value: comparison) } }
                }
            }.padding(30).frame(maxWidth: 1250).frame(maxWidth: .infinity)
        }
    }
    private var filtered: [JSONValue] {
        store.myModels.filter {
            let state = $0["state"].string
            let visible = tab == "Hidden" ? state != "ACTIVE" : state == "ACTIVE"
            let matches = search.isEmpty || ($0["model_id"].string + " " + $0["metadata"].pretty + " " + store.providerName($0["provider_id"].string)).localizedCaseInsensitiveContains(search)
            return visible && matches && (category == "ALL" || $0["metadata"]["primary_category"].string == category)
        }
    }
    private func metric(_ title: String, value: String) -> some View { VStack(alignment: .leading, spacing: 4) { Text(title).font(.caption2).foregroundStyle(.secondary); Text(value).font(.callout.monospacedDigit()) } }
}

struct CatalogView: View {
    @ObservedObject var store: AtlasStore
    let providerID: String?
    @State private var items: [JSONValue] = []
    @State private var search = ""
    @State private var credentialChoice = ""
    @State private var endpoint = "CHAT_COMPLETIONS"
    @State private var manualID = ""
    @State private var manualName = ""
    @State private var problem: String?
    @State private var loading = false
    @State private var shown = 100
    private var availableKeys: [JSONValue] { store.credentials.filter { (providerID == nil || $0["provider_id"].string == providerID) && $0["role"].string == "INFERENCE" && $0["enabled"].boolean } }
    private var key: JSONValue? { availableKeys.first { identity($0) == credentialChoice } }
    private var filtered: [JSONValue] { items.filter { (providerID == nil || $0["provider_id"].string == providerID) && (search.isEmpty || ($0["id"].string + " " + $0["display_name"].string + " " + $0["metadata"].pretty).localizedCaseInsensitiveContains(search)) } }
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            connectionCard
            manualForm
            TextField("搜尋完整 Catalog、別名與模型名稱", text: $search).textFieldStyle(.roundedBorder)
            if loading { ProgressView("讀取 Catalog…") }
            if let problem { Text(problem).foregroundStyle(.red) }
            ForEach(filtered.prefix(shown), id: \.self) { model in
                AtlasCard {
                    HStack {
                        VStack(alignment: .leading, spacing: 5) { Text(model["display_name"].string).font(.headline); Text(model["id"].string + " · " + store.providerName(model["provider_id"].string)).font(.caption).foregroundStyle(.secondary).textSelection(.enabled) }
                        Spacer()
                        if let key, joined(model, key: key) { SmallBadge(text: "已加入", color: atlasAccent) }
                        else { ActionButton(title: "加入") { try await add(model["id"].string, provider: model["provider_id"].string) }.disabled(key == nil || key?["provider_id"].string != model["provider_id"].string) }
                    }
                    if !model["metadata"]["description"].string.isEmpty { Text(model["metadata"]["description"].string).font(.callout).foregroundStyle(.secondary).lineLimit(3) }
                }
            }
            if filtered.count > shown { Button("顯示更多（共 \(filtered.count) 個）") { shown += 100 } }
            if items.isEmpty && !loading { Text("尚無 Catalog。可同步模型清單，或直接手動加入模型 ID。").foregroundStyle(.secondary).padding(.vertical, 20) }
        }.task { await load() }
    }
    private var connectionCard: some View {
            AtlasCard {
                Text("加入使用的連線").font(.headline)
                Picker("帳戶與 Key", selection: $credentialChoice) {
                    Text("選擇帳戶與 Key").tag("")
                    ForEach(availableKeys, id: \.self) { item in
                        Text(credentialLabel(item)).tag(identity(item))
                    }
                }
                Picker("用途與端點", selection: $endpoint) {
                    ForEach(endpointOptions, id: \.self) { Text($0).tag($0) }
                    ForEach(store.endpoints.filter { $0["provider_id"].string == key?["provider_id"].string }, id: \.self) { Text($0["operation"].string + " · " + $0["url"].string).tag($0["id"].string) }
                }
                if availableKeys.isEmpty { Text("先在供應商詳情加入並啟用推論 Key。").foregroundStyle(.secondary) }
                HStack {
                    ActionButton(title: "同步這個 Key 的 Catalog") {
                        guard let key else { throw AtlasError.http(422) }
                        _ = try await store.mutate("/models/sync", body: .object(["provider_id": key["provider_id"], "account_id": key["account_id"], "credential_id": key["id"]])); await load()
                    }.disabled(key == nil)
                    Spacer()
                    Button("重新載入") { Task { await load() } }.disabled(loading)
                }
            }
    }
    private var manualForm: some View {
            DisclosureGroup("手動加入模型") {
                VStack(alignment: .leading, spacing: 12) {
                    FormField(title: "模型 ID", text: $manualID); FormField(title: "顯示名稱", text: $manualName)
                    ActionButton(title: "加入 My Models") {
                        guard let key else { throw AtlasError.http(422) }
                        _ = try await store.mutate("/models", body: .object(["provider_id": key["provider_id"], "id": .string(manualID), "display_name": .string(manualName.isEmpty ? manualID : manualName)]))
                        try await add(manualID, provider: key["provider_id"].string); manualID = ""; manualName = ""; await load()
                    }.disabled(key == nil || manualID.isEmpty)
                }.padding(.top, 12)
            }
    }
    private func credentialLabel(_ item: JSONValue) -> String {
        let provider = store.providerName(item["provider_id"].string)
        let account = store.accounts.first { $0["provider_id"].string == item["provider_id"].string && $0["id"].string == item["account_id"].string }
        let accountName = account?["label"].string ?? item["account_id"].string
        return [provider, accountName, item["label"].string, item["group"].string].joined(separator: " · ")
    }
    private func identity(_ value: JSONValue) -> String { JSONValue.array([value["provider_id"], value["account_id"], value["id"]]).pretty }
    private func scope(_ id: String, key: JSONValue) -> JSONValue { .object(["provider_id": key["provider_id"], "account_id": key["account_id"], "credential_id": key["id"], "model_id": .string(id), "group": key["group"], "endpoint": .string(endpoint)]) }
    private func joined(_ model: JSONValue, key: JSONValue) -> Bool { store.myModels.contains { ModelScope($0) == ModelScope(scope(model["id"].string, key: key)) && $0["state"].string == "ACTIVE" } }
    private func add(_ id: String, provider: String) async throws {
        guard let key, key["provider_id"].string == provider else { throw AtlasError.http(422) }
        _ = try await store.mutate("/my-models", body: scope(id, key: key))
    }
    private func load() async {
        guard let api = store.api, !loading else { return }; loading = true; problem = nil
        defer { loading = false }
        do {
            var rows: [JSONValue] = [], offset = 0
            while true {
                try Task.checkCancellation()
                let page = try await api.get("/models", query: [URLQueryItem(name: "limit", value: "200"), URLQueryItem(name: "offset", value: String(offset))]).requiredArray()
                rows += page; if page.count < 200 { break }; offset += page.count
            }
            items = rows
        } catch { if !Task.isCancelled { problem = error.localizedDescription } }
    }
}

struct ModelDetailView: View {
    @ObservedObject var store: AtlasStore
    let model: JSONValue
    @Environment(\.dismiss) private var dismiss
    @State private var tab = "概覽"
    @State private var window = "7d"
    @State private var detail: JSONValue = .object([:])
    @State private var problem: String?
    @State private var capabilities: Set<String> = []
    @State private var favorite = false
    @State private var watchlist = false
    @State private var state = "ACTIVE"
    @State private var testResult: JSONValue?
    @State private var metadataText = "{}"
    @State private var factsText = "{}"
    @State private var loadRevision = 0
    private var scope: ModelScope { ModelScope(model) }
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 5) { Text(scope.modelID).font(.title2.weight(.bold)); Text(store.scopeTitle(model)).font(.caption).foregroundStyle(.secondary); Text(store.operationName(model)).font(.caption).foregroundStyle(atlasAccent) }
                Spacer(); Button("使用此模型") { store.chooseModel(model); dismiss() }.buttonStyle(.borderedProminent).disabled(store.generating || state != "ACTIVE")
                Button("關閉") { dismiss() }.keyboardShortcut(.cancelAction)
            }.padding(25)
            Picker("詳情", selection: $tab) { ForEach(["概覽", "價格與額度", "效能", "歷史", "設定"], id: \.self) { Text($0).tag($0) } }.pickerStyle(.segmented).padding(.horizontal, 24).padding(.bottom, 16)
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if let problem { Text(problem).foregroundStyle(.red) }
                    if tab == "概覽" {
                        AtlasCard { Text("模型資訊").font(.headline); JSONTree(value: detail["metadata"]); Text("已確認能力").font(.headline); Text(detail["capabilities"].array.map(\.string).joined(separator: " · ")).foregroundStyle(.secondary) }
                        AtlasCard { HStack { Text("本機手動測試").font(.headline); Spacer(); ActionButton(title: "測試這個連線") { guard let api = store.api else { return }; testResult = try await api.call("/models/test", method: "POST", body: scope.json); await store.refresh(); await load() } }; if let testResult { JSONTree(value: testResult) } }
                    }
                    if tab == "價格與額度" {
                        AtlasCard { Text("最後有效觀測").font(.headline); Text("標價、實際扣款與免費機制分開保存。").foregroundStyle(.secondary); JSONTree(value: detail["last_good"]) }
                        AtlasCard { Text("額度資源池").font(.headline); JSONTree(value: detail["resource_pools"]) }
                        AtlasCard { Text("帳戶資料").font(.headline); JSONTree(value: detail["account_facts"]) }
                    }
                    if tab == "效能" {
                        Picker("時間窗", selection: $window) { ForEach(["1h", "24h", "7d", "30d", "all"], id: \.self) { Text($0).tag($0) } }.pickerStyle(.segmented)
                        AtlasCard { Text("我的 Key · 本機實測").font(.headline); JSONTree(value: detail["local"]) }
                        AtlasCard { Text("供應商回報").font(.headline); JSONTree(value: detail["provider_facts"]) }
                        AtlasCard { Text("外部參考").font(.headline); JSONTree(value: detail["external"]) }
                    }
                    if tab == "歷史" { AtlasCard { Text("最新觀測與保留歷史").font(.headline); JSONTree(value: detail["latest"]); Divider(); JSONTree(value: detail["history"]) } }
                    if tab == "設定" { settingsContent }
                }.padding(24)
            }
        }.frame(width: 830, height: 760)
        .task {
            capabilities = Set(model["capabilities"].array.map(\.string)); favorite = model["favorite"].boolean
            watchlist = model["watchlist"].boolean; state = model["state"].string
            await load(); metadataText = detail["metadata"].pretty
        }
        .onChange(of: window) { _ in Task { await load() } }
    }
    private var settingsContent: some View {
        VStack(spacing: 18) {
            AtlasCard {
                Text("模型顯示").font(.headline)
                Toggle("收藏", isOn: $favorite); Toggle("加入觀察清單", isOn: $watchlist)
                Picker("顯示狀態", selection: $state) { Text("正常顯示").tag("ACTIVE"); Text("隱藏").tag("HIDDEN"); Text("封存").tag("ARCHIVED") }
                Text("隱藏或封存會保留對話、樣本與全部歷史。").font(.caption).foregroundStyle(.secondary)
                ActionButton(title: "儲存顯示偏好") { var body = scope.json; body["state"] = .string(state); body["favorite"] = .bool(favorite); body["watchlist"] = .bool(watchlist); _ = try await store.mutate("/models/lifecycle", body: body) }
            }
            AtlasCard {
                Text("確認此連線的能力").font(.headline)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 180), alignment: .leading)], alignment: .leading, spacing: 12) {
                    ForEach(["REASONING", "CODING", "TOOL_CALLING", "FUNCTION_CALLING", "STRUCTURED_OUTPUT", "JSON_MODE", "VISION", "FILES", "IMAGE_GENERATION", "IMAGE_EDITING", "AUDIO_INPUT", "AUDIO_OUTPUT", "EMBEDDING", "RERANK", "WEB_SEARCH", "COMPUTER_USE", "LONG_CONTEXT", "SAFETY", "OTHER"], id: \.self) { capability in
                        Toggle(capability, isOn: Binding(get: { capabilities.contains(capability) }, set: { if $0 { capabilities.insert(capability) } else { capabilities.remove(capability) } })).toggleStyle(.checkbox).font(.caption)
                    }
                }
                ActionButton(title: "確認並保存能力") { var body = scope.json; body["confirm"] = .bool(true); body["capabilities"] = .array(capabilities.sorted().map(JSONValue.string)); _ = try await store.mutate("/models/capabilities", body: body); await load() }
            }
            AtlasCard {
                DisclosureGroup("分類、別名與手動資料") {
                    JSONEditor(title: "模型分類與別名", text: $metadataText)
                    ActionButton(title: "保存模型資訊") { guard var value = try? JSONValue.parse(metadataText) else { throw AtlasError.invalidJSON }; value["provider_id"] = .string(scope.providerID); value["model_id"] = .string(scope.modelID); _ = try await store.mutate("/models/metadata", body: value); await load() }
                    JSONEditor(title: "手動價格／額度標註", text: $factsText)
                    ActionButton(title: "保存手動標註") { guard let facts = try? JSONValue.parse(factsText) else { throw AtlasError.invalidJSON }; var body = scope.json; for (key, value) in facts.fields { guard !scope.json.fields.keys.contains(key) else { throw AtlasError.invalidJSON }; body[key] = value }; _ = try await store.mutate("/models/facts", body: body); await load() }
                }
            }
        }
    }
    private func load() async {
        guard let api = store.api else { return }; loadRevision += 1; let revision = loadRevision
        do {
            let value = try await api.get("/models/detail", query: scope.query + [URLQueryItem(name: "window", value: window)])
            guard loadRevision == revision else { return }; detail = value; problem = nil
        } catch { if loadRevision == revision { problem = error.localizedDescription } }
    }
}
