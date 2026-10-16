import AtlasCore
import SwiftUI

struct ResearchView: View {
    @ObservedObject var store: AtlasStore
    @State private var dashboard: JSONValue = .object([:])
    @State private var sources: [JSONValue] = []
    @State private var runs: [JSONValue] = []
    @State private var candidates: JSONValue = .array([])
    @State private var reflections: JSONValue = .array([])
    @State private var knowledge: JSONValue = .array([])
    @State private var selected: Set<String> = []
    @State private var budget = "20"
    @State private var minimalInference = false
    @State private var useLMSpeed = false
    @State private var sourceName = ""
    @State private var sourceURL = ""
    @State private var sourceKind = "HTML"
    @State private var language = "zh"
    @State private var sourceConfig = "{}"
    @State private var tab = "執行"
    @State private var recipes: [JSONValue] = []
    @State private var recipeProvider = ""
    @State private var recipeReason = ""
    @State private var recipeText = "{}"
    @State private var problem: String?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack { Text("研究工作區").font(.title3.weight(.semibold)); Spacer(); Button("重新載入") { Task { await load() } } }
                Picker("研究分區", selection: $tab) { ForEach(["執行", "來源", "候選與證據", "反思與知識", "Recipes"], id: \.self) { Text($0).tag($0) } }.pickerStyle(.segmented)
                if let problem { Text(problem).foregroundStyle(.red) }
                if tab == "執行" { execution }
                if tab == "來源" { sourceEditor }
                if tab == "候選與證據" { AtlasCard { JSONTree(value: candidates) } }
                if tab == "反思與知識" { AtlasCard { Text("研究反思").font(.headline); JSONTree(value: reflections); Text("版本化知識").font(.headline); JSONTree(value: knowledge) } }
                if tab == "Recipes" { recipeEditor }
            }.padding(24)
        }.task { await load() }
    }
    private var execution: some View {
        VStack(spacing: 18) {
            AtlasCard { Text("研究漏斗").font(.headline); JSONTree(value: dashboard) }
            AtlasCard {
                Text("開始一輪研究").font(.headline)
                ForEach(sources, id: \.self) { source in
                    Toggle(source["name"].string + " · " + source["language"].string, isOn: Binding(get: { selected.contains(source["id"].string) }, set: { if $0 { selected.insert(source["id"].string) } else { selected.remove(source["id"].string) } })).toggleStyle(.checkbox).disabled(!source["enabled"].boolean)
                }
                FormField(title: "本輪工作預算（1–200）", text: $budget)
                Toggle("加入 LMSpeed 公開候選", isOn: $useLMSpeed)
                Toggle("允許對目前選定模型做最小推論驗證", isOn: $minimalInference)
                ActionButton(title: "開始研究") {
                    guard let n = Int(budget), let api = store.api else { throw AtlasError.invalidJSON }
                    var value: JSONValue = .object(["source_ids": .array(selected.sorted().map(JSONValue.string)), "budget": .number(Double(n)), "minimal_inference": .bool(minimalInference), "probe_budget": .number(minimalInference ? 1 : 0), "lmspeed_seeds": .bool(useLMSpeed)])
                    if let scope = store.selectedScope { value["selections"] = .array([scope.json]) }
                    _ = try await api.call("/research/start", method: "POST", body: value); await load()
                }
            }
            ForEach(runs, id: \.self) { run in
                AtlasCard {
                    HStack { Text(run["id"].string).font(.caption).textSelection(.enabled); Spacer(); SmallBadge(text: run["status"].string, color: atlasAccent) }
                    JSONTree(value: run["summary"])
                    HStack {
                        ForEach(["PAUSE", "RESUME", "CANCEL"], id: \.self) { action in
                            ActionButton(title: ["PAUSE": "暫停", "RESUME": "繼續", "CANCEL": "停止"][action]!) { guard let api = store.api else { return }; _ = try await api.call("/research/control", method: "POST", body: .object(["id": run["id"], "action": .string(action)])); await load() }
                        }
                    }
                }
            }
        }
    }
    private var sourceEditor: some View {
        VStack(spacing: 18) {
            ForEach(sources, id: \.self) { source in
                AtlasCard {
                    Text(source["name"].string).font(.headline); Text(source["url"].string).foregroundStyle(.secondary).textSelection(.enabled)
                    ActionButton(title: source["enabled"].boolean ? "停用來源" : "啟用來源") { guard let api = store.api else { return }; var value = source; value["enabled"] = .bool(!source["enabled"].boolean); _ = try await api.call("/research/sources", method: "POST", body: value); await load() }
                }
            }
            AtlasCard {
                Text("新增公開來源").font(.headline)
                FormField(title: "來源名稱", text: $sourceName); FormField(title: "公開 HTTPS 網址", text: $sourceURL)
                HStack {
                    Picker("內容類型", selection: $sourceKind) { Text("網頁").tag("HTML"); Text("JSON").tag("JSON") }
                    Picker("語言", selection: $language) { ForEach(["en", "zh", "ru", "ja", "ko", "es", "pt", "id"], id: \.self) { Text($0).tag($0) } }
                }
                if sourceKind == "JSON" { JSONEditor(title: "公開資料欄位對應", text: $sourceConfig) }
                ActionButton(title: "儲存來源（預設停用）") {
                    guard let api = store.api, let config = try? JSONValue.parse(sourceConfig) else { throw AtlasError.invalidJSON }
                    _ = try await api.call("/research/sources", method: "POST", body: .object(["name": .string(sourceName), "url": .string(sourceURL), "kind": .string(sourceKind), "language": .string(language), "enabled": .bool(false), "config": config])); sourceName = ""; sourceURL = ""; await load()
                }
            }
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
    private func load() async {
        guard let api = store.api else { return }
        do {
            async let d = api.get("/research/dashboard"), s = api.get("/research/sources"), r = api.get("/research/runs")
            async let c = api.get("/research/funnel"), f = api.get("/research/reflections"), k = api.get("/research/knowledge"), p = api.get("/recipes")
            let result = try await (d, s, r, c, f, k, p)
            dashboard = result.0; sources = try result.1.requiredArray(); runs = try result.2.requiredArray()
            candidates = result.3; reflections = result.4; knowledge = result.5; recipes = try result.6.requiredArray(); problem = nil
        } catch { problem = error.localizedDescription }
    }
}
