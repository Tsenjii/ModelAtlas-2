import AtlasCore
import SwiftUI

private func siteDate(_ milliseconds: Double) -> String {
    Date(timeIntervalSince1970: milliseconds / 1000).formatted(date: .abbreviated, time: .shortened)
}
private func siteNumber(_ value: JSONValue, suffix: String = "") -> String {
    guard let number = value.numeric, number.isFinite else { return "未提供" }
    return String(format: "%.2f", number) + suffix
}

@MainActor struct ProviderSiteView: View {
    @ObservedObject var controller: ProviderSiteController
    let api: AtlasClient?
    let origin: String
    private let operations = ["status", "notice", "summary", "pricing"]
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                VStack(alignment: .leading, spacing: 5) {
                    Text("站方資訊").font(.title3.weight(.semibold))
                    Text("直接讀取這個站點公開的公告、價格與效能。").font(.callout).foregroundStyle(.secondary)
                }
                Spacer()
                Button { refresh(operations) } label: { Label("重新整理", systemImage: "arrow.clockwise") }.disabled(!controller.loading.isEmpty || api == nil)
            }
            statusCard
            noticeCard
            summaryCard
            pricingCard
            if controller.didLoad && controller.loading.isEmpty { AtlasLoadedMarker(name: "provider-site-loaded").frame(height: 0) }
        }.task(id: origin) { controller.setOrigin(origin); if let api { controller.refreshIfNeeded(using: api, operations: operations) } }
        .onDisappear { controller.cancel() }
    }
    private func refresh(_ names: [String]) { if let api { controller.refresh(using: api, operations: names) } }
    private var statusCard: some View {
        AtlasCard {
            HStack {
                Label("站點概況", systemImage: "network").font(.headline)
                Spacer()
                if controller.values["status"] != nil { Label("已讀取", systemImage: "checkmark.circle").font(.caption).foregroundStyle(.secondary) }
            }
            ProviderReadState(controller: controller, operation: "status", retry: { refresh(["status"]) })
            if let snapshot = controller.values["status"] {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(snapshot.facts["system_name"].string.isEmpty ? "站點公開資料" : snapshot.facts["system_name"].string).font(.body.weight(.medium))
                        Text("版本 " + snapshot.raw["version"].display).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if snapshot.facts["checkin_enabled"].boolean { Label("提供每日簽到", systemImage: "calendar").font(.callout).foregroundStyle(.secondary) }
                }
                ProviderSiteEvidence(snapshot: snapshot)
            }
        }
    }
    private var noticeCard: some View {
        AtlasCard {
            Label("站方公告", systemImage: "megaphone").font(.headline)
            ProviderReadState(controller: controller, operation: "notice", retry: { refresh(["notice"]) })
            if let snapshot = controller.values["notice"] {
                Text(snapshot.facts["text"].string.isEmpty ? "站點目前沒有公開公告。" : snapshot.facts["text"].string).font(.callout).lineSpacing(4).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                ProviderSiteEvidence(snapshot: snapshot)
            }
        }
    }
    private var summaryCard: some View {
        AtlasCard {
            Label("站方效能概覽", systemImage: "chart.xyaxis.line").font(.headline)
            ProviderReadState(controller: controller, operation: "summary", retry: { refresh(["summary"]) })
            if let snapshot = controller.values["summary"] {
                Text("近 7 天 · 站方統計，包含其他使用者的請求。").font(.caption).foregroundStyle(.secondary)
                SiteMetricValues(facts: snapshot.facts["summary"])
                DisclosureGroup("各模型效能（\(snapshot.facts["models"].array.count) 個）") {
                    VStack(spacing: 12) {
                        ForEach(snapshot.facts["models"].array, id: \.self) { model in
                            HStack { Text(model["model_name"].display).lineLimit(1); Spacer(); Text(siteNumber(model["success_rate"], suffix: "%")); Text(siteNumber(model["avg_tps"], suffix: " tokens/s")).frame(width: 130, alignment: .trailing) }.font(.caption.monospacedDigit())
                            Divider()
                        }
                    }.padding(.top, 10)
                }
                ProviderSiteEvidence(snapshot: snapshot)
            }
        }
    }
    private var pricingCard: some View {
        AtlasCard {
            Label("模型與價格", systemImage: "tag").font(.headline)
            ProviderReadState(controller: controller, operation: "pricing", retry: { refresh(["pricing"]) })
            if let snapshot = controller.values["pricing"] {
                Text("已讀取 \(snapshot.facts["items"].array.count) 個模型的站方計費規則。").font(.callout)
                if !snapshot.facts["price_mapping_available"].boolean { Text("此版本尚未驗證金額換算，先保留站方倍率與公式；不將未知價格標成免費。").font(.caption).foregroundStyle(.secondary) }
                DisclosureGroup("查看站方計費規則") {
                    VStack(alignment: .leading, spacing: 16) {
                        ForEach(snapshot.facts["items"].array, id: \.self) { item in
                            VStack(alignment: .leading, spacing: 5) {
                                Text(item["model_name"].display).font(.subheadline.weight(.medium))
                                Text(item["enable_groups"].array.map(\.string).joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                                if !item["billing_expr"].string.isEmpty { Text(item["billing_expr"].string).font(.caption.monospaced()).textSelection(.enabled) }
                                else { Text("模型倍率 " + item["model_ratio"].display + " · 輸出倍率 " + item["completion_ratio"].display).font(.caption).foregroundStyle(.secondary) }
                            }
                            Divider()
                        }
                    }.padding(.top, 10)
                }
                ProviderSiteEvidence(snapshot: snapshot)
            }
        }
    }
}

