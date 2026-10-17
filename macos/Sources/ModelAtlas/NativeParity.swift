import AppKit
import AtlasCore
import SwiftUI

// Separate opt-in process/data directory. The original 25-frame acceptance
// remains intact; this phase exercises the newly added production workflows.
@MainActor enum NativeParity {
    static func run(store: AtlasStore, output: URL) async {
        var stage = "bootstrap"
        do {
            try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
            guard store.ready, let api = store.api else { throw AtlasError.engineUnavailable }
            let args = ProcessInfo.processInfo.arguments
            guard let at = args.firstIndex(of: "--fixture-url"), args.count > at + 1 else { throw AtlasError.invalidEndpoint }
            let fixture = args[at + 1]
            try JSONEncoder().encode(JSONValue.object(["app_pid": .number(Double(ProcessInfo.processInfo.processIdentifier)), "engine_pid": .number(Double(store.engine.process?.processIdentifier ?? 0))])).write(to: output.appendingPathComponent("measurement-pids.json"), options: .atomic)
            stage = "seed-exact-native-scopes"
            _ = try await store.mutate("/providers", body: .object(["id": .string("parity"), "name": .string("原生功能驗收 · 合成資料"), "base_url": .string(fixture + "/v1"), "auth_mode": .string("BEARER")]))
            for account in ["primary", "other"] {
                _ = try await store.mutate("/accounts", body: .object(["provider_id": .string("parity"), "id": .string(account), "label": .string(account == "primary" ? "Mac 工作區" : "另一個帳戶")]))
                _ = try await store.mutate("/credentials", body: .object(["provider_id": .string("parity"), "account_id": .string(account), "id": .string("same-key-id"), "label": .string("合成 Key"), "secret": .string("native-fixture-provider-key")]))
            }
            let chat = try await join(store, model: "native-fixture-chat", operation: "CHAT_COMPLETIONS")
            var foreign = ModelScope(chat).json; foreign["account_id"] = .string("other")
            _ = try await store.mutate("/my-models", body: foreign)
            var media: [String: JSONValue] = [:]
            let paths = ["AUDIO":"/audio/transcriptions", "AUDIO_SPEECH":"/audio/speech", "GEMINI_SPEECH":"/models/{model}:generateContent", "EMBEDDINGS":"/embeddings", "RERANK":"/rerank", "IMAGES_GENERATION":"/images/generations", "IMAGES_EDIT":"/images/edits"]
            for operation in MediaDraft.operations {
                stage = "seed-profile-" + operation
                let profileID = "qa-" + operation.lowercased()
                _ = try await store.mutate("/endpoints", body: .object(["provider_id": .string("parity"), "id": .string(profileID), "operation": .string(operation), "url": .string(fixture + "/v1" + paths[operation]!)]))
                media[operation] = try await join(store, model: "parity-" + operation.lowercased(), operation: operation, endpointID: profileID)
            }
            stage = "real-retained-chat-before-management"
            store.chooseModel(chat); store.draft = "原生功能驗收的對話"; store.send(); try await completed(store)
            try require(store.messages.count == 2 && store.problem == nil)
            guard let originalSession = store.selectedSession else { throw AtlasError.invalidResponse }
            let originalMessages = store.messages
            stage = "session-library-all-archived-and-exact-scope"
            store.workspace = .models
            let otherSession = try await api.call("/sessions", method: "POST", body: foreign)
            _ = try await api.call("/sessions/update", method: "POST", body: .object(["session_id": otherSession["id"], "revision": otherSession["revision"], "archived": .bool(true)]))
            store.allSessionStates = true; store.sessionOnlyCurrentScope = false; try await store.loadSessions()
            try require(store.sessions.contains { $0["id"] == otherSession["id"] })
            store.sessionOnlyCurrentScope = true; try await store.loadSessions()
            try require(!store.sessions.contains { $0["id"] == otherSession["id"] } && store.sessions.contains { $0["id"] == originalSession["id"] })
            store.sessionOnlyCurrentScope = false; store.allSessionStates = false; store.archiveView = true; try await store.loadSessions()
            try require(store.sessions.allSatisfy { $0["archived"].boolean } && store.sessionID == originalSession["id"].string && store.messages == originalMessages)
            store.archiveView = false; try await store.loadSessions(); store.workspace = .playground
            stage = "native-speech-current-legacy-dual"
            var options = MediaDraft(); options.voice = "Kore"
            for dialect in ["CURRENT", "LEGACY"] {
                options.dialect = dialect
                try await send(store, model: media["GEMINI_SPEECH"]!, options: options, prompt: "你好")
                try require(store.mediaResult?["data"].array.first?["mime"].string == "audio/wav")
            }
            options.dialect = "CURRENT"; options.dual = true
            options.turns = [SpeechLine(speaker: "Joe", text: "你好，我們一起研究模型。", style: "親切"), SpeechLine(speaker: "Jane", text: "好，一起確認資料與來源。")]
            try await send(store, model: media["GEMINI_SPEECH"]!, options: options, prompt: "")
            try await capture(mediaPreview(store, operation: "GEMINI_SPEECH"), output, "01-dual-speech-light", dark: false)
            stage = "speech-format-speed-instructions-wire"
            options = MediaDraft(); options.voice = "alloy"; options.speed = 1.75; options.instructions = "親切"
            try await send(store, model: media["AUDIO_SPEECH"]!, options: options, prompt: "這是語音測試。")
            stage = "audio-with-no-text-real-send"
            let audio = try await fixtureFile(fixture, path: "/fixture/parity.wav", output: output, name: "input.wav")
            store.chooseModel(media["AUDIO"]!); store.draft = ""; store.attachments = try LocalAttachment.adding([audio], to: [])
            try require(store.canSend); store.send(); try await completed(store)
            try require(store.mediaResult?["text"].string.contains("只含音訊") == true && store.problem == nil)
            try await capture(mediaPreview(store, operation: "AUDIO"), output, "02-audio-transcription-light", dark: false)
            stage = "embedding-dimensions-and-rerank-top-n-wire"
            options = MediaDraft(); options.dimensions = "3"
            try await send(store, model: media["EMBEDDINGS"]!, options: options, prompt: "模型研究\n原生 Mac")
            try require(store.mediaResult?["vectors"].array.count == 2)
            try await capture(mediaPreview(store, operation: "EMBEDDINGS"), output, "03-vector-results-light", dark: false)
            options.documents = "第一份文件\n第二份文件"; options.topN = "1"
            try await send(store, model: media["RERANK"]!, options: options, prompt: "哪份文件最相關？")
            try require(store.mediaResult?["rankings"].array.count == 1 && store.mediaResult?["rankings"].array.first?["index"] == .number(1))
            stage = "image-generation-and-edit-real-files"
            try await send(store, model: media["IMAGES_GENERATION"]!, options: MediaDraft(), prompt: "合成圖片")
            try require(store.mediaResult?["data"].array.first?["mime"].string == "image/png")
            try await capture(mediaPreview(store, operation: "IMAGES_GENERATION"), output, "04-image-result-dark", dark: true)
            let image = try await fixtureFile(fixture, path: "/fixture/parity.png", output: output, name: "input.png")
            store.chooseModel(media["IMAGES_EDIT"]!); store.attachments = try LocalAttachment.adding([image], to: []); store.draft = "編輯這份合成圖片"; store.send(); try await completed(store)
            try require(store.mediaResult?["outcome"].string == "SUCCESS")
            stage = "batch-preview-confirm-undo-exact-account"
            let selections = [ModelScope(chat).json, ModelScope(media["AUDIO_SPEECH"]!).json]
            let pending = try await api.call("/assistant/actions/preview", method: "POST", body: .object(["kind": .string("MODEL_BATCH"), "selections": .array(selections), "patch": .object(["state": .string("HIDDEN")])]))
            let review = ActionReviewController(api: api, value: pending)
            try await capture(ActionReviewView(store: store, model: review), output, "06-batch-review-light", dark: false)
            await review.apply(); try require(review.problem == nil && review.value["state"].string == "CONFIRMED")
            await store.refresh()
            try require(store.myModels.filter { $0["state"].string == "HIDDEN" }.count == 2 && store.activeModels.contains { ModelScope($0) == ModelScope(foreign) })
            await review.apply(undo: true); await store.refresh(); try require(review.problem == nil && store.myModels.allSatisfy { $0["state"].string == "ACTIVE" })
            let stale = ActionReviewController(api: api, value: try await api.call("/assistant/actions/preview", method: "POST", body: .object(["kind": .string("MODEL_BATCH"), "selections": .array(selections), "patch": .object(["state": .string("ARCHIVED")])])))
            var lifecycle = ModelScope(chat).json; lifecycle["state"] = .string("HIDDEN"); _ = try await store.mutate("/models/lifecycle", body: lifecycle)
            lifecycle["state"] = .string("ACTIVE"); _ = try await store.mutate("/models/lifecycle", body: lifecycle)
            await stale.apply(); try require(stale.problem != nil && stale.value["state"].string == "PENDING")
            try await capture(ActionReviewView(store: store, model: stale), output, "07-stale-review-dark", dark: true)
            stage = "native-model-filters"
            try await capture(ParityFiltersPreview(store: store), output, "05-model-filters-light", dark: false)
            stage = "connector-draft-test-activate-caller-undo"
            var connectorDraft = ConnectorDraft(); connectorDraft.provider = "parity"; connectorDraft.name = "quota-fixture"; connectorDraft.path = "/v1/quota/{model}"; connectorDraft.root = "/data"; connectorDraft.reason = "隔離 TLS 合成測試"
            stage = "connector-save-draft"
            let proposed = try await api.call("/connectors", method: "POST", body: connectorDraft.request())
            let connector = VersionController(api: api, value: proposed, connector: true)
            stage = "connector-real-test"
            await connector.test(ModelScope(chat)); try require(connector.problem == nil && connector.value["status"].string == "VALIDATED")
            try await capture(VersionDetailView(store: store, controller: connector), output, "08-connector-tested-light", dark: false)
            stage = "connector-activate"
            await connector.activate(); try require(connector.problem == nil && connector.value["active"].boolean)
            stage = "connector-intelligence-caller"
            _ = try await api.call("/intelligence/sync", method: "POST", body: ModelScope(chat).json)
            let synced = try await api.get("/models/detail", query: ModelScope(chat).query)
            try require(synced["history"].array.contains { $0["source"].string.hasPrefix("DECLARATIVE_PROVIDER_META:") })
            stage = "connector-undo"
            await connector.undo(); try require(connector.problem == nil && !connector.value["active"].boolean)
            stage = "evidence-backed-knowledge-version-and-stale-guard"
            let source: JSONValue = .object(["id": .string("parity-source"), "name": .string("公開來源 · 合成定義"), "kind": .string("JSON"), "url": .string("https://example.com/models"), "language": .string("zh"), "enabled": .bool(false), "config": .object(["fields": .object(["model_id": .string("/id")])])])
            _ = try await api.call("/research/sources", method: "POST", body: source)
            var knowledgeBody: JSONValue = .object(["kind": .string("SOURCE_RELIABILITY"), "subject": .string("parity-source"), "data": .object(["source_id": .string("parity-source"), "weight": .number(1.25), "confidence": .string("EVIDENCE_BACKED")]), "evidence_ids": .array([proposed["id"]]), "reason": .string("已保存的合成擷取測試證據")])
            let v1 = try await api.call("/research/knowledge", method: "POST", body: knowledgeBody)
            let knowledge = VersionController(api: api, value: v1, connector: false)
            await knowledge.activate(); try require(knowledge.problem == nil && knowledge.value["active"].boolean)
            try await capture(VersionDetailView(store: store, controller: knowledge), output, "09-knowledge-active-dark", dark: true)
            await knowledge.undo(); try require(knowledge.problem == nil && !knowledge.value["active"].boolean)
            knowledgeBody["reason"] = .string("第二版合成判讀")
            let v2 = VersionController(api: api, value: try await api.call("/research/knowledge", method: "POST", body: knowledgeBody), connector: false)
            await v2.reloadForAcceptance()
            let staleKnowledge = VersionController(api: api, value: v1, connector: false)
            await v2.activate(); try require(v2.problem == nil)
            await staleKnowledge.activate(); try require(staleKnowledge.problem != nil)
            await v2.undo(); try require(v2.problem == nil)
            stage = "candidate-evidence-and-manual-review"
            let candidate = try await api.call("/research/candidates", method: "POST", body: .object(["provider_id": .string("parity"), "model_id": .string("native-fixture-chat"), "rpd": .number(120), "tpd": .number(2_000_000), "website": .string("https://example.com"), "recurring": .string("DAILY_RESET")]))
            let proof = try await api.call("/research/evidence", method: "POST", body: .object(["candidate_id": candidate["id"], "source_type": .string("MANUAL"), "source_url": .string("https://example.com/models"), "claim": .string("隔離驗收用合成主張；不冒充外部服務實測。")]))
            _ = try await api.call("/research/review", method: "POST", body: .object(["candidate_id": candidate["id"], "taiwan": .string("UNKNOWN"), "strength": .string("UNKNOWN"), "access_method": .string("UNKNOWN"), "reason": .string("合成資料保留未知"), "evidence_ids": .array([proof["id"]]), "confirm": .bool(true)]))
            let refreshed = try await api.get("/research/candidates").requiredArray().first { $0["id"] == candidate["id"] }
            guard let refreshed else { throw AtlasError.invalidResponse }; try require(refreshed["state"].string != "VERIFIED")
            try await capture(CandidateDetailView(store: store, candidate: refreshed), output, "10-candidate-evidence-light", dark: false, marker: "candidate-loaded")
            stage = "research-budget-pause-resume-cancel-without-inference"
            let researchBody = try ResearchRequest.body(sources: [], selections: [ModelScope(chat), ModelScope(foreign)], budget: "1", probes: "0", minimal: false, search: false, lmspeed: false, aa: false)
            let run = try await api.call("/research/start", method: "POST", body: researchBody)
            let exhausted = try await researchStopped(api, id: run["id"])
            try require(exhausted["status"].string == "BUDGET_EXHAUSTED")
            _ = try await api.call("/research/control", method: "POST", body: .object(["id": run["id"], "action": .string("PAUSE")]))
            _ = try await api.call("/research/control", method: "POST", body: .object(["id": run["id"], "action": .string("RESUME"), "additional_budget": .number(1), "additional_probes": .number(0), "retry_failed": .bool(true)]))
            let resumed = try await researchStopped(api, id: run["id"])
            try require(resumed["status"].string == "COMPLETED" && resumed["budget"] == .number(2) && resumed["probes_used"] == .number(0))
            let cancelRun = try await api.call("/research/start", method: "POST", body: researchBody)
            let cancelledFrom = try await researchStopped(api, id: cancelRun["id"]); try require(cancelledFrom["status"].string == "BUDGET_EXHAUSTED")
            let cancelled = try await api.call("/research/control", method: "POST", body: .object(["id": cancelRun["id"], "action": .string("CANCEL")]))
            try require(cancelled["status"].string == "CANCELLED")
            try await capture(ResearchView(store: store), output, "11-research-dashboard-dark", dark: true, marker: "research-loaded")
            stage = "batch-onboarding-does-not-auto-join"
            var batch = BatchProviderDraft(); batch.name = "批次待確認 · 合成資料"; batch.url = fixture + "/v1"; batch.key = "native-fixture-provider-key"; batch.models = "native-fixture-chat"
            let imported = try await api.call("/onboarding/import", method: "POST", body: .object(["items": .array([try batch.request(), .object([:])])])).requiredArray()
            try require(imported.count == 2 && imported[0]["review"]["status"].string == "REVIEW" && imported[1]["status"].string == "INVALID")
            try require(imported[0]["review"]["credentials"].array.allSatisfy { $0["secret"].string.isEmpty })
            stage = "services-and-safe-assistant-reference"
            for name in ["AA", "BRAVE_SEARCH"] { _ = try await api.call("/research/integrations", method: "POST", body: .object(["name": .string(name), "secret": .string("synthetic-service-key-only")])) }
            _ = try await api.call("/lmspeed/key", method: "POST", body: .object(["secret": .string("synthetic-lmspeed-key-only")]))
            let reference = try await api.call("/assistant/secrets", method: "POST", body: .object(["base_url": .string(fixture + "/v1"), "label": .string("隔離驗收參照"), "role": .string("INFERENCE"), "secret": .string("native-fixture-provider-key"), "auth_fields": .array([])]))
            let references = try await api.get("/assistant/secrets").requiredArray()
            try require(references.contains { $0["id"] == reference["id"] } && references.allSatisfy { $0["secret"] == .null })
            try await capture(ServicesView(store: store), output, "12-services-light", dark: false, marker: "services-loaded")
            _ = try await api.call("/assistant/config", method: "POST", body: .object(["selection": ModelScope(chat).json]))
            var assistantDone = false
            try await api.stream("/assistant/run", body: .object(["message": .string("查閱安全參照 " + reference["id"].string), "allow_tests": .bool(false), "thread_id": .string("")])) { if $0.name == "done" { assistantDone = true } }
            try require(assistantDone)
            try await capture(AssistantView(store: store), output, "13-assistant-light", dark: false)
            stage = "retained-history-and-complete-export"
            await store.selectSession(originalSession); try require(store.messages == originalMessages)
            let exported = output.appendingPathComponent("scoped-requests.ndjson")
            try await api.download("/telemetry/export", query: ModelScope(chat).query, to: exported)
            let exportData = try Data(contentsOf: exported); try require(exportData.count > 0)
            let counts = try await fixtureJSON(fixture + "/fixture/parity-state")
            for name in ["speech", "native-current", "native-legacy", "native-dual", "transcription", "embeddings", "rerank", "images", "image-edit", "connector"] { try require((counts[name].numeric ?? 0) > 0) }
            try require(counts["invalid"].numeric == nil)
            stage = "modern-native-workspace-captures"
            store.newConversation(); store.chooseModel(chat); store.draft = ""; store.workspace = .playground
            try await captureWorkspace(store, output, "14-welcome-light", dark: false)
            try await captureWorkspace(store, output, "15-welcome-dark", dark: true)
            try await capture(SettingsView(store: store), output, "16-settings-light", dark: false)
            try await capture(SettingsView(store: store), output, "17-settings-dark", dark: true)
            let result: JSONValue = .object(["result": .string("PASS"), "screenshots": .number(17), "real_tls_media": .bool(true), "batch_confirm_undo": .bool(true), "stale_guards": .bool(true), "connector_caller": .bool(true), "knowledge_versions": .bool(true), "manual_evidence": .bool(true), "safe_references": .bool(true), "retained_history": .bool(true), "wire_counts": counts])
            try JSONEncoder().encode(result).write(to: output.appendingPathComponent("native-parity.json"), options: .atomic)
        } catch { try? Data(("Native parity failed at " + stage + ": " + error.localizedDescription).utf8).write(to: output.appendingPathComponent("FAILURE.txt")) }
        DispatchQueue.main.async { NSApp.terminate(nil) }
    }
    private static func require(_ value: @autoclosure () -> Bool) throws { guard value() else { throw AtlasError.invalidResponse } }
    private static func completed(_ store: AtlasStore) async throws {
        let end = Date().addingTimeInterval(25)
        while store.generating, Date() < end { try await Task.sleep(nanoseconds: 20_000_000) }
        try require(!store.generating)
    }
    private static func send(_ store: AtlasStore, model: JSONValue, options: MediaDraft, prompt: String) async throws {
        store.chooseModel(model); store.mediaDraft = options; store.draft = prompt; store.send(); try await completed(store)
        try require(store.problem == nil && store.mediaResult?["outcome"].string == "SUCCESS")
    }
    private static func join(_ store: AtlasStore, model: String, operation: String, endpointID: String? = nil) async throws -> JSONValue {
        _ = try await store.mutate("/models", body: .object(["provider_id": .string("parity"), "id": .string(model), "display_name": .string(model)]))
        let scope: JSONValue = .object(["provider_id": .string("parity"), "account_id": .string("primary"), "credential_id": .string("same-key-id"), "model_id": .string(model), "group": .string(""), "endpoint": .string(endpointID ?? operation)])
        _ = try await store.mutate("/my-models", body: scope)
        let capability = ["CHAT_COMPLETIONS":"TOOL_CALLING", "AUDIO":"AUDIO_INPUT", "AUDIO_SPEECH":"AUDIO_OUTPUT", "GEMINI_SPEECH":"AUDIO_OUTPUT", "EMBEDDINGS":"EMBEDDING", "RERANK":"RERANK", "IMAGES_GENERATION":"IMAGE_GENERATION", "IMAGES_EDIT":"IMAGE_EDITING"][operation]!
        var confirmed = scope; confirmed["capabilities"] = .array([.string(capability)]); confirmed["confirm"] = .bool(true)
        _ = try await store.mutate("/models/capabilities", body: confirmed)
        guard let joined = store.activeModels.first(where: { ModelScope($0) == ModelScope(scope) }) else { throw AtlasError.invalidResponse }; return joined
    }
    private static func researchStopped(_ api: AtlasClient, id: JSONValue) async throws -> JSONValue {
        let end = Date().addingTimeInterval(20)
        while Date() < end {
            let runs = try await api.get("/research/runs").requiredArray()
            if let run = runs.first(where: { $0["id"] == id }), !["QUEUED", "RUNNING"].contains(run["status"].string) { return run }
            try await Task.sleep(nanoseconds: 40_000_000)
        }
        throw AtlasError.invalidResponse
    }
    private static func fixtureJSON(_ url: String) async throws -> JSONValue { let (data, response) = try await URLSession.shared.data(from: URL(string: url)!); try require((response as? HTTPURLResponse)?.statusCode == 200); return try JSONDecoder().decode(JSONValue.self, from: data) }
    private static func fixtureFile(_ fixture: String, path: String, output: URL, name: String) async throws -> URL {
        let (data, response) = try await URLSession.shared.data(from: URL(string: fixture + path)!)
        try require((response as? HTTPURLResponse)?.statusCode == 200)
        let inputs = output.appendingPathComponent("fixture-inputs", isDirectory: true)
        try FileManager.default.createDirectory(at: inputs, withIntermediateDirectories: true)
        let file = inputs.appendingPathComponent(name); try data.write(to: file); return file
    }
    private static func mediaPreview(_ store: AtlasStore, operation: String) -> some View {
        ScrollView { VStack(alignment: .leading, spacing: 18) { Label(operationTitle(operation), systemImage: "sparkles").font(.title2.weight(.semibold)); Text("原生 App · 隔離 TLS 合成驗收").foregroundStyle(.secondary); MediaOptionsView(draft: Binding(get: { store.mediaDraft }, set: { store.mediaDraft = $0 }), operation: operation, expanded: true); if let result = store.mediaResult { MediaResultView(value: result) } }.padding(24) }.frame(width: 850, height: 740)
    }
    private static func capture<V: View>(_ view: V, _ output: URL, _ name: String, dark: Bool, marker: String? = nil) async throws {
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 860, height: 760), styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = "ModelAtlas · 原生功能驗收"
        window.isReleasedWhenClosed = false
        window.contentView = NSHostingView(rootView: ScrollView { view.tint(atlasAccent).preferredColorScheme(dark ? .dark : .light) }.frame(width: 860, height: 760))
        window.center(); window.makeKeyAndOrderFront(nil); defer { window.close() }
        if let marker {
            let end = Date().addingTimeInterval(4)
            while !contains(window.contentView, marker), Date() < end { try await Task.sleep(nanoseconds: 20_000_000) }
            try require(contains(window.contentView, marker))
        }
        try await Task.sleep(nanoseconds: 450_000_000)
        guard let root = window.contentView?.superview else { throw AtlasError.invalidResponse }; root.layoutSubtreeIfNeeded(); root.displayIfNeeded()
        guard let bitmap = root.bitmapImageRepForCachingDisplay(in: root.bounds) else { throw AtlasError.invalidResponse }; root.cacheDisplay(in: root.bounds, to: bitmap)
        guard let data = bitmap.representation(using: .png, properties: [:]), data.count > 10_000 else { throw AtlasError.invalidResponse }; try data.write(to: output.appendingPathComponent(name + ".png"))
    }
    private static func captureWorkspace(_ store: AtlasStore, _ output: URL, _ name: String, dark: Bool) async throws {
        guard let window = NSApp.windows.first(where: { $0.isVisible && $0.contentView != nil }) else { throw AtlasError.invalidResponse }
        UserDefaults.standard.set(dark ? "dark" : "light", forKey: "appearance")
        window.setContentSize(NSSize(width: 1200, height: 800)); window.center(); window.makeKeyAndOrderFront(nil)
        try await Task.sleep(nanoseconds: 450_000_000)
        guard let root = window.contentView?.superview else { throw AtlasError.invalidResponse }
        root.layoutSubtreeIfNeeded(); root.displayIfNeeded()
        guard let bitmap = root.bitmapImageRepForCachingDisplay(in: root.bounds) else { throw AtlasError.invalidResponse }
        root.cacheDisplay(in: root.bounds, to: bitmap)
        guard let data = bitmap.representation(using: .png, properties: [:]), data.count > 10_000 else { throw AtlasError.invalidResponse }
        try data.write(to: output.appendingPathComponent(name + ".png"))
    }
    private static func contains(_ view: NSView?, _ marker: String) -> Bool { guard let view else { return false }; return view.identifier?.rawValue == marker || view.subviews.contains { contains($0, marker) } }
}

private struct ParityFiltersPreview: View {
    @ObservedObject var store: AtlasStore
    @State private var filters = ModelFilters()
    var body: some View { ScrollView { VStack(alignment: .leading, spacing: 18) { Text("模型篩選與排序").font(.title2.weight(.semibold)); ModelFiltersView(store: store, filters: $filters, expanded: true) }.padding(24) }.frame(width: 850, height: 740) }
}

private extension VersionController {
    func reloadForAcceptance() async { do { try await reload() } catch { problem = error.localizedDescription } }
}
