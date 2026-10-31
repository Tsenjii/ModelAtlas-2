import AppKit
import AtlasCore
import SwiftUI

@MainActor final class AccountCheckinController: ObservableObject {
    @Published private(set) var state: AccountCheckinSnapshot?
    @Published private(set) var pending = false
    @Published private(set) var problem: String?
    @Published private(set) var submitting = Set<String>()
    private var task: Task<Void, Never>?
    private var revision = 0
    var timezone: String { TimeZone.current.identifier }
    var today: String { AccountCheckinSnapshot.localDate(Date(), timezone: timezone) ?? "" }
    var current: AccountCheckinSnapshot? { state.flatMap { $0.date == today && $0.timezone == timezone ? $0 : nil } }
    func rows(providerID: String) -> [CheckinAccount]? { current.map { $0.accounts.filter { $0.providerID == providerID } } }
    func refreshIfNeeded(using api: AtlasClient) { if current == nil && !pending { refresh(using: api) } }
    func cancel() { revision += 1; task?.cancel(); task = nil; pending = false }
    func refresh(using api: AtlasClient) {
        cancel(); let ticket = revision, zone = timezone
        pending = true; problem = nil
        task = Task { [weak self] in
            guard let self else { return }
            defer { if revision == ticket { pending = false; task = nil } }
            do {
                try Task.checkCancellation()
                let value = try await api.get("/checkins/accounts", query: [URLQueryItem(name: "timezone", value: zone)])
                try Task.checkCancellation()
                guard revision == ticket, zone == timezone else { return }
                state = try AccountCheckinSnapshot(value, date: today, timezone: zone)
            } catch is CancellationError { }
            catch { if revision == ticket { problem = "無法更新簽到狀態，請重新載入。" } }
        }
    }
    func complete(_ account: CheckinAccount, using api: AtlasClient) async throws {
        guard !submitting.contains(account.id) else { return }
        guard let snapshot = current, let target = snapshot.accounts.first(where: { $0.id == account.id }), target.required else {
            refresh(using: api); throw DraftProblem("日期或帳戶狀態已變更，請等候重新載入。")
        }
        guard target.pending else { return }
        cancel(); problem = nil; submitting.insert(target.id); defer { submitting.remove(target.id) }
        do {
            let value = try await api.call("/checkins/accounts", method: "POST", body: target.request(date: snapshot.date, timezone: snapshot.timezone))
            var verified = snapshot; try verified.apply(value, account: target)
            if var latest = state, latest.date == snapshot.date, latest.timezone == snapshot.timezone {
                try latest.apply(value, account: target); state = latest
            }
            if current == nil { refresh(using: api) }
        } catch {
            if case AtlasError.http(409) = error { refresh(using: api); throw DraftProblem("日期已切換，請在狀態更新後再標記完成。") }
            throw error
        }
    }
}

@MainActor struct AccountCheckinPanel: View {
    @ObservedObject var controller: AccountCheckinController
    let providerID: String
    let api: AtlasClient?
    var body: some View {
        AtlasCard {
            HStack {
                Label("每日簽到", systemImage: "calendar.badge.clock").font(.headline)
                Spacer()
                Button { if let api { controller.refresh(using: api) } } label: { Image(systemName: "arrow.clockwise") }.buttonStyle(.borderless).help("重新載入簽到狀態").disabled(controller.pending)
            }
            Text("\(controller.today) · \(controller.timezone)").font(.caption).foregroundStyle(.secondary)
            if controller.pending { ProgressView("更新簽到狀態…").controlSize(.small) }
            if let problem = controller.problem { Text(problem).font(.caption).foregroundStyle(.red) }
            if let rows = controller.rows(providerID: providerID) {
                ForEach(rows.filter(\.required)) { row in
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Text(row.label).font(.subheadline.weight(.semibold))
                            Spacer()
                            Label(row.pending ? "待簽到" : "今日已完成", systemImage: row.pending ? "clock" : "checkmark.circle")
                                .font(.caption).foregroundStyle(row.pending ? Color.secondary : Color.primary)
                        }
                        if row.pending {
                            HStack {
                                Button("開啟簽到頁") { openHTTPS(row.url) }.disabled(!safeURL(row.url))
                                Spacer()
                                ActionButton(title: "標記此帳戶已完成") { if let api { try await controller.complete(row, using: api) } }
                                    .disabled(controller.submitting.contains(row.id) || api == nil)
                            }
                        } else if let at = row.completedAt {
                            Text("手動標記 · " + Date(timeIntervalSince1970: at / 1000).formatted(date: .omitted, time: .shortened)).font(.caption).foregroundStyle(.secondary)
                        }
                        AtlasLoadedMarker(name: "checkin-" + row.id + (row.pending ? "-pending" : "-completed")).frame(height: 0)
                    }.padding(.vertical, 4)
                    if row.id != rows.filter(\.required).last?.id { Divider() }
                }
                AtlasLoadedMarker(name: "account-checkins-loaded").frame(height: 0)
            } else if !controller.pending {
                Button("載入簽到狀態") { if let api { controller.refresh(using: api) } }
            }
            Text("在網站完成簽到後，再手動標記對應帳戶；這不代表已確認獲得額度。").font(.caption).foregroundStyle(.secondary)
        }.task { if let api { controller.refreshIfNeeded(using: api) } }
    }
    private func safeURL(_ value: String) -> Bool {
        guard let url = URL(string: value) else { return false }; return url.scheme == "https" && url.user == nil && url.password == nil
    }
}
