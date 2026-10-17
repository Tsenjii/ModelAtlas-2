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

