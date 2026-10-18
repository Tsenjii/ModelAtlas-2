import AtlasCore
import SwiftUI

enum IntelligenceLabels {
    static func source(_ value: String) -> String {
        if value.hasPrefix("DECLARATIVE_PROVIDER_META:") { return "供應商連接器" }
        return ["USER":"手動紀錄", "PROVIDER_REPORTED":"供應商回報", "MY_KEY_LOCAL":"我的 Key 實測", "EXTERNAL":"外部參考", "NEW_API":"站方 API"][value] ?? (value.isEmpty ? "來源未提供" : value)
    }
    static func scope(_ value: String) -> String {
        ["MODEL_KEY_GROUP":"模型連線", "MODEL":"模型", "CREDENTIAL":"金鑰", "GROUP":"群組", "ACCOUNT":"帳戶", "PROVIDER":"供應商"][value] ?? (value.isEmpty ? "原始觀測範圍" : value)
    }
    static func kind(_ value: String) -> String {
        if value.hasPrefix("MANUAL_QUOTA:") {
            let components = value.split(separator: ":", omittingEmptySubsequences: false)
            return "手動額度 · " + scope(components.count > 1 ? String(components[1]) : "")
        }
        return ["MANUAL_PRICE":"價格紀錄", "MANUAL_QUOTA":"額度紀錄", "BALANCE":"餘額快照", "PRICE":"價格觀測", "QUOTA":"額度觀測", "CATALOG":"模型目錄同步", "PERFORMANCE":"效能觀測", "AVAILABILITY":"可用性觀測", "CAPABILITY":"模型能力"][value] ?? value
    }
    static func status(_ value: String) -> String {
        ["SUCCESS":"成功", "FAILED":"失敗", "SNAPSHOT":"已保存", "CANCELLED":"已取消", "INTERRUPTED":"已中斷", "RUNNING":"進行中", "TIMEOUT":"逾時", "NETWORK":"網路錯誤", "RATE_LIMIT":"限流", "UNAUTHORIZED":"認證失敗", "SERVER_ERROR":"站方錯誤"][value] ?? value
    }
    static func price(_ value: String) -> String {
        ["FREE_NOW":"目前免費", "PAID":"付費", "PARTIALLY_FREE":"部分免費", "UNKNOWN":"尚未確認"][value] ?? "尚未確認"
    }
}

private func insightDate(_ milliseconds: Double) -> String {
    guard milliseconds > 0 else { return "時間未提供" }
    return Date(timeIntervalSince1970: milliseconds / 1000).formatted(date: .abbreviated, time: .shortened)
}
private func metricText(_ value: JSONValue, unit: String = "") -> String {
    guard let n = ModelIntelligence.number(value) else { return "尚無資料" }
    return String(format: n >= 100 ? "%.0f" : "%.2f", n) + unit
}

struct InsightValue: View {
    let title: String
    let value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.system(size: 20, weight: .semibold)).monospacedDigit().textSelection(.enabled)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct ObservationEvidence: View {
    let observation: IntelligenceObservation
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(IntelligenceLabels.source(observation.source) + " · " + insightDate(observation.at)).font(.caption).foregroundStyle(.secondary)
            DisclosureGroup("來源與適用範圍") {
                VStack(alignment: .leading, spacing: 10) {
                    Text(observation.scopeDescription).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
                    JSONTree(value: observation.raw)
                }.padding(.top, 8)
            }.font(.caption)
        }
    }
}

