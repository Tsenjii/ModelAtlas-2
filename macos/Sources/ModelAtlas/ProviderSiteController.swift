import AtlasCore
import Foundation
import SwiftUI

@MainActor final class ProviderSiteController: ObservableObject {
    let providerID: String
    let modelID: String
    private(set) var origin: String?
    @Published private(set) var values: [String: ProviderSiteSnapshot] = [:]
    @Published private(set) var problems: [String: String] = [:]
    @Published private(set) var loading = Set<String>()
    @Published private(set) var didLoad = false
    @Published private(set) var requestedHours = 168
    private var task: Task<Void, Never>?
    private var revision = 0
    init(providerID: String, modelID: String = "", origin: String? = nil) { self.providerID = providerID; self.modelID = modelID; self.origin = origin }
    func setOrigin(_ origin: String) {
        guard self.origin != origin else { return }
        cancel(); values = [:]; problems = [:]; didLoad = false; self.origin = origin
    }
    func cancel() {
        let interrupted = !loading.isEmpty
        revision += 1; task?.cancel(); task = nil; loading = []
        if interrupted { didLoad = false }
    }
    func refreshIfNeeded(using api: AtlasClient, operations: [String]) { if !didLoad && loading.isEmpty { refresh(using: api, operations: operations, hours: requestedHours) } }
    func refresh(using api: AtlasClient, operations: [String], hours: Int = 168) {
        if operations.contains("performance") || operations.contains("summary") { requestedHours = hours }
        cancel(); let ticket = revision; loading = Set(operations)
        for name in operations { problems.removeValue(forKey: name) }
        task = Task { [weak self] in
            guard let self else { return }
            defer { if revision == ticket { loading = []; didLoad = true; task = nil } }
            // Show exact last-good public snapshots while a refresh is running.
            if values.isEmpty, let cached = try? await api.get("/newapi/snapshots", query: [URLQueryItem(name: "provider_id", value: providerID)]) {
                guard revision == ticket, !Task.isCancelled else { return }
                for operation in operations {
                    if let saved = cached.array.compactMap({ try? ProviderSiteSnapshot($0, providerID: self.providerID, operation: operation, modelID: self.modelID, origin: self.origin) }).max(by: { $0.at < $1.at }) { values[operation] = saved }
                }
            }
            for operation in operations {
                guard revision == ticket, !Task.isCancelled else { return }
                do {
                    let body = try ProviderSiteSnapshot.request(providerID: providerID, operation: operation, modelID: modelID, hours: hours)
                    let raw = try await api.call("/newapi/" + operation, method: "POST", body: body)
                    try Task.checkCancellation()
                    guard revision == ticket else { return }
                    values[operation] = try ProviderSiteSnapshot(raw, providerID: self.providerID, operation: operation, modelID: self.modelID, origin: self.origin)
                } catch is CancellationError { return }
                catch { if revision == ticket && !Task.isCancelled { problems[operation] = error.localizedDescription } }
                if revision == ticket { loading.remove(operation) }
            }
        }
    }
}
