import AtlasCore
import Foundation
import SwiftUI

@MainActor final class ResearchPolicyController: ObservableObject {
    let api: AtlasClient
    let kind: ResearchPolicyKind
    @Published private(set) var snapshot: ResearchPolicySnapshot?
    @Published private(set) var draft: ResearchPolicyDraft?
    @Published private(set) var credentialDrafts: [ResearchCredentialID: ResearchPolicyDraft] = [:]
    @Published private(set) var reading = false
    @Published private(set) var writing = false
    @Published private(set) var needsReload = true
    @Published private(set) var problem: String?
    @Published private(set) var notice: String?
    @Published private(set) var history: [JSONValue] = []
    @Published private(set) var historyNext = 0
    @Published private(set) var historyLoaded = false
    @Published private(set) var historyProblem: String?
    var activeCredential: ResearchCredentialID?
    private var scopeCursors: [ResearchCredentialID: String] = [:]
    private var seenCursors = Set<String>()

    init(api: AtlasClient, kind: ResearchPolicyKind) { self.api = api; self.kind = kind }
    var locked: Bool { reading || writing || needsReload || snapshot == nil }
    func currentDraft(_ id: ResearchCredentialID? = nil) -> ResearchPolicyDraft? { id.flatMap { credentialDrafts[$0] } ?? (id == nil ? draft : nil) }
    func canSave(_ id: ResearchCredentialID? = nil, reset: Bool = false) -> Bool {
        guard !locked, let snapshot, let value = currentDraft(id), value.revision == snapshot.revision else { return false }
        if let id { guard let row = snapshot.row(id) else { return false }; if reset { return row["override"].boolean } }
        return !reset && value.dirty
    }
    func beginCredential(_ id: ResearchCredentialID) {
        guard !locked, let snapshot, snapshot.row(id) != nil else { return }
        if credentialDrafts[id] == nil { credentialDrafts[id] = try? ResearchPolicyDraft(snapshot, credential: id) }
        activeCredential = id
    }
    func edit(_ key: String, text: String, credential: ResearchCredentialID? = nil) {
        guard !locked, var value = currentDraft(credential) else { return }
        value.edit(key, text: text); setDraft(value); notice = nil
    }
    func setEnabled(_ key: String, value: Bool, credential: ResearchCredentialID? = nil) {
        guard !locked, var draft = currentDraft(credential) else { return }
        draft.setEnabled(key, value); setDraft(draft); notice = nil
    }
    private func setDraft(_ value: ResearchPolicyDraft) {
        if let id = value.credential { credentialDrafts[id] = value } else { draft = value }
    }
    private func readSnapshot(cursor: String = "") async throws -> ResearchPolicySnapshot {
        let query = cursor.isEmpty ? [] : [URLQueryItem(name: "cursor", value: cursor)]
        let raw = try await api.get(kind.path, query: query)
        try Task.checkCancellation()
        return try ResearchPolicySnapshot(kind: kind, value: raw)
    }
    private func apply(_ value: ResearchPolicySnapshot, cursor: String, append: Bool = false,
                       discardGlobal: Bool = false, discardCredential: ResearchCredentialID? = nil) throws {
        if let discardCredential, value.row(discardCredential) == nil { throw DraftProblem("這份憑證不在原分頁，請重新讀取清單後再選擇。") }
        let global: ResearchPolicyDraft
        if discardGlobal || draft == nil { global = try ResearchPolicyDraft(value) }
        else { global = try draft!.refreshed(value) }
        var drafts = credentialDrafts
        for (id, old) in drafts where value.row(id) != nil {
            if id == discardCredential { drafts[id] = try ResearchPolicyDraft(value, credential: id) }
            else { drafts[id] = try old.refreshed(value) }
        }
        if !append { scopeCursors = [:]; seenCursors = cursor.isEmpty ? [] : [cursor] }
        for row in value.credentials {
            let id = try ResearchCredentialID(row)
            // Appending retains each scope's original page cursor.
            if !append || scopeCursors[id] == nil { scopeCursors[id] = cursor }
        }
        snapshot = value; draft = global; credentialDrafts = drafts
        needsReload = false; problem = nil
    }
    func reload(discardGlobal: Bool = false, credential: ResearchCredentialID? = nil, discardCredential: Bool = false) async {
        guard !reading, !writing else { return }
        reading = true; defer { reading = false }
        let id = credential ?? activeCredential
        let cursor = id.flatMap { scopeCursors[$0] } ?? ""
        do {
            let value = try await readSnapshot(cursor: cursor)
            if let id, value.row(id) == nil { throw DraftProblem("憑證清單已變更，請返回清單並重新讀取。") }
            try apply(value, cursor: cursor, discardGlobal: discardGlobal, discardCredential: discardCredential ? id : nil)
        } catch {
            needsReload = true
            if !Task.isCancelled { problem = "讀取失敗，保留上次資料與未保存草稿。請重新讀取後再操作。 " + error.localizedDescription }
        }
    }
    func moreCredentials() async {
        guard !locked, let current = snapshot, !current.nextCursor.isEmpty else { return }
        let cursor = current.nextCursor
        guard !seenCursors.contains(cursor) else { problem = "分頁游標重複，請重新讀取清單。"; return }
        reading = true; defer { reading = false }
        do {
            let page = try await readSnapshot(cursor: cursor)
            guard page.nextCursor.isEmpty || !seenCursors.contains(page.nextCursor) else { throw AtlasError.invalidResponse }
            let combined = try current.appending(page)
            let oldIDs = Set(current.credentials.compactMap { try? ResearchCredentialID($0) })
            try apply(combined, cursor: cursor, append: true)
            for row in page.credentials {
                let id = try ResearchCredentialID(row)
                if !oldIDs.contains(id) { scopeCursors[id] = cursor }
            }
            seenCursors.insert(cursor)
        } catch {
            if !Task.isCancelled { problem = "未能讀取下一頁，上次用量與草稿保留。請重新讀取清單。 " + error.localizedDescription }
        }
    }
    func save(credential id: ResearchCredentialID? = nil, reset: Bool = false) async {
        guard canSave(id, reset: reset), var value = currentDraft(id) else { return }
        let request: JSONValue
        do { request = try value.request(reset: reset) }
        catch { problem = error.localizedDescription; return }
        writing = true; problem = nil; notice = nil
        defer { writing = false }
        var acknowledged = false
        let cursor = id.flatMap { scopeCursors[$0] } ?? ""
        do {
            let receipt = try await api.call(kind.path, method: "POST", body: request)
            let revision = try ResearchPolicyValue.receipt(receipt, request: request, kind: kind)
            acknowledged = true
            value.acknowledged(revision); setDraft(value); needsReload = true
            let latest = try await readSnapshot(cursor: cursor)
            guard latest.revision >= revision else { throw AtlasError.invalidResponse }
            try apply(latest, cursor: cursor)
            notice = "已保存；今日用量與研究狀態保留。"
        } catch {
            needsReload = true
            if acknowledged { problem = "操作已保存，但畫面尚未更新。請重新讀取資料；不會再次送出保存。" }
            else if case AtlasError.http(409) = error { problem = "上限已在其他視窗變更，草稿保留。請重新讀取，再明確捨棄舊草稿後編輯。" }
            else { problem = "操作結果尚待確認，草稿保留。請先重新讀取資料，再決定下一步；不會自動重送。" }
        }
    }
    func loadHistory(more: Bool = false) async {
        guard kind != .execution, !reading, !writing, !more || historyNext > 0 else { return }
        reading = true; defer { reading = false }
        var query = [URLQueryItem(name: "history", value: "1")]
        let before = more ? historyNext : nil
        if let before { query.append(URLQueryItem(name: "before", value: String(before))) }
        do {
            let raw = try await api.get(kind.path, query: query)
            try Task.checkCancellation()
            let page = try ResearchPolicyHistory.page(kind: kind, value: raw, before: before)
            let rows = more ? history + page.rows : page.rows
            let versions = try rows.map { try ResearchPolicyValue.count($0, "revision", minimum: 1) }
            guard Set(versions).count == versions.count else { throw AtlasError.invalidResponse }
            history = rows; historyNext = page.next; historyLoaded = true; historyProblem = nil
        } catch { if !Task.isCancelled { historyProblem = "變更紀錄讀取失敗，保留上次紀錄。 " + error.localizedDescription } }
    }
}
