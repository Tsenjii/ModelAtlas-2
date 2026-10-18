import AppKit
import AtlasCore
import CryptoKit
import SwiftUI

// Separate opt-in process/data directory. The original 25-frame acceptance
// remains intact; this phase exercises the newly added production workflows.
@MainActor enum NativeParity {
    static func run(store: AtlasStore, output: URL) async {
        var stage = "bootstrap"
        do {
            try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
            guard store.ready, let api = store.api else { throw AtlasError.engineUnavailable }
            UserDefaults.standard.set("graphite", forKey: "workspaceAccent")
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
            try require(review.batchComplete && Set(review.batchChanges.map { $0.scope }) == Set(selections.map(ModelScope.init)))
            try require(review.batchChanges.allSatisfy { $0.before["state"].string == "ACTIVE" && $0.after["state"].string == "HIDDEN" })
            var incomplete = pending; incomplete["after"]["items"] = .array(Array(pending["after"]["items"].array.prefix(1)))
            let incompleteReview = ActionReviewController(api: api, value: incomplete)
            try require(!incompleteReview.batchComplete); await incompleteReview.apply()
            try require(incompleteReview.value["state"].string == "PENDING")
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
            stage = "effective-price-not-borrowed-across-accounts"
            var nominal = ModelScope(chat).json
            nominal["price"] = .object(["input": .string("0.125"), "output": .string("0.25"), "fixed_absent": .bool(true), "unit": .string("USD/1K tokens")])
            nominal["note"] = .string("較早的合成標價；未確認實際扣款")
            _ = try await api.call("/models/facts", method: "POST", body: nominal)
            var price = ModelScope(chat).json
            price["price"] = .object(["input": .string("0"), "output": .string("0"), "fixed": .null, "fixed_absent": .bool(true), "cache": .string("0.01"), "unit": .string("USD/1K tokens")])
            price["confirmed_effective_price"] = .bool(true)
            price["note"] = .string("隔離合成價格，僅供原生管理介面驗收")
            _ = try await store.mutate("/models/facts", body: price)
            try require(store.myModels.first(where: { ModelScope($0) == ModelScope(chat) })?["price_state"].string == "FREE_NOW")
            try require(store.myModels.first(where: { ModelScope($0) == ModelScope(foreign) })?["price_state"].string == "UNKNOWN")
            store.workspace = .models
            try await captureWorkspace(store, output, "20-models-known-free-light", dark: false)
            stage = "modern-native-workspace-captures"
            store.newConversation(); store.chooseModel(chat); store.draft = ""; store.workspace = .playground
            try await captureWorkspace(store, output, "14-welcome-light", dark: false)
            stage = "native-composer-multiline-ime-and-draft-reset"
            try await verifyComposer(store, output: output)
            try await captureWorkspace(store, output, "15-welcome-dark", dark: true)
            try await capture(SettingsView(store: store), output, "16-settings-light", dark: false)
            try await capture(SettingsView(store: store), output, "17-settings-dark", dark: true)
            stage = "scoped-native-intelligence-prices-pools-trends-history"
            try await verifyIntelligence(store, chat: chat, foreign: foreign, output: output, fixture: fixture)
            stage = "real-native-comparison-and-appearance"
            try await verifyComparison(store, chat: chat, foreign: foreign, output: output)
            stage = "native-chart-selection-and-retained-archive"
            try await verifyCharts(store, chat: chat, foreign: foreign, output: output, fixture: fixture)
            stage = "account-checkin-scope-retry-and-civil-date"
            try await verifyAccountCheckins(store, chat: chat, output: output, fixture: fixture)
            stage = "public-site-auto-read-source-scope-retry"
            try await verifyPublicSite(store, chat: chat, output: output, fixture: fixture)
            stage = "native-motion-and-reduced-motion"
            try await verifyMotion(store, output: output)
            let result: JSONValue = .object(["result": .string("PASS"), "screenshots": .number(43), "real_tls_media": .bool(true), "batch_confirm_undo": .bool(true), "stale_guards": .bool(true), "connector_caller": .bool(true), "knowledge_versions": .bool(true), "manual_evidence": .bool(true), "safe_references": .bool(true), "retained_history": .bool(true), "native_composer": .bool(true), "adaptive_composer": .bool(true), "composer_focus": .bool(true), "sidebar_toggle": .bool(true), "price_scope_guards": .bool(true), "native_intelligence": .bool(true), "telemetry_trends": .bool(true), "quota_scope_guards": .bool(true), "read_only_no_inference": .bool(true), "native_comparison": .bool(true), "comparison_receipts": .bool(true), "comparison_scope_guards": .bool(true), "appearance_choices": .bool(true), "chart_keyboard_selection": .bool(true), "observation_archive": .bool(true), "chart_receipt_identity": .bool(true), "chart_export_exact": .bool(true), "account_checkin_scope": .bool(true), "checkin_retry_cancel": .bool(true), "native_motion": .bool(true), "reduced_motion": .bool(true), "public_site_auto_read": .bool(true), "provider_series_scope": .bool(true), "site_partial_retry": .bool(true), "connection_actions": .bool(true), "wire_counts": counts])
            try JSONEncoder().encode(result).write(to: output.appendingPathComponent("native-parity.json"), options: .atomic)
        } catch { try? Data(("Native parity failed at " + stage + ": " + error.localizedDescription).utf8).write(to: output.appendingPathComponent("FAILURE.txt")) }
        DispatchQueue.main.async { NSApp.terminate(nil) }
    }
    private static func require(_ value: @autoclosure () -> Bool) throws { guard value() else { throw AtlasError.invalidResponse } }
    private static func verifyPublicSite(_ store: AtlasStore, chat: JSONValue, output: URL, fixture: String) async throws {
        guard let api = store.api, let provider = store.providers.first(where: { $0.id == chat["provider_id"].string }),
              let key = store.credentials.first(where: { $0["provider_id"] == chat["provider_id"] && $0["account_id"] == chat["account_id"] && $0["id"] == chat["credential_id"] }) else { throw AtlasError.invalidResponse }
        let before = try await fixtureJSON(fixture + "/fixture/parity-state")
        let selected = store.selectedModel, session = store.sessionID, messages = store.messages, draft = store.draft
        let site = ProviderSiteController(providerID: provider.id)
        site.refresh(using: api, operations: ["status", "notice", "summary", "pricing"])
        try await wait { site.loading.isEmpty }
        try require(site.problems.isEmpty && site.values.count == 4 && site.values["notice"]?.facts["text"].string.contains("\n") == true)
        try require(site.values["status"]?.raw["version"].string == "v0.0.0" && site.values["pricing"]?.facts["price_mapping_available"] == .bool(false))
        try await capture(ProviderDetailView(store: store, provider: provider, initialTab: "站方資料", site: site), output, "40-site-auto-read-light", dark: false, marker: "provider-site-loaded")
        let lastGood = site.values["notice"]?.raw
        _ = try await fixtureJSON(fixture + "/fixture/site-failure?enabled=true")
        site.refresh(using: api, operations: ["notice"]); try await wait { site.loading.isEmpty }
        try require(site.problems["notice"]?.contains("502") == true && site.problems["notice"]?.contains("native-fixture-provider-key") == false && site.values["notice"]?.raw == lastGood)
        try await capture(ProviderDetailView(store: store, provider: provider, initialTab: "站方資料", site: site), output, "43-site-partial-failure-dark", dark: true, marker: "provider-site-loaded")
        _ = try await fixtureJSON(fixture + "/fixture/site-failure?enabled=false")
        site.refresh(using: api, operations: ["notice"]); site.cancel()
        try await Task.sleep(nanoseconds: 30_000_000)
        try require(site.values["notice"]?.raw == lastGood)
        site.refresh(using: api, operations: ["notice"]); try await wait { site.loading.isEmpty }
        try require(site.problems["notice"] == nil && site.values["notice"] != nil)
        let performance = ProviderSiteController(providerID: provider.id, modelID: chat["model_id"].string)
        performance.refresh(using: api, operations: ["performance"]); try await wait { performance.loading.isEmpty }
        guard let snapshot = performance.values["performance"] else { throw AtlasError.invalidResponse }
        try require(performance.problems.isEmpty && snapshot.chart(metric: "avg_tps", group: "")?.points.count == 4 && snapshot.chart(metric: "avg_tps", group: "other") == nil)
        try await capture(ModelDetailView(store: store, model: chat, initialTab: "效能", initialPerformanceSource: "provider", providerPerformance: performance), output, "41-provider-performance-chart-dark", dark: true, marker: "provider-performance-loaded")
        let catalog = try await api.get("/models", query: [URLQueryItem(name: "provider_id", value: provider.id)])
        guard let item = catalog.array.first(where: { $0["provider_id"].string == provider.id && $0["id"] == chat["model_id"] }) else { throw AtlasError.invalidResponse }
        try await capture(CatalogDetailView(store: store, model: item, initialKey: key, initialEndpoint: chat["endpoint"].string), output, "42-catalog-active-connection-light", dark: false, marker: "catalog-connection-state-ACTIVE")
        let counts = try await fixtureJSON(fixture + "/fixture/site-state")
        try require((counts["public_reads"].numeric ?? 0) >= 12 && counts["auth_leaks"] == .number(0))
        let after = try await fixtureJSON(fixture + "/fixture/parity-state")
        try require(before == after && store.selectedModel == selected && store.sessionID == session && store.messages == messages && store.draft == draft)
    }
    private static func verifyAccountCheckins(_ store: AtlasStore, chat: JSONValue, output: URL, fixture: String) async throws {
        guard let api = store.api, let provider = store.providers.first(where: { $0.id == chat["provider_id"].string }) else { throw AtlasError.invalidResponse }
        let before = try await fixtureJSON(fixture + "/fixture/parity-state")
        let selectedScope = store.selectedScope, session = store.sessionID, messages = store.messages, draft = store.draft
        var config = provider["config"]; config["requires_daily_checkin"] = .bool(true); config["checkin_url"] = .string("https://example.com/checkin")
        _ = try await store.mutate("/providers/config", body: .object(["provider_id": provider["id"], "config": config]))
        // Setup intentionally changes the selected offer's check-in metadata.
        // Verify identity/drafts across setup, then freeze the complete model
        // value for the read/complete/retry flow itself.
        try require(store.selectedScope == selectedScope && store.sessionID == session && store.messages == messages && store.draft == draft)
        let selected = store.selectedModel
        _ = try await api.call("/checkins", method: "POST", body: .object(["provider_id": provider["id"], "timezone": .string(store.checkins.timezone)]))
        store.checkins.refresh(using: api); try await wait { !store.checkins.pending && store.checkins.current != nil }
        let rows = store.checkins.rows(providerID: provider.id) ?? []
        try require(rows.count == 2 && rows.allSatisfy(\.pending))
        try await capture(ProvidersView(store: store, initialFilter: "checkin"), output, "36-providers-checkin-pending-light", dark: false)
        let lastGood = store.checkins.state?.accounts
        let denied = try AtlasClient(baseURL: api.baseURL, token: "invalid-native-test-only-admin-token")
        store.checkins.refresh(using: denied); try await wait { !store.checkins.pending }
        try require(store.checkins.problem != nil && store.checkins.state?.accounts == lastGood)
        store.checkins.refresh(using: api); store.checkins.cancel()
        try await Task.sleep(nanoseconds: 30_000_000)
        try require(!store.checkins.pending && store.checkins.state?.accounts == lastGood)
        store.checkins.refresh(using: api); try await wait { !store.checkins.pending && store.checkins.problem == nil }
        try await store.checkins.complete(rows[0], using: api)
        try await store.checkins.complete(rows[0], using: api)
        let partial = store.checkins.rows(providerID: provider.id) ?? []
        try require(partial.count == 2 && partial.filter(\.pending).count == 1 && partial.first(where: { $0.id == rows[1].id })?.pending == true)
        try await capture(ProviderDetailView(store: store, provider: provider), output, "37-account-checkin-overview-dark", dark: true, marker: "account-checkins-loaded")
        try await store.checkins.complete(rows[1], using: api)
        store.checkins.refresh(using: api); try await wait { !store.checkins.pending }
        try require(store.checkins.rows(providerID: provider.id)?.allSatisfy({ !$0.pending && $0.completedAt != nil }) == true)
        try await capture(ProvidersView(store: store, initialFilter: "checkin"), output, "39-checkin-filter-complete-light", dark: false)
        let after = try await fixtureJSON(fixture + "/fixture/parity-state")
        try require(before == after && store.selectedModel == selected && store.sessionID == session && store.messages == messages && store.draft == draft)
    }
    private static func verifyMotion(_ store: AtlasStore, output: URL) async throws {
        guard let window = NSApp.windows.first(where: { $0.isVisible && $0.contentView != nil }) else { throw AtlasError.invalidResponse }
        let oldPreference = UserDefaults.standard.bool(forKey: "reduceInterfaceMotion"), oldWorkspace = store.workspace, oldSidebar = store.sidebarVisible
        defer { UserDefaults.standard.set(oldPreference, forKey: "reduceInterfaceMotion"); store.sidebarVisible = oldSidebar; store.workspace = oldWorkspace }
        let selected = store.selectedModel, session = store.sessionID, messages = store.messages, draft = store.draft
        window.setContentSize(NSSize(width: 1200, height: 800)); window.makeKeyAndOrderFront(nil); window.makeFirstResponder(nil)
        store.workspace = .providers; store.sidebarVisible = true
        UserDefaults.standard.set(false, forKey: "reduceInterfaceMotion")
        let systemReduced = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        try await wait { contains(window.contentView, systemReduced ? "motion-reduced" : "motion-standard") }
        try await Task.sleep(nanoseconds: 300_000_000)
        let directory = output.appendingPathComponent("motion", isDirectory: true); try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var records: [JSONValue] = []
        for (name, visible) in [("sidebar-close", false), ("sidebar-open", true)] {
            let start = ProcessInfo.processInfo.systemUptime
            var hashes = Set<String>()
            for (index, ms) in [0, 40, 80, 120, 180, 280].enumerated() {
                let remaining = start + Double(ms) / 1000 - ProcessInfo.processInfo.systemUptime
                if remaining > 0 { try await Task.sleep(nanoseconds: UInt64(remaining * 1_000_000_000)) }
                let data = try motionFrame(window), file = "\(name)-\(index).png"
                let hash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(); hashes.insert(hash)
                try data.write(to: directory.appendingPathComponent(file))
                records.append(.object(["file": .string(file), "elapsed_ms": .number((ProcessInfo.processInfo.systemUptime - start) * 1000), "sha256": .string(hash)]))
                if index == 0 { store.sidebarVisible = visible }
            }
            if !systemReduced { try require(hashes.count >= 3) }
        }
        UserDefaults.standard.set(true, forKey: "reduceInterfaceMotion")
        try await wait { contains(window.contentView, "motion-reduced") }
        try await capture(SettingsView(store: store), output, "38-settings-reduced-motion-light", dark: false, marker: "motion-reduced")
        window.makeKeyAndOrderFront(nil); window.makeFirstResponder(nil)
        store.sidebarVisible = false
        try await wait { !contains(window.contentView, "workspace-sidebar") }
        try await Task.sleep(nanoseconds: 40_000_000)
        let first = try motionFrame(window); try first.write(to: directory.appendingPathComponent("reduced-0.png"))
        try await Task.sleep(nanoseconds: 160_000_000)
        let last = try motionFrame(window); try last.write(to: directory.appendingPathComponent("reduced-1.png"))
        try require(first == last)
        let report: JSONValue = .object(["result": .string("PASS"), "system_reduced_motion": .bool(systemReduced), "normal_frames": .number(12), "reduced_frames": .number(2), "reduced_settled": .bool(true), "frames": .array(records)])
        try JSONEncoder().encode(report).write(to: output.appendingPathComponent("motion.diagnostics.log"), options: .atomic)
        try require(store.selectedModel == selected && store.sessionID == session && store.messages == messages && store.draft == draft)
    }
    private static func motionFrame(_ window: NSWindow) throws -> Data {
        // Compare actual app content; WindowServer titlebar activation has its
        // own animations and is outside the app's Reduce Motion transaction.
        guard let root = window.contentView else { throw AtlasError.invalidResponse }
        root.layoutSubtreeIfNeeded(); root.displayIfNeeded()
        guard let bitmap = root.bitmapImageRepForCachingDisplay(in: root.bounds) else { throw AtlasError.invalidResponse }
        root.cacheDisplay(in: root.bounds, to: bitmap)
        guard let data = bitmap.representation(using: .png, properties: [:]), data.count > 10_000 else { throw AtlasError.invalidResponse }; return data
    }
    private static func verifyCharts(_ store: AtlasStore, chat: JSONValue, foreign: JSONValue, output: URL, fixture: String) async throws {
        guard let api = store.api else { throw AtlasError.engineUnavailable }
        let before = try await fixtureJSON(fixture + "/fixture/parity-state")
        let selected = store.selectedModel, session = store.sessionID, messages = store.messages, draft = store.draft
        let scope = ModelScope(chat)
        for index in 0..<205 {
            var value = scope.json
            let input = index == 204 ? "0" : String(format: "%.6f", Double((index * 17) % 37) / 1000)
            value["price"] = .object(["input": .string(input), "output": .string("0"), "fixed_absent": .bool(true), "cache": index % 13 == 0 ? .null : .string("0.01"), "unit": .string("USD/1K tokens")])
            value["confirmed_effective_price"] = .bool(true)
            value["note"] = .string("圖表與永久歷史的合成價格觀測")
            _ = try await api.call("/models/facts", method: "POST", body: value)
        }
        let prices = ObservationArchiveController(scope: scope)
        let filter = ObservationFilter(category: "PRICE")
        prices.refresh(using: api, filter: filter); prices.refresh(using: api, filter: filter)
        try await wait { !prices.pending }
        try require(prices.problem == nil && prices.state?.rows.count == 200 && (prices.state?.total ?? 0) >= 207)
        prices.loadMore(using: api); prices.loadMore(using: api)
        try await wait { !prices.pending }
        guard let saved = prices.state else { throw AtlasError.invalidResponse }
        try require(prices.problem == nil && saved.rows.count == saved.total && saved.rows.count > 200 && saved.nextCursor.isEmpty)
        try require(Set(saved.rows.map(\.id)).count == saved.rows.count && saved.rows.allSatisfy { ModelScope($0.raw) == scope })
        let projection = ModelIntelligence(detail: .object(["selection": scope.json, "history": .array(saved.rows.map(\.raw))]), scope: scope)
        guard let track = projection.priceTracks.first(where: { $0.legKey == "price" && $0.observations.count > 1 }) else { throw AtlasError.invalidResponse }
        let priceData = TrendData(points: track.observations.map { TrendPoint(id: $0.id, at: $0.at, rawValue: $0.facts["price"]["input"]) }, unit: "USD/1K tokens", title: "輸入價格")
        try require(priceData.points.count > 200 && priceData.points.contains { $0.value == 0 })
        let export = try JSONValue.parse(priceData.export.pretty)
        try require(export["coverage"].string == "LOADED_OBSERVATIONS_ONLY" && export["points"].array.count == priceData.points.count)
        try await capture(ModelDetailView(store: store, model: chat, initialTab: "價格", priceArchive: prices), output, "31-price-chart-selected-light", dark: false, marker: "price-archive-loaded", interaction: { window in
            try await verifyChartInput(window, id: "price", data: priceData)
        })
        let raw = try await api.get("/models/detail", query: scope.query + [URLQueryItem(name: "window", value: "7d")])
        let detail = ModelIntelligence(detail: raw, scope: scope)
        try require(!detail.samples.isEmpty && detail.samples.allSatisfy { !$0.receiptID.isEmpty })
        let localData = TrendData(points: detail.samples.map { TrendPoint(id: $0.id, at: $0.at, rawValue: $0.ttft.map(JSONValue.number) ?? .null) }, unit: "ms", title: "首 Token 延遲")
        try await capture(ModelDetailView(store: store, model: chat, initialTab: "效能"), output, "32-local-chart-selected-dark", dark: true, marker: "chart-input-local", interaction: { window in
            try await verifyChartInput(window, id: "local", data: localData)
        })
        let sampleID = detail.samples.last!.receiptID
        let receipt = try await api.get("/telemetry/detail", query: [URLQueryItem(name: "id", value: sampleID)])
        let sample = receipt["sample"] == .null ? receipt : receipt["sample"]
        try require(ModelScope(sample) == scope && sample["id"].string == sampleID)
        let history = ObservationArchiveController(scope: scope)
        history.refresh(using: api, filter: ObservationFilter(source: "USER", kind: "MANUAL_PRICE"))
        try await wait { !history.pending }; history.loadMore(using: api); try await wait { !history.pending }
        try require(history.problem == nil && (history.state?.rows.count ?? 0) > 200)
        try await capture(ModelDetailView(store: store, model: chat, initialTab: "歷史", historyArchive: history), output, "33-history-archive-dark", dark: true, marker: "observation-archive-loaded")
        let empty = ObservationArchiveController(scope: scope)
        empty.refresh(using: api, filter: ObservationFilter(category: "PRICE", start: 1_735_689_600_000, end: 1_735_776_000_000))
        try await wait { !empty.pending }
        try require(empty.problem == nil && empty.state?.total == 0 && empty.state?.rows.isEmpty == true)
        try await capture(ModelDetailView(store: store, model: chat, initialTab: "價格", priceArchive: empty), output, "34-price-range-empty-light", dark: false, marker: "price-archive-loaded")
        let emptyHistory = ObservationArchiveController(scope: scope)
        emptyHistory.refresh(using: api, filter: ObservationFilter(source: "USER", kind: "MANUAL_PRICE", start: 1_735_689_600_000, end: 1_735_776_000_000))
        try await wait { !emptyHistory.pending }
        try require(emptyHistory.problem == nil && emptyHistory.state?.total == 0 && emptyHistory.state?.sources.isEmpty == true && emptyHistory.state?.kinds.isEmpty == true)
        try await capture(ModelDetailView(store: store, model: chat, initialTab: "歷史", historyArchive: emptyHistory), output, "35-history-filter-empty-light", dark: false, marker: "observation-archive-loaded")
        let other = try await api.get("/models/observations", query: ModelScope(foreign).query + filter.query)
        let otherPage = try ObservationPage(other, scope: ModelScope(foreign), filter: filter)
        try require(otherPage.rows.isEmpty)
        let cancelled = ObservationArchiveController(scope: scope)
        cancelled.refresh(using: api, filter: filter); cancelled.cancel()
        try await Task.sleep(nanoseconds: 30_000_000)
        try require(!cancelled.pending && cancelled.state == nil)
        let after = try await fixtureJSON(fixture + "/fixture/parity-state")
        try require(before == after && store.selectedModel == selected && store.sessionID == session && store.messages == messages && store.draft == draft)
    }
    private static func verifyChartInput(_ window: NSWindow, id: String, data: TrendData) async throws {
        guard let pointer = find(window.contentView, "chart-input-" + id) as? TrendPointerView, pointer.bounds.width > 100,
              let index = data.nearest(to: 0.45) else { throw AtlasError.invalidResponse }
        if let document = pointer.enclosingScrollView?.documentView {
            _ = document.scrollToVisible(pointer.convert(pointer.bounds, to: document).insetBy(dx: 0, dy: -96))
        } else { _ = pointer.scrollToVisible(pointer.bounds) }
        let location = pointer.convert(NSPoint(x: pointer.bounds.width * 0.45, y: pointer.bounds.midY), to: nil)
        guard let click = NSEvent.mouseEvent(with: .leftMouseDown, location: location, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber, context: nil, eventNumber: 0, clickCount: 1, pressure: 1) else { throw AtlasError.invalidResponse }
        pointer.mouseDown(with: click)
        try await wait { contains(window.contentView, "chart-point-" + id + "-" + data.points[index].id) }
        try require(window.firstResponder === pointer)
        guard let arrow = NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber, context: nil, characters: String(UnicodeScalar(0xF703)!), charactersIgnoringModifiers: String(UnicodeScalar(0xF703)!), isARepeat: false, keyCode: 124),
              let nextID = data.moved(from: data.points[index].id, by: 1) else { throw AtlasError.invalidResponse }
        window.sendEvent(arrow)
        try await wait { contains(window.contentView, "chart-point-" + id + "-" + nextID) }
    }
    private static func verifyComparison(_ store: AtlasStore, chat: JSONValue, foreign: JSONValue, output: URL) async throws {
        guard let api = store.api else { throw AtlasError.engineUnavailable }
        let selected = store.selectedModel, session = store.sessionID, messages = store.messages, draft = store.draft
        _ = try await store.mutate("/models/metadata", body: .object(["provider_id": chat["provider_id"], "model_id": chat["model_id"], "canonical_id": chat["model_id"], "primary_category": .string("CHAT_REASONING")]))
        let scopes = [ModelScope(chat), ModelScope(foreign)]
        let models = try scopes.map { scope -> JSONValue in
            guard let row = store.myModels.first(where: { ModelScope($0) == scope }) else { throw AtlasError.invalidResponse }; return row
        }
        let offers = models.map { model in ComparisonOffer(model: model, title: store.modelEntries([model]).first!.title, context: store.scopeTitle(model), operation: store.operationName(model)) }
        let beforeSamples = scopes.map { scope in models.first(where: { ModelScope($0) == scope })?["performance"]["samples"].numeric ?? 0 }
        let controller = ComparisonController(offers: offers, refresh: { try await store.reload() })
        try require(controller.validation == nil && offers[0].scope.accountID != offers[1].scope.accountID)
        try await capture(ComparisonView(store: store, controller: controller), output, "26-comparison-ready-light", dark: false, marker: "comparison-ready")
        let query = [URLQueryItem(name: "q", value: "COMPARE_LIVE"), URLQueryItem(name: "limit", value: "200")]
        let before = try await api.get("/telemetry", query: query).requiredArray()
        controller.start(using: api); controller.start(using: api)
        try await wait { !controller.pending }
        guard let report = controller.report else { throw AtlasError.invalidResponse }
        try require(controller.problem == nil && report.items.map(\.scope) == scopes && report.items.allSatisfy { $0.outcome == "SUCCESS" && !$0.sampleID.isEmpty })
        for (index, scope) in scopes.enumerated() {
            try require(store.myModels.first(where: { ModelScope($0) == scope })?["performance"]["samples"].numeric == beforeSamples[index] + 1)
        }
        let after = try await api.get("/telemetry", query: query).requiredArray()
        try require(after.count == before.count + 2 && Set(report.items.map(\.sampleID)).count == 2)
        for item in report.items {
            let value = try await api.get("/telemetry/detail", query: [URLQueryItem(name: "id", value: item.sampleID)])
            let sample = value["sample"] == .null ? value : value["sample"]
            try require(ModelScope(sample) == item.scope && sample["source"].string == "COMPARE_LIVE")
        }
        try require(report.items[1].receipt["price_state"].string == "UNKNOWN")
        try await capture(ComparisonView(store: store, controller: controller), output, "27-comparison-results-dark", dark: true, marker: "comparison-results")
        try await capture(RequestReceiptView(store: store, sampleID: report.items[0].sampleID), output, "28-comparison-receipt-light", dark: false, marker: "receipt-loaded-" + report.items[0].sampleID)
        let cancelled = ComparisonController(offers: offers)
        cancelled.start(using: api); cancelled.cancel()
        try await Task.sleep(nanoseconds: 30_000_000)
        let unchanged = try await api.get("/telemetry", query: query).requiredArray()
        try require(!cancelled.pending && cancelled.report == nil && unchanged.count == after.count)
        UserDefaults.standard.set("violet", forKey: "workspaceAccent")
        try await capture(SettingsView(store: store), output, "29-settings-violet-light", dark: false, marker: "appearance-violet")
        try require(UserDefaults.standard.string(forKey: "workspaceAccent") == "violet")
        UserDefaults.standard.set("graphite", forKey: "workspaceAccent")
        store.workspace = .models
        try await captureWorkspace(store, output, "30-models-graphite-light", dark: false)
        try require(store.selectedModel.map(ModelScope.init) == selected.map(ModelScope.init) && store.sessionID == session && store.messages == messages && store.draft == draft)
    }
    private static func verifyIntelligence(_ store: AtlasStore, chat: JSONValue, foreign: JSONValue, output: URL, fixture: String) async throws {
        guard let api = store.api else { throw AtlasError.engineUnavailable }
        let scope = ModelScope(chat), beforeMessages = store.messages, beforeDraft = store.draft, beforeModel = store.selectedModel
        let beforeWire = try await fixtureJSON(fixture + "/fixture/parity-state")
        var quota = scope.json
        quota["quota"] = .object(["rpd": .number(200), "tpd": .number(2_000_000), "remaining": .null])
        quota["scope_kind"] = .string("CREDENTIAL"); quota["slice"] = .string("STANDARD_INTERACTIVE"); quota["note"] = .string("合成每日上限；剩餘額度未知")
        _ = try await api.call("/models/facts", method: "POST", body: quota)
        quota["quota"] = .object(["rpm": .number(30), "tpm": .null]); quota["scope_kind"] = .string("GROUP"); quota["slice"] = .string(""); quota["note"] = .string("另一個合成限流池，不與每日額度相加")
        _ = try await api.call("/models/facts", method: "POST", body: quota)
        let raw = try await api.get("/models/detail", query: scope.query + [URLQueryItem(name: "window", value: "7d")])
        let detail = ModelIntelligence(detail: raw, scope: scope)
        try require(detail.matchesSelection && detail.pools.count >= 2 && detail.history.count >= 3)
        try require(detail.pools.contains { $0.facts["values"]["rpd"].numeric == 200 && $0.facts["values"]["remaining"] == .null })
        try require(detail.pools.contains { $0.facts["values"]["rpm"].numeric == 30 && $0.facts["values"]["tpm"] == .null })
        try require(detail.priceTracks.contains { $0.legKey == "price" && $0.observations.count >= 2 } && detail.lastGood.contains { $0.facts["effective_charge"]["cache"].string == "0.01" })
        try require(!detail.samples.isEmpty && detail.samples.contains { $0.tps != nil && $0.ttft != nil })
        let otherRaw = try await api.get("/models/detail", query: ModelScope(foreign).query)
        let other = ModelIntelligence(detail: otherRaw, scope: ModelScope(foreign))
        try require(other.lastGood.allSatisfy { $0.facts["price"] == .null && $0.facts["effective_charge"] == .null } && other.pools.isEmpty && other.samples.isEmpty)
        let custom = try await api.get("/models/detail", query: scope.query + [URLQueryItem(name: "window", value: "custom"), URLQueryItem(name: "start", value: "0"), URLQueryItem(name: "end", value: String(Int64(Date().timeIntervalSince1970 * 1000)))])
        try require(ModelIntelligence(detail: custom, scope: scope).samples.count == detail.samples.count)
        for (tab, name, dark) in [("價格", "21-model-pricing-light", false), ("額度", "22-model-quota-dark", true), ("效能", "23-model-performance-light", false), ("歷史", "24-model-observations-dark", true)] {
            try await capture(ModelDetailView(store: store, model: chat, initialTab: tab), output, name, dark: dark, marker: "model-intelligence-loaded-" + tab)
        }
        try await capture(ModelDetailView(store: store, model: foreign, initialTab: "價格"), output, "25-model-unknown-account-light", dark: false, marker: "model-intelligence-loaded-價格")
        let afterWire = try await fixtureJSON(fixture + "/fixture/parity-state")
        try require(beforeWire == afterWire && store.messages == beforeMessages && store.draft == beforeDraft && store.selectedModel == beforeModel)
    }
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
    private static func capture<V: View>(_ view: V, _ output: URL, _ name: String, dark: Bool, marker: String? = nil, interaction: ((NSWindow) async throws -> Void)? = nil) async throws {
        UserDefaults.standard.set(dark ? "dark" : "light", forKey: "appearance")
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 860, height: 760), styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
        window.title = "ModelAtlas · 原生功能驗收"
        window.isReleasedWhenClosed = false
        window.contentView = NSHostingView(rootView: ScrollView { view.atlasAppearance().preferredColorScheme(dark ? .dark : .light) }.frame(width: 860, height: 760))
        window.center(); window.makeKeyAndOrderFront(nil); defer { window.close() }
        if let marker {
            let end = Date().addingTimeInterval(4)
            while !contains(window.contentView, marker), Date() < end { try await Task.sleep(nanoseconds: 20_000_000) }
            try require(contains(window.contentView, marker))
        }
        try await Task.sleep(nanoseconds: 450_000_000)
        if let interaction { window.contentView?.layoutSubtreeIfNeeded(); try await interaction(window) }
        try require(NSApp.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == (dark ? .darkAqua : .aqua))
        guard let root = window.contentView?.superview else { throw AtlasError.invalidResponse }; root.layoutSubtreeIfNeeded(); root.displayIfNeeded()
        guard let bitmap = root.bitmapImageRepForCachingDisplay(in: root.bounds) else { throw AtlasError.invalidResponse }; root.cacheDisplay(in: root.bounds, to: bitmap)
        guard let data = bitmap.representation(using: .png, properties: [:]), data.count > 10_000 else { throw AtlasError.invalidResponse }; try data.write(to: output.appendingPathComponent(name + ".png"))
    }
    private static func captureWorkspace(_ store: AtlasStore, _ output: URL, _ name: String, dark: Bool) async throws {
        guard let window = NSApp.windows.first(where: { $0.isVisible && $0.contentView != nil }) else { throw AtlasError.invalidResponse }
        UserDefaults.standard.set(dark ? "dark" : "light", forKey: "appearance")
        window.setContentSize(NSSize(width: 1200, height: 800)); window.center(); window.makeKeyAndOrderFront(nil)
        try await Task.sleep(nanoseconds: 450_000_000)
        if store.workspace == .playground { try await wait { !store.sessionsLoading } }
        guard let root = window.contentView?.superview else { throw AtlasError.invalidResponse }
        root.layoutSubtreeIfNeeded(); root.displayIfNeeded()
        guard let bitmap = root.bitmapImageRepForCachingDisplay(in: root.bounds) else { throw AtlasError.invalidResponse }
        root.cacheDisplay(in: root.bounds, to: bitmap)
        guard let data = bitmap.representation(using: .png, properties: [:]), data.count > 10_000 else { throw AtlasError.invalidResponse }
        try data.write(to: output.appendingPathComponent(name + ".png"))
    }
    private static func verifyComposer(_ store: AtlasStore, output: URL) async throws {
        guard let window = NSApp.windows.first(where: { $0.isVisible && $0.contentView != nil }),
              let editor = find(window.contentView, "chat-composer") as? NSTextView,
              window.makeFirstResponder(editor) else { throw AtlasError.invalidResponse }
        editor.selectAll(nil); editor.insertText("第一行\n第二行", replacementRange: NSRange(location: NSNotFound, length: 0))
        try await wait { store.draft == "第一行\n第二行" }
        try require(editor.string == store.draft && store.canSend && !editor.drawsBackground)
        editor.selectAll(nil)
        editor.setMarkedText("注音候選", selectedRange: NSRange(location: 0, length: 0), replacementRange: NSRange(location: NSNotFound, length: 0))
        try require(editor.hasMarkedText())
        store.draft = "外部狀態更新不得取代組字"
        try await Task.sleep(nanoseconds: 80_000_000)
        try require(editor.hasMarkedText() && editor.string.contains("注音候選"))
        editor.unmarkText(); editor.selectAll(nil)
        editor.insertText("完成的中文\n保留換行", replacementRange: NSRange(location: NSNotFound, length: 0))
        try await wait { store.draft == "完成的中文\n保留換行" }
        store.draft = ""
        try await wait { editor.string.isEmpty }
        try require(editor.undoManager?.canUndo != true && !store.generating && store.sessionID == nil)
        guard let scroll = editor.enclosingScrollView else { throw AtlasError.invalidResponse }
        try await wait { scroll.bounds.height >= 29 && scroll.bounds.height <= 32 }
        window.makeFirstResponder(nil); store.focusComposer()
        try await wait { window.firstResponder === editor }
        let wrapping = String(repeating: "寬度變更與中文自動換行 ", count: 16)
        editor.insertText(wrapping, replacementRange: NSRange(location: NSNotFound, length: 0))
        try await wait { store.draft == wrapping && scroll.bounds.height > 35 && scroll.bounds.height < 140 }
        let wideHeight = scroll.bounds.height
        window.setContentSize(NSSize(width: 920, height: 800))
        try await wait { scroll.bounds.height > wideHeight + 8 && scroll.bounds.height <= 145 }
        window.setContentSize(NSSize(width: 1200, height: 800))
        try await wait { abs(scroll.bounds.height - wideHeight) < 2 }
        store.draft = ""
        try await wait { editor.string.isEmpty && scroll.bounds.height <= 32 }
        let scope = store.selectedScope, history = store.messages
        let longText = (1...20).map { "第\($0)行：這是原生多行編輯、自然換行與捲動驗收。" }.joined(separator: "\n")
        editor.insertText(longText, replacementRange: NSRange(location: NSNotFound, length: 0))
        try await wait { store.draft == longText && scroll.bounds.height >= 130 && scroll.bounds.height <= 145 }
        editor.scrollRangeToVisible(NSRange(location: (longText as NSString).length, length: 0))
        try require(scroll.contentView.bounds.origin.y > 0)
        try await captureWorkspace(store, output, "18-composer-multiline-light", dark: false)
        try await wait { contains(window.contentView, "workspace-sidebar") }
        store.sidebarVisible = false
        try await wait { !contains(window.contentView, "workspace-sidebar") }
        try require(store.draft == longText && store.selectedScope == scope && store.messages == history)
        try await captureWorkspace(store, output, "19-sidebar-hidden-dark", dark: true)
        store.sidebarVisible = true
        try await wait { contains(window.contentView, "workspace-sidebar") }
        store.draft = ""
        try await wait {
            guard let current = find(window.contentView, "chat-composer") as? NSTextView,
                  let currentScroll = current.enclosingScrollView else { return false }
            return current.string.isEmpty && currentScroll.bounds.height >= 29 && currentScroll.bounds.height <= 32
        }
        try require(store.selectedScope == scope && store.messages == history && !store.generating)
    }
    private static func wait(_ condition: () -> Bool) async throws {
        let end = Date().addingTimeInterval(4)
        while !condition(), Date() < end { try await Task.sleep(nanoseconds: 20_000_000) }
        try require(condition())
    }
    private static func find(_ view: NSView?, _ name: String) -> NSView? {
        guard let view else { return nil }
        if view.identifier?.rawValue == name { return view }
        for child in view.subviews { if let match = find(child, name) { return match } }
        return nil
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
