import AppKit
import SwiftUI

/// AppKit's field editor supplies keyboard navigation on macOS 12 as well as
/// current systems. Only this control handles keys; no global event monitor.
struct ModelPickerSearchField: NSViewRepresentable {
    @Binding var text: String
    let move: (Int) -> Void
    let submit: () -> Void
    let cancel: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeNSView(context: Context) -> NSSearchField {
        let field = NSSearchField()
        field.placeholderString = "搜尋模型、供應商、帳戶、Key 或群組"
        field.identifier = NSUserInterfaceItemIdentifier("model-picker-search")
        field.setAccessibilityLabel("搜尋已加入的模型連線")
        field.font = .systemFont(ofSize: NSFont.systemFontSize)
        field.controlSize = .large
        field.sendsSearchStringImmediately = true
        field.sendsWholeSearchString = false
        field.delegate = context.coordinator
        return field
    }
    func updateNSView(_ field: NSSearchField, context: Context) {
        context.coordinator.parent = self
        if field.stringValue != text, (field.currentEditor() as? NSTextView)?.hasMarkedText() != true {
            field.stringValue = text
        }
        DispatchQueue.main.async {
            guard context.coordinator.needsInitialFocus, let window = field.window else { return }
            context.coordinator.needsInitialFocus = !window.makeFirstResponder(field)
        }
    }
    @MainActor final class Coordinator: NSObject, NSSearchFieldDelegate {
        var parent: ModelPickerSearchField
        var needsInitialFocus = true
        init(_ parent: ModelPickerSearchField) { self.parent = parent }
        func controlTextDidChange(_ notification: Notification) {
            guard let field = notification.object as? NSSearchField else { return }
            parent.text = field.stringValue
        }
        func control(_ control: NSControl, textView: NSTextView, doCommandBy selector: Selector) -> Bool {
            // Arrow / Return / Escape first belong to the input method while
            // composing Chinese or Japanese. Never switch a model on IME commit.
            guard !textView.hasMarkedText() else { return false }
            switch selector {
            case #selector(NSResponder.moveDown(_:)): parent.move(1)
            case #selector(NSResponder.moveUp(_:)): parent.move(-1)
            case #selector(NSResponder.insertNewline(_:)): parent.submit()
            case #selector(NSResponder.cancelOperation(_:)): parent.cancel()
            default: return false
            }
            return true
        }
    }
}
