import AppKit
import AtlasCore
import Combine
import Foundation
import UniformTypeIdentifiers

enum Workspace: String, CaseIterable, Identifiable {
    case playground = "Playground", providers = "Providers", models = "Models"
    var id: String { rawValue }
    var symbol: String { switch self { case .playground: return "bubble.left.and.bubble.right"; case .providers: return "server.rack"; case .models: return "square.stack.3d.up" } }
}

@MainActor final class AtlasStore: ObservableObject {
    @Published var workspace: Workspace = .playground
    @Published var sidebarVisible = true
    @Published var composerFocusRequest = 0
    @Published var ready = false
    @Published var booting = false
    @Published var closing = false
    @Published var additionalSheets = 0
    @Published var problem: String?
    @Published var notice: String?
    @Published var providers: [JSONValue] = []
    @Published var accounts: [JSONValue] = []
    @Published var credentials: [JSONValue] = []
    @Published var myModels: [JSONValue] = []
    @Published var sessions: [JSONValue] = []
    @Published var messages: [JSONValue] = []
    @Published var endpoints: [JSONValue] = []
    @Published var settings: JSONValue = .object([:])
    @Published var scopeID = ""
    @Published var sessionID: String?
    @Published var currentSession: JSONValue?
    @Published var draft = ""
    @Published var attachments: [JSONValue] = []
    @Published var generating = false
    @Published var streamingText = ""
    @Published var reasoning = ""
    @Published var mediaResult: JSONValue?
    @Published var mediaDraft = MediaDraft()
    @Published var messageOffset = 0
    @Published var messageHasMore = false
    @Published var toolResults: [String: String] = [:]
    var stagedTools: [String: JSONValue] = [:]
    private var resultDrafts: [String: [String: String]] = [:]
    @Published var sessionSearch = ""
    @Published var archiveView = false
    @Published var allSessionStates = false
    @Published var sessionOnlyCurrentScope = false
    @Published var sessionHasMore = false
    @Published var sessionsLoading = false
    @Published var sessionListProblem: String?
    @Published var chatSettings: ChatSettingsController?
    @Published var settingsOpen = false
    @Published var newProviderOpen = false
    @Published var detailProvider: JSONValue?
    @Published var detailModel: JSONValue?
    @Published var controlReview: ActionReviewController?
    @Published var assistantOpen = false
    @Published var modelPickerOpen = false
    @Published var chatReading = ChatReadingState()
    @Published var requestReceipt: String?
    @Published var attaching = false
    let engine: EngineController
    let checkins = AccountCheckinController()
    var api: AtlasClient?
    private var drafts: [String: String] = [:]
    private var loadRevision = 0
    private var historyRevision = 0
    private var searchRevision = 0
    private var operation: Task<Void, Never>?
    private var pendingText = "", pendingReasoning = ""
    private var activeMediaID: String?

