import AtlasCore
import Foundation
import XCTest

final class ParityTests: XCTestCase {
    private let scope = ModelScope(.object(["provider_id": .string("p"), "account_id": .string("a"), "credential_id": .string("k"), "model_id": .string("m"), "group": .string("team"), "endpoint": .string("native-speech")]))
    func testAudioFileOnlyTranscriptionUsesStrictAttachmentProjection() throws {
        let file: JSONValue = .object(["kind": .string("audio"), "filename": .string("speech.wav"), "mime": .string("audio/wav"), "data": .string("UklGRg=="), "size_bytes": .number(44)])
        let draft = MediaDraft()
        XCTAssertTrue(draft.hasInput(operation: "AUDIO", prompt: "", files: [file]))
        XCTAssertFalse(draft.hasInput(operation: "AUDIO", prompt: "", files: []))
        let request = try draft.request(scope: scope, operation: "AUDIO", prompt: "", files: [file], requestID: "request")
        XCTAssertEqual(request["prompt"], .string("")); XCTAssertEqual(request["audio"]["data"], file["data"])
        XCTAssertEqual(Set(request["audio"].fields.keys), ["kind", "filename", "mime", "data"])
        for (key, value) in scope.json.fields { XCTAssertEqual(request[key], value) }
        XCTAssertThrowsError(try draft.request(scope: scope, operation: "AUDIO", prompt: "", files: [], requestID: "request"))
    }
    func testSpeechOptionsSnapshotAndAllFormatsKeepTheirActualMIMEExtension() throws {
        var draft = MediaDraft(); draft.voice = "alloy"; draft.speed = 1.75; draft.instructions = "親切"
        for format in ["mp3", "wav", "opus", "flac", "aac", "pcm"] {
            draft.format = format
            let body = try draft.request(scope: scope, operation: "AUDIO_SPEECH", prompt: "你好", files: [], requestID: "r")
            XCTAssertEqual(body["format"], .string(format)); XCTAssertEqual(body["speed"], .number(1.75)); XCTAssertEqual(body["instructions"], .string("親切")); XCTAssertEqual(body["speech_dialect"], .null)
        }
        let snapshot = draft; draft.speed = 4; draft.voice = "later"
        XCTAssertEqual(try snapshot.request(scope: scope, operation: "AUDIO_SPEECH", prompt: "first", files: [], requestID: "r")["voice"], .string("alloy"))
        XCTAssertEqual(MediaDraft.filename(mime: "image/jpeg"), "ModelAtlas.jpg")
        XCTAssertEqual(MediaDraft.filename(mime: "audio/mpeg"), "ModelAtlas.mp3")
        XCTAssertEqual(MediaDraft.filename(mime: "audio/pcm"), "ModelAtlas.pcm")
    }
    func testDualSpeechSerializesNativeTurnsAndRejectsLegacyOrAmbiguousNames() throws {
        var draft = MediaDraft(); draft.dual = true; draft.turns = [SpeechLine(speaker: "Joe", text: "你好", style: "親切"), SpeechLine(speaker: "Jane", text: "一起研究")]
        XCTAssertTrue(draft.hasInput(operation: "GEMINI_SPEECH", prompt: "", files: []))
        let body = try draft.request(scope: scope, operation: "GEMINI_SPEECH", prompt: "unused draft", files: [], requestID: "r")
        XCTAssertEqual(body["prompt"], .string("")); XCTAssertEqual(body["instructions"], .string(""))
        XCTAssertEqual(body["speakers"].array.count, 2); XCTAssertEqual(body["turns"].array[1]["speaker"], .string("Jane")); XCTAssertEqual(body["turns"].array[0]["style"], .string("親切"))
        draft.dialect = "LEGACY"; XCTAssertThrowsError(try draft.request(scope: scope, operation: "GEMINI_SPEECH", prompt: "", files: [], requestID: "r"))
        draft.dialect = "CURRENT"; draft.secondName = "Joe"; XCTAssertThrowsError(try draft.request(scope: scope, operation: "GEMINI_SPEECH", prompt: "", files: [], requestID: "r"))
        draft.secondName = "Jane"; draft.turns = [SpeechLine(speaker: "foreign", text: "hello")]; XCTAssertThrowsError(try draft.request(scope: scope, operation: "GEMINI_SPEECH", prompt: "", files: [], requestID: "r"))
    }
    func testVectorOptionsAreOperationSpecificAndBounded() throws {
        var draft = MediaDraft(); draft.dimensions = "3"; draft.documents = "A\nB"; draft.topN = "1"
        let vectors = try draft.request(scope: scope, operation: "EMBEDDINGS", prompt: "alpha\n\nbeta", files: [], requestID: "r")
        XCTAssertEqual(vectors["prompt"], .string("")); XCTAssertEqual(vectors["inputs"], .array([.string("alpha"), .string("beta")]))
        XCTAssertEqual(vectors["dimensions"], .number(3)); XCTAssertEqual(vectors["top_n"], .null); XCTAssertEqual(vectors["documents"], .null)
        let ranks = try draft.request(scope: scope, operation: "RERANK", prompt: "query", files: [], requestID: "r")
        XCTAssertEqual(ranks["top_n"], .number(1)); XCTAssertEqual(ranks["dimensions"], .null); XCTAssertEqual(ranks["documents"].array.count, 2)
        draft.dimensions = "8193"; XCTAssertThrowsError(try draft.request(scope: scope, operation: "EMBEDDINGS", prompt: "text", files: [], requestID: "r"))
        draft.topN = "3"; XCTAssertThrowsError(try draft.request(scope: scope, operation: "RERANK", prompt: "query", files: [], requestID: "r"))
    }
    private func model(_ id: String, performance: JSONValue = .object([:])) -> JSONValue {
        var m = scope.json; m["model_id"] = .string(id); m["enabled"] = .bool(true); m["performance"] = performance; m["price_state"] = .string("UNKNOWN"); return m
    }
    func testNumericFiltersNeverUseMissingMetricsAsZeroOrBorrowExternalUnits() {
        var filters = ModelFilters(); filters.numbers["ttft"] = "0"
        let unknown = model("unknown"), zero = model("zero", performance: .object(["ttft": .object(["mean": .number(0)])]))
        XCTAssertFalse(filters.matches(unknown)); XCTAssertTrue(filters.matches(zero))
        filters.source = "external"
        var external = zero; external["offer"] = .object(["external_performance": .object(["units": .string("TPS"), "average_first_token_latency": .number(0)])])
        XCTAssertFalse(filters.matches(external))
        external["offer"]["external_performance"]["units"] = .string("MS"); XCTAssertTrue(filters.matches(external))
        filters.numbers["ttft"] = "invalid"; XCTAssertFalse(filters.matches(external))
    }
    func testCredentialFilterRetainsProviderAndAccountWhenIDsCollide() {
        let a = model("one"); var b = a; b["account_id"] = .string("other")
        var filters = ModelFilters(); filters.credential = ModelFilters.credentialID(a)
        XCTAssertTrue(filters.matches(a)); XCTAssertFalse(filters.matches(b))
        b = a; b["provider_id"] = .string("other"); XCTAssertFalse(filters.matches(b))
        filters.credential = ""; filters.group = "__DEFAULT__"; XCTAssertFalse(filters.matches(a))
    }
    func testSortKeepsUnknownLastAndRankingCoverageSeparate() {
        var filters = ModelFilters(); filters.sort = "ttft"
        let unknown = model("a"), zero = model("b", performance: .object(["ttft": .object(["mean": .number(0)])])), slower = model("c", performance: .object(["ttft": .object(["mean": .number(20)])]))
        XCTAssertEqual(filters.ordered([unknown, slower, zero], weights: .object([:])).map { $0["model_id"].string }, ["b", "c", "a"])
        XCTAssertNil(filters.ranking(unknown, weights: .object([:])).score)
        var measured = zero; measured["offer"] = .object(["strength": .object(["score": .number(80)])])
        XCTAssertEqual(filters.ranking(measured, weights: .object([:])).score, 0.8)
        XCTAssertEqual(filters.ranking(measured, weights: .object([:])).coverage, 1.0 / 3.0)
    }
    func testConnectorDraftKeepsExactPATReferenceAndRejectsDuplicateMappings() throws {
        var draft = ConnectorDraft(); draft.provider = "p"; draft.reason = "合成測試依據"; draft.role = "ACCOUNT_PAT"; draft.pat = "pat-a"
        let request = try draft.request()
        XCTAssertEqual(request["config"]["pat_credential_id"], .string("pat-a")); XCTAssertEqual(request["config"]["scope"], .string("MODEL_KEY_GROUP"))
        draft.role = "INFERENCE"; XCTAssertEqual(try draft.request()["config"]["pat_credential_id"], .string(""))
        draft.effective = true; XCTAssertThrowsError(try draft.request())
        draft.effective = false; draft.mappings.append(FieldMapping(name: "rpd", pointer: "/another")); XCTAssertThrowsError(try draft.request())
        draft.mappings = []; draft.path = "//foreign.example/quota"; XCTAssertThrowsError(try draft.request())
    }
    func testBatchOnboardingRemainsAReviewAndPreservesExplicitModels() throws {
        var draft = BatchProviderDraft(); draft.name = "測試"; draft.url = "https://example.com/v1"; draft.key = "synthetic-only"; draft.models = "one, two"; draft.group = "team"
        let request = try draft.request()
        XCTAssertEqual(request["model_ids"], .array([.string("one"), .string("two")]))
        XCTAssertEqual(request["credentials"].array[0]["group"], .string("team")); XCTAssertEqual(request["confirm"], .null)
        draft.url = "https://user:secret@example.com"; XCTAssertThrowsError(try draft.request())
        draft.url = "https://example.com"; draft.auth = "NONE"; XCTAssertEqual(try draft.request()["credentials"], .array([]))
    }
}
