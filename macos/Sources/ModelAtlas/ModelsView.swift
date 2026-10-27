import AtlasCore
import SwiftUI

struct ModelsView: View {
    @ObservedObject var store: AtlasStore
    @State private var tab = "My Models"
    @State private var search = ""
    @State private var modelFilters = ModelFilters()
    @State private var selecting = false
    @State private var shown = 100
    @State private var compareSelection: Set<ModelScope> = []
    @State private var comparison: ComparisonController?
    @State private var bulkPending = false
    var body: some View {
        let entries = filtered
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack {
                    PageHeading(eyebrow: "Model Library", title: "模型", subtitle: "\(store.activeModels.count) 個可用連線 · \(store.myModels.filter { $0["favorite"].boolean }.count) 個收藏")
                    Spacer()
                    Button { store.modelPickerOpen = true } label: { Label("選擇模型", systemImage: "magnifyingglass") }.buttonStyle(AtlasActionStyle()).help("選擇模型（⌘K）").disabled(store.generating)
                }
                AtlasTabs(selection: $tab, items: [AtlasTab("My Models", "我的模型", "square.stack"), AtlasTab("Catalog", "探索", "square.grid.2x2"), AtlasTab("Hidden", "已隱藏", "archivebox"), AtlasTab("Research", "全球研究", "globe"), AtlasTab("Knowledge", "知識", "books.vertical")])
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
                                        .contextMenu {
                                            Button("在 Playground 使用") { store.chooseModel(entry.model) }.disabled(store.generating || !entry.model["enabled"].boolean)
                                            Button("複製模型 ID") { copyText(entry.scope.modelID) }
                                            Button("複製連線摘要") { copyText(entry.scope.modelID + " · " + store.scopeTitle(entry.model) + " · " + store.endpointLabel(entry.model)) }
                                        }
                                }
                                if entry.id != entries.prefix(shown).last?.id { Divider().padding(.leading, selecting ? 88 : 60) }
                            }
                        }.background(AtlasAquaSurface()).cornerRadius(12)
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(atlasHairline, lineWidth: 0.5))
                    }
                    if filtered.count > shown { Button("顯示更多（共 \(filtered.count) 個）") { shown += 100 } }
                    Text("目前以" + ["local":"我的實測", "provider":"供應商回報", "external":"外部參考"][modelFilters.source, default: "我的實測"] + "篩選與排序；各來源分開保存。").font(.caption).foregroundStyle(.secondary)
                }
            }.padding(24).frame(maxWidth: 1150).frame(maxWidth: .infinity)
        }.safeAreaInset(edge: .bottom) {
            if selecting && ["My Models", "Hidden"].contains(tab) { compareBar }
        }.sheet(item: $comparison) { ComparisonView(store: store, controller: $0) }
    }
    private var filters: some View {
        VStack(alignment: .leading, spacing: 10) {
            SearchField(placeholder: "搜尋模型、帳戶、Key 或群組", text: $search)
            HStack {
                AtlasFilterChip(title: "全部", selected: modelFilters.favorite.isEmpty && modelFilters.price.isEmpty) { modelFilters.favorite = ""; modelFilters.price = "" }
                AtlasFilterChip(title: "收藏", icon: "star", selected: modelFilters.favorite == "favorite") { modelFilters.favorite = "favorite"; modelFilters.price = "" }
                AtlasFilterChip(title: "目前免費", icon: "gift", selected: modelFilters.price == "FREE_NOW") { modelFilters.price = "FREE_NOW"; modelFilters.favorite = "" }
                Spacer()
                Button(selecting ? "完成選取" : "選取") { selecting.toggle(); if !selecting { compareSelection = [] } }.buttonStyle(.plain).font(.system(size: 11)).foregroundStyle(.secondary)
                Button { search = ""; modelFilters = ModelFilters(); shown = 100 } label: { Image(systemName: "arrow.counterclockwise") }.buttonStyle(.plain).foregroundStyle(.secondary).help("重設所有篩選")
                Text("\(filtered.count) 個連線").font(.caption).foregroundStyle(.secondary)
            }
            ModelFiltersView(store: store, filters: $modelFilters)
        }
    }
    private var canCompare: Bool {
        offers.count == compareSelection.count && ComparisonOffer.problem(offers, prompt: "") == nil
    }
    private var offers: [ComparisonOffer] {
        store.modelEntries(store.myModels.filter { compareSelection.contains(ModelScope($0)) }).sorted { $0.id < $1.id }.map {
            ComparisonOffer(model: $0.model, title: $0.title, context: store.scopeTitle($0.model), operation: store.operationName($0.model))
        }
    }
    private var compareBar: some View {
        HStack(spacing: 14) {
            Text(compareSelection.isEmpty ? "選取要管理的連線" : "已選 \(compareSelection.count) 個連線").font(.system(size: 12, weight: .medium))
            if !compareSelection.isEmpty { Button("清除") { compareSelection = [] }.buttonStyle(.plain).foregroundStyle(.secondary) }
            Spacer()
            Menu("批次管理") {
                ForEach(["HIDDEN", "ARCHIVED", "ACTIVE"], id: \.self) { state in
                    Button(["HIDDEN":"預覽隱藏", "ARCHIVED":"預覽封存", "ACTIVE":"預覽恢復"][state]!) {
                        guard !bulkPending else { return }; bulkPending = true
                        let selections = compareSelection.sorted { $0.id < $1.id }.map(\.json)
                        Task {
                            defer { bulkPending = false }
                            do {
                                guard let api = store.api else { return }
                                let value = try await api.call("/assistant/actions/preview", method: "POST", body: .object(["kind": .string("MODEL_BATCH"), "selections": .array(selections), "patch": .object(["state": .string(state)])]))
                                store.controlReview = ActionReviewController(api: api, value: value)
                            } catch { store.problem = error.localizedDescription }
                        }
                    }
                }
            }.fixedSize().disabled(bulkPending || !(2...100).contains(compareSelection.count))
            if bulkPending { ProgressView().controlSize(.small).accessibilityLabel("正在準備批次預覽") }
            Button { comparison = ComparisonController(offers: offers, refresh: { try await store.reload() }) } label: { Label("並列比較", systemImage: "rectangle.split.2x1") }
                .buttonStyle(AtlasActionStyle(prominent: true)).disabled(!canCompare || bulkPending)
                .help(ComparisonOffer.problem(offers, prompt: "") ?? "比較同一模型的各個連線")
        }.padding(.horizontal, 24).padding(.vertical, 14).background(.regularMaterial).overlay(alignment: .top) { Divider() }
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
        HStack(alignment: .center, spacing: 12) {
            ModelMark(name: entry.title).padding(.top, 2)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text(entry.title).font(.system(size: 14, weight: .semibold)).lineLimit(1)
                    if entry.model["favorite"].boolean { Image(systemName: "star.fill").font(.caption2).foregroundStyle(.orange) }
                    if !entry.model["enabled"].boolean { SmallBadge(text: "已停用") }
                    Spacer(minLength: 4)
                    SmallBadge(text: operationTitle(store.operationName(entry.model)), color: atlasAccent)
                    Image(systemName: "chevron.right").font(.caption2.weight(.semibold)).foregroundStyle(.tertiary)
                }
                Text(store.scopeTitle(entry.model) + " · " + store.endpointLabel(entry.model)).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1)
                if let label = priceLabel {
                    Text(label).font(.system(size: 10, weight: .medium)).foregroundStyle(entry.model["price_state"].string == "FREE_NOW" ? atlasTextAccent : Color.secondary)
                }
                metrics
            }
        }.padding(.horizontal, 16).padding(.vertical, 12).frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
            .help(entry.scope.modelID + " · " + store.scopeTitle(entry.model) + " · " + entry.scope.endpoint)
    }
    private var priceLabel: String? {
        switch entry.model["price_state"].string {
        case "FREE_NOW":
            let cache = entry.model["offer"]["effective_price"]["legs"]["cache"]
            if let value = cache.numeric ?? Double(cache.string), value.isFinite, value > 0 { return "FREE NOW · Cache 另計費" }
            return "FREE NOW"
        case "PAID": return "付費"
        case "PARTIALLY_FREE": return "部分免費"
        default: return nil
        }
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
                Text("\(performance["samples"].display) 筆樣本 · \(windowLabel) · \(samples < 20 ? "樣本較少" : "本機實測")")
                    .font(.system(size: 10)).foregroundStyle(.secondary)
            } else { Text("尚無本機樣本").font(.caption).foregroundStyle(.secondary) }
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

