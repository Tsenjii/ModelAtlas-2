import XCTest
@testable import AtlasCore

final class WorkspaceDraftTests: XCTestCase {
    private let code = String(repeating: "a", count: 43)
    func testPairingUsesRootHTTPSAndPreservesCodeWithoutDescriptionLeak() throws {
        let draft = try WorkspaceDraft(url: " https://atlas.example:8443/ ", label: " 我的 Mac ", code: code)
        XCTAssertEqual(draft.url, "https://atlas.example:8443")
        XCTAssertEqual(draft.body["label"].string, "我的 Mac")
        XCTAssertEqual(draft.body["code"].string, code)
        XCTAssertEqual(draft.body["platform"].string, "macOS")
        XCTAssertTrue(draft.body["resumable"].boolean)
        XCTAssertFalse(draft.description.contains(code))
    }
    func testRejectsCredentialsPathsQueriesAndNonHTTPS() {
        for url in ["http://atlas.example", "https://user@atlas.example", "https://a:b@atlas.example", "https://atlas.example/api", "https://atlas.example/?token=a", "https://atlas.example/#a", "https://atlas.example:0", "https://atlas.example:99999", "file:///tmp/a"] {
            XCTAssertThrowsError(try WorkspaceDraft(url: url, label: "Mac", code: code), url)
        }
    }
    func testRejectsMalformedCodeAndControlCharacters() {
        for value in ["", String(repeating: "a", count: 42), String(repeating: "中", count: 43), String(repeating: "a", count: 42) + "+"] {
            XCTAssertThrowsError(try WorkspaceDraft(url: "https://atlas.example", label: "Mac", code: value))
        }
        for label in [" ", "Mac\nOther", String(repeating: "a", count: 121)] {
            XCTAssertThrowsError(try WorkspaceDraft(url: "https://atlas.example", label: label, code: code))
        }
    }
}
