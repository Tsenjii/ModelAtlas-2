import AtlasCore
import Foundation
import XCTest

final class ManualModelTestsTests: XCTestCase {
    private func offer(_ model: String, account: String = "a", operation: String = "CHAT_COMPLETIONS", enabled: Bool = true) -> ComparisonOffer {
        let row: JSONValue = .object(["provider_id": .string("p"), "account_id": .string(account), "credential_id": .string("same-name"), "model_id": .string(model), "group": .string("free"), "endpoint": .string("chat"), "enabled": .bool(enabled), "state": .string("ACTIVE"), "metadata": .object(["canonical_id": .string(model)])])
        return ComparisonOffer(model: row, title: model, context: account, operation: operation)
    }
    private func result(_ offer: ComparisonOffer, outcome: String = "SUCCESS", status: Double = 200) -> JSONValue {
        .object(["selection": offer.scope.json, "outcome": .string(outcome), "http_status": .number(status), "details": .object(["sample_id": .string("receipt-" + offer.title), "latency_ms": .number(0)])])
    }
    func testBatchAllowsDifferentModelsButRequiresBoundedDistinctUsableTextScopes() throws {
        let a = offer("one"), b = offer("two")
        XCTAssertNoThrow(try ManualModelTestPlan(offers: [a, b]))
        XCTAssertNoThrow(try ManualModelTestPlan(offers: [a]))
        XCTAssertThrowsError(try ManualModelTestPlan(offers: []))
        XCTAssertThrowsError(try ManualModelTestPlan(offers: [a, a]))
        XCTAssertThrowsError(try ManualModelTestPlan(offers: [offer("image", operation: "IMAGES_GENERATION")]))
        XCTAssertThrowsError(try ManualModelTestPlan(offers: [offer("disabled", enabled: false)]))
        XCTAssertThrowsError(try ManualModelTestPlan(offers: (0...100).map { offer("m\($0)") }))
    }
    func testReceiptsCannotBorrowAccountGroupEndpointOrSkipPendingScope() throws {
        let a = offer("one"), b = offer("two")
        var report = ManualModelTestReport(plan: try ManualModelTestPlan(offers: [a, b]))
        XCTAssertThrowsError(try report.append(result(b)))
        XCTAssertThrowsError(try report.append(result(offer("one", account: "other"))))
        var wrong = result(a); wrong["selection"]["group"] = .string("paid")
        XCTAssertThrowsError(try report.append(wrong))
        wrong = result(a); wrong["selection"]["endpoint"] = .string("other")
        XCTAssertThrowsError(try report.append(wrong))
        try report.append(result(a))
        XCTAssertEqual(report.items.first?.sampleID, "receipt-one")
        XCTAssertEqual(report.items.first?.metric("latency_ms"), 0)
        XCTAssertNil(report.items.first?.metric("ttft_ms"))
        XCTAssertEqual(report.raw["results"].array[1]["outcome"], .string("NOT_TESTED"))
        try report.append(result(b))
        XCTAssertEqual(report.status, "COMPLETED")
        XCTAssertFalse(report.canContinue)
        XCTAssertThrowsError(try report.append(result(b)))
    }
    func testLimitAndCancellationStopPendingSelectionsWithoutFakeResults() throws {
        let a = offer("one"), b = offer("two")
        for (outcome, code, expected) in [("RATE_LIMIT", 429.0, "LIMIT_REACHED"), ("OTHER", 402.0, "LIMIT_REACHED"), ("CANCELLED", 0.0, "CANCELLED"), ("REQUEST_IN_PROGRESS", 0.0, "LIMIT_REACHED")] {
            var report = ManualModelTestReport(plan: try ManualModelTestPlan(offers: [a, b]))
            try report.append(result(a, outcome: outcome, status: code))
            XCTAssertEqual(report.status, expected)
            XCTAssertEqual(report.items.count, 1)
            XCTAssertFalse(report.canContinue)
            XCTAssertThrowsError(try report.append(result(b)))
        }
    }
}
