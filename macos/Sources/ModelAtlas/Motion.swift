import SwiftUI

/// Short, interruptible transitions for deliberate input. Never attach these
/// to streaming text, telemetry updates, prices, or background refreshes.
struct AtlasMotion {
    var reduced = false
    var navigation: Animation? { reduced ? nil : .easeInOut(duration: 0.18) }
    var sidebar: Animation? { reduced ? nil : .easeInOut(duration: 0.22) }
    var feedback: Animation? { reduced ? nil : .easeOut(duration: 0.12) }
    var panel: Animation? { reduced ? nil : .easeOut(duration: 0.14) }
}
private struct AtlasMotionKey: EnvironmentKey { static let defaultValue = AtlasMotion(reduced: true) }
extension EnvironmentValues {
    var atlasMotion: AtlasMotion {
        get { self[AtlasMotionKey.self] }
        set { self[AtlasMotionKey.self] = newValue }
    }
}
private struct AtlasMotionPreference: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var systemReduced
    @AppStorage("reduceInterfaceMotion") private var userReduced = false
    func body(content: Content) -> some View {
        let reduced = systemReduced || userReduced
        content.environment(\.atlasMotion, AtlasMotion(reduced: reduced))
            .transaction { if reduced { $0.animation = nil; $0.disablesAnimations = true } }
            .background(AtlasLoadedMarker(name: reduced ? "motion-reduced" : "motion-standard").frame(width: 0, height: 0))
    }
}
extension View { func atlasMotionPreferences() -> some View { modifier(AtlasMotionPreference()) } }
