import AtlasCore
import SwiftUI

struct ModelsView: View {
    @ObservedObject var store: AtlasStore
    @State private var tab = "My Models"
    @State private var search = ""
    @State private var category = "ALL"
    @State private var favorites = false
    @State private var compareSelection: Set<ModelScope> = []
    @State private var comparison: JSONValue?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack {
                    PageHeading(eyebrow: "Model Library", title: "模型", subtitle: "\(store.activeModels.count) 個可用連線 · \(store.myModels.filter { $0["favorite"].boolean }.count) 個收藏")
                    Spacer()
                    Button { store.modelPickerOpen = true } label: { Label("選擇模型", systemImage: "magnifyingglass") }.help("選擇模型（⌘K）").disabled(store.generating)
                }
                Picker("模型庫", selection: $tab) { Text("My Models").tag("My Models"); Text("Catalog").tag("Catalog"); Text("隱藏與封存").tag("Hidden") }.pickerStyle(.segmented).frame(maxWidth: 420)
                if tab == "Catalog" { CatalogView(store: store, providerID: nil) }
                else {
                    filters
                    if filtered.isEmpty {
                        EmptyCard(icon: search.isEmpty ? "square.stack.3d.up" : "magnifyingglass", title: "沒有符合的模型", detail: "調整篩選條件，或從 Catalog 加入要用的模型。", action: "探索 Catalog") { tab = "Catalog" }.frame(height: 300)
                    } else {
                        VStack(spacing: 0) {
                            ForEach(filtered) { entry in
                                HStack(spacing: 0) {
                                    if tab != "Hidden" {
                                        Toggle("比較 " + entry.title, isOn: Binding(get: { compareSelection.contains(entry.scope) }, set: { if $0 { compareSelection.insert(entry.scope) } else { compareSelection.remove(entry.scope) } }))
                                            .labelsHidden().toggleStyle(.checkbox).padding(.leading, 16).padding(.trailing, 2)
                                    }
                                    Button { store.detailModel = entry.model } label: { ModelLibraryRow(store: store, entry: entry) }.buttonStyle(AtlasRowButtonStyle())
                                }
                                if entry.id != filtered.last?.id { Divider().padding(.leading, tab == "Hidden" ? 60 : 88) }
                            }
                        }.background(atlasSurface).cornerRadius(13)
                            .overlay(RoundedRectangle(cornerRadius: 13).stroke(Color.primary.opacity(0.07)))
                    }
                    if !compareSelection.isEmpty { compareBar }
                    if let comparison { AtlasCard { Text("本次即時比較").font(.headline); JSONTree(value: comparison) } }
                    Text("效能來自這個連線的本機請求；詳細來源與歷史可在模型內查看。").font(.caption).foregroundStyle(.secondary)
                }
            }.padding(24).frame(maxWidth: 1150).frame(maxWidth: .infinity)
        }
    }
    private var filters: some View {
        VStack(alignment: .leading, spacing: 10) {
            SearchField(placeholder: "搜尋模型、帳戶、Key 或群組", text: $search)
            HStack {
                Picker("類別", selection: $category) {
                    Text("所有類別").tag("ALL")
                    ForEach(["CHAT_REASONING", "CODING", "IMAGE", "AUDIO", "EMBEDDING", "RERANK", "OTHER"], id: \.self) { Text(categoryTitle($0)).tag($0) }
                }.labelsHidden().frame(width: 145)
                Toggle("只看收藏", isOn: $favorites).toggleStyle(.checkbox).font(.callout)
                Spacer()
                if !search.isEmpty || favorites || category != "ALL" { Button("重設") { search = ""; favorites = false; category = "ALL" }.buttonStyle(.plain).foregroundStyle(atlasAccent) }
                Text("\(filtered.count) 個連線").font(.caption).foregroundStyle(.secondary)
            }
        }
    }
    private var compareBar: some View {
        HStack(spacing: 12) {
            Text("已選 \(compareSelection.count) 個連線").font(.callout)
            Spacer()
            Text("會送出實際請求").font(.caption).foregroundStyle(.secondary)
            Button("清除") { compareSelection = [] }
            ActionButton(title: "即時比較") {
                guard let api = store.api else { return }
                comparison = try await api.call("/models/compare", method: "POST", body: .object(["selections": .array(compareSelection.sorted { $0.id < $1.id }.map(\.json)), "prompt": .string("Reply OK.")]))
                await store.refresh()
            }.disabled(!(2...4).contains(compareSelection.count))
        }.padding(12).background(atlasAccent.opacity(0.06)).cornerRadius(10)
    }
    private var filtered: [ModelSearchEntry] {
        store.modelEntries(store.myModels).filter {
            let state = $0.model["state"].string
            return (tab == "Hidden" ? state != "ACTIVE" : state == "ACTIVE") && $0.matches(search)
                && (!favorites || $0.model["favorite"].boolean) && (category == "ALL" || $0.model["metadata"]["primary_category"].string == category)
        }
    }
    private func categoryTitle(_ value: String) -> String {
        ["CHAT_REASONING": "對話與推理", "CODING": "程式設計", "IMAGE": "圖片", "AUDIO": "音訊", "EMBEDDING": "向量", "RERANK": "重新排序", "OTHER": "其他"][value] ?? value
    }
}

