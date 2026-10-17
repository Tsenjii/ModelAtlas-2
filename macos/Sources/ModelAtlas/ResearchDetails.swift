import AtlasCore
import SwiftUI

struct ResearchSourceEditor: View {
    @ObservedObject var store: AtlasStore
    let initial: JSONValue
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var url = ""
    @State private var kind = "HTML"
    @State private var language = "zh"
    @State private var provider = ""
    @State private var root = ""
    @State private var official = false
    @State private var enabled = false
    @State private var mappings: [FieldMapping] = []
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack { Text("研究公開來源").font(.title2.weight(.semibold)); Spacer(); Button("關閉") { dismiss() }.keyboardShortcut(.cancelAction) }
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    FormField(title: "來源名稱", text: $name); FormField(title: "公開 HTTPS 網址", text: $url)
                    HStack { Picker("內容類型", selection: $kind) { Text("網頁").tag("HTML"); Text("JSON").tag("JSON") }; Picker("語言", selection: $language) { ForEach(["en", "zh", "ru", "ja", "ko", "es", "pt", "id"], id: \.self) { Text($0).tag($0) } } }
                    Picker("對應供應商", selection: $provider) { Text("尚未確認").tag(""); ForEach(store.providers) { Text($0["name"].string).tag($0.id) } }
                    Toggle("官方來源", isOn: $official); Toggle("啟用來源", isOn: $enabled)
                    if kind == "JSON" { FormField(title: "清單 JSON Pointer", text: $root); MappingEditor(rows: $mappings); Text("可對應 model_id、rpd、tpd、taiwan、recurring、free、base_url、access_method、auth_kind、strength、restrictions。").font(.caption).foregroundStyle(.secondary) }
                    ActionButton(title: "儲存來源") {
                        guard let api = store.api, !name.isEmpty else { throw DraftProblem("請填入來源名稱與公開網址。") }
                        let fields = try FieldMapping.json(mappings)
                        _ = try await api.call("/research/sources", method: "POST", body: .object(["id": .string(initial["id"].string.isEmpty ? UUID().uuidString.lowercased() : initial["id"].string), "name": .string(name), "url": .string(url), "kind": .string(kind), "language": .string(language), "enabled": .bool(enabled), "config": .object(["provider_id": .string(provider), "official": .bool(official), "items_pointer": .string(root), "fields": fields]) ])); dismiss()
                    }
                }
            }
        }.padding(24).frame(width: 760, height: 680).task { if !initial["id"].string.isEmpty { name = initial["name"].string; url = initial["url"].string; kind = initial["kind"].string; language = initial["language"].string; enabled = initial["enabled"].boolean; official = initial["config"]["official"].boolean; provider = initial["config"]["provider_id"].string; root = initial["config"]["items_pointer"].string; mappings = FieldMapping.values(initial["config"]["fields"]) } }
    }
}

struct ResearchRunView: View {
    @ObservedObject var store: AtlasStore
    let run: JSONValue
    @Environment(\.dismiss) private var dismiss
    @State private var tasks: [JSONValue] = []
    @State private var additional = "0"
    @State private var probes = "0"
    @State private var retry = false
    @State private var problem: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack { Text("研究紀錄").font(.title2.weight(.semibold)); Spacer(); Button("關閉") { dismiss() }.keyboardShortcut(.cancelAction) }
            HStack { SmallBadge(text: run["status"].string, color: atlasAccent); Text(run["id"].string).font(.caption).textSelection(.enabled) }
            if let problem { Text(problem).foregroundStyle(.red); Button("重試讀取") { Task { await load() } } }
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    AtlasCard { Text("本輪結果").font(.headline); JSONTree(value: run["summary"]) }
                    ForEach(tasks, id: \.self) { item in AtlasCard { HStack { Text(item["kind"].string).font(.headline); Spacer(); SmallBadge(text: item["status"].string) }; Text(item["attempts"].display + " 次嘗試").font(.caption).foregroundStyle(.secondary); DisclosureGroup("計畫與結果") { JSONTree(value: item["payload"]); JSONTree(value: item["result"]) } } }
                    if ["PAUSED", "BUDGET_EXHAUSTED", "COMPLETED"].contains(run["status"].string) {
                        AtlasCard {
                            Text("恢復與追加預算").font(.headline)
                            HStack { FormField(title: "追加 HTTP 預算", text: $additional); FormField(title: "追加推論預算", text: $probes) }
                            Toggle("重試失敗項目", isOn: $retry)
                            ActionButton(title: "確認恢復研究") {
                                guard let api = store.api, let extra = Int(additional), (0...200).contains(extra), let extraProbes = Int(probes), (0...10).contains(extraProbes), Double(extra) + (run["budget"].numeric ?? 0) <= 200, Double(extraProbes) + (run["probe_budget"].numeric ?? 0) <= 10 else { throw DraftProblem("整輪 HTTP 預算上限為 200，推論預算上限為 10。") }
                                _ = try await api.call("/research/control", method: "POST", body: .object(["id": run["id"], "action": .string("RESUME"), "additional_budget": .number(Double(extra)), "additional_probes": .number(Double(extraProbes)), "retry_failed": .bool(retry)])); dismiss()
                            }
                        }
                    }
                }
            }
        }.padding(24).frame(width: 780, height: 710).task { await load() }
    }
    private func load() async { guard let api = store.api else { return }; do { tasks = try await api.get("/research/tasks", query: [URLQueryItem(name: "run_id", value: run["id"].string)]).requiredArray(); problem = nil } catch { problem = error.localizedDescription } }
}

