import Foundation

/// The engine emits fixed error codes, never an upstream response body. Ignore
/// arbitrary error text so a provider cannot echo credentials into the UI.
public struct ProviderProblem: Error, LocalizedError, Sendable {
    public let code: String
    public let upstreamStatus: Int
    public let operation: String
    public init?(_ value: JSONValue) {
        let codes = ["KILO_CATALOG_UNAVAILABLE", "KILO_FREE_MODEL_UNAVAILABLE", "KILO_DEFAULT_CONNECTION_CHANGED", "UPSTREAM_HTTP", "UPSTREAM_NETWORK", "UPSTREAM_TIMEOUT", "UPSTREAM_REJECTED", "INVALID_PROVIDER_RESPONSE", "UNSUPPORTED_PRIVATE_VERSION", "CREDENTIAL_ROLE", "CREDENTIAL_ORIGIN", "INVALID_WINDOW", "MODEL_REQUIRED", "PROVIDER_NOT_FOUND"]
        let item = value["error"]
        guard codes.contains(item["code"].string), let status = item["upstream_status"].numeric,
              status == 0 || (100...599).contains(status), status.rounded() == status else { return nil }
        code = item["code"].string; upstreamStatus = Int(status)
        let allowed = ["catalog", "inference", "/api/status", "/api/notice", "/api/pricing", "/api/perf-metrics", "/api/perf-metrics/summary", "/api/user/self", "/api/usage/token/"]
        operation = allowed.contains(item["operation"].string) ? item["operation"].string : ""
    }
    public var errorDescription: String? {
        switch code {
        case "KILO_CATALOG_UNAVAILABLE": return "暫時無法確認Kilo免費目錄，已保留輸入，請稍後重試。"
        case "KILO_FREE_MODEL_UNAVAILABLE": return "這個Kilo模型目前未確認為免費可用，已停止發送。請更新目錄或選擇其他模型。"
        case "KILO_DEFAULT_CONNECTION_CHANGED": return "預設Kilo連線設定已變更，請檢查連線；沒有傳送聊天。"
        case "UPSTREAM_HTTP":
            if operation == "inference" {
                switch upstreamStatus {
                case 400: return "站點未接受這次模型請求（HTTP 400）。請查看收據，核對模型與對話設定。"
                case 401: return "這把 Key 未通過站點驗證（HTTP 401）。請確認 Key 是否有效，以及是否屬於目前的站點。"
                case 402: return "站點要求確認付費或可用額度（HTTP 402）。請在官網檢查這個帳戶與 Key 的餘額。"
                case 403: return "站點不允許這把 Key 使用目前的模型或群組（HTTP 403）。請在官網確認權限。"
                case 404: return "站點找不到這個模型或端點（HTTP 404）。請核對模型 ID、Key 群組與 API 位址。"
                default: break
                }
            }
            switch upstreamStatus {
            case 401, 403: return "站點拒絕這項查詢（HTTP \(upstreamStatus)）。公開資料與帳戶 PAT 的權限可能不同。"
            case 404, 405: return "這個站點未提供此資料介面（HTTP \(upstreamStatus)）。其他已讀取的資料仍會保留。"
            case 429: return "站點暫時限制查詢頻率（HTTP 429），請稍後再試。"
            case 500...599: return "站點回覆 HTTP \(upstreamStatus)，本次查詢未完成。已保存的資料仍在，可稍後重試。"
            default: return "站點回覆 HTTP \(upstreamStatus)，請檢查這項查詢的站方支援情況。"
            }
        case "UPSTREAM_TIMEOUT": return "連線到站點逾時，請稍後重試。已保存的資料仍會保留。"
        case "UPSTREAM_NETWORK": return "目前無法連上站點，請檢查網路或站點位址後重試。"
        case "UNSUPPORTED_PRIVATE_VERSION": return "此站的私人帳戶介面尚未驗證相容性。公開公告、價格與效能仍可讀取；推論 Key 和帳戶 PAT 不能互換。"
        case "CREDENTIAL_ROLE": return "請選擇此帳戶啟用中的正確憑證：帳戶用量使用 PAT，Key 額度使用推論 Key。"
        case "CREDENTIAL_ORIGIN": return "憑證與查詢站點的來源不一致，請檢查這個 Key 綁定的位址。"
        case "INVALID_WINDOW": return "站方效能時間範圍需為 1–720 小時。"
        case "MODEL_REQUIRED": return "請先選擇要查詢效能的模型。"
        case "PROVIDER_NOT_FOUND": return "這個供應商已不存在，請重新載入清單。"
        case "UPSTREAM_REJECTED": return "站點回覆查詢未成功。請在官網確認資料介面與權限，或稍後重試。"
        default: return "站點回應的格式與這項資料不相容，已保留上次成功的資料。"
        }
    }
}

public enum InferenceFailure {
    public static func message(_ value: JSONValue) -> String {
        if let status = value["http_status"].numeric, (400...599).contains(status), status.rounded() == status,
           let problem = ProviderProblem(.object(["error": .object(["code": .string("UPSTREAM_HTTP"), "upstream_status": .number(status), "operation": .string("inference")])])) { return problem.localizedDescription }
        switch value["category"].string {
        case "TIMEOUT": return "站點回應逾時，這次請求已保存在歷史，可查看收據後重試。"
        case "NETWORK": return "與站點的連線中斷，這次請求已保存在歷史。"
        case "PARSER", "TRUNCATED_STREAM": return "站點的回應不完整或格式不相容，請查看這次請求收據。"
        case "CANCELLED": return "已停止這次請求。"
        default: return "這次請求未完成，可從訊息旁的選單查看收據與失敗原因。"
        }
    }
}