private struct ModelLibraryRow: View {
    @ObservedObject var store: AtlasStore
    let entry: ModelSearchEntry
    private var performance: JSONValue { entry.model["performance"] }
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ModelMark(name: entry.title).padding(.top, 2)
            VStack(alignment: .leading, spacing: 7) {
                HStack(spacing: 8) {
                    Text(entry.title).font(.body.weight(.semibold)).lineLimit(1)
                    if entry.model["favorite"].boolean { Image(systemName: "star.fill").font(.caption2).foregroundStyle(.orange) }
                    if !entry.model["enabled"].boolean { SmallBadge(text: "已停用") }
                    Spacer(minLength: 4)
                    Text(operationTitle(store.operationName(entry.model))).font(.caption).foregroundStyle(atlasAccent)
                    Image(systemName: "chevron.right").font(.caption2.weight(.semibold)).foregroundStyle(.tertiary)
                }
                Text(store.scopeTitle(entry.model) + " · " + entry.scope.endpoint).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                metrics
            }
        }.padding(16).frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
            .help(entry.scope.modelID + " · " + store.scopeTitle(entry.model) + " · " + entry.scope.endpoint)
    }
    private var metrics: some View {
        VStack(alignment: .leading, spacing: 5) {
            if let samples = performance["samples"].numeric, samples > 0 {
                HStack(spacing: 16) {
                    Text("我的上線率 " + (performance["success_rate"].numeric.map { String(format: "%.1f%%", $0 * 100) } ?? "—"))
                    if let ttft = performance["ttft"]["mean"].numeric { Text(String(format: "平均 TTFT %.0f ms", ttft)) }
                    if let tps = performance["tps"]["mean"].numeric { Text(String(format: "平均 TPS %.1f", tps)) }
                }.font(.caption.monospacedDigit()).foregroundStyle(.primary)
                Text("\(performance["samples"].display) 筆本機樣本 · \(windowLabel) · \(samples < 20 ? "樣本較少" : "僅代表此時間窗")")
                    .font(.caption2).foregroundStyle(.secondary)
            } else { Text("尚無本機樣本").font(.caption).foregroundStyle(.tertiary) }
        }
    }
    private var windowLabel: String {
        guard let milliseconds = performance["window_ms"].numeric, milliseconds > 0 else { return "時間窗未提供" }
        let hours = milliseconds / 3_600_000
        return hours >= 24 ? String(format: "近 %.0f 天", hours / 24) : String(format: "近 %.0f 小時", hours)
    }
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
    private var usableModel: JSONValue? { store.activeModels.first { ModelScope($0) == scope } }
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 5) { Text(scope.modelID).font(.title2.weight(.bold)); Text(store.scopeTitle(model)).font(.caption).foregroundStyle(.secondary); Text(store.operationName(model)).font(.caption).foregroundStyle(atlasAccent) }
                Spacer(); Button("使用此模型") { if let usableModel { store.chooseModel(usableModel); dismiss() } }.buttonStyle(.borderedProminent).disabled(store.generating || usableModel == nil)
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
