import AtlasCore
import SwiftUI

struct RequestHistoryView: View {
    @ObservedObject var store: AtlasStore
    var scope: ModelScope? = nil
    @State private var search = "", outcome = "", scopeID = ""
    @State private var records: [JSONValue] = []
    @State private var detail: JSONValue?
    @State private var loading = false, more = false
    @State private var problem: String?
    @State private var revision = 0
    private var selectedScope: ModelScope? { scope ?? store.myModels.first(where: { ModelScope($0).id == scopeID }).map(ModelScope.init) }
    private var query: [URLQueryItem] {
        (selectedScope?.query ?? []) + [URLQueryItem(name: "outcome", value: outcome), URLQueryItem(name: "q", value: search)]
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack { Text("請求紀錄").font(.title3.weight(.semibold)); Spacer(); Button("匯出全部 NDJSON…") { let snapshot = query; Task { await store.export("/telemetry/export", query: snapshot, filename: "ModelAtlas-Requests.ndjson") } } }
            SearchField(placeholder: "搜尋模型、供應商或請求來源", text: $search)
            HStack {
                Picker("結果", selection: $outcome) { Text("全部結果").tag(""); ForEach(["SUCCESS", "FAILED", "CANCELLED", "INTERRUPTED", "RUNNING"], id: \.self) { Text($0).tag($0) } }
                if scope == nil { Picker("連線", selection: $scopeID) { Text("全部連線").tag(""); ForEach(store.myModels, id: \.self) { Text($0["model_id"].string + " · " + store.scopeTitle($0) + " · " + $0["endpoint"].string).tag(ModelScope($0).id) } } }
                Button("重新載入") { Task { await load() } }.disabled(loading)
            }
            if let problem { Text(problem).foregroundStyle(.red); Button("重試") { Task { await load() } } }
            if loading { ProgressView("讀取請求紀錄…").controlSize(.small) }
            ForEach(records, id: \.self) { record in
                ActionButton(title: record["model_id"].string + " · " + record["outcome"].string + " · " + store.scopeTitle(record)) {
                    guard let api = store.api else { return }; detail = try await api.get("/telemetry/detail", query: [URLQueryItem(name: "id", value: record["id"].string)])
                }
            }
            if more { Button("載入更多請求") { Task { await load(append: true) } }.disabled(loading) }
            if records.isEmpty && !loading && problem == nil { Text("還沒有符合的請求紀錄。").foregroundStyle(.secondary) }
        }.task(id: query.map { ($0.name + "=" + ($0.value ?? "")) }.joined(separator: "&")) { await load() }
        .sheet(item: $detail) { FactSheet(title: "請求收據", value: $0).atlasModal(store) }
    }
    private func load(append: Bool = false) async {
        guard let api = store.api else { return }; revision += 1; let ticket = revision, requested = query
        loading = true; problem = nil; defer { if ticket == revision { loading = false } }
        do {
            if !append { try await Task.sleep(nanoseconds: 200_000_000) }
            let page = try await api.get("/telemetry", query: requested + [URLQueryItem(name: "limit", value: "100"), URLQueryItem(name: "offset", value: String(append ? records.count : 0))]).requiredArray()
            try Task.checkCancellation(); guard ticket == revision else { return }
            records = append ? records + page : page; more = page.count == 100
        } catch is CancellationError { } catch { if ticket == revision { problem = error.localizedDescription } }
    }
}
