import AtlasCore
import SwiftUI

struct ModelActionChange: Identifiable {
    let scope: ModelScope
    let before: JSONValue, after: JSONValue
    var id: String { scope.id }
}

@MainActor final class ActionReviewController: ObservableObject, Identifiable {
    let id = UUID()
    let api: AtlasClient
    @Published var value: JSONValue
    @Published var busy = false
    @Published var problem: String?
    var isBatch: Bool { value["kind"].string == "MODEL_BATCH" || !value["before"]["items"].array.isEmpty }
    var batchChanges: [ModelActionChange] {
        let after = value["after"]["items"].array
        return value["before"]["items"].array.compactMap { item in
            let scope = ModelScope(item["selection"])
            guard let result = after.first(where: { ModelScope($0["selection"]) == scope }) else { return nil }
            return ModelActionChange(scope: scope, before: item["value"], after: result["value"])
        }
    }
    var batchComplete: Bool {
        let rows = batchChanges, count = value["before"]["items"].array.count
        return (2...100).contains(count) && rows.count == count && value["after"]["items"].array.count == count && Set(rows.map(\.id)).count == count && rows.allSatisfy { ["ACTIVE", "HIDDEN", "ARCHIVED"].contains($0.before["state"].string) && ["ACTIVE", "HIDDEN", "ARCHIVED"].contains($0.after["state"].string) }
    }
    init(api: AtlasClient, value: JSONValue) { self.api = api; self.value = value }
    func apply(undo: Bool = false) async {
        guard !busy, !isBatch || batchComplete else { return }; busy = true; problem = nil
        defer { busy = false }
        do {
            let result = try await api.call(undo ? "/assistant/actions/undo" : "/assistant/actions/confirm", method: "POST", body: .object(["id": value["id"], "confirm": .bool(true)]))
            // The API returns the persisted action, including its new state.
            for (key, field) in result.fields { value[key] = field }
        } catch { problem = error.localizedDescription }
    }
}

struct ActionReviewView: View {
    @ObservedObject var store: AtlasStore
    @ObservedObject var model: ActionReviewController
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack { Label("檢查變更", systemImage: "checklist").font(.title2.weight(.semibold)); Spacer(); Button("關閉") { dismiss() }.disabled(model.busy).keyboardShortcut(.cancelAction) }
            SmallBadge(text: ["PENDING":"尚未套用", "CONFIRMED":"已套用，可復原", "UNDONE":"已復原"][model.value["state"].string] ?? model.value["state"].string, color: atlasAccent)
            if let problem = model.problem { Text(problem).foregroundStyle(.red) }
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if model.isBatch {
                        batchSummary
                        DisclosureGroup("完整變更快照") {
                            JSONTree(value: model.value["before"])
                            Divider().padding(.vertical, 8)
                            JSONTree(value: model.value["after"])
                        }.font(.caption).padding(.horizontal, 4)
                    } else {
                    AtlasCard { Text("變更前").font(.headline); JSONTree(value: model.value["before"]) }
                    AtlasCard { Text("變更後").font(.headline); JSONTree(value: model.value["after"]) }
                    }
                }
            }
            HStack {
                Text("套用前會重新檢查資料版本。隱藏與封存會保留歷史。").font(.caption).foregroundStyle(.secondary)
                Spacer()
                if model.busy { ProgressView().controlSize(.small) }
                if model.value["state"].string == "PENDING" { Button("確認套用") { Task { await model.apply(); await store.refresh() } }.buttonStyle(AtlasActionStyle(prominent: true)).disabled(model.busy || (model.isBatch && !model.batchComplete)) }
                if model.value["state"].string == "CONFIRMED" { Button("復原這項變更") { Task { await model.apply(undo: true); await store.refresh() } }.disabled(model.busy) }
            }
        }.padding(24).frame(width: 760, height: 660).background(atlasCanvas)
    }
    private var batchSummary: some View {
        let changes = model.batchChanges
        return AtlasCard {
            HStack { Text("將更新 \(changes.count) 個模型連線").font(.headline); Spacer(); Text("變更前 → 變更後").font(.caption).foregroundStyle(.secondary) }
            if !model.batchComplete { Text("變更資料不完整，請重新預覽後再套用。").foregroundStyle(.red) }
            ForEach(changes) { change in
                ModelActionRow(store: store, change: change)
                if change.id != changes.last?.id { Divider() }
            }
        }
    }
}

private struct ModelActionRow: View {
    @ObservedObject var store: AtlasStore
    let change: ModelActionChange
    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(alignment: .top, spacing: 14) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(modelTitle(change.scope)).font(.system(size: 13, weight: .semibold))
                    Text(store.scopeTitle(change.scope.json)).font(.caption).foregroundStyle(.secondary)
                    Text(change.scope.endpoint).font(.caption2).foregroundStyle(.tertiary)
                }.frame(maxWidth: .infinity, alignment: .leading)
                HStack(spacing: 9) {
                    SmallBadge(text: stateTitle(change.before["state"].string))
                    Image(systemName: "arrow.right").font(.caption).foregroundStyle(.tertiary)
                    SmallBadge(text: stateTitle(change.after["state"].string), color: atlasAccent)
                }
            }
            ForEach(["favorite", "watchlist"], id: \.self) { key in
                if change.before[key] != change.after[key] {
                    Text((key == "favorite" ? "收藏" : "追蹤") + "：" + (change.before[key].boolean ? "開啟" : "關閉") + " → " + (change.after[key].boolean ? "開啟" : "關閉")).font(.caption).foregroundStyle(.secondary)
                }
            }
        }.padding(.vertical, 7)
    }
    private func modelTitle(_ scope: ModelScope) -> String {
        store.myModels.first(where: { ModelScope($0) == scope }).map { ModelSearchEntry(model: $0, context: "", operation: "").title } ?? scope.modelID
    }
    private func stateTitle(_ state: String) -> String { ["ACTIVE":"已加入", "HIDDEN":"已隱藏", "ARCHIVED":"已封存"][state] ?? state }
}

struct NotesEditor: View {
    @ObservedObject var store: AtlasStore
    let subject: JSONValue
    let initial: JSONValue
    @State private var notes = ""
    @State private var tags = ""
    @State private var review: ActionReviewController?
    var body: some View {
        AtlasCard {
            Text("備註與標籤").font(.headline)
            TextEditor(text: $notes).frame(height: 80).accessibilityLabel("備註")
            FormField(title: "標籤（逗號分隔）", text: $tags)
            ActionButton(title: "預覽備註變更") {
                guard let api = store.api else { return }
                var body = subject; body["kind"] = .string("NOTES"); body["patch"] = .object(["notes": .string(notes), "tags": .array(tags.split(separator: ",").map { .string($0.trimmingCharacters(in: .whitespacesAndNewlines)) }.filter { $0 != .string("") })])
                review = ActionReviewController(api: api, value: try await api.call("/assistant/actions/preview", method: "POST", body: body))
            }
        }.task { notes = initial["notes"].string; tags = initial["tags"].array.map(\.string).joined(separator: ", ") }
        .sheet(item: $review) { ActionReviewView(store: store, model: $0).atlasModal(store) }
    }
}
