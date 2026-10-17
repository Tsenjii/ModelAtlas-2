import Foundation

public struct DraftProblem: LocalizedError {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
}

public struct SpeechLine: Identifiable, Equatable, Sendable {
    public let id = UUID()
    public var speaker = "Joe"
    public var text = ""
    public var style = ""
    public init(speaker: String = "Joe", text: String = "", style: String = "") { self.speaker = speaker; self.text = text; self.style = style }
}

/// A value snapshot is captured before a request starts. A subsequent draft can
/// never change the in-flight voice, vector options, speakers or documents.
public struct MediaDraft: Equatable, Sendable {
    public var voice = "Kore", format = "wav", speed = 1.0, instructions = ""
    public var dialect = "CURRENT", dual = false
    public var firstName = "Joe", firstVoice = "Puck", secondName = "Jane", secondVoice = "Kore"
    public var turns: [SpeechLine] = [SpeechLine()]
    public var documents = "", dimensions = "", topN = "", size = "1024x1024"
    public init() {}
    public static let operations = ["IMAGES_GENERATION", "IMAGES_EDIT", "AUDIO", "AUDIO_SPEECH", "GEMINI_SPEECH", "EMBEDDINGS", "RERANK"]
    public func hasInput(operation: String, prompt: String, files: [JSONValue]) -> Bool {
        if operation == "AUDIO" { return files.contains { $0["kind"].string == "audio" } }
        if operation == "GEMINI_SPEECH" && dual { return turns.contains { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } }
        return !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
    public func request(scope: ModelScope, operation: String, prompt: String, files: [JSONValue], requestID: String) throws -> JSONValue {
        guard Self.operations.contains(operation), prompt.utf8.count <= (operation == "EMBEDDINGS" ? 131072 : 32000) else { throw DraftProblem("描述最多 32 KB。") }
        var body = scope.json
        body["prompt"] = .string(prompt); body["request_id"] = .string(requestID)
        switch operation {
        case "IMAGES_GENERATION": body["size"] = .string(size)
        case "IMAGES_EDIT", "AUDIO":
            let kind = operation == "AUDIO" ? "audio" : "image"
            guard let file = files.first(where: { $0["kind"].string == kind }) else { throw DraftProblem(kind == "audio" ? "請先加入 WAV 或 MP3 音訊。" : "請先加入要編輯的圖片。") }
            body[operation == "AUDIO" ? "audio" : "image"] = LocalAttachment.requestValue(file)
        case "AUDIO_SPEECH", "GEMINI_SPEECH":
            guard instructions.utf8.count <= 4096 else { throw DraftProblem("說話方式最多 4 KB。") }
            guard !voice.isEmpty, voice.utf8.count <= 64 else { throw DraftProblem("請填入有效的聲音名稱。") }
            body["voice"] = .string(voice); body["format"] = .string(format)
            body["instructions"] = .string(instructions)
            if operation == "AUDIO_SPEECH" {
                guard prompt.utf8.count <= 4096, (0.25...4).contains(speed) else { throw DraftProblem("語音文字最多 4 KB；語速範圍為 0.25–4。") }
                body["speed"] = .number(speed)
            } else {
                guard ["wav", "pcm"].contains(format), dual || (!prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && prompt.utf8.count <= 4096) else { throw DraftProblem("Gemini 語音文字最多 4 KB；格式為 WAV 或 PCM。") }
                body["speech_dialect"] = .string(dialect)
                if dual {
                    guard dialect == "CURRENT", !firstName.isEmpty, !secondName.isEmpty, firstName != secondName else { throw DraftProblem("雙人語音需要目前格式及兩個不同的說話者名稱。") }
                    guard !turns.isEmpty, turns.count <= 64, turns.allSatisfy({ [firstName, secondName].contains($0.speaker) && !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else { throw DraftProblem("請為每一段選擇說話者並填入文字，最多 64 段。") }
                    guard turns.reduce(0, { $0 + $1.text.utf8.count + $1.style.utf8.count }) <= 4096, turns.allSatisfy({ $0.style.utf8.count <= 512 }) else { throw DraftProblem("雙人對話文字及語氣合計最多 4 KB，每段語氣最多 512 bytes。") }
                    body["speakers"] = .array([.object(["name": .string(firstName), "voice": .string(firstVoice)]), .object(["name": .string(secondName), "voice": .string(secondVoice)])])
                    body["turns"] = .array(turns.map { .object(["speaker": .string($0.speaker), "text": .string($0.text), "style": .string($0.style)]) })
                    body["prompt"] = .string(""); body["instructions"] = .string("")
                }
            }
        case "EMBEDDINGS", "RERANK":
            let lines = (operation == "EMBEDDINGS" ? prompt : documents).components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
            guard (1...32).contains(lines.count), lines.allSatisfy({ $0.utf8.count <= 32000 }) else { throw DraftProblem("請填入 1–32 筆文字，每筆最多 32 KB。") }
            body[operation == "EMBEDDINGS" ? "inputs" : "documents"] = .array(lines.map(JSONValue.string))
            if operation == "EMBEDDINGS" {
                body["prompt"] = .string("")
                if !dimensions.isEmpty { guard let n = Int(dimensions), (1...8192).contains(n) else { throw DraftProblem("向量維度需為 1–8192 的整數。") }; body["dimensions"] = .number(Double(n)) }
            } else if !topN.isEmpty { guard let n = Int(topN), (1...lines.count).contains(n) else { throw DraftProblem("回傳筆數需介於 1 與文件數量之間。") }; body["top_n"] = .number(Double(n)) }
        default: break
        }
        return body
    }
    public static func filename(mime: String) -> String {
        "ModelAtlas." + (["image/png":"png", "image/jpeg":"jpg", "image/webp":"webp", "audio/wav":"wav", "audio/x-wav":"wav", "audio/mpeg":"mp3", "audio/mp3":"mp3", "audio/ogg":"opus", "audio/opus":"opus", "audio/flac":"flac", "audio/aac":"aac", "audio/pcm":"pcm", "audio/L16":"pcm"][mime] ?? "bin")
    }
}
