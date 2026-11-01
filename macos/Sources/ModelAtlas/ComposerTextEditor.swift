import AppKit
import SwiftUI

/// Native, transparent editor. The field editor owns marked text and undo;
/// incoming state must not replace a Chinese/Japanese composition in progress.
struct ComposerTextEditor: NSViewRepresentable {
    @Binding var text: String
    var focusRequest = 0
    var onHeightChange: (CGFloat) -> Void = { _ in }
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeNSView(context: Context) -> NSScrollView {
        let scroll = ComposerScrollView()
        scroll.identifier = NSUserInterfaceItemIdentifier("chat-composer-scroll")
        scroll.drawsBackground = false; scroll.borderType = .noBorder
        scroll.hasVerticalScroller = false; scroll.autohidesScrollers = true
        let editor = NSTextView()
        editor.identifier = NSUserInterfaceItemIdentifier("chat-composer")
        editor.setAccessibilityLabel("訊息草稿")
        editor.drawsBackground = false; editor.isRichText = false; editor.allowsUndo = true
        editor.font = .systemFont(ofSize: 14); editor.textColor = .labelColor
        editor.textContainerInset = NSSize(width: 4, height: 5)
        editor.isVerticallyResizable = true; editor.isHorizontallyResizable = false
        editor.autoresizingMask = [.width]
        editor.minSize = .zero; editor.maxSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
        editor.textContainer?.containerSize = NSSize(width: 0, height: CGFloat.greatestFiniteMagnitude)
        editor.textContainer?.widthTracksTextView = true
        editor.delegate = context.coordinator
        scroll.documentView = editor
        scroll.onLayout = { [weak coordinator = context.coordinator] scroll in coordinator?.refresh(scroll) }
        return scroll
    }
    func updateNSView(_ scroll: NSScrollView, context: Context) {
        context.coordinator.parent = self
        guard let editor = scroll.documentView as? NSTextView else { return }
        if editor.string != text && !editor.hasMarkedText() {
            let selection = editor.selectedRange()
            editor.string = text
            editor.undoManager?.removeAllActions()
            let length = (text as NSString).length
            editor.setSelectedRange(NSRange(location: min(selection.location, length), length: min(selection.length, max(0, length - min(selection.location, length)))))
        }
        context.coordinator.refresh(scroll)
    }
    @MainActor final class Coordinator: NSObject, NSTextViewDelegate {
        var parent: ComposerTextEditor
        private var lastHeight: CGFloat = 30
        private var heightRevision = 0
        private var focusedRequest = 0
        private var pendingFocus: Int?
        init(_ parent: ComposerTextEditor) { self.parent = parent }
        func textDidChange(_ notification: Notification) {
            guard let editor = notification.object as? NSTextView else { return }
            parent.text = editor.string
            if let scroll = editor.enclosingScrollView { refresh(scroll) }
        }
        func refresh(_ scroll: NSScrollView) {
            guard let editor = scroll.documentView as? NSTextView, editor.bounds.width > 20 else { return }
            measure(editor)
            let request = parent.focusRequest
            guard request > focusedRequest, pendingFocus != request else { return }
            pendingFocus = request
            DispatchQueue.main.async { [weak self, weak scroll] in
                guard let self else { return }; self.pendingFocus = nil
                guard self.parent.focusRequest == request, let scroll,
                      let window = scroll.window, window.attachedSheet == nil,
                      let editor = scroll.documentView as? NSTextView else { return }
                if let current = window.firstResponder as? NSTextView, current.hasMarkedText() { return }
                if window.makeFirstResponder(editor) { self.focusedRequest = request }
            }
        }
        private func measure(_ editor: NSTextView) {
            // TextKit owns incremental layout invalidation. A manual text/frame
            // cache misses container-only reflow and scrollbar layout passes.
            guard let layout = editor.layoutManager, let container = editor.textContainer else { return }
            layout.ensureLayout(for: container)
            let extra = layout.extraLineFragmentTextContainer === container ? layout.extraLineFragmentRect.maxY : 0
            let content = max(layout.usedRect(for: container).maxY, extra)
            let naturalHeight = ceil(content + editor.textContainerInset.height * 2)
            // Growing input needs no scrollbar. Enable it at the height cap,
            // avoiding legacy scrollbar width/height feedback while wrapping.
            if let scroll = editor.enclosingScrollView {
                let needsScroller = naturalHeight > 144
                if scroll.hasVerticalScroller != needsScroller { scroll.hasVerticalScroller = needsScroller }
            }
            let height = min(144, max(30, naturalHeight))
            if abs(lastHeight - height) > 0.5 {
                lastHeight = height; heightRevision += 1
                let revision = heightRevision
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.heightRevision == revision else { return }
                    self.parent.onHeightChange(height)
                }
            }
        }
    }
}

private final class ComposerScrollView: NSScrollView {
    var onLayout: ((NSScrollView) -> Void)?
    override func layout() { super.layout(); onLayout?(self) }
    override func viewDidMoveToWindow() { super.viewDidMoveToWindow(); onLayout?(self) }
}
