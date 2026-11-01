import Foundation

public enum ResearchRunState {
    public static func active(_ run: JSONValue) -> Bool { ["QUEUED", "RUNNING"].contains(run["status"].string) }
    public static func controllable(_ run: JSONValue) -> Bool { ["QUEUED", "RUNNING", "PAUSED", "BUDGET_EXHAUSTED"].contains(run["status"].string) }
    public static func resumable(_ run: JSONValue, tasks: [JSONValue]) -> Bool {
        ["PAUSED", "BUDGET_EXHAUSTED", "COMPLETED"].contains(run["status"].string) && tasks.contains { eligible($0, retryFailed: true) }
    }
    private static func eligible(_ task: JSONValue, retryFailed: Bool) -> Bool {
        if ["QUEUED", "INTERRUPTED", "BUDGET_EXHAUSTED"].contains(task["status"].string) { return true }
        guard retryFailed, task["status"].string == "FAILED", let attempts = task["attempts"].numeric, (0..<3).contains(attempts) else { return false }
        return task["result"]["aa_progress"]["stopped_reason"].string.isEmpty && task["result"]["brave_progress"]["stopped_reason"].string.isEmpty
    }
    public static func control(_ action: String, run: JSONValue, tasks: [JSONValue], additional: String = "0", probes: String = "0", retryFailed: Bool = false) throws -> JSONValue {
        let id = run["id"].string
        guard !id.isEmpty, tasks.allSatisfy({ $0["run_id"].string == id }) else { throw DraftProblem("研究紀錄不完整，請重新讀取。") }
        var body: JSONValue = .object(["id": .string(id), "action": .string(action)])
        if action == "RESUME" {
            guard resumable(run, tasks: tasks), tasks.contains(where: { eligible($0, retryFailed: retryFailed) }) else { throw DraftProblem("沒有可恢復的項目；若要重試失敗項目，請先明確勾選。") }
            guard !run["query_comparison"].boolean, let http = Int(additional), (0...200).contains(http), let extra = Int(probes), (0...10).contains(extra),
                  let budget = run["budget"].numeric, let used = run["used"].numeric, let probeBudget = run["probe_budget"].numeric,
                  budget >= 0, used >= 0, probeBudget >= 0, budget + Double(http) <= 200, probeBudget + Double(extra) <= 10, used < budget + Double(http) else {
                throw DraftProblem("請核對剩餘預算；整輪 HTTP 上限為 200，推論上限為 10。成對比較請從比較紀錄操作。")
            }
            body["additional_budget"] = .number(Double(http)); body["additional_probes"] = .number(Double(extra)); body["retry_failed"] = .bool(retryFailed)
        } else {
            guard (action == "PAUSE" && active(run)) || (action == "CANCEL" && controllable(run)) else { throw DraftProblem("研究狀態已變更，請重新讀取。") }
        }
        return body
    }
    public static var aaUpdate: JSONValue {
        // Same bounded public-only durable runner as both VPS update buttons.
        .object(["refresh_aa": .bool(true), "budget": .number(30), "probe_budget": .number(0)])
    }
    public static func title(_ status: String) -> String {
        ["QUEUED":"等待中", "RUNNING":"研究中", "PAUSED":"已暫停", "BUDGET_EXHAUSTED":"已達預算上限", "COMPLETED":"執行結束", "CANCELLED":"已取消", "SUCCESS":"成功", "FAILED":"失敗", "INTERRUPTED":"已中斷"][status] ?? status
    }
}
