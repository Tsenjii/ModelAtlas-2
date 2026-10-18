import AtlasCore
import SwiftUI

let adapterOptions = ["GENERIC", "NEW_API", "ANTHROPIC", "OPENROUTER_FREE", "AMD_SHARED", "AMD_DEDICATED", "NVIDIA_NIM_HOSTED", "NVIDIA_NIM_SELF_HOSTED", "GEMINI_FREE", "GROQ_FREE"]
let endpointOptions = ["CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC_MESSAGES", "GEMINI_NATIVE", "IMAGES_GENERATION", "IMAGES_EDIT", "AUDIO", "AUDIO_SPEECH", "GEMINI_SPEECH", "EMBEDDINGS", "RERANK"]

@MainActor struct ProvidersView: View {
    @ObservedObject var store: AtlasStore
    @ObservedObject private var checkins: AccountCheckinController
    @State private var search = ""
    @State private var sort = "name"
    @State private var filter = "all"
    init(store: AtlasStore, initialFilter: String = "all") {
        self.store = store; checkins = store.checkins; _filter = State(initialValue: initialFilter)
    }
    private var filtered: [JSONValue] {
        let freeProviders = Set(store.activeModels.filter { $0["price_state"].string == "FREE_NOW" }.map { $0["provider_id"].string })
        return store.providers.filter { provider in
            let matches = filter == "all" || (filter == "free" && freeProviders.contains(provider.id)) || (filter == "checkin" && checkins.rows(providerID: provider.id)?.contains(where: \.pending) == true)
            return matches && (search.isEmpty || (provider["name"].string + " " + provider["base_url"].string + " " + provider["description"].string).localizedCaseInsensitiveContains(search))
        }.sorted { a, b in
            if sort == "smart" { let filters = ModelFilters(); @MainActor func score(_ p: JSONValue) -> Double? { let scores = store.activeModels.filter { $0["provider_id"] == p["id"] }.compactMap { filters.ranking($0, weights: store.settings["ranking_weights"]).score }; return scores.isEmpty ? nil : scores.reduce(0, +) / Double(scores.count) }; let x = score(a), y = score(b); if x != y { return (x ?? -1) > (y ?? -1) } }
            return a["name"].string.localizedStandardCompare(b["name"].string) == .orderedAscending
        }
    }
    var body: some View {
        let entries = filtered
        let freeCounts = Dictionary(grouping: store.activeModels.filter { $0["price_state"].string == "FREE_NOW" }, by: { $0["provider_id"].string }).mapValues { $0.count }
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .center) {
                    PageHeading(eyebrow: "Connections", title: "供應商", subtitle: "\(store.providers.count) 個供應商 · \(store.accounts.count) 個帳戶")
                    Spacer()
                    Button { store.newProviderOpen = true } label: { Label("新增供應商", systemImage: "plus") }
                        .buttonStyle(AtlasActionStyle(prominent: true))
                }
                HStack(spacing: 12) { SearchField(placeholder: "搜尋供應商、網址或描述", text: $search); AtlasChoiceMenu(title: "排序", selection: $sort, items: [AtlasTab("name", "依名稱"), AtlasTab("smart", "依模型評分")]).frame(width: 130) }
                HStack(spacing: 6) {
                    AtlasFilterChip(title: "全部", selected: filter == "all") { filter = "all" }
                    AtlasFilterChip(title: "有免費連線", icon: "gift", selected: filter == "free") { filter = "free" }
                    AtlasFilterChip(title: "待簽到", icon: "calendar.badge.clock", selected: filter == "checkin") { filter = "checkin" }
                    Spacer()
                    Text("\(entries.count) 個供應商").font(.caption).foregroundStyle(.secondary)
                }
                if store.providers.isEmpty {
                    EmptyCard(icon: "server.rack", title: "接上第一個供應商", detail: "貼上 Base URL 與 Key，檢查結果後再加入。", action: "新增供應商") { store.newProviderOpen = true }.frame(height: 350)
                } else if filter == "checkin" && (checkins.current == nil || checkins.problem != nil) {
                    if checkins.pending { ProgressView("更新簽到狀態…").frame(maxWidth: .infinity, minHeight: 200) }
                    else { EmptyCard(icon: "calendar.badge.exclamationmark", title: "簽到狀態尚未更新", detail: checkins.problem ?? "重新載入後即可按今天的帳戶狀態篩選。", action: "重新載入") { if let api = store.api { checkins.refresh(using: api) } }.frame(height: 280) }
                } else if entries.isEmpty {
                    EmptyCard(icon: filter == "checkin" ? "checkmark.circle" : "magnifyingglass", title: filter == "checkin" ? "今天沒有待簽到帳戶" : "沒有符合的供應商", detail: filter == "free" ? "只顯示已有 FREE NOW 連線的供應商；價格未確認不會當成免費。" : filter == "checkin" ? "已設定每日簽到的帳戶都已完成，或目前沒有符合搜尋的帳戶。" : "試試其他名稱或網址。", action: "清除篩選") { search = ""; filter = "all" }.frame(height: 280)
                } else {
                    VStack(spacing: 0) {
                        ForEach(entries) { provider in
                            ProviderRow(store: store, provider: provider, freeCount: freeCounts[provider.id] ?? 0, checkins: checkins.rows(providerID: provider.id))
                            if provider.id != entries.last?.id { Divider().padding(.leading, 64) }
                        }
                    }.background(atlasSurface).cornerRadius(12)
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(atlasHairline, lineWidth: 0.5))
                }
            }.padding(24).frame(maxWidth: 1150).frame(maxWidth: .infinity)
        }.task { if let api = store.api { checkins.refreshIfNeeded(using: api) } }
    }
}

