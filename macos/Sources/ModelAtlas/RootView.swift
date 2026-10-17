import AppKit
import AtlasCore
import SwiftUI

struct RootView: View {
    @ObservedObject var store: AtlasStore
    @AppStorage("appearance") private var appearance = "system"
    var body: some View {
        HSplitView {
            sidebar.frame(minWidth: 212, idealWidth: 228, maxWidth: 260)
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
        .environment(\.openURL, OpenURLAction { url in
            guard url.scheme == "https", url.user == nil, url.password == nil else { return .discarded }
            NSWorkspace.shared.open(url); return .handled
        })
        .preferredColorScheme(appearance == "dark" ? .dark : appearance == "light" ? .light : nil)
        .toolbar {
            ToolbarItemGroup(placement: .automatic) {
                Button { Task { await store.refresh() } } label: { Image(systemName: "arrow.clockwise") }.help("重新整理（⌘R）").disabled(!store.ready)
                Button { store.assistantOpen = true } label: { Label("Assistant", systemImage: "sparkles") }.disabled(!store.ready)
                Button { store.settingsOpen = true } label: { Image(systemName: "gearshape") }.help("設定（⌘,）")
            }
        }
        .sheet(item: $store.controlReview) { ActionReviewView(store: store, model: $0) }
        .sheet(isPresented: $store.newProviderOpen) { AddProviderView(store: store) }
        .sheet(item: $store.detailProvider) { ProviderDetailView(store: store, provider: $0) }
        .sheet(item: $store.detailModel) { ModelDetailView(store: store, model: $0) }
        .sheet(item: $store.chatSettings) { ChatSettingsView(store: store, model: $0) }
        .sheet(isPresented: $store.settingsOpen) { SettingsView(store: store) }
        .sheet(isPresented: $store.assistantOpen) { AssistantView(store: store) }
        .sheet(isPresented: $store.modelPickerOpen) { ModelPickerView(store: store) }
        .sheet(isPresented: Binding(get: { store.requestReceipt != nil }, set: { if !$0 { store.requestReceipt = nil } })) {
            if let id = store.requestReceipt { RequestReceiptView(store: store, sampleID: id) }
        }
        .tint(atlasAccent)
        .onAppear { applyNativeAppearance(appearance) }
        .onChange(of: appearance) { applyNativeAppearance($0) }
        .disabled(store.closing)
        .overlay {
            if store.closing { ProgressView("正在儲存並關閉本機工作區…").padding(24).background(.regularMaterial).cornerRadius(14) }
        }
    }
    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Image(systemName: "square.stack.3d.up.fill").font(.system(size: 17, weight: .medium)).foregroundStyle(atlasTextAccent)
                    .frame(width: 32, height: 32).background(atlasAccent.opacity(0.08)).cornerRadius(10)
                VStack(alignment: .leading, spacing: 2) {
                    Text("ModelAtlas").font(.system(size: 15, weight: .semibold))
                    Text("你的 AI 工作區").font(.system(size: 10)).foregroundStyle(.secondary)
                }
            }.padding(.horizontal, 9).padding(.top, 12).padding(.bottom, 7)
            Text("工作區").font(.system(size: 10, weight: .medium)).foregroundStyle(.tertiary).padding(.horizontal, 12)
            VStack(spacing: 3) {
                ForEach(Workspace.allCases) { section in
                    Button { store.workspace = section } label: {
                        HStack(spacing: 10) {
                            Image(systemName: section.symbol).font(.system(size: 14, weight: .medium)).frame(width: 19)
                                .foregroundStyle(store.workspace == section ? atlasTextAccent : Color.secondary)
                            Text(section.rawValue).font(.system(size: 12, weight: store.workspace == section ? .semibold : .medium))
                            Spacer(minLength: 4)
                            if section == .providers { sidebarCount(store.providers.count) }
                            if section == .models { sidebarCount(store.activeModels.count) }
                        }.frame(maxWidth: .infinity, minHeight: 35, alignment: .leading).padding(.horizontal, 11)
                            .background(store.workspace == section ? atlasSurface.opacity(0.85) : Color.clear).cornerRadius(7)
                            .overlay(RoundedRectangle(cornerRadius: 7).stroke(store.workspace == section ? atlasHairline : Color.clear, lineWidth: 0.5))
                            .foregroundStyle(Color.primary)
                    }.buttonStyle(.plain).accessibilityIdentifier("nav-" + section.rawValue)
                }
            }
            if store.ready {
                Button { store.modelPickerOpen = true } label: {
                    HStack { Label("快速選擇模型", systemImage: "magnifyingglass"); Spacer(); Text("⌘K").font(.system(size: 10)).padding(3).background(Color.primary.opacity(0.035)).cornerRadius(4) }
                        .font(.system(size: 11)).padding(.horizontal, 11).padding(.vertical, 8).contentShape(Rectangle())
                }.buttonStyle(.plain).foregroundStyle(.secondary).disabled(store.generating)
            }
            if store.workspace == .playground && store.ready { conversationList }
            Spacer(minLength: 0)
            Divider()
            HStack(spacing: 8) {
                Image(systemName: "internaldrive").font(.system(size: 12)).foregroundStyle(.secondary)
                Text(store.ready ? "本機工作區" : "尚未啟動").font(.system(size: 11)).foregroundStyle(.secondary)
                Spacer()
                Circle().fill(store.ready ? atlasAccent : Color.secondary).frame(width: 5, height: 5)
            }.padding(.horizontal, 11).padding(.bottom, 9)
        }.padding(10).background(atlasSidebar)
    }
    @MainActor private func applyNativeAppearance(_ mode: String) {
        NSApp.appearance = mode == "system" ? nil : NSAppearance(named: mode == "dark" ? .darkAqua : .aqua)
    }
    private func sidebarCount(_ count: Int) -> some View {
        Text("\(count)").font(.system(size: 10).monospacedDigit()).foregroundStyle(.secondary)
            .padding(.horizontal, 5).padding(.vertical, 2).background(Color.primary.opacity(0.035)).cornerRadius(4)
    }
    private var conversationList: some View {
        VStack(alignment: .leading, spacing: 10) {
            Divider().padding(.vertical, 4)
            HStack {
                Text("最近對話").font(.system(size: 10, weight: .medium)).foregroundStyle(.secondary)
                Spacer()
                Menu {
                    Toggle("已封存", isOn: $store.archiveView).disabled(store.allSessionStates)
                    Toggle("全部狀態", isOn: $store.allSessionStates)
                    Toggle("僅目前模型連線", isOn: $store.sessionOnlyCurrentScope)
                } label: { Image(systemName: "line.3.horizontal.decrease") }.menuStyle(.borderlessButton).fixedSize().help("篩選對話")
                Button { store.newConversation() } label: { Image(systemName: "square.and.pencil") }.buttonStyle(.plain).disabled(store.generating).help("新增對話（⌘N）")
            }.padding(.horizontal, 10)
            SearchField(placeholder: "搜尋對話", text: $store.sessionSearch)
            if store.archiveView || store.allSessionStates || store.sessionOnlyCurrentScope {
                Text([store.allSessionStates ? "全部狀態" : store.archiveView ? "已封存" : "", store.sessionOnlyCurrentScope ? "目前連線" : ""].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.system(size: 10)).foregroundStyle(atlasTextAccent).padding(.horizontal, 10)
            }
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
                                HStack(spacing: 5) { if item["pinned"].boolean { Image(systemName: "pin.fill").font(.system(size: 9)).foregroundStyle(.secondary) }; Text(item["title"].string).font(.system(size: 12)).lineLimit(1) }
                                Text(item["model_id"].string).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                            }.frame(maxWidth: .infinity, minHeight: 36, alignment: .leading).padding(.horizontal, 11).padding(.vertical, 8)
                                .background(store.sessionID == item["id"].string ? Color.primary.opacity(0.055) : Color.clear).cornerRadius(7)
                        }.buttonStyle(AtlasRowButtonStyle()).disabled(store.generating)
                    }
                    if store.sessionHasMore {
                        Button("載入更多對話") { Task {
                            do { try await store.loadSessions(more: true); store.sessionListProblem = nil }
                            catch { store.sessionListProblem = error.localizedDescription }
                        } }.disabled(store.sessionsLoading)
                    }
                }
            }
        }.task(id: "\(store.archiveView):\(store.allSessionStates):\(store.sessionOnlyCurrentScope):\(store.sessionOnlyCurrentScope ? store.selectedScope?.id ?? "" : ""):\(store.sessionSearch)") { await store.searchSessions() }
    }
}

extension JSONValue: Identifiable {
    public var id: String { self["id"].string.isEmpty ? pretty : self["id"].string }
}
