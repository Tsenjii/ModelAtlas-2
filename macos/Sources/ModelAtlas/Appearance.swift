import AppKit
import SwiftUI

enum AtlasAccentChoice: String, CaseIterable, Identifiable {
    case graphite, system, violet, blue
    var id: String { rawValue }
    var title: String { ["graphite": "石墨灰", "system": "跟隨 macOS", "violet": "鳶尾紫", "blue": "靛藍"][rawValue]! }
    var color: Color {
        switch self {
        case .graphite: return atlasInk
        case .system: return Color(nsColor: .controlAccentColor)
        case .violet: return Color(red: 0.40, green: 0.32, blue: 0.78)
        case .blue: return Color(red: 0.22, green: 0.36, blue: 0.70)
        }
    }
    func foreground(_ scheme: ColorScheme) -> Color { self == .graphite && scheme == .dark ? .black : .white }
}

private struct AtlasAccentKey: EnvironmentKey { static let defaultValue = AtlasAccentChoice.graphite }
extension EnvironmentValues {
    var atlasAccentChoice: AtlasAccentChoice {
        get { self[AtlasAccentKey.self] }
        set { self[AtlasAccentKey.self] = newValue }
    }
}
private struct AtlasAppearance: ViewModifier {
    @AppStorage("workspaceAccent") private var saved = "graphite"
    private var choice: AtlasAccentChoice { AtlasAccentChoice(rawValue: saved) ?? .graphite }
    func body(content: Content) -> some View {
        content.environment(\.atlasAccentChoice, choice).tint(choice.color).accentColor(choice.color)
    }
}
extension View { func atlasAppearance() -> some View { modifier(AtlasAppearance()) } }
