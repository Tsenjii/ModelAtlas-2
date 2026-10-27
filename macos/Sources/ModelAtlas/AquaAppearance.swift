import AppKit
import SwiftUI

enum AtlasInterfaceChoice: String, CaseIterable, Identifiable {
    case classic, aqua
    var id: String { rawValue }
    var title: String { self == .aqua ? "Aqua · 水光玻璃" : "經典" }
    init(saved: String) { self = Self(rawValue: saved) ?? .classic }
}

enum AtlasWallpaperChoice: String, CaseIterable, Identifiable {
    case kotoha, elaina
    case kotohaChibi = "kotoha-chibi", elainaChibi = "elaina-chibi"
    var id: String { rawValue }
    var title: String {
        switch self {
        case .kotoha: return "Kotoha · 原圖"
        case .elaina: return "伊蕾娜 · 原圖"
        case .kotohaChibi: return "Kotoha · Q版"
        case .elainaChibi: return "伊蕾娜 · Q版"
        }
    }
    var chibi: Bool { self == .kotohaChibi || self == .elainaChibi }
    var filename: String { "aqua-" + rawValue + "-v1." + (chibi ? "png" : "jpg") }
    init(saved: String) { self = Self(rawValue: saved) ?? .kotoha }
}

func atlasNativeAccent(_ choice: AtlasAccentChoice, interface: AtlasInterfaceChoice, scheme: ColorScheme) -> Color {
    guard choice == .graphite && interface == .aqua else { return choice.color }
    return scheme == .dark ? Color(red: 0.60, green: 0.85, blue: 0.95) : Color(red: 0.09, green: 0.39, blue: 0.55)
}

private struct AtlasInterfaceKey: EnvironmentKey { static let defaultValue = AtlasInterfaceChoice.classic }
extension EnvironmentValues {
    var atlasInterface: AtlasInterfaceChoice {
        get { self[AtlasInterfaceKey.self] }
        set { self[AtlasInterfaceKey.self] = newValue }
    }
}

// Only bundled paths are accepted. Classic appearance never invokes the loader.
enum AtlasWallpaperImages {
    private static let cache: NSCache<NSString, NSImage> = {
        let value = NSCache<NSString, NSImage>(); value.countLimit = 2
        value.totalCostLimit = 16 * 1024 * 1024
        return value
    }()
    static func image(_ choice: AtlasWallpaperChoice) -> NSImage? {
        let key = choice.rawValue as NSString
        if let found = cache.object(forKey: key) { return found }
        guard let root = Bundle.main.resourceURL,
              let image = NSImage(contentsOf: root.appendingPathComponent("Wallpapers", isDirectory: true).appendingPathComponent(choice.filename)),
              image.size.width > 0, image.size.height > 0 else { return nil }
        cache.setObject(image, forKey: key, cost: Int(image.size.width * image.size.height * 4))
        return image
    }
}

struct AtlasAquaSurface: View {
    var fallback: Color = atlasSurface
    @Environment(\.atlasInterface) private var style
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceTransparency) private var systemOpaque
    @AppStorage("reduceInterfaceTransparency") private var userOpaque = false
    private var opaque: Bool { systemOpaque || userOpaque }
    var body: some View {
        Group {
            if style == .classic || opaque { fallback }
            else {
                ZStack {
                    Rectangle().fill(.thinMaterial)
                    LinearGradient(colors: scheme == .dark
                        ? [Color(red: 0.11, green: 0.19, blue: 0.28).opacity(0.86), Color(red: 0.09, green: 0.17, blue: 0.25).opacity(0.80)]
                        : [Color.white.opacity(0.80), Color(red: 0.94, green: 0.98, blue: 1).opacity(0.68)],
                        startPoint: .topLeading, endPoint: .bottomTrailing)
                }
            }
        }.allowsHitTesting(false)
    }
}