    init(directory: URL? = nil) { engine = EngineController(directory: directory) }
    var hasPresentedSheet: Bool {
        additionalSheets > 0 || controlReview != nil || settingsOpen || newProviderOpen || detailProvider != nil || detailModel != nil || assistantOpen || modelPickerOpen || requestReceipt != nil || chatSettings != nil
    }
    var activeModels: [JSONValue] { myModels.filter { $0["state"].string == "ACTIVE" && $0["enabled"].boolean } }
    var selectedModel: JSONValue? { myModels.first { ModelScope($0).id == scopeID } }
    var selectedScope: ModelScope? { currentSession.map(ModelScope.init) ?? selectedModel.map(ModelScope.init) }
    var selectedSession: JSONValue? { currentSession }
    func providerName(_ id: String) -> String { providers.first { $0["id"].string == id }?["name"].string ?? id }
    func scopeTitle(_ value: JSONValue) -> String {
        let s = ModelScope(value)
        let key = credentials.first { $0["provider_id"].string == s.providerID && $0["account_id"].string == s.accountID && $0["id"].string == s.credentialID }
        let account = accounts.first { $0["provider_id"].string == s.providerID && $0["id"].string == s.accountID }
        return [providerName(s.providerID), account?["label"].string ?? s.accountID, key?["label"].string ?? s.credentialID, s.group.isEmpty ? "預設群組" : s.group].filter { !$0.isEmpty }.joined(separator: " · ")
    }
    func operationName(_ value: JSONValue) -> String {
        let id = value["endpoint"].string
        return endpoints.first { $0["provider_id"].string == value["provider_id"].string && $0["id"].string == id }?["operation"].string ?? id
    }
    func endpointLabel(_ value: JSONValue) -> String {
        let id = value["endpoint"].string
        if id == operationName(value) { return "標準端點" }
        guard let endpoint = endpoints.first(where: { $0["provider_id"] == value["provider_id"] && $0["id"].string == id }),
              let url = URL(string: endpoint["url"].string), !url.path.isEmpty else { return id }
        return url.path
    }
    func modelEntries(_ models: [JSONValue]) -> [ModelSearchEntry] {
        models.map { ModelSearchEntry(model: $0, context: scopeTitle($0), operation: operationTitle(operationName($0))) }
    }
    func bootstrap(smoke: Bool = false) async {
        guard !booting && !ready && !closing else { return }
        booting = true; problem = nil
        defer { booting = false }
        do {
            engine.onUnexpectedExit = { [weak self] in self?.ready = false; self?.problem = "本機引擎已停止。資料仍保存在原位置，請重新啟動工作區。" }
            let executable = Bundle.main.bundleURL.appendingPathComponent("Contents/MacOS/ModelAtlasEngine")
            guard FileManager.default.isExecutableFile(atPath: executable.path) else { throw AtlasError.engineUnavailable }
            let secrets: EngineSecrets
            if smoke { secrets = EngineSecrets(token: "native-ui-fixture-admin-token-only", masterKey: Data(repeating: 9, count: 32).base64EncodedString()) }
            else { secrets = try KeychainVault().loadOrCreate(databaseExists: FileManager.default.fileExists(atPath: engine.directory.appendingPathComponent("atlas.sqlite").path)) }
            api = try await engine.start(executable: executable, secrets: secrets)
            try await reload()
            ready = true
        } catch { problem = error.localizedDescription }
    }
    func reload() async throws {
        guard let api else { throw AtlasError.engineUnavailable }
        loadRevision += 1; let revision = loadRevision
        async let p = api.get("/providers")
        async let a = api.get("/accounts")
        async let c = api.get("/credentials")
        async let e = api.get("/endpoints")
        async let prefs = api.get("/settings")
        let values = try await (p, a, c, e, prefs)
        var members: [JSONValue] = [], offset = 0
        while true {
            let page = try await api.get("/my-models", query: [URLQueryItem(name: "all", value: "true"), URLQueryItem(name: "limit", value: "200"), URLQueryItem(name: "offset", value: String(offset))]).requiredArray()
            members += page
            if page.count < 200 { break }; offset += page.count
        }
        guard revision == loadRevision else { return }
        providers = try values.0.requiredArray(); accounts = try values.1.requiredArray(); credentials = try values.2.requiredArray()
        endpoints = try values.3.requiredArray(); settings = values.4; myModels = members
        checkins.refresh(using: api)
        if sessionID == nil && !activeModels.contains(where: { ModelScope($0).id == scopeID }) { scopeID = activeModels.first.map { ModelScope($0).id } ?? "" }
        try await loadSessions()
    }
    func refresh() async {
        do { try await reload(); problem = nil } catch { problem = error.localizedDescription }
    }
    func loadSessions(more: Bool = false) async throws {
        guard let api else { return }
        historyRevision += 1; let revision = historyRevision
        let offset = more ? sessions.count : 0
        var query = [URLQueryItem(name: "q", value: sessionSearch), URLQueryItem(name: "state", value: allSessionStates ? "ALL" : archiveView ? "ARCHIVED" : "ACTIVE"), URLQueryItem(name: "limit", value: "100"), URLQueryItem(name: "offset", value: String(offset))]
        if sessionOnlyCurrentScope { guard let selectedScope else { sessions = []; sessionHasMore = false; return }; query += selectedScope.query }
        let page = try await api.get("/sessions", query: query).requiredArray()
        try Task.checkCancellation()
        guard revision == historyRevision else { return }
        sessions = more ? sessions + page : page; sessionHasMore = page.count == 100
        if let updated = sessions.first(where: { $0["id"].string == sessionID }) { currentSession = updated }
        else if let id = sessionID {
            let exact = try await api.get("/sessions", query: [URLQueryItem(name: "session_id", value: id), URLQueryItem(name: "state", value: "ALL")]).requiredArray()
            if sessionID == id, let found = exact.first { currentSession = found }
        }
    }
    func searchSessions() async {
        searchRevision += 1; let revision = searchRevision
        sessionsLoading = true; sessionListProblem = nil
        defer { if revision == searchRevision { sessionsLoading = false } }
        do {
            try await Task.sleep(nanoseconds: 250_000_000)
            try await loadSessions()
        } catch is CancellationError { }
        catch {
            if revision == searchRevision && !Task.isCancelled { sessionListProblem = error.localizedDescription }
        }
    }
    func selectSession(_ session: JSONValue) async {
        guard !generating else { return }
        if let sessionID { drafts[sessionID] = draft } else { drafts["new"] = draft }
        resultDrafts[sessionID ?? "new"] = toolResults
        let id = session["id"].string
        chatReading.reset()
        sessionID = id; currentSession = session; scopeID = ModelScope(session).id; draft = drafts[id] ?? ""
        toolResults = resultDrafts[id] ?? [:]
        attachments = []; mediaResult = nil; streamingText = ""; reasoning = ""
        await loadMessages()
    }
    func loadMessages(older: Bool = false) async {
        guard let api, let id = sessionID else { messages = []; return }
        let offset = older ? messageOffset + messages.count : 0
        do {
            let result = try await api.get("/messages", query: [URLQueryItem(name: "session_id", value: id),
                URLQueryItem(name: "limit", value: "100"), URLQueryItem(name: "offset", value: String(offset))]).requiredArray()
            guard sessionID == id else { return }
            messages = older ? result + messages : result
            messageHasMore = result.count == 100
        } catch { problem = error.localizedDescription }
    }
    func openChatSettings() {
        guard !generating, !closing, let session = selectedSession else { return }
        chatSettings = ChatSettingsController(session: session)
    }
    func focusComposer() {
        guard ready, !closing, !hasPresentedSheet else { return }
        workspace = .playground
        composerFocusRequest += 1
    }
    func newConversation() {
        guard !generating else { return }
        if let sessionID { drafts[sessionID] = draft }
        resultDrafts[sessionID ?? "new"] = toolResults
        chatReading.reset()
        sessionID = nil; currentSession = nil; messages = []; draft = drafts["new"] ?? ""; attachments = []
        toolResults = [:]
        mediaResult = nil; streamingText = ""; reasoning = ""; messageHasMore = false; messageOffset = 0
        workspace = .playground
    }
    func chooseModel(_ value: JSONValue) {
        let id = ModelScope(value).id
        guard !generating, !closing, activeModels.contains(where: { ModelScope($0).id == id }) else { return }
        // Switching an unsent model must keep its draft and attachments. Existing
        // conversations keep their immutable scope and open a separate draft.
        if sessionID != nil { newConversation() }
        scopeID = id; detailModel = nil; modelPickerOpen = false; workspace = .playground
    }
    func mutate(_ path: String, body: JSONValue, method: String = "POST") async throws -> JSONValue {
        guard let api, !closing else { throw AtlasError.engineUnavailable }
        let value = try await api.call(path, method: method, body: body)
        do { try await reload() } catch { problem = "已保存，但列表暫時無法更新。請重新整理。" }
        return value
    }
    var canSend: Bool {
        guard selectedScope != nil else { return false }
        return mediaDraft.hasInput(operation: selectedModel.map(operationName) ?? "", prompt: draft, files: attachments)
    }
    func send(retry: String? = nil, toolContinuation: Bool = false) {
        guard let api, !closing, !generating, !attaching, let scope = selectedScope, retry != nil || toolContinuation || canSend else { return }
        let prompt = draft, files = attachments, beforeIDs = Set(messages.map { $0["id"].string })
        let requestFiles = files.map(LocalAttachment.requestValue)
        let options = mediaDraft
        chatReading.resume()
        generating = true; problem = nil; mediaResult = nil; pendingText = ""; pendingReasoning = ""
        streamingText = ""; reasoning = ""
        let submittedTools = stagedTools[sessionID ?? "new"]
        let submittedResults = pendingToolCalls.map { call in JSONValue.object(["call_id": call["id"], "content": .string(toolResults[call["id"].string] ?? "")]) }
        if retry == nil && !toolContinuation { draft = ""; attachments = []; drafts[sessionID ?? "new"] = "" }
        let op = selectedModel.map(operationName) ?? scope.endpoint
        operation = Task {
            let ticker = Task { @MainActor in
                while !Task.isCancelled { self.flush(); try? await Task.sleep(nanoseconds: 40_000_000) }
            }
            defer { ticker.cancel(); flush(); generating = false; activeMediaID = nil; operation = nil; streamingText = ""; reasoning = "" }
            do {
                if MediaDraft.operations.contains(op) {
                    let id = UUID().uuidString.lowercased(); activeMediaID = id
                    let body = try options.request(scope: scope, operation: op, prompt: prompt, files: requestFiles, requestID: id)
                    mediaResult = try await api.call("/infer/media", method: "POST", body: body)
                } else {
                    if sessionID == nil {
                        let created = try await api.call("/sessions", method: "POST", body: scope.json)
                        sessionID = created["id"].string
                        currentSession = created
                    }
                    guard let id = sessionID else { throw AtlasError.invalidResponse }
                    var body: JSONValue = .object(["session_id": .string(id)])
                    if let retry { body["retry_id"] = .string(retry) }
                    else if toolContinuation { body["tool_results"] = .array(submittedResults) }
                    else { body["prompt"] = .string(prompt); if !requestFiles.isEmpty { body["attachments"] = .array(requestFiles) } }
                    if let submittedTools { body["tools"] = submittedTools; stagedTools[id] = submittedTools }
                    if retry == nil && !toolContinuation { messages.append(.object(["id": .string("pending-user"), "role": .string("user"), "content": .string(prompt)])) }
                    try await api.stream("/infer", body: body) { event in
                        if event.name == "start" && toolContinuation { for result in submittedResults { self.toolResults.removeValue(forKey: result["call_id"].string) } }
                        if event.name == "delta" { self.pendingText += event.value["text"].string }
                        if event.name == "reasoning" { self.pendingReasoning += event.value["text"].string }
                        if event.name == "error" { self.problem = "請求結束：" + event.value["category"].string }
                    }
                    flush(); await loadMessages()
                }
                try await reload()
            } catch {
                if !Task.isCancelled { problem = error.localizedDescription }
                await loadMessages()
                if retry == nil && !toolContinuation && draft.isEmpty && !messages.contains(where: { $0["role"].string == "user" && $0["content"].string == prompt && !beforeIDs.contains($0["id"].string) }) { draft = prompt; attachments = files }
            }
        }
    }
    private func flush() {
        if !pendingText.isEmpty { streamingText += pendingText; pendingText = "" }
        if !pendingReasoning.isEmpty { reasoning += pendingReasoning; pendingReasoning = "" }
    }
    var pendingToolCalls: [JSONValue] {
        ConversationTools.pending(in: messages)
    }
    func cancel() async {
        guard let api, generating else { return }
        do {
            let body: JSONValue = activeMediaID.map { .object(["request_id": .string($0)]) } ?? .object(["session_id": .string(sessionID ?? "")])
            _ = try await api.call("/infer/cancel", method: "POST", body: body)
        } catch { problem = error.localizedDescription }
    }
    func shutdown() async { closing = true; operation?.cancel(); checkins.cancel(); await engine.stop(); ready = false }

