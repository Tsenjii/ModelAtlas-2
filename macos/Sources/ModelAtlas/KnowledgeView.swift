import AtlasCore
import SwiftUI

@MainActor final class VersionController: ObservableObject {
    let api: AtlasClient, connector: Bool
    @Published var value: JSONValue
    @Published var busy = false
    @Published var problem: String?
    @Published var undoID: String?
    @Published var notice: String?
    @Published var needsReload = false
    init(api: AtlasClient, value: JSONValue, connector: Bool) { self.api = api; self.value = value; self.connector = connector; let action = value["activation_action_id"].string; undoID = action.isEmpty ? nil : action }
    var measured: Bool { !connector && ["SOURCE_RELIABILITY", "RESEARCH_STRATEGY"].contains(value["kind"].string) }
    var needsEvaluation: Bool { measured && !value["data"]["scope_hash"].string.isEmpty }
    var evaluationReady: Bool { value["evaluation"]["status"].string == "READY_FOR_TRIAL" && value["evaluation"]["baseline_id"].string == value["active_id"].string }
    func reload() async throws {
        let values = try await api.get(connector ? "/connectors" : "/research/knowledge").requiredArray()
        guard let latest = values.first(where: { $0["id"] == value["id"] }) else { throw AtlasError.invalidResponse }; value = latest
        if !connector { let action = latest["activation_action_id"].string; undoID = action.isEmpty ? nil : action }
    }
    func activate() async {
        guard !busy, !needsReload else { return }; busy = true; problem = nil; defer { busy = false }
        do {
            let result = try await api.call(connector ? "/connectors/activate" : "/research/knowledge/activate", method: "POST", body: .object(["id": value["id"], "expected_active_id": .string(value["active_id"].string), "evaluation_id": .string(evaluationReady ? value["evaluation"]["id"].string : ""), "confirm": .bool(true)]))
            undoID = result["action_id"].string; value["active"] = .bool(true); notice = "這個版本已啟用"; await refreshAfterWrite()
        } catch { problem = error.localizedDescription }
    }
    func evaluate() async {
        guard !busy, !needsReload, measured else { return }; busy = true; problem = nil; defer { busy = false }
        do { value["evaluation"] = try await api.call("/research/knowledge/evaluate", method: "POST", body: .object(["id": value["id"]])); notice = "研究策略評估已保存"; await refreshAfterWrite() }
        catch { problem = error.localizedDescription }
    }
    func undo() async {
        guard !busy, !needsReload, let undoID else { return }; busy = true; problem = nil; defer { busy = false }
        do { _ = try await api.call("/versions/undo", method: "POST", body: .object(["id": .string(undoID), "confirm": .bool(true)])); self.undoID = nil; value["active"] = .bool(false); notice = "這次啟用已復原"; await refreshAfterWrite() }
        catch { problem = error.localizedDescription }
    }
    func test(_ scope: ModelScope) async {
        guard !busy, !needsReload, connector, scope.providerID == value["provider_id"].string else { return }; busy = true; problem = nil; defer { busy = false }
        do { let result = try await api.call("/connectors/test", method: "POST", body: .object(["id": value["id"], "selection": scope.json])); value["status"] = result["status"]; value["test"] = result["test"]; notice = result["status"].string == "VALIDATED" ? "擷取測試已通過" : "測試未通過，原因已保存"; await refreshAfterWrite() }
        catch { problem = error.localizedDescription }
    }
    func retryLoad() async {
        guard !busy else { return }; busy = true; defer { busy = false }
        do { try await reload(); needsReload = false; problem = nil } catch { problem = "資料尚未更新，請重試。" }
    }
    private func refreshAfterWrite() async {
        do { try await reload(); needsReload = false }
        catch { needsReload = true; problem = "操作已保存，但畫面暫時無法更新。請重新讀取後再進行下一項操作。" }
    }

}