struct ModelPricingPanel: View {
    let intelligence: ModelIntelligence
    let priceState: String
    @State private var series = ""
    @State private var leg = "input"
    private var prices: [IntelligenceObservation] { intelligence.lastGood.filter { !$0.facts["price"].fields.isEmpty || !$0.facts["effective_charge"].fields.isEmpty } }
    private var tracks: [IntelligencePriceTrack] { intelligence.priceTracks }
    private var track: IntelligencePriceTrack? { tracks.first { $0.id == series } ?? tracks.first { $0.legKey == "price" && $0.observations.count > 1 } ?? tracks.first }
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            AtlasCard {
                HStack { Text("這個連線的價格狀態").font(.headline); Spacer(); SmallBadge(text: IntelligenceLabels.price(priceState)) }
                Text("FREE NOW 與定期免費資格分開判定；以下保留最後成功觀測的時間與來源。").font(.callout).foregroundStyle(.secondary)
            }
            if prices.isEmpty { AtlasCard { Text("尚無價格觀測").font(.headline); Text("加入模型後可同步資料，或在設定中記錄標價與已確認的實際扣款。").foregroundStyle(.secondary) } }
            ForEach(prices) { row in
                AtlasCard {
                    HStack { Text(row.kind == "MANUAL_PRICE" ? "人工價格紀錄" : "價格觀測").font(.headline); Spacer(); Text(insightDate(row.at)).font(.caption).foregroundStyle(.secondary) }
                    if !row.facts["price"].fields.isEmpty { PriceLegValues(title: "標價", legs: row.facts["price"]) }
                    if !row.facts["effective_charge"].fields.isEmpty { PriceLegValues(title: "已確認實際扣款", legs: row.facts["effective_charge"]) }
                    else { Text("實際扣款尚未確認").font(.caption).foregroundStyle(.secondary) }
                    ObservationEvidence(observation: row)
                }
            }
            if let track {
                AtlasCard {
                    Text("價格歷史").font(.headline)
                    Picker("來源、計價種類與單位", selection: Binding(get: { track.id }, set: { series = $0 })) { ForEach(tracks) { Text($0.label).tag($0.id) } }
                    Picker("價格項目", selection: $leg) { Text("輸入").tag("input"); Text("輸出").tag("output"); Text("固定請求").tag("fixed"); Text("Cache").tag("cache") }.pickerStyle(.segmented)
                    InsightTrend(points: track.observations.map { InsightPoint(id: $0.id, at: $0.at, value: ModelIntelligence.number($0.facts[track.legKey][leg])) }, unit: track.observations.first?.facts[track.legKey]["unit"].string ?? "")
                    Text("依觀測時間顯示；缺值保留空段。不同來源、單位與憑證版本各自呈現。").font(.caption).foregroundStyle(.secondary)
                    DisclosureGroup("精確價格資料（\(track.observations.count) 筆）") {
                        ForEach(track.observations) { row in
                            HStack { Text(insightDate(row.at)); Spacer(); Text(ModelIntelligence.amount(row.facts[track.legKey][leg])) }.font(.caption.monospacedDigit()).textSelection(.enabled)
                        }
                    }
                }
            }
        }
    }
}

private struct PriceLegValues: View {
    let title: String
    let legs: JSONValue
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack { Text(title).font(.subheadline.weight(.semibold)); Spacer(); Text(legs["unit"].display).font(.caption).foregroundStyle(.secondary) }
            HStack(alignment: .top, spacing: 12) {
                InsightValue(title: "輸入", value: ModelIntelligence.amount(legs["input"]))
                InsightValue(title: "輸出", value: ModelIntelligence.amount(legs["output"]))
                InsightValue(title: "Cache", value: ModelIntelligence.amount(legs["cache"]))
                InsightValue(title: "固定請求", value: legs["fixed_absent"].boolean && legs["fixed"] == .null ? "無固定費用" : ModelIntelligence.amount(legs["fixed"]))
            }
        }
    }
}

struct ModelQuotaPanel: View {
    let intelligence: ModelIntelligence
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("額度池依自己的範圍保存，不合併成總餘額。未提供單位的站方額度，不換算成金額或 Token。").font(.callout).foregroundStyle(.secondary)
            if intelligence.pools.isEmpty { AtlasCard { Text("尚無額度資料").font(.headline); Text("沒有數值不代表額度為零，也不代表不可使用。").foregroundStyle(.secondary) } }
            ForEach(intelligence.pools) { row in
                AtlasCard {
                    HStack { Text(row.facts["scope_kind"].string.isEmpty ? "站方額度快照" : IntelligenceLabels.scope(row.facts["scope_kind"].string) + "額度池").font(.headline); Spacer(); Text(IntelligenceLabels.status(row.status)).font(.caption).foregroundStyle(.secondary) }
                    let values = row.facts["values"].fields.isEmpty ? row.facts : row.facts["values"]
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 170), alignment: .leading)], alignment: .leading, spacing: 18) {
                        ForEach(ModelIntelligence.quotaFields.map(\.0), id: \.self) { key in
                            if values.fields[key] != nil {
                                InsightValue(title: ModelIntelligence.quotaFields.first { $0.0 == key }!.1, value: ModelIntelligence.amount(values[key]))
                            }
                        }
                    }
                    HStack {
                        Text("單位：" + (row.facts["unit"].string.isEmpty ? "未提供（依各欄位原始語意）" : row.facts["unit"].string))
                        Spacer()
                        if values["unlimited_quota"].boolean { Text("站方宣告無上限") }
                    }.font(.caption).foregroundStyle(.secondary)
                    if let reset = ModelIntelligence.number(values["reset_at"]), reset > 0 { Text("重設原始值：" + values["reset_at"].display).font(.caption) }
                    if !row.facts["note"].string.isEmpty { Text(row.facts["note"].string).font(.callout) }
                    ObservationEvidence(observation: row)
                }
            }
        }
    }
}

