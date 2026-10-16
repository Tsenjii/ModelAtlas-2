import AppKit
import AtlasCore
import SwiftUI

let atlasAccent = Color(red: 0.02, green: 0.51, blue: 0.47)
let atlasCanvas = Color(nsColor: .windowBackgroundColor)
let atlasSurface = Color(nsColor: .controlBackgroundColor)

struct AtlasCard<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 14) { content }
            .padding(20).frame(maxWidth: .infinity, alignment: .leading)
            .background(atlasSurface).cornerRadius(16)
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.primary.opacity(0.06)))
    }
}

struct EmptyCard: View {
    var icon: String, title: String, detail: String
    var action: String? = nil
    var run: () -> Void = {}
    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: icon).font(.system(size: 34, weight: .light)).foregroundStyle(atlasAccent)
                .frame(width: 76, height: 76).background(atlasAccent.opacity(0.09)).cornerRadius(24)
            Text(title).font(.title2.weight(.semibold))
            Text(detail).foregroundStyle(.secondary).multilineTextAlignment(.center).frame(maxWidth: 390)
            if let action { Button(action, action: run).buttonStyle(.borderedProminent).controlSize(.large) }
        }.padding(40).frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct PageHeading: View {
    let eyebrow: String, title: String, subtitle: String
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(eyebrow.uppercased()).font(.caption.weight(.semibold)).tracking(1.8).foregroundStyle(atlasAccent)
            Text(title).font(.system(size: 30, weight: .bold, design: .rounded))
            Text(subtitle).foregroundStyle(.secondary)
        }.padding(.bottom, 8)
    }
}

struct FormField: View {
    let title: String
    @Binding var text: String
    var secure = false
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.callout.weight(.medium))
            if secure { SecureField(title, text: $text).textFieldStyle(.roundedBorder) }
            else { TextField(title, text: $text).textFieldStyle(.roundedBorder) }
        }
    }
}

struct SmallBadge: View {
    var text: String
    var color: Color = .secondary
    var body: some View {
        Text(text).font(.caption.weight(.medium)).padding(.horizontal, 9).padding(.vertical, 5)
            .foregroundStyle(color).background(color.opacity(0.1)).cornerRadius(7)
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
            }.disabled(pending).tint(prominent ? atlasAccent : nil)
            if let problem { Text(problem).font(.callout).foregroundStyle(.red).textSelection(.enabled) }
        }
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
        ["provider_id": "供應商", "account_id": "帳戶", "credential_id": "認證", "model_id": "模型", "group": "群組", "endpoint": "端點", "source": "來源", "observed_at": "觀測時間", "state": "狀態", "status": "狀態", "price_state": "價格狀態", "success_rate": "我的上線率", "samples": "樣本數", "p50": "中位數", "p90": "第90百分位", "p95": "第95百分位", "ttft_ms": "首 Token（ms）", "latency_ms": "總延遲（ms）", "tps": "生成速度（Token/s）", "notes": "備註", "capabilities": "已確認能力", "metadata": "模型資訊", "latest": "最新觀測", "last_good": "最後有效資料", "external": "外部資料", "local": "本機實測", "provider_facts": "站方資料", "account_facts": "帳戶資料", "resource_pools": "額度資源池", "history": "歷史", "reason": "原因", "confidence": "信心", "outcome": "結果", "created_at": "建立時間"][key] ?? key
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
