import AtlasCore
import SwiftUI

struct ResearchView: View {
    @ObservedObject var store: AtlasStore
    @State private var dashboard: JSONValue = .object([:])
    @State private var sources: [JSONValue] = []
    @State private var runs: [JSONValue] = []
    @State private var candidates: [JSONValue] = []
    @State private var recipes: [JSONValue] = []
    @State private var aa: JSONValue = .object([:])
    @State private var selected: Set<String> = []
    @State private var budget = "20"
    @State private var probes = "0"
    @State private var target = ""
    @State private var minimalInference = false
    @State private var useLMSpeed = false
    @State private var useSearch = false
    @State private var refreshAA = false
    @State private var tab = "執行"
    @State private var candidateState = ""
    @State private var search = ""
    @State private var recipeProvider = ""
    @State private var recipeReason = ""
    @State private var recipeText = "{}"
    @State private var problem: String?
    @State private var loading = false
    @State private var candidateDetail: JSONValue?
    @State private var runDetail: JSONValue?
    @State private var sourceDetail: JSONValue?
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack { Text("全球研究").font(.title3.weight(.semibold)); Spacer(); Button("重新載入") { Task { await load() } }.disabled(loading) }
            Picker("研究分區", selection: $tab) { ForEach(["執行", "來源", "候選與證據", "AA 排名", "Recipes"], id: \.self) { Text($0).tag($0) } }.pickerStyle(.segmented)
            if let problem { HStack { Text(problem).foregroundStyle(.red); Button("重試") { Task { await load() } } } }
            if tab == "執行" { execution }
            if tab == "來源" { sourceList }
            if tab == "候選與證據" { candidateList }
            if tab == "AA 排名" { AARankingCard(value: aa) { guard let api = store.api else { return }; _ = try await api.call("/research/aa/sync", method: "POST", body: .object([:])); await load() } }
            if tab == "Recipes" { recipeEditor }
        }
        .task { await load(); while !Task.isCancelled { do { try await Task.sleep(nanoseconds: 3_000_000_000); if runs.contains(where: { ["QUEUED", "RUNNING"].contains($0["status"].string) }) { await load() } } catch { return } } }
        .sheet(item: $candidateDetail, onDismiss: { Task { await load() } }) { CandidateDetailView(store: store, candidate: $0).atlasModal(store) }
        .sheet(item: $runDetail, onDismiss: { Task { await load() } }) { ResearchRunView(store: store, run: $0).atlasModal(store) }
        .sheet(item: $sourceDetail, onDismiss: { Task { await load() } }) { ResearchSourceEditor(store: store, initial: $0).atlasModal(store) }
    }
    private var execution: some View {
        VStack(alignment: .leading, spacing: 18) {
            AtlasCard {
                Text("研究漏斗").font(.headline)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 125))]) {
                    ForEach(dashboard["funnel"]["states"].fields.keys.sorted(), id: \.self) { state in VStack(spacing: 6) { Text(dashboard["funnel"]["states"][state].display).font(.title2.monospacedDigit()); Text(researchStateTitle(state)).font(.caption).foregroundStyle(.secondary) }.padding(10) }
                }
            }
            AtlasCard {
                Text("開始一輪研究").font(.headline)
                ForEach(sources.filter { $0["enabled"].boolean }, id: \.self) { source in Toggle(source["name"].string + " · " + source["language"].string, isOn: Binding(get: { selected.contains(source["id"].string) }, set: { if $0 { selected.insert(source["id"].string) } else { selected.remove(source["id"].string) } })).toggleStyle(.checkbox) }
                Picker("查詢我的連線", selection: $target) { Text("不查我的 Key").tag(""); ForEach(store.activeModels.filter { ["CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC_MESSAGES", "GEMINI_NATIVE", "CUSTOM"].contains(store.operationName($0)) }, id: \.self) { Text($0["model_id"].string + " · " + store.scopeTitle($0)).tag(ModelScope($0).id) } }
                HStack { FormField(title: "HTTP 預算（1–200）", text: $budget); FormField(title: "推論預算（0–10）", text: $probes).disabled(!minimalInference) }
                Toggle("使用 Brave 公開搜尋", isOn: $useSearch)
                Toggle("加入 LMSpeed 公開候選", isOn: $useLMSpeed)
                Toggle("更新 AA 動態排名", isOn: $refreshAA)
                Toggle("允許最小推論驗證", isOn: $minimalInference)
                ActionButton(title: "開始研究") {
                    guard let api = store.api, let n = Int(budget), (1...200).contains(n), let p = Int(probes), (0...10).contains(p) else { throw DraftProblem("HTTP 預算需為 1–200；推論預算需為 0–10。") }
                    let selections = store.activeModels.first(where: { ModelScope($0).id == target }).map { [ModelScope($0).json] } ?? []
                    _ = try await api.call("/research/start", method: "POST", body: .object(["source_ids": .array(selected.sorted().map(JSONValue.string)), "budget": .number(Double(n)), "minimal_inference": .bool(minimalInference), "probe_budget": .number(minimalInference ? Double(p) : 0), "lmspeed_seeds": .bool(useLMSpeed), "search": .bool(useSearch), "refresh_aa": .bool(refreshAA), "selections": .array(selections)])); await load()
                }
            }
            ForEach(runs, id: \.self) { run in
                AtlasCard {
                    HStack { VStack(alignment: .leading, spacing: 5) { Text("研究 · " + run["status"].string).font(.headline); Text(run["id"].string).font(.caption).foregroundStyle(.secondary) }; Spacer(); Text(run["used"].display + " / " + run["budget"].display + " HTTP").monospacedDigit() }
                    HStack {
                        Button("執行紀錄與預算") { runDetail = run }
                        Spacer()
                        if ["QUEUED", "RUNNING"].contains(run["status"].string) { ActionButton(title: "暫停") { try await control(run, action: "PAUSE") } }
                        if ["PAUSED", "BUDGET_EXHAUSTED"].contains(run["status"].string) { Button("恢復研究…") { runDetail = run } }
                        if ["QUEUED", "RUNNING", "PAUSED", "BUDGET_EXHAUSTED"].contains(run["status"].string) { ActionButton(title: "取消研究") { try await control(run, action: "CANCEL") } }
                    }
                }
            }
        }
    }
    private var sourceList: some View {
        VStack(alignment: .leading, spacing: 18) {
            Button { sourceDetail = .object([:]) } label: { Label("新增公開來源", systemImage: "plus") }.buttonStyle(.borderedProminent)
            ForEach(sources, id: \.self) { source in AtlasCard { HStack { VStack(alignment: .leading, spacing: 5) { Text(source["name"].string).font(.headline); Text(source["url"].string).font(.caption).foregroundStyle(.secondary).lineLimit(2) }; Spacer(); SmallBadge(text: source["enabled"].boolean ? "已啟用" : "已停用", color: atlasAccent) }; HStack { Button("編輯來源") { sourceDetail = source }; ActionButton(title: source["enabled"].boolean ? "停用" : "啟用") { guard let api = store.api else { return }; var body = source; body["enabled"] = .bool(!source["enabled"].boolean); _ = try await api.call("/research/sources", method: "POST", body: body); await load() } } } }
        }
    }
    private var candidateList: some View {
        VStack(alignment: .leading, spacing: 14) {
            SearchField(placeholder: "搜尋候選模型或供應商", text: $search)
            Picker("候選狀態", selection: $candidateState) { Text("全部狀態").tag(""); ForEach(["DISCOVERED", "VERIFYING", "VERIFIED", "HIGH_VALUE_GREY_ZONE", "STALE", "FAILED", "REJECTED"], id: \.self) { Text(researchStateTitle($0)).tag($0) } }
            ForEach(candidates.filter { (candidateState.isEmpty || $0["state"].string == candidateState) && (search.isEmpty || ($0["model_id"].string + " " + $0["provider_id"].string).localizedCaseInsensitiveContains(search)) }, id: \.self) { candidate in
                Button { candidateDetail = candidate } label: { AtlasCard { HStack { Text(candidate["model_id"].string == "UNKNOWN" ? "未確認模型" : candidate["model_id"].string).font(.headline); Spacer(); SmallBadge(text: researchStateTitle(candidate["state"].string), color: atlasAccent) }; Text(store.providerName(candidate["provider_id"].string)).font(.callout).foregroundStyle(.secondary); HStack { Text("RPD " + candidate["rpd"].display); Text("TPD " + candidate["tpd"].display) }.font(.caption).foregroundStyle(.secondary) } }.buttonStyle(.plain)
            }
            if candidates.isEmpty { Text("尚無候選。加入來源或選擇公開搜尋，開始一輪研究。").foregroundStyle(.secondary) }
        }
    }
    private var recipeEditor: some View {
        VStack(spacing: 18) {
            ForEach(recipes, id: \.self) { item in
                AtlasCard {
                    Text(store.providerName(item["provider_id"].string) + " · v" + item["version"].display).font(.headline)
                    Text(item["reason"].string).foregroundStyle(.secondary)
                    JSONTree(value: item["settings"])
                    if item["active"].boolean { SmallBadge(text: "目前版本", color: atlasAccent) }
                    else { ActionButton(title: "切換至此版本") { guard let api = store.api else { return }; _ = try await api.call("/recipes/rollback", method: "POST", body: .object(["provider_id": item["provider_id"], "version": item["version"], "confirm": .bool(true)])); await load() } }
                }
            }
            AtlasCard {
                Text("建立宣告式 Recipe").font(.headline)
                Picker("供應商", selection: $recipeProvider) { Text("選擇供應商").tag(""); ForEach(store.providers, id: \.self) { Text($0["name"].string).tag($0["id"].string) } }
                JSONEditor(title: "設定", text: $recipeText); FormField(title: "修改原因", text: $recipeReason)
                ActionButton(title: "保存新版本") { guard let api = store.api, let settings = try? JSONValue.parse(recipeText) else { throw AtlasError.invalidJSON }; _ = try await api.call("/recipes", method: "POST", body: .object(["provider_id": .string(recipeProvider), "settings": settings, "reason": .string(recipeReason)])); await load() }
            }
        }
    }
    private func control(_ run: JSONValue, action: String) async throws { guard let api = store.api else { return }; _ = try await api.call("/research/control", method: "POST", body: .object(["id": run["id"], "action": .string(action)])); await load() }
    private func load() async {
        guard let api = store.api, !loading else { return }; loading = true; defer { loading = false }
        do {
            let result = try await api.get("/research/dashboard")
            dashboard = result; sources = result["sources"].array; runs = result["runs"].array; candidates = result["candidates"].array
            async let a = api.get("/research/aa"), p = api.get("/recipes"); let extras = try await (a, p); aa = extras.0; recipes = try extras.1.requiredArray(); problem = nil
        } catch { if !Task.isCancelled { problem = error.localizedDescription } }
    }
}

func researchStateTitle(_ state: String) -> String { ["DISCOVERED":"新線索", "VERIFYING":"驗證中", "VERIFIED":"符合門檻", "HIGH_VALUE_GREY_ZONE":"待查證", "STALE":"待更新", "FAILED":"失敗", "REJECTED":"不符條件"][state] ?? state }