@MainActor struct ProviderReadState: View {
    @ObservedObject var controller: ProviderSiteController
    let operation: String
    let retry: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if controller.loading.contains(operation) {
                ProgressView("正在從站點讀取…").controlSize(.small)
                if controller.values[operation] != nil { Text("更新中，下方保留上次成功讀取的資料。").font(.caption).foregroundStyle(.secondary) }
            }
            if let problem = controller.problems[operation] {
                HStack(alignment: .top, spacing: 14) {
                    Label(problem, systemImage: "exclamationmark.circle").font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    Button("重試", action: retry).disabled(!controller.loading.isEmpty)
                }
                if controller.values[operation] != nil { Text("下方顯示上次成功讀取的資料。").font(.caption).foregroundStyle(.secondary) }
            }
        }
    }
}

struct ProviderSiteEvidence: View {
    let snapshot: ProviderSiteSnapshot
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("站方公開資料 · " + siteDate(snapshot.at)).font(.caption).foregroundStyle(.secondary)
            if let url = URL(string: snapshot.facts["origin"].string), let host = url.host { Text(host).font(.caption).foregroundStyle(.secondary).textSelection(.enabled) }
            DisclosureGroup("來源與原始資料") { JSONTree(value: snapshot.raw).padding(.top, 8) }.font(.caption)
        }
    }
}

struct SiteMetricValues: View {
    let facts: JSONValue
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            InsightValue(title: "站方成功率", value: siteNumber(facts["success_rate"], suffix: "%"))
            InsightValue(title: "首 Token 延遲", value: siteNumber(facts["avg_ttft_ms"], suffix: " ms"))
            InsightValue(title: "生成速度", value: siteNumber(facts["avg_tps"], suffix: " t/s"))
            InsightValue(title: "總延遲", value: siteNumber(facts["avg_latency_ms"], suffix: " ms"))
        }
    }
}

@MainActor struct ProviderPerformanceView: View {
    @ObservedObject var controller: ProviderSiteController
    let api: AtlasClient?
    let selectedGroup: String
    var origin: String? = nil
    @State private var group = ""
    @State private var metric = "avg_tps"
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            AtlasCard {
                HStack {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("站方效能").font(.headline)
                        Text("此站點提供的模型統計，包含其他使用者；與本機實測分開。").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button { refresh() } label: { Image(systemName: "arrow.clockwise") }.buttonStyle(.borderless).help("重新讀取站方效能").disabled(!controller.loading.isEmpty)
                }
                Picker("站方時間範圍", selection: Binding(get: { controller.requestedHours }, set: { hours in if let api { controller.refresh(using: api, operations: ["performance"], hours: hours) } })) { Text("1 天").tag(24); Text("7 天").tag(168); Text("30 天").tag(720) }.pickerStyle(.segmented)
                ProviderReadState(controller: controller, operation: "performance", retry: refresh)
                if let snapshot = controller.values["performance"] { performance(snapshot) }
                else if controller.didLoad && controller.loading.isEmpty && controller.problems["performance"] == nil { Text("站點尚未提供這個模型的效能資料。").foregroundStyle(.secondary) }
            }
        }.task(id: origin) {
            if let origin { controller.setOrigin(origin) }
            group = selectedGroup
            if let api { controller.refreshIfNeeded(using: api, operations: ["performance"]) }
        }
        .onDisappear { controller.cancel() }
    }
    private func refresh() { if let api { controller.refresh(using: api, operations: ["performance"], hours: controller.requestedHours) } }
    @ViewBuilder private func performance(_ snapshot: ProviderSiteSnapshot) -> some View {
        Picker("參考群組", selection: $group) {
            Text("全部群組 · 站方整體統計").tag("")
            if !group.isEmpty && !snapshot.groups.contains(where: { $0["group"].string == group }) { Text(group + " · 站方未提供").tag(group) }
            ForEach(snapshot.groups, id: \.self) { row in Text(row["group"].display).tag(row["group"].string) }
        }
        if selectedGroup.isEmpty { Text("這把 Key 尚未指定群組；目前顯示你選擇的站方參考範圍。").font(.caption).foregroundStyle(.secondary) }
        else if group != selectedGroup { Text("目前參考範圍與這把 Key 的群組（\(selectedGroup)）不同。").font(.caption).foregroundStyle(.secondary) }
        if let facts = snapshot.metrics(group: group) {
            SiteMetricValues(facts: facts)
            Text(facts["request_count"] == .null ? "站點未提供樣本數。" : "站方樣本數：" + facts["request_count"].display).font(.caption).foregroundStyle(.secondary)
        } else { Text("站點未提供這個群組的統計，沒有借用其他群組的數值。").font(.callout).foregroundStyle(.secondary) }
        if let start = snapshot.facts["window_start"].numeric, let end = snapshot.facts["window_end"].numeric { Text(siteDate(start * 1000) + " — " + siteDate(end * 1000)).font(.caption).foregroundStyle(.secondary) }
        Picker("趨勢指標", selection: $metric) { Text("生成速度").tag("avg_tps"); Text("首 Token 延遲").tag("avg_ttft_ms"); Text("總延遲").tag("avg_latency_ms"); Text("成功率").tag("success_rate") }.pickerStyle(.segmented)
        if let chart = snapshot.chart(metric: metric, group: group) { TrendChartView(data: chart, identifier: "provider") }
        else { Text("這份回應沒有可繪製的時間序列或明確單位。").foregroundStyle(.secondary).frame(maxWidth: .infinity, minHeight: 120) }
        ProviderSiteEvidence(snapshot: snapshot)
        if controller.loading.isEmpty { AtlasLoadedMarker(name: "provider-performance-loaded").frame(height: 0) }
    }
}
