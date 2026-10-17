import AppKit
import AtlasCore
import SwiftUI
import UniformTypeIdentifiers

struct AttachmentTray: View {
    @ObservedObject var store: AtlasStore
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(store.attachments, id: \.self) { file in
                    HStack(spacing: 9) {
                        Image(systemName: file["kind"].string == "image" ? "photo" : file["kind"].string == "pdf" ? "doc.richtext" : "waveform")
                            .font(.system(size: 18)).foregroundStyle(atlasAccent)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(file["filename"].string).lineLimit(1).truncationMode(.middle)
                            if let size = file["size_bytes"].numeric { Text(ByteCountFormatter.string(fromByteCount: Int64(size), countStyle: .file)).foregroundStyle(.secondary) }
                        }.frame(maxWidth: 180, alignment: .leading)
                        Button { store.attachments.removeAll { $0 == file } } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                            .buttonStyle(.plain).help("移除 " + file["filename"].string).disabled(store.generating)
                    }.font(.caption).padding(10).background(atlasAccent.opacity(0.07)).cornerRadius(9)
                }
            }
        }.fixedSize(horizontal: false, vertical: true)
    }
}

@MainActor struct ComposerFileDrop: DropDelegate {
    let store: AtlasStore
    @Binding var targeted: Bool
    func validateDrop(info: DropInfo) -> Bool { !store.generating && !store.attaching && info.hasItemsConforming(to: [UTType.fileURL.identifier]) }
    func dropEntered(info: DropInfo) { targeted = validateDrop(info: info) }
    func dropExited(info: DropInfo) { targeted = false }
    func performDrop(info: DropInfo) -> Bool {
        targeted = false
        let providers = info.itemProviders(for: [UTType.fileURL.identifier])
        guard !store.generating, !store.attaching, !providers.isEmpty else { return false }
        guard providers.count <= 3 else { store.problem = "一次最多加入三份附件：圖片、PDF、音訊各一份。"; return false }
        let session = store.sessionID, scope = store.selectedScope
        Task { @MainActor in
            do {
                var urls: [URL] = []
                for provider in providers {
                    let url: URL = try await withCheckedThrowingContinuation { continuation in
                        provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { value, error in
                            if let error { continuation.resume(throwing: error) }
                            else if let url = value as? URL { continuation.resume(returning: url) }
                            else if let data = value as? Data, let url = URL(dataRepresentation: data, relativeTo: nil) { continuation.resume(returning: url) }
                            else { continuation.resume(throwing: LocalAttachmentError.unsupported) }
                        }
                    }
                    urls.append(url)
                }
                guard session == store.sessionID, scope == store.selectedScope, !store.generating else { return }
                await store.addAttachments(urls)
            } catch { if session == store.sessionID, scope == store.selectedScope { store.problem = error.localizedDescription } }
        }
        return true
    }
}
