import AtlasCore
import SwiftUI

let adapterOptions = ["GENERIC", "NEW_API", "ANTHROPIC", "OPENROUTER_FREE", "AMD_SHARED", "AMD_DEDICATED", "NVIDIA_NIM_HOSTED", "NVIDIA_NIM_SELF_HOSTED", "GEMINI_FREE", "GROQ_FREE"]
let endpointOptions = ["CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC_MESSAGES", "GEMINI_NATIVE", "IMAGES_GENERATION", "IMAGES_EDIT", "AUDIO", "AUDIO_SPEECH", "GEMINI_SPEECH", "EMBEDDINGS", "RERANK"]

struct ProvidersView: View {
    @ObservedObject var store: AtlasStore
    @State private var search = ""
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .top) {
                    PageHeading(eyebrow: "Connections", title: "所有連線，各就其位。", subtitle: "獨立管理帳戶、Key 與群組，保留每一次使用紀錄。")
                    Spacer()
                    Button { store.newProviderOpen = true } label: { Label("新增供應商", systemImage: "plus") }.buttonStyle(.borderedProminent).controlSize(.large)
                }
                TextField("搜尋名稱或網址", text: $search).textFieldStyle(.roundedBorder).frame(maxWidth: 380)
                if store.providers.isEmpty { EmptyCard(icon: "server.rack", title: "接上第一個供應商", detail: "貼上 Base URL 與 Key，檢查結果後再加入。", action: "新增供應商") { store.newProviderOpen = true }.frame(height: 350) }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 280), spacing: 16)], alignment: .leading, spacing: 16) {
                    ForEach(store.providers.filter { search.isEmpty || ($0["name"].string + $0["base_url"].string).localizedCaseInsensitiveContains(search) }, id: \.self) { provider in
                        Button { store.detailProvider = provider } label: {
                            AtlasCard {
                                HStack {
                                    Image(systemName: "server.rack").font(.title2).foregroundStyle(atlasAccent).frame(width: 48, height: 48).background(atlasAccent.opacity(0.09)).cornerRadius(12)
                                    VStack(alignment: .leading, spacing: 4) { Text(provider["name"].string).font(.headline); Text(provider["config"]["adapter"].string).font(.caption).foregroundStyle(.secondary) }
                                    Spacer(); Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                                }
                                Text(provider["description"].string.isEmpty ? provider["base_url"].string : provider["description"].string).foregroundStyle(.secondary).lineLimit(2).frame(height: 36, alignment: .top)
                                HStack {
                                    let id = provider["id"].string
                                    SmallBadge(text: "\(store.accounts.filter { $0["provider_id"].string == id }.count) 帳戶")
                                    SmallBadge(text: "\(store.credentials.filter { $0["provider_id"].string == id && $0["enabled"].boolean }.count) 個啟用 Key", color: atlasAccent)
                                }
                            }
                        }.buttonStyle(.plain)
                    }
                }
            }.padding(30).frame(maxWidth: 1300).frame(maxWidth: .infinity)
        }
    }
}

