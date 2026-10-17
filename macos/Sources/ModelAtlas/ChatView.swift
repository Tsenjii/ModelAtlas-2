import AppKit
import AtlasCore
import AVKit
import SwiftUI

struct ChatView: View {
    @ObservedObject var store: AtlasStore
    @State private var dropTargeted = false
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(store.selectedSession?["title"].string ?? "新對話").font(.title3.weight(.semibold)).lineLimit(1)
                    Text(store.selectedModel.map(store.scopeTitle) ?? "選擇已加入的模型開始對話").font(.caption).foregroundStyle(.secondary).lineLimit(2)
                }
                Spacer(minLength: 8)
                Button { store.newConversation() } label: { Image(systemName: "square.and.pencil") }
                    .buttonStyle(.plain).help("新增對話（⌘N）").disabled(store.generating)
                Menu {
                    if let session = store.selectedSession {
                        Button(session["pinned"].boolean ? "取消置頂" : "置頂對話") { update(session, key: "pinned", value: .bool(!session["pinned"].boolean)) }
                        Button(session["archived"].boolean ? "恢復對話" : "封存對話") { update(session, key: "archived", value: .bool(!session["archived"].boolean)) }
                        Button("重新命名…") { store.openChatSettings() }
                    }
                    Button("對話設定…") { store.openChatSettings() }.disabled(store.sessionID == nil)
                    Button("匯出完整 Markdown…") { export("markdown") }.disabled(store.sessionID == nil)
                    Button("匯出完整 JSON…") { export("json") }.disabled(store.sessionID == nil)
                } label: { Image(systemName: "ellipsis.circle").font(.title3) }.menuStyle(.borderlessButton).frame(width: 26).disabled(store.generating)
            }.padding(.horizontal, 24).padding(.vertical, 16)
            Divider()
            if store.messages.isEmpty && !store.generating && store.mediaResult == nil {
                EmptyCard(icon: "sparkle", title: store.activeModels.isEmpty ? "加入第一個模型" : "今天想完成什麼？",
                          detail: store.activeModels.isEmpty ? "新增供應商，把想用的模型加入工作區。你的對話會保存在這台 Mac。" : "聊天、研究、寫程式，或選擇影像與音訊模型。",
                          action: store.activeModels.isEmpty ? "新增供應商" : nil) { store.newProviderOpen = true }
            } else {
                ChatTranscriptView(store: store)
            }
            composer
        }
    }
    private var composer: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Button { store.modelPickerOpen = true } label: {
                    HStack(spacing: 7) {
                        Image(systemName: "cube").foregroundStyle(atlasAccent)
                        Text(store.selectedModel.map { ModelSearchEntry(model: $0, context: "", operation: "").title } ?? store.selectedScope?.modelID ?? "選擇模型").font(.callout.weight(.medium)).lineLimit(1).truncationMode(.middle)
                        Image(systemName: "chevron.down").font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
                    }.padding(.horizontal, 10).padding(.vertical, 7).background(Color.primary.opacity(0.035)).cornerRadius(8)
                }.buttonStyle(.plain).disabled(store.generating).help("選擇模型（⌘K）")
                Spacer(minLength: 0)
                if let model = store.selectedModel { Text(operationTitle(store.operationName(model))).font(.caption).foregroundStyle(.secondary) }
            }
            if let model = store.selectedModel, MediaDraft.operations.contains(store.operationName(model)) {
                MediaOptionsView(draft: $store.mediaDraft, operation: store.operationName(model)).disabled(store.generating)
            }
            if !store.attachments.isEmpty { AttachmentTray(store: store) }
            ZStack(alignment: .topLeading) {
                TextEditor(text: $store.draft).font(.body).frame(minHeight: 62, maxHeight: 86).padding(4)
                    .accessibilityLabel("訊息草稿").accessibilityIdentifier("chat-composer")
                if store.draft.isEmpty { Text(store.generating ? "先寫下一則訊息…" : "輸入訊息…  ⌘↵ 傳送").font(.body).foregroundStyle(.tertiary).padding(.horizontal, 10).padding(.top, 12).allowsHitTesting(false) }
            }
            HStack {
                Button { store.pickAttachments() } label: { Label("附件", systemImage: "paperclip") }.disabled(store.generating || store.attaching).help("也可拖入圖片、PDF、WAV、MP3 · 每份 4 MiB")
                Spacer()
                Text(store.attaching ? "正在加入附件…" : "拖入附件 · ⌘↵ 傳送").font(.caption2).foregroundStyle(.tertiary)
                if store.generating { Button { Task { await store.cancel() } } label: { Label("停止", systemImage: "stop.fill") }.keyboardShortcut(.escape, modifiers: []).disabled(store.hasPresentedSheet) }
                else { Button { store.send() } label: { Label("傳送", systemImage: "arrow.up") }.buttonStyle(.borderedProminent).controlSize(.large).keyboardShortcut(.return, modifiers: .command).disabled(store.attaching || store.hasPresentedSheet || !store.canSend).accessibilityIdentifier("chat-send") }
            }
        }.padding(14).background(atlasSurface).cornerRadius(14)
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(dropTargeted ? atlasAccent : Color.primary.opacity(0.1), lineWidth: dropTargeted ? 2 : 1))
            .overlay {
                if dropTargeted { Label("放開以加入附件", systemImage: "arrow.down.doc").padding(14).background(.regularMaterial).cornerRadius(10).allowsHitTesting(false) }
            }
            .onDrop(of: ["public.file-url"], delegate: ComposerFileDrop(store: store, targeted: $dropTargeted))
            .padding(.horizontal, 24).padding(.bottom, 20).padding(.top, 10)
    }
    private func update(_ session: JSONValue, key: String, value: JSONValue) {
        Task {
            do { _ = try await store.mutate("/sessions/update", body: .object(["session_id": session["id"], "revision": session["revision"], key: value])) }
            catch { store.problem = error.localizedDescription }
        }
    }
    private func export(_ format: String) {
        guard let id = store.sessionID else { return }
        Task { await store.export("/sessions/export", query: [URLQueryItem(name: "session_id", value: id), URLQueryItem(name: "format", value: format)], filename: "ModelAtlas-Conversation." + (format == "json" ? "json" : "md")) }
    }
}

