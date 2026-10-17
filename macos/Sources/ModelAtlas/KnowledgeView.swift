import AtlasCore
import SwiftUI

@MainActor final class VersionController: ObservableObject {
    let api: AtlasClient, connector: Bool
    @Published var value: JSONValue
    @Published var busy = false
    @Published var problem: String?
    @Published var undoID: String?
    init(api: AtlasClient, value: JSONValue, connector: Bool) { self.api = api; self.value = value; self.connector = connector }
    func reload() async throws {
        let values = try await api.get(connector ? "/connectors" : "/research/knowledge").requiredArray()
        guard let latest = values.first(where: { $0["id"] == value["id"] }) else { throw AtlasError.invalidResponse }; value = latest
    }
    func activate() async {
        guard !busy else { return }; busy = true; problem = nil; defer { busy = false }
        do {
            let result = try await api.call(connector ? "/connectors/activate" : "/research/knowledge/activate", method: "POST", body: .object(["id": value["id"], "expected_active_id": .string(value["active_id"].string), "confirm": .bool(true)]))
            undoID = result["action_id"].string; try await reload()
        } catch { problem = error.localizedDescription }
    }
    func undo() async {
        guard !busy, let undoID else { return }; busy = true; problem = nil; defer { busy = false }
        do { _ = try await api.call("/versions/undo", method: "POST", body: .object(["id": .string(undoID), "confirm": .bool(true)])); self.undoID = nil; try await reload() }
        catch { problem = error.localizedDescription }
    }
    func test(_ scope: ModelScope) async {
        guard !busy, connector, scope.providerID == value["provider_id"].string else { return }; busy = true; problem = nil; defer { busy = false }
        do { _ = try await api.call("/connectors/test", method: "POST", body: .object(["id": value["id"], "selection": scope.json])); try await reload() }
        catch { problem = error.localizedDescription }
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
            if let problem = controller.problem { Text(problem).foregroundStyle(.red) }
            ScrollView { VStack(alignment: .leading, spacing: 18) { Text(controller.value["reason"].string); JSONTree(value: controller.value[controller.connector ? "config" : "data"]); DisclosureGroup("證據與測試") { JSONTree(value: controller.value["evidence_ids"]); JSONTree(value: controller.value["test"]); Text("版本 ID：" + controller.value["id"].string).font(.caption).textSelection(.enabled) } } }
            if controller.connector {
                Picker("測試使用的精確連線", selection: $scopeID) { Text("選擇連線").tag(""); ForEach(store.activeModels.filter { $0["provider_id"] == controller.value["provider_id"] }, id: \.self) { Text($0["model_id"].string + " · " + store.scopeTitle($0) + " · " + $0["endpoint"].string).tag(ModelScope($0).id) } }
                Button("測試資料擷取") { if let model = store.activeModels.first(where: { ModelScope($0).id == scopeID }) { Task { await controller.test(ModelScope(model)) } } }.disabled(controller.busy || scopeID.isEmpty)
            }
            HStack {
                if controller.busy { ProgressView().controlSize(.small) }
                if let _ = controller.undoID { Button("復原這次啟用") { Task { await controller.undo(); await store.refresh() } }.disabled(controller.busy) }
                Spacer()
                if !controller.value["active"].boolean { Button("確認啟用此版本") { Task { await controller.activate(); await store.refresh() } }.buttonStyle(.borderedProminent).disabled(controller.busy || (controller.connector && controller.value["status"].string != "VALIDATED")) }
            }
        }.padding(24).frame(width: 780, height: 700)
        .sheet(isPresented: $editing) { Group { if controller.connector { ConnectorEditor(store: store, initial: controller.value) } else { KnowledgeEditor(store: store, initial: controller.value) } }.atlasModal(store) }
    }
}

struct MappingEditor: View {
    @Binding var rows: [FieldMapping]
    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            Text("欄位對照").font(.headline)
            ForEach($rows) { $row in HStack { TextField("資料欄位", text: $row.name); TextField("JSON Pointer，例如 /limits/rpd", text: $row.pointer); Button { rows.removeAll { $0.id == row.id } } label: { Image(systemName: "minus.circle") }.help("移除欄位") }.textFieldStyle(.roundedBorder) }
            Button { rows.append(FieldMapping()) } label: { Label("加入欄位", systemImage: "plus") }.disabled(rows.count >= 20)
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
                    FormField(title: "規則名稱", text: $draft.name).disabled(initial != nil)
                    Picker("資料種類", selection: $draft.kind) { ForEach(["QUOTA", "PRICING", "BALANCE", "USAGE", "PERFORMANCE", "NOTICE", "CATALOG"], id: \.self) { Text($0).tag($0) } }
                    FormField(title: "唯讀 GET Path", text: $draft.path)
                    HStack {
                        Picker("憑證用途", selection: $draft.role) { ForEach(["INFERENCE", "ACCOUNT_PAT", "NONE"], id: \.self) { Text($0).tag($0) } }
                        Picker("資料範圍", selection: $draft.scope) { ForEach(["MODEL_KEY_GROUP", "MODEL_PROVIDER", "ACCOUNT", "CATALOG"], id: \.self) { Text($0).tag($0) } }
                    }
                    if draft.role == "ACCOUNT_PAT" { Picker("帳戶 PAT 參照", selection: $draft.pat) { Text("選擇同一帳戶／來源的 PAT").tag(""); ForEach(store.credentials.filter { $0["provider_id"].string == draft.provider && $0["role"].string == "ACCOUNT_PAT" }, id: \.self) { Text($0["account_id"].string + " · " + $0["label"].string).tag($0["id"].string) } } }
                    FormField(title: "清單 JSON Pointer（根節點可留空）", text: $draft.root)
                    MappingEditor(rows: $draft.mappings)
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
