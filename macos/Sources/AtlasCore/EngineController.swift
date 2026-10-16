import Foundation
import Security
import Darwin

public struct EngineSecrets: Codable, Sendable {
    public let token: String
    public let masterKey: String
    public init(token: String, masterKey: String) { self.token = token; self.masterKey = masterKey }
}

public final class KeychainVault {
    private let service: String
    public init(service: String = "dev.modelatlas.mac.engine") { self.service = service }
    private var query: [String: Any] { [kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: service, kSecAttrAccount as String: "engine-v1"] }
    public func loadOrCreate(databaseExists: Bool) throws -> EngineSecrets {
        var lookup = query
        lookup[kSecReturnData as String] = true; lookup[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(lookup as CFDictionary, &result)
        if status == errSecSuccess {
            guard let data = result as? Data, let secrets = try? JSONDecoder().decode(EngineSecrets.self, from: data),
                  secrets.token.count >= 24, Data(base64Encoded: secrets.masterKey)?.count == 32 else { throw AtlasError.keychain(errSecDecode) }
            return secrets
        }
        guard status == errSecItemNotFound else { throw AtlasError.keychain(status) }
        guard !databaseExists else { throw AtlasError.missingVault }
        func random() throws -> String {
            var bytes = [UInt8](repeating: 0, count: 32)
            let code = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
            guard code == errSecSuccess else { throw AtlasError.keychain(code) }
            return Data(bytes).base64EncodedString()
        }
        let secrets = try EngineSecrets(token: random(), masterKey: random())
        var item = query; item[kSecValueData as String] = try JSONEncoder().encode(secrets)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let added = SecItemAdd(item as CFDictionary, nil)
        guard added == errSecSuccess else { throw AtlasError.keychain(added) }
        return secrets
    }
}

@MainActor public final class EngineController {
    public private(set) var process: Process?
    public private(set) var client: AtlasClient?
    public var onUnexpectedExit: (() -> Void)?
    public let directory: URL
    private var lifetime: Pipe?
    private var readyDirectory: URL?
    private var logHandle: FileHandle?
    private var stopping = false

    public init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ModelAtlas", isDirectory: true)
    }
    public func start(executable: URL, secrets: EngineSecrets, additionalEnvironment: [String: String] = [:]) async throws -> AtlasClient {
        if let client, process?.isRunning == true { return client }
        if process != nil { await stop() }
        stopping = false
        let manager = FileManager.default
        try manager.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try manager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
        let temporary = manager.temporaryDirectory.appendingPathComponent("modelatlas-" + UUID().uuidString, isDirectory: true)
        try manager.createDirectory(at: temporary, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        readyDirectory = temporary
        let ready = temporary.appendingPathComponent("ready.json")
        let log = directory.appendingPathComponent("engine.log")
        manager.createFile(atPath: log.path, contents: Data(), attributes: [.posixPermissions: 0o600])
        logHandle = try FileHandle(forWritingTo: log)
        let task = Process(), pipe = Pipe()
        var env = ProcessInfo.processInfo.environment.filter { !$0.key.hasPrefix("MODELATLAS_") && $0.key != "PORT" }
        for (name, value) in additionalEnvironment { env[name] = value }
        env["MODELATLAS_DEPLOYMENT"] = "desktop"; env["MODELATLAS_ADMIN_TOKEN"] = secrets.token
        env["MODELATLAS_MASTER_KEY"] = secrets.masterKey; env["MODELATLAS_DB"] = directory.appendingPathComponent("atlas.sqlite").path
        env["MODELATLAS_DESKTOP_READY"] = ready.path; env["GOMEMLIMIT"] = "192MiB"
        task.executableURL = executable; task.environment = env; task.currentDirectoryURL = directory
        task.standardInput = pipe; task.standardOutput = logHandle; task.standardError = logHandle
        task.terminationHandler = { [weak self] process in
            Task { @MainActor [weak self] in
                guard let self, self.process === process, !self.stopping else { return }
                self.client = nil; self.onUnexpectedExit?()
            }
        }
        lifetime = pipe; process = task
        do {
            try task.run()
            let deadline = Date().addingTimeInterval(20)
            while Date() < deadline && task.isRunning {
                try Task.checkCancellation()
                if let data = try? Data(contentsOf: ready), let value = try? JSONDecoder().decode(JSONValue.self, from: data),
                   value["pid"].numeric == Double(task.processIdentifier), let url = URL(string: value["url"].string) {
                    let api = try AtlasClient(baseURL: url, token: secrets.token)
                    do { _ = try await api.get("/status"); client = api; return api }
                    catch let error as URLError where [.cannotConnectToHost, .networkConnectionLost].contains(error.code) { /* listener is bound; Serve may still be starting */ }
                }
                try await Task.sleep(nanoseconds: 50_000_000)
            }
            throw AtlasError.engineUnavailable
        } catch {
            await stop()
            throw error
        }
    }
    public func stop() async {
        stopping = true
        client = nil
        try? lifetime?.fileHandleForWriting.close(); lifetime = nil
        if let task = process {
            let deadline = Date().addingTimeInterval(17)
            while task.isRunning && Date() < deadline { try? await Task.sleep(nanoseconds: 50_000_000) }
            if task.isRunning {
                task.terminate()
                try? await Task.sleep(nanoseconds: 500_000_000)
                if task.isRunning { Darwin.kill(task.processIdentifier, SIGKILL) }
            }
        }
        process = nil
        try? logHandle?.close(); logHandle = nil
        if let readyDirectory { try? FileManager.default.removeItem(at: readyDirectory) }
        readyDirectory = nil
    }
}
