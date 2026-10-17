import AtlasCore
import Foundation
import XCTest

final class ChatExperienceTests: XCTestCase {
    func testNativeBlocksKeepCodeLiteralIncludingNestedShortFences() {
        let source = "# 標題\r\n\r\n````swift\r\nlet text = \"```\"\r\n```\r\n  print(text)\r\n````\r\n\r\n> 引言\r\n- 項目\r\n12. 步驟\r\n---"
        XCTAssertEqual(MessageMarkup.parse(source), [.heading(1, "標題"), .code("swift", "let text = \"```\"\n```\n  print(text)"), .quote("引言"), .listItem("•", "項目"), .listItem("12.", "步驟"), .rule])
        XCTAssertEqual(MessageMarkup.parse("~~~python\nx = 1\n\n"), [.code("python", "x = 1\n\n")])
        XCTAssertEqual(MessageMarkup.parse("````\n```\n````"), [.code("", "```")])
        XCTAssertEqual(MessageMarkup.parse("    ```swift\nkept"), [.paragraph("    ```swift\nkept")])
    }
    func testTablesKeepEscapedAndInlineCodePipesAndRejectMalformedRows() {
        let source = "| 欄位 | 值 |\n| :--- | ---: |\n| `a|b` | one\\|two |\n\n正文\n| a | b |\n| -- | --- |"
        XCTAssertEqual(MessageMarkup.parse(source), [.table([["欄位", "值"], ["`a|b`", "one\\|two"]]), .paragraph("正文\n| a | b |\n| -- | --- |")])
        XCTAssertEqual(MessageMarkup.parse("原始 <script>alert(1)</script>\n![遠端圖片](https://example.invalid/a.png)"), [.paragraph("原始 <script>alert(1)</script>\n![遠端圖片](https://example.invalid/a.png)")])
        let long = String(repeating: "繁中長訊息 ", count: 20_000)
        XCTAssertEqual(MessageMarkup.parse(long), [.paragraph(long)])
    }
    func testDocumentGrowthCannotResumeOrPauseIntentionalReading() {
        var state = ChatReadingState()
        state.observe(distance: 300, userScroll: false)
        XCTAssertTrue(state.following)
        state.observe(distance: 900, userScroll: true)
        XCTAssertFalse(state.following); XCTAssertFalse(state.atLatest)
        state.observe(distance: 0, userScroll: false)
        XCTAssertFalse(state.following); XCTAssertTrue(state.atLatest)
        state.observe(distance: 40, userScroll: true)
        XCTAssertTrue(state.following)
        state.pause(); state.observe(distance: 1600, userScroll: false)
        XCTAssertFalse(state.following)
        state.resume(); XCTAssertTrue(state.following)
        state.reset(); XCTAssertEqual(state, ChatReadingState())
    }
    func testLocalAttachmentBatchRejectsInvalidFilesWithoutReplacingExistingTray() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let pdf = dir.appendingPathComponent("筆記.pdf"), forged = dir.appendingPathComponent("fake.png"), unknown = dir.appendingPathComponent("file.txt"), huge = dir.appendingPathComponent("huge.pdf")
        try Data("%PDF-1.7\nlocal fixture".utf8).write(to: pdf)
        try Data("this is not a PNG".utf8).write(to: forged)
        try Data("text".utf8).write(to: unknown)
        try Data(repeating: 0, count: (4 << 20) + 1).write(to: huge)
        let added = try LocalAttachment.adding([pdf], to: [])
        XCTAssertEqual(added.count, 1); XCTAssertEqual(added[0]["kind"].string, "pdf")
        XCTAssertEqual(Data(base64Encoded: added[0]["data"].string), try Data(contentsOf: pdf))
        XCTAssertEqual(added[0]["size_bytes"].numeric, Double(try Data(contentsOf: pdf).count))
        var local = added[0]; local["future_display_metadata"] = .string("local only")
        let request = LocalAttachment.requestValue(local)
        XCTAssertEqual(Set(request.fields.keys), Set(["kind", "filename", "mime", "data"]))
        XCTAssertEqual(request["data"], added[0]["data"])
        XCTAssertEqual(request["size_bytes"], .null)
        XCTAssertThrowsError(try LocalAttachment.adding([pdf], to: added))
        XCTAssertThrowsError(try LocalAttachment.adding([pdf, forged], to: []))
        XCTAssertThrowsError(try LocalAttachment.adding([unknown], to: []))
        XCTAssertThrowsError(try LocalAttachment.adding([huge], to: []))
        XCTAssertThrowsError(try LocalAttachment.adding([URL(string: "https://example.invalid/remote.pdf")!], to: []))
        XCTAssertEqual(added.count, 1)
    }
}
