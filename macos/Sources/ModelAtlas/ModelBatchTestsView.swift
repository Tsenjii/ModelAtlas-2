import AppKit
import AtlasCore
import SwiftUI
import UniformTypeIdentifiers

@MainActor final class ModelBatchTestsController: ObservableObject, Identifiable {
    let id = UUID()
    let plan: ManualModelTestPlan
    @Published private(set) var report: ManualModelTestReport
    @Published private(set) var pending = false
    @Published private(set) var started = false
    @Published private(set) var stopping = false
    @Published private(set) var problem: String?
    private var task: Task<Void, Never>?
    init(offers: [ComparisonOffer]) throws {
        plan = try ManualModelTestPlan(offers: offers)
        report = ManualModelTestReport(plan: plan)
    }
    func start(using api: AtlasClient) {
        guard !started, !pending else { return }
        started = true; pending = true
        task = Task { [weak self] in
            guard let self else { return }
            defer { self.pending = false; self.stopping = false; self.task = nil }
            var requestInFlight = false
            do {
                while self.report.canContinue {
                    try Task.checkCancellation()
                    let scope = self.plan.offers[self.report.items.count].scope
                    requestInFlight = true
                    let response = try await api.call("/models/test", method: "POST", body: scope.json)
                    // A returned receipt is retained even when cancellation arrives
                    // immediately after the engine has committed it.
                    try self.report.append(response)
                    requestInFlight = false
                    try Task.checkCancellation()
                }
            } catch {
                self.report.stop(cancelled: Task.isCancelled, requestUnconfirmed: requestInFlight)
                self.problem = Task.isCancelled ? "已停止測試。已發送的請求仍保留在實測歷史。" : error.localizedDescription
            }
        }
    }
    func cancel() {
        guard pending, !stopping else { return }
        stopping = true; task?.cancel()
    }
    deinit { task?.cancel() }
}

struct ModelBatchTestsView: View {
    @ObservedObject var store: AtlasStore
    @ObservedObject var controller: ModelBatchTestsController
    @Environment(\.dismiss) private var dismiss
    @State private var receipt: BatchReceipt?
    private struct BatchReceipt: Identifiable { let id: String }
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("批次模型測試").font(.title2.weight(.semibold))
                Spacer()
                Button("完成") { controller.cancel(); dismiss() }.keyboardShortcut(.cancelAction)
            }.padding(24)
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text("依序測試選取的精確連線，結果保留在各模型的實測歷史。").foregroundStyle(.secondary)
                    ProgressView(value: Double(controller.report.items.count), total: Double(controller.plan.offers.count))
                        .accessibilityIdentifier("model-batch-progress")
                    Text(status + " · \(controller.report.items.count) / \(controller.plan.offers.count)")
                    if let problem = controller.problem { Text(problem).foregroundStyle(.secondary) }
                    ForEach(Array(controller.plan.offers.enumerated()), id: \.element.id) { index, offer in
                        AtlasCard {
                            Text(offer.title).font(.headline)
                            Text(offer.context).font(.caption).foregroundStyle(.secondary)
                            if index < controller.report.items.count {
                                let item = controller.report.items[index]
                                Text(item.outcome == "SUCCESS" ? "可用" : item.outcome)
                                Text("首字 " + metric(item.metric("ttft_ms"), unit: "ms") + " · 速率 " + metric(item.metric("tps"), unit: "tokens/s") + " · 延遲 " + metric(item.metric("latency_ms"), unit: "ms")).font(.caption).foregroundStyle(.secondary)
                                if !item.sampleID.isEmpty { Button("查看這次請求") { receipt = BatchReceipt(id: item.sampleID) } }
                            } else { Text(controller.pending && index == controller.report.items.count ? "測試中…" : "尚未取得結果").foregroundStyle(.secondary) }
                        }
                    }
                }.padding(24)
            }
            Divider()
            HStack {
                if controller.started { Button("匯出結果…") { export() } }
                Spacer()
                if controller.pending { Button(controller.stopping ? "正在停止…" : "停止測試") { controller.cancel() }.disabled(controller.stopping) }
                else if !controller.started { Button("開始測試 \(controller.plan.offers.count) 個模型") { if let api = store.api { controller.start(using: api) } }.buttonStyle(AtlasActionStyle(prominent: true)).disabled(store.api == nil).accessibilityIdentifier("model-batch-start") }
            }.padding(24)
        }.frame(width: 760, height: 700).background(atlasCanvas).atlasModal(store)
            .background(AtlasLoadedMarker(name: controller.started ? "model-batch-results" : "model-batch-ready"))
            .onDisappear { controller.cancel(); Task { await store.refresh() } }
            .sheet(item: $receipt) { ref in RequestReceiptView(store: store, sampleID: ref.id).atlasModal(store) }
    }
    private var status: String {
        if controller.pending { return controller.stopping ? "正在停止…" : "正在測試" }
        if controller.problem != nil { return "已停止，未完成的請求不會自動重送" }
        return ["READY":"準備測試", "COMPLETED":"測試完成", "LIMIT_REACHED":"已暫停：遇到額度、限流或忙碌", "CANCELLED":"已停止測試"][controller.report.status] ?? "測試已停止"
    }
    private func metric(_ value: Double?, unit: String) -> String { value.map { String(format: "%.1f", $0) + " " + unit } ?? "尚無資料" }
    private func export() {
        let panel = NSSavePanel(); panel.allowedContentTypes = [.json]; panel.nameFieldStringValue = "modelatlas-model-tests.json"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do { try Data(controller.report.raw.pretty.utf8).write(to: url, options: .atomic) } catch { store.problem = error.localizedDescription }
    }
}
