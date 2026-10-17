import AtlasCore
import SwiftUI

struct ModelFiltersView: View {
    @ObservedObject var store: AtlasStore
    @Binding var filters: ModelFilters
    @State private var expanded: Bool
    init(store: AtlasStore, filters: Binding<ModelFilters>, expanded: Bool = false) { self.store = store; _filters = filters; _expanded = State(initialValue: expanded) }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            filterToolbar
            if expanded {
            AtlasCard {
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
            }.controlSize(.regular)
            }
        }.accessibilityIdentifier("model-advanced-filters")
    }
    private var filterToolbar: some View {
            HStack(spacing: 12) {
                AtlasFilterChip(title: expanded ? "收起篩選" : "進階篩選", icon: "line.3.horizontal.decrease", selected: expanded) { expanded.toggle() }
                Spacer(minLength: 0)
                Picker("資料來源", selection: $filters.source) { Text("我的實測").tag("local"); Text("供應商回報").tag("provider"); Text("外部參考").tag("external") }.labelsHidden().frame(width: 120)
                Picker("排序", selection: $filters.sort) { ForEach(["smart", "name", "recent", "new", "strength", "tps", "ttft", "latency", "quota", "price"], id: \.self) { Text(["smart":"綜合排序", "name":"依名稱", "recent":"最近使用", "new":"最近加入", "strength":"模型強度", "tps":"生成速度", "ttft":"首字延遲", "latency":"總延遲", "quota":"可用額度", "price":"輸入價格"][$0]!).tag($0) } }.labelsHidden().frame(width: 130)
            }.controlSize(.small)
    }
    private func filterTitle(_ value: String) -> String {
        ["CHAT_REASONING":"對話與推理", "CODING":"程式碼", "IMAGE":"圖片", "AUDIO":"音訊", "EMBEDDING":"向量", "RERANK":"重新排序", "SAFETY":"安全", "OTHER":"其他", "VISION":"圖片理解", "FILES":"檔案理解", "AUDIO_INPUT":"音訊輸入", "AUDIO_OUTPUT":"音訊輸出", "IMAGE_GENERATION":"圖片生成", "IMAGE_EDITING":"圖片編輯", "REASONING":"推理", "TOOL_CALLING":"工具呼叫", "FUNCTION_CALLING":"函式呼叫", "STRUCTURED_OUTPUT":"結構化輸出", "JSON_MODE":"JSON 模式", "WEB_SEARCH":"網路搜尋", "COMPUTER_USE":"電腦操作", "LONG_CONTEXT":"長上下文", "TEXT_INPUT":"文字輸入", "TEXT_OUTPUT":"文字輸出", "IMAGE_INPUT":"圖片輸入", "IMAGE_OUTPUT":"圖片輸出", "FILE_INPUT":"檔案輸入", "FREE_NOW":"目前免費", "PAID":"付費", "PARTIALLY_FREE":"部分免費", "UNKNOWN":"尚未確認"][value] ?? value
    }
    private func keyLabel(_ key: JSONValue) -> String { [store.providerName(key["provider_id"].string), key["account_id"].string, key["label"].string].joined(separator: " · ") }
    private func choice(_ title: String, value: Binding<String>, options: [String]) -> some View {
        Picker(title, selection: value) { Text("全部").tag(""); ForEach(options, id: \.self) { Text(filterTitle($0)).tag($0) } }
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