struct CandidateDetailView: View {
    @ObservedObject var store: AtlasStore
    let candidate: JSONValue
    @Environment(\.dismiss) private var dismiss
    @State private var evidence: [JSONValue] = []
    @State private var selected: Set<String> = []
    @State private var taiwan = "UNKNOWN"
    @State private var strength = "UNKNOWN"
    @State private var access = "UNKNOWN"
    @State private var reason = ""
    @State private var problem: String?
    @State private var notice: String?
    @State private var adding = false
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack { VStack(alignment: .leading, spacing: 5) { Text(candidate["model_id"].string).font(.title2.weight(.semibold)); Text(store.providerName(candidate["provider_id"].string)).foregroundStyle(.secondary) }; Spacer(); Button("關閉") { dismiss() }.keyboardShortcut(.cancelAction) }
            if let problem { Text(problem).foregroundStyle(.red); Button("重試讀取證據") { Task { await load() } } }
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    AtlasCard { SmallBadge(text: researchStateTitle(candidate["state"].string), color: atlasAccent); Text(candidate["reason"].string); DisclosureGroup("候選與額度資料") { JSONTree(value: candidate) } }
                    ForEach(evidence, id: \.self) { proof in
                        AtlasCard {
                            HStack { Text(proof["source_type"].string).font(.headline); Spacer(); SmallBadge(text: proof["verified"].boolean ? "已驗證／人工判讀" : "未驗證主張") }
                            Text(proof["claim"].string).textSelection(.enabled)
                            HStack { Button("查看證據來源") { openHTTPS(proof["source_url"].string) }; Button("複製證據 ID") { copyText(proof["id"].string) }; Spacer(); Toggle("作為判讀依據", isOn: Binding(get: { selected.contains(proof["id"].string) }, set: { if $0 { selected.insert(proof["id"].string) } else { selected.remove(proof["id"].string) } })).toggleStyle(.checkbox) }
                            DisclosureGroup("正規化與時間") { JSONTree(value: proof) }
                        }
                    }
                    AtlasCard {
                        Text("確認研究判讀").font(.headline)
                        Picker("台灣可用性", selection: $taiwan) { ForEach(["UNKNOWN", "YES", "NO"], id: \.self) { Text($0).tag($0) } }
                        Picker("模型能力", selection: $strength) { ForEach(["UNKNOWN", "STRONG", "WEAK"], id: \.self) { Text($0).tag($0) } }
                        Picker("使用方式", selection: $access) { ForEach(["UNKNOWN", "INFERENCE_READY", "CODING_AGENT_ONLY", "WEB_ONLY", "CUSTOM"], id: \.self) { Text($0).tag($0) } }
                        FormField(title: "判讀理由", text: $reason)
                        ActionButton(title: "確認並儲存判讀") {
                            guard let api = store.api, !selected.isEmpty, !reason.isEmpty else { throw DraftProblem("請選擇已保存的證據並填入判讀理由。") }
                            _ = try await api.call("/research/review", method: "POST", body: .object(["candidate_id": candidate["id"], "taiwan": .string(taiwan), "strength": .string(strength), "access_method": .string(access), "reason": .string(reason), "evidence_ids": .array(selected.sorted().map(JSONValue.string)), "confirm": .bool(true)])); notice = "研究判讀已儲存"
                        }
                        if let notice { Label(notice, systemImage: "checkmark.circle") }
                    }
                    if let provider = store.providers.first(where: { $0["id"] == candidate["provider_id"] }) { Text("供應商已在工作區：" + provider["name"].string).foregroundStyle(.secondary) }
                    else if !candidate["base_url"].string.isEmpty { Button("以這份資料新增供應商…") { adding = true } }
                }
            }
        }.padding(24).frame(width: 800, height: 730).task { taiwan = candidate["taiwan"].string; strength = candidate["strength"].string; access = candidate["access_method"].string.isEmpty ? "UNKNOWN" : candidate["access_method"].string; await load() }
        .sheet(isPresented: $adding) { AddProviderView(store: store, seed: candidate).atlasModal(store) }
    }
    private func load() async { guard let api = store.api else { return }; do { evidence = try await api.get("/research/evidence", query: [URLQueryItem(name: "candidate_id", value: candidate["id"].string)]).requiredArray(); problem = nil } catch { problem = error.localizedDescription } }
}
