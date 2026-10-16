import AtlasCore
import SwiftUI

struct AssistantView: View {
    @ObservedObject var store: AtlasStore
    @Environment(\.dismiss) private var dismiss
    @State private var scopeID = ""
    @State private var threadID = ""
    @State private var message = ""
    @State private var output = ""
    @State private var problem: String?
    @State private var threads: [JSONValue] = []
    @State private var history: JSONValue = .array([])
    @State private var actions: [JSONValue] = []
    @State private var busy = false
    @State private var allowTests = false
    @State private var task: Task<Void, Never>?
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack { Label("App Assistant", systemImage: "sparkles").font(.title2.weight(.semibold)); Spacer(); Button("關閉") { dismiss() }.disabled(busy).keyboardShortcut(.cancelAction) }
            Text("使用安全的連線參照研究與操作 App；變更先檢查，再由你確認。").foregroundStyle(.secondary)
            HStack {
                Picker("Assistant 模型", selection: $scopeID) { Text("選擇已確認工具能力的模型").tag(""); ForEach(store.activeModels, id: \.self) { Text($0["model_id"].string + " · " + store.scopeTitle($0)).tag(ModelScope($0).id) } }.disabled(busy)
                ActionButton(title: "設定模型") {
                    guard let model = store.activeModels.first(where: { ModelScope($0).id == scopeID }), let api = store.api else { throw AtlasError.http(422) }
                    _ = try await api.call("/assistant/config", method: "POST", body: .object(["selection": ModelScope(model).json]))
                }.disabled(busy)
            }
            Picker("對話", selection: $threadID) { Text("新的 Assistant 對話").tag(""); ForEach(threads, id: \.self) { Text($0["selection"]["model_id"].string + " · " + String($0["id"].string.prefix(8))).tag($0["id"].string) } }.disabled(busy).onChange(of: threadID) { _ in if !busy { Task { await loadHistory() } } }
            if let problem { Text(problem).foregroundStyle(.red) }
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if history != .array([]) { JSONTree(value: history) }
                    if !output.isEmpty { AtlasCard { Text(output).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading) } }
                    ForEach(actions, id: \.self) { action in
                        if ["CONTROL_CHANGE", "ADD_MY_MODEL", "REMOVE_MY_MODEL"].contains(action["action"].string) {
                            AtlasCard {
                                HStack { Text(action["subject"].display).font(.headline); Spacer(); SmallBadge(text: action["state"].string, color: atlasAccent) }
                                DisclosureGroup("變更內容") { Text("之前").font(.headline); JSONTree(value: action["before"]); Text("之後").font(.headline); JSONTree(value: action["after"]) }
                                if action["state"].string == "PENDING" { ActionButton(title: "確認套用這項變更") { _ = try await store.mutate("/assistant/actions/confirm", body: .object(["id": action["id"], "confirm": .bool(true)])); await load() } }
                                if action["state"].string == "CONFIRMED" { ActionButton(title: "復原這項變更") { _ = try await store.mutate("/assistant/actions/undo", body: .object(["id": action["id"], "confirm": .bool(true)])); await load() } }
                            }
                        }
                    }
                }
            }
            Toggle("這輪允許最小模型測試", isOn: $allowTests).toggleStyle(.checkbox).disabled(busy)
            TextEditor(text: $message).frame(height: 90).padding(8).overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.secondary.opacity(0.2))).accessibilityLabel("Assistant 訊息")
            HStack {
                Spacer()
                if busy { ActionButton(title: "停止 Assistant") { guard let api = store.api else { return }; _ = try await api.call("/assistant/cancel", method: "POST", body: .object(["thread_id": .string(threadID)])) } }
                else { Button("傳送給 Assistant") { send() }.buttonStyle(.borderedProminent).controlSize(.large).disabled(message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) }
            }
        }.padding(26).frame(width: 850, height: 740).task {
            if let api = store.api, let config = try? await api.get("/assistant/config"), config["configured"].boolean { scopeID = ModelScope(config["selection"]).id }
            await load()
        }
        .onDisappear { task?.cancel() }
    }
    private func send() {
        guard let api = store.api, !busy else { return }
        let prompt = message; message = ""; output = ""; busy = true; problem = nil
        task = Task {
            defer { busy = false; task = nil }
            do {
                try await api.stream("/assistant/run", body: .object(["thread_id": .string(threadID), "message": .string(prompt), "allow_tests": .bool(allowTests)])) { event in
                    if event.name == "thread" { threadID = event.value["id"].string }
                    if event.name == "done" { output = event.value["text"].string }
                    if event.name == "tool" { output += "\n" + event.value["name"].string + " · " + event.value["status"].string }
                    if event.name == "error" { problem = "Assistant：" + event.value["category"].string }
                }
                await load(); await loadHistory(); await store.refresh()
            } catch { problem = error.localizedDescription; if message.isEmpty { message = prompt } }
        }
    }
    private func load() async {
        guard let api = store.api else { return }
        do { async let t = api.get("/assistant/threads"), a = api.get("/assistant/actions"); let values = try await (t, a); threads = try values.0.requiredArray(); actions = try values.1.requiredArray() }
        catch { problem = error.localizedDescription }
    }
    private func loadHistory() async {
        guard let api = store.api, !threadID.isEmpty else { history = .array([]); return }
        let id = threadID
        do { let result = try await api.get("/assistant/history", query: [URLQueryItem(name: "thread_id", value: id)]); if threadID == id { history = result } }
        catch { problem = error.localizedDescription }
    }
}