    func pickAttachments() {
        guard ready, !closing, !generating, !attaching, !hasPresentedSheet else { return }
        let panel = NSOpenPanel(); panel.allowsMultipleSelection = true
        panel.allowedContentTypes = ["png", "jpg", "jpeg", "webp", "pdf", "wav", "mp3"].compactMap { UTType(filenameExtension: $0) }
        guard panel.runModal() == .OK else { return }
        Task { await addAttachments(panel.urls) }
    }
    func addAttachments(_ urls: [URL]) async {
        guard !generating, !attaching, !closing else { return }
        let session = sessionID, scope = selectedScope, before = attachments
        attaching = true
        defer { attaching = false }
        do {
            let result = try await Task.detached(priority: .userInitiated) { try LocalAttachment.adding(urls, to: before) }.value
            guard session == sessionID, scope == selectedScope, !generating, attachments == before else { return }
            attachments = result
        } catch { if session == sessionID, scope == selectedScope { problem = error.localizedDescription } }
    }
    func observeChatScroll(distance: Double, userScroll: Bool) {
        var next = chatReading; next.observe(distance: distance, userScroll: userScroll)
        if next != chatReading { chatReading = next }
    }
    func export(_ path: String, query: [URLQueryItem] = [], filename: String) async {
        guard let api else { return }
        let panel = NSSavePanel(); panel.nameFieldStringValue = filename
        guard panel.runModal() == .OK, let destination = panel.url else { return }
        do { try await api.download(path, query: query, to: destination); notice = "已匯出 \(destination.lastPathComponent)" }
        catch { problem = error.localizedDescription }
    }
}
