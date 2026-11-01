import AtlasCore
import Foundation
import XCTest

final class TrendDataTests: XCTestCase {
    func testTimeAxisKeepsRealSpacingStableTiesAndRejectsInvalidDates() {
        let data = TrendData(points: [
            TrendPoint(id: "last", at: 100, rawValue: .number(10)),
            TrendPoint(id: "zero", at: 0, rawValue: .number(0)),
            TrendPoint(id: "tie-a", at: 10, rawValue: .null),
            TrendPoint(id: "tie-b", at: 10, rawValue: .number(5)),
            TrendPoint(id: "bad", at: .infinity, rawValue: .number(5)),
            TrendPoint(id: "negative", at: -1, rawValue: .number(5))
        ], unit: "ms", title: "TTFT")
        XCTAssertEqual(data.points.map(\.id), ["zero", "tie-a", "tie-b", "last"])
        XCTAssertEqual(data.xFraction(1), 0.1, accuracy: 0.00001)
        XCTAssertEqual(data.yFraction(0), 1); XCTAssertNil(data.yFraction(1))
        XCTAssertEqual(data.yFraction(3), 0); XCTAssertEqual(data.knownCount, 3)
    }
    func testZeroMissingAndConstantSeriesNeverInventValuesOrInvalidCoordinates() {
        let data = TrendData(points: [TrendPoint(id: "zero", at: 8, rawValue: .string("0")), TrendPoint(id: "missing", at: 8, rawValue: .null), TrendPoint(id: "invalid", at: 8, rawValue: .bool(false))], unit: "credits", title: "Quota")
        XCTAssertEqual(data.ceiling, 1); XCTAssertEqual(data.xFraction(0), 0.5)
        XCTAssertEqual(data.yFraction(0), 1); XCTAssertNil(data.yFraction(1)); XCTAssertNil(data.yFraction(2))
        XCTAssertEqual(data.minimum, 0); XCTAssertEqual(data.knownCount, 1)
        let empty = TrendData(points: [], unit: "ms", title: "TTFT")
        XCTAssertNil(empty.minimum); XCTAssertNil(empty.nearest(to: 0.5)); XCTAssertEqual(empty.ceiling, 1)
        XCTAssertNil(data.nearest(to: .nan)); XCTAssertEqual(data.nearest(to: -2), 0)
    }
    func testSelectionAndExportPreserveExactDecimalSourceUnitAndUnknown() throws {
        let exact = "0.000000000123456789"
        let context: JSONValue = .object(["source": .string("USER"), "kind": .string("price"), "credential_version": .number(3)])
        let data = TrendData(points: [TrendPoint(id: "exact", at: 1, rawValue: .string(exact)), TrendPoint(id: "missing", at: 2, rawValue: .null), TrendPoint(id: "invalid", at: 3, rawValue: .number(.infinity))], unit: "USD/1K tokens", title: "Price", context: context)
        let value = try JSONValue.parse(data.export.pretty)
        XCTAssertEqual(value["points"].array[0]["value"].string, exact)
        XCTAssertEqual(value["points"].array[1]["value"], .null); XCTAssertEqual(value["points"].array[2]["value"], .null)
        XCTAssertEqual(value["unit"].string, "USD/1K tokens"); XCTAssertEqual(value["context"], context)
        XCTAssertEqual(value["coverage"].string, "LOADED_OBSERVATIONS_ONLY")
    }
    func testKeyboardTraversalIncludesMissingAndSameTimestampSamples() {
        let data = TrendData(points: [TrendPoint(id: "a", at: 1, rawValue: .number(0)), TrendPoint(id: "b", at: 1, rawValue: .null), TrendPoint(id: "c", at: 8, rawValue: .number(5))], unit: "ms", title: "Latency")
        XCTAssertEqual(data.moved(from: nil, by: 1), "a"); XCTAssertEqual(data.moved(from: nil, by: -1), "c")
        XCTAssertEqual(data.moved(from: "a", by: 1), "b"); XCTAssertEqual(data.moved(from: "b", by: 1), "c")
        XCTAssertEqual(data.moved(from: "a", by: -1), "a"); XCTAssertEqual(data.moved(from: "c", by: 1), "c")
        XCTAssertEqual(data.nearest(to: 1), 2)
    }
    func testLocalChartRetainsReceiptIdentityWithoutInventingFallbackReceiptIDs() {
        let scope = ModelScope(.object(["provider_id": .string("p"), "account_id": .string("a"), "credential_id": .string("c"), "model_id": .string("m"), "endpoint": .string("chat")]))
        let detail: JSONValue = .object(["selection": scope.json, "local": .object(["source": .string("MY_KEY_LOCAL"), "history": .array([
            .object(["id": .string("real-sample"), "at": .number(1), "outcome": .string("SUCCESS"), "ttft_ms": .null]),
            .object(["at": .number(2), "outcome": .string("TIMEOUT")]),
            .object(["id": .string("cancelled"), "at": .number(3), "outcome": .string("CANCELLED")])])])])
        let samples = ModelIntelligence(detail: detail, scope: scope).samples
        XCTAssertEqual(samples.count, 2); XCTAssertEqual(samples[0].id, "real-sample"); XCTAssertEqual(samples[0].receiptID, "real-sample")
        XCTAssertNil(samples[0].ttft); XCTAssertTrue(samples[1].receiptID.isEmpty)
        XCTAssertEqual(samples.map { $0.value("success") }, [1, 0])
    }
}
