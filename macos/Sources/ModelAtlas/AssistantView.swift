import AtlasCore
import SwiftUI

struct AssistantView: View {
    @ObservedObject var store: AtlasStore
    @Environment(\.dismiss) private var dismiss
    @State private var scopeID = ""
    @State private var configuredID = ""
    @State private var refs: [JSONValue] = []
    @State private var reviews: [JSONValue] = []
    @State private var tools: [JSONValue] = []
    @State private var secretEditor = false
    @State private var reviewController: ActionReviewController?
    @State private var onboarding: JSONValue?
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
                Picker("Assistant 模型", selection: $scopeID) { Text("選擇已確認工具能力的模型").tag(""); ForEach(assistantModels, id: \.self) { Text($0["model_id"].string + " · " + store.scopeTitle($0)).tag(ModelScope($0).id) } }.disabled(busy)
                ActionButton(title: "設定模型") {
                    guard let model = store.activeModels.first(where: { ModelScope($0).id == scopeID }), let api = store.api else { throw AtlasError.http(422) }
                    _ = try await api.call("/assistant/config", method: "POST", body: .object(["selection": ModelScope(model).json])); configuredID = ModelScope(model).id; threadID = ""; history = .array([]); output = ""; await load()
                }.disabled(busy)
            }
            Picker("對話", selection: $threadID) { Text("新的 Assistant 對話").tag(""); ForEach(threads.filter { ModelScope($0["selection"]).id == configuredID }, id: \.self) { Text($0["selection"]["model_id"].string + " · " + String($0["id"].string.prefix(8))).tag($0["id"].string) } }.disabled(busy).onChange(of: threadID) { _ in if !busy { Task { await loadHistory() } } }
            if let problem { Text(problem).foregroundStyle(.red) }
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    ForEach(history.array, id: \.self) { run in
                        AtlasCard { Label("你", systemImage: "person.crop.circle").font(.caption); Text(run["message"].string).textSelection(.enabled); Divider(); Label("Assistant", systemImage: "sparkles").font(.caption); ChatMarkdown(source: run["result"]["text"].string.isEmpty ? "工作狀態：" + run["status"].string : run["result"]["text"].string).equatable() }
                    }
                    if !output.isEmpty { AtlasCard { ChatMarkdown(source: output).equatable().frame(maxWidth: .infinity, alignment: .leading) } }
                    ForEach(tools, id: \.self) { tool in AtlasCard { HStack { Text(tool["name"].string).font(.headline); Spacer(); SmallBadge(text: tool["status"].string) }; DisclosureGroup("工具結果") { JSONTree(value: tool["result"]) } } }
                    DisclosureGroup("安全 Key 參照") {
                        Button("安全輸入 Key／PAT…") { secretEditor = true }.disabled(busy)
                        ForEach(refs, id: \.self) { ref in HStack { VStack(alignment: .leading) { Text(ref["label"].string); Text(ref["base_url"].string + " · " + ref["role"].string).font(.caption).foregroundStyle(.secondary) }; Spacer(); Button("加入這個參照") { message += "\n安全參照 " + ref["id"].string + "（" + ref["label"].string + "）" }.disabled(busy) } }
                    }
                    DisclosureGroup("待確認與操作紀錄") {
                        ForEach(reviews.filter { $0["status"].string == "REVIEW" }, id: \.self) { row in Button("新增連線 · " + row["provider"]["name"].string) { onboarding = row }.disabled(busy) }
                        ForEach(actions, id: \.self) { action in
                            Button { if let api = store.api { reviewController = ActionReviewController(api: api, value: action) } } label: { HStack { Text(action["action"].string); Spacer(); SmallBadge(text: action["state"].string); Image(systemName: "chevron.right") }.padding(10) }.buttonStyle(.plain).disabled(busy)
                        }
                    }

                }
            }
            Toggle("這輪允許最小模型測試", isOn: $allowTests).toggleStyle(.checkbox).disabled(busy)
            TextEditor(text: $message).frame(height: 90).padding(8).overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.secondary.opacity(0.2))).accessibilityLabel("Assistant 訊息")
            HStack {
                Spacer()
                if busy { ActionButton(title: "停止 Assistant") { defer { task?.cancel() }; if let api = store.api, !threadID.isEmpty { _ = try await api.call("/assistant/cancel", method: "POST", body: .object(["thread_id": .string(threadID)])) } } }
                else { Button("傳送給 Assistant") { send() }.buttonStyle(.borderedProminent).controlSize(.large).disabled(message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || configuredID.isEmpty || scopeID != configuredID) }
            }
        }.padding(26).frame(width: 850, height: 740).task {
            if let api = store.api, let config = try? await api.get("/assistant/config"), config["configured"].boolean { scopeID = ModelScope(config["selection"]).id; configuredID = scopeID }
            await load()
        }
        .onDisappear { task?.cancel() }
        .sheet(isPresented: $secretEditor, onDismiss: { Task { await load() } }) { AssistantSecretEditor(store: store) { ref in message += "\n安全參照 " + ref["id"].string + "（" + ref["label"].string + "）" }.atlasModal(store) }
        .sheet(item: $reviewController, onDismiss: { Task { await load() } }) { ActionReviewView(store: store, model: $0).atlasModal(store) }
        .sheet(item: $onboarding, onDismiss: { Task { await load() } }) { AddProviderView(store: store, resumedReview: $0).atlasModal(store) }
    }
    private var assistantModels: [JSONValue] { store.activeModels.filter { ["CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC_MESSAGES", "GEMINI_NATIVE", "CUSTOM"].contains(store.operationName($0)) && !$0["capabilities"].array.filter { [.string("TOOL_CALLING"), .string("FUNCTION_CALLING")].contains($0) }.isEmpty } }
    private func send() {
        guard let api = store.api, !busy, !configuredID.isEmpty, scopeID == configuredID else { return }
        let prompt = message; message = ""; output = ""; tools = []; busy = true; problem = nil
        task = Task {
            defer { busy = false; task = nil }
            do {
                try await api.stream("/assistant/run", body: .object(["thread_id": .string(threadID), "message": .string(prompt), "allow_tests": .bool(allowTests)])) { event in
                    if event.name == "thread" { threadID = event.value["id"].string }
                    if event.name == "delta" { output += event.value["text"].string }
                    if event.name == "done", !event.value["text"].string.isEmpty { output = event.value["text"].string }
                    if event.name == "tool" {
                        let callID = event.value["call_id"]
                        if callID != .null, let index = tools.firstIndex(where: { $0["call_id"] == callID }) { tools[index] = event.value } else { tools.append(event.value) }
                        if !store.generating, let selected = store.activeModels.first(where: { ModelScope($0) == ModelScope(event.value["result"]["ui_selection"]) }) { store.chooseModel(selected) }
                    }
                    if event.name == "error" { problem = "Assistant：" + event.value["category"].string }
                }
                await load(); await loadHistory(); await store.refresh(); if problem == nil { output = "" }
            } catch { problem = error.localizedDescription; if message.isEmpty { message = prompt } }
        }
    }
    private func load() async {
        guard let api = store.api else { return }
        do { async let t = api.get("/assistant/threads"), a = api.get("/assistant/actions"), r = api.get("/assistant/secrets"), o = api.get("/onboarding"); let values = try await (t, a, r, o); threads = try values.0.requiredArray(); actions = try values.1.requiredArray(); refs = try values.2.requiredArray(); reviews = try values.3.requiredArray() }
        catch { problem = error.localizedDescription }
    }
    private func loadHistory() async {
        guard let api = store.api, !threadID.isEmpty else { history = .array([]); return }
        let id = threadID
        do { let result = try await api.get("/assistant/history", query: [URLQueryItem(name: "thread_id", value: id)]); if threadID == id { history = result } }
        catch { problem = error.localizedDescription }
    }
}
