import AtlasCore
import SwiftUI

struct KiloFactoryDefaultsCard: View {
    @ObservedObject var store: AtlasStore
    @State private var info: JSONValue?
    @State private var readRequired = false
    @State private var pending = false
    var body: some View {
        AtlasCard {
            Text("Kilo 免Key預設模型").font(.headline)
            Text("加入預載的公開模型與匿名連線；不自動聊天，也不更動原有帳戶、偏好與已移除模型。").font(.caption).foregroundStyle(.secondary)
            if let info {
                Text(info["model_count"].display + " 個預載模型 · 來源 " + info["source_url"].string).font(.caption).textSelection(.enabled)
                AtlasLoadedMarker(name: info["installed"].boolean ? "kilo-defaults-installed" : "kilo-defaults-available").frame(height: 0)
            }
            if readRequired { Text("結果尚未確認，請先重新讀取已保存狀態。").font(.callout).foregroundStyle(.red) }
            ActionButton(title: info?["installed"].boolean == true ? "已加入Kilo預設模型" : "加入Kilo免費預設") {
                guard let api = store.api, !pending, !readRequired else { return }
                pending = true; defer { pending = false }
                do {
                    _ = try await api.call("/defaults/kilo", method: "POST", body: .object(["confirm": .bool(true)]))
                    info = try await api.get("/defaults")
                    try await store.reload()
                } catch { readRequired = true; throw error }
            }.disabled(pending || readRequired || info == nil || info?["installed"].boolean == true)
            ActionButton(title: "重新讀取預設資料") { try await read() }.disabled(pending)
            Text("免Key來源可能保存對話並用於改善模型；請勿輸入個人或機密資料。預載資料不是實測，送出前會核對公開目錄。").font(.caption).foregroundStyle(.secondary)
        }.task { try? await read() }
    }
    private func read() async throws {
        guard let api = store.api, !pending else { return }
        pending = true; defer { pending = false }
        let recovering = readRequired
        do {
            info = try await api.get("/defaults")
            if recovering && info?["installed"].boolean == true { try await store.reload() }
            readRequired = false
        }
        catch { readRequired = true; throw error }
    }
}
