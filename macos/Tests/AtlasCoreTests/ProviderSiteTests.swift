import AtlasCore
import XCTest

final class ProviderSiteTests: XCTestCase {
    private func snapshot() -> JSONValue {
        let points: [JSONValue] = [.object(["ts": .number(1_791_705_600), "avg_tps": .number(0), "success_rate": .number(0)]), .object(["ts": .number(1_791_712_800), "avg_tps": .number(50), "success_rate": .number(0.5)])]
        return .object(["provider_id": .string("p"), "account_id": .string(""), "credential_id": .string(""), "credential_role": .string("ANONYMOUS"), "endpoint": .string("/api/perf-metrics"), "source": .string("NEW_API_OFFICIAL"), "version": .string("v0.0.0"), "observed_at": .number(1_792_309_328_000), "facts": .object(["model_name": .string("m"), "timestamp_unit": .string("UNIX_SECONDS"), "success_rate_unit": .string("PERCENT"), "series_schema": .string("dbcd0a3c01b55203"), "summary": .object(["success_rate": .number(0.5)]), "series": .array(points), "groups": .array([.object(["group": .string("default"), "avg_tps": .number(25), "series": .array(points)])])])])
    }
    func testPublicRequestsNeverCarryAccountOrCredentialAndOnlyPerformanceNeedsWindow() throws {
        XCTAssertEqual(try ProviderSiteSnapshot.request(providerID: "p", operation: "status"), .object(["provider_id": .string("p")]))
        let request = try ProviderSiteSnapshot.request(providerID: "p", operation: "performance", modelID: "m", hours: 720)
        XCTAssertEqual(Set(request.fields.keys), ["provider_id", "model_id", "hours"])
        XCTAssertEqual(request["hours"], .number(720))
        XCTAssertThrowsError(try ProviderSiteSnapshot.request(providerID: "p", operation: "performance"))
        XCTAssertThrowsError(try ProviderSiteSnapshot.request(providerID: "p", operation: "performance", modelID: "m", hours: 721))
        XCTAssertThrowsError(try ProviderSiteSnapshot.request(providerID: "p", operation: "account"))
    }
    func testPublicSnapshotsRejectAnotherProviderModelOrPrivateCredential() throws {
        for (field, value) in [("provider_id", JSONValue.string("q")), ("account_id", .string("a")), ("credential_id", .string("c")), ("credential_role", .string("INFERENCE")), ("source", .string("MY_KEY_LOCAL")), ("observed_at", .number(0))] {
            var bad = snapshot(); bad[field] = value
            XCTAssertThrowsError(try ProviderSiteSnapshot(bad, providerID: "p", operation: "performance", modelID: "m"), field)
        }
        var bad = snapshot(); bad["facts"]["model_name"] = .string("other")
        XCTAssertThrowsError(try ProviderSiteSnapshot(bad, providerID: "p", operation: "performance", modelID: "m"))
        bad = snapshot(); bad["facts"]["origin"] = .string("https://old.example/v1")
        XCTAssertThrowsError(try ProviderSiteSnapshot(bad, providerID: "p", operation: "performance", modelID: "m", origin: "https://new.example/v1"))
    }
    func testProviderChartsKeepSecondsSpacingPercentageUnitsAndZero() throws {
        let value = try ProviderSiteSnapshot(snapshot(), providerID: "p", operation: "performance", modelID: "m")
        let chart = try XCTUnwrap(value.chart(metric: "success_rate", group: ""))
        XCTAssertEqual(chart.unit, "%"); XCTAssertEqual(chart.points.count, 2)
        XCTAssertEqual(chart.points[0].value, 0); XCTAssertEqual(chart.points[1].value, 0.5)
        XCTAssertEqual(chart.points[1].at - chart.points[0].at, 7_200_000)
        XCTAssertEqual(chart.context["source"], .string("PROVIDER_REPORTED"))
        XCTAssertEqual(chart.context["scope"], .string("PROVIDER_MODEL_REFERENCE"))
        XCTAssertEqual(chart.export["points"].array[1]["value"], .number(0.5))
    }
    func testMissingGroupOrMetricDoesNotBorrowOtherGroupOrBecomeZero() throws {
        let value = try ProviderSiteSnapshot(snapshot(), providerID: "p", operation: "performance", modelID: "m")
        XCTAssertNil(value.chart(metric: "avg_tps", group: "other")); XCTAssertNil(value.metrics(group: "other"))
        let chart = try XCTUnwrap(value.chart(metric: "avg_ttft_ms", group: "default"))
        XCTAssertEqual(chart.knownCount, 0); XCTAssertTrue(chart.points.allSatisfy { $0.value == nil })
        XCTAssertEqual(value.metrics(group: "")?["success_rate"], .number(0.5))
    }
    func testUnknownProviderUnitsDoNotCreateAChart() throws {
        for field in ["timestamp_unit", "success_rate_unit"] {
            var bad = snapshot(); bad["facts"][field] = .null
            let value = try ProviderSiteSnapshot(bad, providerID: "p", operation: "performance", modelID: "m")
            XCTAssertNil(value.chart(metric: "avg_tps", group: ""))
        }
    }
    func testErrorDescriptionUsesFixedCodesAndKeepsActualUpstreamStatus() throws {
        let raw: JSONValue = .object(["error": .object(["code": .string("UPSTREAM_HTTP"), "upstream_status": .number(502), "operation": .string("/api/pricing"), "message": .string("never-show-this-secret")])])
        let problem = try XCTUnwrap(ProviderProblem(raw))
        XCTAssertTrue(problem.localizedDescription.contains("502")); XCTAssertFalse(problem.localizedDescription.contains("never-show"))
        var bad = raw; bad["error"]["code"] = .string("never-show-this-secret"); XCTAssertNil(ProviderProblem(bad))
        bad = raw; bad["error"]["upstream_status"] = .number(-1); XCTAssertNil(ProviderProblem(bad))
        XCTAssertTrue(InferenceFailure.message(.object(["http_status": .number(503), "category": .string("anything")])).contains("503"))
    }
    func testConnectionLabelsResolveAccountNameWithinExactProvider() throws {
        let accounts: [JSONValue] = [.object(["provider_id": .string("q"), "id": .string("same"), "label": .string("另一站帳戶")]), .object(["provider_id": .string("p"), "id": .string("same"), "label": .string("主要帳戶")])]
        let key: JSONValue = .object(["provider_id": .string("p"), "account_id": .string("same"), "label": .string("工作 Key"), "group": .string("default")])
        XCTAssertEqual(ConnectionLabels.credential(key, accounts: accounts), "主要帳戶 · 工作 Key · default")
        XCTAssertFalse(ConnectionLabels.credential(key, accounts: []).contains("same"))
    }
    func testActiveHiddenAndArchivedConnectionsHaveDistinctActions() throws {
        XCTAssertEqual(ConnectionLabels.action(state: "ACTIVE"), "查看這個連線")
        XCTAssertEqual(ConnectionLabels.action(state: "HIDDEN"), "重新顯示這個連線")
        XCTAssertEqual(ConnectionLabels.action(state: "ARCHIVED"), "取消封存這個連線")
        XCTAssertEqual(ConnectionLabels.action(state: nil), "加入我的模型")
        XCTAssertEqual(ConnectionLabels.state("ACTIVE"), "已加入")
    }
}
