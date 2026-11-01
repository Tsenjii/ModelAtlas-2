import AtlasCore
import XCTest

final class ProviderManagementTests: XCTestCase {
    private func key(_ p: String = "p", _ a: String = "a", _ id: String = "same", enabled: JSONValue = .bool(true)) -> JSONValue {
        .object(["provider_id": .string(p), "account_id": .string(a), "id": .string(id), "enabled": enabled])
    }
    private var accounts: [JSONValue] { [.object(["provider_id": .string("p"), "id": .string("a")]), .object(["provider_id": .string("q"), "id": .string("foreign")])] }
    func testSameAccountWritersAndRepeatedFormAreSerializedButOtherAccountsRemainEditable() throws {
        var gate = ProviderMutationGate()
        let first = try XCTUnwrap(gate.begin(form: "rotate-a", accountID: "a"))
        XCTAssertTrue(gate.busy(accountID: "a")); XCTAssertNil(gate.begin(form: "bulk-a", accountID: "a"))
        XCTAssertNil(gate.begin(form: "rotate-a", accountID: "b"))
        let other = try XCTUnwrap(gate.begin(form: "toggle-b", accountID: "b"))
        XCTAssertNotNil(gate.begin(form: "new-account"))
        gate.finish(first); XCTAssertFalse(gate.busy(accountID: "a")); XCTAssertTrue(gate.busy(accountID: "b"))
        gate.finish(other)
    }
    func testLateCompletionDoesNotUnlockANewerLease() throws {
        var gate = ProviderMutationGate()
        let first = try XCTUnwrap(gate.begin(form: "same", accountID: "a")); gate.finish(first)
        let next = try XCTUnwrap(gate.begin(form: "same", accountID: "a")); gate.finish(first)
        XCTAssertTrue(gate.busy(form: "same")); XCTAssertTrue(gate.busy(accountID: "a"))
        gate.finish(next); XCTAssertFalse(gate.busy(form: "same"))
    }
    func testBulkToggleUsesOnlyExactAccountKnownOppositeStateAndUniqueIDs() throws {
        let values = [key(), key(), key("p", "b"), key("q", "a"), key("p", "a", "disabled", enabled: .bool(false)), key("p", "a", "unknown", enabled: .null)]
        let body = try XCTUnwrap(ProviderManagementRequest.toggle(providerID: "p", accountID: "a", credentials: values, enabled: false))
        XCTAssertEqual(body["ids"], .array([.string("same")]))
        XCTAssertEqual(body["provider_id"], .string("p")); XCTAssertEqual(body["account_id"], .string("a")); XCTAssertEqual(body["enabled"], .bool(false))
        XCTAssertNil(try ProviderManagementRequest.toggle(providerID: "p", accountID: "a", credentials: [key("p", "a", "off", enabled: .bool(false))], enabled: false))
        XCTAssertThrowsError(try ProviderManagementRequest.toggle(providerID: "", accountID: "a", credentials: values, enabled: true))
    }
    func testKeyIdentityAndRotationNeverBorrowAnAccountWithTheSameKeyID() throws {
        let a = try CredentialIdentity(key()), b = try CredentialIdentity(key("p", "b")), other = try CredentialIdentity(key("q", "a"))
        XCTAssertNotEqual(a, b); XCTAssertNotEqual(a.id, other.id)
        XCTAssertThrowsError(try CredentialIdentity(.object(["id": .string("same")])))
        var draft = CredentialUpdateDraft(); draft.secret = "new-synthetic-value"
        let body = try draft.request(identity: b)
        XCTAssertEqual(body["account_id"], .string("b")); XCTAssertEqual(body["credential_id"], .string("same")); XCTAssertEqual(body["confirm"], .bool(true))
    }
    func testRestoreModeNeverSendsTheUnselectedSecretAndRejectsInvalidVersions() throws {
        var draft = CredentialUpdateDraft(); draft.mode = "version"; draft.secret = "must-not-send"; draft.version = "2"
        let identity = try CredentialIdentity(key())
        let body = try draft.request(identity: identity)
        XCTAssertEqual(body["version"], .number(2)); XCTAssertEqual(body["secret"], .null)
        for value in ["0", "-1", "1.5", "invalid"] { draft.version = value; XCTAssertThrowsError(try draft.request(identity: identity)) }
        draft.mode = "secret"
        for value in ["", "line\r\nsecret", "line\0secret", String(repeating: "x", count: 8193)] { draft.secret = value; XCTAssertThrowsError(try draft.request(identity: identity)) }
    }
    func testNewKeyFreezesOriginRoleAndStableIdentityUntilConfirmed() throws {
        var draft = ProviderKeyDraft(); draft.accountID = "a"; draft.secret = "synthetic"; draft.role = "ACCOUNT_PAT"
        let body = try draft.request(providerID: "p", accounts: accounts, inheritedOrigin: "https://site.example/v1", inheritedAuth: "BEARER")
        XCTAssertEqual(body["base_url"], .string("https://site.example/v1")); XCTAssertEqual(body["role"], .string("ACCOUNT_PAT")); XCTAssertEqual(body["id"], .string(draft.id))
        XCTAssertEqual(try draft.request(providerID: "p", accounts: accounts, inheritedOrigin: "https://site.example/v1", inheritedAuth: "BEARER")["id"], body["id"])
        draft.accountID = "foreign"; XCTAssertThrowsError(try draft.request(providerID: "p", accounts: accounts, inheritedOrigin: "https://site.example/v1", inheritedAuth: "BEARER"))
        draft.accountID = "a"; draft.origin = "https://name:secret@site.example/v1"; XCTAssertThrowsError(try draft.request(providerID: "p", accounts: accounts, inheritedOrigin: "https://site.example/v1", inheritedAuth: "BEARER"))
        draft.origin = ""; draft.role = "INFERENCE"; draft.auth = "CUSTOM_HEADER"; draft.secret = ""
        var field = SecretFieldDraft(); field.name = "x-workspace-key"; field.secret = "synthetic"
        draft.authFields = [field]
        XCTAssertEqual(try draft.request(providerID: "p", accounts: accounts, inheritedOrigin: "https://site.example/v1", inheritedAuth: "BEARER")["auth_fields"].array.count, 1)
        draft.role = "ACCOUNT_PAT"; XCTAssertThrowsError(try draft.request(providerID: "p", accounts: accounts, inheritedOrigin: "https://site.example/v1", inheritedAuth: "BEARER"))
    }
    func testConfirmedKeyClearsOnlyTheSubmittedDraftAndReseedsNextDraftIdentity() throws {
        var draft = ProviderKeyDraft(); draft.accountID = "a"; draft.label = "first"; draft.secret = "one"
        let submitted = draft
        draft.label = "next"; draft.secret = "two"; draft.committed(submitted)
        XCTAssertEqual(draft.label, "next"); XCTAssertEqual(draft.secret, "two"); XCTAssertNotEqual(draft.id, submitted.id)
        let next = draft; draft.committed(submitted); XCTAssertEqual(draft, next)
        draft.committed(next); XCTAssertEqual(draft.secret, ""); XCTAssertEqual(draft.label, ""); XCTAssertEqual(draft.accountID, "a"); XCTAssertNotEqual(draft.id, next.id)
    }
    func testAccountRetriesKeepIdentityAndSuccessfulSaveDoesNotOverwriteTheNextName() throws {
        var draft = ProviderAccountDraft(); XCTAssertThrowsError(try draft.request(providerID: "p"))
        draft.label = "first"; let submitted = draft
        XCTAssertEqual(try draft.request(providerID: "p"), try submitted.request(providerID: "p"))
        draft.label = "next"; draft.committed(submitted)
        XCTAssertEqual(draft.label, "next"); XCTAssertNotEqual(draft.id, submitted.id)
        let next = draft; draft.committed(next); XCTAssertEqual(draft.label, "")
    }
}
