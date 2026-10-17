import AtlasCore
import SwiftUI

struct ModelsView: View {
    @ObservedObject var store: AtlasStore
    @State private var tab = "My Models"
    @State private var search = ""
    @State private var modelFilters = ModelFilters()
    @State private var selecting = false
    @State private var shown = 100
    @State private var comparePrompt = "Reply OK."
    @State private var compareSelection: Set<ModelScope> = []
    @State private var comparison: JSONValue?
    var body: some View {
        let entries = filtered
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack {
                    PageHeading(eyebrow: "Model Library", title: "模型", subtitle: "\(store.activeModels.count) 個可用連線 · \(store.myModels.filter { $0["favorite"].boolean }.count) 個收藏")
                    Spacer()
                    Button { store.modelPickerOpen = true } label: { Label("選擇模型", systemImage: "magnifyingglass") }.help("選擇模型（⌘K）").disabled(store.generating)
                }
                Picker("模型庫", selection: $tab) { Text("My Models").tag("My Models"); Text("Catalog").tag("Catalog"); Text("隱藏與封存").tag("Hidden"); Text("全球研究").tag("Research"); Text("知識與擷取").tag("Knowledge") }.pickerStyle(.segmented)
                if tab == "Catalog" { CatalogView(store: store, providerID: nil) }
                else if tab == "Research" { ResearchView(store: store) }
                else if tab == "Knowledge" { KnowledgeView(store: store) }
                else {
                    filters
                    if filtered.isEmpty {
                        EmptyCard(icon: search.isEmpty ? "square.stack.3d.up" : "magnifyingglass", title: "沒有符合的模型", detail: "調整篩選條件，或從 Catalog 加入要用的模型。", action: "探索 Catalog") { tab = "Catalog" }.frame(height: 300)
                    } else {
                        VStack(spacing: 0) {
                            ForEach(entries.prefix(shown)) { entry in
                                HStack(spacing: 0) {
                                    if selecting {
                                        Toggle("比較 " + entry.title, isOn: Binding(get: { compareSelection.contains(entry.scope) }, set: { if $0 { compareSelection.insert(entry.scope) } else { compareSelection.remove(entry.scope) } }))
                                            .labelsHidden().toggleStyle(.checkbox).padding(.leading, 16).padding(.trailing, 2)
                                    }
                                    Button { store.detailModel = entry.model } label: { ModelLibraryRow(store: store, entry: entry, performanceSource: modelFilters.source) }.buttonStyle(AtlasRowButtonStyle())
                                }
                                if entry.id != entries.prefix(shown).last?.id { Divider().padding(.leading, selecting ? 88 : 60) }
                            }
                        }.background(atlasSurface).cornerRadius(13)
                            .overlay(RoundedRectangle(cornerRadius: 13).stroke(Color.primary.opacity(0.07)))
                    }
                    if filtered.count > shown { Button("顯示更多（共 \(filtered.count) 個）") { shown += 100 } }
                    if !compareSelection.isEmpty { compareBar }
                    if let comparison { AtlasCard { Text("本次即時比較").font(.headline); JSONTree(value: comparison) } }
                    Text("目前以" + ["local":"我的實測", "provider":"供應商回報", "external":"外部參考"][modelFilters.source, default: "我的實測"] + "篩選與排序；各來源分開保存。").font(.caption).foregroundStyle(.secondary)
                }
            }.padding(24).frame(maxWidth: 1150).frame(maxWidth: .infinity)
        }
    }
    private var filters: some View {
        VStack(alignment: .leading, spacing: 10) {
            SearchField(placeholder: "搜尋模型、帳戶、Key 或群組", text: $search)
            HStack {
                Button("全部") { modelFilters.favorite = ""; modelFilters.price = "" }
                Button("最愛") { modelFilters.favorite = "favorite"; modelFilters.price = "" }
                Button("FREE NOW") { modelFilters.price = "FREE_NOW"; modelFilters.favorite = "" }
                Spacer()
                Button(selecting ? "完成選取" : "選取") { selecting.toggle(); if !selecting { compareSelection = [] } }
                Button("重設篩選") { search = ""; modelFilters = ModelFilters(); shown = 100 }
                Text("\(filtered.count) 個連線").font(.caption).foregroundStyle(.secondary)
            }
            ModelFiltersView(store: store, filters: $modelFilters)
        }
    }
    private var canCompare: Bool {
        let rows = store.activeModels.filter { compareSelection.contains(ModelScope($0)) }
        let ids = Set(rows.map { $0["metadata"]["canonical_id"].string })
        return (2...4).contains(compareSelection.count) && rows.count == compareSelection.count && ids.count == 1 && !ids.contains("") && rows.allSatisfy { ["CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC_MESSAGES", "GEMINI_NATIVE", "CUSTOM"].contains(store.operationName($0)) } && comparePrompt.utf8.count <= 1000
    }
    private var compareBar: some View {
        AtlasCard {
            HStack { Text("已選 \(compareSelection.count) 個連線").font(.headline); Spacer(); Button("清除選取") { compareSelection = [] } }
            FormField(title: "比較使用的提示詞", text: $comparePrompt)
            Text("即時比較使用同一 Canonical 模型的 2–4 個可用連線；批次管理最多 100 個連線。").font(.caption).foregroundStyle(.secondary)
            HStack {
                ActionButton(title: "即時比較（實際請求）") {
                    guard let api = store.api else { return }
                    comparison = try await api.call("/models/compare", method: "POST", body: .object(["selections": .array(compareSelection.sorted { $0.id < $1.id }.map(\.json)), "prompt": .string(comparePrompt)]))
                    await store.refresh()
                }.disabled(!canCompare)
                Spacer()
                ForEach(["HIDDEN", "ARCHIVED", "ACTIVE"], id: \.self) { state in
                    ActionButton(title: ["HIDDEN":"預覽隱藏", "ARCHIVED":"預覽封存", "ACTIVE":"預覽恢復"][state]!) {
                        guard let api = store.api else { return }
                        let value = try await api.call("/assistant/actions/preview", method: "POST", body: .object(["kind": .string("MODEL_BATCH"), "selections": .array(compareSelection.sorted { $0.id < $1.id }.map(\.json)), "patch": .object(["state": .string(state)])]))
                        store.controlReview = ActionReviewController(api: api, value: value)
                    }.disabled(!(2...100).contains(compareSelection.count))
                }
            }
        }
    }
    private var filtered: [ModelSearchEntry] {
        let models = store.modelEntries(store.myModels).filter {
            let state = $0.model["state"].string
            return (tab == "Hidden" ? state != "ACTIVE" : state == "ACTIVE") && $0.matchesFuzzy(search) && modelFilters.matches($0.model)
        }.map(\.model)
        return store.modelEntries(modelFilters.ordered(models, weights: store.settings["ranking_weights"]))
    }

}

