import AtlasCore
import SwiftUI

@MainActor final class ObservationArchiveController: ObservableObject {
    let scope: ModelScope
    @Published private(set) var state: ObservationArchiveState?
    @Published private(set) var pending = false
    @Published private(set) var problem: String?
    @Published private(set) var requested = ObservationFilter()
    private var task: Task<Void, Never>?
    private var revision = 0
    private var retryMore = false
    init(scope: ModelScope) { self.scope = scope }
    var showingPreviousQuery: Bool { state.map { $0.filter != requested } ?? false }
    func refresh(using api: AtlasClient, filter: ObservationFilter) {
        guard !pending || requested != filter else { return }
        start(using: api, filter: filter, cursor: "")
    }
    func loadMore(using api: AtlasClient) {
        guard !pending, let state, !state.nextCursor.isEmpty, state.filter == requested else { return }
        start(using: api, filter: state.filter, cursor: state.nextCursor)
    }
    func retry(using api: AtlasClient) {
        if retryMore { loadMore(using: api) } else { refresh(using: api, filter: requested) }
    }
    func cancel() { revision += 1; task?.cancel(); task = nil; pending = false }
    private func start(using api: AtlasClient, filter: ObservationFilter, cursor: String) {
        task?.cancel(); revision += 1
        let ticket = revision
        requested = filter; pending = true; problem = nil; retryMore = !cursor.isEmpty
        task = Task { [weak self] in
            guard let self else { return }
            defer { if revision == ticket { pending = false; task = nil } }
            do {
                try Task.checkCancellation()
                var query = scope.query + filter.query
                if !cursor.isEmpty { query.append(URLQueryItem(name: "cursor", value: cursor)) }
                let raw = try await api.get("/models/observations", query: query)
                try Task.checkCancellation()
                guard revision == ticket else { return }
                let page = try ObservationPage(raw, scope: scope, filter: filter)
                if cursor.isEmpty { state = try ObservationArchiveState(page) }
                else {
                    guard var next = state else { throw AtlasError.invalidResponse }
                    try next.append(page, requestedCursor: cursor); state = next
                }
                problem = nil
            } catch is CancellationError { }
            catch { if revision == ticket { problem = error.localizedDescription } }
        }
    }
}

struct ObservationArchiveFooter: View {
    @ObservedObject var archive: ObservationArchiveController
    let api: AtlasClient?
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                if archive.pending { ProgressView().controlSize(.small) }
                if let state = archive.state {
                    Text("已載入 \(state.rows.count)／\(state.total) 筆").font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    if !state.nextCursor.isEmpty {
                        Button("載入更早紀錄") { if let api { archive.loadMore(using: api) } }.disabled(archive.pending || archive.showingPreviousQuery)
                    }
                }
            }
            if archive.showingPreviousQuery { Label("篩選尚未載入完成，以下保留上次成功資料。", systemImage: "clock").font(.caption).foregroundStyle(.secondary) }
            if let problem = archive.problem {
                HStack { Text(problem).font(.caption).foregroundStyle(.red); Spacer(); Button("重試") { if let api { archive.retry(using: api) } }.disabled(archive.pending) }
            }
        }
    }
}

struct ObservationTimePicker: View {
    @Binding var window: String
    @Binding var start: Date
    @Binding var end: Date
    let apply: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Picker("時間範圍", selection: $window) {
                Text("1 小時").tag("1h"); Text("24 小時").tag("24h"); Text("7 天").tag("7d"); Text("30 天").tag("30d"); Text("全部").tag("all"); Text("自訂").tag("custom")
            }.pickerStyle(.segmented)
            if window == "custom" {
                HStack { DatePicker("從", selection: $start); DatePicker("至", selection: $end); Button("套用", action: apply) }
            }
        }.onChange(of: window) { if $0 != "custom" { apply() } }
    }
    static func filter(window: String, start: Date, end: Date, source: String = "", kind: String = "", category: String = "") throws -> ObservationFilter {
        if window == "all" { return ObservationFilter(source: source, kind: kind, category: category) }
        let last = window == "custom" ? end : Date()
        let durations = ["1h": 3600.0, "24h": 86400.0, "7d": 604800.0, "30d": 2592000.0]
        let first = window == "custom" ? start : last.addingTimeInterval(-(durations[window] ?? 604800))
        guard first.timeIntervalSince1970 >= 0, first < last, last <= Date() else { throw DraftProblem("請選擇有效的開始與結束時間。") }
        return ObservationFilter(source: source, kind: kind, category: category,
                                 start: floor(first.timeIntervalSince1970 * 1000), end: floor(last.timeIntervalSince1970 * 1000))
    }
}
