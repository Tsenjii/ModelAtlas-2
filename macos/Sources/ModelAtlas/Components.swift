import AppKit
import AtlasCore
import SwiftUI

let atlasAccent = Color(red: 0.02, green: 0.51, blue: 0.47)
let atlasTextAccent = Color(nsColor: NSColor(name: nil, dynamicProvider: { appearance in
    appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        ? NSColor(srgbRed: 0.25, green: 0.78, blue: 0.70, alpha: 1)
        : NSColor(srgbRed: 0.0, green: 0.44, blue: 0.41, alpha: 1)
}))
private func atlasColor(light: CGFloat, dark: CGFloat) -> Color {
    Color(nsColor: NSColor(name: nil, dynamicProvider: { appearance in
        let value = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light
        return NSColor(srgbRed: value, green: value, blue: value, alpha: 1)
    }))
}
let atlasCanvas = atlasColor(light: 0.985, dark: 0.105)
let atlasSurface = atlasColor(light: 1, dark: 0.14)
let atlasInset = atlasColor(light: 0.955, dark: 0.18)
let atlasSidebar = atlasColor(light: 0.96, dark: 0.125)
let atlasHairline = Color.primary.opacity(0.085)

struct AtlasCard<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 16) { content }
            .padding(20).frame(maxWidth: .infinity, alignment: .leading)
            .background(atlasSurface).cornerRadius(12)
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(atlasHairline, lineWidth: 0.5))
            .shadow(color: .black.opacity(0.025), radius: 5, y: 2)
    }
}

struct EmptyCard: View {
    var icon: String, title: String, detail: String
    var action: String? = nil
    var run: () -> Void = {}
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: icon).font(.system(size: 29, weight: .light)).foregroundStyle(atlasTextAccent)
                .frame(width: 64, height: 64).background(atlasAccent.opacity(0.07)).cornerRadius(19)
            Text(title).font(.title2.weight(.semibold))
            Text(detail).foregroundStyle(.secondary).multilineTextAlignment(.center).frame(maxWidth: 390)
            if let action { Button(action, action: run).buttonStyle(.borderedProminent).controlSize(.large) }
        }.padding(40).frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct PageHeading: View {
    let eyebrow: String, title: String, subtitle: String
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title).font(.system(size: 25, weight: .bold))
            Text(subtitle).font(.system(size: 12)).foregroundStyle(.secondary)
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
        Text(String(name.prefix(1)).uppercased()).font(.system(size: 16, weight: .semibold, design: .rounded))
            .foregroundStyle(atlasTextAccent).frame(width: 36, height: 36)
            .background(atlasAccent.opacity(0.07)).cornerRadius(10)
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(atlasAccent.opacity(0.09), lineWidth: 0.5))
    }
}

struct AtlasRowButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { Row(configuration: configuration) }
    private struct Row: View {
        let configuration: ButtonStyleConfiguration
        @State private var hovering = false
        var body: some View {
            configuration.label.contentShape(Rectangle())
                .background(atlasAccent.opacity(configuration.isPressed ? 0.12 : hovering ? 0.055 : 0))
                .onHover { hovering = $0 }
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
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 12, weight: .medium))
            .padding(.horizontal, 12).padding(.vertical, 6)
            .foregroundStyle(prominent ? Color.white : Color.primary)
            .background(prominent ? atlasAccent : atlasSurface).cornerRadius(7)
            .overlay(RoundedRectangle(cornerRadius: 7).stroke(prominent ? Color.clear : atlasHairline, lineWidth: 0.5))
            .opacity(enabled ? (configuration.isPressed ? 0.7 : 1) : 0.45)
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
    var body: some View {
        HStack(spacing: 3) {
            ForEach(items) { item in
                Button { selection = item.id } label: {
                    HStack(spacing: 6) {
                        if !item.icon.isEmpty { Image(systemName: item.icon).font(.system(size: 11)) }
                        Text(item.title).font(.system(size: 12, weight: selection == item.id ? .semibold : .medium)).lineLimit(1)
                    }.padding(.horizontal, 13).frame(height: 31).frame(maxWidth: .infinity)
                        .foregroundStyle(selection == item.id ? Color.primary : Color.secondary)
                        .background(selection == item.id ? atlasSurface : Color.clear).cornerRadius(7)
                        .shadow(color: .black.opacity(selection == item.id ? 0.06 : 0), radius: 2, y: 1)
                }.buttonStyle(.plain).accessibilityAddTraits(selection == item.id ? .isSelected : [])
            }
        }.padding(3).background(atlasInset).cornerRadius(9)
    }
}

struct AtlasFilterChip: View {
    let title: String
    var icon = ""
    var selected = false
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) { if !icon.isEmpty { Image(systemName: icon) }; Text(title) }
                .font(.system(size: 11, weight: .medium)).padding(.horizontal, 10).frame(height: 27)
                .foregroundStyle(selected ? atlasTextAccent : Color.secondary)
                .background(selected ? atlasAccent.opacity(0.09) : Color.clear).cornerRadius(6)
        }.buttonStyle(AtlasRowButtonStyle())
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
