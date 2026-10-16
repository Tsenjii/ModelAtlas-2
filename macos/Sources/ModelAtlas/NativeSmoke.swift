import AppKit
import AtlasCore
import SwiftUI

// Opt-in acceptance harness. It uses a separate temporary data directory and a
// real TLS fixture; normal launches never seed data or run any inference.
@MainActor enum NativeSmoke {
    static func run(store: AtlasStore, output: URL) async {
        let manager = FileManager.default
        do {
            try manager.createDirectory(at: output, withIntermediateDirectories: true)
            guard store.ready, let api = store.api else { throw AtlasError.engineUnavailable }
            let identities: JSONValue = .object(["app_pid": .number(Double(ProcessInfo.processInfo.processIdentifier)), "engine_pid": .number(Double(store.engine.process?.processIdentifier ?? 0))])
            try JSONEncoder().encode(identities).write(to: output.appendingPathComponent("measurement-pids.json"), options: .atomic)
            let args = ProcessInfo.processInfo.arguments
            guard let at = args.firstIndex(of: "--fixture-url"), args.count > at + 1 else { throw AtlasError.invalidEndpoint }
            let fixture = args[at + 1]
            let provider: JSONValue = .object(["id": .string("native-fixture"), "name": .string("本機測試供應商"), "base_url": .string(fixture + "/v1"), "description": .string("原生 Mac 整合測試 · 合成資料"), "auth_mode": .string("BEARER")])
            _ = try await store.mutate("/providers", body: provider)
            _ = try await store.mutate("/accounts", body: .object(["id": .string("primary"), "provider_id": provider["id"], "label": .string("Mac 工作區")]))
            _ = try await store.mutate("/credentials", body: .object(["id": .string("key"), "provider_id": provider["id"], "account_id": .string("primary"), "label": .string("測試用 Key"), "secret": .string("native-fixture-provider-key")]))
            _ = try await store.mutate("/models/sync", body: .object(["provider_id": provider["id"], "account_id": .string("primary"), "credential_id": .string("key")]))
            for id in ["native-fixture-chat", "native-fixture-code"] {
                _ = try await store.mutate("/my-models", body: .object(["provider_id": provider["id"], "account_id": .string("primary"), "credential_id": .string("key"), "model_id": .string(id), "group": .string(""), "endpoint": .string("CHAT_COMPLETIONS")]))
            }
            store.workspace = .playground; store.draft = "請用繁體中文介紹這個原生 Mac 工作區。"
            store.send()
            let deadline = Date().addingTimeInterval(25)
            while store.generating && Date() < deadline { try await Task.sleep(nanoseconds: 30_000_000) }
            guard !store.generating, store.messages.contains(where: { $0["role"].string == "assistant" && $0["content"].string.contains("原生 Mac") }) else { throw AtlasError.invalidResponse }
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
            let result: JSONValue = .object(["result": .string("PASS"), "real_engine": .bool(true), "real_tls_chat": .bool(true), "native_windows": .bool(true), "providers": .number(Double(store.providers.count)), "models": .number(Double(store.myModels.count)), "messages": .number(Double(store.messages.count)), "screenshots": .number(6)])
            try JSONEncoder().encode(result).write(to: output.appendingPathComponent("native-smoke.json"), options: .atomic)
        } catch {
            let message = "Native smoke failed: " + error.localizedDescription
            try? Data(message.utf8).write(to: output.appendingPathComponent("FAILURE.txt"), options: .atomic)
        }
        await store.shutdown()
        NSApp.terminate(nil)
    }
    private static func capture(_ window: NSWindow, to url: URL) async throws {
        try await Task.sleep(nanoseconds: 450_000_000)
        guard let view = window.contentView else { throw AtlasError.invalidResponse }
        view.layoutSubtreeIfNeeded(); view.displayIfNeeded()
        guard let bitmap = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { throw AtlasError.invalidResponse }
        view.cacheDisplay(in: view.bounds, to: bitmap)
        guard let data = bitmap.representation(using: .png, properties: [:]), data.count > 10_000 else { throw AtlasError.invalidResponse }
        try data.write(to: url, options: .atomic)
    }
}
