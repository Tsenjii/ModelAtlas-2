import AtlasCore
import SwiftUI

struct RequestReceiptView: View {
    @ObservedObject var store: AtlasStore
    let sampleID: String
    @State private var value: JSONValue?
    @State private var problem: String?
    @State private var loading = false
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                VStack(alignment: .leading, spacing: 5) {
                    Text("請求收據").font(.title2.weight(.semibold))
                    Text("這次請求的用量、效能與當時的價格快照").foregroundStyle(.secondary)
                }
                Spacer()
                Button("完成") { store.requestReceipt = nil }.keyboardShortcut(.cancelAction)
            }
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if loading { ProgressView("讀取請求紀錄…").frame(maxWidth: .infinity) }
                    else if let problem {
                        Text(problem).foregroundStyle(.red)
                        Button("重新讀取") { Task { await load() } }
                    } else if let value {
                        receipt(value)
                    }
                }.padding(.vertical, 4)
            }
        }.padding(26).frame(width: 640, height: 600)
            .task(id: sampleID) { await load() }
    }
    private func load() async {
        guard let api = store.api, !loading else { return }
        loading = true; problem = nil
        defer { loading = false }
        do { value = try await api.get("/telemetry/detail", query: [URLQueryItem(name: "id", value: sampleID)]) }
        catch { problem = error.localizedDescription }
    }
    @ViewBuilder private func receipt(_ value: JSONValue) -> some View {
        let item = value["sample"] == .null ? value : value["sample"]
        AtlasCard {
            HStack {
                VStack(alignment: .leading, spacing: 5) {
                    Text(item["model_id"].string).font(.headline).textSelection(.enabled)
                    Text(store.scopeTitle(item)).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                SmallBadge(text: ["SUCCESS": "成功", "FAILED": "失敗", "CANCELLED": "已取消", "RUNNING": "進行中"][item["outcome"].string] ?? item["outcome"].display, color: item["outcome"].string == "SUCCESS" ? atlasAccent : .secondary)
            }
            Divider()
            metric("輸入 Token", item["input_tokens"])
            metric("輸出 Token", item["output_tokens"])
            metric("首 Token", item["ttft_ms"], suffix: " ms")
            metric("總延遲", item["latency_ms"], suffix: " ms")
        }
        DisclosureGroup("價格、額度與完整原始收據") { JSONTree(value: value).padding(.top, 12) }
        Text("缺少數值表示尚無資料；價格與額度保留各自來源，不以零推算。").font(.caption).foregroundStyle(.secondary)
    }
    private func metric(_ title: String, _ value: JSONValue, suffix: String = "") -> some View {
        HStack { Text(title).foregroundStyle(.secondary); Spacer(); Text(value == .null ? "尚無資料" : value.display + suffix).monospacedDigit().textSelection(.enabled) }
    }
}
