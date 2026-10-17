import AppKit
import AtlasCore
import SwiftUI

// Opt-in acceptance harness. It uses a separate temporary data directory and a
// real TLS fixture; normal launches never seed data or run any inference.
@MainActor enum NativeSmoke {
    static func run(store: AtlasStore, output: URL) async {
        let manager = FileManager.default
        var stage = "bootstrap"
        do {
            try manager.createDirectory(at: output, withIntermediateDirectories: true)
            guard store.ready, store.api != nil else { throw AtlasError.engineUnavailable }
            let identities: JSONValue = .object(["app_pid": .number(Double(ProcessInfo.processInfo.processIdentifier)), "engine_pid": .number(Double(store.engine.process?.processIdentifier ?? 0))])
            try JSONEncoder().encode(identities).write(to: output.appendingPathComponent("measurement-pids.json"), options: .atomic)
            let args = ProcessInfo.processInfo.arguments
            guard let at = args.firstIndex(of: "--fixture-url"), args.count > at + 1 else { throw AtlasError.invalidEndpoint }
            let fixture = args[at + 1]
            stage = "seed-primary-scope"
            let provider: JSONValue = .object(["id": .string("native-fixture"), "name": .string("本機測試供應商"), "base_url": .string(fixture + "/v1"), "description": .string("原生 Mac 整合測試 · 合成資料"), "auth_mode": .string("BEARER")])
            _ = try await store.mutate("/providers", body: provider)
            _ = try await store.mutate("/accounts", body: .object(["id": .string("primary"), "provider_id": provider["id"], "label": .string("Mac 工作區")]))
            _ = try await store.mutate("/credentials", body: .object(["id": .string("key"), "provider_id": provider["id"], "account_id": .string("primary"), "label": .string("測試用 Key"), "secret": .string("native-fixture-provider-key")]))
            _ = try await store.mutate("/models/sync", body: .object(["provider_id": provider["id"], "account_id": .string("primary"), "credential_id": .string("key")]))
            for id in ["native-fixture-chat", "native-fixture-code"] {
                _ = try await store.mutate("/my-models", body: .object(["provider_id": provider["id"], "account_id": .string("primary"), "credential_id": .string("key"), "model_id": .string(id), "group": .string(""), "endpoint": .string("CHAT_COMPLETIONS")]))
            }
            stage = "resolve-joined-models"
            guard let chat = store.activeModels.first(where: { $0["model_id"].string == "native-fixture-chat" }),
                  let code = store.activeModels.first(where: { $0["model_id"].string == "native-fixture-code" }) else { throw AtlasError.invalidResponse }
            store.draft = "切換模型仍保留的未送出草稿"
            store.attachments = [.object(["filename": .string("draft-only.pdf")])]
            let unsentFiles = store.attachments
            stage = "unsent-draft-and-attachments"
            store.chooseModel(code)
            guard store.draft == "切換模型仍保留的未送出草稿", store.attachments == unsentFiles,
                  store.selectedScope == ModelScope(code), store.sessionID == nil else { throw AtlasError.invalidResponse }
            stage = "native-picker-keyboard-and-ime"
            try await verifyPickerKeyboard(store: store, chat: chat, code: code, files: unsentFiles) { stage = "native-picker-" + $0 }
            store.attachments = []; store.chooseModel(chat)
            store.workspace = .playground; store.draft = "請用繁體中文介紹這個原生 Mac 工作區。"
            stage = "real-tls-chat"
            store.send()
            let deadline = Date().addingTimeInterval(25)
            while store.generating && Date() < deadline { try await Task.sleep(nanoseconds: 30_000_000) }
            guard !store.generating, store.messages.contains(where: { $0["role"].string == "assistant" && $0["content"].string.contains("原生 Mac") }) else { throw AtlasError.invalidResponse }
            guard let conversation = store.selectedSession else { throw AtlasError.invalidResponse }
            stage = "existing-conversation-new-scope"
            store.draft = "這則既有對話的草稿必須保留"
            store.chooseModel(code)
            guard store.sessionID == nil, store.selectedScope == ModelScope(code), store.messages.isEmpty else { throw AtlasError.invalidResponse }
            stage = "restore-existing-draft-and-history"
            await store.selectSession(conversation)
            guard store.draft == "這則既有對話的草稿必須保留", store.selectedScope == ModelScope(chat), store.messages.count == 2 else { throw AtlasError.invalidResponse }
            // Multiple same-model / same-key IDs make scope collisions and long
            // labels visible in real UI. These are isolated synthetic fixtures.
            stage = "seed-distinct-account-and-provider"
            _ = try await store.mutate("/accounts", body: .object(["id": .string("review"), "provider_id": provider["id"], "label": .string("長名稱帳戶 · 團隊文件與程式審查")]))
            _ = try await store.mutate("/credentials", body: .object(["id": .string("key"), "provider_id": provider["id"], "account_id": .string("review"), "label": .string("專案用 Key"), "group": .string("review-team"), "secret": .string("fixture-other-account-key")]))
            var otherScope = ModelScope(chat).json; otherScope["account_id"] = .string("review"); otherScope["group"] = .string("review-team")
            _ = try await store.mutate("/my-models", body: otherScope)
            _ = try await store.mutate("/providers", body: .object(["id": .string("research-fixture"), "name": .string("研究工作區"), "base_url": .string(fixture + "/v1"), "description": .string("獨立帳戶與憑證 · 合成測試資料"), "auth_mode": .string("BEARER")]))
            _ = try await store.mutate("/accounts", body: .object(["id": .string("primary"), "provider_id": .string("research-fixture"), "label": .string("研究帳戶")]))
            _ = try await store.mutate("/credentials", body: .object(["id": .string("key"), "provider_id": .string("research-fixture"), "account_id": .string("primary"), "label": .string("研究用 Key"), "secret": .string("fixture-other-account-key")]))
            _ = try await store.mutate("/models", body: .object(["provider_id": .string("research-fixture"), "id": .string("native-fixture-chat"), "display_name": .string("研究對話模型")]))
            var researchScope = ModelScope(chat).json; researchScope["provider_id"] = .string("research-fixture")
            _ = try await store.mutate("/my-models", body: researchScope)
            var favorite = ModelScope(chat).json; favorite["favorite"] = .bool(true); favorite["state"] = .string("ACTIVE")
            _ = try await store.mutate("/models/lifecycle", body: favorite)
            stage = "provider-scoped-display-name"
            let named = store.modelEntries(store.activeModels).filter { $0.scope.providerID == "research-fixture" }
            guard named.count == 1, named[0].title == "研究對話模型", named[0].matches("研究對話模型 native-fixture-chat"),
                  store.modelEntries(store.activeModels).filter({ $0.scope.providerID == "native-fixture" }).allSatisfy({ $0.title == $0.scope.modelID }) else { throw AtlasError.invalidResponse }
            stage = "exact-search-and-usable-scopes"
            let matches = store.modelEntries(store.activeModels).filter { $0.matches("native-fixture-chat review-team") }
            guard matches.count == 1, matches.first?.scope == ModelScope(otherScope),
                  store.activeModels.filter({ $0["model_id"].string == "native-fixture-chat" }).count == 3 else { throw AtlasError.invalidResponse }
            var invalid = chat; invalid["account_id"] = .string("not-joined")
            stage = "reject-unjoined-scope"
            store.chooseModel(invalid)
            guard store.sessionID == conversation["id"].string, store.draft == "這則既有對話的草稿必須保留" else { throw AtlasError.invalidResponse }
            stage = "native-window-captures"
            guard let window = NSApp.windows.first(where: { $0.isVisible && $0.contentView != nil }) else { throw AtlasError.invalidResponse }
            window.setContentSize(NSSize(width: 1200, height: 800)); window.center(); window.makeKeyAndOrderFront(nil)
            UserDefaults.standard.set("light", forKey: "appearance")
            try await capture(window, to: output.appendingPathComponent("01-playground-light.png"))
            store.workspace = .providers
            try await capture(window, to: output.appendingPathComponent("02-providers-light.png"))
            store.workspace = .models
            try await capture(window, to: output.appendingPathComponent("03-models-light.png"))
            UserDefaults.standard.set("dark", forKey: "appearance")
            try await capture(window, to: output.appendingPathComponent("04-models-dark.png"))
            store.workspace = .playground
            try await capture(window, to: output.appendingPathComponent("05-playground-dark.png"))
            window.setContentSize(NSSize(width: 920, height: 650))
            try await capture(window, to: output.appendingPathComponent("06-playground-compact.png"))
            store.modelPickerOpen = true
            try await capture(window, to: output.appendingPathComponent("07-model-picker-dark.png"), sheet: true)
            UserDefaults.standard.set("light", forKey: "appearance")
            try await capture(window, to: output.appendingPathComponent("08-model-picker-light.png"), sheet: true)
            store.modelPickerOpen = false
            try await Task.sleep(nanoseconds: 450_000_000)
            store.workspace = .models
            try await capture(window, to: output.appendingPathComponent("09-models-compact.png"))
            store.workspace = .providers
            try await capture(window, to: output.appendingPathComponent("10-providers-compact.png"))
            window.setContentSize(NSSize(width: 1200, height: 800))
            store.newProviderOpen = true
            try await capture(window, to: output.appendingPathComponent("11-add-provider-light.png"), sheet: true)
            store.newProviderOpen = false
            try await Task.sleep(nanoseconds: 450_000_000)
            let result: JSONValue = .object(["result": .string("PASS"), "real_engine": .bool(true), "real_tls_chat": .bool(true), "native_windows": .bool(true), "draft_scope_guards": .bool(true), "search_scope_guards": .bool(true), "keyboard_picker_guards": .bool(true), "providers": .number(Double(store.providers.count)), "models": .number(Double(store.myModels.count)), "messages": .number(Double(store.messages.count)), "screenshots": .number(11)])
            try JSONEncoder().encode(result).write(to: output.appendingPathComponent("native-smoke.json"), options: .atomic)
        } catch {
            // Fixed stage labels only: never dump a store, request, credential,
            // prompt or conversation to the public CI failure report.
            let message = "Native smoke failed at " + stage + ": " + error.localizedDescription
            try? Data(message.utf8).write(to: output.appendingPathComponent("FAILURE.txt"), options: .atomic)
        }
        // Leave the SwiftUI .task before entering AppKit termination. Exercise
        // the same delegate-owned drain used by normal Quit / last-window close.
        DispatchQueue.main.async {
            if ProcessInfo.processInfo.arguments.contains("--smoke-close-window"), let window = NSApp.windows.first(where: \.isVisible) { window.performClose(nil) }
            else { NSApp.terminate(nil) }
        }
    }
    private static func verifyPickerKeyboard(store: AtlasStore, chat: JSONValue, code: JSONValue, files: [JSONValue], checkpoint: (String) -> Void) async throws {
        let draft = store.draft
        checkpoint("open-first-sheet")
        var (window, field, _) = try await openPicker(store)
        checkpoint("up-arrow-before-confirm")
        try press(126, "\u{F700}", in: window)
        await Task.yield()
        guard store.selectedScope == ModelScope(code), store.sessionID == nil else { throw AtlasError.invalidResponse }
        try press(36, "\r", in: window)
        checkpoint("up-arrow-confirm")
        try await waitFor { !store.modelPickerOpen }
        guard store.selectedScope == ModelScope(chat), store.draft == draft, store.attachments == files else { throw AtlasError.invalidResponse }
        checkpoint("open-second-sheet")
        (window, field, _) = try await openPicker(store)
        checkpoint("down-arrow-before-confirm")
        try press(125, "\u{F701}", in: window)
        await Task.yield()
        guard store.selectedScope == ModelScope(chat) else { throw AtlasError.invalidResponse }
        try press(36, "\r", in: window)
        checkpoint("down-arrow-confirm")
        try await waitFor { !store.modelPickerOpen }
        guard store.selectedScope == ModelScope(code), store.draft == draft, store.attachments == files else { throw AtlasError.invalidResponse }
        checkpoint("open-empty-search-sheet")
        let opened = try await openPicker(store)
        window = opened.0; field = opened.1
        let editor = opened.2
        editor.selectAll(nil)
        editor.insertText("no-matching-fixture-connection", replacementRange: NSRange(location: NSNotFound, length: 0))
        // Let SwiftUI apply the field editor's genuine change notification.
        try await Task.sleep(nanoseconds: 100_000_000)
        checkpoint("empty-search-rejects-confirm")
        guard field.stringValue == "no-matching-fixture-connection" else { throw AtlasError.invalidResponse }
        try press(36, "\r", in: window)
        guard store.modelPickerOpen, store.selectedScope == ModelScope(code) else { throw AtlasError.invalidResponse }
        checkpoint("marked-text-guard")
        guard let delegate = field.delegate as? ModelPickerSearchField.Coordinator else { throw AtlasError.invalidResponse }
        let composing = NSTextView()
        composing.setMarkedText("注音候選", selectedRange: NSRange(location: 0, length: 0), replacementRange: NSRange(location: NSNotFound, length: 0))
        guard composing.hasMarkedText() else { throw AtlasError.invalidResponse }
        for command in [#selector(NSResponder.moveDown(_:)), #selector(NSResponder.insertNewline(_:)), #selector(NSResponder.cancelOperation(_:))] {
            guard !delegate.control(field, textView: composing, doCommandBy: command) else { throw AtlasError.invalidResponse }
        }
        guard store.modelPickerOpen, store.sessionID == nil, !store.generating,
              store.messages.isEmpty, store.draft == draft, store.attachments == files else { throw AtlasError.invalidResponse }
        checkpoint("escape-closes-sheet")
        try press(53, "\u{1b}", in: window)
        try await waitFor { !store.modelPickerOpen }
    }
    private static func openPicker(_ store: AtlasStore) async throws -> (NSWindow, NSSearchField, NSTextView) {
        // Wait for the preceding sheet to finish detaching before reopening it.
        try await waitFor { !NSApp.windows.contains { $0.attachedSheet != nil } }
        store.modelPickerOpen = true
        try await waitFor {
            NSApp.windows.contains { parent in
                guard let sheet = parent.attachedSheet, let view = sheet.contentView else { return false }
                return searchField(in: view) != nil
            }
        }
        guard let sheet = NSApp.windows.compactMap(\.attachedSheet).first,
              let view = sheet.contentView, let field = searchField(in: view) else { throw AtlasError.invalidResponse }
        sheet.makeKeyAndOrderFront(nil)
        guard sheet.makeFirstResponder(field), let editor = field.currentEditor() as? NSTextView else { throw AtlasError.invalidResponse }
        await Task.yield()
        return (sheet, field, editor)
    }
    private static func searchField(in view: NSView) -> NSSearchField? {
        if let field = view as? NSSearchField, field.identifier?.rawValue == "model-picker-search" { return field }
        for child in view.subviews { if let field = searchField(in: child) { return field } }
        return nil
    }
    private static func press(_ code: UInt16, _ characters: String, in window: NSWindow) throws {
        guard let event = NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
                                           windowNumber: window.windowNumber, context: nil, characters: characters,
                                           charactersIgnoringModifiers: characters, isARepeat: false, keyCode: code) else { throw AtlasError.invalidResponse }
        window.sendEvent(event)
    }
    private static func waitFor(_ condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(4)
        while !condition() {
            guard Date() < deadline else { throw AtlasError.invalidResponse }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
    }
    private static func capture(_ window: NSWindow, to url: URL, sheet: Bool = false) async throws {
        try await Task.sleep(nanoseconds: 450_000_000)
        guard let target = (sheet ? window.attachedSheet : window), let view = target.contentView?.superview else { throw AtlasError.invalidResponse }
        view.layoutSubtreeIfNeeded(); view.displayIfNeeded()
        guard let bitmap = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { throw AtlasError.invalidResponse }
        view.cacheDisplay(in: view.bounds, to: bitmap)
        guard let data = bitmap.representation(using: .png, properties: [:]), data.count > 10_000 else { throw AtlasError.invalidResponse }
        try data.write(to: url, options: .atomic)
    }
}

