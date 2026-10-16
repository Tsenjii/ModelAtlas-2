import AppKit
import AtlasCore
import SwiftUI

@MainActor final class AppDelegate: NSObject, NSApplicationDelegate {
    var store: AtlasStore?
    private var stopping = false
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard let store, !stopping else { return .terminateNow }
        stopping = true
        Task { await store.shutdown(); sender.reply(toApplicationShouldTerminate: true) }
        return .terminateLater
    }
    func applicationDidFinishLaunching(_ notification: Notification) { NSApp.activate(ignoringOtherApps: true) }
}

@main struct ModelAtlasApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var store: AtlasStore
    private let smokeDirectory: URL?
    init() {
        let args = ProcessInfo.processInfo.arguments
        if let index = args.firstIndex(of: "--smoke-dir"), args.count > index + 1 {
            let url = URL(fileURLWithPath: args[index + 1], isDirectory: true)
            smokeDirectory = url
            _store = StateObject(wrappedValue: AtlasStore(directory: url.appendingPathComponent("Workspace", isDirectory: true)))
        } else { smokeDirectory = nil; _store = StateObject(wrappedValue: AtlasStore()) }
    }
    var body: some Scene {
        WindowGroup("ModelAtlas") {
            RootView(store: store)
                .frame(idealWidth: 1200, idealHeight: 820)
                .task {
                    delegate.store = store
                    await store.bootstrap(smoke: smokeDirectory != nil)
                    if let smokeDirectory { await NativeSmoke.run(store: store, output: smokeDirectory) }
                    else { await store.observeNotifications() }
                }
        }
        .windowToolbarStyle(.unified)
        .commands {
            CommandGroup(replacing: .newItem) { Button("新增對話") { store.newConversation() }.keyboardShortcut("n").disabled(store.generating) }
            CommandGroup(replacing: .appSettings) { Button("設定…") { store.settingsOpen = true }.keyboardShortcut(",") }
            CommandMenu("工作區") {
                Button("Playground") { store.workspace = .playground }.keyboardShortcut("1")
                Button("Providers") { store.workspace = .providers }.keyboardShortcut("2")
                Button("Models") { store.workspace = .models }.keyboardShortcut("3")
                Divider()
                Button("重新整理") { Task { await store.refresh() } }.keyboardShortcut("r").disabled(!store.ready)
                Button("新增供應商…") { store.newProviderOpen = true }.keyboardShortcut("n", modifiers: [.command, .shift]).disabled(!store.ready)
            }
        }
    }
}
