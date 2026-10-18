import AtlasCore
import Foundation
import XCTest

final class ObservationArchiveTests: XCTestCase {
    private let scope = ModelScope(.object(["provider_id": .string("p"), "account_id": .string("a"), "credential_id": .string("same-key"), "model_id": .string("m"), "group": .string("free"), "endpoint": .string("chat")]))
    private func row(_ id: String, at: Double = 1) -> JSONValue {
        var value = scope.json; value["id"] = .string(id); value["observed_at"] = .number(at)
        value["source"] = .string("USER"); value["kind"] = .string("PRICE"); value["status"] = .string("SUCCESS")
        value["facts"] = .object(["price": .object(["input": .string("0.000000000123456789"), "output": .string("0"), "cache": .null, "unit": .string("USD/1K tokens")])])
        return value
    }
    private func raw(_ rows: [JSONValue], total: Int? = nil, cursor: String = "", filter: ObservationFilter = ObservationFilter()) -> JSONValue {
        .object(["selection": scope.json, "query": filter.json, "history": .array(rows), "total": .number(Double(total ?? rows.count)), "next_cursor": .string(cursor), "sources": .array([.string("USER")]), "kinds": .array([.string("PRICE")])])
    }
    func testPageRejectsForeignScopeQueryDuplicateIDsAndMalformedFields() throws {
        let input = raw([row("a")])
        for field in ["provider_id", "account_id", "credential_id", "model_id", "group", "endpoint"] {
            var foreign = input; foreign["history"] = .array([row("a")]); foreign["history"] = .array(foreign["history"].array.map { value in var r = value; r[field] = .string("other"); return r })
            XCTAssertThrowsError(try ObservationPage(foreign, scope: scope, filter: ObservationFilter()), field)
        }
        XCTAssertThrowsError(try ObservationPage(raw([row("same"), row("same")]), scope: scope, filter: ObservationFilter()))
        var wrongQuery = input; wrongQuery["query"]["source"] = .string("OTHER")
        XCTAssertThrowsError(try ObservationPage(wrongQuery, scope: scope, filter: ObservationFilter()))
        for key in ["history", "sources", "kinds", "total", "next_cursor"] {
            var malformed = input; malformed[key] = .bool(false)
            XCTAssertThrowsError(try ObservationPage(malformed, scope: scope, filter: ObservationFilter()), key)
        }
    }
    func testHalfOpenFiltersAndUnknownDecimalFieldsRemainExact() throws {
        let filter = ObservationFilter(source: "USER", kind: "PRICE", category: "PRICE", start: 100, end: 200)
        let page = try ObservationPage(raw([row("a", at: 100)], filter: filter), scope: scope, filter: filter)
        XCTAssertEqual(page.rows[0].facts["price"]["input"].string, "0.000000000123456789")
        XCTAssertEqual(page.rows[0].facts["price"]["output"].string, "0"); XCTAssertEqual(page.rows[0].facts["price"]["cache"], .null)
        XCTAssertThrowsError(try ObservationPage(raw([row("before", at: 99)], filter: filter), scope: scope, filter: filter))
        XCTAssertThrowsError(try ObservationPage(raw([row("end", at: 200)], filter: filter), scope: scope, filter: filter))
        let parameters = Dictionary(uniqueKeysWithValues: filter.query.map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(parameters["start"], "100"); XCTAssertEqual(parameters["end"], "200"); XCTAssertEqual(parameters["category"], "PRICE")
    }
    func testArchiveCanContinueBeyondTwoHundredWithSameTimestampAndNoDuplicates() throws {
        let first = try ObservationPage(raw((0..<200).map { row("r\($0)") }, total: 205, cursor: "next"), scope: scope, filter: ObservationFilter())
        var archive = try ObservationArchiveState(first)
        let last = try ObservationPage(raw((200..<205).map { row("r\($0)") }, total: 205), scope: scope, filter: ObservationFilter())
        try archive.append(last, requestedCursor: "next")
        XCTAssertEqual(archive.rows.count, 205); XCTAssertEqual(Set(archive.rows.map(\.id)).count, 205); XCTAssertTrue(archive.nextCursor.isEmpty)
    }
    func testRejectedContinuationAtomicallyKeepsLastGoodRowsAndCursor() throws {
        var archive = try ObservationArchiveState(ObservationPage(raw([row("a")], total: 3, cursor: "next"), scope: scope, filter: ObservationFilter()))
        let invalid = [raw([row("a")], total: 3, cursor: "third"), raw([row("b")], total: 4, cursor: "third"), raw([row("b")], total: 3, cursor: "next"), raw([row("b")], total: 3)]
        for value in invalid {
            let page = try ObservationPage(value, scope: scope, filter: ObservationFilter())
            XCTAssertThrowsError(try archive.append(page, requestedCursor: "next"))
            XCTAssertEqual(archive.rows.map(\.id), ["a"]); XCTAssertEqual(archive.nextCursor, "next")
        }
        let page = try ObservationPage(raw([row("b"), row("c")], total: 3), scope: scope, filter: ObservationFilter())
        XCTAssertThrowsError(try archive.append(page, requestedCursor: "wrong"))
        try archive.append(page, requestedCursor: "next")
        XCTAssertThrowsError(try archive.append(page, requestedCursor: "next")); XCTAssertEqual(archive.rows.count, 3)
    }
    func testEmptyAndShortFirstPagesRequireHonestTotals() throws {
        let empty = try ObservationArchiveState(ObservationPage(raw([]), scope: scope, filter: ObservationFilter()))
        XCTAssertTrue(empty.rows.isEmpty); XCTAssertEqual(empty.total, 0)
        XCTAssertThrowsError(try ObservationArchiveState(ObservationPage(raw([row("a")], total: 10), scope: scope, filter: ObservationFilter())))
        XCTAssertThrowsError(try ObservationArchiveState(ObservationPage(raw([row("a")], total: 1, cursor: "next"), scope: scope, filter: ObservationFilter())))
    }
}
