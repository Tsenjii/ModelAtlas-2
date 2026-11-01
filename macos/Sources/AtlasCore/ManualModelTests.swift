import Foundation

/// Unlike Compare, a manual test batch may contain different canonical models.
/// Every result must still match the next exact account/key/group/endpoint.
public struct ManualModelTestPlan: Sendable {
    public let offers: [ComparisonOffer]
    public init(offers: [ComparisonOffer]) throws {
        if let problem = Self.problem(offers) { throw DraftProblem(problem) }
        self.offers = offers
    }
    public static func problem(_ offers: [ComparisonOffer]) -> String? {
        guard (1...100).contains(offers.count) else { return "請選擇 1–100 個連線。" }
        guard Set(offers.map(\.scope)).count == offers.count else { return "每個連線只能選擇一次。" }
        guard offers.allSatisfy(\.enabled) else { return "所選連線已停用、隱藏或封存，請重新選擇。" }
        guard offers.allSatisfy({ ["CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC_MESSAGES", "GEMINI_NATIVE", "CUSTOM"].contains($0.operation) }) else { return "批次測試適用於文字對話連線。" }
        return nil
    }
}

public struct ManualModelTestReport: Sendable {
    public let plan: ManualModelTestPlan
    public private(set) var items: [LiveComparisonItem] = []
    public private(set) var status = "READY"
    public private(set) var unconfirmedIndex: Int?
    public init(plan: ManualModelTestPlan) { self.plan = plan }
    public mutating func append(_ response: JSONValue) throws {
        guard ["READY", "RUNNING"].contains(status), items.count < plan.offers.count,
              ModelScope(response["selection"]) == plan.offers[items.count].scope,
              !response["outcome"].string.isEmpty else { throw AtlasError.invalidResponse }
        items.append(LiveComparisonItem(raw: response, scope: plan.offers[items.count].scope))
        if response["outcome"].string == "CANCELLED" { status = "CANCELLED" }
        else if [402, 429].contains(ModelIntelligence.number(response["http_status"]) ?? -1) || response["outcome"].string == "REQUEST_IN_PROGRESS" { status = "LIMIT_REACHED" }
        else { status = items.count == plan.offers.count ? "COMPLETED" : "RUNNING" }
    }
    public var canContinue: Bool { ["READY", "RUNNING"].contains(status) && items.count < plan.offers.count }
    public mutating func stop(cancelled: Bool, requestUnconfirmed: Bool) {
        status = cancelled ? "CANCELLED" : "DISCONNECTED"
        if requestUnconfirmed && items.count < plan.offers.count { unconfirmedIndex = items.count }
    }
    public var raw: JSONValue {
        .object(["format": .string("modelatlas-manual-test-batch"), "status": .string(status), "results": .array(plan.offers.enumerated().map { index, offer in
            index < items.count ? items[index].raw : .object(["selection": offer.scope.json, "outcome": .string(index == unconfirmedIndex ? "OUTCOME_UNCONFIRMED" : "NOT_TESTED")])
        })])
    }
}
