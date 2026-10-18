import AppKit
import AtlasCore
import SwiftUI

private func atlasColor(light: CGFloat, dark: CGFloat) -> Color {
    Color(nsColor: NSColor(name: nil, dynamicProvider: { appearance in
        let value = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light
        return NSColor(srgbRed: value, green: value, blue: value, alpha: 1)
    }))
}
let atlasInk = atlasColor(light: 0.18, dark: 0.88)
let atlasAccent = atlasInk
let atlasTextAccent = Color.primary
let atlasCanvas = atlasColor(light: 0.975, dark: 0.105)
let atlasSurface = atlasColor(light: 1, dark: 0.145)
let atlasInset = atlasColor(light: 0.95, dark: 0.185)
let atlasSidebar = atlasColor(light: 0.945, dark: 0.125)
let atlasHairline = Color.primary.opacity(0.10)

struct AtlasCard<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 16) { content }
            .padding(20).frame(maxWidth: .infinity, alignment: .leading)
            .background(atlasSurface).cornerRadius(10)
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(atlasHairline, lineWidth: 0.5))
    }
}

struct EmptyCard: View {
    var icon: String, title: String, detail: String
    var action: String? = nil
    var run: () -> Void = {}
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 24, weight: .regular)).foregroundStyle(.secondary)
            Text(title).font(.system(size: 17, weight: .semibold))
            Text(detail).font(.system(size: 12)).foregroundStyle(.secondary).multilineTextAlignment(.center).frame(maxWidth: 390)
            if let action { Button(action, action: run).buttonStyle(AtlasActionStyle(prominent: true)) }
        }.padding(40).frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct PageHeading: View {
    let eyebrow: String, title: String, subtitle: String
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title).font(.system(size: 24, weight: .semibold))
            Text(subtitle).font(.system(size: 13)).foregroundStyle(.secondary)
        }
    }
}

struct SearchField: View {
    let placeholder: String
    @Binding var text: String
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField(placeholder, text: $text).textFieldStyle(.plain).accessibilityLabel(placeholder)
            if !text.isEmpty {
                Button { text = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                    .buttonStyle(.plain).help("清除搜尋")
            }
        }.font(.system(size: 13)).padding(.horizontal, 11).frame(minHeight: 34).background(atlasInset).cornerRadius(8)
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.primary.opacity(0.04), lineWidth: 0.5))
    }
}

struct ModelMark: View {
    let name: String
    var body: some View {
        Text(String(name.prefix(1)).uppercased()).font(.system(size: 13, weight: .semibold))
            .foregroundStyle(Color.primary.opacity(0.8)).frame(width: 34, height: 34)
            .background(atlasInset).cornerRadius(9)
    }
}

struct AtlasRowButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { Row(configuration: configuration) }
    private struct Row: View {
        let configuration: ButtonStyleConfiguration
        @State private var hovering = false
        @Environment(\.atlasMotion) private var motion
        var body: some View {
            configuration.label.contentShape(Rectangle())
                .background(Color.primary.opacity(configuration.isPressed ? 0.09 : hovering ? 0.035 : 0))
                .onHover { hovering = $0 }
                .animation(motion.feedback, value: hovering)
                .animation(motion.feedback, value: configuration.isPressed)
        }
    }
}

func operationTitle(_ operation: String) -> String {
    ["CHAT_COMPLETIONS": "對話", "RESPONSES": "回應", "ANTHROPIC_MESSAGES": "對話", "GEMINI_NATIVE": "對話",
     "IMAGES_GENERATION": "圖片生成", "IMAGES_EDIT": "圖片編輯", "AUDIO": "音訊轉寫",
     "AUDIO_SPEECH": "語音生成", "GEMINI_SPEECH": "語音生成", "EMBEDDINGS": "向量", "RERANK": "重新排序"][operation] ?? operation
}

struct FormField: View {
    let title: String
    @Binding var text: String
    var secure = false
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.system(size: 12, weight: .medium)).foregroundStyle(.secondary)
            Group {
                if secure { SecureField(title, text: $text) }
                else { TextField(title, text: $text) }
            }.textFieldStyle(.plain).font(.system(size: 13)).padding(.horizontal, 10).frame(height: 34)
                .background(atlasInset).cornerRadius(7)
                .overlay(RoundedRectangle(cornerRadius: 7).stroke(Color.primary.opacity(0.07), lineWidth: 0.5))
        }
    }
}