private struct ProviderRow: View {
    @ObservedObject var store: AtlasStore
    let provider: JSONValue
    let freeCount: Int
    let checkins: [CheckinAccount]?
    private var providerID: String { provider["id"].string }
    var body: some View {
        Button { store.detailProvider = provider } label: {
            HStack(spacing: 12) {
                Image(systemName: "server.rack").font(.system(size: 16, weight: .medium)).foregroundStyle(atlasTextAccent)
                    .frame(width: 30, height: 30).background(atlasAccent.opacity(0.06)).cornerRadius(7)
                VStack(alignment: .leading, spacing: 5) {
                    Text(provider["name"].string).font(.system(size: 14, weight: .semibold)).lineLimit(1)
                    Text(provider["base_url"].string).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    if !provider["description"].string.isEmpty { Text(provider["description"].string).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                }
                Spacer(minLength: 8)
                VStack(alignment: .trailing, spacing: 5) {
                    Text("\(store.accounts.filter { $0["provider_id"].string == providerID }.count) 帳戶 · \(store.credentials.filter { $0["provider_id"].string == providerID && $0["enabled"].boolean }.count) 啟用 Key").font(.caption).foregroundStyle(.secondary)
                    SmallBadge(text: "\(store.activeModels.filter { $0["provider_id"].string == providerID }.count) 條模型連線", color: atlasAccent)
                    if freeCount > 0 { Text("\(freeCount) 條免費連線").font(.system(size: 10)).foregroundStyle(atlasTextAccent) }
                    if provider["config"]["requires_daily_checkin"].boolean {
                        if let rows = checkins {
                            let waiting = rows.filter(\.pending).count
                            if waiting > 0 { Label("\(waiting) 個帳戶待簽到", systemImage: "calendar.badge.clock").font(.caption2).foregroundStyle(.secondary) }
                            else if !rows.isEmpty { Label("今日簽到已完成", systemImage: "checkmark.circle").font(.caption2).foregroundStyle(.secondary) }
                        } else { Text("簽到狀態待更新").font(.caption2).foregroundStyle(.secondary) }
                    }
                }
                Image(systemName: "chevron.right").font(.caption2.weight(.semibold)).foregroundStyle(.tertiary)
            }.padding(.horizontal, 16).padding(.vertical, 13).frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
        }.buttonStyle(AtlasRowButtonStyle()).help(provider["name"].string + " · " + provider["base_url"].string)
    }
}

struct AddProviderView: View {
    @ObservedObject var store: AtlasStore
    var seed: JSONValue? = nil
    var resumedReview: JSONValue? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var url = ""
    @State private var key = ""
    @State private var account = "主要帳戶"
    @State private var group = ""
    @State private var models = ""
    @State private var mode = "QUICK"
    @State private var adapter = "GENERIC"
    @State private var auth = "BEARER"
    @State private var endpoint = "CHAT_COMPLETIONS"
    @State private var header = ""
    @State private var authPrefix = ""
    @State private var authQuery = ""
    @State private var addAuthFields: [SecretFieldDraft] = []
    @State private var website = ""
    @State private var review: JSONValue?
    @State private var selected: Set<String> = []
    @State private var existing: [JSONValue] = []
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack { VStack(alignment: .leading, spacing: 5) { Text(review == nil ? "新增供應商" : "檢查並加入").font(.title2.weight(.semibold)); Text("只有你選擇的模型會加入 My Models。").foregroundStyle(.secondary) }; Spacer(); Button("關閉") { dismiss() }.keyboardShortcut(.cancelAction) }.padding(26)
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if let review {
                        AtlasCard {
                            Text(review["provider"]["name"].string).font(.title3.weight(.semibold))
                            Text(review["provider"]["base_url"].string).foregroundStyle(.secondary).textSelection(.enabled)
                            OnboardingScopeSummary(review: review)
                            SmallBadge(text: review["catalog_verified"].boolean ? "Catalog 已取得" : "Catalog 尚未驗證", color: review["catalog_verified"].boolean ? atlasAccent : .orange)
                            if !review["unknown_fields"].array.isEmpty { Text("待補資料：" + review["unknown_fields"].array.map(\.display).joined(separator: "、")).font(.callout).foregroundStyle(.secondary) }
                            ForEach(review["models"].array, id: \.self) { item in
                                Toggle(item.string, isOn: Binding(get: { selected.contains(item.string) }, set: { if $0 { selected.insert(item.string) } else { selected.remove(item.string) } })).toggleStyle(.checkbox)
                            }
                            if review["models"].array.isEmpty { Text("尚無模型。返回後填入要加入的模型 ID。").foregroundStyle(.secondary) }
                        }
                        HStack {
                            Button("返回修改") { self.review = nil }
                            ActionButton(title: "重新檢查") {
                                guard let api = store.api else { return }
                                let updated = try await api.call("/onboarding/retry", method: "POST", body: .object(["id": review["id"]])); self.review = updated; selected.formIntersection(Set(updated["models"].array.map(\.string)))
                            }
                            Spacer()
                            ActionButton(title: "確認加入", prominent: true) {
                                guard !selected.isEmpty else { throw AtlasError.http(400) }
                                _ = try await store.mutate("/onboarding/confirm", body: .object(["id": review["id"], "confirm": .bool(true), "name": .string(review["provider"]["name"].string), "selected_model_ids": .array(selected.sorted().map(JSONValue.string))]))
                                key = ""; addAuthFields = []; dismiss()
                            }.disabled(selected.isEmpty)
                        }
                    } else {
                        AtlasTabs(selection: $mode, items: [AtlasTab("QUICK", "快速新增", "bolt"), AtlasTab("ADVANCED", "進階設定", "slider.horizontal.3"), AtlasTab("BATCH", "批次匯入", "square.and.arrow.down")])
                        if mode == "BATCH" { BatchImportView(store: store) { review = $0; selected = [] } }
                        else {
                        manualEntry
                        }
                        if !existing.isEmpty {
                            DisclosureGroup("繼續尚未完成的加入流程") {
                                ForEach(existing.filter { $0["status"].string != "COMMITTED" }, id: \.self) { item in
                                    Button(item["provider"]["name"].string.isEmpty ? item["id"].string : item["provider"]["name"].string) { review = item; selected = [] }
                                }
                            }
                        }
                    }
                }.padding(28)
            }
        }.frame(width: 680, height: 720).background(atlasCanvas)
        .task { if let api = store.api { existing = (try? await api.get("/onboarding").requiredArray()) ?? [] }; if let resumedReview { review = resumedReview; selected = [] }; if let seed { url = seed["base_url"].string; name = URL(string: url)?.host ?? ""; models = seed["model_id"].string == "UNKNOWN" ? "" : seed["model_id"].string } }
    }
    private var manualEntry: some View {
        VStack(alignment: .leading, spacing: 20) {
                        FormField(title: "供應商名稱", text: $name)
                        FormField(title: "API Base URL", text: $url)
                        HStack { FormField(title: "帳戶名稱", text: $account); FormField(title: "群組（選填）", text: $group) }
                        Picker("認證方式", selection: $auth) { ForEach(["BEARER", "API_KEY", "CUSTOM_HEADER", "NONE"], id: \.self) { Text($0 == "NONE" ? "不需 Key" : $0).tag($0) } }
                        if auth != "NONE" { FormField(title: "API Key", text: $key, secure: true) }
                        FormField(title: "要加入的模型 ID（逗號分隔）", text: $models)
                        if mode == "ADVANCED" {
                            Picker("供應商類型", selection: $adapter) { ForEach(adapterOptions, id: \.self) { Text($0).tag($0) } }
                            Picker("模型用途", selection: $endpoint) { ForEach(endpointOptions, id: \.self) { Text($0).tag($0) } }
                            FormField(title: "官方網站（選填）", text: $website)
                            if auth == "CUSTOM_HEADER" || auth == "API_KEY" { FormField(title: "認證 Header 名稱", text: $header); FormField(title: "認證 Query 名稱（選填）", text: $authQuery); FormField(title: "認證前綴（選填）", text: $authPrefix) }
                            if auth != "NONE" { DisclosureGroup("額外認證欄位") { SecretFieldsEditor(rows: $addAuthFields) } }
                        }
                        ActionButton(title: "檢查連線並預覽", prominent: true) {
                            guard let api = store.api else { return }
                            let ids = models.split(separator: ",").map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
                            var config: JSONValue = .object(["adapter": .string(adapter), "auth_mode": .string(auth), "website": .string(website)])
                            if !header.isEmpty { config["auth_header"] = .string(header); config["auth_prefix"] = .string(authPrefix) }
                            if !authQuery.isEmpty { config["auth_query"] = .string(authQuery) }
                            let body: JSONValue = .object([
                                "mode": .string(mode), "provider": .object(["name": .string(name), "base_url": .string(url), "auth_mode": .string(auth), "config": config]),
                                "account": .object(["label": .string(account)]), "group": .string(group), "endpoint": .string(endpoint), "model_ids": .array(ids.map(JSONValue.string)),
                                "credentials": auth == "NONE" ? .array([]) : .array([.object(["label": .string("主要 Key"), "secret": .string(key), "group": .string(group), "role": .string("INFERENCE"), "auth_fields": .array(addAuthFields.map(\.json))])])
                            ])
                            let result = try await api.call("/onboarding/preview", method: "POST", body: body)
                            review = result
                            // Discovery never silently opts the entire catalog into My Models.
                            selected = Set(ids.filter { result["models"].array.map(\.string).contains($0) })
                        }
        }
    }

}

