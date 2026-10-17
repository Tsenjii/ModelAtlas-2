import AppKit
import AtlasCore
import SwiftUI

struct ChatMarkdown: View, Equatable {
    let source: String
    var body: some View {
        let blocks = MessageMarkup.parse(source)
        VStack(alignment: .leading, spacing: 14) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                MarkdownBlockView(block: block)
            }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct MarkdownBlockView: View {
    let block: MessageBlock
    @ViewBuilder var body: some View {
        switch block {
        case .paragraph(let text): InlineMarkdown(source: text)
        case .heading(let level, let text):
            InlineMarkdown(source: text).font(.system(size: level == 1 ? 24 : level == 2 ? 20 : 16, weight: .semibold)).padding(.top, 4)
        case .quote(let text):
            HStack(alignment: .top, spacing: 12) {
                RoundedRectangle(cornerRadius: 2).fill(atlasAccent.opacity(0.6)).frame(width: 3)
                InlineMarkdown(source: text).foregroundStyle(.secondary).padding(.vertical, 4)
            }.fixedSize(horizontal: false, vertical: true)
        case .listItem(let marker, let text):
            HStack(alignment: .firstTextBaseline, spacing: 9) {
                Text(marker).foregroundStyle(atlasAccent).frame(minWidth: 17, alignment: .trailing)
                InlineMarkdown(source: text)
            }
        case .code(let language, let code): CodeBlockView(language: language, code: code)
        case .table(let rows): MarkdownTable(rows: rows)
        case .rule: Divider().padding(.vertical, 4)
        }
    }
}

private struct InlineMarkdown: View {
    let source: String
    var body: some View {
        Text((try? AttributedString(markdown: source, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(source))
            .textSelection(.enabled).lineSpacing(5).fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct CodeBlockView: View {
    let language: String, code: String
    @State private var copied = false
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(language.isEmpty ? "程式碼" : language).font(.caption.weight(.medium)).foregroundStyle(.secondary)
                Spacer()
                Button {
                    copyText(code); copied = true
                } label: { Label(copied ? "已複製" : "複製程式碼", systemImage: copied ? "checkmark" : "doc.on.doc") }
                    .buttonStyle(.plain).font(.caption).help("複製原始程式碼")
                    .accessibilityIdentifier("chat-copy-code")
            }.padding(.horizontal, 14).padding(.vertical, 10).background(Color.primary.opacity(0.035))
            Divider()
            ScrollView(.horizontal) {
                Text(code.isEmpty ? " " : code).font(.system(size: 12.5, design: .monospaced)).textSelection(.enabled)
                    .fixedSize(horizontal: true, vertical: false).lineSpacing(4).padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }.background(atlasSurface).cornerRadius(10)
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.primary.opacity(0.09)))
            .onChange(of: code) { _ in copied = false }
    }
}

private struct MarkdownTable: View {
    let rows: [[String]]
    var body: some View {
        ScrollView(.horizontal) {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                    HStack(alignment: .top, spacing: 0) {
                        ForEach(Array(row.enumerated()), id: \.offset) { _, cell in
                            InlineMarkdown(source: cell).font(.callout.weight(index == 0 ? .semibold : .regular))
                                .frame(width: 160, alignment: .leading).padding(12)
                        }
                    }.background(Color.primary.opacity(index == 0 ? 0.055 : index.isMultiple(of: 2) ? 0.02 : 0))
                    if index + 1 < rows.count { Divider() }
                }
            }
        }.background(atlasSurface).cornerRadius(10)
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.primary.opacity(0.09)))
    }
}