struct SmallBadge: View {
    var text: String
    var color: Color = .secondary
    var body: some View {
        Text(text).font(.system(size: 10, weight: .medium)).padding(.horizontal, 7).padding(.vertical, 4)
            .foregroundStyle(Color.primary).background(color.opacity(0.075)).cornerRadius(5)
    }
}

struct ActionButton: View {
    let title: String
    var prominent = false
    let action: () async throws -> Void
    @State private var pending = false
    @State private var problem: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                guard !pending else { return }; pending = true; problem = nil
                Task { @MainActor in
                    defer { pending = false }
                    do { try await action() } catch { problem = error.localizedDescription }
                }
            } label: {
                HStack { if pending { ProgressView().controlSize(.small) }; Text(title) }
                    .frame(minHeight: 25)
            }.buttonStyle(AtlasActionStyle(prominent: prominent)).disabled(pending)
            if let problem { Text(problem).font(.callout).foregroundStyle(.red).textSelection(.enabled) }
        }
    }
}

struct AtlasActionStyle: ButtonStyle {
    var prominent = false
    @Environment(\.isEnabled) private var enabled
    @Environment(\.colorScheme) private var scheme
    @Environment(\.atlasAccentChoice) private var accent
    @Environment(\.atlasMotion) private var motion
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 12, weight: .medium))
            .padding(.horizontal, 12).padding(.vertical, 6)
            .foregroundStyle(prominent ? accent.foreground(scheme) : Color.primary)
            .background(prominent ? accent.color : atlasSurface).cornerRadius(7)
            .overlay(RoundedRectangle(cornerRadius: 7).stroke(prominent ? Color.clear : atlasHairline, lineWidth: 0.5))
            .opacity(enabled ? (configuration.isPressed ? 0.7 : 1) : 0.45)
            .animation(motion.feedback, value: configuration.isPressed)
    }
}

struct AtlasTab: Identifiable {
    let id: String
    let title: String
    let icon: String
    init(_ id: String, _ title: String, _ icon: String = "") { self.id = id; self.title = title; self.icon = icon }
}

struct AtlasTabs: View {
    @Binding var selection: String
    let items: [AtlasTab]
    @Environment(\.atlasMotion) private var motion
    @Namespace private var underline
    var body: some View {
        HStack(spacing: 20) {
            ForEach(items) { item in
                Button { selection = item.id } label: {
                    HStack(spacing: 6) {
                        if !item.icon.isEmpty { Image(systemName: item.icon).font(.system(size: 11)) }
                        Text(item.title).font(.system(size: 12, weight: selection == item.id ? .semibold : .medium)).lineLimit(1)
                    }.padding(.horizontal, 2).frame(height: 37)
                        .foregroundStyle(selection == item.id ? Color.primary : Color.secondary)
                        .overlay(alignment: .bottom) {
                            if selection == item.id { Capsule().fill(Color.primary).frame(height: 2).matchedGeometryEffect(id: "selection", in: underline) }
                        }
                }.buttonStyle(.plain).accessibilityAddTraits(selection == item.id ? .isSelected : [])
            }
            Spacer(minLength: 0)
        }.frame(maxWidth: .infinity).overlay(alignment: .bottom) { Rectangle().fill(atlasHairline).frame(height: 0.5) }
            .animation(motion.navigation, value: selection)
    }
}

struct AtlasFilterChip: View {
    let title: String
    var icon = ""
    var selected = false
    let action: () -> Void
    @Environment(\.atlasMotion) private var motion
    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) { if !icon.isEmpty { Image(systemName: icon) }; Text(title) }
                .font(.system(size: 11, weight: .medium)).padding(.horizontal, 10).frame(height: 27)
                .foregroundStyle(selected ? Color.primary : Color.secondary)
                .background(selected ? Color.primary.opacity(0.07) : Color.clear).cornerRadius(6)
        }.buttonStyle(AtlasRowButtonStyle())
            .animation(motion.feedback, value: selected)
    }
}

struct AtlasChoiceMenu: View {
    let title: String
    @Binding var selection: String
    let items: [AtlasTab]
    var body: some View {
        Menu {
            Picker(title, selection: $selection) {
                ForEach(items) { Text($0.title).tag($0.id) }
            }.pickerStyle(.inline)
        } label: {
            Text(items.first(where: { $0.id == selection })?.title ?? title)
                .font(.system(size: 11, weight: .medium)).foregroundStyle(Color.primary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }.menuStyle(.borderlessButton).tint(Color.primary).padding(.horizontal, 9).padding(.vertical, 6)
            .background(atlasInset).cornerRadius(6)
            .accessibilityLabel(title)
    }
}

struct AtlasIconButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 13)).frame(width: 29, height: 29)
            .background(configuration.isPressed ? atlasInset : Color.clear).cornerRadius(7)
            .contentShape(Rectangle())
    }
}

