import Foundation

public enum LocalAttachmentError: Error, LocalizedError {
    case unsupported, size, duplicate, signature
    public var errorDescription: String? {
        switch self {
        case .unsupported: return "請加入 PNG、JPEG、WebP、PDF、WAV 或 MP3 本機檔案。"
        case .size: return "附件不能為空，每份上限為 4 MiB。"
        case .duplicate: return "每則訊息可各加入一份圖片、PDF 和音訊。請先移除同類附件。"
        case .signature: return "附件內容與副檔名不符，請確認檔案格式。"
        }
    }
}

public enum LocalAttachment {
    /// Only the four fields accepted by the engine's strict Attachment decoder.
    /// Display metadata remains local, including fields added by future UI.
    public static func requestValue(_ value: JSONValue) -> JSONValue {
        .object(Dictionary(uniqueKeysWithValues: ["kind", "filename", "mime", "data"].map { ($0, value[$0]) }))
    }
    /// Atomic batch: callers keep the original tray when any file is invalid.
    public static func adding(_ urls: [URL], to existing: [JSONValue]) throws -> [JSONValue] {
        var result = existing
        let formats = ["png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg", "webp": "image/webp", "pdf": "application/pdf", "wav": "audio/wav", "mp3": "audio/mpeg"]
        for url in urls {
            guard url.isFileURL, let mime = formats[url.pathExtension.lowercased()] else { throw LocalAttachmentError.unsupported }
            let kind = mime == "application/pdf" ? "pdf" : mime.hasPrefix("audio/") ? "audio" : "image"
            guard !result.contains(where: { $0["kind"].string == kind }) else { throw LocalAttachmentError.duplicate }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
            guard values.isRegularFile == true, let size = values.fileSize, size > 0, size <= 4 << 20 else { throw LocalAttachmentError.size }
            let handle = try FileHandle(forReadingFrom: url)
            defer { try? handle.close() }
            let data = try handle.read(upToCount: (4 << 20) + 1) ?? Data()
            guard !data.isEmpty, data.count <= 4 << 20 else { throw LocalAttachmentError.size }
            guard validSignature(data, mime: mime) else { throw LocalAttachmentError.signature }
            result.append(.object(["kind": .string(kind), "filename": .string(url.lastPathComponent), "mime": .string(mime), "size_bytes": .number(Double(data.count)), "data": .string(data.base64EncodedString())]))
        }
        return result
    }
    private static func validSignature(_ data: Data, mime: String) -> Bool {
        let b = [UInt8](data.prefix(12))
        switch mime {
        case "image/png": return b.starts(with: [137,80,78,71,13,10,26,10])
        case "image/jpeg": return b.starts(with: [255,216,255])
        case "image/webp": return b.count >= 12 && String(bytes: b[0..<4], encoding: .ascii) == "RIFF" && String(bytes: b[8..<12], encoding: .ascii) == "WEBP"
        case "application/pdf": return b.starts(with: Array("%PDF-".utf8))
        case "audio/wav": return b.count >= 12 && String(bytes: b[0..<4], encoding: .ascii) == "RIFF" && String(bytes: b[8..<12], encoding: .ascii) == "WAVE"
        case "audio/mpeg": return b.starts(with: Array("ID3".utf8)) || (b.count >= 2 && b[0] == 255 && b[1] & 224 == 224)
        default: return false
        }
    }
}
