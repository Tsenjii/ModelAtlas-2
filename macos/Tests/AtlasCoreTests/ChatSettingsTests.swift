import AtlasCore
import Foundation
import XCTest

final class ChatSettingsTests: XCTestCase {
    func testTokenLimitsHaveTheirOwnErrorAndPreserveTheUnsentInput() throws {
        for input in ["", "0", "32769", "-1", "1.5", "abc", "999999999999999999999"] {
            let draft = ChatSettingsDraft(title: "工作對話", maxTokens: input)
            XCTAssertThrowsError(try draft.validated()) { XCTAssertEqual($0 as? ChatSettingsValidation, .tokenLimit) }
            XCTAssertEqual(draft.maxTokens, input)
        }
        for input in ["1", "32768", " 777 "] {
            XCTAssertEqual(try ChatSettingsDraft(title: "工作對話", maxTokens: input).validated().tokens, Int(input.trimmingCharacters(in: .whitespaces)))
        }
    }
    func testToolsMustBeAnArrayAndKeepTheirExactTypedArguments() throws {
        for input in ["{", "[broken]"] {
            XCTAssertThrowsError(try ChatSettingsDraft(title: "對話", toolsJSON: input).validated()) { XCTAssertEqual($0 as? ChatSettingsValidation, .toolsJSON) }
        }
        for input in ["{}", "null", "\"text\""] {
            XCTAssertThrowsError(try ChatSettingsDraft(title: "對話", toolsJSON: input).validated()) { XCTAssertEqual($0 as? ChatSettingsValidation, .toolsArray) }
        }
        let definition = "[{\"type\":\"function\",\"function\":{\"name\":\"read\",\"parameters\":{\"type\":\"object\"}}}]"
        XCTAssertEqual(try ChatSettingsDraft(title: "對話", toolsJSON: definition).validated().tools, try JSONValue.parse(definition))
    }
    func testTitleBoundIsUTF8BytesAndRejectsInvisibleControlCharacters() throws {
        for title in ["", "  ", " 對話", "對話 ", "a\nb", "a\u{202E}b", String(repeating: "中", count: 67)] {
            XCTAssertThrowsError(try ChatSettingsDraft(title: title).validated()) { XCTAssertEqual($0 as? ChatSettingsValidation, .title) }
        }
        XCTAssertNoThrow(try ChatSettingsDraft(title: String(repeating: "中", count: 66)).validated())
        XCTAssertNoThrow(try ChatSettingsDraft(title: String(repeating: "a", count: 200)).validated())
    }
}
