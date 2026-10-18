import AppKit
import AtlasCore
import SwiftUI
import UniformTypeIdentifiers

@MainActor struct TrendChartView: View {
    let data: TrendData
    var identifier = "trend"
    var binary = false
    var openReceipt: ((String) -> Void)?
    @State private var selectedID: String?
    @State private var hoveredID: String?
    @State private var exportProblem: String?
    private var point: TrendPoint? { data.points.first { $0.id == (hoveredID ?? selectedID) } ?? data.points.last }
    private var activeIndex: Int? { data.points.firstIndex { $0.id == point?.id } }
    private func date(_ at: Double, seconds: Bool = false) -> String {
        Date(timeIntervalSince1970: at / 1000).formatted(date: .abbreviated, time: seconds ? .standard : .shortened)
    }
    private func axisDate(_ at: Double) -> String {
        let span = (data.points.last?.at ?? at) - (data.points.first?.at ?? at)
        let value = Date(timeIntervalSince1970: at / 1000)
        return span < 3_600_000 ? value.formatted(.dateTime.hour().minute().second()) : date(at)
    }
    private func value(_ point: TrendPoint?) -> String {
        guard let point, let value = point.value else { return "未提供" }
        return binary ? (value == 1 ? "成功" : "失敗") : point.rawValue.display
    }
    private func axis(_ value: Double) -> String { binary ? (value >= 0.5 ? "成功" : "失敗") : String(format: "%.3g", value) }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(data.title).font(.subheadline.weight(.semibold))
                    HStack(alignment: .firstTextBaseline, spacing: 7) {
                        Text(value(point)).font(.system(size: 23, weight: .semibold)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.6).textSelection(.enabled)
                        if !binary { Text(data.unit).font(.caption).foregroundStyle(.secondary) }
                    }
                    Text(point.map { date($0.at, seconds: true) } ?? "尚無觀測").font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 12)
                HStack(spacing: 6) {
                    Button { move(-1) } label: { Image(systemName: "chevron.left") }.help("上一筆觀測").accessibilityLabel("上一筆觀測").disabled(data.points.isEmpty || activeIndex == 0)
                    Button { move(1) } label: { Image(systemName: "chevron.right") }.help("下一筆觀測").accessibilityLabel("下一筆觀測").disabled(data.points.isEmpty || activeIndex == data.points.count - 1)
                    Menu {
                        Button("匯出這組資料…") { save() }.disabled(data.points.isEmpty)
                        Button("清除選取") { selectedID = nil; hoveredID = nil }.disabled(selectedID == nil && hoveredID == nil)
                    } label: { Image(systemName: "ellipsis") }.menuStyle(.borderlessButton).fixedSize().help("圖表選項")
                }.buttonStyle(.borderless)
            }
            if data.knownCount == 0 {
                VStack(spacing: 8) { Image(systemName: "chart.xyaxis.line").font(.title2); Text(data.points.isEmpty ? "這個時間範圍尚無觀測" : "這個指標尚無數值"); Text("缺少數值會保留空白，已記錄的 0 會正常顯示。").font(.caption) }
                    .foregroundStyle(.secondary).frame(maxWidth: .infinity, minHeight: 150)
            } else {
                plot
                HStack {
                    Text(data.points.first.map { axisDate($0.at) } ?? "")
                    Spacer()
                    Text(data.points.last.map { axisDate($0.at) } ?? "")
                }.font(.caption2).foregroundStyle(.secondary).padding(.leading, 48)
            }
            HStack {
                if let point, !point.note.isEmpty { Text(point.note).font(.caption).foregroundStyle(.secondary) }
                Spacer()
                if let point, !point.receiptID.isEmpty, let openReceipt {
                    Button { openReceipt(point.receiptID) } label: { Label("查看這筆請求", systemImage: "doc.text") }.font(.caption)
                }
                Text("\(data.knownCount)／\(data.points.count) 筆有數值").font(.caption).foregroundStyle(.secondary)
            }
            Text("移動滑鼠查看 · 點一下固定 · ← → 切換觀測").font(.caption2).foregroundStyle(.tertiary)
            if let selectedID {
                AtlasLoadedMarker(name: "chart-selected-" + identifier).frame(height: 0)
                AtlasLoadedMarker(name: "chart-point-" + identifier + "-" + selectedID).frame(height: 0)
            }
            if let exportProblem { Text(exportProblem).font(.caption).foregroundStyle(.red) }
        }
        .onChange(of: data.points.map(\.id)) { ids in
            if let selectedID, !ids.contains(selectedID) { self.selectedID = nil }
            hoveredID = nil
        }
    }
    private var plot: some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(alignment: .trailing) {
                Text(axis(data.ceiling)); Spacer()
                if !binary { Text(axis(data.ceiling / 2)); Spacer() }
                Text(axis(0))
            }.font(.caption2.monospacedDigit()).lineLimit(1).minimumScaleFactor(0.7).foregroundStyle(.secondary).frame(width: 38, height: 162).padding(.vertical, 2)
            GeometryReader { geometry in
                ZStack {
                    Path { path in
                        for fraction in (binary ? [0.0, 1.0] : [0.0, 0.5, 1.0]) {
                            let y = 6 + fraction * max(geometry.size.height - 12, 0)
                            path.move(to: CGPoint(x: 0, y: y)); path.addLine(to: CGPoint(x: geometry.size.width, y: y))
                        }
                    }.stroke(Color.primary.opacity(0.10), style: StrokeStyle(lineWidth: 0.5, dash: [3, 4]))
                    if !binary { line(in: geometry.size).stroke(atlasInk.opacity(0.7), style: StrokeStyle(lineWidth: 1.4, lineJoin: .round)) }
                    marks(in: geometry.size).fill(atlasInk)
                    if selectedID != nil || hoveredID != nil, let activeIndex {
                        let x = CGFloat(data.xFraction(activeIndex)) * geometry.size.width
                        Path { path in path.move(to: CGPoint(x: x, y: 0)); path.addLine(to: CGPoint(x: x, y: geometry.size.height)) }
                            .stroke(Color.secondary.opacity(0.5), style: StrokeStyle(lineWidth: 1, dash: [3, 3]))
                        if let y = data.yFraction(activeIndex) {
                            Circle().fill(Color(nsColor: .windowBackgroundColor)).frame(width: 11, height: 11)
                                .overlay(Circle().stroke(atlasInk, lineWidth: 2)).position(x: x, y: 6 + CGFloat(y) * max(geometry.size.height - 12, 0))
                        }
                    }
                    TrendPointer(identifier: identifier, moved: { fraction in
                        guard selectedID == nil else { return }
                        let id = fraction.flatMap { data.nearest(to: $0) }.map { data.points[$0].id }
                        if id != hoveredID { hoveredID = id }
                    }, pinned: { fraction in
                        selectedID = data.nearest(to: fraction).map { data.points[$0].id }; hoveredID = nil
                    }, command: { key in
                        switch key {
                        case 123: move(-1)
                        case 124: move(1)
                        case 115: selectedID = data.points.first?.id; hoveredID = nil
                        case 119: selectedID = data.points.last?.id; hoveredID = nil
                        case 53: guard selectedID != nil || hoveredID != nil else { return false }; selectedID = nil; hoveredID = nil
                        default: return false
                        }
                        return true
                    })
                }
            }.frame(height: 174).accessibilityHidden(true)
        }.padding(.trailing, 8)
    }
    private func position(_ index: Int, in size: CGSize) -> CGPoint? {
        guard let y = data.yFraction(index) else { return nil }
        return CGPoint(x: CGFloat(data.xFraction(index)) * size.width, y: 6 + CGFloat(y) * max(size.height - 12, 0))
    }
    private func line(in size: CGSize) -> Path {
        Path { path in
            var continuing = false
            for index in data.points.indices {
                guard let point = position(index, in: size) else { continuing = false; continue }
                if continuing { path.addLine(to: point) } else { path.move(to: point) }
                continuing = true
            }
        }
    }
    private func marks(in size: CGSize) -> Path {
        Path { path in
            // Dense line series keep every value in the path/selection/export;
            // mark the endpoints instead of covering the line with opaque dots.
            let indices = !binary && data.points.count > 60 ? [0, data.points.count - 1] : Array(data.points.indices)
            for index in indices {
                guard let point = position(index, in: size) else { continue }
                path.addEllipse(in: CGRect(x: point.x - 2, y: point.y - 2, width: 4, height: 4))
            }
        }
    }
    private func move(_ distance: Int) { selectedID = data.moved(from: point?.id, by: distance); hoveredID = nil }
    private func save() {
        let panel = NSSavePanel(); panel.nameFieldStringValue = "ModelAtlas-Chart.json"; panel.allowedContentTypes = [.json]
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do { try Data(data.export.pretty.utf8).write(to: url, options: .atomic); exportProblem = nil }
        catch { exportProblem = "無法儲存圖表資料，請確認檔案位置後重試。" }
    }
}

