import AppKit
import AtlasCore
import SwiftUI

@MainActor
struct WorkspaceSyncView: View {
    @ObservedObject var store: AtlasStore
    @State private var status: JSONValue?
    @State private var url = ""
    @State private var label = "我的 Mac"
    @State private var code = ""
    @State private var shareSecrets = false
    @State private var pairing: JSONValue?
    @State private var busy = false
    @State private var syncing = false
    @State private var pausing = false
    @State private var confirmResetTransfer = false
    @State private var problem: String?
    @State private var confirmDisconnect = false
    @State private var confirmRevoke = false
    @State private var revokeDevice: JSONValue?
    @State private var conflict: JSONValue?
    @State private var conflictID = ""

    var body: some View {
        AtlasCard {
            Label("跨裝置同步", systemImage: "arrow.triangle.2.circlepath").font(.headline)
            Text("連上自己的 VPS，同步連線、模型及歷史。離線修改會保留；兩端有衝突時由你選擇。").font(.callout).foregroundStyle(.secondary)
            statusContent
            if !busy { AtlasLoadedMarker(name: "workspace-sync-idle").frame(height: 0) }
            HStack {
                Button("更新同步狀態") { Task { await run { _ in } } }.disabled(busy)
                if busy { ProgressView().controlSize(.small) }
            }
            if let problem { Text(problem).font(.callout).foregroundStyle(.red).textSelection(.enabled) }
        }
        .task(id: syncing) {
            guard syncing, let api = store.api else { return }
            while syncing && !Task.isCancelled {
                do {
                    let latest = try await api.get("/workspace")
                    if Task.isCancelled || !syncing { return }
                    status = latest
                    try await Task.sleep(nanoseconds: 750_000_000)
                } catch { return }
            }
        }
        .task {
            await run { _ in }
            while !Task.isCancelled {
                do { try await Task.sleep(nanoseconds: 15_000_000_000) } catch { return }
                await run { _ in }
            }
        }
        .onDisappear { code = ""; pairing = nil }
        .confirmationDialog("中斷這台 Mac 的同步連線？", isPresented: $confirmDisconnect) {
            Button("中斷連線", role: .destructive) { Task { await run { api in _ = try await api.call("/workspace/disconnect", method: "POST", body: .object(["confirm": .bool(true)])) } } }
        } message: { Text("本機資料與完整歷史都會保留。") }
        .confirmationDialog("重新準備傳輸？", isPresented: $confirmResetTransfer) {
            Button("重新準備傳輸") { Task { await run { api in
                guard let id = status?["transfer"]["id"].string, !id.isEmpty else { return }
                _ = try await api.call("/workspace/transfer/discard", method: "POST", body: .object(["id": .string(id), "confirm": .bool(true)]))
            } } }
        } message: { Text("只重設尚未完成的傳輸暫存。本機資料、完整歷史與待同步修改都會保留；已完成的資料必須先恢復讀取結果。") }
        .confirmationDialog("撤銷同步權限？", isPresented: $confirmRevoke, presenting: revokeDevice) { device in
            Button("撤銷這台裝置", role: .destructive) { Task { await run { api in
                _ = try await api.call("/workspace/revoke", method: "POST", body: .object(["device_id": device["id"], "confirm": .bool(true)]))
            } } }
        } message: { device in Text("「" + device["label"].string + "」已下載的資料仍會保留。") }
        .sheet(isPresented: Binding(get: { conflict != nil }, set: { if !$0 { conflict = nil } })) {
            VStack(alignment: .leading, spacing: 18) {
                HStack { Text("選擇要保留的修改").font(.title2); Spacer(); Button("關閉") { conflict = nil } }
                Text("兩份修改及衝突歷史都會保留。選擇前會再次核對最新版本。").foregroundStyle(.secondary)
                if let conflict {
                    if conflict["preview_limited"].boolean { Text("長內容顯示摘要；完整兩份資料與歷史仍保留。").font(.caption).foregroundStyle(.secondary) }
                    ScrollView {
                        VStack(alignment: .leading, spacing: 20) {
                            snapshot("本機", conflict["local"])
                            snapshot("同步中心", conflict["remote"])
                        }
                    }
                    HStack {
                        Button("保留本機修改") { Task { await resolve("local", detail: conflict) } }
                        Button("採用同步中心") { Task { await resolve("remote", detail: conflict) } }
                    }.disabled(busy)
                }
                if let problem { Text(problem).foregroundStyle(.red) }
            }.padding(24).frame(width: 700, height: 620)
        }
    }
    @ViewBuilder private var statusContent: some View {
            if let status {
                let peer = status["peer"]
                Text((peer == .null ? "此裝置可作同步中心" : "已連接同步中心") + " · " + status["pending"].display + " 筆待同步")
                if peer != .null {
                    connectedPeer(peer, current: status)
                } else {
                    connectionForms
                }
                pairedDevices(status)
                conflictsList(status)
                AtlasLoadedMarker(name: "workspace-sync-loaded").frame(height: 0)
            } else { ProgressView("正在讀取同步狀態…") }
    }
    @ViewBuilder private func connectedPeer(_ peer: JSONValue, current: JSONValue) -> some View {
                    Text(peer["url"].string).textSelection(.enabled)
                    Text(peer["share_secrets"].boolean ? "已允許 Key 同步" : "只同步資料；新裝置需另行輸入 Key").font(.caption).foregroundStyle(.secondary)
                    if let timestamp = peer["last_success"].numeric {
                        Text("上次成功：" + Date(timeIntervalSince1970: timestamp / 1000).formatted()).font(.caption).foregroundStyle(.secondary)
                    }
                    if peer["enabled"].boolean && !peer["last_error"].string.isEmpty { Text("同步暫未完成，本機資料與待同步修改已保留。").foregroundStyle(.red) }
                    let transfer = current["transfer"]
                    if !peer["enabled"].boolean { AtlasLoadedMarker(name: "workspace-transfer-paused").frame(height: 0); Text("同步已暫停；傳輸進度與本機修改都已保留。").font(.caption).foregroundStyle(.secondary) }
                    if transfer != .null { transferProgress(transfer, paused: !peer["enabled"].boolean) }
                    HStack {
                        Button(peer["enabled"].boolean && transfer == .null ? "立即同步" : "恢復同步") { Task { await syncNow() } }.disabled(busy).background(AtlasLoadedMarker(name: "workspace-sync-now-target"))
                        if peer["enabled"].boolean { Button("暫停同步") { Task { await pauseSync() } }.disabled(pausing).background(AtlasLoadedMarker(name: "workspace-sync-pause-target")) }
                        Button("中斷連線", role: .destructive) { confirmDisconnect = true }.disabled(busy)
                    }
                    if !peer["enabled"].boolean && transfer != .null && !transfer["consumed"].boolean {
                        Button("重新準備傳輸") { confirmResetTransfer = true }.disabled(busy)
                    }
    }
    private var connectionForms: some View {
        VStack(alignment: .leading, spacing: 14) {
                    DisclosureGroup("連接同步中心") {
                        VStack(alignment: .leading, spacing: 14) {
                            FormField(title: "同步中心 HTTPS 網址", text: $url)
                            FormField(title: "這台裝置的名稱", text: $label)
                            FormField(title: "一次性配對碼", text: $code, secure: true)
                            Text("先在同步中心產生配對碼。請只配對自己信任的裝置。").font(.caption).foregroundStyle(.secondary)
                            Button("配對並開始同步") { Task { await run { api in
                                let draft = try WorkspaceDraft(url: url, label: label, code: code)
                                _ = try await api.call("/workspace/connect", method: "POST", body: draft.body)
                                code = ""
                                status = try await api.get("/workspace")
                                syncing = true
                                defer { syncing = false }
                                _ = try await api.call("/workspace/sync", method: "POST", body: .object([:]))
                                try await store.reload()
                            } } }.disabled(busy)
                        }.padding(.top, 10)
                    }
                    DisclosureGroup("配對另一台裝置") {
                        VStack(alignment: .leading, spacing: 12) {
                            Toggle("也同步 API Key 與自訂認證", isOn: $shareSecrets).toggleStyle(.checkbox)
                            Text("秘密經認證 HTTPS 傳送，並在目的端重新加密保存。預設只同步資料。").font(.caption).foregroundStyle(.secondary)
                            Button("產生一次性配對碼") { Task { await run { api in
                                pairing = try await api.call("/workspace/pair", method: "POST", body: .object(["share_secrets": .bool(shareSecrets)]))
                            } } }.disabled(busy)
                            if let pairing {
                                SecureField("一次性配對碼", text: .constant(pairing["code"].string)).textFieldStyle(.roundedBorder)
                                Text("五分鐘內有效，限用一次。請只交給自己的裝置。").font(.caption).foregroundStyle(.secondary)
                                Button("複製配對碼") { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(pairing["code"].string, forType: .string) }
                            }
                        }.padding(.top, 10)
                    }
        }
    }
    private func pairedDevices(_ status: JSONValue) -> some View {
                DisclosureGroup("已配對裝置（\(status["devices"].array.count)）") {
                    ForEach(status["devices"].array, id: \.self) { device in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(device["label"].string).font(.callout.weight(.medium))
                            Text(device["platform"].string + " · " + (device["revoked_at"] != .null ? "已撤銷" : device["share_secrets"].boolean ? "可同步 Key" : "只同步資料")).font(.caption).foregroundStyle(.secondary)
                            if device["revoked_at"] == .null {
                                Button("撤銷這台裝置", role: .destructive) { revokeDevice = device; confirmRevoke = true }.disabled(busy)
                            }
                        }.padding(.vertical, 8)
                    }
                }
    }
    @ViewBuilder private func conflictsList(_ status: JSONValue) -> some View {
                if !status["conflicts"].array.isEmpty {
                    Text("需要你選擇的修改").font(.headline)
                    ForEach(status["conflicts"].array, id: \.self) { item in
                        Button("檢視兩份修改") { Task { await run { api in
                            let value = try await api.get("/workspace/conflict", query: [URLQueryItem(name: "id", value: item["id"].string), URLQueryItem(name: "preview", value: "1")])
                            conflictID = item["id"].string; conflict = value
                        } } }.disabled(busy)
                    }
                }
    }
    private func transferProgress(_ transfer: JSONValue, paused: Bool) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(transferPhase(transfer, paused: paused)).font(.caption)
            Text(transferBytes(transfer)).font(.caption).foregroundStyle(.secondary)
            AtlasLoadedMarker(name: "workspace-transfer-ready").frame(height: 0)
        }
    }
    private func transferPhase(_ transfer: JSONValue, paused: Bool) -> String {
        if transfer["consumed"].boolean { return "資料已儲存，等待確認同步收據" }
        if paused { return "傳輸進度已保存，可恢復同步" }
        if transfer["state"].string == "DOWNLOADING" { return "正在接收同步結果" }
        return "正在上傳，可從已儲存的進度恢復"
    }
    private func transferBytes(_ transfer: JSONValue) -> String {
        let upload = "上傳 " + bytes(transfer["progress_bytes"]) + " / " + bytes(transfer["request_bytes"])
        guard let reply = transfer["reply_bytes"].numeric, reply > 0 else { return upload + " · 等待同步結果" }
        return upload + " · 接收 " + bytes(transfer["downloaded_bytes"]) + " / " + bytes(transfer["reply_bytes"])
    }
    private func snapshot(_ title: String, _ record: JSONValue) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline)
            if record["deleted"].boolean { Text("已移除") }
            ForEach(["name", "label", "title", "display_name", "model_id", "base_url", "description", "notes", "content", "state"], id: \.self) { key in
                if !record["data"][key].string.isEmpty { Text(String(record["data"][key].string.prefix(1200))).textSelection(.enabled) }
            }
            DisclosureGroup("查看欄位差異") {
                let data = JSONValue.object(record["data"].fields.filter { $0.key != "_secret_digest" })
                Text(String(data.pretty.prefix(5000))).font(.system(.callout, design: .monospaced)).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
    private func resolve(_ choice: String, detail: JSONValue) async {
        await run { api in
            _ = try await api.call("/workspace/resolve", method: "POST", body: .object(["id": .string(conflictID), "choice": .string(choice), "confirm": .bool(true), "local_hash": detail["local_hash"]]))
            conflict = nil
            syncing = true
            defer { syncing = false }
            _ = try await api.call("/workspace/sync", method: "POST", body: .object([:]))
            try await store.reload()
        }
    }
    private func bytes(_ value: JSONValue) -> String {
        guard let count = value.numeric, count.isFinite, count >= 0, count <= Double(72 * 1024 * 1024), count.rounded() == count else { return "尚未確認" }
        return ByteCountFormatter.string(fromByteCount: Int64(count), countStyle: .file)
    }
    private func syncNow() async {
        await run { api in
            syncing = true
            defer { syncing = false }
            _ = try await api.call("/workspace/control", method: "POST", body: .object(["enabled": .bool(true), "resumable": .bool(true)]))
            _ = try await api.call("/workspace/sync", method: "POST", body: .object([:]))
            try await store.reload()
        }
    }
    private func pauseSync() async {
        guard !pausing, let api = store.api else { return }
        pausing = true
        defer { pausing = false }
        do {
            _ = try await api.call("/workspace/control", method: "POST", body: .object(["enabled": .bool(false)]))
            status = try await api.get("/workspace")
        } catch { problem = error.localizedDescription }
    }
    private func run(_ action: (AtlasClient) async throws -> Void) async {
        guard !busy else { return }
        guard let api = store.api else { problem = AtlasError.engineUnavailable.localizedDescription; return }
        busy = true; problem = nil
        defer { busy = false }
        do {
            try await action(api)
            status = try await api.get("/workspace")
            if let expires = pairing?["expires_at"].numeric, expires < Date().timeIntervalSince1970 * 1000 { pairing = nil }
        } catch is CancellationError { }
        catch {
            if let latest = try? await api.get("/workspace") { status = latest }
            if status?["peer"]["enabled"].boolean != false { problem = error.localizedDescription }
        }
    }
}
