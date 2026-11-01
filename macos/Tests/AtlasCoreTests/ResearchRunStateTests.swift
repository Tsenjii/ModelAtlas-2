import AtlasCore
import XCTest

final class ResearchRunStateTests: XCTestCase {
    private var run: JSONValue { .object(["id": .string("retained-run"), "status": .string("BUDGET_EXHAUSTED"), "budget": .number(2), "used": .number(2), "probe_budget": .number(0)]) }
    private var task: JSONValue { .object(["run_id": .string("retained-run"), "status": .string("BUDGET_EXHAUSTED"), "attempts": .number(1), "result": .object([:])]) }
    func testUnknownBudgetNeverGrantsResumeAndExactRunIsRequired() throws {
        XCTAssertThrowsError(try ResearchRunState.control("RESUME", run: run, tasks: [task]))
        var unknown = run; unknown["budget"] = .null
        XCTAssertThrowsError(try ResearchRunState.control("RESUME", run: unknown, tasks: [task], additional: "1"))
        var foreign = task; foreign["run_id"] = .string("other-run")
        XCTAssertThrowsError(try ResearchRunState.control("RESUME", run: run, tasks: [foreign], additional: "1"))
        let body = try ResearchRunState.control("RESUME", run: run, tasks: [task], additional: "1")
        XCTAssertEqual(body["id"], run["id"]); XCTAssertEqual(body["additional_budget"], .number(1)); XCTAssertEqual(body["additional_probes"], .number(0))
        XCTAssertEqual(body["retry_failed"], .bool(false))
    }
    func testStoppedOrExhaustedFailureCannotBorrowRetryPermission() throws {
        var finished = run; finished["status"] = .string("COMPLETED")
        var failed = task; failed["status"] = .string("FAILED")
        XCTAssertTrue(ResearchRunState.resumable(finished, tasks: [failed]))
        XCTAssertThrowsError(try ResearchRunState.control("RESUME", run: finished, tasks: [failed], additional: "1"))
        XCTAssertNoThrow(try ResearchRunState.control("RESUME", run: finished, tasks: [failed], additional: "1", retryFailed: true))
        failed["result"]["aa_progress"] = .object(["stopped_reason": .string("CREDENTIAL_CHANGED")])
        XCTAssertFalse(ResearchRunState.resumable(finished, tasks: [failed]))
        failed["result"] = .object([:]); failed["attempts"] = .number(3)
        XCTAssertFalse(ResearchRunState.resumable(finished, tasks: [failed]))
        failed["attempts"] = .number(1); failed["result"]["brave_progress"] = .object(["stopped_reason": .string("INCONSISTENT_PAGES")])
        XCTAssertFalse(ResearchRunState.resumable(finished, tasks: [failed]))
    }
    func testCompletedSuccessAndCancelledRunDoNotExposeControl() throws {
        var done = run; done["status"] = .string("COMPLETED")
        var success = task; success["status"] = .string("SUCCESS")
        XCTAssertFalse(ResearchRunState.resumable(done, tasks: [success])); XCTAssertFalse(ResearchRunState.controllable(done))
        XCTAssertThrowsError(try ResearchRunState.control("CANCEL", run: done, tasks: [success]))
        done["status"] = .string("CANCELLED")
        XCTAssertFalse(ResearchRunState.resumable(done, tasks: [task]))
        XCTAssertThrowsError(try ResearchRunState.control("RESUME", run: done, tasks: [task], additional: "1"))
    }
    func testAAUpdateHasNoPrivateTargetsOrInferenceAndBoundsRemainEnforced() throws {
        XCTAssertEqual(Set(ResearchRunState.aaUpdate.fields.keys), Set(["refresh_aa", "budget", "probe_budget"]))
        XCTAssertEqual(ResearchRunState.aaUpdate["refresh_aa"], .bool(true)); XCTAssertEqual(ResearchRunState.aaUpdate["budget"], .number(30)); XCTAssertEqual(ResearchRunState.aaUpdate["probe_budget"], .number(0))
        XCTAssertThrowsError(try ResearchRunState.control("RESUME", run: run, tasks: [task], additional: "199"))
        XCTAssertThrowsError(try ResearchRunState.control("RESUME", run: run, tasks: [task], additional: "1", probes: "11"))
        var comparison = run; comparison["query_comparison"] = .bool(true)
        XCTAssertThrowsError(try ResearchRunState.control("RESUME", run: comparison, tasks: [task], additional: "1"))
    }
}
