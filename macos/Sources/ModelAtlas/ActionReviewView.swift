import AtlasCore
import SwiftUI

@MainActor final class ActionReviewController: ObservableObject, Identifiable {
    let id = UUID()
    let api: AtlasClient
    @Published var value: JSONValue
    @Published var busy = false
    @Published var problem: String?
    init(api: AtlasClient, value: JSONValue) { self.api = api; self.value = value }
    func apply(undo: Bool = false) async {
        guard !busy else { return }; busy = true; problem = nil
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
                    AtlasCard { Text("變更前").font(.headline); JSONTree(value: model.value["before"]) }
                    AtlasCard { Text("變更後").font(.headline); JSONTree(value: model.value["after"]) }
                }
            }
            HStack {
                Text("套用前會重新檢查資料版本。隱藏與封存會保留歷史。").font(.caption).foregroundStyle(.secondary)
                Spacer()
                if model.busy { ProgressView().controlSize(.small) }
                if model.value["state"].string == "PENDING" { Button("確認套用") { Task { await model.apply(); await store.refresh() } }.buttonStyle(.borderedProminent).disabled(model.busy) }
                if model.value["state"].string == "CONFIRMED" { Button("復原這項變更") { Task { await model.apply(undo: true); await store.refresh() } }.disabled(model.busy) }
            }
        }.padding(24).frame(width: 760, height: 660)
    }
}

struct NotesEditor: View {
    @ObservedObject var store: AtlasStore
    let subject: JSONValue
    let initial: JSONValue
    @State private var notes = "", tags = ""
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