/// macOS 12 hover/keyboard input. No newer continuous-hover or Charts API is
/// required; keyboard navigation also has labelled SwiftUI button equivalents.
struct TrendPointer: NSViewRepresentable {
    let identifier: String
    let moved: (Double?) -> Void
    let pinned: (Double) -> Void
    let command: (UInt16) -> Bool
    func makeNSView(context: Context) -> TrendPointerView { let view = TrendPointerView(); configure(view); return view }
    func updateNSView(_ view: TrendPointerView, context: Context) { configure(view) }
    private func configure(_ view: TrendPointerView) {
        view.identifier = NSUserInterfaceItemIdentifier("chart-input-" + identifier)
        view.moved = moved; view.pinned = pinned; view.command = command
    }
}
final class TrendPointerView: NSView {
    var moved: (Double?) -> Void = { _ in }
    var pinned: (Double) -> Void = { _ in }
    var command: (UInt16) -> Bool = { _ in false }
    private var area: NSTrackingArea?
    override var isFlipped: Bool { true }
    override var acceptsFirstResponder: Bool { true }
    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let area { removeTrackingArea(area) }
        let next = NSTrackingArea(rect: .zero, options: [.activeInKeyWindow, .inVisibleRect, .mouseMoved, .mouseEnteredAndExited], owner: self, userInfo: nil)
        addTrackingArea(next); area = next
    }
    private func fraction(_ event: NSEvent) -> Double { min(max(Double(convert(event.locationInWindow, from: nil).x / max(bounds.width, 1)), 0), 1) }
    override func mouseMoved(with event: NSEvent) { moved(fraction(event)) }
    override func mouseEntered(with event: NSEvent) { moved(fraction(event)) }
    override func mouseExited(with event: NSEvent) { moved(nil) }
    override func mouseDown(with event: NSEvent) { window?.makeFirstResponder(self); pinned(fraction(event)) }
    override func keyDown(with event: NSEvent) { if !command(event.keyCode) { super.keyDown(with: event) } }
}
