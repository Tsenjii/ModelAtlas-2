import Foundation

/// Backpressure is bounded by both wire-event count and encoded bytes. Adjacent
/// text-only deltas may share a UI delivery, while control events and metadata
/// retain their original order and values.
public struct StreamEventBuffer {
    private struct Batch {
        let event: StreamEvent
        var fragments: [String]?
    }
    private var batches: [Batch] = []
    private var bytes = 0
    private var frames = 0
    private let maxBytes: Int
    public init(maxBytes: Int = 2_097_152) { precondition(maxBytes > 0); self.maxBytes = maxBytes }
    public mutating func append(_ event: StreamEvent) throws -> Bool {
        let size = try JSONEncoder().encode(event.value).count + event.name.utf8.count
        guard size <= maxBytes else { throw AtlasError.invalidResponse }
        guard frames < 128, bytes <= maxBytes - size else { return false }
        bytes += size; frames += 1
        let textOnly = ["delta", "reasoning"].contains(event.name) && event.value.fields.count == 1
        if textOnly, case .string(let text) = event.value["text"] {
            if let last = batches.last, last.event.name == event.name, last.fragments != nil {
                batches[batches.count - 1].fragments!.append(text)
            } else { batches.append(Batch(event: event, fragments: [text])) }
        } else { batches.append(Batch(event: event, fragments: nil)) }
        return true
    }
    public mutating func drain() -> [StreamEvent] {
        let result = batches.map { batch in
            batch.fragments.map { StreamEvent(name: batch.event.name, value: .object(["text": .string($0.joined())])) } ?? batch.event
        }
        batches.removeAll(keepingCapacity: true); bytes = 0; frames = 0
        return result
    }
}

// The network parser never waits for a MainActor hop for every token. Exactly
// one pending delivery is scheduled; the bounded buffer applies backpressure
// if rendering falls behind. A final await guarantees no callback outlives the
// stream call, including cancellation and failure.
final class StreamDelivery: @unchecked Sendable {
    private let lock = NSLock()
    private var buffer = StreamEventBuffer()
    private var scheduled = false
    private var cancelled = false
    private var task: Task<Void, Never>?
    private let receive: @MainActor (StreamEvent) -> Void
    init(receive: @escaping @MainActor (StreamEvent) -> Void) { self.receive = receive }

    func enqueue(_ event: StreamEvent) throws -> Bool {
        lock.lock(); defer { lock.unlock() }
        if cancelled { throw CancellationError() }
        guard try buffer.append(event) else { return false }
        if !scheduled {
            scheduled = true
            task = Task { @MainActor [self] in
                for value in take() { receive(value) }
            }
        }
        return true
    }
    private func take() -> [StreamEvent] {
        lock.lock(); defer { lock.unlock() }
        scheduled = false
        return cancelled ? [] : buffer.drain()
    }
    private func currentTask() -> Task<Void, Never>? {
        lock.lock(); defer { lock.unlock() }; return task
    }
    func finish() async { await currentTask()?.value }
    func cancel() {
        lock.lock(); defer { lock.unlock() }; cancelled = true; _ = buffer.drain()
    }
}