struct MessageBubble: View {
    let message: JSONValue
    @ObservedObject var store: AtlasStore
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label(message["role"].string == "user" ? "你" : message["role"].string == "tool" ? "工具結果" : "ModelAtlas", systemImage: message["role"].string == "user" ? "person.crop.circle" : "sparkles").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                Spacer()
                Button { copyText(message["content"].string) } label: { Image(systemName: "doc.on.doc") }.buttonStyle(.plain).help("複製訊息")
                if !message["sample_id"].string.isEmpty {
                    Menu("請求") {
                        Button("查看請求收據…") { store.requestReceipt = message["sample_id"].string }
                        if !["SUCCESS", "RUNNING", ""].contains(message["request_outcome"].string) { Button("重試這次請求") { store.send(retry: message["sample_id"].string) }.disabled(store.generating) }
                    }.menuStyle(.borderlessButton).frame(width: 55)
                }
            }
            Group {
                if message["role"].string == "assistant" { ChatMarkdown(source: message["content"].string).equatable() }
                else { Text(message["content"].string).textSelection(.enabled).lineSpacing(5).frame(maxWidth: .infinity, alignment: .leading) }
            }
            if !message["attachments"].array.isEmpty {
                ForEach(message["attachments"].array, id: \.self) { SmallBadge(text: $0["filename"].string) }
            }
            if !message["tool_calls"].array.isEmpty { DisclosureGroup("工具呼叫") { JSONTree(value: message["tool_calls"]) } }
        }.padding(message["role"].string == "user" ? 18 : 0)
            .background(message["role"].string == "user" ? atlasAccent.opacity(0.065) : Color.clear).cornerRadius(14)
    }
}

struct MediaResultView: View {
    let value: JSONValue
    @State private var player: AVAudioPlayer?
    @State private var problem: String?
    var body: some View {
        AtlasCard {
            HStack { Label(value["outcome"].string == "SUCCESS" ? "生成結果" : "請求結果", systemImage: "sparkles").font(.headline); Spacer(); SmallBadge(text: value["outcome"].string) }
            if let problem { Text(problem).foregroundStyle(.red) }
            if !value["text"].string.isEmpty { Text(value["text"].string).textSelection(.enabled) }
            ForEach(value["data"].array, id: \.self) { item in
                if let data = Data(base64Encoded: item["b64_json"].string) {
                    if item["mime"].string.hasPrefix("image/"), let image = NSImage(data: data) { Image(nsImage: image).resizable().scaledToFit().frame(maxHeight: 520).cornerRadius(12) }
                    HStack {
                        if item["mime"].string.hasPrefix("audio/") { Button("播放／重播") { do { player = try AVAudioPlayer(data: data); guard player?.play() == true else { throw DraftProblem("這個音訊格式無法在本機播放，請儲存後使用其他播放器。") }; problem = nil } catch { problem = error.localizedDescription } }; Button("停止播放") { player?.stop() } }
                        Button("儲存結果…") {
                            save(data, name: MediaDraft.filename(mime: item["mime"].string))
                        }
                    }
                } else if !item["url"].string.isEmpty { Button("開啟供應商圖片") { openHTTPS(item["url"].string) } }
            }
            if !value["vectors"].array.isEmpty { DisclosureGroup("向量結果") { JSONTree(value: value["vectors"]) } }
            if !value["rankings"].array.isEmpty { JSONTree(value: value["rankings"]) }
            HStack {
                Button("複製文字") { copyText(value["text"].string) }.disabled(value["text"].string.isEmpty)
                Button("儲存文字…") { save(Data(value["text"].string.utf8), name: "ModelAtlas-Transcript.txt") }.disabled(value["text"].string.isEmpty)
                Button("下載完整 JSON…") { save(Data(value.pretty.utf8), name: "ModelAtlas-Result.json") }
            }
            DisclosureGroup("請求與來源") { JSONTree(value: value["selection"]); JSONTree(value: value["details"]) }
        }
    }
    private func save(_ data: Data, name: String) {
        let panel = NSSavePanel(); panel.nameFieldStringValue = name
        if panel.runModal() == .OK, let url = panel.url {
            do { try data.write(to: url, options: .atomic); problem = nil } catch { problem = "儲存失敗：" + error.localizedDescription }
        }
    }

}
