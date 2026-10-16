import AtlasCore
import Foundation
import Security
import XCTest

final class AtlasCoreTests: XCTestCase {
    func testToolCallIDCanRepeatAfterAnEarlierCompletedTurn() throws {
        let old = try JSONValue.parse("{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call_1\",\"function\":{\"name\":\"old\"}}]}")
        let result = try JSONValue.parse("{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"old result\"}")
        let current = try JSONValue.parse("{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call_1\",\"function\":{\"name\":\"new\"}}]}")
        let pending = ConversationTools.pending(in: [old, result, current])
        XCTAssertEqual(pending.count, 1); XCTAssertEqual(pending[0]["function"]["name"].string, "new")
        XCTAssertTrue(ConversationTools.pending(in: [old, result]).isEmpty)
    }
    func testSSEMultilineUnicodeCommentsAndBounds() throws {
        var decoder = EventDecoder()
        XCTAssertNil(try decoder.consume(": heartbeat"))
        XCTAssertNil(try decoder.consume("event: delta\r"))
        XCTAssertNil(try decoder.consume("data: {\"text\":"))
        XCTAssertNil(try decoder.consume("data: \"原生 Mac ✨\"}"))
        let event = try XCTUnwrap(decoder.consume(""))
        XCTAssertEqual(event.name, "delta"); XCTAssertEqual(event.value["text"].string, "原生 Mac ✨")
        XCTAssertThrowsError(try decoder.consume("data: " + String(repeating: "a", count: 1_048_577)))
    }
    func testScopeNeverCollapsesSameKeyIDsAcrossAccountsOrEndpoints() throws {
        let a = try JSONValue.parse("{\"provider_id\":\"p\",\"account_id\":\"a\",\"credential_id\":\"same\",\"model_id\":\"m\",\"group\":\"free\",\"endpoint\":\"CHAT_COMPLETIONS\"}")
        var b = a; b["account_id"] = .string("b")
        var c = a; c["endpoint"] = .string("RESPONSES")
        XCTAssertEqual(Set([ModelScope(a), ModelScope(b), ModelScope(c)]).count, 3)
        XCTAssertNotEqual(ModelScope(a).id, ModelScope(b).id)
        XCTAssertEqual(try JSONDecoder().decode(ModelScope.self, from: JSONEncoder().encode(ModelScope(a))), ModelScope(a))
        XCTAssertEqual(JSONValue.null.display, "尚無資料")
        XCTAssertNotEqual(JSONValue.null.display, JSONValue.number(0).display)
    }
    func testKeychainPersistsAndNeverReplacesMissingDatabaseKey() throws {
        let service = "dev.modelatlas.ci." + UUID().uuidString
        defer { SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service] as CFDictionary) }
        let vault = KeychainVault(service: service)
        XCTAssertThrowsError(try vault.loadOrCreate(databaseExists: true))
        let first = try vault.loadOrCreate(databaseExists: false)
        let second = try vault.loadOrCreate(databaseExists: true)
        XCTAssertEqual(first.token, second.token); XCTAssertEqual(first.masterKey, second.masterKey)
        XCTAssertEqual(Data(base64Encoded: first.masterKey)?.count, 32)
        XCTAssertGreaterThanOrEqual(first.token.count, 24)
    }
    func testManagementClientOnlyAcceptsFixedLoopbackOrigins() throws {
        for raw in ["https://elsewhere.example", "http://localhost:8000", "http://0.0.0.0:8000", "http://127.0.0.1:8000/path", "http://a:b@127.0.0.1:8000"] {
            XCTAssertThrowsError(try AtlasClient(baseURL: URL(string: raw)!, token: "test"))
        }
    }
    @MainActor func testPackagedEnginePersistenceScopeSSEAndCancellation() async throws {
        guard let path = ProcessInfo.processInfo.environment["MODELATLAS_ENGINE_PATH"], let fixture = ProcessInfo.processInfo.environment["MODELATLAS_FIXTURE_URL"] else { throw XCTSkip("Requires the built .app and the local TLS fixture") }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let secrets = EngineSecrets(token: "mac-integration-admin-token-12345", masterKey: Data(repeating: 17, count: 32).base64EncodedString())
        let engine = EngineController(directory: directory)
        let api = try await engine.start(executable: URL(fileURLWithPath: path), secrets: secrets)
        do {
            let (_, response) = try await URLSession.shared.data(from: api.baseURL.appendingPathComponent("api/v1/status"))
            XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 401)
            _ = try await api.call("/providers", method: "POST", body: .object(["id": .string("p"), "name": .string("Native integration"), "base_url": .string(fixture + "/v1"), "auth_mode": .string("BEARER")]))
            for account in ["a", "b"] {
                _ = try await api.call("/accounts", method: "POST", body: .object(["id": .string(account), "provider_id": .string("p"), "label": .string(account)]))
                _ = try await api.call("/credentials", method: "POST", body: .object(["id": .string("same"), "provider_id": .string("p"), "account_id": .string(account), "secret": .string(account == "a" ? "native-fixture-provider-key" : "fixture-other-account-key")]))
            }
            _ = try await api.call("/models/sync", method: "POST", body: .object(["provider_id": .string("p"), "account_id": .string("a"), "credential_id": .string("same")]))
            let before = try await api.get("/my-models").requiredArray(); XCTAssertTrue(before.isEmpty)
            var scope: JSONValue = .object(["provider_id": .string("p"), "account_id": .string("a"), "credential_id": .string("same"), "model_id": .string("native-fixture-chat"), "group": .string(""), "endpoint": .string("CHAT_COMPLETIONS")])
            _ = try await api.call("/my-models", method: "POST", body: scope)
            var wrong = scope; wrong["account_id"] = .string("b")
            do { _ = try await api.call("/sessions", method: "POST", body: wrong); XCTFail("Unjoined account scope was accepted") } catch AtlasError.http(let status) { XCTAssertEqual(status, 422) }
            let conversation = try await api.call("/sessions", method: "POST", body: scope)
            let id = conversation["id"].string
            var text = "", terminal = ""
            try await api.stream("/infer", body: .object(["session_id": .string(id), "prompt": .string("原生聊天測試")])) { event in
                if event.name == "delta" { text += event.value["text"].string }
                if ["done", "error"].contains(event.name) { terminal = event.name }
            }
            XCTAssertEqual(terminal, "done"); XCTAssertTrue(text.contains("這是原生 Mac 工作區"))
            let messages = try await api.get("/messages", query: [URLQueryItem(name: "session_id", value: id)]).requiredArray()
            XCTAssertEqual(messages.count, 2); XCTAssertEqual(messages.last?["content"].string, text)
            _ = try await api.call("/credentials/toggle", method: "POST", body: .object(["provider_id": .string("p"), "account_id": .string("b"), "ids": .array([.string("same")]), "enabled": .bool(false)]))
            let credentials = try await api.get("/credentials").requiredArray()
            XCTAssertTrue(credentials.first { $0["account_id"].string == "a" }?["enabled"].boolean == true)
            XCTAssertTrue(credentials.first { $0["account_id"].string == "b" }?["enabled"].boolean == false)
            XCTAssertFalse(JSONValue.array(credentials).pretty.contains("native-fixture-provider-key"))
            var lifecycle = scope; lifecycle["state"] = .string("HIDDEN"); lifecycle["favorite"] = .bool(true); lifecycle["watchlist"] = .bool(false)
            _ = try await api.call("/models/lifecycle", method: "POST", body: lifecycle)
            let retained = try await api.get("/messages", query: [URLQueryItem(name: "session_id", value: id)]).requiredArray()
            XCTAssertEqual(retained.count, 2)
            let export = directory.appendingPathComponent("conversation.json")
            try await api.download("/sessions/export", query: [URLQueryItem(name: "session_id", value: id), URLQueryItem(name: "format", value: "json")], to: export)
            XCTAssertTrue(try String(contentsOf: export).contains(text))
            scope["model_id"] = .string("native-fixture-slow")
            _ = try await api.call("/my-models", method: "POST", body: scope)
            let slow = try await api.call("/sessions", method: "POST", body: scope)
            var began = false, cancelled = false
            let stream = Task { try await api.stream("/infer", body: .object(["session_id": slow["id"], "prompt": .string("等候取消")])) { event in
                if event.name == "start" { began = true }
                if event.name == "error", event.value["category"].string == "CANCELLED" { cancelled = true }
            } }
            let deadline = Date().addingTimeInterval(5)
            while !began && Date() < deadline { try await Task.sleep(nanoseconds: 20_000_000) }
            XCTAssertTrue(began)
            _ = try await api.call("/infer/cancel", method: "POST", body: .object(["session_id": slow["id"]]))
            try await stream.value; XCTAssertTrue(cancelled)
            let child = try XCTUnwrap(engine.process)
            await engine.stop(); XCTAssertFalse(child.isRunning)
            let reopened = try await engine.start(executable: URL(fileURLWithPath: path), secrets: secrets)
            let history = try await reopened.get("/messages", query: [URLQueryItem(name: "session_id", value: id)]).requiredArray()
            XCTAssertEqual(history.count, 2)
            _ = try await reopened.call("/models/sync", method: "POST", body: .object(["provider_id": .string("p"), "account_id": .string("a"), "credential_id": .string("same")]))
            let duplicate = EngineController(directory: directory)
            do { _ = try await duplicate.start(executable: URL(fileURLWithPath: path), secrets: secrets); XCTFail("Second writer was accepted") } catch { XCTAssertTrue(engine.process?.isRunning == true) }
            await duplicate.stop()
            let prefs = try await reopened.get("/settings")
            XCTAssertFalse(prefs["notifications"].boolean); XCTAssertFalse(prefs["background"]["enabled"].boolean)
            await engine.stop()
        } catch { await engine.stop(); throw error }
    }
    @MainActor func testClientRefusesRedirectsAndUnfinishedEventStreams() async throws {
        guard let raw = ProcessInfo.processInfo.environment["MODELATLAS_PLAIN_FIXTURE_URL"], let url = URL(string: raw) else { throw XCTSkip("Requires local fixture") }
        let api = try AtlasClient(baseURL: url, token: "synthetic-management-value")
        do { _ = try await api.get("/redirect"); XCTFail("Followed a redirect") } catch AtlasError.http(let status) { XCTAssertEqual(status, 302) }
        let stats = try await api.get("/stats"); XCTAssertEqual(stats["redirected"].numeric, 0)
        do { try await api.stream("/truncated", body: .object([:])) { _ in }; XCTFail("Truncated stream was marked done") } catch AtlasError.invalidResponse { }
    }
}