struct JSONTree: View {
    let value: JSONValue
    @State private var limit = 40
    var body: some View {
        Group {
            switch value {
            case .object(let fields):
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(fields.keys.sorted(), id: \.self) { key in
                        if case .object = fields[key]! { DisclosureGroup(label(key)) { AnyView(JSONTree(value: fields[key]!)).padding(.top, 8) } }
                        else if case .array = fields[key]! { DisclosureGroup(label(key)) { AnyView(JSONTree(value: fields[key]!)).padding(.top, 8) } }
                        else { HStack(alignment: .top) { Text(label(key)).foregroundStyle(.secondary).frame(width: 145, alignment: .leading); Text(fields[key]!.display).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading) } }
                    }
                    if fields.isEmpty { Text("尚無資料").foregroundStyle(.secondary) }
                }
            case .array(let values):
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(Array(values.prefix(limit).enumerated()), id: \.offset) { index, item in
                        if case .object = item { DisclosureGroup("\(index + 1) · \(item["name"].string.isEmpty ? item["id"].string : item["name"].string)") { AnyView(JSONTree(value: item)).padding(.top, 8) } }
                        else { Text(item.display).textSelection(.enabled) }
                    }
                    if values.count > limit { Button("繼續顯示（還有 \(values.count - limit) 筆）") { limit += 40 } }
                    if values.isEmpty { Text("尚無紀錄").foregroundStyle(.secondary) }
                }
            default: Text(value.display).textSelection(.enabled)
            }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
    private func label(_ key: String) -> String {
        ["provider_id": "供應商", "account_id": "帳戶", "credential_id": "認證", "model_id": "模型", "group": "群組", "endpoint": "端點", "source": "來源", "observed_at": "觀測時間", "state": "狀態", "status": "狀態", "price_state": "價格狀態", "success_rate": "成功率", "samples": "樣本數", "p50": "中位數", "p90": "第90百分位", "p95": "第95百分位", "ttft_ms": "首 Token（ms）", "latency_ms": "總延遲（ms）", "tps": "生成速度（Token/s）", "notes": "備註", "capabilities": "已確認能力", "metadata": "模型資訊", "latest": "最新觀測", "last_good": "最後有效資料", "external": "外部資料", "local": "本機實測", "provider_facts": "站方資料", "account_facts": "帳戶資料", "resource_pools": "額度資源池", "history": "歷史", "reason": "原因", "confidence": "信心", "outcome": "結果", "created_at": "建立時間"][key] ?? key
    }
}

func openHTTPS(_ string: String) {
    guard let url = URL(string: string), url.scheme == "https", url.user == nil, url.password == nil else { return }
    NSWorkspace.shared.open(url)
}

func copyText(_ text: String) {
    NSPasteboard.general.clearContents(); NSPasteboard.general.setString(text, forType: .string)
}

struct JSONEditor: View {
    let title: String
    @Binding var text: String
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(title).font(.callout.weight(.medium))
            TextEditor(text: $text).font(.system(.body, design: .monospaced)).padding(8)
                .frame(minHeight: 120).background(atlasSurface).cornerRadius(8)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.secondary.opacity(0.2)))
        }
    }
}

private struct AtlasModalGuard: ViewModifier {
    @ObservedObject var store: AtlasStore
    @State private var counted = false
    func body(content: Content) -> some View {
        content.onAppear { if !counted { counted = true; store.additionalSheets += 1 } }
            .onDisappear { if counted { counted = false; store.additionalSheets = max(0, store.additionalSheets - 1) } }
    }
}
extension View {
    func atlasModal(_ store: AtlasStore) -> some View { modifier(AtlasModalGuard(store: store)) }
}

struct AtlasLoadedMarker: NSViewRepresentable {
    let name: String
    func makeNSView(context: Context) -> Marker { let view = Marker(); view.identifier = NSUserInterfaceItemIdentifier(name); return view }
    func updateNSView(_ view: Marker, context: Context) { view.identifier = NSUserInterfaceItemIdentifier(name) }
    final class Marker: NSView { override func hitTest(_ point: NSPoint) -> NSView? { nil } }
}
