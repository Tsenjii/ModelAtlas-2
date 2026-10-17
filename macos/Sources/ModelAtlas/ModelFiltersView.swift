import AtlasCore
import SwiftUI

struct ModelFiltersView: View {
    @ObservedObject var store: AtlasStore
    @Binding var filters: ModelFilters
    var body: some View {
        DisclosureGroup("篩選與排序") {
            VStack(alignment: .leading, spacing: 14) {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 220), alignment: .leading)], alignment: .leading, spacing: 14) {
                    Picker("供應商", selection: $filters.provider) { Text("全部供應商").tag(""); ForEach(store.providers) { Text($0["name"].string + " · " + $0.id).tag($0.id) } }
                    Picker("帳戶與 Key", selection: $filters.credential) {
                        Text("全部我的 Key").tag("")
                        ForEach(store.credentials.filter { $0["role"].string == "INFERENCE" }, id: \.self) { key in Text(keyLabel(key)).tag(JSONValue.array([key["provider_id"], key["account_id"], key["id"]]).pretty) }
                    }
                    Picker("群組", selection: $filters.group) { Text("全部群組").tag(""); ForEach(Array(Set(store.myModels.map { $0["group"].string.isEmpty ? "__DEFAULT__" : $0["group"].string })).sorted(), id: \.self) { Text($0 == "__DEFAULT__" ? "預設群組" : $0).tag($0) } }
                    choice("類別", value: $filters.category, options: ["CHAT_REASONING", "CODING", "IMAGE", "AUDIO", "EMBEDDING", "RERANK", "SAFETY", "OTHER"])
                    choice("已確認能力", value: $filters.capability, options: ["VISION", "FILES", "AUDIO_INPUT", "AUDIO_OUTPUT", "IMAGE_GENERATION", "IMAGE_EDITING", "CODING", "REASONING", "TOOL_CALLING", "FUNCTION_CALLING", "STRUCTURED_OUTPUT", "JSON_MODE", "EMBEDDING", "RERANK", "WEB_SEARCH", "COMPUTER_USE", "LONG_CONTEXT", "SAFETY", "OTHER"])
                    choice("模態", value: $filters.modality, options: ["TEXT_INPUT", "TEXT_OUTPUT", "IMAGE_INPUT", "IMAGE_OUTPUT", "AUDIO_INPUT", "AUDIO_OUTPUT", "FILE_INPUT"])
                    choice("價格狀態", value: $filters.price, options: ["FREE_NOW", "PAID", "PARTIALLY_FREE", "UNKNOWN"])
                    Picker("收藏", selection: $filters.favorite) { Text("全部").tag(""); Text("最愛").tag("favorite"); Text("追蹤").tag("watchlist") }
                    Picker("資料來源", selection: $filters.source) { Text("我的實測").tag("local"); Text("Provider 回報").tag("provider"); Text("External 參考").tag("external") }
                    Picker("排序", selection: $filters.sort) { ForEach(["smart", "name", "recent", "new", "strength", "tps", "ttft", "latency", "quota", "price"], id: \.self) { Text(["smart":"綜合排序", "name":"名稱", "recent":"最近使用", "new":"最近加入", "strength":"模型強度", "tps":"TPS", "ttft":"TTFT", "latency":"延遲", "quota":"額度", "price":"輸入價格"][$0]!).tag($0) } }
                    Picker("變更", selection: $filters.change) { Text("全部").tag(""); Text("7 天內加入").tag("newly_added"); Text("價格變更").tag("price_changed"); Text("7 天內使用").tag("recently_used") }
                    FormField(title: "Vendor", text: $filters.vendor)
                }
                DisclosureGroup("數值條件") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 180), alignment: .leading)], spacing: 12) {
                        ForEach(["context", "tps", "ttft", "latency", "success1h", "success24h", "samples", "input", "output", "cache"], id: \.self) { key in
                            FormField(title: ["context":"最低 Context", "tps":"最低 TPS", "ttft":"最高 TTFT（ms）", "latency":"最高延遲（ms）", "success1h":"成功率 1h 至少（%）", "success24h":"成功率 24h 至少（%）", "samples":"最少實測次數", "input":"輸入上限（USD／1M）", "output":"輸出上限（USD／1M）", "cache":"Cache 上限（USD／1M）"][key]!, text: Binding(get: { filters.numbers[key] ?? "" }, set: { filters.numbers[key] = $0 }))
                        }
                    }.padding(.top, 10)
                    Text("缺少資料的模型不會通過數值條件；未知價格不當作免費。").font(.caption).foregroundStyle(.secondary)
                }
                Toggle("只顯示已啟用且具備認證的連線", isOn: $filters.usable).toggleStyle(.checkbox)
                RankingWeightsView(store: store)
            }.padding(.top, 12)
        }.accessibilityIdentifier("model-advanced-filters")
    }
    private func keyLabel(_ key: JSONValue) -> String { [store.providerName(key["provider_id"].string), key["account_id"].string, key["label"].string].joined(separator: " · ") }
    private func choice(_ title: String, value: Binding<String>, options: [String]) -> some View {
        Picker(title, selection: value) { Text("全部").tag(""); ForEach(options, id: \.self) { Text($0).tag($0) } }
    }
}

struct RankingWeightsView: View {
    @ObservedObject var store: AtlasStore
    @State private var weights = ["strength": 1.0, "speed": 1.0, "quota": 1.0, "reliability": 1.0]
    @State private var saved = false
    var body: some View {
        DisclosureGroup("綜合排序權重") {
            VStack(alignment: .leading, spacing: 10) {
                ForEach(["strength", "speed", "quota", "reliability"], id: \.self) { key in
                    HStack {
                        Text(["strength":"強度", "speed":"速率", "quota":"額度", "reliability":"可靠度"][key]!).frame(width: 65, alignment: .leading)
                        Slider(value: Binding(get: { weights[key] ?? 1 }, set: { weights[key] = $0; saved = false }), in: 0...(key == "reliability" ? 3 : 5), step: 0.25)
                        Text(String(format: "%.2f", weights[key] ?? 1)).monospacedDigit().frame(width: 45)
                    }
                }
                ActionButton(title: "保存排序權重") { let snapshot = weights; _ = try await store.mutate("/settings", body: .object(["ranking_weights": .object(snapshot.mapValues(JSONValue.number))])); if weights == snapshot { saved = true } }
                if saved { Label("已儲存", systemImage: "checkmark.circle").font(.caption) }
            }.padding(.top, 10)
        }.task { for key in weights.keys { weights[key] = store.settings["ranking_weights"][key].numeric ?? 1 } }
    }
}
