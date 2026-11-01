import AtlasCore
import SwiftUI

struct ServicesView: View {
    @ObservedObject var store: AtlasStore
    @State private var loaded = false
    @State private var service = "LMSPEED"
    @State private var secret = ""
    @State private var locale = "zh"
    @State private var notice: String?
    @State private var problem: String?
    @State private var aa: JSONValue = .object([:])
    @State private var models: [JSONValue] = []
    @State private var history: JSONValue = .array([])
    @State private var query = ""
    @State private var runDetail: JSONValue?
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            if let problem { Text(problem).foregroundStyle(.red) }
            AtlasCard {
                Label("研究與外部資料服務", systemImage: "network").font(.headline)
                Picker("服務", selection: $service) { Text("LMSpeed").tag("LMSPEED"); Text("Artificial Analysis").tag("AA"); Text("Brave Search").tag("BRAVE_SEARCH") }
                FormField(title: "服務 API Key", text: $secret, secure: true)
                Text("服務金鑰會加密保存，與供應商的推論 Key 分開使用。").font(.caption).foregroundStyle(.secondary)
                ActionButton(title: "儲存服務金鑰") {
                    guard let api = store.api else { return }; let selected = service, submitted = secret
                    guard (8...8192).contains(submitted.utf8.count), !submitted.contains(where: { $0.isNewline }) else { throw DraftProblem("請輸入有效的服務 API Key。") }
                    _ = try await api.call(selected == "LMSPEED" ? "/lmspeed/key" : "/research/integrations", method: "POST", body: selected == "LMSPEED" ? .object(["secret": .string(submitted)]) : .object(["name": .string(selected), "secret": .string(submitted)]))
                    if secret == submitted, service == selected { secret = "" }; notice = "服務金鑰已儲存"
                }.disabled(secret.isEmpty)
                if let notice { Label(notice, systemImage: "checkmark.circle").font(.callout) }
            }
            AtlasCard {
                Text("LMSpeed 外部參考").font(.headline)
                HStack {
                    Picker("資料語言", selection: $locale) { Text("中文").tag("zh"); Text("English").tag("en"); Text("Русский").tag("ru") }
                    ActionButton(title: "同步外部模型") { guard let api = store.api else { return }; _ = try await api.call("/lmspeed/sync", method: "POST", body: .object(["locale": .string(locale)])); await load(); notice = "LMSpeed 資料已同步" }
                }
                SearchField(placeholder: "搜尋外部模型", text: $query)
                ForEach(models.filter { query.isEmpty || $0.pretty.localizedCaseInsensitiveContains(query) }.prefix(100), id: \.self) { item in DisclosureGroup(item["canonical_id"].string) { JSONTree(value: item) } }
                if models.isEmpty { Text("尚無外部資料。同步後可在模型詳情查看獨立來源。").foregroundStyle(.secondary) }
            }
            AARankingCard(value: aa) { guard let api = store.api else { return }; runDetail = try await ResearchRunController.queueAA(using: api) }
            AtlasCard { Text("資料同步歷史").font(.headline); JSONTree(value: history) }
        }.background(Group { if loaded { AtlasLoadedMarker(name: "services-loaded") } }).task { await load() }
        .sheet(item: $runDetail, onDismiss: { Task { await load() } }) { ResearchRunView(store: store, run: $0).atlasModal(store) }
    }
    private func load() async {
        guard let api = store.api else { return }
        do { async let a = api.get("/research/aa"), m = api.get("/lmspeed/models"), h = api.get("/intelligence/history"); let result = try await (a, m, h); aa = result.0; models = try result.1.requiredArray(); history = result.2; loaded = true; problem = nil }
        catch { problem = error.localizedDescription }
    }
}

struct AARankingCard: View {
    let value: JSONValue
    let sync: () async throws -> Void
    var body: some View {
        AtlasCard {
            HStack { Text("Artificial Analysis 排名").font(.headline); Spacer(); ActionButton(title: "更新榜單", action: sync).background(AtlasLoadedMarker(name: "aa-update-target")) }
            if value["snapshot"] == .null { Text("尚無有效榜單。先在設定的服務分區保存 AA Key。").foregroundStyle(.secondary) }
            else {
                Text(value["status"].string == "FRESH" ? "目前有效；動態 Top-4 依這份快照判斷。" : "榜單已過期，Top-4 特例暫停。").font(.callout).foregroundStyle(.secondary)
                Text("指標版本 " + value["snapshot"]["index_version"].display).font(.caption)
                ForEach(value["snapshot"]["models"].array.prefix(10), id: \.self) { row in HStack { Text(row["rank"].display).monospacedDigit().frame(width: 25); Text(row["name"].string); Spacer(); Text(row["score"].display).monospacedDigit() } }
                DisclosureGroup("來源與觀測時間") { JSONTree(value: value) }
            }
        }
    }
}
