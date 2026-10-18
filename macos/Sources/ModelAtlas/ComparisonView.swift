import AppKit
import AtlasCore
import SwiftUI
import UniformTypeIdentifiers

@MainActor final class ComparisonController: ObservableObject, Identifiable {
    let id = UUID()
    let offers: [ComparisonOffer]
    @Published var prompt = "Reply OK."
    @Published private(set) var report: LiveComparison?
    @Published private(set) var pending = false
    @Published private(set) var problem: String?
    @Published private(set) var completedAt: Date?
    @Published private(set) var comparedPrompt = ""
    @Published private(set) var showingPrevious = false
    private var task: Task<Void, Never>?
    private var revision = UUID()
    init(offers: [ComparisonOffer]) { self.offers = offers }
    var validation: String? { ComparisonOffer.problem(offers, prompt: prompt) }
    func start(using api: AtlasClient) {
        guard !pending else { return }
        let body: JSONValue
        do { body = try ComparisonOffer.request(offers, prompt: prompt) }
        catch { problem = error.localizedDescription; return }
        let submittedPrompt = prompt.isEmpty ? "Reply OK." : prompt
        let token = UUID(); revision = token; pending = true; problem = nil; showingPrevious = report != nil
        task = Task { [weak self] in
            do {
                try Task.checkCancellation()
                let raw = try await api.call("/models/compare", method: "POST", body: body)
                try Task.checkCancellation()
                guard let self, self.revision == token else { return }
                self.report = try LiveComparison(response: raw, scopes: self.offers.map(\.scope))
                self.completedAt = Date(); self.comparedPrompt = submittedPrompt; self.showingPrevious = false; self.pending = false; self.task = nil
            } catch {
                guard let self, self.revision == token else { return }
                self.problem = Task.isCancelled ? "已停止比較。已完成的請求仍保留在歷史。" : error.localizedDescription
                self.pending = false; self.task = nil
            }
        }
    }
    func cancel() {
        guard pending else { return }
        revision = UUID(); task?.cancel(); task = nil; pending = false
        problem = "已停止比較。已完成的請求仍保留在歷史。"
    }
    deinit { task?.cancel() }
}

private struct ComparisonReceipt: Identifiable { let id: String }

