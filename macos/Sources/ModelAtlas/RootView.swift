import AppKit
import AtlasCore
import SwiftUI

struct RootView: View {
    @ObservedObject var store: AtlasStore
    @AppStorage("appearance") private var appearance = "system"
    var body: some View {
        HSplitView {
            sidebar.frame(minWidth: 205, idealWidth: 218, maxWidth: 260)
            VStack(spacing: 0) {
                if let problem = store.problem {
                    HStack(alignment: .top) {
                        Image(systemName: "exclamationmark.circle.fill").foregroundStyle(.red)
                        Text(problem).textSelection(.enabled)
                        Spacer()
                        Button { store.problem = nil } label: { Image(systemName: "xmark") }.buttonStyle(.plain).help("關閉提示")
                    }.font(.callout).padding(14).background(Color.red.opacity(0.07))
                }
                if !store.ready {
                    if store.booting { VStack(spacing: 18) { ProgressView(); Text("正在準備你的工作區…").foregroundStyle(.secondary) }.frame(maxWidth: .infinity, maxHeight: .infinity) }
                    else { EmptyCard(icon: "externaldrive", title: "本機工作區", detail: "模型、對話與研究資料保存在這台 Mac。", action: "啟動") { Task { await store.bootstrap() } } }
                } else {
                    switch store.workspace {
                    case .playground: ChatView(store: store)
                    case .providers: ProvidersView(store: store)
                    case .models: ModelsView(store: store)
                    }
                }
            }.frame(minWidth: 600).background(atlasCanvas)
        }
        .frame(minWidth: 900, minHeight: 620)
        .tint(atlasAccent)
        .environment(\.openURL, OpenURLAction { url in
            guard url.scheme == "https", url.user == nil, url.password == nil else { return .discarded }
            NSWorkspace.shared.open(url); return .handled
        })
        .preferredColorScheme(appearance == "dark" ? .dark : appearance == "light" ? .light : nil)
        .toolbar {
            ToolbarItem(placement: .navigation) { Text(store.workspace.rawValue).font(.headline) }
            ToolbarItemGroup(placement: .automatic) {
                Button { Task { await store.refresh() } } label: { Image(systemName: "arrow.clockwise") }.help("重新整理（⌘R）").disabled(!store.ready)
                Button { store.assistantOpen = true } label: { Label("Assistant", systemImage: "sparkles") }.disabled(!store.ready)
                Button { store.settingsOpen = true } label: { Image(systemName: "gearshape") }.help("設定（⌘,）")
            }
        }
        .sheet(isPresented: $store.newProviderOpen) { AddProviderView(store: store) }
        .sheet(item: $store.detailProvider) { ProviderDetailView(store: store, provider: $0) }
        .sheet(item: $store.detailModel) { ModelDetailView(store: store, model: $0) }
        .sheet(isPresented: $store.settingsOpen) { SettingsView(store: store) }
        .sheet(isPresented: $store.assistantOpen) { AssistantView(store: store) }
        .sheet(isPresented: $store.modelPickerOpen) { ModelPickerView(store: store) }
        .sheet(isPresented: Binding(get: { store.requestReceipt != nil }, set: { if !$0 { store.requestReceipt = nil } })) {
            if let id = store.requestReceipt { RequestReceiptView(store: store, sampleID: id) }
        }
        .disabled(store.closing)
        .overlay {
            if store.closing { ProgressView("正在儲存並關閉本機工作區…").padding(24).background(.regularMaterial).cornerRadius(14) }
        }
    }
    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 10) {
                Image(systemName: "hexagon.fill").font(.system(size: 23)).foregroundStyle(atlasAccent)
                Text("ModelAtlas").font(.system(size: 17, weight: .semibold))
            }.padding(.horizontal, 12).padding(.top, 14).padding(.bottom, 4)
            VStack(spacing: 4) {
                ForEach(Workspace.allCases) { section in
                    Button { store.workspace = section } label: {
                        HStack {
                            Label(section.rawValue, systemImage: section.symbol).font(.body.weight(.medium))
                            Spacer(minLength: 4)
                            if section == .providers { Text("\(store.providers.count)").font(.caption.monospacedDigit()).foregroundStyle(.secondary) }
                            if section == .models { Text("\(store.activeModels.count)").font(.caption.monospacedDigit()).foregroundStyle(.secondary) }
                        }.frame(maxWidth: .infinity, minHeight: 38, alignment: .leading).padding(.horizontal, 12)
                            .background(store.workspace == section ? atlasAccent.opacity(0.11) : Color.clear).cornerRadius(8)
                            .foregroundStyle(store.workspace == section ? atlasAccent : Color.primary)
                    }.buttonStyle(.plain).accessibilityIdentifier("nav-" + section.rawValue)
                }
            }
            if store.ready {
                Button { store.modelPickerOpen = true } label: {
                    HStack { Label("選擇模型", systemImage: "magnifyingglass"); Spacer(); Text("⌘K").foregroundStyle(.secondary) }
                        .font(.caption).padding(.horizontal, 12).padding(.vertical, 9).contentShape(Rectangle())
                }.buttonStyle(.plain).foregroundStyle(.secondary).disabled(store.generating)
            }
            if store.workspace == .playground && store.ready { conversationList }
            Spacer(minLength: 0)
            HStack(spacing: 8) {
                Circle().fill(store.ready ? atlasAccent : Color.secondary).frame(width: 7, height: 7)
                Text(store.ready ? "本機工作區" : "尚未啟動").font(.caption).foregroundStyle(.secondary)
                Spacer()
            }.padding(.horizontal, 12).padding(.vertical, 10)
        }.padding(12).background(.ultraThinMaterial)
    }
    private var conversationList: some View {
        VStack(alignment: .leading, spacing: 10) {
            Divider().padding(.vertical, 4)
            HStack { Text("對話").font(.caption.weight(.semibold)).foregroundStyle(.secondary); Spacer(); Button { store.newConversation() } label: { Image(systemName: "square.and.pencil") }.buttonStyle(.plain).disabled(store.generating).help("新增對話（⌘N）") }.padding(.horizontal, 10)
            SearchField(placeholder: "搜尋對話", text: $store.sessionSearch)
            Toggle("已封存", isOn: $store.archiveView).font(.caption).toggleStyle(.checkbox)
            if store.sessionsLoading { ProgressView("搜尋中…").controlSize(.small).font(.caption) }
            if let problem = store.sessionListProblem {
                VStack(alignment: .leading, spacing: 8) {
                    Text(problem).font(.caption).foregroundStyle(.secondary)
                    Button("重試") { Task { await store.searchSessions() } }
                }.padding(.horizontal, 10)
            } else if !store.sessionsLoading && store.sessions.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text(store.sessionSearch.isEmpty ? (store.archiveView ? "尚無已封存對話" : "尚無對話") : "沒有符合的對話")
                        .font(.caption).foregroundStyle(.secondary)
                    if !store.sessionSearch.isEmpty { Button("清除搜尋") { store.sessionSearch = "" } }
                }.padding(10)
            }
            ScrollView {
                LazyVStack(spacing: 4) {
                    ForEach(store.sessions, id: \.self) { item in
                        Button { Task { await store.selectSession(item) } } label: {
                            VStack(alignment: .leading, spacing: 5) {
                                HStack { if item["pinned"].boolean { Image(systemName: "pin.fill").font(.caption2) }; Text(item["title"].string).lineLimit(1) }
                                Text(item["model_id"].string).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                            }.frame(maxWidth: .infinity, minHeight: 45, alignment: .leading).padding(10)
                                .background(store.sessionID == item["id"].string ? Color.primary.opacity(0.055) : Color.clear).cornerRadius(9)
                        }.buttonStyle(.plain).disabled(store.generating)
                    }
                    if store.sessionHasMore {
                        Button("載入更多對話") { Task {
                            do { try await store.loadSessions(more: true); store.sessionListProblem = nil }
                            catch { store.sessionListProblem = error.localizedDescription }
                        } }.disabled(store.sessionsLoading)
                    }
                }
            }
        }.task(id: "\(store.archiveView):\(store.sessionSearch)") { await store.searchSessions() }
    }
}

extension JSONValue: Identifiable {
    public var id: String { self["id"].string.isEmpty ? pretty : self["id"].string }
}
