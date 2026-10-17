import AtlasCore
import SwiftUI

struct ChatTranscriptView: View {
    @ObservedObject var store: AtlasStore
    var body: some View {
        VStack(spacing: 0) {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 26) {
                            if store.messageHasMore { Button("載入更早的訊息") { store.chatReading.pause(); Task { await store.loadMessages(older: true) } }.frame(maxWidth: .infinity) }
                            ForEach(store.messages, id: \.id) { message in
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
                        }.padding(26).frame(maxWidth: 880).frame(maxWidth: .infinity)
                            .background(ChatScrollProbe(following: store.chatReading.following) { distance, user in store.observeChatScroll(distance: distance, userScroll: user) })
                    }
                    if !store.chatReading.following && !store.chatReading.atLatest {
                        HStack {
                            Spacer()
                            Button { store.chatReading.resume() } label: {
                                Label("回到最新", systemImage: "arrow.down")
                            }.buttonStyle(.bordered).accessibilityIdentifier("chat-return-latest")
                        }.padding(.horizontal, 26).padding(.vertical, 6)
                    }
        }
    }
}
