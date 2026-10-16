import AppKit
import AtlasCore
import AVKit
import SwiftUI

struct ChatView: View {
    @ObservedObject var store: AtlasStore
    @State private var follow = true
    @State private var options = false
    @State private var system = ""
    @State private var maxTokens = "512"
    @State private var streaming = true
    @State private var title = ""
    @State private var toolsJSON = "[]"
    @State private var optionsLoaded = false
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(store.selectedSession?["title"].string ?? "新的想法，從這裡開始").font(.title3.weight(.semibold)).lineLimit(1)
                    Text(store.selectedModel.map(store.scopeTitle) ?? "選擇已加入的模型開始對話").font(.caption).foregroundStyle(.secondary).lineLimit(2)
                }
                Spacer(minLength: 8)
                Menu {
                    if let session = store.selectedSession {
                        Button(session["pinned"].boolean ? "取消置頂" : "置頂對話") { update(session, key: "pinned", value: .bool(!session["pinned"].boolean)) }
                        Button(session["archived"].boolean ? "恢復對話" : "封存對話") { update(session, key: "archived", value: .bool(!session["archived"].boolean)) }
                        Button("重新命名…") { title = session["title"].string; options = true }
                    }
                    Button("對話設定…") { optionsLoaded = false; options = true }.disabled(store.sessionID == nil)
                    Button("匯出完整 Markdown…") { export("markdown") }.disabled(store.sessionID == nil)
                    Button("匯出完整 JSON…") { export("json") }.disabled(store.sessionID == nil)
                } label: { Image(systemName: "ellipsis.circle").font(.title3) }.menuStyle(.borderlessButton).frame(width: 26).disabled(store.generating)
            }.padding(.horizontal, 28).padding(.vertical, 20)
            Divider()
            if store.messages.isEmpty && !store.generating && store.mediaResult == nil {
                EmptyCard(icon: "sparkle", title: store.activeModels.isEmpty ? "讓模型，開始為你工作" : "今天想完成什麼？",
                          detail: store.activeModels.isEmpty ? "新增供應商，把想用的模型加入工作區。你的對話會保存在這台 Mac。" : "聊天、研究、寫程式，或選擇影像與音訊模型。",
                          action: store.activeModels.isEmpty ? "新增供應商" : nil) { store.newProviderOpen = true }
            } else {
                ScrollViewReader { reader in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 26) {
                            if store.messageHasMore { Button("載入更早的訊息") { follow = false; Task { await store.loadMessages(older: true) } }.frame(maxWidth: .infinity) }
                            ForEach(store.messages, id: \.self) { message in
                                MessageBubble(message: message, store: store).id(message["id"].string)
                            }
                            if !store.pendingToolCalls.isEmpty && !store.generating {
                                AtlasCard {
                                    Text("工具結果").font(.headline)
                                    ForEach(store.pendingToolCalls, id: \.self) { call in
                                        Text(call["function"]["name"].string).font(.callout.weight(.semibold))
                                        TextEditor(text: Binding(get: { store.toolResults[call["id"].string] ?? "" }, set: { store.toolResults[call["id"].string] = $0 })).frame(height: 90)
                                    }
                                    Button("送出全部工具結果並繼續") { store.send(toolContinuation: true) }.disabled(store.pendingToolCalls.contains { (store.toolResults[$0["id"].string] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty })
                                }
                            }
                            if store.generating {
                                VStack(alignment: .leading, spacing: 12) {
                                    Label("ModelAtlas", systemImage: "sparkles").font(.caption.weight(.semibold)).foregroundStyle(atlasAccent)
                                    if !store.reasoning.isEmpty { DisclosureGroup("思考過程") { Text(store.reasoning).foregroundStyle(.secondary).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading) } }
                                    if store.streamingText.isEmpty { ProgressView().controlSize(.small) }
                                    else { Text(store.streamingText).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading) }
                                }
                            }
                            if let media = store.mediaResult { MediaResultView(value: media) }
                            Color.clear.frame(height: 1).id("chat-bottom")
                        }.padding(30).frame(maxWidth: 880).frame(maxWidth: .infinity)
                    }
                    .onChange(of: store.streamingText) { _ in if follow { reader.scrollTo("chat-bottom", anchor: .bottom) } }
                    .onChange(of: store.sessionID) { _ in follow = true; reader.scrollTo("chat-bottom", anchor: .bottom) }
                    .overlay(alignment: .bottomTrailing) {
                        Button { follow.toggle(); if follow { reader.scrollTo("chat-bottom", anchor: .bottom) } } label: {
                            Label(follow ? "暫停跟隨" : "回到最新", systemImage: follow ? "pause" : "arrow.down")
                        }.buttonStyle(.bordered).padding(18)
                    }
                }
            }
            composer
        }
        .sheet(isPresented: $options) {
            VStack(alignment: .leading, spacing: 18) {
                Text("對話設定").font(.title2.weight(.semibold))
                if !optionsLoaded { ProgressView("讀取已保存設定…") }
                Group {
                FormField(title: "對話名稱", text: $title)
                Text("系統指示").font(.headline)
                TextEditor(text: $system).frame(height: 150).border(Color.secondary.opacity(0.15))
                FormField(title: "回應 Token 上限（1–32768）", text: $maxTokens)
                Toggle("串流回應", isOn: $streaming)
                DisclosureGroup("工具定義") { JSONEditor(title: "Function tools", text: $toolsJSON) }
                }.disabled(!optionsLoaded)
                HStack {
                    Button("取消") { options = false }.keyboardShortcut(.cancelAction)
                    Spacer()
                    ActionButton(title: "儲存", prominent: true) {
                        guard let api = store.api, let id = store.sessionID, let tokens = Int(maxTokens), (1...32768).contains(tokens) else { throw AtlasError.invalidJSON }
                        guard let tools = try? JSONValue.parse(toolsJSON), case .array = tools else { throw AtlasError.invalidJSON }
                        let submitted = (system, maxTokens, streaming, title, toolsJSON)
                        _ = try await api.call("/sessions/settings", method: "POST", body: .object(["session_id": .string(id), "system": .string(system), "max_tokens": .number(Double(tokens)), "streaming": .bool(streaming)]))
                        store.stagedTools[id] = tools
                        if let session = store.selectedSession, !submitted.3.isEmpty && submitted.3 != session["title"].string {
                            _ = try await store.mutate("/sessions/update", body: .object(["session_id": .string(id), "revision": session["revision"], "title": .string(submitted.3)]))
                        }
                        if submitted == (system, maxTokens, streaming, title, toolsJSON) { options = false }
                    }.disabled(!optionsLoaded)
                }
            }.padding(28).frame(width: 560)
            .task { optionsLoaded = false; await loadOptions() }
        }
    }
    private var composer: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                if store.sessionID != nil { Text(store.selectedScope?.modelID ?? "對話模型").font(.callout.weight(.semibold)).lineLimit(1) }
                else { Picker("模型", selection: $store.scopeID) {
                    Text("選擇模型").tag("")
                    ForEach(store.activeModels, id: \.self) { value in
                        Text(value["model_id"].string + " · " + store.scopeTitle(value) + " · " + store.operationName(value)).tag(ModelScope(value).id)
                    }
                }.labelsHidden().disabled(store.generating) }
                if store.sessionID != nil { Button("新對話") { store.newConversation() }.disabled(store.generating) }
                Spacer(minLength: 0)
                if let model = store.selectedModel { SmallBadge(text: store.operationName(model), color: atlasAccent) }
            }
            if let model = store.selectedModel {
                let operation = store.operationName(model)
                if operation == "RERANK" { Text("待排序文件（每行一份）").font(.caption); TextEditor(text: $store.mediaDocuments).frame(height: 70) }
                if ["AUDIO_SPEECH", "GEMINI_SPEECH"].contains(operation) {
                    HStack {
                        TextField("聲音名稱", text: $store.mediaVoice).textFieldStyle(.roundedBorder)
                        if operation == "GEMINI_SPEECH" { Picker("協定", selection: $store.speechDialect) { Text("CURRENT").tag("CURRENT"); Text("LEGACY").tag("LEGACY") }.frame(width: 210) }
                    }
                }
                if operation == "IMAGES_GENERATION" { Picker("圖片大小", selection: $store.imageSize) { ForEach(["256x256", "512x512", "1024x1024", "1024x1536", "1536x1024", "auto"], id: \.self) { Text($0).tag($0) } }.frame(width: 250) }
            }
            if !store.attachments.isEmpty {
                HStack {
                    ForEach(store.attachments, id: \.self) { file in
                        HStack { Image(systemName: "paperclip"); Text(file["filename"].string).lineLimit(1); Button { store.attachments.removeAll { $0 == file } } label: { Image(systemName: "xmark.circle.fill") }.buttonStyle(.plain) }
                            .font(.caption).padding(8).background(atlasAccent.opacity(0.08)).cornerRadius(8)
                    }
                }
            }
            TextEditor(text: $store.draft).font(.body).frame(minHeight: 68, maxHeight: 135).padding(4)
                .accessibilityLabel("訊息草稿").accessibilityIdentifier("chat-composer")
            HStack {
                Button { store.pickAttachments() } label: { Label("附件", systemImage: "paperclip") }.disabled(store.generating)
                Text("圖片、PDF、WAV、MP3 · 每份 4 MiB").font(.caption2).foregroundStyle(.secondary)
                Spacer()
                if store.generating { Button { Task { await store.cancel() } } label: { Label("停止", systemImage: "stop.fill") }.keyboardShortcut(.escape, modifiers: []) }
                else { Button { store.send() } label: { Label("傳送", systemImage: "arrow.up") }.buttonStyle(.borderedProminent).controlSize(.large).keyboardShortcut(.return, modifiers: .command).disabled(store.selectedScope == nil || store.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty).accessibilityIdentifier("chat-send") }
            }
        }.padding(18).background(atlasSurface).cornerRadius(18)
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.primary.opacity(0.08)))
            .padding(.horizontal, 24).padding(.bottom, 22).padding(.top, 10)
    }
    private func loadOptions() async {
        guard let api = store.api, let id = store.sessionID else { return }
        do {
            let value = try await api.get("/sessions/settings", query: [URLQueryItem(name: "session_id", value: id)])
            guard options && store.sessionID == id else { return }
            system = value["system"].string; maxTokens = value["max_tokens"].display; streaming = value["streaming"].boolean
            title = store.selectedSession?["title"].string ?? ""
            toolsJSON = (store.stagedTools[id] ?? value["tools"]).pretty
            if toolsJSON == "null" { toolsJSON = "[]" }
            optionsLoaded = true
        } catch { store.problem = error.localizedDescription }
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
                        Button("查看請求收據…") { store.settingsOpen = true }
                        if !["SUCCESS", "RUNNING", ""].contains(message["request_outcome"].string) { Button("重試這次請求") { store.send(retry: message["sample_id"].string) }.disabled(store.generating) }
                    }.menuStyle(.borderlessButton).frame(width: 55)
                }
            }
            Text((try? AttributedString(markdown: message["content"].string, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(message["content"].string)).textSelection(.enabled).lineSpacing(5).frame(maxWidth: .infinity, alignment: .leading)
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
    var body: some View {
        AtlasCard {
            Label("生成結果", systemImage: "sparkles").font(.headline)
            if !value["text"].string.isEmpty { Text(value["text"].string).textSelection(.enabled) }
            ForEach(value["data"].array, id: \.self) { item in
                if let data = Data(base64Encoded: item["b64_json"].string) {
                    if item["mime"].string.hasPrefix("image/"), let image = NSImage(data: data) { Image(nsImage: image).resizable().scaledToFit().frame(maxHeight: 520).cornerRadius(12) }
                    HStack {
                        if item["mime"].string.hasPrefix("audio/") { Button("播放／重播") { player = try? AVAudioPlayer(data: data); player?.play() } }
                        Button("儲存結果…") {
                            let panel = NSSavePanel(); panel.nameFieldStringValue = item["mime"].string.hasPrefix("image/") ? "ModelAtlas.png" : "ModelAtlas.wav"
                            if panel.runModal() == .OK, let url = panel.url { try? data.write(to: url, options: .atomic) }
                        }
                    }
                } else if !item["url"].string.isEmpty { Button("開啟供應商圖片") { openHTTPS(item["url"].string) } }
            }
            if !value["vectors"].array.isEmpty { DisclosureGroup("向量結果") { JSONTree(value: value["vectors"]) } }
            if !value["rankings"].array.isEmpty { JSONTree(value: value["rankings"]) }
            Button("複製文字") { copyText(value["text"].string) }.disabled(value["text"].string.isEmpty)
        }
    }
}
