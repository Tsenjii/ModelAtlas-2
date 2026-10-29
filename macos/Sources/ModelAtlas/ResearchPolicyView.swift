import AppKit
import AtlasCore
import SwiftUI

private extension ResearchPolicyKind {
    var title: String { switch self { case .execution: return "研究執行"; case .inference: return "推論次數"; case .resource: return "Token／USD" } }
}

@MainActor struct ResearchPolicyWorkspace: View {
    @ObservedObject var store: AtlasStore
    var body: some View {
        if let api = store.api { ResearchPolicyPanels(api: api).id(ObjectIdentifier(api)) }
        else { Text("引擎尚未連線，請稍後重新開啟研究設定。").foregroundStyle(.secondary) }
    }
}

@MainActor private struct ResearchPolicyPanels: View {
    @StateObject private var execution: ResearchPolicyController
    @StateObject private var inference: ResearchPolicyController
    @StateObject private var resource: ResearchPolicyController
    @State private var selection = ResearchPolicyKind.execution.rawValue
    init(api: AtlasClient) {
        _execution = StateObject(wrappedValue: ResearchPolicyController(api: api, kind: .execution))
        _inference = StateObject(wrappedValue: ResearchPolicyController(api: api, kind: .inference))
        _resource = StateObject(wrappedValue: ResearchPolicyController(api: api, kind: .resource))
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            AtlasTabs(selection: $selection, items: ResearchPolicyKind.allCases.map { AtlasTab($0.rawValue, $0.title) })
            if selection == ResearchPolicyKind.execution.rawValue { ResearchPolicyPanel(model: execution) }
            if selection == ResearchPolicyKind.inference.rawValue { ResearchPolicyPanel(model: inference) }
            if selection == ResearchPolicyKind.resource.rawValue { ResearchPolicyPanel(model: resource) }
        }
    }
}

@MainActor struct ResearchPolicyPanel: View {
    @ObservedObject var model: ResearchPolicyController
    @State private var credentialsOpen = false
    @State private var historyOpen = false
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text(model.kind.title).font(.headline)
                Spacer()
                Button("更新設定與用量") { Task { await model.reload() } }
                    .disabled(model.reading || model.writing)
                    .background(AtlasLoadedMarker(name: "policy-" + model.kind.rawValue + "-refresh-target"))
                if model.reading || model.writing { ProgressView().controlSize(.small) }
            }
            if let problem = model.problem { Text(problem).foregroundStyle(.red).textSelection(.enabled) }
            if let notice = model.notice { Text(notice).foregroundStyle(.secondary) }
            explanation
            if let snapshot = model.snapshot {
                ResearchPolicyMeter(kind: model.kind, value: snapshot.value)
                if let draft = model.draft {
                    AtlasCard {
                        Text("本機設定").font(.headline)
                        ResearchPolicyFields(model: model, draft: draft)
                        if draft.revision != snapshot.revision {
                            Text("上限已在其他視窗變更，未保存草稿仍保留。請捨棄舊草稿並重讀後再編輯。").foregroundStyle(.red)
                        }
                        HStack {
                            Button("保存上限") { Task { await model.save() } }
                                .buttonStyle(AtlasActionStyle(prominent: true)).disabled(!model.canSave())
                                .background(AtlasLoadedMarker(name: "policy-" + model.kind.rawValue + "-save-target"))
                            Button("捨棄變更並重讀") { Task { await model.reload(discardGlobal: true) } }
                                .disabled(model.reading || model.writing)
                                .background(AtlasLoadedMarker(name: "policy-" + model.kind.rawValue + "-discard-target"))
                        }
                    }
                }
                if model.kind != .execution {
                    HStack {
                        Button("憑證用量與個別上限") { credentialsOpen = true }.disabled(model.locked)
                            .background(AtlasLoadedMarker(name: "policy-" + model.kind.rawValue + "-credentials-target"))
                        Button("上限變更紀錄") { historyOpen = true }.disabled(model.reading || model.writing)
                            .background(AtlasLoadedMarker(name: "policy-" + model.kind.rawValue + "-history-target"))
                    }
                }
            } else { Text("尚未取得有效設定；讀取成功後才能保存。").foregroundStyle(.secondary) }
        }
        .background(Group {
            if model.snapshot != nil { AtlasLoadedMarker(name: "policy-" + model.kind.rawValue + "-loaded") }
            if !model.locked { AtlasLoadedMarker(name: "policy-" + model.kind.rawValue + "-ready") }
            if model.needsReload { AtlasLoadedMarker(name: "policy-" + model.kind.rawValue + "-needs-reload") }
        })
        .sheet(isPresented: $credentialsOpen, onDismiss: { model.activeCredential = nil }) { ResearchCredentialPolicies(model: model) }
        .sheet(isPresented: $historyOpen) { ResearchPolicyHistoryView(model: model) }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in Task { await model.reload() } }
        .task {
            await model.reload()
            while !Task.isCancelled {
                do {
                    try await Task.sleep(nanoseconds: 15_000_000_000)
                    if let reset = model.snapshot?.value["reset_at"].numeric, Date().timeIntervalSince1970 * 1000 >= reset { await model.reload() }
                } catch { return }
            }
        }
    }
    @ViewBuilder private var explanation: some View {
        if model.kind == .execution {
            Text("設定同時處理的研究輪數，預設 1、最多 4 輪。已開始的工作先完成，新上限用於後續工作；增加輪數可能更快用完已核准額度。保存不會啟用排程，也不會恢復已暫停或取消的研究。").foregroundStyle(.secondary)
        } else {
            Text("只限制此引擎新核准的研究推論。日常聊天與重用既有觀測不扣此上限；已核准後的失敗、取消及重啟仍保留用量。每日 UTC 00:00（台灣 08:00）重新計數。").foregroundStyle(.secondary)
            if model.kind == .inference { Text("0 表示停止核准。相同供應商／帳戶／憑證的模型、群組、端點及 Key 版本共同計數；這些次數不是供應商官方剩餘額度。").font(.callout).foregroundStyle(.secondary) }
            if model.kind == .resource { Text("未勾選代表不追加該項本機上限，不代表免費或供應商額度無限。每次先預留，只有同次請求的完整回報才結算；未知或中斷保留預留。此處不是供應商帳單的硬上限。").font(.callout).foregroundStyle(.secondary) }
        }
    }
}

