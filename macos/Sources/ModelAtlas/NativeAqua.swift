import AppKit
import AtlasCore
import CryptoKit
import SwiftUI

// Separate fourth opt-in process: real settings controls and a packaged relaunch.
@MainActor enum NativeAqua {
    private static let preferenceKeys = ["workspaceInterface", "workspaceWallpaper", "appearance", "workspaceAccent", "reduceInterfaceTransparency"]
    static func run(store: AtlasStore, output: URL) async {
        var stage = "bootstrap"
        let defaults = UserDefaults.standard
        let beforeFile = output.appendingPathComponent("appearance-before.json")
        let reopening = ProcessInfo.processInfo.arguments.contains("--aqua-reopen")
        var primaryWindow: NSWindow?
        func checkpoint(_ next: String) {
            stage = next
            let state: JSONValue = .object(["stage": .string(stage), "interface": .string(AtlasInterfaceChoice(saved: defaults.string(forKey: "workspaceInterface") ?? "").rawValue), "wallpaper": .string(AtlasWallpaperChoice(saved: defaults.string(forKey: "workspaceWallpaper") ?? "").rawValue), "windows": .array(NSApp.windows.filter { $0.isVisible }.map { windowState($0) })])
            try? JSONEncoder().encode(state).write(to: output.appendingPathComponent("progress.json"), options: .atomic)
        }
        defer { DispatchQueue.main.async { NSApp.terminate(nil) } }
        do {
            try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
            guard store.ready, let api = store.api,
                  let window = NSApp.windows.first(where: { $0.isVisible && $0.contentView != nil }) else { throw AtlasError.engineUnavailable }
            primaryWindow = window
            try JSONEncoder().encode(JSONValue.object(["app_pid": .number(Double(ProcessInfo.processInfo.processIdentifier)), "engine_pid": .number(Double(store.engine.process?.processIdentifier ?? 0))])).write(to: output.appendingPathComponent("measurement-pids.json"), options: .atomic)
            window.setContentSize(NSSize(width: 1200, height: 800)); window.center(); window.makeKeyAndOrderFront(nil)
            if reopening {
                defer { restorePreferences(beforeFile) }
                stage = "actual-relaunch-restores-appearance"
                try require(defaults.string(forKey: "workspaceInterface") == "aqua" && defaults.string(forKey: "workspaceWallpaper") == "elaina-chibi")
                try await wait { find(window.contentView, "aqua-artwork-ready-elaina-chibi") != nil }
                try await frame(window, output, "11-relaunch-retained-dark")
                stage = "actual-app-reduced-transparency-control"
                defaults.set("light", forKey: "appearance")
                let sheet = try await openSettings(store, window)
                try await click(sheet, "native-transparency-target")
                try await wait { defaults.bool(forKey: "reduceInterfaceTransparency") }
                try await click(sheet, "native-settings-close-target")
                try await wait { window.attachedSheet == nil && find(window.contentView, "aqua-opaque-background") != nil }
                try require(find(window.contentView, "aqua-artwork-ready-elaina-chibi") == nil)
                try await frame(window, output, "12-reduced-transparency-light")
                try require(try await api.get("/telemetry").requiredArray().isEmpty)
                try require(try await api.get("/research/runs").requiredArray().isEmpty)
                let first = try JSONDecoder().decode(JSONValue.self, from: Data(contentsOf: output.appendingPathComponent("aqua-first.json")))
                try require(first["result"].string == "PASS")
                let report: JSONValue = .object(["result": .string("PASS"), "screenshots": .number(12), "actual_settings_buttons": .bool(true), "four_bundled_artworks": .bool(true), "same_original_bytes": .bool(true), "classic_default_and_invalid_fallback": .bool(true), "scope_and_draft_retained": .bool(true), "provider_form_retained": .bool(true), "relaunch_preference_retained": .bool(true), "reduced_transparency": .bool(true), "transparency_control": .string("actual app toggle; system preference read-only and never modified"), "no_inference_or_research": .bool(true), "images": first["images"]])
                try JSONEncoder().encode(report).write(to: output.appendingPathComponent("native-aqua.json"))
                return
            }
            var preferences: [String: Any] = [:]
            for key in preferenceKeys { preferences[key] = defaults.object(forKey: key) ?? NSNull() }
            try JSONSerialization.data(withJSONObject: preferences).write(to: beforeFile)
            stage = "seed-local-model-without-inference"
            _ = try await store.mutate("/providers", body: .object(["id": .string("native-aqua"), "name": .string("Aqua 原生工作區"), "base_url": .string("https://appearance.example/v1"), "auth_mode": .string("BEARER")]))
            _ = try await store.mutate("/accounts", body: .object(["id": .string("a"), "provider_id": .string("native-aqua"), "label": .string("外觀草稿帳戶")]))
            _ = try await store.mutate("/credentials", body: .object(["id": .string("c"), "provider_id": .string("native-aqua"), "account_id": .string("a"), "label": .string("合成測試用 Key"), "secret": .string("aqua-synthetic-local-key")]))
            _ = try await store.mutate("/models", body: .object(["provider_id": .string("native-aqua"), "id": .string("aqua-chat"), "display_name": .string("Atlas Aqua · 跨介面完整模型長名稱")]))
            _ = try await store.mutate("/my-models", body: .object(["provider_id": .string("native-aqua"), "account_id": .string("a"), "credential_id": .string("c"), "model_id": .string("aqua-chat"), "group": .string(""), "endpoint": .string("CHAT_COMPLETIONS")]))
            guard let model = store.activeModels.first(where: { $0["model_id"].string == "aqua-chat" }) else { throw AtlasError.invalidResponse }
            store.chooseModel(model); store.workspace = .playground
            store.draft = "切換介面與背景仍保留這份草稿"
            store.attachments = [.object(["filename": .string("appearance-draft.pdf")])]
            let scope = store.selectedScope, draft = store.draft, attachments = store.attachments
            func unchanged() throws { try require(store.selectedScope == scope && store.draft == draft && store.attachments == attachments && store.sessionID == nil && store.messages.isEmpty) }
            defaults.set("light", forKey: "appearance"); defaults.set("graphite", forKey: "workspaceAccent")
            defaults.set(false, forKey: "reduceInterfaceTransparency")
            defaults.set("invalid-interface", forKey: "workspaceInterface"); defaults.set("https://invalid.example/not-a-bundled-image", forKey: "workspaceWallpaper")
            stage = "unknown-preferences-fall-back-to-classic"
            try await wait { find(window.contentView, "native-interface-classic") != nil }
            for item in AtlasWallpaperChoice.allCases { try require(find(window.contentView, "aqua-artwork-ready-" + item.rawValue) == nil) }
            try await frame(window, output, "01-classic-invalid-preference-light")
            var images: [JSONValue] = []
            let definitions: [(AtlasWallpaperChoice, String, Bool)] = [(.kotoha, "02-kotoha-light", false), (.elaina, "03-elaina-light", false), (.kotohaChibi, "04-kotoha-chibi-light", false), (.elainaChibi, "05-elaina-chibi-dark", true)]
            for (choice, name, dark) in definitions {
                checkpoint("settings-open-" + choice.rawValue)
                let sheet = try await openSettings(store, window)
                checkpoint("settings-ready-" + choice.rawValue)
                if AtlasInterfaceChoice(saved: defaults.string(forKey: "workspaceInterface") ?? "") != .aqua {
                    checkpoint("style-click-" + choice.rawValue)
                    try await click(sheet, "native-style-aqua-target")
                    checkpoint("style-applied-" + choice.rawValue)
                    try await wait { defaults.string(forKey: "workspaceInterface") == "aqua" }
                }
                checkpoint("wallpaper-click-" + choice.rawValue)
                try await click(sheet, "native-wallpaper-" + choice.rawValue + "-target")
                checkpoint("wallpaper-applied-" + choice.rawValue)
                try await wait { defaults.string(forKey: "workspaceWallpaper") == choice.rawValue }
                defaults.set(dark ? "dark" : "light", forKey: "appearance")
                if choice == .elainaChibi { try await frame(sheet, output, "06-settings-four-choices-dark") }
                checkpoint("settings-close-" + choice.rawValue)
                try await click(sheet, "native-settings-close-target")
                try await wait { !store.settingsOpen && window.attachedSheet == nil }
                checkpoint("background-ready-" + choice.rawValue)
                try await wait { find(window.contentView, "aqua-artwork-ready-" + choice.rawValue) != nil }
                checkpoint("bitmap-dimensions-" + choice.rawValue)
                guard let root = Bundle.main.resourceURL, let decoded = AtlasWallpaperImages.image(choice), let bitmap = decoded.representations.first else { throw AtlasError.invalidResponse }
                let bytes = try Data(contentsOf: root.appendingPathComponent("Wallpapers").appendingPathComponent(choice.filename))
                let hash = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
                try require(bitmap.pixelsWide > 900 && bitmap.pixelsHigh >= 1254)
                images.append(.object(["id": .string(choice.rawValue), "sha256": .string(hash), "bytes": .number(Double(bytes.count))]))
                try unchanged(); try await frame(window, output, name)
            }
            stage = "three-production-workspaces"
            defaults.set("light", forKey: "appearance"); store.workspace = .providers
            try await frame(window, output, "07-providers-light"); try unchanged()
            defaults.set("dark", forKey: "appearance"); store.workspace = .models
            window.setContentSize(NSSize(width: 960, height: 700))
            try await frame(window, output, "08-models-compact-dark"); try unchanged()
            stage = "provider-form-draft-survives-appearance-change"
            store.newProviderOpen = true
            try await wait { window.attachedSheet != nil }
            guard let providerSheet = window.attachedSheet else { throw AtlasError.invalidResponse }
            try await wait { editable(providerSheet.contentView, placeholder: "供應商名稱") != nil }
            guard let field = editable(providerSheet.contentView, placeholder: "供應商名稱") else { throw AtlasError.invalidResponse }
            providerSheet.makeFirstResponder(field)
            guard let editor = providerSheet.firstResponder as? NSTextView else { throw AtlasError.invalidResponse }
            editor.selectAll(nil); editor.insertText("切換後保留的供應商草稿", replacementRange: NSRange(location: NSNotFound, length: 0))
            defaults.set("classic", forKey: "workspaceInterface")
            try await wait { find(window.contentView, "native-interface-classic") != nil }
            defaults.set("aqua", forKey: "workspaceInterface")
            try await wait { find(window.contentView, "native-interface-aqua") != nil }
            try require(field.stringValue == "切換後保留的供應商草稿")
            try await frame(providerSheet, output, "09-provider-form-retained-dark")
            store.newProviderOpen = false; try await wait { window.attachedSheet == nil }
            store.workspace = .playground
            stage = "actual-classic-and-aqua-round-trip"
            let sheet = try await openSettings(store, window)
            try await click(sheet, "native-style-classic-target"); try await wait { defaults.string(forKey: "workspaceInterface") == "classic" }
            try await click(sheet, "native-settings-close-target"); try await wait { window.attachedSheet == nil }
            try unchanged(); try await frame(window, output, "10-classic-retained-dark")
            let finalSheet = try await openSettings(store, window)
            try await click(finalSheet, "native-style-aqua-target"); try await wait { defaults.string(forKey: "workspaceInterface") == "aqua" }
            try await click(finalSheet, "native-settings-close-target"); try await wait { window.attachedSheet == nil }
            try unchanged(); try require(defaults.string(forKey: "workspaceWallpaper") == "elaina-chibi")
            try require(try await api.get("/telemetry").requiredArray().isEmpty)
            try require(try await api.get("/research/runs").requiredArray().isEmpty)
            defaults.synchronize()
            try JSONEncoder().encode(JSONValue.object(["result": .string("PASS"), "screenshots": .number(10), "images": .array(images)])).write(to: output.appendingPathComponent("aqua-first.json"))
        } catch {
            checkpoint(stage)
            if let window = primaryWindow { try? await frame(window.attachedSheet ?? window, output, "failure") }
            try? Data((stage + ": " + error.localizedDescription).utf8).write(to: output.appendingPathComponent("FAILURE.txt"), options: .atomic)
            // End real modal sheets before asking AppKit to terminate. Failure
            // remains fatal; original assertions and total deadline stay intact.
            store.settingsOpen = false; store.newProviderOpen = false
            if let window = primaryWindow {
                for sheet in window.sheets { window.endSheet(sheet); sheet.orderOut(nil) }
            }
            restorePreferences(beforeFile)
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
    }
    private static func restorePreferences(_ path: URL) {
        guard let bytes = try? Data(contentsOf: path), let object = try? JSONSerialization.jsonObject(with: bytes), let values = object as? [String: Any] else { return }
        for key in preferenceKeys { if let value = values[key], !(value is NSNull) { UserDefaults.standard.set(value, forKey: key) } else { UserDefaults.standard.removeObject(forKey: key) } }
        UserDefaults.standard.synchronize()
    }
    private static func require(_ value: Bool) throws { if !value { throw AtlasError.invalidResponse } }
    private static func wait(_ condition: () -> Bool) async throws {
        let end = Date().addingTimeInterval(8)
        while !condition() && Date() < end { try await Task.sleep(nanoseconds: 20_000_000) }
        try require(condition())
    }
    private static func windowState(_ window: NSWindow) -> JSONValue {
        .object(["number": .number(Double(window.windowNumber)), "sheet": .number(Double(window.attachedSheet?.windowNumber ?? 0)), "markers": .array(markers(window.contentView))])
    }
    private static func markers(_ view: NSView?) -> [JSONValue] {
        guard let view else { return [] }
        var rows: [JSONValue] = []
        if let name = view.identifier?.rawValue, name.hasPrefix("native-") || name.hasPrefix("aqua-") || name.hasPrefix("appearance-") {
            let rect = view.convert(view.bounds, to: nil)
            rows.append(.object(["id": .string(name), "x": .number(Double(rect.minX)), "y": .number(Double(rect.minY)), "width": .number(Double(rect.width)), "height": .number(Double(rect.height))]))
        }
        for child in view.subviews { rows += markers(child) }; return rows
    }
    private static func find(_ view: NSView?, _ marker: String) -> NSView? {
        guard let view else { return nil }; if view.identifier?.rawValue == marker { return view }
        for child in view.subviews { if let found = find(child, marker) { return found } }; return nil
    }
    private static func editable(_ view: NSView?, placeholder: String) -> NSTextField? {
        guard let view else { return nil }
        if let field = view as? NSTextField, field.isEditable, field.placeholderString == placeholder { return field }
        for child in view.subviews { if let found = editable(child, placeholder: placeholder) { return found } }; return nil
    }
    private static func openSettings(_ store: AtlasStore, _ window: NSWindow) async throws -> NSWindow {
        store.settingsOpen = true
        try await wait { window.attachedSheet != nil }
        guard let sheet = window.attachedSheet else { throw AtlasError.invalidResponse }
        sheet.makeKeyAndOrderFront(nil); sheet.contentView?.layoutSubtreeIfNeeded()
        try await wait { find(sheet.contentView, "native-settings-ready") != nil }
        return sheet
    }
    private static func click(_ window: NSWindow, _ marker: String) async throws {
        try await wait { find(window.contentView, marker) != nil }
        guard let target = find(window.contentView, marker) else { throw AtlasError.invalidResponse }
        window.makeKeyAndOrderFront(nil)
        target.scrollToVisible(target.bounds); window.contentView?.layoutSubtreeIfNeeded()
        try await Task.sleep(nanoseconds: 80_000_000)
        let point = target.convert(NSPoint(x: target.bounds.midX, y: target.bounds.midY), to: nil)
        for (index, type) in [NSEvent.EventType.leftMouseDown, .leftMouseUp].enumerated() {
            guard let event = NSEvent.mouseEvent(with: type, location: point, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber, context: nil, eventNumber: index, clickCount: 1, pressure: type == .leftMouseDown ? 1 : 0) else { throw AtlasError.invalidResponse }
            NSApp.postEvent(event, atStart: false)
        }
    }
    private static func frame(_ window: NSWindow, _ output: URL, _ name: String) async throws {
        try await Task.sleep(nanoseconds: 450_000_000)
        guard let root = window.contentView?.superview else { throw AtlasError.invalidResponse }
        root.layoutSubtreeIfNeeded(); root.displayIfNeeded()
        guard let bitmap = root.bitmapImageRepForCachingDisplay(in: root.bounds) else { throw AtlasError.invalidResponse }
        root.cacheDisplay(in: root.bounds, to: bitmap)
        guard let bytes = bitmap.representation(using: .png, properties: [:]), bytes.count > 10_000 else { throw AtlasError.invalidResponse }
        try bytes.write(to: output.appendingPathComponent(name + ".png"))
    }
}
