import AtlasCore
import Foundation
import Security
import XCTest

final class WorkspaceRuntimeTests: XCTestCase {
    private func progress(_ phase: String) { FileHandle.standardError.write(Data(("Native workspace integration: " + phase + "\n").utf8)) }
    @MainActor func testPackagedMacEnginesUseRealTLSForSyncConflictRestartRevocationAndDestinationKey() async throws {
        guard let path = ProcessInfo.processInfo.environment["MODELATLAS_ENGINE_PATH"],
              let fixture = ProcessInfo.processInfo.environment["MODELATLAS_FIXTURE_URL"] else {
            throw XCTSkip("Requires packaged native engines and disposable trusted local TLS fixture")
        }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let services = ["dev.modelatlas.test.workspace." + UUID().uuidString, "dev.modelatlas.test.workspace." + UUID().uuidString]
        defer {
            try? FileManager.default.removeItem(at: directory)
            for service in services { SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "engine-v1"] as CFDictionary) }
        }
        let center = EngineController(directory: directory.appendingPathComponent("center"))
        let peer = EngineController(directory: directory.appendingPathComponent("peer"))
        let centerSecrets = try KeychainVault(service: services[0]).loadOrCreate(databaseExists: false)
        let peerSecrets = try KeychainVault(service: services[1]).loadOrCreate(databaseExists: false)
        progress("start independent engines and Keychain vaults")
        let hub = try await center.start(executable: URL(fileURLWithPath: path), secrets: centerSecrets)
        do {
            var api = try await peer.start(executable: URL(fileURLWithPath: path), secrets: peerSecrets)
            var target = URLRequest(url: URL(string: fixture + "/fixture/workspace-target")!)
            target.httpMethod = "POST"; target.setValue("application/json", forHTTPHeaderField: "Content-Type")
            target.httpBody = try JSONEncoder().encode(JSONValue.object(["url": .string(hub.baseURL.absoluteString)]))
            let (_, response) = try await URLSession.shared.data(for: target)
            XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 204)
            _ = try await hub.call("/providers", method: "POST", body: .object(["id": .string("p"), "name": .string("Hub provider"), "base_url": .string(fixture + "/v1"), "auth_mode": .string("BEARER")]))
            _ = try await hub.call("/accounts", method: "POST", body: .object(["provider_id": .string("p"), "id": .string("a"), "label": .string("Workspace account")]))
            _ = try await hub.call("/credentials", method: "POST", body: .object(["provider_id": .string("p"), "account_id": .string("a"), "id": .string("c"), "secret": .string("native-fixture-provider-key")]))
            _ = try await hub.call("/models", method: "POST", body: .object(["provider_id": .string("p"), "id": .string("native-fixture-chat"), "display_name": .string("Native sync model")]))
            let scope: JSONValue = .object(["provider_id": .string("p"), "account_id": .string("a"), "credential_id": .string("c"), "model_id": .string("native-fixture-chat"), "group": .string(""), "endpoint": .string("CHAT_COMPLETIONS")])
            _ = try await hub.call("/my-models", method: "POST", body: scope)
            progress("pair and synchronize through trusted HTTPS")
            let pair = try await hub.call("/workspace/pair", method: "POST", body: .object(["share_secrets": .bool(true)]))
            let draft = try WorkspaceDraft(url: fixture, label: "原生 Mac", code: pair["code"].string)
            _ = try await api.call("/workspace/connect", method: "POST", body: draft.body)
            _ = try await api.call("/workspace/sync", method: "POST", body: .object([:]))
            let firstReceipt = try await api.get("/workspace")
            XCTAssertTrue(firstReceipt["peer"]["resumable"].boolean)
            XCTAssertTrue((firstReceipt["transport_receipts"]["outgoing_acked"].numeric ?? 0) > 0, "Native Settings draft must use the real durable transfer path")
            let imported = try await api.get("/my-models").requiredArray()
            XCTAssertEqual(imported.count, 1)
            guard let first = imported.first else { throw AtlasError.invalidResponse }
            XCTAssertEqual(ModelScope(first).credentialID, "c")
            let samples = try await api.get("/telemetry").requiredArray()
            XCTAssertTrue(samples.isEmpty, "Pairing and synchronization must not run inference")
            // A real authenticated provider request proves the destination can
            // decrypt the imported key using its independently created vault.
            progress("use destination-encrypted secret and synchronize retained messages")
            let conversation = try await api.call("/sessions", method: "POST", body: scope)
            var terminal = ""
            try await api.stream("/infer", body: .object(["session_id": conversation["id"], "prompt": .string("Native sync fixture")])) { event in
                if ["done", "error"].contains(event.name) { terminal = event.name }
            }
            XCTAssertEqual(terminal, "done")
            _ = try await api.call("/workspace/sync", method: "POST", body: .object([:]))
            let retained = try await hub.get("/messages", query: [URLQueryItem(name: "session_id", value: conversation["id"].string)]).requiredArray()
            XCTAssertEqual(retained.count, 2)
            func rename(_ client: AtlasClient, _ name: String) async throws {
                let preview = try await client.call("/assistant/actions/preview", method: "POST", body: .object(["kind": .string("PROVIDER"), "provider_id": .string("p"), "patch": .object(["name": .string(name)])]))
                _ = try await client.call("/assistant/actions/confirm", method: "POST", body: .object(["id": preview["id"], "confirm": .bool(true)]))
            }
            progress("preserve both offline edits and resolve explicitly")
            try await rename(api, "Mac offline"); try await rename(hub, "Hub offline")
            _ = try await api.call("/workspace/sync", method: "POST", body: .object([:]))
            let status = try await api.get("/workspace")
            XCTAssertEqual(status["conflict_count"].numeric, 1)
            guard let item = status["conflicts"].array.first else { throw AtlasError.invalidResponse }
            let conflictID = item["id"].string
            let detail = try await api.get("/workspace/conflict", query: [URLQueryItem(name: "id", value: conflictID)])
            XCTAssertEqual(detail["local"]["data"]["name"].string, "Mac offline")
            XCTAssertEqual(detail["remote"]["data"]["name"].string, "Hub offline")
            _ = try await api.call("/workspace/resolve", method: "POST", body: .object(["id": .string(conflictID), "local_hash": detail["local_hash"], "choice": .string("remote"), "confirm": .bool(true)]))
            _ = try await api.call("/workspace/sync", method: "POST", body: .object([:]))
            progress("restart with retained Keychain and cursor")
            await peer.stop()
            let restored = try KeychainVault(service: services[1]).loadOrCreate(databaseExists: true)
            api = try await peer.start(executable: URL(fileURLWithPath: path), secrets: restored)
            let providers = try await api.get("/providers").requiredArray()
            XCTAssertEqual((providers.first ?? .null)["name"].string, "Hub offline")
            let restoredStatus = try await api.get("/workspace")
            XCTAssertTrue(restoredStatus["peer"]["resumable"].boolean, "Transport preference must survive the actual engine restart")
            XCTAssertTrue((restoredStatus["transport_receipts"]["outgoing_acked"].numeric ?? 0) > 0, "ACK must retain its metadata receipt")
            XCTAssertTrue((restoredStatus["peer"]["cursor"].numeric ?? 0) > 0)
            let devices = try await hub.get("/workspace")
            progress("revoke device and retain local history")
            guard let device = devices["devices"].array.first else { throw AtlasError.invalidResponse }
            _ = try await hub.call("/workspace/revoke", method: "POST", body: .object(["device_id": device["id"], "confirm": .bool(true)]))
            do { _ = try await api.call("/workspace/sync", method: "POST", body: .object([:])); XCTFail("Revoked device must not synchronize") } catch { }
            let local = try await api.get("/messages", query: [URLQueryItem(name: "session_id", value: conversation["id"].string)]).requiredArray()
            XCTAssertEqual(local.count, 2)
            let models = try await api.get("/my-models").requiredArray(); XCTAssertEqual(models.count, 1)
            _ = try await api.call("/workspace/disconnect", method: "POST", body: .object(["confirm": .bool(true)]))
            let disconnected = try await api.get("/workspace"); XCTAssertEqual(disconnected["peer"], .null)
        } catch { await peer.stop(); await center.stop(); throw error }
        await peer.stop(); await center.stop()
    }
}