@MainActor private struct ResearchPolicyFields: View {
    @ObservedObject var model: ResearchPolicyController
    let draft: ResearchPolicyDraft
    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 225), alignment: .top)], alignment: .leading, spacing: 16) {
            ForEach(draft.keys, id: \.self) { key in
                VStack(alignment: .leading, spacing: 6) {
                    if draft.nullable.contains(key) {
                        Toggle("追加" + title(key), isOn: Binding(get: { draft.enabled.contains(key) }, set: { model.setEnabled(key, value: $0, credential: draft.credential) }))
                    }
                    FormField(title: title(key), text: Binding(get: { draft.fields[key] ?? "" }, set: { model.edit(key, text: $0, credential: draft.credential) }))
                        .disabled(draft.nullable.contains(key) && !draft.enabled.contains(key))
                        .background(AtlasLoadedMarker(name: "policy-" + model.kind.rawValue + "-field-" + key))
                }
            }
        }.disabled(model.locked)
    }
    private func title(_ key: String) -> String {
        if key == "daily_limit" { return draft.credential == nil ? "全部研究每日推論上限（0–2000）" : "此憑證每日推論上限（0–2000）" }
        return ["max_parallel_runs":"同時研究輪數（1–4）", "default_credential_limit":"每份憑證預設每日上限（0–2000）", "daily_tokens":"全部研究每日 token 上限", "daily_usd":"全部研究每日 USD 上限", "credential_tokens":"每份憑證預設 token 上限", "credential_usd":"每份憑證預設 USD 上限", "reserve_tokens":"每次預留 token（1–1,000,000）", "reserve_usd":"每次預留 USD", "tokens":"此憑證每日 token 上限", "usd":"此憑證每日 USD 上限"][key] ?? key
    }
}

private struct ResearchPolicyMeter: View {
    let kind: ResearchPolicyKind
    let value: JSONValue
    var body: some View {
        AtlasCard {
            if kind == .execution {
                Text("目前執行 " + value["running_runs"].display + " 輪 · 已排入 " + value["scheduled_rounds"].display + " 輪排程")
                Text("設定上限 " + value["policy"]["max_parallel_runs"].display + " 輪").foregroundStyle(.secondary)
            } else {
                Text(value["day"].string + " · 今日用量").font(.headline)
                if kind == .inference { Text("全部研究已核准 " + value["today_used"].display + " / " + value["daily_limit"].display + " 次").monospacedDigit() }
                else { ResearchResourceUsage(value: value["usage"]) }
                Text("從 " + date(value["started_at"]) + " 開始記錄；升級前或其他裝置的耗用不在此計數內。").font(.caption).foregroundStyle(.secondary)
                if kind == .resource, (value["older_unbudgeted_requests"].numeric ?? 0) > 0 { Text("另有 " + value["older_unbudgeted_requests"].display + " 次較早核准的研究，未納入 token／USD 預算。").font(.caption).foregroundStyle(.secondary) }
            }
        }
    }
    private func date(_ value: JSONValue) -> String { guard let time = value.numeric else { return "尚無資料" }; return Date(timeIntervalSince1970: time / 1000).formatted(date: .numeric, time: .shortened) }
}