struct KnowledgeView: View {
    @ObservedObject var store: AtlasStore
    @State private var loaded = false
    @State private var tab = "知識"
    @State private var query = ""
    @State private var knowledge: [JSONValue] = []
    @State private var connectors: [JSONValue] = []
    @State private var reflections: [JSONValue] = []
    @State private var problem: String?
    @State private var selected: JSONValue?
    @State private var editor: JSONValue?
    init(store: AtlasStore, initialTab: String = "知識") { self.store = store; _tab = State(initialValue: initialTab) }
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack { Text("研究知識與擷取規則").font(.title3.weight(.semibold)); Spacer(); Button("重新載入") { Task { await load() } }; Button("新增" + tab) { editor = .object(["editor": .string(tab)]) } .disabled(tab == "反思") }
            Text("依據保存的證據建立版本；測試與啟用分開，啟用後可復原。").foregroundStyle(.secondary)
            Picker("知識分區", selection: $tab) { ForEach(["知識", "擷取規則", "反思"], id: \.self) { Text($0).tag($0) } }.pickerStyle(.segmented)
            SearchField(placeholder: "搜尋名稱、對象或類型", text: $query)
            if let problem { Text(problem).foregroundStyle(.red) }
            ForEach(rows.filter { query.isEmpty || [$0["name"].string, $0["subject"].string, $0["kind"].string, $0["reason"].string].joined(separator: " ").localizedCaseInsensitiveContains(query) }, id: \.self) { row in
                Button { selected = row } label: {
                    AtlasCard {
                        HStack { Text(title(row)).font(.headline); Spacer(); SmallBadge(text: row["active"].boolean ? "已啟用" : row["status"].string, color: row["active"].boolean ? atlasAccent : .secondary); Image(systemName: "chevron.right").foregroundStyle(.secondary) }
                        Text(row["reason"].string.isEmpty ? row["subject"].string : row["reason"].string).font(.callout).foregroundStyle(.secondary).lineLimit(2)
                    }
                }.buttonStyle(.plain)
            }
            if rows.isEmpty { EmptyCard(icon: "books.vertical", title: "尚無" + tab, detail: "完成研究後可查看反思，或依據已保存的證據建立提案。").frame(minHeight: 220) }
        }.background(Group { if loaded { AtlasLoadedMarker(name: "knowledge-loaded") } }).task { await load() }
        .sheet(item: $selected, onDismiss: { Task { await load() } }) { row in
            if tab == "反思" { FactSheet(title: "研究反思", value: row).atlasModal(store) }
            else if let api = store.api { VersionDetailView(store: store, controller: VersionController(api: api, value: row, connector: tab == "擷取規則")).atlasModal(store) }
        }
        .sheet(item: $editor, onDismiss: { Task { await load() } }) { value in
            if value["editor"].string == "擷取規則" { ConnectorEditor(store: store).atlasModal(store) }
            else { KnowledgeEditor(store: store).atlasModal(store) }
        }
    }
    private var rows: [JSONValue] { tab == "知識" ? knowledge : tab == "擷取規則" ? connectors : reflections }
    private func title(_ row: JSONValue) -> String { tab == "反思" ? "研究反思 · " + row["report"]["status"].string : (tab == "擷取規則" ? row["name"].string : row["kind"].string + " · " + row["subject"].string) + " · v" + row["version"].display }
    private func load() async {
        guard let api = store.api else { return }
        do { async let k = api.get("/research/knowledge"), c = api.get("/connectors"), r = api.get("/research/reflections"); let data = try await (k, c, r); knowledge = try data.0.requiredArray(); connectors = try data.1.requiredArray(); reflections = try data.2.requiredArray(); problem = nil; loaded = true }
        catch { problem = error.localizedDescription }
    }
}

struct FactSheet: View {
    let title: String, value: JSONValue
    @Environment(\.dismiss) private var dismiss
    var body: some View { VStack(alignment: .leading, spacing: 18) { HStack { Text(title).font(.title2.weight(.semibold)); Spacer(); Button("關閉") { dismiss() }.keyboardShortcut(.cancelAction) }; ScrollView { JSONTree(value: value) } }.padding(24).frame(width: 740, height: 650) }
}

