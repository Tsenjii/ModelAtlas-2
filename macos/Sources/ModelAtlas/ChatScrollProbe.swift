import AppKit
import SwiftUI

/// Scoped to the chat's own NSScrollView. Live-scroll notifications distinguish
/// intentional reading from document growth caused by a streaming response.
struct ChatScrollProbe: NSViewRepresentable {
    let observe: (Double, Bool) -> Void
    func makeNSView(context: Context) -> ProbeView {
        let view = ProbeView(); view.observe = observe
        view.identifier = NSUserInterfaceItemIdentifier("chat-scroll-probe")
        return view
    }
    func updateNSView(_ view: ProbeView, context: Context) { view.observe = observe; view.connect() }
    static func dismantleNSView(_ view: ProbeView, coordinator: ()) { view.disconnect() }

    final class ProbeView: NSView {
        var observe: ((Double, Bool) -> Void)?
        private weak var scroll: NSScrollView?
        private var tokens: [NSObjectProtocol] = []
        private var live = false
        override func hitTest(_ point: NSPoint) -> NSView? { nil }
        override func viewDidMoveToWindow() { super.viewDidMoveToWindow(); connect() }
        func connect() {
            guard let enclosing = enclosingScrollView, enclosing !== scroll else { return }
            disconnect(); scroll = enclosing
            enclosing.contentView.postsBoundsChangedNotifications = true
            let center = NotificationCenter.default
            tokens.append(center.addObserver(forName: NSScrollView.willStartLiveScrollNotification, object: enclosing, queue: .main) { [weak self] _ in self?.live = true })
            for name in [NSScrollView.didLiveScrollNotification, NSScrollView.didEndLiveScrollNotification] {
                tokens.append(center.addObserver(forName: name, object: enclosing, queue: .main) { [weak self] event in
                    self?.report(user: true)
                    if event.name == NSScrollView.didEndLiveScrollNotification { self?.live = false }
                })
            }
            tokens.append(center.addObserver(forName: NSView.boundsDidChangeNotification, object: enclosing.contentView, queue: .main) { [weak self] _ in
                self?.report(user: self?.live ?? false)
            })
        }
        private func report(user: Bool) {
            guard let scroll, let document = scroll.documentView else { return }
            let visible = scroll.documentVisibleRect
            let distance = document.isFlipped ? document.bounds.maxY - visible.maxY : visible.minY - document.bounds.minY
            // Notifications may arrive during a SwiftUI layout pass.
            DispatchQueue.main.async { [weak self] in self?.observe?(max(0, Double(distance)), user) }
        }
        func disconnect() { tokens.forEach { NotificationCenter.default.removeObserver($0) }; tokens.removeAll(); scroll = nil; live = false }
        deinit { tokens.forEach { NotificationCenter.default.removeObserver($0) } }
    }
}
