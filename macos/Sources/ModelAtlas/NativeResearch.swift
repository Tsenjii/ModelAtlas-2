import AppKit
import AtlasCore
import SwiftUI

// A third opt-in native process, in addition to every original smoke/parity
// workload and deadline. Its upstream is a separate test-only real TLS server.
@MainActor enum NativeResearch {
    static func run(store: AtlasStore, output: URL) async {
        let originalAPI = store.api
        var window: NSWindow?
        var stage = "fixture"
        defer { window?.close(); store.api = originalAPI; DispatchQueue.main.async { NSApp.terminate(nil) } }
        do {
            try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
            guard let path = ProcessInfo.processInfo.environment["MODELATLAS_RESEARCH_FIXTURE_OUT"] else { throw AtlasError.invalidEndpoint }
            let fixture = try JSONDecoder().decode(JSONValue.self, from: Data(contentsOf: URL(fileURLWithPath: path)))
            guard let url = URL(string: fixture["url"].string), !fixture["token"].string.isEmpty else { throw AtlasError.invalidEndpoint }
            let api = try AtlasClient(baseURL: url, token: fixture["token"].string)
            store.api = api
            try JSONEncoder().encode(JSONValue.object(["app_pid": .number(Double(ProcessInfo.processInfo.processIdentifier)), "engine_pid": .number(Double(store.engine.process?.processIdentifier ?? 0)), "fixture_pid": fixture["pid"]])).write(to: output.appendingPathComponent("measurement-pids.json"))
            func fixtureAction(_ action: String) async throws { _ = try await api.call("/research-fixture", method: "POST", body: .object(["action": .string(action)])) }
            stage = "real-partial-aa-run"
            let body = try ResearchRequest.body(sources: [], selections: [], budget: "2", probes: "0", minimal: false, search: false, lmspeed: false, aa: true)
            let initial = try await api.call("/research/start", method: "POST", body: body)
            let model = ResearchRunController(api: api, run: initial)
            try await refreshed(model, until: "BUDGET_EXHAUSTED")
            try require(model.tasks.count == 1 && model.tasks[0]["result"]["aa_progress"]["completed_pages"] == .number(1))
            try require(model.ranking["snapshot"]["id"].string == "aa-last-good")
            window = try await show(ResearchRunPanel(model: model), marker: "research-run-ready", dark: false)
            try frame(window!, output, "01-aa-partial-light")
            close(window!)
            window = try await show(ResearchRunPanel(model: model), marker: "research-run-ready", dark: true)
            try frame(window!, output, "02-aa-partial-dark")
            stage = "real-resume-commit-then-get-failure"
            model.additional = "1"
            try await fixtureAction("FAIL_READ_AFTER_CONTROL")
            try await click(window!, "research-run-resume-target")
            try await wait { model.needsReload && !model.busy }
            try require(model.run["status"].string == "QUEUED" && model.problem?.contains("已保存") == true)
            try frame(window!, output, "03-saved-read-failure-dark")
            let lastGood = model.tasks
            await model.control("RESUME")
            try require(model.tasks == lastGood)
            try await click(window!, "research-run-refresh-target")
            try await wait { !model.locked }
            try require(model.run["budget"] == .number(3) && model.run["used"] == .number(2))
            stage = "actual-pause-button-and-concurrent-controller-guard"
            try await fixtureAction("HOLD_CONTROL")
            try await click(window!, "research-run-pause-target")
            try await wait { model.busy }
            try await waitFixture(api) { $0["held"].boolean }
            await model.control("PAUSE")
            let held = try await api.get("/research-fixture")
            try require(held["controls"] == .number(2) && held["resumes"] == .number(1))
            try await fixtureAction("RELEASE_CONTROL")
            try await wait { !model.locked && model.run["status"].string == "PAUSED" }
            try frame(window!, output, "04-paused-dark")
            stage = "lost-ack-must-read-without-reposting"
            try await fixtureAction("LOSE_CONTROL_ACK")
            try await click(window!, "research-run-resume-target")
            try await wait { model.needsReload && !model.busy }
            try require(model.run["status"].string == "PAUSED" && model.problem?.contains("待確認") == true)
            await model.control("RESUME")
            try frame(window!, output, "05-lost-ack-dark")
            try await click(window!, "research-run-refresh-target")
            try await wait { !model.locked }
            let before = try await api.get("/research-fixture")
            try require(before["controls"] == .number(3) && before["resumes"] == .number(2) && before["used"] == .number(2))
            stage = "real-page-two-only-recovery-and-reopen"
            try await fixtureAction("ADVANCE_CLOCK")
            try await wait { model.run["status"].string == "COMPLETED" && !model.locked }
            try require(model.run["used"] == .number(3) && model.tasks[0]["status"].string == "SUCCESS" && model.tasks[0]["result"]["aa_progress"]["completed_pages"] == .number(2))
            try require(model.ranking["snapshot"]["models"].array.count == 4 && model.ranking["snapshot"]["id"].string != "aa-last-good")
            try frame(window!, output, "06-aa-complete-dark")
            close(window!); window = nil
            let reopened = ResearchRunController(api: api, run: initial)
            await reopened.reload()
            try require(reopened.loaded && reopened.run["status"].string == "COMPLETED" && !reopened.canResume)
            stage = "actual-services-update-button"
            window = try await show(ServicesView(store: store).padding(24), marker: "services-loaded", dark: false)
            try await click(window!, "aa-update-target")
            let serviceSheet = try await completedSheet(window!)
            try frame(serviceSheet, output, "07-services-aa-complete-light")
            close(window!); window = nil
            stage = "actual-research-update-button"
            window = try await show(ResearchView(store: store, initialTab: "AA 排名").padding(24), marker: "research-loaded", dark: true)
            try await click(window!, "aa-update-target")
            let researchSheet = try await completedSheet(window!)
            try frame(researchSheet, output, "08-research-aa-complete-dark")
            let state = try await api.get("/research-fixture")
            try require(state["first"] == .number(3) && state["second"] == .number(4) && state["used"] == .number(7) && state["runs"] == .number(3) && state["starts"] == .number(3) && state["controls"] == .number(3) && state["resumes"] == .number(2) && state["direct_sync"] == .number(0) && state["telemetry"] == .number(0))
            let rows = try await api.get("/research/runs").requiredArray()
            try require(rows.count == 3 && rows.allSatisfy { $0["status"].string == "COMPLETED" } && rows.compactMap { $0["used"].numeric }.sorted() == [2, 2, 3])
            let report: JSONValue = .object(["result": .string("PASS"), "screenshots": .number(8), "real_tls": .bool(true), "native_update_buttons": .bool(true), "live_run_state": .bool(true), "last_good_preserved": .bool(true), "saved_refresh_no_repost": .bool(true), "lost_ack_no_repost": .bool(true), "concurrent_control_guard": .bool(true), "reopen_same_run": .bool(true), "seven_exact_requests": .bool(true), "no_inference": .bool(true), "wire": state])
            try JSONEncoder().encode(report).write(to: output.appendingPathComponent("native-research.json"))
        } catch {
            if let window { try? frame(window.sheets.first ?? window, output, "failure") }
            try? Data((stage + ": " + error.localizedDescription).utf8).write(to: output.appendingPathComponent("FAILURE.txt"))
        }
    }
    private static func refreshed(_ model: ResearchRunController, until status: String) async throws {
        let end = Date().addingTimeInterval(10)
        while Date() < end {
            await model.reload()
            if model.run["status"].string == status && !model.locked { return }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        throw AtlasError.invalidResponse
    }
    private static func wait(_ condition: () -> Bool) async throws {
        let end = Date().addingTimeInterval(10)
        while !condition() && Date() < end { try await Task.sleep(nanoseconds: 20_000_000) }
        try require(condition())
    }
    private static func waitFixture(_ api: AtlasClient, condition: (JSONValue) -> Bool) async throws {
        let end = Date().addingTimeInterval(10)
        while Date() < end { if condition(try await api.get("/research-fixture")) { return }; try await Task.sleep(nanoseconds: 20_000_000) }
        throw AtlasError.invalidResponse
    }
    private static func show<V: View>(_ view: V, marker: String, dark: Bool) async throws -> NSWindow {
        UserDefaults.standard.set(dark ? "dark" : "light", forKey: "appearance")
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 860, height: 760), styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false; window.title = "ModelAtlas · 原生研究驗收"; window.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
        window.contentView = NSHostingView(rootView: ScrollView { view.atlasAppearance().preferredColorScheme(dark ? .dark : .light) }.frame(width: 860, height: 760))
        window.center(); window.makeKeyAndOrderFront(nil)
        do { try await wait { find(window.contentView, marker) != nil }; return window }
        catch { window.close(); throw error }
    }
    private static func completedSheet(_ window: NSWindow) async throws -> NSWindow {
        try await wait { window.sheets.first.map { find($0.contentView, "research-run-status-COMPLETED") != nil && find($0.contentView, "research-run-ready") != nil } == true }
        guard let sheet = window.sheets.first else { throw AtlasError.invalidResponse }; return sheet
    }
    private static func find(_ view: NSView?, _ name: String) -> NSView? {
        guard let view else { return nil }
        if view.identifier?.rawValue == name { return view }
        for child in view.subviews { if let value = find(child, name) { return value } }
        return nil
    }
    private static func click(_ window: NSWindow, _ marker: String) async throws {
        guard let target = find(window.contentView, marker) else { throw AtlasError.invalidResponse }
        target.scrollToVisible(target.bounds); window.contentView?.layoutSubtreeIfNeeded()
        try await Task.sleep(nanoseconds: 80_000_000)
        let point = target.convert(NSPoint(x: target.bounds.midX, y: target.bounds.midY), to: nil)
        for (index, type) in [NSEvent.EventType.leftMouseDown, .leftMouseUp].enumerated() {
            guard let event = NSEvent.mouseEvent(with: type, location: point, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber, context: nil, eventNumber: index, clickCount: 1, pressure: type == .leftMouseDown ? 1 : 0) else { throw AtlasError.invalidResponse }
            NSApp.postEvent(event, atStart: false)
        }
    }
    private static func frame(_ window: NSWindow, _ output: URL, _ name: String) throws {
        guard let root = window.contentView?.superview else { throw AtlasError.invalidResponse }
        root.layoutSubtreeIfNeeded(); root.displayIfNeeded()
        guard let bitmap = root.bitmapImageRepForCachingDisplay(in: root.bounds) else { throw AtlasError.invalidResponse }
        root.cacheDisplay(in: root.bounds, to: bitmap)
        guard let data = bitmap.representation(using: .png, properties: [:]), data.count > 10_000 else { throw AtlasError.invalidResponse }
        try data.write(to: output.appendingPathComponent(name + ".png"))
    }
    private static func close(_ window: NSWindow) { for sheet in window.sheets { window.endSheet(sheet); sheet.orderOut(nil) }; window.close() }
    private static func require(_ value: @autoclosure () -> Bool) throws { guard value() else { throw AtlasError.invalidResponse } }
}
