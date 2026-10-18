import AtlasCore
import Foundation
import XCTest

final class AccountCheckinTests: XCTestCase {
    private let day = "2026-10-18", zone = "Asia/Taipei"
    private func row(_ account: String, provider: String = "p", done: Bool = false, required: Bool = true) -> JSONValue {
        .object(["provider_id": .string(provider), "account_id": .string(account), "label": .string(account), "required": .bool(required), "checkin_url": .string("https://example.com/checkin"), "completed_at": done ? .number(1000) : .null, "source": .string(done ? "MANUAL_COMPLETION" : "")])
    }
    private func snapshot(_ rows: [JSONValue]) throws -> AccountCheckinSnapshot {
        try AccountCheckinSnapshot(.object(["date": .string(day), "timezone": .string(zone), "accounts": .array(rows)]), date: day, timezone: zone)
    }
    private func receipt(_ account: CheckinAccount) -> JSONValue {
        var value = account.request(date: day, timezone: zone); value["completed_at"] = .number(2000); value["source"] = .string("MANUAL_COMPLETION"); return value
    }
    func testAccountsWithSameIDAcrossProvidersRemainSeparateAndDuplicatesFail() throws {
        let state = try snapshot([row("same"), row("same", provider: "q")])
        XCTAssertEqual(state.accounts.count, 2); XCTAssertNotEqual(state.accounts[0].id, state.accounts[1].id)
        XCTAssertThrowsError(try snapshot([row("same"), row("same")]))
    }
    func testUnknownCompletionAndDisabledRequirementNeverBecomeCompleted() throws {
        let state = try snapshot([row("pending"), row("done", done: true), row("not-required", required: false)])
        XCTAssertTrue(state.accounts[0].pending); XCTAssertFalse(state.accounts[1].pending); XCTAssertFalse(state.accounts[2].pending)
        XCTAssertNil(state.accounts[0].completedAt); XCTAssertNil(state.accounts[2].completedAt)
    }
    func testMalformedFlagsSourcesAndCompletionValuesAreRejected() throws {
        for (key,value) in [("required",JSONValue.string("true")),("provider_id",.string("")),("account_id",.null),("completed_at",.number(0)),("completed_at",.string("1000")),("source",.bool(false)),("source",.string("OFFICIAL"))] {
            var bad = row("a"); bad[key] = value; XCTAssertThrowsError(try snapshot([bad]),key)
        }
        var bad = row("a",done:true); bad["source"] = .string(""); XCTAssertThrowsError(try snapshot([bad]))
    }
    func testCompletionRequestContainsOnlyExactAccountAndCivilDate() throws {
        let account = try CheckinAccount(row("same",provider:"q")), body = account.request(date:day,timezone:zone)
        XCTAssertEqual(body, .object(["provider_id":.string("q"),"account_id":.string("same"),"date":.string(day),"timezone":.string(zone)]))
    }
    func testForeignOrWrongDateReceiptsAtomicallyPreserveLastGood() throws {
        var state = try snapshot([row("a"),row("b")]);let target = state.accounts[0]
        for (field,value) in [("provider_id",JSONValue.string("other")),("account_id",.string("b")),("date",.string("2026-10-19")),("timezone",.string("UTC")),("source",.string("OFFICIAL")),("completed_at",.null)] {
            var bad = receipt(target);bad[field] = value;XCTAssertThrowsError(try state.apply(bad,account:target));XCTAssertTrue(state.accounts.allSatisfy(\.pending))
        }
    }
    func testConcurrentAccountReceiptsPreserveBothAndOtherProvider() throws {
        var state = try snapshot([row("a"),row("b"),row("a",provider:"q")]);let a = state.accounts[0],b = state.accounts[1]
        try state.apply(receipt(a),account:a);try state.apply(receipt(b),account:b)
        XCTAssertFalse(state.accounts[0].pending);XCTAssertFalse(state.accounts[1].pending);XCTAssertTrue(state.accounts[2].pending)
        try state.apply(receipt(a),account:a);XCTAssertTrue(state.accounts[2].pending)
    }
    func testCivilDateChangesAtLocalMidnightAndNotAtDSTJump() throws {
        let f = ISO8601DateFormatter()
        let before = try XCTUnwrap(f.date(from:"2026-01-01T15:59:59Z")),after = try XCTUnwrap(f.date(from:"2026-01-01T16:00:00Z"))
        XCTAssertEqual(AccountCheckinSnapshot.localDate(before,timezone:zone),"2026-01-01");XCTAssertEqual(AccountCheckinSnapshot.localDate(after,timezone:zone),"2026-01-02")
        XCTAssertEqual(AccountCheckinSnapshot.localDate(after,timezone:"UTC"),"2026-01-01")
        for value in ["2026-03-08T06:59:59Z","2026-03-08T07:00:00Z"] { XCTAssertEqual(AccountCheckinSnapshot.localDate(try XCTUnwrap(f.date(from:value)),timezone:"America/New_York"),"2026-03-08") }
        XCTAssertNil(AccountCheckinSnapshot.localDate(after,timezone:"Invalid/Zone"))
    }
    func testEmptySnapshotRequiresMatchingDateZoneAndAnActualArray() throws {
        XCTAssertTrue(try snapshot([]).accounts.isEmpty)
        let good:JSONValue = .object(["date":.string(day),"timezone":.string(zone),"accounts":.array([])])
        for (field,value) in [("date",JSONValue.string("2026-10-17")),("timezone",.string("UTC")),("accounts",.null)] {
            var bad=good;bad[field]=value;XCTAssertThrowsError(try AccountCheckinSnapshot(bad,date:day,timezone:zone))
        }
    }
}
