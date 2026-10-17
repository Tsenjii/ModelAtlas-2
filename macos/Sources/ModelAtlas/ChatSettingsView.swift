import AppKit
import AtlasCore
import SwiftUI

@MainActor final class ChatSettingsController: ObservableObject, Identifiable {
    let id = UUID()
    let sessionID: String
    let scope: ModelScope
    @Published var draft = ChatSettingsDraft()
    @Published private(set) var loading = false
    @Published private(set) var loaded = false
    @Published private(set) var saving = false
    @Published private(set) var loadProblem: String?
    @Published private(set) var saveProblem: String?
    @Published private(set) var savedDraft: ChatSettingsDraft?
    private var session: JSONValue
    private var loadRevision = 0
    private var loadedDraft: ChatSettingsDraft?
    var hasUnsavedChanges: Bool { loaded && draft != loadedDraft }
    init(session: JSONValue) {
        self.session = session; sessionID = session["id"].string; scope = ModelScope(session)
    }
    func load(api: AtlasClient?, tools: JSONValue?) async {
        guard !loading, !saving else { return }
        loadRevision += 1; let revision = loadRevision
        loading = true; loaded = false; loadProblem = nil; saveProblem = nil
        defer { if revision == loadRevision { loading = false } }
        do {
            guard let api else { throw AtlasError.engineUnavailable }
            let value = try await api.get("/sessions/settings", query: [URLQueryItem(name: "session_id", value: sessionID)])
            try Task.checkCancellation()
            guard revision == loadRevision else { return }
            let definition = tools ?? value["tools"]
            draft = ChatSettingsDraft(title: session["title"].string, system: value["system"].string,
                maxTokens: value["max_tokens"].display, streaming: value["streaming"].boolean,
                toolsJSON: definition == .null ? "[]" : definition.pretty)
            loadedDraft = draft; savedDraft = nil; loaded = true
        } catch is CancellationError { }
        catch { if !Task.isCancelled && revision == loadRevision { loadProblem = error.localizedDescription } }
    }
    func save(store: AtlasStore) async {
        guard loaded, !loading, !saving, !store.generating, !store.closing else { return }
        saving = true; saveProblem = nil; savedDraft = nil
        defer { saving = false }
        let submitted = draft
        var settingsCommitted = false
        do {
            let validated = try submitted.validated()
            guard let api = store.api else { throw AtlasError.engineUnavailable }
            _ = try await api.call("/sessions/settings", method: "POST", body: .object([
                "session_id": .string(sessionID), "system": .string(submitted.system),
                "max_tokens": .number(Double(validated.tokens)), "streaming": .bool(submitted.streaming)]))
            settingsCommitted = true
            store.stagedTools[sessionID] = validated.tools
            if submitted.title != session["title"].string {
                let updated = try await api.call("/sessions/update", method: "POST", body: .object([
                    "session_id": .string(sessionID), "revision": session["revision"], "title": .string(submitted.title)]))
                session["title"] = updated["title"]; session["revision"] = updated["revision"]
            }
            loadedDraft = submitted; savedDraft = submitted
            // A later refresh failure must not report an already committed save
            // as a failed mutation or replace another open settings controller.
            await store.refresh()
        } catch {
            saveProblem = (settingsCommitted ? "對話參數已儲存，但名稱更新未完成。修改仍保留，可重試。\n" : "") + error.localizedDescription
        }
    }
}

struct ChatSettingsView: View {
    @ObservedObject var store: AtlasStore
    @ObservedObject var model: ChatSettingsController
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            content
            Divider()
            footer
        }.frame(width: 620, height: 610).background(atlasCanvas)
            .task { await load() }
    }
    private var header: some View {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("對話設定").font(.title3.weight(.semibold))
                    Text(store.scopeTitle(model.scope.json)).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                }
                Spacer()
                Button(model.hasUnsavedChanges ? "取消" : "完成") { dismiss() }.keyboardShortcut(.cancelAction)
            }.padding(24)
    }
    private var content: some View {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if model.loading || (!model.loaded && model.loadProblem == nil) {
                        ProgressView("讀取已保存設定…").frame(maxWidth: .infinity, minHeight: 240)
                    } else if let problem = model.loadProblem {
                        VStack(alignment: .leading, spacing: 14) {
                            Label("無法讀取對話設定", systemImage: "exclamationmark.triangle").font(.headline)
                            Text(problem).foregroundStyle(.secondary).textSelection(.enabled)
                            Button("重試讀取") { Task { await load() } }.buttonStyle(.borderedProminent)
                        }.frame(maxWidth: .infinity, alignment: .leading).padding(20)
                    } else {
                        FormField(title: "對話名稱", text: $model.draft.title)
                        VStack(alignment: .leading, spacing: 6) {
                            Text("系統指示").font(.callout.weight(.medium))
                            TextEditor(text: $model.draft.system).font(.body).frame(height: 145).padding(5)
                                .background(atlasSurface).cornerRadius(8)
                                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.secondary.opacity(0.2)))
                                .accessibilityLabel("系統指示")
                        }
                        HStack(alignment: .bottom, spacing: 24) {
                            FormField(title: "回應 Token 上限（1–32768）", text: $model.draft.maxTokens)
                            Toggle("串流回應", isOn: $model.draft.streaming).toggleStyle(.checkbox).padding(.bottom, 5)
                        }
                        DisclosureGroup("工具定義") {
                            Text("不使用工具時填 []；只有明確傳送訊息時才會使用這些定義。")
                                .font(.caption).foregroundStyle(.secondary)
                            JSONEditor(title: "Function tools", text: $model.draft.toolsJSON)
                        }
                        Text("修改只套用到這則對話，已保存的訊息與請求紀錄會保留。")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }.padding(24)
            }
    }
    private var footer: some View {
            HStack(alignment: .center, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    if let problem = model.saveProblem { Text(problem).foregroundStyle(.red).textSelection(.enabled) }
                    else if let saved = model.savedDraft {
                        Label(saved == model.draft ? "已儲存" : "上一份修改已儲存，目前修改尚未儲存。", systemImage: "checkmark.circle")
                            .foregroundStyle(atlasAccent)
                    } else { Text("儲存後繼續留在設定面板。").foregroundStyle(.secondary) }
                }.font(.caption).frame(maxWidth: .infinity, alignment: .leading)
                Button { Task { await model.save(store: store) } } label: {
                    HStack { if model.saving { ProgressView().controlSize(.small) }; Text(model.saving ? "儲存中…" : "儲存") }
                }.buttonStyle(.borderedProminent).disabled(!model.loaded || model.loading || model.saving || store.generating || store.closing)
            }.padding(20)
    }
    private func load() async { await model.load(api: store.api, tools: store.stagedTools[model.sessionID]) }
}
