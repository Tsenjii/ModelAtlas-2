@testable import AtlasCore
import XCTest

final class StreamDeliveryTests: XCTestCase {
    private func text(_ value: String, name: String = "delta") -> StreamEvent { StreamEvent(name: name, value: .object(["text": .string(value)])) }
    func testBackloggedUnicodeStreamKeepsAllTextAndControlOrdering() throws {
        var buffer = StreamEventBuffer()
        var output: [StreamEvent] = []
        let fragments = (0..<4096).map { "繁中\($0)✨" }
        let source = [StreamEvent(name: "start", value: .object(["id": .string("r")]))] + fragments.map { text($0) } + [text("推理", name: "reasoning"), text("後文"), StreamEvent(name: "done", value: .object(["tokens": .number(0)]))]
        for event in source {
            if !(try buffer.append(event)) { output += buffer.drain(); XCTAssertTrue(try buffer.append(event)) }
        }
        output += buffer.drain()
        XCTAssertLessThan(output.count, 50)
        XCTAssertEqual(output.first?.name, "start"); XCTAssertEqual(output.last?.name, "done")
        XCTAssertEqual(output.last?.value["tokens"], JSONValue.number(0))
        XCTAssertEqual(output.filter { $0.name == "delta" }.map { $0.value["text"].string }.joined(), fragments.joined() + "後文")
        let tail = Array(output.suffix(3)); XCTAssertEqual(tail.map(\.name), ["reasoning", "delta", "done"])
        XCTAssertTrue(buffer.drain().isEmpty)
    }
    func testBufferBackpressureAndMetadataNeverDropOrMergeEvents() throws {
        var buffer = StreamEventBuffer(maxBytes: 100)
        let event = text(String(repeating: "a", count: 40))
        XCTAssertTrue(try buffer.append(event)); XCTAssertFalse(try buffer.append(event))
        XCTAssertEqual(buffer.drain().first?.value, event.value)
        XCTAssertTrue(try buffer.append(event)); _ = buffer.drain()
        let metadata = StreamEvent(name: "delta", value: .object(["text": .string("B"), "index": .number(2)]))
        XCTAssertTrue(try buffer.append(text("A"))); XCTAssertTrue(try buffer.append(metadata)); XCTAssertTrue(try buffer.append(text("C")))
        let result = buffer.drain(); XCTAssertEqual(result.count, 3); XCTAssertEqual(result[1].value, metadata.value)
        XCTAssertThrowsError(try buffer.append(text(String(repeating: "x", count: 101))))
    }
    @MainActor func testDeliveryFinishesOrCancelsBeforeReturning() async throws {
        var events: [StreamEvent] = []
        let delivery = StreamDelivery { events.append($0) }
        for index in 0..<500 {
            let event = text("\(index),")
            if !(try delivery.enqueue(event)) { await delivery.finish(); XCTAssertTrue(try delivery.enqueue(event)) }
        }
        XCTAssertTrue(try delivery.enqueue(StreamEvent(name: "done", value: .object([:]))))
        await delivery.finish()
        XCTAssertEqual(events.filter { $0.name == "delta" }.map { $0.value["text"].string }.joined(), (0..<500).map { "\($0)," }.joined())
        XCTAssertEqual(events.last?.name, "done")
        let count = events.count
        let cancelled = StreamDelivery { events.append($0) }
        XCTAssertTrue(try cancelled.enqueue(text("must not arrive")))
        cancelled.cancel(); await cancelled.finish()
        XCTAssertEqual(events.count, count)
        XCTAssertThrowsError(try cancelled.enqueue(text("also rejected")))
    }
}
