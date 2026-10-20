import AtlasCore
import Foundation
import XCTest

final class LiveComparisonTests: XCTestCase {
    private func model(_ account: String = "a", canonical: String = "canonical") -> JSONValue {
        .object(["provider_id": .string("p"), "account_id": .string(account), "credential_id": .string("same-key"), "model_id": .string("model"), "group": .string("free"), "endpoint": .string("chat"), "state": .string("ACTIVE"), "enabled": .bool(true), "metadata": .object(["canonical_id": .string(canonical)])])
    }
    private func offer(_ value: JSONValue, operation: String = "CHAT_COMPLETIONS") -> ComparisonOffer {
        ComparisonOffer(model: value, title: "顯示名稱", context: "帳戶名稱", operation: operation)
    }
    private func row(_ value: JSONValue, outcome: String = "SUCCESS", details: JSONValue = .object([:])) -> JSONValue {
        .object(["selection": ModelScope(value).json, "outcome": .string(outcome), "details": details])
    }
    func testRequestRequiresDistinctEnabledCanonicalTextOfferingsAndBoundedUTF8Prompt() throws {
        let a = offer(model()), b = offer(model("b"))
        let request = try ComparisonOffer.request([a, b], prompt: "相同問題")
        XCTAssertEqual(request["selections"].array.map(ModelScope.init), [a.scope, b.scope])
        XCTAssertEqual(request["prompt"], .string("相同問題"))
        XCTAssertThrowsError(try ComparisonOffer.request([a, a], prompt: ""))
        XCTAssertThrowsError(try ComparisonOffer.request([a], prompt: ""))
        XCTAssertThrowsError(try ComparisonOffer.request([a, offer(model("b", canonical: "different"))], prompt: ""))
        XCTAssertThrowsError(try ComparisonOffer.request([a, offer(model("b", canonical: ""))], prompt: ""))
        var disabled = model("b"); disabled["enabled"] = .bool(false)
        XCTAssertThrowsError(try ComparisonOffer.request([a, offer(disabled)], prompt: ""))
        XCTAssertThrowsError(try ComparisonOffer.request([a, offer(model("b"), operation: "IMAGES_GENERATION")], prompt: ""))
        XCTAssertNoThrow(try ComparisonOffer.request([a, b], prompt: String(repeating: "a", count: 1000)))
        XCTAssertThrowsError(try ComparisonOffer.request([a, b], prompt: String(repeating: "中", count: 334)))
    }
    func testResponseIsReorderedByFullScopeWithoutBorrowingSameNamedAccount() throws {
        let a = model(), b = model("b"), scopes = [ModelScope(a), ModelScope(b)]
        let report = try LiveComparison(response: .array([row(b, outcome: "RATE_LIMIT"), row(a)]), scopes: scopes)
        XCTAssertEqual(report.items.map(\.scope), scopes)
        XCTAssertEqual(report.items.map(\.outcome), ["SUCCESS", "RATE_LIMIT"])
        XCTAssertThrowsError(try LiveComparison(response: .array([row(a), row(model("other"))]), scopes: scopes))
        var wrong = b; wrong["group"] = .string("paid")
        XCTAssertThrowsError(try LiveComparison(response: .array([row(a), row(wrong)]), scopes: scopes))
        wrong = b; wrong["endpoint"] = .string("other-chat")
        XCTAssertThrowsError(try LiveComparison(response: .array([row(a), row(wrong)]), scopes: scopes))
    }
    func testDuplicateMissingAndMalformedResultsFailClosed() {
        let a = model(), b = model("b"), scopes = [ModelScope(a), ModelScope(b)]
        let cases: [JSONValue] = [.null, .object([:]), .array([row(a)]), .array([row(a), row(a)]), .array([row(a), .object(["selection": ModelScope(b).json])])]
        for raw in cases {
            XCTAssertThrowsError(try LiveComparison(response: raw, scopes: scopes))
        }
    }
    func testUnknownZeroFailureAndNonStreamingMetricsRemainDistinct() throws {
        let a = model(), b = model("b")
        let first = row(a, details: .object(["streaming": .bool(false), "ttft_ms": .number(999), "latency_ms": .number(0), "tps": .string("fast")]))
        let second = row(b, outcome: "RATE_LIMIT", details: .object(["ttft_ms": .number(0), "tps": .null, "input_tokens": .bool(false)]))
        let items = try LiveComparison(response: .array([first, second]), scopes: [ModelScope(a), ModelScope(b)]).items
        XCTAssertNil(items[0].ttft); XCTAssertEqual(items[0].metric("latency_ms"), 0); XCTAssertNil(items[0].metric("tps"))
        XCTAssertEqual(items[1].ttft, 0); XCTAssertNil(items[1].metric("tps")); XCTAssertNil(items[1].metric("input_tokens"))
        XCTAssertEqual(items[1].outcome, "RATE_LIMIT")
    }
    func testCostKeepsActualEffectiveNominalAndExactDecimalSeparate() throws {
        let a = model(), b = model("b")
        let cost: JSONValue = .object(["actual": .string("0"), "estimated": .null, "nominal_estimated": .string("0.000000000123456789"), "currency": .string("USD"), "state": .string("PROVIDER_REPORTED")])
        let first = row(a, details: .object(["receipt": .object(["cost": cost])]))
        let reported: JSONValue = .object(["receipt": .object(["cost": .object(["reported_charge": .object(["amount": .string("0.00123456789"), "unit": .string("CREDITS")])])])])
        let report = try LiveComparison(response: .array([first, row(b, details: reported)]), scopes: [ModelScope(a), ModelScope(b)])
        XCTAssertEqual(report.items[0].cost("actual"), "0 USD")
        XCTAssertEqual(report.items[0].cost("estimated"), "—")
        XCTAssertEqual(report.items[0].cost("nominal_estimated"), "0.000000000123456789 USD")
        XCTAssertEqual(report.items[1].cost("actual"), "—")
        XCTAssertEqual(report.items[1].reportedCost, "0.00123456789 CREDITS")
    }
    func testQuotaBeforeAfterPairsKeepSourceScopeUnitAndCredentialVersionSeparate() throws {
        let a = model(), b = model("b")
        let pool: JSONValue = .object(["scope": ModelScope(a).json, "kind": .string("QUOTA"), "source": .string("OFFICIAL"), "facts": .object(["remaining": .null, "unit": .string("REQUESTS"), "credential_version": .number(1)])])
        var after = pool; after["facts"]["remaining"] = .number(0)
        var unit = after; unit["facts"]["unit"] = .string("TOKENS")
        var version = after; version["facts"]["credential_version"] = .number(2)
        var source = after; source["source"] = .string("USER")
        var account = after; account["scope"]["account_id"] = .string("b")
        let r: JSONValue = .object(["quota_before": .array([pool]), "quota_after": .array([after, unit, version, source, account])])
        let result = try LiveComparison(response: .array([row(a, details: .object(["receipt": r])), row(b)]), scopes: [ModelScope(a), ModelScope(b)]).items[0]
        XCTAssertEqual(result.quota.count, 4)
        let pair = try XCTUnwrap(result.quota.first { $0.before != nil })
        XCTAssertEqual(pair.before?["facts"]["remaining"], .null)
        XCTAssertEqual(pair.after?["facts"]["remaining"], .number(0))
        XCTAssertEqual(result.quota.filter { $0.before == nil }.count, 3)
    }
}