struct AddProviderView: View {
    @ObservedObject var store: AtlasStore
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
                                self.review = try await api.call("/onboarding/retry", method: "POST", body: .object(["id": review["id"]]))
                            }
                            Spacer()
                            ActionButton(title: "確認加入", prominent: true) {
                                guard !selected.isEmpty else { throw AtlasError.http(400) }
                                _ = try await store.mutate("/onboarding/confirm", body: .object(["id": review["id"], "confirm": .bool(true), "name": .string(review["provider"]["name"].string), "selected_model_ids": .array(selected.sorted().map(JSONValue.string))]))
                                key = ""; dismiss()
                            }.disabled(selected.isEmpty)
                        }
                    } else {
                        Picker("新增方式", selection: $mode) { Text("快速新增").tag("QUICK"); Text("進階新增").tag("ADVANCED") }.pickerStyle(.segmented)
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
                            if auth == "CUSTOM_HEADER" || auth == "API_KEY" { FormField(title: "認證 Header 名稱", text: $header); FormField(title: "認證前綴（選填）", text: $authPrefix) }
                        }
                        ActionButton(title: "檢查連線並預覽", prominent: true) {
                            guard let api = store.api else { return }
                            let ids = models.split(separator: ",").map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
                            var config: JSONValue = .object(["adapter": .string(adapter), "auth_mode": .string(auth), "website": .string(website)])
                            if !header.isEmpty { config["auth_header"] = .string(header); config["auth_prefix"] = .string(authPrefix) }
                            let body: JSONValue = .object([
                                "mode": .string(mode), "provider": .object(["name": .string(name), "base_url": .string(url), "auth_mode": .string(auth), "config": config]),
                                "account": .object(["label": .string(account)]), "group": .string(group), "endpoint": .string(endpoint), "model_ids": .array(ids.map(JSONValue.string)),
                                "credentials": auth == "NONE" ? .array([]) : .array([.object(["label": .string("主要 Key"), "secret": .string(key), "group": .string(group), "role": .string("INFERENCE")])])
                            ])
                            let result = try await api.call("/onboarding/preview", method: "POST", body: body)
                            review = result
                            // Discovery never silently opts the entire catalog into My Models.
                            selected = Set(ids.filter { result["models"].array.map(\.string).contains($0) })
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
        }.frame(width: 680, height: 720)
        .task { if let api = store.api { existing = (try? await api.get("/onboarding").requiredArray()) ?? [] } }
    }
}

