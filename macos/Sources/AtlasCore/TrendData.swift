import Foundation

public struct TrendPoint: Identifiable, Hashable, Sendable {
    public let id: String
    public let at: Double
    public let rawValue: JSONValue
    public let note: String
    public let receiptID: String
    public var value: Double? { ModelIntelligence.number(rawValue) }
    public init(id: String, at: Double, rawValue: JSONValue, note: String = "", receiptID: String = "") {
        self.id = id; self.at = at; self.rawValue = rawValue; self.note = note; self.receiptID = receiptID
    }
}

/// One source/scope/unit per chart. Exact decimal strings survive selection
/// and export; floating point is used only for coordinates.
public struct TrendData: Sendable {
    public let points: [TrendPoint]
    public let unit: String
    public let title: String
    public let context: JSONValue
    public init(points: [TrendPoint], unit: String, title: String, context: JSONValue = .null) {
        self.points = points.enumerated().filter { $0.element.at.isFinite && $0.element.at >= 0 && $0.element.at <= 253_402_300_799_999 }
            .sorted { $0.element.at == $1.element.at ? $0.offset < $1.offset : $0.element.at < $1.element.at }.map(\.element)
        self.unit = unit; self.title = title; self.context = context
    }
    public var knownCount: Int { points.filter { $0.value != nil }.count }
    public var maximum: Double { points.compactMap(\.value).max() ?? 0 }
    public var minimum: Double? { points.compactMap(\.value).min() }
    public var ceiling: Double { maximum > 0 ? maximum : 1 }
    public func xFraction(_ index: Int) -> Double {
        guard points.indices.contains(index), let start = points.first?.at, let end = points.last?.at, end > start else { return 0.5 }
        return (points[index].at - start) / (end - start)
    }
    public func yFraction(_ index: Int) -> Double? {
        guard points.indices.contains(index), let value = points[index].value else { return nil }
        return 1 - value / ceiling
    }
    public func nearest(to fraction: Double) -> Int? {
        guard !points.isEmpty, fraction.isFinite else { return nil }
        let target = min(max(fraction, 0), 1)
        return points.indices.min { abs(xFraction($0) - target) < abs(xFraction($1) - target) }
    }
    public func moved(from id: String?, by distance: Int) -> String? {
        guard !points.isEmpty else { return nil }
        guard let index = points.firstIndex(where: { $0.id == id }) else { return distance < 0 ? points.last?.id : points.first?.id }
        return points[min(max(index + distance, 0), points.count - 1)].id
    }
    public var export: JSONValue {
        .object(["title": .string(title), "unit": .string(unit), "context": context,
                 "coverage": .string("LOADED_OBSERVATIONS_ONLY"), "points": .array(points.map {
                    .object(["id": .string($0.id), "at_ms": .number($0.at), "value": $0.value == nil ? .null : $0.rawValue,
                             "note": .string($0.note), "sample_id": .string($0.receiptID)])
                 })])
    }
}