private struct ResearchResourceUsage: View {
    let value: JSONValue
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("已核准 " + value["requests"].display + " 次 · 待結算 " + value["pending_requests"].display + " 次")
            Text("預算計入：" + value["accounted_tokens"].display + " token / " + value["accounted_usd"].display + " USD").textSelection(.enabled)
            Text("完整回報：" + amount(value["reported_tokens"]) + " token / " + amount(value["reported_usd"]) + " USD").textSelection(.enabled)
            if (value["unknown_token_requests"].numeric ?? 0) > 0 || (value["unknown_usd_requests"].numeric ?? 0) > 0 {
                Text("缺少完整回報：token " + value["unknown_token_requests"].display + " 次 / USD " + value["unknown_usd_requests"].display + " 次；未知不視為 0。").font(.caption).foregroundStyle(.secondary)
            }
            if (value["overrun_requests"].numeric ?? 0) > 0 { Text("超出原預留 " + value["overrun_requests"].display + " 次；原回報已保留並計入後續核准。").font(.caption).foregroundStyle(.secondary) }
        }
    }
    private func amount(_ value: JSONValue) -> String { value == .null ? "尚無完整資料" : value.display }
}

@MainActor private struct ResearchCredentialPolicies: View {
    @ObservedObject var model: ResearchPolicyController
    @Environment(\.dismiss) private var dismiss
    @State private var selected: ResearchCredentialID?
    @State private var search = ""
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack { Text("憑證用量與上限").font(.title2.weight(.semibold)); Spacer(); Button("關閉") { dismiss() } }
                if let selected {
                    Button("返回憑證清單（保留草稿）") { self.selected = nil; model.activeCredential = nil }
                    ResearchCredentialPolicyEditor(model: model, id: selected)
                } else {
                    HStack {
                        Button("重新讀取清單") { Task { await model.reload() } }.disabled(model.reading || model.writing)
                        if model.snapshot?.nextCursor.isEmpty == false {
                            Button("讀取更多憑證") { Task { await model.moreCredentials() } }.disabled(model.locked)
                                .background(AtlasLoadedMarker(name: "policy-" + model.kind.rawValue + "-more-target"))
                        }
                        Spacer(); Text("已讀 " + String(model.snapshot?.credentials.count ?? 0) + " 份").foregroundStyle(.secondary)
                    }
                    SearchField(placeholder: "搜尋供應商、帳戶或 Key", text: $search)
                    if let problem = model.problem { Text(problem).foregroundStyle(.red) }
                    Text("同一供應商／帳戶／憑證跨模型、群組、端點及 Key 版本共同計數。不可用或歷史憑證的既有用量仍保留。").font(.callout).foregroundStyle(.secondary)
                    if let snapshot = model.snapshot {
                        LazyVStack(alignment: .leading, spacing: 12) {
                            ForEach(snapshot.credentials.indices, id: \.self) { index in
                                let row = snapshot.credentials[index]
                                if matches(row), let id = try? ResearchCredentialID(row) {
                                    AtlasCard {
                                        Text(row["provider_name"].string + " / " + row["account_label"].string + " / " + row["label"].string).font(.headline)
                                        Text(id.provider + " / " + id.account + " / " + id.credential).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
                                        if model.kind == .inference { Text("已核准 " + row["used"].display + " / " + row["daily_limit"].display + " 次") }
                                        else { ResearchResourceUsage(value: row["usage"]) }
                                        Text((row["override"].boolean ? "個別上限" : "預設上限") + (row["available"].boolean ? "" : " · 憑證目前不可用")).font(.caption).foregroundStyle(.secondary)
                                        Button("編輯此憑證上限") { model.beginCredential(id); selected = id }.disabled(model.locked)
                                            .background(AtlasLoadedMarker(name: "policy-" + model.kind.rawValue + "-edit-" + String(index)))
                                    }
                                }
                            }
                        }
                    }
                }
            }.padding(24)
        }.frame(width: 740, height: 660)
            .task { model.activeCredential = nil; await model.reload() }
    }
    private func matches(_ row: JSONValue) -> Bool { search.isEmpty || ["provider_name", "account_label", "label", "provider_id", "account_id", "credential_id"].map { row[$0].string }.joined(separator: " ").localizedCaseInsensitiveContains(search) }
}

