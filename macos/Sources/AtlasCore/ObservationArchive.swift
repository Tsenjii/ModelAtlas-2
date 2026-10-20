import Foundation

public struct ObservationFilter: Hashable, Sendable {
    public var source = "", kind = "", category = ""
    public var start: Double?, end: Double?
    public init(source: String = "", kind: String = "", category: String = "", start: Double? = nil, end: Double? = nil) {
        self.source = source; self.kind = kind; self.category = category; self.start = start; self.end = end
    }
    public var json: JSONValue {
        .object(["source": .string(source), "kind": .string(kind), "category": .string(category),
                 "start": start.map(JSONValue.number) ?? .null, "end": end.map(JSONValue.number) ?? .null])
    }
    public var query: [URLQueryItem] {
        var values = [URLQueryItem(name: "limit", value: "200")]
        for (key, value) in [("source", source), ("kind", kind), ("category", category)] where !value.isEmpty { values.append(URLQueryItem(name: key, value: value)) }
        if let start { values.append(URLQueryItem(name: "start", value: String(format: "%.0f", start))) }
        if let end { values.append(URLQueryItem(name: "end", value: String(format: "%.0f", end))) }
        return values
    }
    public func matches(_ row: IntelligenceObservation) -> Bool {
        let price: Bool
        if case .object = row.facts["price"] { price = true }
        else if case .object = row.facts["effective_charge"] { price = true }
        else { price = false }
        return (source.isEmpty || row.source == source) && (kind.isEmpty || row.kind == kind) &&
        (start == nil || row.at >= start!) && (end == nil || row.at < end!) &&
        (category.isEmpty || price)
    }
}

public struct ObservationPage: Sendable {
    public let scope: ModelScope
    public let filter: ObservationFilter
    public let rows: [IntelligenceObservation]
    public let total: Int
    public let nextCursor: String
    public let sources, kinds: [String]
    public init(_ value: JSONValue, scope: ModelScope, filter: ObservationFilter) throws {
        guard ModelScope(value["selection"]) == scope, value["query"] == filter.json,
              let count = value["total"].numeric, count.isFinite, count >= 0, count <= Double(Int.max / 2), count.rounded() == count,
              case .array(let raw) = value["history"], raw.count <= 200,
              case .string(let cursor) = value["next_cursor"], cursor.utf8.count <= 1024,
              case .array(let sourceValues) = value["sources"], case .array(let kindValues) = value["kinds"] else { throw AtlasError.invalidResponse }
        let rows = raw.map(IntelligenceObservation.init)
        guard count >= Double(rows.count), Set(rows.map(\.id)).count == rows.count,
              raw.allSatisfy({ !$0["id"].string.isEmpty && ModelScope($0) == scope && ModelIntelligence.number($0["observed_at"]) != nil }),
              rows.allSatisfy(filter.matches), (cursor.isEmpty || !rows.isEmpty),
              sourceValues.allSatisfy({ if case .string = $0 { return true }; return false }),
              kindValues.allSatisfy({ if case .string = $0 { return true }; return false }) else { throw AtlasError.invalidResponse }
        self.scope = scope; self.filter = filter; self.rows = rows; total = Int(count); nextCursor = cursor
        sources = Array(Set(sourceValues.map(\.string))).sorted(); kinds = Array(Set(kindValues.map(\.string))).sorted()
    }
}

/// Atomic page application: a rejected/repeated/foreign page cannot partially
/// overwrite last-good history. Cursors and totals belong to one frozen query.
public struct ObservationArchiveState: Sendable {
    public private(set) var rows: [IntelligenceObservation]
    public let scope: ModelScope
    public let filter: ObservationFilter
    public let total: Int
    public let sources, kinds: [String]
    public private(set) var nextCursor: String
    private var consumed = Set<String>()
    public init(_ page: ObservationPage) throws {
        guard page.nextCursor.isEmpty ? page.rows.count == page.total : page.rows.count < page.total else { throw AtlasError.invalidResponse }
        scope = page.scope; filter = page.filter; rows = page.rows; total = page.total
        sources = page.sources; kinds = page.kinds; nextCursor = page.nextCursor
    }
    public mutating func append(_ page: ObservationPage, requestedCursor: String) throws {
        let IDs = Set(rows.map(\.id))
        guard !requestedCursor.isEmpty, requestedCursor == nextCursor, !consumed.contains(requestedCursor),
              page.scope == scope, page.filter == filter, page.total == total, !page.rows.isEmpty,
              page.rows.allSatisfy({ !IDs.contains($0.id) }), page.nextCursor != requestedCursor,
              page.nextCursor.isEmpty || !consumed.contains(page.nextCursor),
              rows.count + page.rows.count <= total,
              page.nextCursor.isEmpty ? rows.count + page.rows.count == total : rows.count + page.rows.count < total else { throw AtlasError.invalidResponse }
        rows += page.rows; consumed.insert(requestedCursor); nextCursor = page.nextCursor
    }
}
