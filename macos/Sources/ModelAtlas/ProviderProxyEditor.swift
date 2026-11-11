import AtlasCore
import Foundation
import SwiftUI

@MainActor struct ProviderProxyEditor: View {
    @ObservedObject var store: AtlasStore
    let providerID: String
    @State private var status: JSONValue?
    @State private var mode = "DIRECT"
    @State private var proxyURL = ""
    @State private var busy = false
    @State private var problem: String?
    @State private var saved = false

    var body: some View {
        AtlasCard {
            Label("連線設定", systemImage: "network").font(.headline)
            if let problem {
                Text(problem).font(.callout).foregroundStyle(.red)
            }
            Picker("連線方式", selection: $mode) {
                Text("直連").tag("DIRECT")
                Text("指定代理").tag("PROXY")
            }
            .disabled(busy || status == nil)
            if mode == "PROXY" {
                if let status, !status["url"].string.isEmpty {
                    Text("目前代理 · " + status["url"].string)
                        .font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
                }
                FormField(title: "代理網址", text: $proxyURL, secure: true)
                    .disabled(busy)
                Text("支援 HTTP、HTTPS 和 SOCKS5，可填入含帳密的網址。留空沿用已儲存的代理。")
                    .font(.caption).foregroundStyle(.secondary)
                Text("連線方式與代理位址保存在這台 Mac。")
                    .font(.caption).foregroundStyle(.secondary)
            }
            HStack {
                Button("重新讀取") { Task { await load() } }
                    .disabled(busy)
                Spacer()
                if saved {
                    Label("已儲存", systemImage: "checkmark.circle")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Button(busy ? "處理中…" : "儲存連線設定") {
                    Task { await save() }
                }
                .disabled(busy || !canSave)
            }
        }
        .task(id: providerID) { await load() }
        .onDisappear { proxyURL = "" }
    }

    private var canSave: Bool {
        guard let status else { return false }
        return mode == "DIRECT" || status["configured"].boolean ||
            !proxyURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func load() async {
        guard let api = store.api else { return }
        busy = true
        defer { busy = false }
        do {
            let value = try await api.get("/providers/proxy", query: [
                URLQueryItem(name: "provider_id", value: providerID)
            ])
            try Task.checkCancellation()
            status = value
            mode = value["mode"].string
            problem = nil
        } catch is CancellationError {
            return
        } catch {
            problem = error.localizedDescription
        }
    }

    private func save() async {
        guard let status, store.api != nil else { return }
        busy = true
        saved = false
        defer { busy = false }
        do {
            _ = try await store.mutate("/providers/proxy", body: .object([
                "provider_id": .string(providerID),
                "mode": .string(mode),
                "proxy_url": .string(mode == "PROXY" ? proxyURL : ""),
                "revision": status["revision"]
            ]))
            proxyURL = ""
            await load()
            saved = problem == nil
        } catch is CancellationError {
            return
        } catch {
            problem = error.localizedDescription
        }
    }
}