struct VersionDetailView: View {
    @ObservedObject var store: AtlasStore
    @StateObject var controller: VersionController
    @Environment(\.dismiss) private var dismiss
    @State private var scopeID = ""
    @State private var editing = false
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack { Text(controller.connector ? controller.value["name"].string : controller.value["subject"].string).font(.title2.weight(.semibold)); Spacer(); Button("關閉") { dismiss() }.disabled(controller.busy).keyboardShortcut(.cancelAction) }
            HStack { SmallBadge(text: "v" + controller.value["version"].display); SmallBadge(text: controller.value["active"].boolean ? "已啟用" : controller.value["status"].string, color: atlasAccent); Spacer(); Button("修訂新版本") { editing = true }.disabled(controller.busy) }
            if let notice = controller.notice { Text(notice).font(.callout) }
            if let problem = controller.problem { Text(problem).foregroundStyle(.red); if controller.needsReload { Button("重新讀取") { Task { await controller.retryLoad() } }.disabled(controller.busy) } }
            ScrollView { VStack(alignment: .leading, spacing: 18) { Text(controller.value["reason"].string); if controller.measured { evaluationSection }; JSONTree(value: controller.value[controller.connector ? "config" : "data"]); DisclosureGroup("證據與測試") { JSONTree(value: controller.value["evidence_ids"]); JSONTree(value: controller.value["test"]); Text("版本 ID：" + controller.value["id"].string).font(.caption).textSelection(.enabled) } } }
            if controller.connector {
                Picker("測試使用的精確連線", selection: $scopeID) { Text("選擇連線").tag(""); ForEach(store.activeModels.filter { $0["provider_id"] == controller.value["provider_id"] }, id: \.self) { Text($0["model_id"].string + " · " + store.scopeTitle($0) + " · " + $0["endpoint"].string).tag(ModelScope($0).id) } }
                Button("測試資料擷取") { if let model = store.activeModels.first(where: { ModelScope($0).id == scopeID }) { Task { await controller.test(ModelScope(model)) } } }.disabled(controller.busy || controller.needsReload || scopeID.isEmpty)
            }
            HStack {
                if controller.busy { ProgressView().controlSize(.small) }
                if let _ = controller.undoID { Button("復原這次啟用") { Task { await controller.undo(); await store.refresh() } }.disabled(controller.busy || controller.needsReload) }
                Spacer()
                if !controller.value["active"].boolean { Button(controller.needsEvaluation ? "確認試用此版本" : "確認啟用此版本") { Task { await controller.activate(); await store.refresh() } }.buttonStyle(AtlasActionStyle(prominent: true)).disabled(controller.busy || controller.needsReload || controller.value["status"].string == "IMPORTED" || (controller.connector && controller.value["status"].string != "VALIDATED") || (controller.needsEvaluation && !controller.evaluationReady)) }
            }
        }.padding(24).frame(width: 780, height: 700)
        .sheet(isPresented: $editing) { Group { if controller.connector { ConnectorEditor(store: store, initial: controller.value) } else { KnowledgeEditor(store: store, initial: controller.value) } }.atlasModal(store) }
    }
    private var evaluationSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("策略評估").font(.headline)
            if !controller.value["evaluation"]["id"].string.isEmpty {
                let evaluation = controller.value["evaluation"]
                Text(evaluationTitle(evaluation["status"].string)).font(.subheadline.weight(.semibold))
                Text(evaluation["reason"].string).foregroundStyle(.secondary)
                Text(evaluation["target_observations"]["runs"].display + " 輪同範圍研究 · " + evaluation["target_observations"]["http_requests"].display + " 次 HTTP").font(.callout)
                DisclosureGroup("提案差異、比較範圍與限制") { JSONTree(value: evaluation) }
            } else { Text("比較多輪本機研究後確認試用。手動優先序及HTTP成功本身不表示研究品質已改善。").foregroundStyle(.secondary) }
            Button("評估這個提案") { Task { await controller.evaluate() } }.disabled(controller.busy || controller.needsReload || controller.value["status"].string == "IMPORTED")
            if !controller.value["monitor"]["status"].string.isEmpty {
                let monitor = controller.value["monitor"]
                Text("啟用後的實際研究 · " + evaluationTitle(monitor["status"].string)).font(.headline)
                Text(monitor["reason"].string).foregroundStyle(.secondary)
                DisclosureGroup("實際使用版本的觀測") { JSONTree(value: monitor) }
            }
        }
    }
    private func evaluationTitle(_ status: String) -> String { ["POLICY_CHANGED":"需重新評估", "READY_FOR_TRIAL":"可確認試用", "INSUFFICIENT":"比較資料不足", "REGRESSION":"發現退步", "SOURCE_CHANGED":"來源版本已變", "DEGRADED":"研究成效下降", "OBSERVED":"持續觀察中", "NO_BASELINE":"尚無比較基線"][status] ?? status }
}

