import AtlasCore
import Foundation
import XCTest

final class KiloFactoryTests: XCTestCase {
    func testFactoryPreflightFailuresExplainStoppedRequestWithoutInventingProviderHTTPOrReceipt() throws {
        for code in ["KILO_CATALOG_UNAVAILABLE", "KILO_FREE_MODEL_UNAVAILABLE", "KILO_DEFAULT_CONNECTION_CHANGED"] {
            let problem = try XCTUnwrap(ProviderProblem(.object(["error": .object(["code": .string(code), "upstream_status": .number(0), "operation": .string("inference")])])))
            XCTAssertEqual(problem.upstreamStatus, 0)
            XCTAssertTrue(problem.localizedDescription.contains("Kilo"))
            XCTAssertFalse(problem.localizedDescription.contains("收據"))
        }
    }
    @MainActor func testFreshPackagedEngineInstallsOfflineDefaultsAndKeepsRemovalAcrossRestart() async throws {
        guard let path = ProcessInfo.processInfo.environment["MODELATLAS_ENGINE_PATH"] else { throw XCTSkip("Requires packaged engine") }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let engine = EngineController(directory: directory)
        let secrets = EngineSecrets(token: "factory-test-" + UUID().uuidString, masterKey: Data(repeating: 7, count: 32).base64EncodedString())
        defer { try? FileManager.default.removeItem(at: directory) }
        do {
            let api = try await engine.start(executable: URL(fileURLWithPath: path), secrets: secrets, factoryDefaults: true)
            let info = try await api.get("/defaults")
            let models = try await api.get("/my-models").requiredArray()
            XCTAssertTrue(info["installed"].boolean); XCTAssertEqual(models.count, 15)
            XCTAssertTrue(models.allSatisfy { $0["provider_id"].string == "builtin-kilo-anonymous" && $0["price_state"].string == "UNKNOWN" })
            let telemetry = try await api.get("/telemetry").requiredArray()
            let sessions = try await api.get("/sessions").requiredArray()
            XCTAssertTrue(telemetry.isEmpty); XCTAssertTrue(sessions.isEmpty)
            let selected = try XCTUnwrap(models.first { $0["model_id"].string == "nvidia/nemotron-3.5-lightning:free" })
            _ = try await api.call("/my-models", method: "DELETE", body: ModelScope(selected).json)
            await engine.stop()
            let restarted = try await engine.start(executable: URL(fileURLWithPath: path), secrets: secrets, factoryDefaults: true)
            let after = try await restarted.get("/my-models").requiredArray()
            XCTAssertEqual(after.count, 14); XCTAssertFalse(after.contains { ModelScope($0) == ModelScope(selected) })
            await engine.stop()
        } catch { await engine.stop(); throw error }
    }

    @MainActor func testExistingPackagedWorkspaceNeedsExplicitAdditionAndRetainsItsAccount() async throws {
        guard let path = ProcessInfo.processInfo.environment["MODELATLAS_ENGINE_PATH"] else { throw XCTSkip("Requires packaged engine") }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let engine = EngineController(directory: directory)
        let secrets = EngineSecrets(token: "factory-test-" + UUID().uuidString, masterKey: Data(repeating: 8, count: 32).base64EncodedString())
        defer { try? FileManager.default.removeItem(at: directory) }
        do {
            let first = try await engine.start(executable: URL(fileURLWithPath: path), secrets: secrets)
            _ = try await first.call("/providers", method: "POST", body: .object(["id": .string("personal"), "name": .string("Original account"), "base_url": .string("https://existing.example/v1"), "auth_mode": .string("BEARER")]))
            _ = try await first.call("/accounts", method: "POST", body: .object(["provider_id": .string("personal"), "id": .string("personal")]))
            _ = try await first.call("/credentials", method: "POST", body: .object(["provider_id": .string("personal"), "account_id": .string("personal"), "id": .string("personal"), "secret": .string("only-a-synthetic-test-key")]))
            await engine.stop()
            let api = try await engine.start(executable: URL(fileURLWithPath: path), secrets: secrets, factoryDefaults: true)
            let before = try await api.get("/defaults")
            let originalKeys = try await api.get("/credentials").requiredArray()
            XCTAssertFalse(before["installed"].boolean); XCTAssertEqual(originalKeys.count, 1)
            _ = try await api.call("/defaults/kilo", method: "POST", body: .object(["confirm": .bool(true)]))
            let providers = try await api.get("/providers").requiredArray()
            let keys = try await api.get("/credentials").requiredArray()
            XCTAssertEqual(providers.count, 2); XCTAssertEqual(keys.filter { $0["provider_id"].string == "personal" }, originalKeys)
            let models = try await api.get("/my-models").requiredArray()
            XCTAssertEqual(models.count, 15)
            let telemetry = try await api.get("/telemetry").requiredArray()
            XCTAssertTrue(telemetry.isEmpty)
            await engine.stop()
        } catch { await engine.stop(); throw error }
    }
}