@MainActor struct ResearchCredentialPolicyEditor: View {
    @ObservedObject var model: ResearchPolicyController
    let id: ResearchCredentialID
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(id.provider + " / " + id.account + " / " + id.credential).font(.headline).textSelection(.enabled)
            Text("換 Key 或改用預設上限都不會清除今日用量。這份設定只屬於目前引擎。").foregroundStyle(.secondary)
            if let problem = model.problem { Text(problem).foregroundStyle(.red) }
            if let notice = model.notice { Text(notice).foregroundStyle(.secondary) }
            Button("更新此憑證用量") { Task { await model.reload(credential: id) } }.disabled(model.reading || model.writing)
                .background(AtlasLoadedMarker(name: "policy-credential-refresh-target"))
            if let row = model.snapshot?.row(id) {
                if model.kind == .inference { Text("今日已核准 " + row["used"].display + " 次").monospacedDigit() }
                else { ResearchResourceUsage(value: row["usage"]) }
            } else { Text("此憑證不在已讀分頁，請先返回清單並重新讀取。").foregroundStyle(.secondary) }
            if let draft = model.credentialDrafts[id] {
                ResearchPolicyFields(model: model, draft: draft)
                if draft.revision != model.snapshot?.revision { Text("設定版本已變更；未保存草稿保留，請明確捨棄後再編輯。").foregroundStyle(.red) }
                HStack {
                    Button("保存憑證上限") { Task { await model.save(credential: id) } }.buttonStyle(AtlasActionStyle(prominent: true)).disabled(!model.canSave(id))
                        .background(AtlasLoadedMarker(name: "policy-credential-save-target"))
                    Button("改用預設上限") { Task { await model.save(credential: id, reset: true) } }.disabled(!model.canSave(id, reset: true))
                        .background(AtlasLoadedMarker(name: "policy-credential-reset-target"))
                }
                Button("捨棄此草稿並重讀") { Task { await model.reload(credential: id, discardCredential: true) } }.disabled(model.reading || model.writing)
                    .background(AtlasLoadedMarker(name: "policy-credential-discard-target"))
            }
        }.background(Group { if !model.locked { AtlasLoadedMarker(name: "policy-credential-ready") } })
    }
}

@MainActor private struct ResearchPolicyHistoryView: View {
    @ObservedObject var model: ResearchPolicyController
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack { Text("上限變更紀錄").font(.title2.weight(.semibold)); Spacer(); Button("關閉") { dismiss() } }
                HStack {
                    Button("更新變更紀錄") { Task { await model.loadHistory() } }.disabled(model.reading || model.writing)
                    if model.historyNext > 0 { Button("讀取更早紀錄") { Task { await model.loadHistory(more: true) } }.disabled(model.reading || model.writing) }
                }
                if let problem = model.historyProblem { Text(problem).foregroundStyle(.red) }
                ForEach(model.history, id: \.self) { event in
                    AtlasCard {
                        Text("版本 " + event["revision"].display + " · " + action(event["action"].string)).font(.headline)
                        if event["action"].string != "POLICY" { Text(event["provider_id"].string + " / " + event["account_id"].string + " / " + event["credential_id"].string).textSelection(.enabled) }
                        if let time = event["changed_at"].numeric { Text(Date(timeIntervalSince1970: time / 1000).formatted()).font(.caption).foregroundStyle(.secondary) }
                        if event["action"].string == "RESET" { Text("改用預設上限；既有用量保持。").foregroundStyle(.secondary) }
                        else if model.kind == .inference {
                            Text("每日推論上限 " + event["daily_limit"].display + " 次")
                            if event["action"].string == "POLICY" { Text("逐憑證預設 " + event["default_credential_limit"].display + " 次") }
                        } else {
                            ForEach(resourceKeys(event), id: \.self) { key in Text(resourceTitle(key) + "：" + amount(event["value"][key])).textSelection(.enabled) }
                        }
                    }
                }
            }.padding(24)
        }.frame(width: 740, height: 660).task { await model.loadHistory() }
    }
    private func action(_ value: String) -> String { ["POLICY":"全部研究設定", "CREDENTIAL":"憑證個別設定", "RESET":"改用預設上限"][value] ?? value }
    private func resourceKeys(_ event: JSONValue) -> [String] { event["action"].string == "POLICY" ? ["daily_tokens", "daily_usd", "credential_tokens", "credential_usd", "reserve_tokens", "reserve_usd"] : ["tokens", "usd"] }
    private func resourceTitle(_ key: String) -> String { ["daily_tokens":"每日 token 上限", "daily_usd":"每日 USD 上限", "credential_tokens":"逐憑證預設 token 上限", "credential_usd":"逐憑證預設 USD 上限", "reserve_tokens":"每次預留 token", "reserve_usd":"每次預留 USD", "tokens":"此憑證 token 上限", "usd":"此憑證 USD 上限"][key] ?? key }
    private func amount(_ value: JSONValue) -> String { value == .null ? "未追加本機上限" : value.display }
}