struct ModelPerformancePanel: View {
    let intelligence: ModelIntelligence
    @State private var source = "local"
    @State private var metric = "ttft"
    private var local: JSONValue { intelligence.local }
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Picker("資料來源", selection: $source) { Text("我的 Key").tag("local"); Text("供應商回報").tag("provider"); Text("外部參考").tag("external") }.pickerStyle(.segmented)
            if source == "local" { localContent }
            if source == "provider" {
                Text("站方範圍的觀測，可能包含其他帳戶與群組；不作為我的 Key 上線率。").font(.callout).foregroundStyle(.secondary)
                if providerRows.isEmpty { Text("尚無站方效能回報").foregroundStyle(.secondary) }
                ForEach(providerRows.prefix(20)) { row in
                    AtlasCard {
                        Text("站方效能 · " + row.status).font(.headline)
                        if row.facts["groups"].array.isEmpty { ProviderMetricValues(facts: row.facts) }
                        else { ForEach(row.facts["groups"].array, id: \.self) { group in VStack(alignment: .leading, spacing: 10) { Text("群組 " + group["group"].display).font(.subheadline.weight(.semibold)); ProviderMetricValues(facts: group) } } }
                        ObservationEvidence(observation: row)
                    }
                }
            }
            if source == "external" {
                Text("外部參考獨立保存；單位未確認時保留原始資料，不套用本機指標。").font(.callout).foregroundStyle(.secondary)
                if intelligence.external.isEmpty { Text("尚無外部參考").foregroundStyle(.secondary) }
                ForEach(intelligence.external, id: \.self) { row in
                    AtlasCard {
                        Text("外部觀測 · " + insightDate(ModelIntelligence.number(row["observed_at"]) ?? 0)).font(.headline)
                        if row["units"].string == "TPS" { InsightValue(title: "平均 TPS", value: metricText(row["average_tokens_per_second"])) }
                        if row["units"].string == "MS" { InsightValue(title: "平均首 Token 延遲", value: metricText(row["average_first_token_latency"], unit: " ms")) }
                        DisclosureGroup("來源與完整外部資料") { JSONTree(value: row) }
                    }
                }
            }
        }
    }
    private var providerRows: [IntelligenceObservation] { intelligence.provider.filter { $0.kind.contains("PERFORMANCE") || $0.kind.hasPrefix("/api/perf-metrics") } }
    private var localContent: some View {
        VStack(alignment: .leading, spacing: 18) {
            AtlasCard {
                HStack { Text("我的 Key · 本機實測").font(.headline); Spacer(); Text(local["samples"].display + " 筆有效樣本").font(.caption).foregroundStyle(.secondary) }
                Text(insightDate(ModelIntelligence.number(local["window_start"]) ?? 0) + " — " + insightDate(ModelIntelligence.number(local["window_end"]) ?? 0)).font(.caption).foregroundStyle(.secondary)
                HStack {
                    InsightValue(title: "我的上線率", value: successText)
                    InsightValue(title: "平均 TTFT", value: metricText(local["ttft"]["mean"], unit: " ms"))
                    InsightValue(title: "平均 TPS", value: metricText(local["tps"]["mean"]))
                    InsightValue(title: "平均延遲", value: metricText(local["latency"]["mean"], unit: " ms"))
                }
                Text("取消 \(local["cancelled"].display) 筆 · 中斷 \(local["interrupted"].display) 筆，均不計入上線率。非串流 TTFT 不適用。").font(.caption).foregroundStyle(.secondary)
            }
            AtlasCard {
                HStack { Text("請求趨勢").font(.headline); Spacer(); Text("最近 \(intelligence.samples.count) / \(local["history_total"].display) 筆").font(.caption).foregroundStyle(.secondary) }
                Picker("趨勢指標", selection: $metric) { Text("TTFT · ms").tag("ttft"); Text("TPS").tag("tps"); Text("延遲 · ms").tag("latency"); Text("成功／失敗").tag("success") }.pickerStyle(.segmented)
                InsightTrend(points: intelligence.samples.map { InsightPoint(id: $0.id, at: $0.at, value: $0.value(metric)) }, unit: metric == "ttft" || metric == "latency" ? "ms" : metric == "tps" ? "tokens/s" : "成功 1／失敗 0")
                Text("圖表最多顯示最近 \(local["history_limit"].display) 筆；上方統計使用選定時間窗內全部有效樣本，完整請求仍保留在歷史。").font(.caption).foregroundStyle(.secondary)
                DisclosureGroup("查看每筆數值") { ForEach(intelligence.samples) { sample in
                    HStack { Text(insightDate(sample.at)); Text(sample.outcome); Spacer(); Text(sample.value(metric).map { String(format: "%.2f", $0) } ?? "不適用／未提供") }.font(.caption.monospacedDigit()).textSelection(.enabled)
                } }
            }
            AtlasCard {
                Text("分布與錯誤").font(.headline)
                ForEach(["ttft", "tps", "latency"], id: \.self) { name in
                    HStack { Text(name == "latency" ? "延遲（ms）" : name == "ttft" ? "TTFT（ms）" : "TPS").frame(width: 100, alignment: .leading); ForEach(["p50", "p90", "p95"], id: \.self) { key in Text(key + " " + metricText(local[name][key])).frame(maxWidth: .infinity, alignment: .leading) } }.font(.caption.monospacedDigit())
                }
                if local["failures"].fields.isEmpty { Text("此時間窗尚無失敗分類").font(.caption).foregroundStyle(.secondary) }
                ForEach(local["failures"].fields.keys.sorted(), id: \.self) { key in HStack { Text(key); Spacer(); Text(local["failures"][key].display) }.font(.caption) }
                Divider()
                Text("目前觀測 · 最近 60 秒（非站方限額）").font(.subheadline.weight(.semibold))
                HStack { InsightValue(title: "RPM", value: metricText(local["observed_rpm"])); InsightValue(title: "輸入 TPM", value: metricText(local["observed_input_tpm"])); InsightValue(title: "輸出 TPM", value: metricText(local["observed_output_tpm"])); InsightValue(title: "總 TPM", value: metricText(local["observed_total_tpm"])) }
            }
        }
    }
    private var successText: String {
        guard let count = ModelIntelligence.number(local["samples"]), count > 0, let rate = ModelIntelligence.number(local["success_rate"]), rate <= 1 else { return "尚無資料" }
        return String(format: "%.1f%%", rate * 100)
    }
}