struct MappingEditor: View {
    @Binding var rows: [FieldMapping]
    var maximum = 20
    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            Text("欄位對照").font(.headline)
            ForEach($rows) { $row in HStack { TextField("資料欄位", text: $row.name); TextField("JSON Pointer，例如 /limits/rpd", text: $row.pointer); Button { rows.removeAll { $0.id == row.id } } label: { Image(systemName: "minus.circle") }.help("移除欄位") }.textFieldStyle(.roundedBorder) }
            Button { rows.append(FieldMapping()) } label: { Label("加入欄位", systemImage: "plus") }.disabled(rows.count >= maximum)
        }
    }
}

struct ConnectorEditor: View {
    @ObservedObject var store: AtlasStore
    var initial: JSONValue? = nil
    @State private var draft = ConnectorDraft()
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack { Text(initial == nil ? "新增擷取規則" : "修訂擷取規則").font(.title2.weight(.semibold)); Spacer(); Button("關閉") { dismiss() }.keyboardShortcut(.cancelAction) }
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Picker("供應商", selection: $draft.provider) { Text("選擇供應商").tag(""); ForEach(store.providers) { Text($0["name"].string).tag($0.id) } }.disabled(initial != nil)
                    FormField(title: "規則 ID（英數字，例如 quota）", text: $draft.name).disabled(initial != nil)
                    Picker("資料種類", selection: $draft.kind) { ForEach(["QUOTA", "PRICING", "BALANCE", "USAGE", "PERFORMANCE", "NOTICE", "CATALOG"], id: \.self) { Text($0).tag($0) } }
                    FormField(title: "唯讀 GET Path", text: $draft.path)
                    HStack {
                        Picker("憑證用途", selection: $draft.role) { ForEach(["INFERENCE", "ACCOUNT_PAT", "NONE"], id: \.self) { Text($0).tag($0) } }
                        Picker("資料範圍", selection: $draft.scope) { ForEach(["MODEL_KEY_GROUP", "MODEL_PROVIDER", "ACCOUNT", "CATALOG"], id: \.self) { Text($0).tag($0) } }
                    }
                    if draft.role == "ACCOUNT_PAT" { Picker("帳戶 PAT 參照", selection: $draft.pat) { Text("選擇同一帳戶／來源的 PAT").tag(""); ForEach(store.credentials.filter { $0["provider_id"].string == draft.provider && $0["role"].string == "ACCOUNT_PAT" }, id: \.self) { Text($0["account_id"].string + " · " + $0["label"].string).tag($0["id"].string) } } }
                    FormField(title: "清單 JSON Pointer（根節點可留空）", text: $draft.root)
                    MappingEditor(rows: $draft.mappings, maximum: 12)
                    Button("套用此類型的常用欄位") {
                        let names = ["QUOTA":["model_id", "group", "rpd", "tpd"], "PRICING":["model_id", "group", "input", "output"], "BALANCE":["model_id", "group", "balance"], "USAGE":["model_id", "group", "requests", "tokens"], "PERFORMANCE":["model_id", "group", "tps", "ttft_ms", "success_rate", "sample_count"], "NOTICE":["model_id", "group", "notice"], "CATALOG":["model_id", "name"]][draft.kind] ?? []
                        draft.mappings = names.map { FieldMapping(name: $0, pointer: "/" + $0) }
                        if draft.kind == "PRICING" { draft.unit = "USD/1M tokens" }
                        if draft.kind == "CATALOG" { draft.scope = "CATALOG"; draft.unit = "NONE" }
                    }
                    Picker("單位", selection: $draft.unit) { ForEach(["REQUESTS", "TOKENS", "USD/1M tokens", "USD/1K tokens", "USD/token", "USD", "CREDITS", "MS", "TPS", "NONE"], id: \.self) { Text($0).tag($0) } }
                    if draft.kind == "PRICING" { Toggle("證據確認沒有固定請求費", isOn: $draft.fixedAbsent); Toggle("證據確認為這個推論 Key／Group 的實際費率", isOn: $draft.effective) }
                    FormField(title: "已保存證據 ID（逗號分隔，可選）", text: $draft.evidence)
                    FormField(title: "判斷理由", text: $draft.reason)
                    ActionButton(title: "儲存草稿，接著測試") { guard let api = store.api else { return }; let body = try draft.request(); _ = try await api.call("/connectors", method: "POST", body: body); dismiss() }
                }.padding(.trailing, 8)
            }
        }.padding(24).frame(width: 780, height: 730).task { if let initial { draft = ConnectorDraft(initial) } }
    }
}

