import Foundation

public struct ChatSettingsDraft: Equatable, Sendable {
    public var title: String
    public var system: String
    public var maxTokens: String
    public var streaming: Bool
    public var toolsJSON: String
    public init(title: String = "", system: String = "", maxTokens: String = "512", streaming: Bool = true, toolsJSON: String = "[]") {
        self.title = title; self.system = system; self.maxTokens = maxTokens
        self.streaming = streaming; self.toolsJSON = toolsJSON
    }
    public func validated() throws -> (tokens: Int, tools: JSONValue) {
        guard let tokens = Int(maxTokens.trimmingCharacters(in: .whitespacesAndNewlines)), (1...32768).contains(tokens) else { throw ChatSettingsValidation.tokenLimit }
        guard !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              title == title.trimmingCharacters(in: .whitespacesAndNewlines), title.utf8.count <= 200,
              !title.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) || $0.properties.generalCategory == .format }) else { throw ChatSettingsValidation.title }
        guard let tools = try? JSONValue.parse(toolsJSON) else { throw ChatSettingsValidation.toolsJSON }
        guard case .array = tools else { throw ChatSettingsValidation.toolsArray }
        return (tokens, tools)
    }
}

public enum ChatSettingsValidation: Error, LocalizedError, Equatable {
    case tokenLimit, title, toolsJSON, toolsArray
    public var errorDescription: String? {
        switch self {
        case .tokenLimit: return "回應 Token 上限請輸入 1–32768 的整數。"
        case .title: return "對話名稱不可空白、含換行、隱藏控制字元或首尾空白；過長時請縮短名稱。"
        case .toolsJSON: return "工具定義的 JSON 格式不正確，請檢查括號、逗號與引號。"
        case .toolsArray: return "工具定義必須是 JSON 陣列；不使用工具時請填 []。"
        }
    }
}