struct ComparisonView: View {
    @ObservedObject var store: AtlasStore
    @ObservedObject var controller: ComparisonController
    @Environment(\.dismiss) private var dismiss
    @State private var receipt: ComparisonReceipt?
    @State private var editingPrompt = false
    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .top, spacing: 16) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("連線比較").font(.system(size: 21, weight: .semibold))
                    Text(controller.offers.first?.canonicalID ?? "").font(.system(size: 12)).foregroundStyle(.secondary).textSelection(.enabled)
                }
                Spacer()
                if controller.report != nil { Button("匯出結果…") { export() }.buttonStyle(AtlasActionStyle()) }
                Button("完成") { controller.cancel(); dismiss() }.buttonStyle(AtlasActionStyle()).keyboardShortcut(.cancelAction)
            }.padding(24)
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    if controller.report != nil && !controller.pending {
                        DisclosureGroup("提示詞與重新比較", isExpanded: $editingPrompt) { setup.padding(.top, 12) }.font(.callout)
                    } else { setup }
                    if let problem = controller.problem { Label(problem, systemImage: "exclamationmark.circle").font(.callout).foregroundStyle(.secondary).textSelection(.enabled) }
                    if let report = controller.report {
                        results(report)
                        AtlasLoadedMarker(name: "comparison-results").frame(height: 0)
                    } else if !controller.pending {
                        selectedOffers
                        AtlasLoadedMarker(name: "comparison-ready").frame(height: 0)
                    }
                }.padding(24)
            }
        }.frame(width: 860, height: 760).background(atlasCanvas).atlasModal(store)
            .onDisappear { controller.cancel() }
            .onChange(of: controller.pending) { if !$0 { Task { await store.refresh() } } }
            .sheet(item: $receipt) { ref in RequestReceiptView(store: store, sampleID: ref.id).atlasModal(store) }
    }
    private var setup: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack { Text("相同提示詞，逐一比較").font(.system(size: 13, weight: .semibold)); Spacer(); Text("\(controller.offers.count) 個連線").font(.caption).foregroundStyle(.secondary) }
            TextField("輸入短提示詞；留空使用 Reply OK.", text: $controller.prompt).textFieldStyle(.plain).font(.system(size: 13))
                .padding(12).background(atlasSurface).cornerRadius(8).overlay(RoundedRectangle(cornerRadius: 8).stroke(atlasHairline, lineWidth: 0.5)).disabled(controller.pending)
                .accessibilityIdentifier("comparison-prompt")
            HStack(alignment: .center, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("每個連線各發送一次真實請求，可能消耗額度。").font(.caption).foregroundStyle(.secondary)
                    if let validation = controller.validation { Text(validation).font(.caption).foregroundStyle(.secondary) }
                    if controller.pending { HStack(spacing: 8) { ProgressView().controlSize(.small); Text("正在比較；結果完成後一次顯示。") }.font(.caption) }
                }
                Spacer(minLength: 0)
                if controller.pending { Button("停止比較") { controller.cancel() }.buttonStyle(AtlasActionStyle()) }
                else {
                    Button(controller.report == nil ? "開始比較" : "再次比較") { if let api = store.api { controller.start(using: api) } }
                        .buttonStyle(AtlasActionStyle(prominent: true)).disabled(controller.validation != nil || store.api == nil)
                        .accessibilityIdentifier("comparison-start")
                }
            }
        }
    }
    private var selectedOffers: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("所選連線").font(.system(size: 13, weight: .semibold))
            VStack(spacing: 0) {
                ForEach(controller.offers) { offer in
                    HStack(spacing: 12) {
                        ModelMark(name: offer.title)
                        VStack(alignment: .leading, spacing: 5) { Text(offer.title).font(.system(size: 13, weight: .medium)); Text(offer.context).font(.caption).foregroundStyle(.secondary) }
                        Spacer(); Text(operationTitle(offer.operation)).font(.caption).foregroundStyle(.secondary)
                    }.padding(16)
                    if offer.id != controller.offers.last?.id { Divider().padding(.leading, 62) }
                }
            }.background(atlasSurface).cornerRadius(10).overlay(RoundedRectangle(cornerRadius: 10).stroke(atlasHairline, lineWidth: 0.5))
        }
    }
    @ViewBuilder private func results(_ report: LiveComparison) -> some View {
        HStack {
            Text(controller.pending || controller.showingPrevious ? "上次完成的比較" : "本次比較結果").font(.system(size: 14, weight: .semibold))
            Spacer()
            if let date = controller.completedAt { Text(date.formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(.secondary) }
        }
        Text("每個連線 1 次實測；— 表示未提供。這次結果不代表長期品質排名。").font(.caption).foregroundStyle(.secondary)
        DisclosureGroup("這次使用的提示詞") { Text(controller.comparedPrompt).font(.callout).textSelection(.enabled) }.font(.caption)
        ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: 0) {
                VStack(alignment: .leading, spacing: 0) {
                    Text("比較項目").font(.caption.weight(.medium)).foregroundStyle(.secondary).frame(height: 92, alignment: .bottom).padding(.bottom, 12)
                    ForEach(ComparisonMetric.allCases) { metric in Text(metric.title).font(.system(size: 12)).foregroundStyle(.secondary).frame(height: 36) }
                    Text("請求紀錄").font(.system(size: 12)).foregroundStyle(.secondary).frame(height: 48)
                }.frame(width: 135, alignment: .leading).padding(.leading, 18)
                ForEach(report.items) { item in column(item) }
            }.padding(.vertical, 12)
        }.background(atlasSurface).cornerRadius(10).overlay(RoundedRectangle(cornerRadius: 10).stroke(atlasHairline, lineWidth: 0.5))
        ForEach(report.items) { item in
            DisclosureGroup(offer(item.scope)?.context ?? item.scope.modelID) {
                VStack(alignment: .leading, spacing: 14) {
                    if !item.raw["text"].string.isEmpty { Text(item.raw["text"].string).font(.callout).textSelection(.enabled) }
                    if !item.raw["reasoning"].string.isEmpty { DisclosureGroup("推理內容") { Text(item.raw["reasoning"].string).textSelection(.enabled) } }
                    quota(item)
                    DisclosureGroup("完整請求結果與來源") { JSONTree(value: item.raw) }
                }.padding(.top, 12)
            }.font(.system(size: 12))
        }
    }
    private func column(_ item: LiveComparisonItem) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 6) {
                Text(offer(item.scope)?.title ?? item.scope.modelID).font(.system(size: 14, weight: .semibold)).lineLimit(2)
                Text(offer(item.scope)?.context ?? item.scope.providerID).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(3)
            }.frame(height: 92, alignment: .top).frame(maxWidth: .infinity, alignment: .leading).padding(.bottom, 12)
            ForEach(ComparisonMetric.allCases) { metric in
                HStack { Text(metric.value(item)).font(.system(size: 13, weight: metric == .outcome ? .semibold : .regular)).monospacedDigit().textSelection(.enabled); Spacer(minLength: 0) }
                    .frame(height: 36).overlay(alignment: .top) { Rectangle().fill(atlasHairline.opacity(0.5)).frame(height: 0.5) }
            }
            Button("查看收據") { receipt = ComparisonReceipt(id: item.sampleID) }.buttonStyle(AtlasActionStyle()).disabled(item.sampleID.isEmpty).frame(height: 48)
        }.frame(width: controller.offers.count == 2 ? 290 : 235, alignment: .leading).padding(.horizontal, 18)
    }
    @ViewBuilder private func quota(_ item: LiveComparisonItem) -> some View {
        if item.quota.isEmpty && item.receipt["response_quota"].fields.isEmpty { Text("這次請求沒有可用的額度快照。").foregroundStyle(.secondary) }
        else {
            Text("請求前後的額度快照").font(.subheadline.weight(.semibold))
            Text("各池與單位分開；快照可能未重新向站方查詢，不推算實際扣款。").font(.caption).foregroundStyle(.secondary)
            ForEach(item.quota) { pair in
                let row = pair.after ?? pair.before ?? .null
                DisclosureGroup(IntelligenceLabels.source(row["source"].string) + " · " + IntelligenceLabels.scope(row["facts"]["scope_kind"].string)) {
                    HStack(alignment: .top, spacing: 24) {
                        VStack(alignment: .leading, spacing: 8) { Text("請求前").font(.caption.weight(.medium)); JSONTree(value: pair.before ?? .null) }
                        VStack(alignment: .leading, spacing: 8) { Text("請求後").font(.caption.weight(.medium)); JSONTree(value: pair.after ?? .null) }
                    }.padding(.top, 10)
                }
            }
            if !item.receipt["response_quota"].fields.isEmpty { DisclosureGroup("這次回應的限流資訊") { JSONTree(value: item.receipt["response_quota"]) } }
        }
    }
    private func offer(_ scope: ModelScope) -> ComparisonOffer? { controller.offers.first { $0.scope == scope } }
    private func export() {
        guard let report = controller.report else { return }
        let panel = NSSavePanel(); panel.nameFieldStringValue = "ModelAtlas-Comparison.json"; panel.allowedContentTypes = [.json]
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do { try Data(report.raw.pretty.utf8).write(to: url, options: .atomic) }
        catch { store.problem = "無法儲存比較結果。" }
    }
}

