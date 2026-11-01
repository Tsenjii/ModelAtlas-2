import Foundation

/// A deliberately small, native Markdown block grammar. Unknown syntax remains
/// visible text; no HTML, image loading, scripts or external renderer is used.
public enum MessageBlock: Equatable, Sendable {
    case paragraph(String), heading(Int, String), quote(String)
    case listItem(String, String), code(String, String), table([[String]]), rule
}

public enum MessageMarkup {
    public static func parse(_ source: String) -> [MessageBlock] {
        let lines = source.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n").components(separatedBy: "\n")
        var blocks: [MessageBlock] = [], paragraph: [String] = [], i = 0
        func flush() { if !paragraph.isEmpty { blocks.append(.paragraph(paragraph.joined(separator: "\n"))); paragraph.removeAll(keepingCapacity: true) } }
        while i < lines.count {
            let line = lines[i], trimmed = line.trimmingCharacters(in: .whitespaces)
            if let fence = openingFence(line) {
                flush(); i += 1; var code: [String] = []
                while i < lines.count && !closesFence(lines[i], marker: fence.marker, count: fence.count) { code.append(lines[i]); i += 1 }
                blocks.append(.code(fence.language, code.joined(separator: "\n")))
                if i < lines.count { i += 1 }; continue
            }
            if trimmed.isEmpty { flush(); i += 1; continue }
            if i + 1 < lines.count, let header = tableCells(line), let divider = tableCells(lines[i + 1]),
               header.count == divider.count, divider.allSatisfy(isTableDivider) {
                flush(); var rows = [header]; i += 2
                while i < lines.count, let row = tableCells(lines[i]), row.count == header.count { rows.append(row); i += 1 }
                blocks.append(.table(rows)); continue
            }
            let hashes = trimmed.prefix(while: { $0 == "#" }).count
            if (1...6).contains(hashes), trimmed.dropFirst(hashes).first == " " {
                flush(); blocks.append(.heading(hashes, String(trimmed.dropFirst(hashes + 1)))); i += 1; continue
            }
            if trimmed.hasPrefix("> ") || trimmed == ">" {
                flush(); blocks.append(.quote(String(trimmed.dropFirst(trimmed.count > 1 ? 2 : 1)))); i += 1; continue
            }
            let compact = trimmed.filter { !$0.isWhitespace }
            if compact.count >= 3, let first = compact.first, ["-", "*", "_"].contains(first), compact.allSatisfy({ $0 == first }) {
                flush(); blocks.append(.rule); i += 1; continue
            }
            if ["- ", "* ", "+ "].contains(where: trimmed.hasPrefix) {
                flush(); blocks.append(.listItem("•", String(trimmed.dropFirst(2)))); i += 1; continue
            }
            let digits = trimmed.prefix(while: { $0.isASCII && $0.isNumber })
            let rest = trimmed.dropFirst(digits.count)
            if !digits.isEmpty, digits.count <= 9, rest.hasPrefix(". ") || rest.hasPrefix(") ") {
                flush(); blocks.append(.listItem(String(digits) + ".", String(rest.dropFirst(2)))); i += 1; continue
            }
            paragraph.append(line); i += 1
        }
        flush(); return blocks
    }
    private static func openingFence(_ line: String) -> (marker: Character, count: Int, language: String)? {
        let spaces = line.prefix(while: { $0 == " " }).count
        guard spaces <= 3 else { return nil }
        let text = line.dropFirst(spaces)
        guard let marker = text.first, marker == "`" || marker == "~" else { return nil }
        let count = text.prefix(while: { $0 == marker }).count
        guard count >= 3 else { return nil }
        let info = text.dropFirst(count).trimmingCharacters(in: .whitespaces)
        guard marker != "`" || !info.contains("`") else { return nil }
        return (marker, count, String(info.prefix(while: { !$0.isWhitespace })))
    }
    private static func closesFence(_ line: String, marker: Character, count: Int) -> Bool {
        let spaces = line.prefix(while: { $0 == " " }).count
        guard spaces <= 3 else { return false }
        let text = line.dropFirst(spaces), markers = text.prefix(while: { $0 == marker }).count
        return markers >= count && text.dropFirst(markers).allSatisfy(\.isWhitespace)
    }
    private static func isTableDivider(_ cell: String) -> Bool {
        var value = cell[...]
        if value.first == ":" { value = value.dropFirst() }
        if value.last == ":" { value = value.dropLast() }
        return value.count >= 3 && value.allSatisfy { $0 == "-" }
    }
    private static func tableCells(_ line: String) -> [String]? {
        let text = line.trimmingCharacters(in: .whitespaces)
        var cells: [String] = [], cell = "", escaped = false, codeRun = 0, i = text.startIndex
        while i < text.endIndex {
            let c = text[i]
            if escaped { cell.append(c); escaped = false; i = text.index(after: i); continue }
            if c == "\\" { cell.append(c); escaped = true; i = text.index(after: i); continue }
            if c == "`" {
                let start = i
                while i < text.endIndex && text[i] == "`" { i = text.index(after: i) }
                let run = text.distance(from: start, to: i)
                if codeRun == 0 { codeRun = run } else if codeRun == run { codeRun = 0 }
                cell += String(text[start..<i]); continue
            }
            if c == "|" && codeRun == 0 { cells.append(cell.trimmingCharacters(in: .whitespaces)); cell = "" }
            else { cell.append(c) }
            i = text.index(after: i)
        }
        guard !cells.isEmpty else { return nil }
        cells.append(cell.trimmingCharacters(in: .whitespaces))
        if text.hasPrefix("|") { cells.removeFirst() }
        if cells.last == "", text.hasSuffix("|"), !text.hasSuffix("\\|") { cells.removeLast() }
        return cells.count >= 2 && cells.count <= 32 ? cells : nil
    }
}

public struct ChatReadingState: Equatable, Sendable {
    public private(set) var following = true
    public private(set) var atLatest = true
    public init() {}
    public mutating func observe(distance: Double, userScroll: Bool) {
        atLatest = distance <= 48
        if userScroll { following = atLatest }
    }
    public mutating func pause() { following = false }
    public mutating func resume() { following = true }
    public mutating func reset() { self = Self() }
}