@MainActor struct ModelDetailView: View {
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
    @State private var loading = true
    @State private var customStart = Date().addingTimeInterval(-7 * 86400)
    @State private var customEnd = Date()
    @State private var chartReceipt: String?
    @State private var historyKind = "observations"
    @State private var performanceSource = "provider"
    @State private var performanceChoiceMade = false
    @StateObject private var providerPerformance: ProviderSiteController
    @StateObject private var priceArchive: ObservationArchiveController
    @StateObject private var historyArchive: ObservationArchiveController
    private var scope: ModelScope { ModelScope(model) }
    private var usableModel: JSONValue? { store.activeModels.first { ModelScope($0) == scope } }
    private var intelligence: ModelIntelligence { ModelIntelligence(detail: detail, scope: scope) }
    init(store: AtlasStore, model: JSONValue, initialTab: String = "概覽", priceArchive: ObservationArchiveController? = nil, historyArchive: ObservationArchiveController? = nil, initialPerformanceSource: String? = nil, providerPerformance: ProviderSiteController? = nil) {
        self.store = store; self.model = model; _tab = State(initialValue: initialTab)
        _priceArchive = StateObject(wrappedValue: priceArchive ?? ObservationArchiveController(scope: ModelScope(model)))
        _historyArchive = StateObject(wrappedValue: historyArchive ?? ObservationArchiveController(scope: ModelScope(model)))
        _providerPerformance = StateObject(wrappedValue: providerPerformance ?? ProviderSiteController(providerID: model["provider_id"].string, modelID: model["model_id"].string, origin: store.providers.first(where: { $0["id"] == model["provider_id"] })?["base_url"].string))
        if let initialPerformanceSource { _performanceSource = State(initialValue: initialPerformanceSource); _performanceChoiceMade = State(initialValue: true) }
    }
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 6) { Text(ModelSearchEntry(model: model, context: "", operation: "").title).font(.system(size: 20, weight: .semibold)); Text(store.scopeTitle(model)).font(.system(size: 12)).foregroundStyle(.secondary); Text(operationTitle(store.operationName(model))).font(.caption).foregroundStyle(.secondary) }
                Spacer(); Button("使用此模型") { if let usableModel { store.chooseModel(usableModel); dismiss() } }.buttonStyle(AtlasActionStyle(prominent: true)).disabled(store.generating || usableModel == nil)
                Button("關閉") { dismiss() }.keyboardShortcut(.cancelAction)
            }.padding(25)
            Picker("詳情", selection: $tab) { ForEach(["概覽", "價格", "額度", "效能", "歷史", "設定"], id: \.self) { Text($0).tag($0) } }.pickerStyle(.segmented).padding(.horizontal, 24).padding(.bottom, 16)
            if tab == "效能" {
                Picker("資料來源", selection: Binding(get: { performanceSource }, set: { performanceSource = $0; performanceChoiceMade = true })) {
                    Text("本機實測").tag("local"); Text("站方效能").tag("provider"); Text("外部參考").tag("external")
                }.pickerStyle(.segmented).padding(.horizontal, 24).padding(.bottom, 16)
            }
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if loading { ProgressView("讀取模型資料…").controlSize(.small) }
                    if let problem { HStack { Text(problem).foregroundStyle(.red); Spacer(); Button("重試") { Task { await load() } } } }
                    if !loading && problem == nil && intelligence.matchesSelection { AtlasLoadedMarker(name: "model-intelligence-loaded-" + tab).frame(height: 0) }
                    if tab == "概覽" {
                        AtlasCard { Text("模型資訊").font(.headline); JSONTree(value: detail["metadata"]); Text("已確認能力").font(.headline); Text(detail["capabilities"].array.map(\.string).joined(separator: " · ")).foregroundStyle(.secondary) }
                        AtlasCard { HStack { Text("本機手動測試").font(.headline); Spacer(); ActionButton(title: "同步資料") { _ = try await store.mutate("/intelligence/sync", body: scope.json); await load() }; ActionButton(title: "測試這個連線") { guard let api = store.api else { return }; testResult = try await api.call("/models/test", method: "POST", body: scope.json); await store.refresh(); await load() } }; if let testResult { JSONTree(value: testResult) } }
                    }
                    if tab == "價格" { ModelPricingPanel(intelligence: intelligence, priceState: usableModel?["price_state"].string ?? model["price_state"].string, cachePrice: (usableModel ?? model)["offer"]["effective_price"]["legs"]["cache"], api: store.api, archive: priceArchive) }
                    if tab == "額度" { ModelQuotaPanel(intelligence: intelligence); DisclosureGroup("帳戶來源紀錄") { JSONTree(value: detail["account_facts"]) } }
                    if tab == "效能", intelligence.matchesSelection { performanceContent }
                    if tab == "歷史" {
                        Picker("歷史種類", selection: $historyKind) { Text("觀測紀錄").tag("observations"); Text("請求紀錄").tag("requests") }.pickerStyle(.segmented)
                        if historyKind == "observations" { ModelObservationHistory(api: store.api, archive: historyArchive) }
                        else { RequestHistoryView(store: store, scope: scope) }
                    }
                    if tab == "設定" { settingsContent }
                }.padding(24)
            }.id(tab + (tab == "效能" ? performanceSource : ""))
        }.frame(width: 830, height: 760)
        .task {
            capabilities = Set(model["capabilities"].array.map(\.string)); favorite = model["favorite"].boolean
            watchlist = model["watchlist"].boolean; state = model["state"].string
            await load(); metadataText = detail["metadata"].pretty
        }
        .onChange(of: window) { _ in Task { await load() } }
        .sheet(isPresented: Binding(get: { chartReceipt != nil }, set: { if !$0 { chartReceipt = nil } })) {
            if let chartReceipt { RequestReceiptView(store: store, sampleID: chartReceipt).atlasModal(store) }
        }
        .onDisappear { priceArchive.cancel(); historyArchive.cancel(); providerPerformance.cancel() }
    }
    @ViewBuilder private var performanceContent: some View {
        if performanceSource == "provider" {
            ProviderPerformanceView(controller: providerPerformance, api: store.api, selectedGroup: scope.group, origin: store.providers.first(where: { $0["id"] == model["provider_id"] })?["base_url"].string)
        } else if performanceSource == "local" {
            if intelligence.samples.isEmpty && !loading {
                AtlasCard {
                    Label("還沒有本機請求紀錄", systemImage: "bubble.left.and.bubble.right").font(.headline)
                    Text("使用這條連線送出對話後，會記錄你這把 Key 的速度、延遲與成功率。你也可以先查看站方提供的效能資料。").font(.callout).foregroundStyle(.secondary)
                    HStack { Button("查看站方效能") { performanceSource = "provider"; performanceChoiceMade = true }; Spacer(); Button("使用此模型") { if let usableModel { store.chooseModel(usableModel); dismiss() } }.disabled(usableModel == nil || store.generating) }
                }
            } else {
                Picker("時間窗", selection: $window) { ForEach(["1h", "24h", "7d", "30d", "all", "custom"], id: \.self) { Text(["1h":"1 小時", "24h":"24 小時", "7d":"7 天", "30d":"30 天", "all":"全部", "custom":"自訂"][$0]!).tag($0) } }.pickerStyle(.segmented)
                if window == "custom" { HStack { DatePicker("開始", selection: $customStart); DatePicker("結束", selection: $customEnd); Button("套用") { Task { await load() } } } }
                ModelPerformancePanel(intelligence: intelligence, openReceipt: { chartReceipt = $0 })
            }
        } else { ModelPerformancePanel(intelligence: intelligence, source: "external") }
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
        guard let api = store.api else { loading = false; return }; loadRevision += 1; let revision = loadRevision
        loading = true
        defer { if loadRevision == revision { loading = false } }
        do {
            var query = scope.query + [URLQueryItem(name: "window", value: window)]
            if window == "custom" {
                guard customEnd > customStart, customEnd <= Date() else { throw DraftProblem("結束時間需晚於開始時間，且不能超過現在。") }
                query += [URLQueryItem(name: "start", value: String(Int64(customStart.timeIntervalSince1970 * 1000))), URLQueryItem(name: "end", value: String(Int64(customEnd.timeIntervalSince1970 * 1000)))]
            }
            let value = try await api.get("/models/detail", query: query)
            guard loadRevision == revision else { return }
            guard ModelScope(value["selection"]) == scope else { throw AtlasError.invalidResponse }
            let previousObservation = detail["history"].array.first?["id"].string
            if !performanceChoiceMade { performanceSource = (ModelIntelligence(detail: value, scope: scope).local["samples"].numeric ?? 0) > 0 ? "local" : "provider"; performanceChoiceMade = true }
            detail = value; problem = nil
            if let previousObservation, previousObservation != value["history"].array.first?["id"].string {
                if priceArchive.state != nil { priceArchive.refresh(using: api, filter: priceArchive.requested) }
                if historyArchive.state != nil { historyArchive.refresh(using: api, filter: historyArchive.requested) }
            }
        } catch { if loadRevision == revision { problem = error.localizedDescription } }
    }
}
