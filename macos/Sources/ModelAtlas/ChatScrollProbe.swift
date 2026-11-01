import AppKit
import SwiftUI

/// Keep scrolling inside the chat's own NSScrollView. A coalesced native scroll
/// uses the document's actual laid-out bounds, including completed Markdown.
struct ChatScrollProbe: NSViewRepresentable {
    let following: Bool
    let observe: (Double, Bool) -> Void
    func makeNSView(context: Context) -> ProbeView {
        let view = ProbeView(); view.observe = observe; view.setFollowing(following)
        view.identifier = NSUserInterfaceItemIdentifier("chat-scroll-probe")
        return view
    }
    func updateNSView(_ view: ProbeView, context: Context) {
        view.observe = observe; view.setFollowing(following)
        view.connect(); view.schedulePosition()
    }
    static func dismantleNSView(_ view: ProbeView, coordinator: ()) { view.disconnect() }

    final class ProbeView: NSView {
        var observe: ((Double, Bool) -> Void)?
        private(set) var following = true
        private(set) var readingOffset: CGFloat?
        private var modelFollowing: Bool?
        private weak var scroll: NSScrollView?
        private var tokens: [NSObjectProtocol] = []
        private var live = false, scheduled = false
        private var generation = 0
        func setFollowing(_ value: Bool) {
            // A view update can still carry the model value from before the
            // user's live-scroll notification. Only a changed model value is
            // a command; an unchanged value must not undo that native input.
            guard value != modelFollowing else { return }
            modelFollowing = value
            guard value != following else { return }
            following = value
            if value { readingOffset = nil } else { rememberReadingPosition() }
        }
        private func rememberReadingPosition() {
            guard let scroll, let document = scroll.documentView else { return }
            let visible = scroll.documentVisibleRect
            readingOffset = max(0, document.isFlipped ? visible.minY - document.bounds.minY : document.bounds.maxY - visible.maxY)
        }
        override func hitTest(_ point: NSPoint) -> NSView? { nil }
        override func viewDidMoveToWindow() { super.viewDidMoveToWindow(); connect(); schedulePosition() }
        func connect() {
            guard let enclosing = enclosingScrollView, enclosing !== scroll, let document = enclosing.documentView else { return }
            disconnect(); scroll = enclosing
            if !following { rememberReadingPosition() }
            enclosing.contentView.postsBoundsChangedNotifications = true
            document.postsFrameChangedNotifications = true
            let center = NotificationCenter.default
            tokens.append(center.addObserver(forName: NSScrollView.willStartLiveScrollNotification, object: enclosing, queue: .main) { [weak self] _ in self?.live = true })
            for name in [NSScrollView.didLiveScrollNotification, NSScrollView.didEndLiveScrollNotification] {
                tokens.append(center.addObserver(forName: name, object: enclosing, queue: .main) { [weak self] event in
                    self?.report(user: true)
                    if event.name == NSScrollView.didEndLiveScrollNotification { self?.live = false }
                })
            }
            tokens.append(center.addObserver(forName: NSView.boundsDidChangeNotification, object: enclosing.contentView, queue: .main) { [weak self] _ in
                guard let self else { return }
                self.report(user: self.live); self.schedulePosition()
            })
            tokens.append(center.addObserver(forName: NSView.frameDidChangeNotification, object: document, queue: .main) { [weak self] _ in
                self?.report(user: false); self?.schedulePosition()
            })
        }
        func schedulePosition() {
            guard !live, !scheduled, scroll != nil, following || readingOffset != nil else { return }
            scheduled = true
            let revision = generation
            DispatchQueue.main.async { [weak self] in
                guard let self, self.generation == revision else { return }
                self.scheduled = false
                guard !self.live, let scroll = self.scroll, let document = scroll.documentView else { return }
                document.layoutSubtreeIfNeeded()
                let clip = scroll.contentView
                let maximum = max(0, document.bounds.height - clip.bounds.height)
                let offset = self.following ? maximum : min(maximum, max(0, self.readingOffset ?? 0))
                let y = document.isFlipped ? document.bounds.minY + offset : document.bounds.minY + maximum - offset
                if abs(clip.bounds.minY - y) > 0.5 {
                    clip.scroll(to: NSPoint(x: clip.bounds.minX, y: y))
                    scroll.reflectScrolledClipView(clip)
                }
                self.report(user: false)
            }
        }
        private func report(user: Bool) {
            guard let scroll, let document = scroll.documentView else { return }
            let visible = scroll.documentVisibleRect
            let distance = max(0, document.isFlipped ? document.bounds.maxY - visible.maxY : visible.minY - document.bounds.minY)
            // Stop a queued follow before the next layout, including legacy mice
            // whose didLiveScroll does not have a willStart/didEnd pair.
            if user {
                following = distance <= 48
                if following { readingOffset = nil } else { rememberReadingPosition() }
            }
            let revision = generation
            DispatchQueue.main.async { [weak self] in
                guard let self, self.generation == revision else { return }
                self.observe?(Double(distance), user)
            }
        }
        func disconnect() {
            generation += 1; scheduled = false; readingOffset = nil
            tokens.forEach { NotificationCenter.default.removeObserver($0) }; tokens.removeAll(); scroll = nil; live = false
        }
        deinit { tokens.forEach { NotificationCenter.default.removeObserver($0) } }
    }
}