@MainActor struct ProviderDetailView: View {
    @ObservedObject var store: AtlasStore
    @ObservedObject private var checkins: AccountCheckinController
    let provider: JSONValue
    @Environment(\.dismiss) private var dismiss
    @Environment(\.atlasMotion) private var motion
    @State private var tab = "概覽"
    init(store: AtlasStore, provider: JSONValue, initialTab: String = "概覽") {
        self.store = store; self.provider = provider; checkins = store.checkins; _tab = State(initialValue: initialTab)
    }
    @State private var accountLabel = ""
    @State private var keyLabel = ""
    @State private var secret = ""
    @State private var group = ""
    @State private var accountID = ""
    @State private var role = "INFERENCE"
    @State private var endpointID = ""
    @State private var endpointURL = ""
    @State private var endpointType = "CHAT_COMPLETIONS"
    @State private var custom = "{}"
    @State private var notes = ""
    @State private var website = ""
    @State private var checkinURL = ""
    @State private var requiresCheckin = false
    @State private var configuration: JSONValue = .object([:])
    @State private var customHeaders = "{}"
    @State private var customPaths = "{}"
    @State private var authFields: [SecretFieldDraft] = []
    @State private var keyOrigin = ""
    @State private var keyAuth = ""
    @State private var keyNote = ""
    @State private var keyPriority = "0"
    @State private var performanceModel = ""
    @State private var performanceHours = "168"
    @State private var snapshots: JSONValue = .array([])
    @State private var dataCredential = ""
    private var id: String { provider["id"].string }
    private var currentProvider: JSONValue { store.providers.first { $0["id"].string == id } ?? provider }
    private var providerAccounts: [JSONValue] { store.accounts.filter { $0["provider_id"].string == id } }
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 14) {
                Image(systemName: "server.rack").font(.largeTitle).foregroundStyle(atlasTextAccent)
                VStack(alignment: .leading, spacing: 5) { Text(currentProvider["name"].string).font(.title2.weight(.bold)); Text(currentProvider["base_url"].string).foregroundStyle(.secondary).textSelection(.enabled) }
                Spacer(); Button("關閉") { dismiss() }.keyboardShortcut(.cancelAction)
            }.padding(25)
            Picker("分區", selection: $tab) { ForEach(["概覽", "帳戶與 Key", "Catalog", "端點", "設定", "站方資料"], id: \.self) { Text($0).tag($0) } }.pickerStyle(.segmented).padding(.horizontal, 24).padding(.bottom, 16)
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if tab == "概覽" { overview }
                    if tab == "帳戶與 Key" { accountsContent }
                    if tab == "Catalog" { CatalogView(store: store, providerID: id) }
                    if tab == "端點" { endpointsContent }
                    if tab == "設定" { configContent }
                    if tab == "站方資料" { providerData }
                }.padding(24).animation(motion.panel, value: tab)
            }
        }.frame(width: 820, height: 760)
        .onChange(of: accountID) { _ in dataCredential = "" }
        .task {
            configuration = provider["config"]
            website = configuration["website"].string; notes = configuration["notes"].string
            requiresCheckin = configuration["requires_daily_checkin"].boolean; checkinURL = configuration["checkin_url"].string
            customHeaders = configuration["headers"] == .null ? "{}" : configuration["headers"].pretty
            customPaths = configuration["endpoints"] == .null ? "{}" : configuration["endpoints"].pretty
            if accountID.isEmpty { accountID = providerAccounts.first?["id"].string ?? "" }
        }
    }
    private var overview: some View {
        VStack(spacing: 18) {
            AtlasCard {
                HStack {
                    Text("帳戶與連線").font(.headline)
                    Spacer()
                    Button("管理帳戶與 Key") { tab = "帳戶與 Key" }
                }
                if !currentProvider["description"].string.isEmpty { Text(currentProvider["description"].string).foregroundStyle(.secondary) }
                ForEach(providerAccounts) { account in
                    let keys = store.credentials.filter { $0["provider_id"].string == id && $0["account_id"].string == account.id }
                    let models = store.activeModels.filter { $0["provider_id"].string == id && $0["account_id"].string == account.id }
                    HStack {
                        Label(account["label"].string, systemImage: "person.crop.circle").font(.subheadline.weight(.medium))
                        Spacer()
                        Text("\(keys.filter { $0["enabled"].boolean }.count)／\(keys.count) 啟用 Key · \(models.count) 條模型連線").font(.caption).foregroundStyle(.secondary)
                    }.padding(.vertical, 4)
                    if account.id != providerAccounts.last?.id { Divider() }
                }
                if providerAccounts.isEmpty { Text("先新增帳戶，再加入對應的 Key。").foregroundStyle(.secondary) }
            }
            if currentProvider["config"]["requires_daily_checkin"].boolean { AccountCheckinPanel(controller: checkins, providerID: id, api: store.api) }
            AtlasLoadedMarker(name: "provider-overview-loaded").frame(height: 0)
        }
    }
    private var accountsContent: some View {
        VStack(spacing: 18) {
            ForEach(providerAccounts, id: \.id) { account in
                AtlasCard {
                    Label(account["label"].string, systemImage: "person.crop.circle").font(.headline)
                    HStack { ForEach([true, false], id: \.self) { enabled in ActionButton(title: enabled ? "啟用此帳戶全部 Key" : "停用此帳戶全部 Key") { let ids = store.credentials.filter { $0["provider_id"].string == id && $0["account_id"] == account["id"] }.map { $0["id"] }; guard !ids.isEmpty else { return }; _ = try await store.mutate("/credentials/toggle", body: .object(["provider_id": .string(id), "account_id": account["id"], "ids": .array(ids), "enabled": .bool(enabled)])) } } }
                    ForEach(store.credentials.filter { $0["provider_id"].string == id && $0["account_id"].string == account["id"].string }, id: \.id) { credential in
                        CredentialRow(store: store, credential: credential)
                    }
                }
            }
            AtlasCard {
                Text("新增帳戶").font(.headline)
                FormField(title: "帳戶名稱", text: $accountLabel)
                ActionButton(title: "加入帳戶") {
                    let submitted = accountLabel
                    _ = try await store.mutate("/accounts", body: .object(["id": .string(UUID().uuidString.lowercased()), "provider_id": .string(id), "label": .string(submitted)]))
                    if accountLabel == submitted { accountLabel = "" }
                }
            }
            AtlasCard {
                Text("新增 Key").font(.headline)
                Picker("帳戶", selection: $accountID) { Text("選擇帳戶").tag(""); ForEach(providerAccounts, id: \.self) { Text($0["label"].string).tag($0["id"].string) } }
                HStack { FormField(title: "Key 名稱", text: $keyLabel); FormField(title: "群組（選填）", text: $group) }
                Picker("用途", selection: $role) { Text("模型推論").tag("INFERENCE"); Text("帳戶 PAT").tag("ACCOUNT_PAT") }
                FormField(title: "秘密值", text: $secret, secure: true)
                DisclosureGroup("來源與額外認證") {
                    FormField(title: "綁定 Base URL（留空沿用目前供應商）", text: $keyOrigin)
                    Picker("此 Key 認證方式", selection: $keyAuth) { Text("沿用供應商設定").tag(""); ForEach(["BEARER", "API_KEY", "CUSTOM_HEADER", "NONE"], id: \.self) { Text($0).tag($0) } }
                    FormField(title: "備註", text: $keyNote); FormField(title: "優先序（0–1000）", text: $keyPriority)
                    SecretFieldsEditor(rows: $authFields)
                }
                ActionButton(title: "儲存 Key") {
                    let submitted = [accountID, keyLabel, secret, group, role, keyOrigin, keyAuth, keyNote, keyPriority], fields = authFields
                    guard let priority = Int(keyPriority), (0...1000).contains(priority) else { throw DraftProblem("優先序需為 0–1000 的整數。") }
                    _ = try await store.mutate("/credentials", body: .object(["id": .string(UUID().uuidString.lowercased()), "provider_id": .string(id), "account_id": .string(accountID), "label": .string(keyLabel), "secret": .string(secret), "group": .string(group), "role": .string(role), "base_url": .string(keyOrigin), "auth_mode": .string(keyAuth), "note": .string(keyNote), "priority": .number(Double(priority)), "auth_fields": .array(fields.map(\.json))]))
                    if submitted == [accountID, keyLabel, secret, group, role, keyOrigin, keyAuth, keyNote, keyPriority], fields == authFields { secret = ""; authFields = []; keyLabel = "" }

                }.disabled(accountID.isEmpty)
            }
        }
    }
    private var endpointsContent: some View {
        VStack(spacing: 18) {
            ForEach(store.endpoints.filter { $0["provider_id"].string == id }, id: \.self) { item in AtlasCard { Label(item["operation"].string, systemImage: "point.3.connected.trianglepath.dotted"); Text(item["url"].string).textSelection(.enabled).foregroundStyle(.secondary); Text(item["id"].string).font(.caption).foregroundStyle(.secondary) } }
            AtlasCard {
                Text("加入獨立端點").font(.headline)
                FormField(title: "自訂端點 ID（例如 my-speech）", text: $endpointID)
                Text("操作類型另行選擇；每個端點 ID 建立後會固定綁定來源。").font(.caption).foregroundStyle(.secondary)
                Picker("用途", selection: $endpointType) { ForEach(endpointOptions + ["CUSTOM"], id: \.self) { Text($0).tag($0) } }
                FormField(title: "完整 HTTPS URL（與供應商相同 origin）", text: $endpointURL)
                if endpointType == "CUSTOM" { JSONEditor(title: "宣告式 CUSTOM 協定", text: $custom) }
                ActionButton(title: "加入端點") {
                    let submitted = [endpointID, endpointURL, endpointType, custom]
                    var value: JSONValue = .object(["provider_id": .string(id), "id": .string(endpointID), "operation": .string(endpointType), "url": .string(endpointURL)])
                    if endpointType == "CUSTOM" { guard let definition = try? JSONValue.parse(custom) else { throw AtlasError.invalidJSON }; value["custom_protocol"] = definition }
                    _ = try await store.mutate("/endpoints", body: value)
                    if submitted == [endpointID, endpointURL, endpointType, custom] { endpointID = ""; endpointURL = "" }
                }
            }
        }
    }
    private var configContent: some View {
        VStack(spacing: 18) {
        ProviderIdentityEditor(store: store, provider: provider)
        AtlasCard {
            Text("供應商設定").font(.headline)
            FormField(title: "官方網站", text: $website)
            FormField(title: "備註", text: $notes)
            Toggle("需要每日簽到", isOn: $requiresCheckin)
            FormField(title: "簽到網址", text: $checkinURL)
            Picker("Adapter", selection: configText("adapter")) { ForEach(adapterOptions, id: \.self) { Text($0).tag($0) } }
            Picker("認證方式", selection: configText("auth_mode")) { ForEach(["BEARER", "API_KEY", "CUSTOM_HEADER", "NONE"], id: \.self) { Text($0).tag($0) } }.disabled(true)
            Text("連線認證方式於建立時固定；個別 Key 可指定自己的認證方式。").font(.caption).foregroundStyle(.secondary)
            FormField(title: "認證 Header", text: configText("auth_header")); FormField(title: "認證 Query 參數", text: configText("auth_query")); FormField(title: "認證前綴", text: configText("auth_prefix")); FormField(title: "Project ID", text: configText("project_id"))
            DisclosureGroup("進階路徑與 Header") { JSONEditor(title: "端點路徑", text: $customPaths); JSONEditor(title: "非秘密 Header", text: $customHeaders) }
            ActionButton(title: "儲存設定") {
                guard let paths = try? JSONValue.parse(customPaths), let headers = try? JSONValue.parse(customHeaders), case .object = paths, case .object = headers else { throw AtlasError.invalidJSON }
                let submitted = configuration
                var value = submitted
                value["website"] = .string(website); value["notes"] = .string(notes); value["requires_daily_checkin"] = .bool(requiresCheckin); value["checkin_url"] = .string(checkinURL); value["headers"] = headers; value["endpoints"] = paths
                _ = try await store.mutate("/providers/config", body: .object(["provider_id": .string(id), "config": value])); if configuration == submitted { configuration = value }
            }
            if requiresCheckin {
                Button("查看各帳戶簽到狀態") { tab = "概覽" }
            }
        }
        }
    }
    private func configText(_ key: String) -> Binding<String> { Binding(get: { configuration[key].string }, set: { configuration[key] = .string($0) }) }
    private var providerData: some View {
        AtlasCard {
            Text("New API 結構化資料").font(.headline)
            Text("站方資料與本機實測分開保存。帳戶查詢需在上方選擇帳戶 PAT。").foregroundStyle(.secondary)
            Picker("帳戶", selection: $accountID) { Text("選擇帳戶").tag(""); ForEach(providerAccounts, id: \.self) { Text($0["label"].string).tag($0["id"].string) } }
            Picker("資料查詢 Key／PAT", selection: $dataCredential) {
                Text("選擇認證").tag("")
                ForEach(store.credentials.filter { $0["provider_id"].string == id && $0["account_id"].string == accountID && $0["enabled"].boolean }, id: \.self) { credential in
                    Text(credential["label"].string + " · " + credential["role"].string + " · " + credential["group"].string).tag(credential["id"].string)
                }
            }
            HStack { FormField(title: "站方效能模型 ID", text: $performanceModel); FormField(title: "時間窗（1–720 小時）", text: $performanceHours) }
            ForEach(["status", "notice", "pricing", "summary", "performance", "account", "token"], id: \.self) { operation in
                ActionButton(title: ["status": "讀取站方狀態", "notice": "讀取公告", "pricing": "讀取價格", "summary": "讀取站方效能摘要", "performance": "讀取指定模型站方效能", "account": "讀取帳戶用量", "token": "讀取 Key 額度"][operation]!) {
                    guard let api = store.api else { return }
                    let wantPAT = operation == "account"
                    let credential = store.credentials.first { $0["provider_id"].string == id && $0["account_id"].string == accountID && $0["id"].string == dataCredential && $0["enabled"].boolean }
                    if ["account", "token"].contains(operation) && credential?["role"].string != (wantPAT ? "ACCOUNT_PAT" : "INFERENCE") { throw AtlasError.http(422) }
                    guard let hours = Int(performanceHours), (1...720).contains(hours) else { throw DraftProblem("時間窗需為 1–720 小時。") }
                    var body: JSONValue = .object(["provider_id": .string(id), "account_id": .string(accountID), "hours": .number(Double(hours))])
                    if operation == "performance" { guard !performanceModel.isEmpty else { throw DraftProblem("請先填入模型 ID。") }; body["model_id"] = .string(performanceModel) }
                    if let credential { body["credential_id"] = credential["id"] }
                    snapshots = try await api.call("/newapi/" + operation, method: "POST", body: body)
                }
            }
            JSONTree(value: snapshots)
        }
    }
}

