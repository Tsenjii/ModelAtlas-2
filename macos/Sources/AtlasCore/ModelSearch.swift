import Foundation

/// Search presentation data only. Never index arbitrary config or credentials.
public struct ModelSearchEntry: Identifiable, Sendable {
    public let model: JSONValue
    public let scope: ModelScope
    public let title: String
    private let searchable: String
    public var id: String { scope.id }
    public init(model: JSONValue, context: String, operation: String) {
        self.model = model; scope = ModelScope(model)
        let name = model["display_name"].string.trimmingCharacters(in: .whitespacesAndNewlines)
        let legacyName = model["metadata"]["display_name"].string.trimmingCharacters(in: .whitespacesAndNewlines)
        title = !name.isEmpty ? name : !legacyName.isEmpty ? legacyName : scope.modelID
        let aliases = model["metadata"]["aliases"].array.map(\.string).joined(separator: " ")
        searchable = Self.fold([title, model["display_name"].string, model["metadata"]["canonical_id"].string, scope.providerID, scope.accountID, scope.credentialID, scope.modelID,
                                scope.group, scope.endpoint, context, operation, aliases,
                                model["metadata"]["vendor"].string, model["metadata"]["primary_category"].string].joined(separator: " "))
    }
    public func matches(_ query: String) -> Bool {
        Self.fold(query).split(whereSeparator: { $0.isWhitespace }).allSatisfy { searchable.contains($0) }
    }
    public func matchesFuzzy(_ query: String) -> Bool {
        if matches(query) { return true }
        func normalized(_ text: String) -> String { Self.fold(text).filter { $0.isLetter || $0.isNumber } }
        let target = normalized(query)
        guard target.count >= 4, target.count <= 128 else { return false }
        let values = [scope.modelID, title, model["metadata"]["canonical_id"].string] + model["metadata"]["aliases"].array.map(\.string) + model["offer"]["tags"].array.map(\.string)
        return values.map(normalized).contains { value in
            if value.contains(target) { return true }
            guard abs(value.count - target.count) <= 2, value.count <= 128 else { return false }
            let left = Array(value), right = Array(target)
            var row = Array(0...right.count)
            for (i, character) in left.enumerated() {
                var next = [i + 1]
                for j in right.indices { next.append(min(next[j] + 1, row[j + 1] + 1, row[j] + (character == right[j] ? 0 : 1))) }
                row = next
            }
            return row.last! <= 2
        }
    }
    private static func fold(_ value: String) -> String {
        value.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: Locale(identifier: "en_US_POSIX"))
    }
}

/// The highlighted row is a presentation cursor, never the active model.
/// Stable full-scope identities keep filtering and refreshes from choosing a
/// different account merely because it occupies the same list index.
public struct ModelPickerCursor: Sendable {
    public var highlightedID: String?
    public init() {}
    public mutating func reconcile(_ ids: [String], preferredID: String? = nil) {
        if let highlightedID, ids.contains(highlightedID) { return }
        highlightedID = preferredID.flatMap { ids.contains($0) ? $0 : nil } ?? ids.first
    }
    public mutating func move(by step: Int, in ids: [String]) {
        guard !ids.isEmpty else { highlightedID = nil; return }
        guard let highlightedID, let index = ids.firstIndex(of: highlightedID) else {
            self.highlightedID = ids.first; return
        }
        self.highlightedID = ids[max(0, min(ids.count - 1, index + step))]
    }
}

