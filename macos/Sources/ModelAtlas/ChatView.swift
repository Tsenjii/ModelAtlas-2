import AppKit
import AtlasCore
import AVKit
import SwiftUI

struct ChatView: View {
    @ObservedObject var store: AtlasStore
    @State private var dropTargeted = false
    @State private var editorHeight: CGFloat = 30
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(store.selectedSession?["title"].string ?? "新對話").font(.system(size: 14, weight: .semibold)).lineLimit(1)
                    Text(store.selectedModel.map(store.scopeTitle) ?? "選擇已加入的模型開始對話").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        .help(store.selectedModel.map(store.scopeTitle) ?? "")
                }
                Spacer(minLength: 8)
                Button { store.newConversation() } label: { Image(systemName: "square.and.pencil") }
                    .buttonStyle(AtlasIconButtonStyle()).help("新增對話（⌘N）").disabled(store.generating)
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
            }.padding(.horizontal, 24).padding(.vertical, 10)
            Divider()
            if store.messages.isEmpty && !store.generating && store.mediaResult == nil {
                welcome
            } else {
                ChatTranscriptView(store: store)
            }
            composer
        }
    }
    private var welcome: some View {
        VStack(alignment: .leading, spacing: 14) {
            if store.activeModels.isEmpty {
                Text("尚未加入模型").font(.system(size: 19, weight: .semibold))
                Text("新增供應商，檢查連線後選擇要使用的模型。").font(.system(size: 13)).foregroundStyle(.secondary)
                Button { store.newProviderOpen = true } label: { Label("新增供應商", systemImage: "plus") }.buttonStyle(AtlasActionStyle(prominent: true))
            } else if let model = store.selectedModel {
                Text(ModelSearchEntry(model: model, context: "", operation: "").title).font(.system(size: 19, weight: .semibold)).textSelection(.enabled)
                VStack(alignment: .leading, spacing: 6) {
                    Text(store.scopeTitle(model)).font(.system(size: 12)).foregroundStyle(.secondary)
                    Text(operationTitle(store.operationName(model)) + " · " + store.endpointLabel(model)).font(.system(size: 11)).foregroundStyle(.secondary)
                }
                HStack(spacing: 10) {
                    Button("切換模型") { store.modelPickerOpen = true }.buttonStyle(AtlasActionStyle())
                    Button("模型資料") { store.detailModel = model }.buttonStyle(AtlasActionStyle())
                }.padding(.top, 3)
                Text("⌘L 輸入訊息   ·   ⌘K 選擇模型   ·   ⌘O 加入附件").font(.system(size: 11)).foregroundStyle(.secondary).padding(.top, 5)
            } else {
                Text("選擇模型").font(.system(size: 19, weight: .semibold))
                Text("搜尋已加入的模型、帳戶或 Key。").font(.system(size: 13)).foregroundStyle(.secondary)
                Button("選擇模型…") { store.modelPickerOpen = true }.buttonStyle(AtlasActionStyle(prominent: true))
            }
        }.frame(maxWidth: 480, alignment: .leading).padding(28).frame(maxWidth: .infinity, maxHeight: .infinity)
    }
    private var composer: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let model = store.selectedModel, MediaDraft.operations.contains(store.operationName(model)) {
                MediaOptionsView(draft: $store.mediaDraft, operation: store.operationName(model)).disabled(store.generating)
            }
            if !store.attachments.isEmpty { AttachmentTray(store: store) }
            ZStack(alignment: .topLeading) {
                ComposerTextEditor(text: $store.draft, focusRequest: store.composerFocusRequest, onHeightChange: { editorHeight = $0 }).frame(height: editorHeight)
                    .accessibilityLabel("訊息草稿").accessibilityIdentifier("chat-composer")
                if store.draft.isEmpty { Text(store.generating ? "輸入下一則訊息…" : "輸入訊息…").font(.system(size: 14)).foregroundStyle(.secondary).padding(.horizontal, 8).padding(.top, 5).allowsHitTesting(false) }
            }
            HStack(spacing: 8) {
                Button { store.pickAttachments() } label: { Image(systemName: "paperclip") }.buttonStyle(AtlasIconButtonStyle()).accessibilityLabel("加入附件").disabled(store.generating || store.attaching).help("加入附件（⌘O），也可拖入圖片、PDF、WAV、MP3 · 每份 4 MiB")
                Button { store.modelPickerOpen = true } label: {
                    HStack(spacing: 5) {
                        Text(store.selectedModel.map { ModelSearchEntry(model: $0, context: "", operation: "").title } ?? store.selectedScope?.modelID ?? "選擇模型").font(.system(size: 12, weight: .medium)).lineLimit(1).truncationMode(.middle)
                        Image(systemName: "chevron.down").font(.system(size: 9)).foregroundStyle(.secondary)
                    }.padding(.horizontal, 7).frame(height: 25).background(atlasInset).cornerRadius(5)
                }.buttonStyle(.plain).disabled(store.generating).help("選擇模型（⌘K）")
                Spacer(minLength: 4)
                Text(store.attaching ? "加入附件中…" : "⌘↵").font(.system(size: 10)).foregroundStyle(.secondary)
                if store.generating { Button { Task { await store.cancel() } } label: { Label("停止", systemImage: "stop.fill") }.keyboardShortcut(.escape, modifiers: []).disabled(store.hasPresentedSheet) }
                else { Button { store.send() } label: { Image(systemName: "arrow.up").font(.system(size: 14, weight: .semibold)).frame(width: 16, height: 22) }.buttonStyle(.borderedProminent).keyboardShortcut(.return, modifiers: .command).disabled(store.attaching || store.hasPresentedSheet || !store.canSend).accessibilityLabel("傳送訊息").accessibilityIdentifier("chat-send") }
            }
        }.padding(10).background(atlasSurface).cornerRadius(10)
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(dropTargeted ? atlasAccent : Color.primary.opacity(0.16), lineWidth: dropTargeted ? 2 : 0.5))
            .overlay {
                if dropTargeted { Label("放開以加入附件", systemImage: "arrow.down.doc").padding(14).background(.regularMaterial).cornerRadius(10).allowsHitTesting(false) }
            }
            .onDrop(of: ["public.file-url"], delegate: ComposerFileDrop(store: store, targeted: $dropTargeted))
            .frame(maxWidth: 900).padding(.horizontal, 24).padding(.bottom, 16).padding(.top, 8).frame(maxWidth: .infinity)
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
                Label(message["role"].string == "user" ? "你" : message["role"].string == "tool" ? "工具結果" : "ModelAtlas", systemImage: message["role"].string == "user" ? "person.crop.circle" : "bubble.left").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
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
        }.padding(message["role"].string == "user" ? 14 : 0)
            .background(message["role"].string == "user" ? atlasInset.opacity(0.65) : Color.clear).cornerRadius(10)
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
