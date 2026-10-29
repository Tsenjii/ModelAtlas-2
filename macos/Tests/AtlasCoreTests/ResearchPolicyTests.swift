import AtlasCore
import XCTest

final class ResearchPolicyTests: XCTestCase {
    private func execution(_ revision: Int = 0, parallel: Int = 1) -> JSONValue {
        .object(["policy": .object(["revision": .number(Double(revision)), "max_parallel_runs": .number(Double(parallel)), "updated_at": .number(revision == 0 ? 0 : 1)]), "running_runs": .number(0), "scheduled_rounds": .number(0)])
    }
    private func credential(_ account: String = "a") -> JSONValue {
        .object(["provider_id": .string("p"), "account_id": .string(account), "credential_id": .string("c"), "provider_name": .string("站點"), "account_label": .string(account), "label": .string("同名 Key"), "available": .bool(true), "override": .bool(false), "used": .number(0), "daily_limit": .number(20)])
    }
    private var usage: JSONValue {
        .object(["requests": .number(0), "pending_requests": .number(0), "unknown_token_requests": .number(0), "unknown_usd_requests": .number(0), "overrun_requests": .number(0), "accounted_tokens": .string("0"), "accounted_usd": .string("0"), "reported_tokens": .string("0"), "reported_usd": .string("0"), "known_reported_tokens": .string("0"), "known_reported_usd": .string("0")])
    }
    private func inference(_ revision: Int = 1) -> JSONValue {
        .object(["revision": .number(Double(revision)), "daily_limit": .number(100), "default_credential_limit": .number(20), "today_used": .number(0), "started_at": .number(1), "reset_at": .number(2), "day": .string("2026-10-29"), "credentials": .array([credential(), credential("b")]), "next_cursor": .string(""), "lifecycle": .string("THIS_ENGINE"), "token_usage": .null, "currency_cost": .null, "provider_remaining": .null])
    }
    private var resource: JSONValue {
        var row = credential(); row["limits"] = .object(["tokens": .null, "usd": .null]); row["usage"] = usage
        return .object(["revision": .number(1), "started_at": .number(1), "reset_at": .number(2), "day": .string("2026-10-29"), "credentials": .array([row]), "next_cursor": .string(""), "lifecycle": .string("THIS_ENGINE"), "budget_basis": .string("LOCAL_RESERVATIONS_AND_REPORTED_USAGE"), "older_unbudgeted_requests": .number(0), "usage": usage, "policy": .object(["daily_tokens": .null, "daily_usd": .null, "credential_tokens": .null, "credential_usd": .null, "reserve_tokens": .number(256), "reserve_usd": .string("0.01")])])
    }
    func testOnlyCompleteTypedLocalPoliciesGrantEditableSnapshots() throws {
        XCTAssertEqual(try ResearchPolicySnapshot(kind: .execution, value: execution()).revision, 0)
        XCTAssertThrowsError(try ResearchPolicySnapshot(kind: .execution, value: execution(parallel: 4)))
        XCTAssertThrowsError(try ResearchPolicySnapshot(kind: .execution, value: execution(1, parallel: 5)))
        for (key, value) in [("revision", JSONValue.number(1.5)), ("revision", .number(9_007_199_254_740_992)), ("daily_limit", .bool(false)), ("day", .string("2026-02-30")), ("lifecycle", .string("OTHER_ENGINE")), ("credentials", .null)] {
            var changed = inference(); changed[key] = value
            XCTAssertThrowsError(try ResearchPolicySnapshot(kind: .inference, value: changed), key)
        }
        var missing = inference().fields; missing.removeValue(forKey: "provider_remaining")
        XCTAssertThrowsError(try ResearchPolicySnapshot(kind: .inference, value: .object(missing)))
        var money = resource; money["policy"]["daily_usd"] = .number(0)
        XCTAssertThrowsError(try ResearchPolicySnapshot(kind: .resource, value: money))
    }
    func testZeroMissingAndNullableLimitsKeepDifferentMeanings() throws {
        var draft = try ResearchPolicyDraft(ResearchPolicySnapshot(kind: .resource, value: resource))
        var body = try draft.request()
        XCTAssertEqual(Set(body.fields.keys), Set(["revision", "action", "daily_tokens", "daily_usd", "credential_tokens", "credential_usd", "reserve_tokens", "reserve_usd"]))
        XCTAssertEqual(body.fields["daily_tokens"], .null)
        draft.setEnabled("daily_tokens", true)
        XCTAssertThrowsError(try draft.request())
        draft.edit("daily_tokens", text: "0"); body = try draft.request()
        XCTAssertEqual(body["daily_tokens"], .number(0))
        draft.edit("daily_tokens", text: "1000000000001"); XCTAssertThrowsError(try draft.request())
        var count = try ResearchPolicyDraft(ResearchPolicySnapshot(kind: .inference, value: inference()))
        count.edit("daily_limit", text: "0"); XCTAssertEqual(try count.request()["daily_limit"], .number(0))
        count.edit("daily_limit", text: ""); XCTAssertThrowsError(try count.request())
        count.edit("daily_limit", text: "2001"); XCTAssertThrowsError(try count.request())
    }
    func testUSDUsesExactDecimalBoundsAndNeverRoundsTinyReservationsToZero() throws {
        for value in ["0", "-0", "0.0000000000000000000000000000000000000000000001", "1e-4096", "1000000.00000000000000000000000000000000000000"] { XCTAssertTrue(ResearchPolicyValue.validUSD(value), value) }
        for value in ["", "NaN", "-0.01", ".01", "1e4097", "1000000.0000000000000000000000000000000000000001"] { XCTAssertFalse(ResearchPolicyValue.validUSD(value), value) }
        XCTAssertFalse(ResearchPolicyValue.validUSD("-0", positive: true))
        var draft = try ResearchPolicyDraft(ResearchPolicySnapshot(kind: .resource, value: resource))
        let exact = "0.000000000000000000000000000001"
        draft.edit("reserve_usd", text: exact)
        XCTAssertEqual(try draft.request()["reserve_usd"], .string(exact))
        draft.edit("reserve_usd", text: "0"); XCTAssertThrowsError(try draft.request())
    }
    func testCredentialRequestsBindOnlyTheExactStableTriple() throws {
        let snapshot = try ResearchPolicySnapshot(kind: .inference, value: inference())
        let first = try ResearchCredentialID(credential()), other = try ResearchCredentialID(credential("b"))
        var a = try ResearchPolicyDraft(snapshot, credential: first)
        var b = try ResearchPolicyDraft(snapshot, credential: other)
        a.edit("daily_limit", text: "0"); b.edit("daily_limit", text: "7")
        let body = try a.request(), peer = try b.request()
        XCTAssertEqual(Set(body.fields.keys), Set(["action", "revision", "provider_id", "account_id", "credential_id", "daily_limit"]))
        XCTAssertEqual(body["account_id"], .string("a")); XCTAssertEqual(peer["account_id"], .string("b")); XCTAssertNotEqual(first.id, other.id)
        XCTAssertEqual(try a.request(reset: true).fields.count, 5)
        var foreign = credential(); foreign["provider_id"] = .string("elsewhere")
        XCTAssertThrowsError(try ResearchPolicyDraft(snapshot, credential: ResearchCredentialID(foreign)))
        var duplicate = inference(); var model = credential(); model["model_id"] = .string("different-model"); duplicate["credentials"] = .array([credential(), model])
        XCTAssertThrowsError(try ResearchPolicySnapshot(kind: .inference, value: duplicate))
    }
    func testCredentialPaginationRequiresTheSameDayRevisionAndUniqueScopes() throws {
        var first = inference(); first["credentials"] = .array([credential()]); first["next_cursor"] = .string("page-two")
        let one = try ResearchPolicySnapshot(kind: .inference, value: first)
        var second = inference(); second["credentials"] = .array([credential("b")])
        XCTAssertEqual(try one.appending(ResearchPolicySnapshot(kind: .inference, value: second)).credentials.count, 2)
        for (key, value) in [("revision", JSONValue.number(2)), ("day", .string("2026-10-30")), ("next_cursor", .string("page-two")), ("credentials", .array([credential()]))] {
            var changed = second; changed[key] = value
            XCTAssertThrowsError(try one.appending(ResearchPolicySnapshot(kind: .inference, value: changed)), key)
        }
    }
    func testReloadCannotSilentlyRebaseOrEraseAnUnsavedDraft() throws {
        let initial = try ResearchPolicySnapshot(kind: .inference, value: inference())
        var draft = try ResearchPolicyDraft(initial); draft.edit("daily_limit", text: "33")
        var latest = inference(2); latest["daily_limit"] = .number(44)
        let snapshot = try ResearchPolicySnapshot(kind: .inference, value: latest)
        let kept = try draft.refreshed(snapshot)
        XCTAssertEqual(kept.revision, 1); XCTAssertEqual(kept.fields["daily_limit"], "33"); XCTAssertTrue(kept.dirty)
        let discarded = try ResearchPolicyDraft(snapshot)
        XCTAssertEqual(discarded.revision, 2); XCTAssertEqual(discarded.fields["daily_limit"], "44")
        draft.acknowledged(2)
        XCTAssertFalse(draft.dirty); XCTAssertEqual(try draft.refreshed(snapshot), discarded)
    }
    func testUnknownReportedUsageAndArbitraryPrecisionTotalsRemainSeparate() throws {
        var value = resource
        value["usage"]["requests"] = .number(1); value["usage"]["pending_requests"] = .number(1)
        value["usage"]["unknown_token_requests"] = .number(1); value["usage"]["unknown_usd_requests"] = .number(1)
        value["usage"]["reported_tokens"] = .null; value["usage"]["reported_usd"] = .null
        let huge = "92233720368547758089999999999999999999999999"
        value["usage"]["accounted_tokens"] = .string(huge)
        let snapshot = try ResearchPolicySnapshot(kind: .resource, value: value)
        XCTAssertEqual(snapshot.value["usage"]["reported_tokens"], .null)
        XCTAssertEqual(snapshot.value["usage"]["accounted_tokens"], .string(huge))
        value["usage"]["reported_usd"] = .string("0")
        XCTAssertThrowsError(try ResearchPolicySnapshot(kind: .resource, value: value))
    }
    func testAcknowledgementsAndHistoryRequireExactVersionsAndResetSemantics() throws {
        var draft = try ResearchPolicyDraft(ResearchPolicySnapshot(kind: .execution, value: execution()))
        draft.edit("max_parallel_runs", text: "2"); let request = try draft.request()
        XCTAssertEqual(try ResearchPolicyValue.receipt(execution(1, parallel: 2)["policy"], request: request, kind: .execution), 1)
        for receipt in [execution(1, parallel: 3)["policy"], execution(2, parallel: 2)["policy"], .object(["revision": .bool(true)])] { XCTAssertThrowsError(try ResearchPolicyValue.receipt(receipt, request: request, kind: .execution)) }
        var row = credential(); row["revision"] = .number(2); row["action"] = .string("RESET"); row["changed_at"] = .number(1); row["value"] = .object(["use_default": .bool(true)])
        var page: JSONValue = .object(["history": .array([row]), "next_before": .number(0)])
        XCTAssertEqual(try ResearchPolicyHistory.page(kind: .resource, value: page).rows.count, 1)
        row["value"] = .null; page["history"] = .array([row])
        XCTAssertThrowsError(try ResearchPolicyHistory.page(kind: .resource, value: page))
        row["value"] = .object(["use_default": .bool(true)]); page["history"] = .array([row, row])
        XCTAssertThrowsError(try ResearchPolicyHistory.page(kind: .resource, value: page))
    }
}