private enum ComparisonMetric: String, CaseIterable, Identifiable {
    case outcome, ttft, tps, latency, input, output, price, actual, estimate, nominal
    var id: String { rawValue }
    var title: String { ["outcome":"請求結果", "ttft":"首 Token · ms", "tps":"TPS · tokens/s", "latency":"總延遲 · ms", "input":"輸入 Token", "output":"輸出 Token", "price":"當時價格狀態", "actual":"站方回報扣款", "estimate":"有效價格估算", "nominal":"標價估算"][rawValue]! }
    func value(_ item: LiveComparisonItem) -> String {
        switch self {
        case .outcome: return IntelligenceLabels.status(item.outcome) + (item.raw["http_status"] == .null ? "" : " · " + item.raw["http_status"].display)
        case .price: return IntelligenceLabels.price(item.receipt["price_state"].string)
        case .actual: return item.reportedCost
        case .estimate: return item.cost("estimated")
        case .nominal: return item.cost("nominal_estimated")
        default:
            let key = ["ttft":"ttft_ms", "tps":"tps", "latency":"latency_ms", "input":"input_tokens", "output":"output_tokens"][rawValue]!
            guard let n = item.metric(key) else { return self == .ttft && item.raw["details"]["streaming"] == .bool(false) ? "不適用（非串流）" : "—" }
            return self == .tps ? String(format: "%.1f", n) : String(format: "%.0f", n)
        }
    }
}
