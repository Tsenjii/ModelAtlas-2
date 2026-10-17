import AppKit
import CryptoKit
import AtlasCore
import SwiftUI

// Opt-in acceptance harness. It uses a separate temporary data directory and a
// real TLS fixture; normal launches never seed data or run any inference.
@MainActor enum NativeSmoke {
    private static var readingMeasurements: [String: Double] = [:]
    static func run(store: AtlasStore, output: URL) async {
        readingMeasurements = [:]
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
            stage = "live-session-search-retains-current-conversation"
            try await verifySessionSearch(store: store, conversation: conversation)
            stage = "native-chat-settings-feedback"
            try await verifyChatSettings(store: store, code: code, output: output) { stage = "native-settings-" + $0 }
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
            stage = "filtered-picker-native-captures"
            let (pickerWindow, pickerField, pickerEditor) = try await openPicker(store)
            pickerEditor.selectAll(nil)
            pickerEditor.insertText("native-fixture-chat review-team", replacementRange: NSRange(location: NSNotFound, length: 0))
            try await capture(window, to: output.appendingPathComponent("19-model-picker-filtered-light.png"), sheet: true)
            guard pickerField.stringValue == "native-fixture-chat review-team", store.sessionID == conversation["id"].string,
                  store.draft == "這則既有對話的草稿必須保留" else { throw AtlasError.invalidResponse }
            pickerEditor.selectAll(nil)
            pickerEditor.insertText("no-matching-fixture-connection", replacementRange: NSRange(location: NSNotFound, length: 0))
            try await capture(window, to: output.appendingPathComponent("20-model-picker-empty-light.png"), sheet: true)
            try press(36, "\r", in: pickerWindow)
            guard store.modelPickerOpen, store.sessionID == conversation["id"].string else { throw AtlasError.invalidResponse }
            try press(53, "\u{1b}", in: pickerWindow)
            try await waitFor { !store.modelPickerOpen }
            store.sessionSearch = "no-matching-fixture-conversation"
            try await waitFor { !store.sessionsLoading && store.sessions.isEmpty }
            try await capture(window, to: output.appendingPathComponent("21-conversation-search-empty-light.png"))
            store.sessionSearch = ""
            try await waitFor { !store.sessionsLoading && store.sessions.contains { $0["id"].string == conversation["id"].string } }
            store.workspace = .models
            try await capture(window, to: output.appendingPathComponent("09-models-compact.png"))
            store.workspace = .providers
            try await capture(window, to: output.appendingPathComponent("10-providers-compact.png"))
            window.setContentSize(NSSize(width: 1200, height: 800))
            store.newProviderOpen = true
            try await capture(window, to: output.appendingPathComponent("11-add-provider-light.png"), sheet: true)
            store.newProviderOpen = false
            try await Task.sleep(nanoseconds: 450_000_000)
            stage = "chat-reading-markdown-attachments-receipt"
            try await verifyChatExperience(store: store, code: code, window: window, output: output, fixture: fixture) { stage = "chat-experience-" + $0 }
            let result: JSONValue = .object(["result": .string("PASS"), "real_engine": .bool(true), "real_tls_chat": .bool(true), "native_windows": .bool(true), "draft_scope_guards": .bool(true), "search_scope_guards": .bool(true), "keyboard_picker_guards": .bool(true), "live_session_search": .bool(true), "chat_settings_guards": .bool(true), "providers": .number(Double(store.providers.count)), "models": .number(Double(store.myModels.count)), "messages": .number(Double(store.messages.count)), "screenshots": .number(25), "chat_reading_guards": .bool(true), "attachment_guards": .bool(true), "request_receipt": .bool(true), "native_markdown": .bool(true)])
            try JSONEncoder().encode(result).write(to: output.appendingPathComponent("native-smoke.json"), options: .atomic)
        } catch {
            // Fixed stage labels only: never dump a store, request, credential,
            // prompt or conversation to the public CI failure report.
            var diagnostics: JSONValue = .object(["stage": .string(stage), "generating": .bool(store.generating), "stream_characters": .number(Double(store.streamingText.count)), "message_count": .number(Double(store.messages.count)), "has_problem": .bool(store.problem != nil), "following": .bool(store.chatReading.following), "at_latest": .bool(store.chatReading.atLatest)])
            for (key, value) in readingMeasurements { diagnostics[key] = .number(value) }
            if let root = NSApp.windows.first(where: \.isVisible)?.contentView,
               let probe = findView(in: root, identifier: "chat-scroll-probe"), let scroll = probe.enclosingScrollView, let document = scroll.documentView {
                diagnostics["document_height"] = .number(Double(document.bounds.height))
                diagnostics["document_flipped"] = .bool(document.isFlipped)
                diagnostics["clip_y"] = .number(Double(scroll.contentView.bounds.minY))
                diagnostics["clip_height"] = .number(Double(scroll.contentView.bounds.height))
                diagnostics["visible_y"] = .number(Double(scroll.documentVisibleRect.minY))
                if let offset = (probe as? ChatScrollProbe.ProbeView)?.readingOffset { diagnostics["retained_reading_offset"] = .number(Double(offset)) }
            }
            try? JSONEncoder().encode(diagnostics).write(to: output.appendingPathComponent("failure-state.json"), options: .atomic)
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
    private static func verifyChatExperience(store: AtlasStore, code: JSONValue, window: NSWindow, output: URL, fixture: String, checkpoint: (String) -> Void) async throws {
        checkpoint("start-real-rich-stream")
        store.chooseModel(code); store.draft = "請示範原生 Markdown、Swift 程式碼及比較表。"
        store.send()
        let streamDeadline = Date().addingTimeInterval(15)
        while store.generating && store.streamingText.count < 650 && Date() < streamDeadline { try await Task.sleep(nanoseconds: 30_000_000) }
        guard store.generating else { checkpoint("rich-stream-ended-before-release"); throw AtlasError.invalidResponse }
        guard store.streamingText.count >= 650 else { checkpoint("rich-stream-threshold"); throw AtlasError.invalidResponse }
        checkpoint("fixture-prefix-reached-barrier")
        guard let stateURL = URL(string: fixture + "/fixture/rich-state") else { throw AtlasError.invalidEndpoint }
        var fixtureState: JSONValue = .null
        let barrierDeadline = Date().addingTimeInterval(10)
        while Date() < barrierDeadline {
            let (data, response) = try await URLSession.shared.data(from: stateURL)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw AtlasError.invalidResponse }
            fixtureState = try JSONDecoder().decode(JSONValue.self, from: data)
            if fixtureState["paused"].boolean { break }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        guard fixtureState["paused"].boolean else { throw AtlasError.invalidResponse }
        checkpoint("find-real-scroll-view")
        guard let root = window.contentView, let probe = findView(in: root, identifier: "chat-scroll-probe"), let scroll = probe.enclosingScrollView,
              let document = scroll.documentView, document.bounds.height > scroll.contentView.bounds.height + 100 else { throw AtlasError.invalidResponse }
        checkpoint("pause-reading-with-live-scroll")
        let center = NotificationCenter.default
        center.post(name: NSScrollView.willStartLiveScrollNotification, object: scroll)
        scroll.contentView.scroll(to: NSPoint(x: 0, y: document.isFlipped ? 0 : max(0, document.bounds.height - scroll.contentView.bounds.height)))
        scroll.reflectScrolledClipView(scroll.contentView)
        center.post(name: NSScrollView.didLiveScrollNotification, object: scroll)
        center.post(name: NSScrollView.didEndLiveScrollNotification, object: scroll)
        checkpoint("user-scroll-survives-stale-model-update")
        guard let nativeProbe = probe as? ChatScrollProbe.ProbeView else { throw AtlasError.invalidResponse }
        nativeProbe.setFollowing(store.chatReading.following)
        guard !nativeProbe.following, (nativeProbe.readingOffset ?? .infinity) < 3 else { throw AtlasError.invalidResponse }
        try await waitFor { !store.chatReading.following && !store.chatReading.atLatest && transcriptOffset(scroll) < 3 }
        // Compare content-relative positions on both sides. The clip view's
        // raw coordinates can change while its document is being laid out.
        let readingPosition = transcriptOffset(scroll), textCount = store.streamingText.count
        readingMeasurements = ["baseline_content_offset": Double(readingPosition), "baseline_clip_y": Double(scroll.contentView.bounds.minY), "baseline_visible_y": Double(scroll.documentVisibleRect.minY), "baseline_document_y": Double(document.bounds.minY), "baseline_characters": Double(textCount)]
        checkpoint("release-rich-fixture")
        guard let releaseURL = URL(string: fixture + "/fixture/release-rich") else { throw AtlasError.invalidEndpoint }
        var release = URLRequest(url: releaseURL); release.httpMethod = "POST"
        let (_, releaseResponse) = try await URLSession.shared.data(for: release)
        guard (releaseResponse as? HTTPURLResponse)?.statusCode == 204 else { throw AtlasError.invalidResponse }
        try await Task.sleep(nanoseconds: 500_000_000)
        readingMeasurements["after_content_offset"] = Double(transcriptOffset(scroll))
        readingMeasurements["after_clip_y"] = Double(scroll.contentView.bounds.minY)
        readingMeasurements["after_characters"] = Double(store.streamingText.count)
        checkpoint("stream-advances-after-release")
        guard store.streamingText.count > textCount else { throw AtlasError.invalidResponse }
        checkpoint("stream-stays-paused")
        guard !store.chatReading.following else { throw AtlasError.invalidResponse }
        checkpoint("stream-holds-content-reading-offset")
        guard abs(transcriptOffset(scroll) - readingPosition) < 3 else { throw AtlasError.invalidResponse }
        checkpoint("finish-rich-stream")
        let finish = Date().addingTimeInterval(25)
        while store.generating && Date() < finish { try await Task.sleep(nanoseconds: 30_000_000) }
        checkpoint("completed-rich-history")
        guard !store.generating, !store.chatReading.following,
              let answer = store.messages.last, answer["role"].string == "assistant" else { throw AtlasError.invalidResponse }
        let answerText = answer["content"].string
        let digest = SHA256.hash(data: Data(answerText.utf8)).map { String(format: "%02x", $0) }.joined()
        checkpoint("complete-rich-text-sha256")
        guard digest == fixtureState["sha256"].string, Double(answerText.count) == fixtureState["expected_characters"].numeric else { throw AtlasError.invalidResponse }
        let blocks = MessageMarkup.parse(answer["content"].string)
        checkpoint("native-code-and-table-blocks")
        guard blocks.contains(where: { if case .code = $0 { return true }; return false }),
              blocks.contains(where: { if case .table = $0 { return true }; return false }) else { throw AtlasError.invalidResponse }
        checkpoint("resume-latest")
        store.chatReading.resume()
        try await waitFor { store.chatReading.atLatest }
        guard scroll.documentVisibleRect.height > 0 else { throw AtlasError.invalidResponse }
        // Use the real scroll notifications again to inspect the start of the
        // completed, natively rendered reply without changing stored content.
        center.post(name: NSScrollView.willStartLiveScrollNotification, object: scroll)
        scroll.contentView.scroll(to: NSPoint(x: 0, y: document.isFlipped ? 0 : max(0, document.bounds.height - scroll.contentView.bounds.height)))
        scroll.reflectScrolledClipView(scroll.contentView)
        center.post(name: NSScrollView.didLiveScrollNotification, object: scroll)
        center.post(name: NSScrollView.didEndLiveScrollNotification, object: scroll)
        nativeProbe.setFollowing(store.chatReading.following)
        guard !nativeProbe.following, (nativeProbe.readingOffset ?? .infinity) < 3 else { throw AtlasError.invalidResponse }
        try await waitFor { !store.chatReading.following && !store.chatReading.atLatest && transcriptOffset(scroll) < 3 }
        UserDefaults.standard.set("light", forKey: "appearance")
        checkpoint("capture-rich-light")
        try await capture(window, to: output.appendingPathComponent("12-chat-markdown-light.png"))
        guard transcriptOffset(scroll) < 3 else { throw AtlasError.invalidResponse }
        UserDefaults.standard.set("dark", forKey: "appearance")
        try await capture(window, to: output.appendingPathComponent("13-chat-markdown-dark.png"))
        guard transcriptOffset(scroll) < 3 else { throw AtlasError.invalidResponse }
        checkpoint("exact-message-request-receipt")
        let sample = answer["sample_id"].string
        guard !sample.isEmpty, let api = store.api else { throw AtlasError.invalidResponse }
        let receipt = try await api.get("/telemetry/detail", query: [URLQueryItem(name: "id", value: sample)])
        guard receipt["id"].string == sample, receipt["session_id"].string == store.sessionID,
              ModelScope(receipt) == store.selectedScope, receipt["outcome"].string == "SUCCESS" else { throw AtlasError.invalidResponse }
        store.requestReceipt = sample
        checkpoint("wait-for-rendered-exact-receipt")
        try await waitFor {
            guard let sheet = window.attachedSheet, let content = sheet.contentView else { return false }
            return findView(in: content, identifier: "receipt-loaded-" + sample) != nil
        }
        try await capture(window, to: output.appendingPathComponent("14-chat-request-receipt.png"), sheet: true)
        store.requestReceipt = nil
        try await waitFor { window.attachedSheet == nil }
        checkpoint("attachment-production-caller")
        let file = output.appendingPathComponent("attachment-fixture.pdf")
        try Data("%PDF-1.7\nNative attachment fixture".utf8).write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        let beforeDraft = store.draft, scope = store.selectedScope
        await store.addAttachments([file])
        guard store.attachments.count == 1, store.attachments[0]["filename"].string == file.lastPathComponent,
              store.selectedScope == scope, store.draft == beforeDraft else { throw AtlasError.invalidResponse }
        let beforeFiles = store.attachments
        await store.addAttachments([file])
        guard store.attachments == beforeFiles, store.problem == LocalAttachmentError.duplicate.localizedDescription else { throw AtlasError.invalidResponse }
        store.problem = nil
        try await capture(window, to: output.appendingPathComponent("15-chat-attachment-dark.png"))
        store.attachments = []
        window.setContentSize(NSSize(width: 920, height: 650))
        try await capture(window, to: output.appendingPathComponent("16-chat-markdown-compact.png"))
        checkpoint("capture-native-table")
        window.setContentSize(NSSize(width: 1200, height: 780))
        UserDefaults.standard.set("light", forKey: "appearance")
        // Move the real viewport below the code block for table/quote review.
        // The original sixteen window/sheet captures remain mandatory.
        document.layoutSubtreeIfNeeded()
        center.post(name: NSScrollView.willStartLiveScrollNotification, object: scroll)
        let tableOffset: CGFloat = 400
        let tableY = document.isFlipped ? document.bounds.minY + tableOffset : document.bounds.maxY - scroll.contentView.bounds.height - tableOffset
        scroll.contentView.scroll(to: NSPoint(x: 0, y: tableY))
        scroll.reflectScrolledClipView(scroll.contentView)
        center.post(name: NSScrollView.didLiveScrollNotification, object: scroll)
        center.post(name: NSScrollView.didEndLiveScrollNotification, object: scroll)
        try await waitFor { abs(transcriptOffset(scroll) - tableOffset) < 3 }
        try await capture(window, to: output.appendingPathComponent("17-chat-table-light.png"))
        guard abs(transcriptOffset(scroll) - tableOffset) < 3 else { throw AtlasError.invalidResponse }
        UserDefaults.standard.set("dark", forKey: "appearance")
        try await capture(window, to: output.appendingPathComponent("18-chat-table-dark.png"))
        guard abs(transcriptOffset(scroll) - tableOffset) < 3 else { throw AtlasError.invalidResponse }
        checkpoint("new-conversation-resets-reading")
        store.newConversation()
        guard store.chatReading == ChatReadingState(), store.messages.isEmpty else { throw AtlasError.invalidResponse }
        checkpoint("real-attachment-inference-strict-decoder")
        let codeScope = ModelScope(code)
        guard let attachmentModel = store.activeModels.first(where: {
            let value = ModelScope($0)
            return value.providerID == codeScope.providerID && value.accountID == codeScope.accountID && value.credentialID == codeScope.credentialID && value.modelID == "native-fixture-chat"
        }) else { throw AtlasError.invalidResponse }
        var capability = ModelScope(attachmentModel).json
        capability["confirm"] = .bool(true); capability["capabilities"] = .array([.string("FILES")])
        _ = try await store.mutate("/models/capabilities", body: capability)
        store.chooseModel(attachmentModel)
        await store.addAttachments([file])
        store.draft = "請讀取這份本機測試附件。"; store.send()
        let attachmentDeadline = Date().addingTimeInterval(15)
        while store.generating && Date() < attachmentDeadline { try await Task.sleep(nanoseconds: 30_000_000) }
        guard !store.generating, store.problem == nil, store.messages.count == 2,
              store.messages.first?["attachments"].array.first?["filename"].string == file.lastPathComponent,
              store.messages.last?["role"].string == "assistant" else { throw AtlasError.invalidResponse }
        // Keep final report's existing exact two-message assertion and verify
        // that rendering and attachment validation never rewrote the history.
        let sessions = try await api.get("/sessions", query: [URLQueryItem(name: "state", value: "ALL")]).requiredArray()
        guard let saved = sessions.first(where: { $0["id"].string == receipt["session_id"].string }) else { throw AtlasError.invalidResponse }
        await store.selectSession(saved)
        guard store.messages.last?["content"].string == answer["content"].string, store.messages.count == 2 else { throw AtlasError.invalidResponse }
    }
    private static func verifyChatSettings(store: AtlasStore, code: JSONValue, output: URL, checkpoint: (String) -> Void) async throws {
        guard let api = store.api, let original = store.selectedSession,
              let window = NSApp.windows.first(where: { $0.isVisible && $0.contentView != nil }) else { throw AtlasError.invalidResponse }
        let originalID = original["id"].string, originalDraft = store.draft, originalMessages = store.messages
        store.openChatSettings()
        guard let model = store.chatSettings, model.sessionID == originalID else { throw AtlasError.invalidResponse }
        checkpoint("ready")
        try await waitFor { model.loaded && !model.loading && window.attachedSheet != nil }
        UserDefaults.standard.set("light", forKey: "appearance")
        try await capture(window, to: output.appendingPathComponent("22-chat-settings-ready-light.png"), sheet: true)
        checkpoint("real-401-and-retry")
        let denied = try AtlasClient(baseURL: api.baseURL, token: "native-settings-invalid-token")
        await model.load(api: denied, tools: nil)
        guard !model.loading, !model.loaded, model.loadProblem != nil else { throw AtlasError.invalidResponse }
        UserDefaults.standard.set("dark", forKey: "appearance")
        try await capture(window, to: output.appendingPathComponent("24-chat-settings-read-error-dark.png"), sheet: true)
        await model.load(api: api, tools: store.stagedTools[originalID])
        guard model.loaded, model.loadProblem == nil else { throw AtlasError.invalidResponse }
        checkpoint("invalid-token-never-writes")
        let before = try await api.get("/sessions/settings", query: [URLQueryItem(name: "session_id", value: originalID)])
        model.draft.maxTokens = "0"
        await model.save(store: store)
        let after = try await api.get("/sessions/settings", query: [URLQueryItem(name: "session_id", value: originalID)])
        guard before == after, model.draft.maxTokens == "0", model.saveProblem == ChatSettingsValidation.tokenLimit.localizedDescription else { throw AtlasError.invalidResponse }
        UserDefaults.standard.set("light", forKey: "appearance")
        try await capture(window, to: output.appendingPathComponent("23-chat-settings-validation-light.png"), sheet: true)
        checkpoint("exact-session-save-after-context-change")
        let second = try await api.call("/sessions", method: "POST", body: ModelScope(code).json)
        await store.selectSession(second)
        model.draft.maxTokens = "777"; model.draft.system = "原對話設定 · 合成驗證"; model.draft.streaming = false
        model.draft.title = "已儲存的原對話設定"
        let submitted = model.draft
        await model.save(store: store)
        let saved = try await api.get("/sessions/settings", query: [URLQueryItem(name: "session_id", value: originalID)])
        let untouched = try await api.get("/sessions/settings", query: [URLQueryItem(name: "session_id", value: second["id"].string)])
        let exact = try await api.get("/sessions", query: [URLQueryItem(name: "session_id", value: originalID), URLQueryItem(name: "state", value: "ALL")]).requiredArray()
        guard saved["max_tokens"].numeric == 777, saved["system"].string == submitted.system, !saved["streaming"].boolean,
              untouched["max_tokens"].numeric == 512, untouched["system"].string.isEmpty,
              exact.first?["title"].string == submitted.title, model.savedDraft == submitted, model.saveProblem == nil,
              store.sessionID == second["id"].string else { throw AtlasError.invalidResponse }
        checkpoint("next-draft-keeps-saved-feedback")
        model.draft.maxTokens = "888"
        guard model.hasUnsavedChanges, model.savedDraft != model.draft, store.chatSettings === model else { throw AtlasError.invalidResponse }
        UserDefaults.standard.set("dark", forKey: "appearance")
        try await capture(window, to: output.appendingPathComponent("25-chat-settings-next-draft-dark.png"), sheet: true)
        store.chatSettings = nil
        try await waitFor { window.attachedSheet == nil }
        guard let restored = exact.first else { throw AtlasError.invalidResponse }
        await store.selectSession(restored)
        guard store.draft == originalDraft, store.messages == originalMessages, store.selectedScope == ModelScope(original),
              store.problem == nil else { throw AtlasError.invalidResponse }
        UserDefaults.standard.set("light", forKey: "appearance")
    }
    private static func transcriptOffset(_ scroll: NSScrollView) -> CGFloat {
        guard let document = scroll.documentView else { return .infinity }
        let visible = scroll.documentVisibleRect
        return max(0, document.isFlipped ? visible.minY - document.bounds.minY : document.bounds.maxY - visible.maxY)
    }
    private static func verifySessionSearch(store: AtlasStore, conversation: JSONValue) async throws {
        let id = conversation["id"].string, scope = store.selectedScope, draft = store.draft, messages = store.messages
        // Drive the same bound state as typing; the RootView task must load the
        // real API without an Enter key or explicit test-side loadSessions.
        store.sessionSearch = "no-matching-fixture-conversation"
        try await waitFor { !store.sessionsLoading && store.sessions.isEmpty }
        guard store.sessionID == id, store.selectedScope == scope, store.draft == draft,
              store.messages == messages, store.sessionListProblem == nil else { throw AtlasError.invalidResponse }
        store.sessionSearch = "native-fixture-chat"
        try await waitFor { !store.sessionsLoading && store.sessions.contains { $0["id"].string == id } }
        store.archiveView = true
        try await waitFor { !store.sessionsLoading && store.sessions.isEmpty }
        guard store.sessionID == id, store.messages == messages, store.draft == draft else { throw AtlasError.invalidResponse }
        store.archiveView = false; store.sessionSearch = ""
        try await waitFor { !store.sessionsLoading && store.sessions.contains { $0["id"].string == id } }
        guard store.selectedScope == scope, store.draft == draft, store.messages == messages else { throw AtlasError.invalidResponse }
    }
    private static func findView(in view: NSView, identifier: String) -> NSView? {
        if view.identifier?.rawValue == identifier { return view }
        for child in view.subviews { if let result = findView(in: child, identifier: identifier) { return result } }
        return nil
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