private struct ModelLibraryRow: View {
    @ObservedObject var store: AtlasStore
    let entry: ModelSearchEntry
    var performanceSource = "local"
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
                    Text(operationTitle(store.operationName(entry.model))).font(.caption).foregroundStyle(atlasTextAccent)
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
            if performanceSource != "local" {
                let sourceName = performanceSource == "provider" ? "供應商回報" : "外部參考"
                HStack(spacing: 14) {
                    Text(sourceName)
                    if let value = sourceMetric("tps") { Text(String(format: "TPS %.1f", value)) }
                    if let value = sourceMetric("ttft") { Text(String(format: "TTFT %.0f ms", value)) }
                    if let value = sourceMetric("latency") { Text(String(format: "延遲 %.0f ms", value)) }
                }.font(.caption.monospacedDigit())
                if sourceMetric("tps") == nil && sourceMetric("ttft") == nil && sourceMetric("latency") == nil { Text("這個來源尚無可用效能數值").font(.caption2).foregroundStyle(.secondary) }
            } else if let samples = performance["samples"].numeric, samples > 0 {
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
    private func sourceMetric(_ name: String) -> Double? { var filters = ModelFilters(); filters.source = performanceSource; return filters.metric(entry.model, name) }
    private var windowLabel: String {
        guard let milliseconds = performance["window_ms"].numeric, milliseconds > 0 else { return "時間窗未提供" }
        let hours = milliseconds / 3_600_000
        return hours >= 24 ? String(format: "近 %.0f 天", hours / 24) : String(format: "近 %.0f 小時", hours)
    }
}

struct CatalogView: View {
    @ObservedObject var store: AtlasStore
    let providerID: String?
    @State private var catalogProvider = ""
    @State private var catalogCategory = ""
    @State private var catalogCapability = ""
    @State private var membership = ""
    @State private var catalogDetail: JSONValue?
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
    private var filtered: [JSONValue] { items.filter { item in
        let joined = store.myModels.contains { $0["provider_id"] == item["provider_id"] && $0["model_id"] == item["id"] }
        var model = item; model["model_id"] = item["id"]
        let match = ModelSearchEntry(model: model, context: store.providerName(item["provider_id"].string), operation: "").matchesFuzzy(search)
        return (providerID == nil || item["provider_id"].string == providerID) && (catalogProvider.isEmpty || item["provider_id"].string == catalogProvider) && match && (catalogCategory.isEmpty || item["metadata"]["primary_category"].string == catalogCategory) && (catalogCapability.isEmpty || item["metadata"]["capabilities"].array.contains(.string(catalogCapability))) && (membership.isEmpty || (membership == "joined" ? joined : !joined))
    }.sorted { ($0["display_name"].string + $0["provider_id"].string + $0["id"].string).localizedStandardCompare($1["display_name"].string + $1["provider_id"].string + $1["id"].string) == .orderedAscending } }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            connectionCard
            manualForm
            TextField("搜尋完整 Catalog、別名與模型名稱", text: $search).textFieldStyle(.roundedBorder)
            DisclosureGroup("篩選 Catalog") {
                Picker("供應商", selection: $catalogProvider) { Text("全部供應商").tag(""); ForEach(store.providers) { Text($0["name"].string).tag($0.id) } }.disabled(providerID != nil)
                Picker("類別", selection: $catalogCategory) { Text("全部").tag(""); ForEach(["CHAT_REASONING", "CODING", "IMAGE", "AUDIO", "EMBEDDING", "RERANK", "SAFETY", "OTHER"], id: \.self) { Text($0).tag($0) } }
                Picker("能力", selection: $catalogCapability) { Text("全部").tag(""); ForEach(["VISION", "FILES", "AUDIO_INPUT", "AUDIO_OUTPUT", "IMAGE_GENERATION", "IMAGE_EDITING", "CODING", "REASONING", "TOOL_CALLING", "EMBEDDING", "RERANK"], id: \.self) { Text($0).tag($0) } }
                Picker("加入狀態", selection: $membership) { Text("全部").tag(""); Text("已加入").tag("joined"); Text("尚未加入").tag("unjoined") }
            }
            HStack { Text("顯示 \(min(shown, filtered.count)) / \(filtered.count) 個模型").font(.caption).foregroundStyle(.secondary); Spacer(); Button("重設篩選") { search = ""; catalogProvider = ""; catalogCategory = ""; catalogCapability = ""; membership = ""; shown = 100 } }
            if loading { ProgressView("讀取 Catalog…") }
            if let problem { Text(problem).foregroundStyle(.red) }
            ForEach(filtered.prefix(shown), id: \.self) { model in
                AtlasCard {
                    HStack {
                        VStack(alignment: .leading, spacing: 5) { Text(model["display_name"].string).font(.headline); Text(model["id"].string + " · " + store.providerName(model["provider_id"].string)).font(.caption).foregroundStyle(.secondary).textSelection(.enabled) }
                        Spacer()
                        Button("詳細資料") { catalogDetail = model }
                        if let key, joined(model, key: key) { SmallBadge(text: "已加入", color: atlasAccent) }
                        else { ActionButton(title: "加入") { try await add(model["id"].string, provider: model["provider_id"].string) }.disabled(key == nil || key?["provider_id"].string != model["provider_id"].string) }
                    }
                    if !model["metadata"]["description"].string.isEmpty { Text(model["metadata"]["description"].string).font(.callout).foregroundStyle(.secondary).lineLimit(3) }
                }
            }
            if filtered.count > shown { Button("顯示更多（共 \(filtered.count) 個）") { shown += 100 } }
            if items.isEmpty && !loading { Text("尚無 Catalog。可同步模型清單，或直接手動加入模型 ID。").foregroundStyle(.secondary).padding(.vertical, 20) }
        }.task { await load() }
        .sheet(item: $catalogDetail) { CatalogDetailView(store: store, model: $0).atlasModal(store) }
        .onChange(of: credentialChoice) { _ in endpoint = availableEndpoints(store: store, key: key).first?["id"].string ?? "" }
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
                    ForEach(availableEndpoints(store: store, key: key), id: \.self) { Text($0["operation"].string + " · " + $0["url"].string).tag($0["id"].string) }
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
        guard let key, key["provider_id"].string == provider, availableEndpoints(store: store, key: key).contains(where: { $0["id"].string == endpoint }) else { throw DraftProblem("請選擇同一來源的 Key 與端點。") }
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
    @State private var customStart = Date().addingTimeInterval(-7 * 86400)
    @State private var customEnd = Date()
    private var scope: ModelScope { ModelScope(model) }
    private var usableModel: JSONValue? { store.activeModels.first { ModelScope($0) == scope } }
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 5) { Text(scope.modelID).font(.title2.weight(.bold)); Text(store.scopeTitle(model)).font(.caption).foregroundStyle(.secondary); Text(store.operationName(model)).font(.caption).foregroundStyle(atlasTextAccent) }
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
                        AtlasCard { HStack { Text("本機手動測試").font(.headline); Spacer(); ActionButton(title: "同步資料") { _ = try await store.mutate("/intelligence/sync", body: scope.json); await load() }; ActionButton(title: "測試這個連線") { guard let api = store.api else { return }; testResult = try await api.call("/models/test", method: "POST", body: scope.json); await store.refresh(); await load() } }; if let testResult { JSONTree(value: testResult) } }
                    }
                    if tab == "價格與額度" {
                        AtlasCard { Text("最後有效觀測").font(.headline); Text("標價、實際扣款與免費機制分開保存。").foregroundStyle(.secondary); JSONTree(value: detail["last_good"]) }
                        AtlasCard { Text("額度資源池").font(.headline); JSONTree(value: detail["resource_pools"]) }
                        AtlasCard { Text("帳戶資料").font(.headline); JSONTree(value: detail["account_facts"]) }
                    }
                    if tab == "效能" {
                        Picker("時間窗", selection: $window) { ForEach(["1h", "24h", "7d", "30d", "all", "custom"], id: \.self) { Text($0).tag($0) } }.pickerStyle(.segmented)
                        if window == "custom" { HStack { DatePicker("開始", selection: $customStart); DatePicker("結束", selection: $customEnd); Button("套用") { Task { await load() } } } }
                        AtlasCard { Text("我的 Key · 本機實測").font(.headline); JSONTree(value: detail["local"]) }
                        AtlasCard { Text("供應商回報").font(.headline); JSONTree(value: detail["provider_facts"]) }
                        AtlasCard { Text("外部參考").font(.headline); JSONTree(value: detail["external"]) }
                    }
                    if tab == "歷史" { RequestHistoryView(store: store, scope: scope); AtlasCard { Text("最新觀測與保留歷史").font(.headline); JSONTree(value: detail["latest"]); Divider(); JSONTree(value: detail["history"]) } }
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
            ModelInformationEditor(store: store, scope: scope, metadata: detail["metadata"]) { await load() }
            NotesEditor(store: store, subject: .object(["selection": scope.json]), initial: model["offer"])
            ModelFactsEditor(store: store, scope: scope) { await load() }

        }
    }
    private func load() async {
        guard let api = store.api else { return }; loadRevision += 1; let revision = loadRevision
        do {
            var query = scope.query + [URLQueryItem(name: "window", value: window)]
            if window == "custom" {
                guard customEnd > customStart, customEnd <= Date() else { throw DraftProblem("結束時間需晚於開始時間，且不能超過現在。") }
                query += [URLQueryItem(name: "start", value: String(Int64(customStart.timeIntervalSince1970 * 1000))), URLQueryItem(name: "end", value: String(Int64(customEnd.timeIntervalSince1970 * 1000)))]
            }
            let value = try await api.get("/models/detail", query: query)
            guard loadRevision == revision else { return }; detail = value; problem = nil
        } catch { if loadRevision == revision { problem = error.localizedDescription } }
    }
}