private struct ProviderMetricValues: View {
    let facts: JSONValue
    private func metric(_ key: String, alternate: String) -> JSONValue {
        if facts[key]["mean"] != .null { return facts[key]["mean"] }
        return facts[key] == .null ? facts[alternate] : facts[key]
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack { InsightValue(title: "TTFT", value: metricText(metric("ttft_ms", alternate: "avg_ttft_ms"), unit: " ms")); InsightValue(title: "TPS", value: metricText(metric("tps", alternate: "avg_tps"))); InsightValue(title: "延遲", value: metricText(metric("latency_ms", alternate: "avg_latency_ms"), unit: " ms")) }
            Text("樣本數：" + (facts["sample_count"] == .null ? facts["request_count"].display : facts["sample_count"].display)).font(.caption).foregroundStyle(.secondary)
        }
    }
}

struct ModelObservationHistory: View {
    let intelligence: ModelIntelligence
    @State private var source = ""
    @State private var kind = ""
    private var rows: [IntelligenceObservation] { intelligence.history.filter { (source.isEmpty || $0.source == source) && (kind.isEmpty || $0.kind == kind) } }
    var body: some View {
        AtlasCard {
            HStack { Text("價格、額度與同步觀測").font(.headline); Spacer(); Text("\(rows.count) 筆").font(.caption).foregroundStyle(.secondary) }
            HStack {
                Picker("來源", selection: $source) { Text("全部來源").tag(""); ForEach(Array(Set(intelligence.history.map(\.source))).sorted(), id: \.self) { Text(IntelligenceLabels.source($0)).tag($0) } }
                Picker("類型", selection: $kind) { Text("全部類型").tag(""); ForEach(Array(Set(intelligence.history.map(\.kind))).sorted(), id: \.self) { Text(IntelligenceLabels.kind($0)).tag($0) } }
            }
            Text("顯示引擎最近保留的最多 200 筆觀測；失敗不取代最後成功資料。原始紀錄持續保存。").font(.caption).foregroundStyle(.secondary)
            if rows.isEmpty { Text("尚無符合的觀測").foregroundStyle(.secondary) }
            ForEach(rows) { row in
                VStack(alignment: .leading, spacing: 8) {
                    HStack { Image(systemName: row.status == "SUCCESS" ? "checkmark.circle" : "exclamationmark.circle").foregroundStyle(row.status == "SUCCESS" ? Color.green : Color.secondary); Text(IntelligenceLabels.kind(row.kind)).font(.subheadline.weight(.semibold)); Spacer(); Text(IntelligenceLabels.status(row.status)).font(.caption).foregroundStyle(.secondary) }
                    ObservationEvidence(observation: row)
                    if !row.facts["reason"].string.isEmpty { Text(row.facts["reason"].string).font(.caption).foregroundStyle(.secondary) }
                }.padding(.vertical, 8)
                Divider()
            }
        }
    }
}