struct AtlasWallpaperBackdrop: View {
    let style: AtlasInterfaceChoice
    let wallpaper: AtlasWallpaperChoice
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceTransparency) private var systemOpaque
    @AppStorage("reduceInterfaceTransparency") private var userOpaque = false
    private var opaque: Bool { systemOpaque || userOpaque }
    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .bottomTrailing) {
                if style == .aqua {
                    (scheme == .dark ? Color(red: 0.06, green: 0.12, blue: 0.19) : Color(red: 0.91, green: 0.96, blue: 0.99))
                    if !opaque, let image = AtlasWallpaperImages.image(wallpaper) {
                        if wallpaper.chibi {
                            Image(nsImage: image).resizable().scaledToFit()
                                .frame(width: min(geometry.size.width * 0.58, 620), height: min(geometry.size.height * 0.88, 620))
                                .padding(.trailing, 4).padding(.bottom, 36)
                        } else {
                            Image(nsImage: image).resizable().scaledToFill()
                                .frame(width: geometry.size.width, height: geometry.size.height, alignment: .top).clipped()
                        }
                        LinearGradient(colors: scheme == .dark
                            ? [Color(red: 0.06, green: 0.12, blue: 0.19).opacity(0.42), Color(red: 0.06, green: 0.12, blue: 0.19).opacity(0.58)]
                            : [Color.white.opacity(0.18), Color(red: 0.91, green: 0.96, blue: 0.99).opacity(0.48)],
                            startPoint: .top, endPoint: .bottom)
                        AtlasLoadedMarker(name: "aqua-artwork-ready-" + wallpaper.rawValue).frame(width: 0, height: 0)
                    }
                }
                AtlasLoadedMarker(name: "native-interface-" + style.rawValue).frame(width: 0, height: 0)
                if style == .aqua && opaque { AtlasLoadedMarker(name: "aqua-opaque-background").frame(width: 0, height: 0) }
            }.frame(width: geometry.size.width, height: geometry.size.height).clipped()
        }.allowsHitTesting(false).accessibilityHidden(true)
    }
}

struct AtlasWallpaperSettings: View {
    @Binding var interface: String
    @Binding var wallpaper: String
    private var style: AtlasInterfaceChoice { AtlasInterfaceChoice(saved: interface) }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("介面風格")
            HStack(spacing: 10) {
                ForEach(AtlasInterfaceChoice.allCases) { choice in
                    Button { interface = choice.rawValue } label: {
                        Text(choice.title).frame(maxWidth: .infinity, minHeight: 32)
                            .background(AtlasLoadedMarker(name: "native-style-" + choice.rawValue + "-target"))
                    }.buttonStyle(AtlasActionStyle(prominent: style == choice))
                        .accessibilityIdentifier("native-style-" + choice.rawValue)
                        .accessibilityAddTraits(style == choice ? .isSelected : [])
                }
            }
            if style == .aqua {
                Text("背景插畫")
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                    ForEach(AtlasWallpaperChoice.allCases) { choice in
                        Button { wallpaper = choice.rawValue } label: {
                            HStack { Text(choice.title); if AtlasWallpaperChoice(saved: wallpaper) == choice { Image(systemName: "checkmark.circle.fill") } }
                                .frame(maxWidth: .infinity, minHeight: 36)
                                .background(AtlasLoadedMarker(name: "native-wallpaper-" + choice.rawValue + "-target"))
                        }.buttonStyle(AtlasActionStyle())
                            .accessibilityLabel(choice.title).accessibilityIdentifier("native-wallpaper-" + choice.rawValue)
                            .accessibilityAddTraits(AtlasWallpaperChoice(saved: wallpaper) == choice ? .isSelected : [])
                    }
                }
                if let image = AtlasWallpaperImages.image(AtlasWallpaperChoice(saved: wallpaper)) {
                    Image(nsImage: image).resizable().scaledToFit().frame(maxWidth: .infinity).frame(height: 120)
                        .padding(8).background(atlasInset).cornerRadius(12).accessibilityLabel("所選背景預覽")
                } else { Text("背景插畫暫時無法載入。").font(.caption).foregroundStyle(.secondary) }
            }
        }
    }
}
