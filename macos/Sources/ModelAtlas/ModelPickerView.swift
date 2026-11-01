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
    private var hasFilters: Bool { !search.isEmpty || favorites || !providerID.isEmpty }
    private var availableProviders: [JSONValue] {
        let ids = Set(store.activeModels.map { $0["provider_id"].string })
        return store.providers.filter { ids.contains($0["id"].string) }
            .sorted { ($0["name"].string, $0.id) < ($1["name"].string, $1.id) }
    }
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
            header
            ModelPickerSearchField(text: $search, move: move, submit: chooseHighlighted, cancel: { dismiss() })
                .frame(height: 32).padding(.horizontal, 20).padding(.bottom, 12)
            filters
            Divider()
            results
            Divider()
            footer
        }.frame(width: 620, height: 550).background(atlasCanvas)
            .onChange(of: entryIDs) { ids in cursor.reconcile(ids, preferredID: store.scopeID) }
    }
    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text("選擇模型").font(.system(size: 17, weight: .semibold))
                Text(store.sessionID == nil ? "從已加入的連線中選擇。" : "選擇後開啟新對話；目前對話與草稿會保留。")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Button { dismiss() } label: { Image(systemName: "xmark").foregroundStyle(.secondary) }.buttonStyle(AtlasIconButtonStyle()).keyboardShortcut(.cancelAction).accessibilityLabel("關閉模型選擇")
        }.padding(20)
    }
    private var filters: some View {
        HStack {
            HStack(spacing: 3) {
                AtlasFilterChip(title: "全部模型", selected: !favorites) { favorites = false }
                AtlasFilterChip(title: "收藏", icon: "star", selected: favorites) { favorites = true }
            }
            Spacer()
            Picker("供應商", selection: $providerID) {
                Text("所有供應商").tag("")
                ForEach(availableProviders) { provider in
                    Text(providerLabel(provider)).tag(provider["id"].string)
                }
            }.labelsHidden().frame(maxWidth: 210)
        }.padding(.horizontal, 20).padding(.bottom, 14)
    }
    private var results: some View {
        ScrollViewReader { reader in
            ScrollView {
                LazyVStack(spacing: 3) {
                    ForEach(entries) { entry in
                        ModelPickerRow(store: store, entry: entry, highlighted: cursor.highlightedID == entry.id) { choose(entry) }
                            .id(entry.id)
                    }
                    if entries.isEmpty { emptyResults }
                }.padding(8)
            }
            .onChange(of: scrollTarget) { id in if let id { reader.scrollTo(id, anchor: .center) } }
            .onAppear {
                cursor.reconcile(entryIDs, preferredID: store.scopeID)
                if let id = cursor.highlightedID { reader.scrollTo(id, anchor: .center) }
            }
        }
    }
    private var emptyResults: some View {
        EmptyCard(icon: favorites ? "star" : "magnifyingglass", title: store.activeModels.isEmpty ? "尚未加入模型" : "沒有符合的連線",
                  detail: store.activeModels.isEmpty ? "到 Models 的 Catalog 加入要使用的模型。" : "試試模型名稱、帳戶或群組，或清除篩選。",
                  action: store.activeModels.isEmpty ? "前往 Models" : "清除篩選") {
            if store.activeModels.isEmpty { store.workspace = .models; dismiss() }
            else { resetFilters() }
        }.frame(minHeight: 250)
    }
    private var footer: some View {
        HStack {
            Text("\(entries.count) / \(store.activeModels.count) 個連線").foregroundStyle(.secondary)
            if hasFilters { Button("清除篩選", action: resetFilters).accessibilityIdentifier("model-picker-reset") }
            Spacer()
            Text("↑↓ 選擇 · ↵ 使用 · esc 關閉").foregroundStyle(.secondary)
            Button("使用模型", action: chooseHighlighted).buttonStyle(AtlasActionStyle(prominent: true))
                .disabled(highlighted == nil || store.generating)
        }.font(.caption).padding(.horizontal, 20).padding(.vertical, 12)
    }
    private func resetFilters() { search = ""; favorites = false; providerID = "" }
    private func providerLabel(_ provider: JSONValue) -> String {
        let name = provider["name"].string
        return availableProviders.filter { $0["name"].string == name }.count > 1 ? name + " · " + provider.id : name
    }
    private func choose(_ entry: ModelSearchEntry) {
        guard !store.generating, !store.closing,
              store.activeModels.contains(where: { ModelScope($0).id == entry.id }) else { return }
        store.chooseModel(entry.model); dismiss()
    }
    private func chooseHighlighted() { if let highlighted { choose(highlighted) } }
    private func move(_ step: Int) { cursor.move(by: step, in: entryIDs); scrollTarget = cursor.highlightedID }
}


private struct ModelPickerRow: View {
    @ObservedObject var store: AtlasStore
    let entry: ModelSearchEntry
    let highlighted: Bool
    let choose: () -> Void
    var body: some View {
        Button(action: choose) {
            HStack(spacing: 12) {
                ModelMark(name: entry.title)
                identity
                Spacer(minLength: 6)
                if entry.id == store.scopeID {
                    Image(systemName: "checkmark").font(.body.weight(.semibold)).foregroundStyle(atlasTextAccent)
                }
            }.padding(.horizontal, 12).padding(.vertical, 12).frame(maxWidth: .infinity, alignment: .leading)
        }.buttonStyle(AtlasRowButtonStyle())
            .disabled(store.generating || store.closing)
            .background(highlighted ? atlasAccent.opacity(0.085) : Color.clear).cornerRadius(8)
            .accessibilityAddTraits(highlighted ? .isSelected : [])
            .help(entry.scope.modelID + " · " + store.scopeTitle(entry.model) + " · " + entry.scope.endpoint)
    }
    private var identity: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Text(entry.title).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                if entry.model["favorite"].boolean { Image(systemName: "star.fill").font(.caption2).foregroundStyle(.orange) }
            }
            Text(store.scopeTitle(entry.model)).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(2)
            Text(operationTitle(store.operationName(entry.model)) + " · " + store.endpointLabel(entry.model))
                .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
        }
    }
}