struct InsightPoint: Identifiable { let id: String; let at: Double; let value: Double? }

/// Native macOS 12 Path rendering. Missing values break the line; observations
/// are placed on their actual time axis rather than evenly spaced by index.
struct InsightTrend: View {
    let points: [InsightPoint]
    let unit: String
    private var values: [Double] { points.compactMap(\.value) }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if values.isEmpty { Text("這個指標尚無可繪製數值").foregroundStyle(.secondary).frame(maxWidth: .infinity, minHeight: 110) }
            else {
                HStack { Text(String(format: "最高 %.3g", values.max()!)); Spacer(); Text(unit) }.font(.caption2).foregroundStyle(.secondary)
                GeometryReader { geometry in
                    let low = values.min()!, high = values.max()!
                    let start = points.map(\.at).min() ?? 0, end = points.map(\.at).max() ?? 0
                    let verticalRange = max(high - low, max(abs(high) * 0.1, 0.000001))
                    Path { path in
                        for fraction in [0.0, 0.5, 1.0] {
                            let y = 6 + fraction * max(geometry.size.height - 12, 0)
                            path.move(to: CGPoint(x: 6, y: y)); path.addLine(to: CGPoint(x: geometry.size.width - 6, y: y))
                        }
                    }.stroke(Color.primary.opacity(0.09), style: StrokeStyle(lineWidth: 0.5, dash: [3, 4]))
                    Path { path in
                        var continuing = false
                        for point in points {
                            guard let value = point.value else { continuing = false; continue }
                            let x = end > start ? (point.at - start) / (end - start) * max(geometry.size.width - 12, 0) + 6 : geometry.size.width / 2
                            let y = high == low ? geometry.size.height / 2 : (high - value) / verticalRange * max(geometry.size.height - 12, 0) + 6
                            let position = CGPoint(x: x, y: y)
                            if continuing { path.addLine(to: position) } else { path.move(to: position) }
                            continuing = true
                        }
                    }.stroke(atlasAccent, style: StrokeStyle(lineWidth: 1.5, lineJoin: .round))
                    Path { path in
                        for point in points {
                            guard let value = point.value else { continue }
                            let x = end > start ? (point.at - start) / (end - start) * max(geometry.size.width - 12, 0) + 6 : geometry.size.width / 2
                            let y = high == low ? geometry.size.height / 2 : (high - value) / verticalRange * max(geometry.size.height - 12, 0) + 6
                            path.addEllipse(in: CGRect(x: x - 2, y: y - 2, width: 4, height: 4))
                        }
                    }.fill(atlasTextAccent)
                }.frame(height: 110).accessibilityHidden(true)
                HStack { Text(points.first.map { insightDate($0.at) } ?? ""); Spacer(); Text(points.last.map { insightDate($0.at) } ?? "") }.font(.caption2).foregroundStyle(.secondary)
                Text("最低 \(String(format: "%.3g", values.min()!)) · \(values.count) 個有效觀測點").font(.caption2).foregroundStyle(.secondary)
            }
        }.accessibilityElement(children: .combine)
    }
}