struct KnowledgeEditor: View {
    @ObservedObject var store: AtlasStore
    var initial: JSONValue? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var kind = "SOURCE_RELIABILITY"
    @State private var subject = ""
    @State private var evidence = ""
    @State private var reason = ""
    @State private var data = "{}"
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack { Text("知識版本提案").font(.title2.weight(.semibold)); Spacer(); Button("關閉") { dismiss() }.keyboardShortcut(.cancelAction) }
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Picker("類型", selection: $kind) { ForEach(["SOURCE_RELIABILITY", "RESEARCH_STRATEGY", "ENDPOINT_PATTERN", "AUTH_PATTERN", "DISCOVERY_PATH", "MODEL_ALIAS_RULE", "VERIFICATION_OUTCOME", "PROVIDER_RECIPE"], id: \.self) { Text($0).tag($0) } }.disabled(initial != nil)
                    FormField(title: "對象", text: $subject).disabled(initial != nil)
                    JSONEditor(title: "宣告式知識資料", text: $data)
                    FormField(title: "已保存證據 ID（逗號分隔）", text: $evidence)
                    FormField(title: "判斷理由", text: $reason)
                    Text("證據 ID 可在候選詳情、反思或已通過測試的擷取版本中複製。儲存後需另行確認啟用。").font(.callout).foregroundStyle(.secondary)
                    ActionButton(title: "儲存新版本") {
                        guard let api = store.api else { return }; let value = try JSONValue.parse(data)
                        guard case .object = value, !subject.isEmpty, !reason.isEmpty, !ConnectorDraft.ids(evidence).isEmpty else { throw DraftProblem("請填入對象、資料物件、判斷理由與已保存的證據。") }
                        _ = try await api.call("/research/knowledge", method: "POST", body: .object(["kind": .string(kind), "subject": .string(subject), "data": value, "reason": .string(reason), "evidence_ids": .array(ConnectorDraft.ids(evidence))])); dismiss()
                    }
                }
            }
        }.padding(24).frame(width: 750, height: 680).task { if let initial { kind = initial["kind"].string; subject = initial["subject"].string; evidence = initial["evidence_ids"].array.map(\.string).joined(separator: ", "); data = initial["data"].pretty } }
    }
}
