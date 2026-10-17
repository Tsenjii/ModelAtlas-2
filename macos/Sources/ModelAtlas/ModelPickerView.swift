import AtlasCore
import SwiftUI

struct ModelPickerView: View {
    @ObservedObject var store: AtlasStore
    @Environment(\.dismiss) private var dismiss
    @State private var search = ""
    @State private var favorites = false
    @State private var providerID = ""
    @State private var cursor = ModelPickerCursor()
    @State private var scrollTarget: String?
    private var entryIDs: [String] { entries.map(\.id) }
    private var highlighted: ModelSearchEntry? { entries.first { $0.id == cursor.highlightedID } }
    private var entries: [ModelSearchEntry] {
        store.modelEntries(store.activeModels).filter {
            $0.matches(search) && (!favorites || $0.model["favorite"].boolean) && (providerID.isEmpty || $0.scope.providerID == providerID)
        }.sorted {
            if $0.model["favorite"].boolean != $1.model["favorite"].boolean { return $0.model["favorite"].boolean }
            return ($0.title.localizedLowercase, $0.id) < ($1.title.localizedLowercase, $1.id)
        }
    }
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("選擇模型").font(.title3.weight(.semibold))
                    Text(store.sessionID == nil ? "從已加入的連線中選擇。" : "選擇後開啟新對話；目前對話與草稿會保留。")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button("完成") { dismiss() }.keyboardShortcut(.cancelAction)
            }.padding(20)
            ModelPickerSearchField(text: $search, move: move, submit: chooseHighlighted, cancel: { dismiss() })
                .frame(height: 30)
                .padding(.horizontal, 20).padding(.bottom, 12)
            HStack {
                Picker("顯示", selection: $favorites) { Text("全部").tag(false); Text("收藏").tag(true) }
                    .pickerStyle(.segmented).frame(width: 160)
                Spacer()
                Picker("供應商", selection: $providerID) {
                    Text("所有供應商").tag("")
                    ForEach(store.providers) { Text($0["name"].string).tag($0["id"].string) }
                }.labelsHidden().frame(maxWidth: 210)
            }.padding(.horizontal, 20).padding(.bottom, 14)
            Divider()
            ScrollViewReader { reader in
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(entries) { entry in
                        Button { choose(entry) } label: {
                            HStack(spacing: 12) {
                                ModelMark(name: entry.title)
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack(spacing: 6) {
                                        Text(entry.title).font(.body.weight(.medium)).lineLimit(1)
                                        if entry.model["favorite"].boolean { Image(systemName: "star.fill").font(.caption2).foregroundStyle(.orange) }
                                    }
                                    Text(store.scopeTitle(entry.model)).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                                    Text(operationTitle(store.operationName(entry.model)) + " · " + entry.scope.endpoint)
                                        .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                                }
                                Spacer(minLength: 6)
                                if entry.id == store.scopeID { Image(systemName: "checkmark").font(.body.weight(.semibold)).foregroundStyle(atlasAccent) }
                            }.padding(.horizontal, 20).padding(.vertical, 13).frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(AtlasRowButtonStyle())
                            .background(cursor.highlightedID == entry.id ? atlasAccent.opacity(0.085) : Color.clear)
                            .accessibilityAddTraits(cursor.highlightedID == entry.id ? .isSelected : [])
                            .help(entry.scope.modelID + " · " + store.scopeTitle(entry.model) + " · " + entry.scope.endpoint)
                            .id(entry.id)
                        Divider().padding(.leading, 64)
                    }
                    if entries.isEmpty {
                        EmptyCard(icon: favorites ? "star" : "magnifyingglass", title: store.activeModels.isEmpty ? "尚未加入模型" : "沒有符合的連線",
                                  detail: store.activeModels.isEmpty ? "到 Models 的 Catalog 加入要使用的模型。" : "試試模型名稱、帳戶或群組，或清除篩選。",
                                  action: store.activeModels.isEmpty ? "前往 Models" : "清除篩選") {
                            if store.activeModels.isEmpty { store.workspace = .models; dismiss() }
                            else { search = ""; favorites = false; providerID = "" }
                        }.frame(minHeight: 250)
                    }
                }
            }
            .onChange(of: scrollTarget) { id in if let id { reader.scrollTo(id, anchor: .center) } }
            .onAppear {
                cursor.reconcile(entryIDs, preferredID: store.scopeID)
                if let id = cursor.highlightedID { reader.scrollTo(id, anchor: .center) }
            }
            }
            Divider()
            HStack {
                Text("\(entries.count) 個連線").foregroundStyle(.secondary)
                Spacer()
                Text("↑↓ 選擇 · ↵ 使用 · esc 關閉").foregroundStyle(.secondary)
                Button("使用模型", action: chooseHighlighted).buttonStyle(.borderedProminent)
                    .disabled(highlighted == nil || store.generating)
            }.font(.caption).padding(.horizontal, 20).padding(.vertical, 12)
        }.frame(width: 620, height: 550).background(atlasSurface)
            .onChange(of: entryIDs) { ids in cursor.reconcile(ids, preferredID: store.scopeID) }
    }
    private func choose(_ entry: ModelSearchEntry) { store.chooseModel(entry.model); dismiss() }
    private func chooseHighlighted() { if let highlighted { choose(highlighted) } }
    private func move(_ step: Int) { cursor.move(by: step, in: entryIDs); scrollTarget = cursor.highlightedID }
}

