import AppKit
import AtlasCore
import SwiftUI
import UserNotifications
import UniformTypeIdentifiers

struct SettingsView: View {
    @ObservedObject var store: AtlasStore
    @Environment(\.dismiss) private var dismiss
    @AppStorage("appearance") private var appearance = "system"
    @State private var tab = "一般"
    @State private var timezone = "Asia/Taipei"
    @State private var performance = "7d"
    @State private var background = false
    @State private var notifications = false
    @State private var interval = "60"
    @State private var maxScopes = "2"
    @State private var backup: JSONValue?
    @State private var records: [JSONValue] = []
    @State private var notificationRecords: [JSONValue] = []
    @State private var filter = ""
    @State private var offset = 0
    @State private var detail: JSONValue?
    @State private var hasMore = false
    @State private var rules: JSONValue = .object([:])
    @State private var quotaThreshold = "100"
    var body: some View {
        VStack(spacing: 0) {
            HStack { Text("設定與資料").font(.title2.weight(.semibold)); Spacer(); Button("完成") { dismiss() }.keyboardShortcut(.cancelAction) }.padding(25)
            Picker("設定分區", selection: $tab) { ForEach(["一般", "資料", "研究", "請求紀錄", "通知"], id: \.self) { Text($0).tag($0) } }.pickerStyle(.segmented).padding(.horizontal, 24).padding(.bottom, 16)
            Divider()
            if tab == "研究" { ResearchView(store: store) }
            else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        if tab == "一般" { general }
                        if tab == "資料" { data }
                        if tab == "請求紀錄" { requests }
                        if tab == "通知" {
                            AtlasCard {
                                Text("通知紀錄").font(.headline)
                                Text("通知預設關閉；啟用後僅在 App 執行期間投遞本機通知。").foregroundStyle(.secondary)
                                ActionButton(title: "重新載入") { guard let api = store.api else { return }; notificationRecords = try await api.get("/notifications").requiredArray() }
                                JSONTree(value: .array(notificationRecords))
                            }
                        }
                    }.padding(24)
                }
            }
        }.frame(width: 850, height: 740)
        .task {
            timezone = store.settings["timezone"].string; performance = store.settings["performance_window"].string
            notifications = store.settings["notifications"].boolean; background = store.settings["background"]["enabled"].boolean
            interval = store.settings["background"]["interval_minutes"].display; maxScopes = store.settings["background"]["max_scopes"].display
            rules = store.settings["notification_rules"]; quotaThreshold = rules["quota_remaining_below"].display
            await loadRequests()
            if let api = store.api { notificationRecords = (try? await api.get("/notifications").requiredArray()) ?? [] }
        }
    }
    private var general: some View {
        VStack(spacing: 20) {
            AtlasCard {
                Text("外觀").font(.headline)
                Picker("色彩模式", selection: $appearance) { Text("跟隨系統").tag("system"); Text("淺色").tag("light"); Text("深色").tag("dark") }.pickerStyle(.segmented)
            }
            AtlasCard {
                Text("使用偏好").font(.headline)
                FormField(title: "時區", text: $timezone)
                Picker("效能預設時間窗", selection: $performance) { ForEach(["1h", "24h", "7d", "30d", "all"], id: \.self) { Text($0).tag($0) } }
                Toggle("背景資料同步", isOn: $background)
                HStack { FormField(title: "同步間隔（分鐘，15–10080）", text: $interval); FormField(title: "每輪連線數（1–10）", text: $maxScopes) }
                Toggle("通知總開關", isOn: $notifications)
                DisclosureGroup("個別通知規則") {
                    ForEach(["became_free", "quota_low", "checkin_pending", "favorite_recovered", "price_changed", "outage", "recovery"], id: \.self) { key in
                        Toggle(["became_free": "模型變為免費", "quota_low": "額度不足", "checkin_pending": "待簽到", "favorite_recovered": "收藏模型恢復", "price_changed": "價格變動", "outage": "服務異常", "recovery": "服務恢復"][key]!, isOn: Binding(get: { rules[key].boolean }, set: { rules[key] = .bool($0) })).toggleStyle(.checkbox)
                    }
                    FormField(title: "額度提醒門檻", text: $quotaThreshold)
                }
                ActionButton(title: "儲存偏好") {
                    guard let minutes = Int(interval), let scopes = Int(maxScopes), let threshold = Int(quotaThreshold) else { throw AtlasError.invalidJSON }
                    var selectedRules = rules; selectedRules["quota_remaining_below"] = .number(Double(threshold))
                    let submitted: JSONValue = .object(["timezone": .string(timezone), "performance_window": .string(performance), "notifications": .bool(notifications), "notification_rules": selectedRules, "background": .object(["enabled": .bool(background), "interval_minutes": .number(Double(minutes)), "max_scopes": .number(Double(scopes))])])
                    if notifications && !store.settings["notifications"].boolean {
                        let granted = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
                        guard granted else { throw AtlasError.http(403) }
                    }
                    _ = try await store.mutate("/settings", body: submitted)
                }
            }
        }
    }
    private var data: some View {
        VStack(spacing: 20) {
            AtlasCard {
                Label("資料留在這台 Mac", systemImage: "externaldrive").font(.headline)
                Text(store.engine.directory.path).font(.system(.callout, design: .monospaced)).textSelection(.enabled)
                Text("資料庫保存完整對話、telemetry 與研究歷史；加密主金鑰和內部認證放在 macOS 鑰匙圈。").foregroundStyle(.secondary)
                Button("在 Finder 顯示資料目錄") { NSWorkspace.shared.activateFileViewerSelecting([store.engine.directory]) }
            }
            AtlasCard {
                Text("可攜設定").font(.headline)
                Text("匯出供應商、模型、別名、設定與研究定義。不含秘密值、原始聊天或完整 telemetry；匯入的 Key 會停用，需重新填入。").font(.callout).foregroundStyle(.secondary)
                HStack {
                    Button("匯出設定…") { Task { await store.export("/backup", filename: "ModelAtlas-Settings.json") } }
                    Button("選擇設定檔…") {
                        let panel = NSOpenPanel(); panel.allowedContentTypes = [.json]; panel.allowsMultipleSelection = false
                        if panel.runModal() == .OK, let url = panel.url {
                            do {
                                let attributes = try url.resourceValues(forKeys: [.fileSizeKey])
                                guard (attributes.fileSize ?? Int.max) <= 15 << 20 else { throw AtlasError.http(413) }
                                backup = try JSONDecoder().decode(JSONValue.self, from: Data(contentsOf: url))
                            } catch { store.problem = AtlasError.invalidJSON.localizedDescription }
                        }
                    }
                }
                if let backup {
                    Text("\(backup["providers"].array.count) 個供應商 · \(backup["models"].array.count) 個模型定義").font(.callout)
                    ActionButton(title: "確認匯入設定") { _ = try await store.mutate("/backup/restore", body: .object(["confirm": .bool(true), "backup": backup])); self.backup = nil }
                }
                if let notice = store.notice { Text(notice).foregroundStyle(atlasAccent) }
            }
        }
    }
    private var requests: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Picker("結果", selection: $filter) { Text("全部結果").tag(""); Text("成功").tag("SUCCESS"); Text("失敗").tag("FAILED"); Text("取消").tag("CANCELLED") }.onChange(of: filter) { _ in Task { await loadRequests() } }
                Spacer()
                Button("匯出全部 NDJSON…") { Task { await store.export("/telemetry/export", query: filter.isEmpty ? [] : [URLQueryItem(name: "outcome", value: filter)], filename: "ModelAtlas-Requests.ndjson") } }
            }
            if let detail { AtlasCard { HStack { Text("請求收據").font(.headline); Spacer(); Button("關閉收據") { self.detail = nil } }; JSONTree(value: detail) } }
            ForEach(records, id: \.self) { item in
                Button {
                    Task { do { detail = try await store.api?.get("/telemetry/detail", query: [URLQueryItem(name: "id", value: item["id"].string)]) } catch { store.problem = error.localizedDescription } }
                } label: {
                    AtlasCard { HStack { VStack(alignment: .leading, spacing: 5) { Text(item["model_id"].string).font(.headline); Text(store.providerName(item["provider_id"].string)).font(.caption).foregroundStyle(.secondary) }; Spacer(); SmallBadge(text: item["outcome"].string, color: item["outcome"].string == "SUCCESS" ? atlasAccent : .secondary); Text(item["created_at"].numeric.map { Date(timeIntervalSince1970: $0 / 1000).formatted(date: .abbreviated, time: .shortened) } ?? "") } }
                }.buttonStyle(.plain)
            }
            if hasMore { Button("載入更多請求") { Task { await loadRequests(more: true) } } }
            if records.isEmpty { Text("還沒有請求紀錄。").foregroundStyle(.secondary) }
        }
    }
    private func loadRequests(more: Bool = false) async {
        guard let api = store.api else { return }
        let next = more ? records.count : 0, requestedFilter = filter
        do {
            var query = [URLQueryItem(name: "limit", value: "100"), URLQueryItem(name: "offset", value: String(next))]
            if !filter.isEmpty { query.append(URLQueryItem(name: "outcome", value: filter)) }
            let page = try await api.get("/telemetry", query: query).requiredArray()
            guard filter == requestedFilter else { return }
            records = more ? records + page : page; hasMore = page.count == 100
        } catch { store.problem = error.localizedDescription }
    }
}

extension AtlasStore {
    func observeNotifications() async {
        while !Task.isCancelled {
            if ready, settings["notifications"].boolean, let api {
                do {
                    let permission = await UNUserNotificationCenter.current().notificationSettings()
                    if permission.authorizationStatus == .authorized {
                        let items = try await api.get("/notifications").requiredArray()
                        for item in items.reversed().filter({ $0["seen_at"] == .null }).prefix(5) {
                            let content = UNMutableNotificationContent()
                            content.title = "ModelAtlas · " + item["kind"].string; content.body = item["body"].string
                            try await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: item["id"].string, content: content, trigger: nil))
                            _ = try await api.call("/notifications/seen", method: "POST", body: .object(["ids": .array([item["id"]])]))
                        }
                    }
                } catch { /* A later scheduled poll retries without erasing events. */ }
            }
            try? await Task.sleep(nanoseconds: 30_000_000_000)
        }
    }
}