struct ProviderDetailView: View {
    @ObservedObject var store: AtlasStore
    let provider: JSONValue
    @Environment(\.dismiss) private var dismiss
    @State private var tab = "帳戶與 Key"
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
    @State private var authFields = "[]"
    @State private var snapshots: JSONValue = .array([])
    @State private var dataCredential = ""
    private var id: String { provider["id"].string }
    private var providerAccounts: [JSONValue] { store.accounts.filter { $0["provider_id"].string == id } }
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 14) {
                Image(systemName: "server.rack").font(.largeTitle).foregroundStyle(atlasAccent)
                VStack(alignment: .leading, spacing: 5) { Text(provider["name"].string).font(.title2.weight(.bold)); Text(provider["base_url"].string).foregroundStyle(.secondary).textSelection(.enabled) }
                Spacer(); Button("關閉") { dismiss() }.keyboardShortcut(.cancelAction)
            }.padding(25)
            Picker("分區", selection: $tab) { ForEach(["帳戶與 Key", "Catalog", "端點", "設定", "站方資料"], id: \.self) { Text($0).tag($0) } }.pickerStyle(.segmented).padding(.horizontal, 24).padding(.bottom, 16)
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if tab == "帳戶與 Key" { accountsContent }
                    if tab == "Catalog" { CatalogView(store: store, providerID: id) }
                    if tab == "端點" { endpointsContent }
                    if tab == "設定" { configContent }
                    if tab == "站方資料" { providerData }
                }.padding(24)
            }
        }.frame(width: 820, height: 760)
        .task {
            configuration = provider["config"]
            website = configuration["website"].string; notes = configuration["notes"].string
            requiresCheckin = configuration["requires_daily_checkin"].boolean; checkinURL = configuration["checkin_url"].string
            customHeaders = configuration["headers"] == .null ? "{}" : configuration["headers"].pretty
            customPaths = configuration["endpoints"] == .null ? "{}" : configuration["endpoints"].pretty
            if accountID.isEmpty { accountID = providerAccounts.first?["id"].string ?? "" }
        }
    }
    private var accountsContent: some View {
        VStack(spacing: 18) {
            ForEach(providerAccounts, id: \.id) { account in
                AtlasCard {
                    Label(account["label"].string, systemImage: "person.crop.circle").font(.headline)
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
                DisclosureGroup("額外認證欄位") { JSONEditor(title: "Header／Query 秘密欄位", text: $authFields) }
                ActionButton(title: "儲存 Key") {
                    let submitted = [accountID, keyLabel, secret, group, role, authFields]
                    guard let fields = try? JSONValue.parse(authFields), case .array = fields else { throw AtlasError.invalidJSON }
                    _ = try await store.mutate("/credentials", body: .object(["id": .string(UUID().uuidString.lowercased()), "provider_id": .string(id), "account_id": .string(accountID), "label": .string(keyLabel), "secret": .string(secret), "group": .string(group), "role": .string(role), "auth_fields": fields]))
                    if submitted == [accountID, keyLabel, secret, group, role, authFields] { secret = ""; authFields = "[]"; keyLabel = "" }
                }.disabled(accountID.isEmpty)
            }
        }
    }
    private var endpointsContent: some View {
        VStack(spacing: 18) {
            ForEach(store.endpoints.filter { $0["provider_id"].string == id }, id: \.self) { item in AtlasCard { Label(item["operation"].string, systemImage: "point.3.connected.trianglepath.dotted"); Text(item["url"].string).textSelection(.enabled).foregroundStyle(.secondary); Text(item["id"].string).font(.caption).foregroundStyle(.secondary) } }
            AtlasCard {
                Text("加入獨立端點").font(.headline)
                FormField(title: "端點 ID", text: $endpointID)
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
        AtlasCard {
            Text("供應商設定").font(.headline)
            FormField(title: "官方網站", text: $website)
            FormField(title: "備註", text: $notes)
            Toggle("需要每日簽到", isOn: $requiresCheckin)
            FormField(title: "簽到網址", text: $checkinURL)
            DisclosureGroup("進階路徑與 Header") { JSONEditor(title: "端點路徑", text: $customPaths); JSONEditor(title: "非秘密 Header", text: $customHeaders) }
            ActionButton(title: "儲存設定") {
                guard let paths = try? JSONValue.parse(customPaths), let headers = try? JSONValue.parse(customHeaders), case .object = paths, case .object = headers else { throw AtlasError.invalidJSON }
                var value = configuration
                value["website"] = .string(website); value["notes"] = .string(notes); value["requires_daily_checkin"] = .bool(requiresCheckin); value["checkin_url"] = .string(checkinURL); value["headers"] = headers; value["endpoints"] = paths
                _ = try await store.mutate("/providers/config", body: .object(["provider_id": .string(id), "config": value])); configuration = value
            }
            if requiresCheckin {
                HStack {
                    Button("開啟簽到頁") { openHTTPS(checkinURL) }
                    ActionButton(title: "標記今日已簽到") { _ = try await store.mutate("/checkins", body: .object(["provider_id": .string(id), "timezone": store.settings["timezone"]])) }
                }
            }
        }
    }
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
            ForEach(["status", "notice", "pricing", "summary", "account", "token"], id: \.self) { operation in
                ActionButton(title: ["status": "讀取站方狀態", "notice": "讀取公告", "pricing": "讀取價格", "summary": "讀取站方效能", "account": "讀取帳戶用量", "token": "讀取 Key 額度"][operation]!) {
                    guard let api = store.api else { return }
                    let wantPAT = operation == "account"
                    let credential = store.credentials.first { $0["provider_id"].string == id && $0["account_id"].string == accountID && $0["id"].string == dataCredential && $0["enabled"].boolean }
                    if ["account", "token"].contains(operation) && credential?["role"].string != (wantPAT ? "ACCOUNT_PAT" : "INFERENCE") { throw AtlasError.http(422) }
                    var body: JSONValue = .object(["provider_id": .string(id), "account_id": .string(accountID), "hours": .number(168)])
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
    @State private var secret = ""
    @State private var version = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "key.fill").foregroundStyle(atlasAccent)
                VStack(alignment: .leading, spacing: 3) { Text(credential["label"].string.isEmpty ? credential["id"].string : credential["label"].string).font(.callout.weight(.semibold)); Text(credential["role"].string + " · " + (credential["group"].string.isEmpty ? "預設群組" : credential["group"].string)).font(.caption).foregroundStyle(.secondary) }
                Spacer()
                SmallBadge(text: credential["enabled"].boolean ? "已啟用" : "已停用", color: credential["enabled"].boolean ? atlasAccent : .secondary)
                ActionButton(title: credential["enabled"].boolean ? "停用" : "啟用") {
                    _ = try await store.mutate("/credentials/toggle", body: .object(["provider_id": credential["provider_id"], "account_id": credential["account_id"], "ids": .array([credential["id"]]), "enabled": .bool(!credential["enabled"].boolean)]))
                }
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
    }
}
