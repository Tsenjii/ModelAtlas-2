import AtlasCore
import SwiftUI

@MainActor final class ResearchRunController: ObservableObject {
    let api: AtlasClient
    let id: String
    @Published private(set) var run: JSONValue
    @Published private(set) var tasks: [JSONValue] = []
    @Published private(set) var ranking: JSONValue = .null
    @Published private(set) var loaded = false
    @Published private(set) var busy = false
    @Published private(set) var reading = false
    @Published private(set) var needsReload = true
    @Published private(set) var problem: String?
    @Published var additional = "0", probes = "0", retryFailed = false

    init(api: AtlasClient, run: JSONValue) { self.api = api; self.run = run; id = run["id"].string }
    static func queueAA(using api: AtlasClient) async throws -> JSONValue {
        let receipt = try await api.call("/research/start", method: "POST", body: ResearchRunState.aaUpdate)
        guard !receipt["id"].string.isEmpty, receipt["status"].string == "QUEUED", receipt["budget"] == .number(30), receipt["probe_budget"] == .number(0) else { throw AtlasError.invalidResponse }
        return receipt
    }
    var active: Bool { ResearchRunState.active(run) }
    var canResume: Bool { ResearchRunState.resumable(run, tasks: tasks) }
    var locked: Bool { busy || reading || needsReload || !loaded }
    var shouldPoll: Bool { loaded && active && !needsReload }

    private func snapshot() async throws {
        // Read the run before its tasks. The runner commits task results before
        // terminal run status; parallel reads could show COMPLETED with an old
        // RUNNING task, then stop polling forever.
        let rows = try await api.get("/research/runs", query: [URLQueryItem(name: "id", value: id)])
        let details = try await api.get("/research/tasks", query: [URLQueryItem(name: "run_id", value: id)])
        let aa = try await api.get("/research/aa")
        try Task.checkCancellation()
        let values = try rows.requiredArray(), items = try details.requiredArray()
        guard values.count == 1, let latest = values.first, latest["id"].string == id,
              items.allSatisfy({ $0["run_id"].string == id }) else { throw AtlasError.invalidResponse }
        // Commit the complete read together; failed/late reads keep last-good.
        run = latest; tasks = items; ranking = aa
        loaded = true; needsReload = false; problem = nil
    }
    func reload() async {
        guard !busy, !reading, !id.isEmpty else { return }
        reading = true; defer { reading = false }
        do { try await snapshot() }
        catch { if !Task.isCancelled { needsReload = true; problem = "暫時無法讀取最新紀錄，已保留畫面。請重新讀取後再操作。" } }
    }
    func control(_ action: String) async {
        guard !locked else { return }
        let body: JSONValue
        do { body = try ResearchRunState.control(action, run: run, tasks: tasks, additional: additional, probes: probes, retryFailed: retryFailed) }
        catch { problem = error.localizedDescription; return }
        busy = true; problem = nil; defer { busy = false }
        var acknowledged = false
        do {
            let receipt = try await api.call("/research/control", method: "POST", body: body)
            let expected = ["RESUME":"QUEUED", "PAUSE":"PAUSED", "CANCEL":"CANCELLED"][action]
            guard receipt["id"].string == id, receipt["status"].string == expected else { throw AtlasError.invalidResponse }
            acknowledged = true; run["status"] = receipt["status"]; needsReload = true
            if action == "RESUME" {
                if additional == body["additional_budget"].display { additional = "0" }
                if probes == body["additional_probes"].display { probes = "0" }
                retryFailed = false
            }
            try await snapshot()
        } catch {
            needsReload = true
            problem = acknowledged ? "操作已保存，但畫面尚未更新。請重新讀取紀錄。" : "操作結果尚待確認。請重新讀取紀錄後再決定下一步。"
        }
    }
}