struct CredentialRow: View {
    @ObservedObject var store: AtlasStore
    let credential: JSONValue
    @State private var rotating = false
    @State private var editing = false
    @State private var secret = ""
    @State private var version = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "key.fill").foregroundStyle(atlasTextAccent)
                VStack(alignment: .leading, spacing: 3) { Text(credential["label"].string.isEmpty ? credential["id"].string : credential["label"].string).font(.callout.weight(.semibold)); Text(credential["role"].string + " · " + (credential["group"].string.isEmpty ? "預設群組" : credential["group"].string)).font(.caption).foregroundStyle(.secondary) }
                Spacer()
                SmallBadge(text: credential["enabled"].boolean ? "已啟用" : "已停用", color: credential["enabled"].boolean ? atlasAccent : .secondary)
                ActionButton(title: credential["enabled"].boolean ? "停用" : "啟用") {
                    _ = try await store.mutate("/credentials/toggle", body: .object(["provider_id": credential["provider_id"], "account_id": credential["account_id"], "ids": .array([credential["id"]]), "enabled": .bool(!credential["enabled"].boolean)]))
                }
                Button("編輯…") { editing = true }
                Button("更新…") { rotating.toggle() }
            }
            if rotating {
                FormField(title: "新的秘密值", text: $secret, secure: true)
                FormField(title: "或恢復指定版本（留空代表儲存新 Key）", text: $version)
                ActionButton(title: "確認更新這個帳戶的 Key") {
                    let submitted = [secret, version]
                    var value: JSONValue = .object(["provider_id": credential["provider_id"], "account_id": credential["account_id"], "credential_id": credential["id"], "confirm": .bool(true), "secret": .string(secret)])
                    if !version.isEmpty { guard let number = Int(version), number > 0 else { throw AtlasError.invalidJSON }; value["version"] = .number(Double(number)); value["secret"] = .string("") }
                    _ = try await store.mutate("/credentials/rotate", body: value)
                    if submitted == [secret, version] { secret = ""; version = ""; rotating = false }
                }
            }
        }.padding(.vertical, 6)
        .sheet(isPresented: $editing) { CredentialMetadataEditor(store: store, credential: credential).atlasModal(store) }
    }
}
