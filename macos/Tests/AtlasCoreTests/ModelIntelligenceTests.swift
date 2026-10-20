import AtlasCore
import Foundation
import XCTest

final class ModelIntelligenceTests: XCTestCase {
    private let scope = ModelScope(.object(["provider_id": .string("p"), "account_id": .string("a"), "credential_id": .string("same-key"), "model_id": .string("m"), "group": .string("free"), "endpoint": .string("chat")]))
    private func observation(_ id: String, kind: String = "PRICING", source: String = "OFFICIAL", at: Double = 1, facts: JSONValue = .object([:])) -> JSONValue {
        var row = scope.json; row["id"] = .string(id); row["kind"] = .string(kind); row["source"] = .string(source)
        row["status"] = .string("SUCCESS"); row["observed_at"] = .number(at); row["facts"] = facts; return row
    }
    private func detail(_ history: [JSONValue] = [], good: [String: JSONValue] = [:]) -> JSONValue {
        .object(["selection": scope.json, "history": .array(history), "last_good": .object(good)])
    }
    func testMismatchedDetailCannotExposeAnotherAccountHistoryOrMetrics() {
        let row = observation("one")
        var d = detail([row], good: ["price": row]); d["selection"]["account_id"] = .string("other")
        d["local"] = .object(["source": .string("MY_KEY_LOCAL"), "samples": .number(100)])
        d["external"] = .array([.object(["units": .string("TPS")])])
        let value = ModelIntelligence(detail: d, scope: scope)
        XCTAssertFalse(value.matchesSelection); XCTAssertEqual(value.local, .null)
        XCTAssertTrue(value.history.isEmpty && value.lastGood.isEmpty && value.external.isEmpty && value.pools.isEmpty)
    }
    func testMissingMetricsStayUnknownAndExactDecimalTextIsRetained() {
        let inputs: [JSONValue] = [.null, .bool(false), .string(""), .string("NaN"), .string("Infinity"), .number(-1)]
        for input in inputs { XCTAssertNil(ModelIntelligence.number(input)) }
        XCTAssertEqual(ModelIntelligence.number(.string("0")), 0)
        XCTAssertEqual(ModelIntelligence.amount(.string("0.000000000123456789")), "0.000000000123456789")
        XCTAssertEqual(ModelIntelligence.amount(.null), "尚無資料")
    }
    func testPriceHistorySeparatesSourceUnitsEffectiveAndCredentialVersions() {
        let legs: JSONValue = .object(["input": .string("0"), "unit": .string("USD/1K tokens")])
        let a = observation("a", facts: .object(["price": legs, "effective_charge": legs, "credential_version": .number(1)]))
        var unit = a; unit["id"] = .string("unit"); unit["facts"]["price"]["unit"] = .string("USD/request"); unit["facts"]["effective_charge"] = .null
        var source = a; source["id"] = .string("source"); source["source"] = .string("USER"); source["facts"]["effective_charge"] = .null
        var version = a; version["id"] = .string("version"); version["facts"]["credential_version"] = .number(2); version["facts"]["effective_charge"] = .null
        var foreign = a; foreign["id"] = .string("foreign"); foreign["account_id"] = .string("other")
        let data = ModelIntelligence(detail: detail([a, unit, source, version, foreign]), scope: scope)
        XCTAssertEqual(data.priceTracks.count, 5)
        XCTAssertEqual(data.history.count, 4)
        XCTAssertTrue(data.priceTracks.allSatisfy { $0.observations.count == 1 })
        XCTAssertEqual(data.priceTracks.filter { $0.legKey == "effective_charge" }.count, 1)
    }
    func testQuotaPoolsKeepScopesSeparateAndDoNotBorrowOtherGroupOrEndpoint() {
        var account = scope.json; account["credential_id"] = .string(""); account["model_id"] = .string(""); account["group"] = .string(""); account["endpoint"] = .string("")
        func pool(_ id: String, selection: JSONValue, remaining: JSONValue) -> JSONValue {
            .object(["id": .string(id), "scope": selection, "kind": .string("QUOTA"), "source": .string("OFFICIAL"), "status": .string("SUCCESS"), "observed_at": .number(1), "facts": .object(["remaining": remaining])])
        }
        var wrongGroup = account; wrongGroup["group"] = .string("paid")
        var wrongEndpoint = scope.json; wrongEndpoint["endpoint"] = .string("images")
        var wrongAccount = account; wrongAccount["account_id"] = .string("other")
        var d = detail(); d["resource_pools"] = .array([pool("account", selection: account, remaining: .number(0)), pool("key", selection: scope.json, remaining: .null), pool("group", selection: wrongGroup, remaining: .number(999)), pool("endpoint", selection: wrongEndpoint, remaining: .number(999)), pool("other", selection: wrongAccount, remaining: .number(999))])
        let value = ModelIntelligence(detail: d, scope: scope)
        XCTAssertEqual(Set(value.pools.map(\.id)), ["account", "key"])
        XCTAssertEqual(value.pools.first { $0.id == "key" }?.facts["remaining"], .null)
        XCTAssertEqual(value.pools.first { $0.id == "account" }?.facts["remaining"], .number(0))
    }
    func testLocalChartExcludesCancelledAndRetainsMissingValueGaps() {
        var d = detail(); d["local"] = .object(["source": .string("MY_KEY_LOCAL"), "history": .array([
            .object(["at": .number(100), "outcome": .string("SUCCESS"), "ttft_ms": .number(0), "tps": .number(0)]),
            .object(["at": .number(200), "outcome": .string("RATE_LIMIT"), "ttft_ms": .null]),
            .object(["at": .number(300), "outcome": .string("CANCELLED"), "ttft_ms": .number(999)]),
            .object(["at": .number(400), "outcome": .string("INTERRUPTED")])])])
        let value = ModelIntelligence(detail: d, scope: scope)
        XCTAssertEqual(value.samples.count, 2); XCTAssertEqual(value.samples[0].ttft, 0); XCTAssertNil(value.samples[1].ttft)
        XCTAssertEqual(value.samples.map { $0.value("success") }, [1, 0])
        d["local"]["source"] = .string("PROVIDER_REPORTED")
        XCTAssertTrue(ModelIntelligence(detail: d, scope: scope).samples.isEmpty)
    }
    func testFailedRefreshKeepsLastGoodAndProviderScopeIsNeverLocal() {
        let good = observation("good", kind: "QUOTA", facts: .object(["remaining": .number(20)]))
        var failed = good; failed["id"] = .string("failure"); failed["status"] = .string("FAILED"); failed["facts"] = .object(["reason": .string("timeout")]); failed["observed_at"] = .number(2)
        var site = observation("site", kind: "/api/perf-metrics", source: "PROVIDER_REPORTED"); site["credential_id"] = .string("")
        var foreign = site; foreign["account_id"] = .string("other")
        var d = detail([failed, good], good: ["quota": good]); d["account_facts"] = .array([site, foreign])
        let value = ModelIntelligence(detail: d, scope: scope)
        XCTAssertEqual(value.pools.first?.facts["remaining"], .number(20)); XCTAssertEqual(value.history.first?.status, "FAILED")
        XCTAssertEqual(value.provider.map(\.id), ["site"]); XCTAssertEqual(value.local, .null)
    }
}
