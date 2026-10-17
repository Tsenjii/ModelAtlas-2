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
        let name = model["metadata"]["display_name"].string
        title = name.isEmpty ? scope.modelID : name
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
