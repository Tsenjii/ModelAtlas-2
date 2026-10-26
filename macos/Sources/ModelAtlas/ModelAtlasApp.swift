import AppKit
import AtlasCore
import SwiftUI

@MainActor final class AppDelegate: NSObject, NSApplicationDelegate {
    var store: AtlasStore?
    private enum QuitPhase: Equatable { case idle, draining, finished }
    private var quitPhase = QuitPhase.idle
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard let store else { return .terminateNow }
        if quitPhase == .finished { return .terminateNow }
        guard quitPhase == .idle else { return .terminateCancel }
        quitPhase = .draining
        // Keep the normal run loop alive while Swift concurrency drains the
        // engine. Reissue termination only after shutdown has completed.
        Task { @MainActor in
            await store.shutdown()
            quitPhase = .finished
            DispatchQueue.main.async { sender.terminate(nil) }
        }
        return .terminateCancel
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
                    if let smokeDirectory {
                        if ProcessInfo.processInfo.arguments.contains("--research-smoke") { await NativeResearch.run(store: store, output: smokeDirectory) }
                        else if ProcessInfo.processInfo.arguments.contains("--parity-smoke") { await NativeParity.run(store: store, output: smokeDirectory) }
                        else { await NativeSmoke.run(store: store, output: smokeDirectory) }
                    }
                    else { await store.observeNotifications() }
                }
        }
        .windowToolbarStyle(.unified)
        .commands {
            CommandGroup(replacing: .newItem) { Button("新增對話") { store.newConversation() }.keyboardShortcut("n").disabled(store.generating || store.hasPresentedSheet) }
            CommandGroup(replacing: .appSettings) { Button("設定…") { store.settingsOpen = true }.keyboardShortcut(",").disabled(store.hasPresentedSheet) }
            CommandMenu("工作區") {
                Button("Playground") { store.workspace = .playground }.keyboardShortcut("1").disabled(store.hasPresentedSheet)
                Button("Providers") { store.workspace = .providers }.keyboardShortcut("2").disabled(store.hasPresentedSheet)
                Button("Models") { store.workspace = .models }.keyboardShortcut("3").disabled(store.hasPresentedSheet)
                Divider()
                Button("選擇模型…") { store.modelPickerOpen = true }.keyboardShortcut("k").disabled(!store.ready || store.generating || store.hasPresentedSheet)
                Button("輸入訊息") { store.focusComposer() }.keyboardShortcut("l").disabled(!store.ready || store.closing || store.hasPresentedSheet)
                Button("加入附件…") { store.pickAttachments() }.keyboardShortcut("o").disabled(!store.ready || store.closing || store.workspace != .playground || store.generating || store.attaching || store.hasPresentedSheet)
                Button("重新整理") { Task { await store.refresh() } }.keyboardShortcut("r").disabled(!store.ready || store.hasPresentedSheet)
                Button("新增供應商…") { store.newProviderOpen = true }.keyboardShortcut("n", modifiers: [.command, .shift]).disabled(!store.ready || store.hasPresentedSheet)
            }
            CommandMenu("顯示") {
                Toggle("顯示側邊欄", isOn: $store.sidebarVisible).keyboardShortcut("s", modifiers: [.command, .control]).disabled(store.closing || store.hasPresentedSheet)
            }
        }
    }
}
