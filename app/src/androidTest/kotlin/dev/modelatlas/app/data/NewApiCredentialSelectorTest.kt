package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.core.newapi.AccountPatRef
import dev.modelatlas.core.newapi.InferenceKeyRef
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.SafeCredentialRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CR-001 A5 selection/role regression (device androidTest;本檔唯一新增).
 *
 * 契約: CORRECTNESS_REPAIR_AUDIT A1/A5 + CR-001 planned gate;
 * Spec: PRODUCT_SPEC §6 (Accounts/Credentials/Selected-context, PAT_ACCOUNT vs inference),
 * §10 (PricingScope/Account/Credential isolation, Unknown!=0), §12 (New API PAT vs key),
 * §18 (acceptance); Contract §6.4/§23.3 (selected Credential/Group exact scope, enrichment
 * fail-open but membership never polluted).
 *
 * 鎖定 API (production 由另片實作,本檔只定義回歸期望,不重複產品邏輯):
 * - `NewApiCredentialSelector(db)`:
 *   - `suspend resolveSync(providerId, accountId: String? = null, credentialId: String? = null): NewApiResolvedSyncScope`
 *     其中 `NewApiResolvedSyncScope(accountId: String, pat: AccountPatRef, key: InferenceKeyRef?)`.
 *   - `suspend resolvePerfPat(providerId, accountId: String? = null): NewApiAccountPatSelection?`
 *     其中 `NewApiAccountPatSelection(accountId: String, pat: AccountPatRef)`, null == 匿名.
 *
 * 本檔全部真 in-memory Room13 + 合成 metadata,零 HTTP/零 vault/零真 key/PAT,無共用 helper.
 * 選擇器必須唯讀:任何成功/失敗/取消路徑皆不得寫入 history/telemetry/membership
 * (samples/evidence/snapshot_facts+heads/provider_perf_* /provider_account_usage_* /my_models).
 */
@RunWith(AndroidJUnit4::class)
class NewApiCredentialSelectorTest {

    private val open = mutableListOf<AtlasDatabase>()

    @After
    fun tearDown() {
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    // ===== resolveSync: disabled/missing/dual-role 首項 + 同帳號有效 fallback =====

    @Test
    fun disabledFirstPatFallsBackToValidSameAccount() = runBlocking {
        val db = memoryDb()
        val provider = "sel-dis-p1"
        val account = "sel-dis-a1"
        seedProviderAccount(db, provider, account)
        // DB: 無效首項 disabled + 同帳號有效第二項.
        seedCredential(db, "sel-dis-pat-bad", account, enabled = false)
        seedCredential(db, "sel-dis-pat-good", account, enabled = true)
        seedConfig(
            db, provider,
            listOf(
                SafeCredentialRef("sel-dis-pat-bad", "bad", CredentialRole.ACCOUNT),
                SafeCredentialRef("sel-dis-pat-good", "good", CredentialRole.ACCOUNT),
            ),
        )
        val before = readOnlyCounts(db)
        val selector = NewApiCredentialSelector(db)
        val resolved = selector.resolveSync(provider, account, null)
        assertEquals(account, resolved.accountId)
        assertEquals("sel-dis-pat-good", resolved.pat.value)
        // 本帳號無 inference key 時 key 必須 null,不報錯.
        assertNull(resolved.key?.value)
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun missingFirstPatFallsBackToValidSameAccount() = runBlocking {
        val db = memoryDb()
        val provider = "sel-miss-p1"
        val account = "sel-miss-a1"
        seedProviderAccount(db, provider, account)
        // DB 只存在第二項;首項 config 引用缺席列.
        seedCredential(db, "sel-miss-pat-good", account, enabled = true)
        seedConfig(
            db, provider,
            listOf(
                SafeCredentialRef("sel-miss-pat-ghost", "ghost", CredentialRole.ACCOUNT),
                SafeCredentialRef("sel-miss-pat-good", "good", CredentialRole.ACCOUNT),
            ),
        )
        val before = readOnlyCounts(db)
        val resolved = NewApiCredentialSelector(db).resolveSync(provider, account, null)
        assertEquals(account, resolved.accountId)
        assertEquals("sel-miss-pat-good", resolved.pat.value)
        assertNull(resolved.key?.value)
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun dualRoleFirstPatFallsBackToValidSameAccount() = runBlocking {
        val db = memoryDb()
        val provider = "sel-dual-p1"
        val account = "sel-dual-a1"
        seedProviderAccount(db, provider, account)
        seedCredential(db, "sel-dual-dup", account, enabled = true)
        seedCredential(db, "sel-dual-pat-good", account, enabled = true)
        // 同一 safe id 同時 ACCOUNT+INFERENCE == dual-role,必须判無效.
        seedConfig(
            db, provider,
            listOf(
                SafeCredentialRef("sel-dual-dup", "dup-pat", CredentialRole.ACCOUNT),
                SafeCredentialRef("sel-dual-dup", "dup-key", CredentialRole.INFERENCE),
                SafeCredentialRef("sel-dual-pat-good", "good", CredentialRole.ACCOUNT),
            ),
        )
        val before = readOnlyCounts(db)
        val resolved = NewApiCredentialSelector(db).resolveSync(provider, account, null)
        assertEquals(account, resolved.accountId)
        assertEquals("sel-dual-pat-good", resolved.pat.value)
        assertReadOnlyUnchanged(db, before)
    }

    // ===== resolveSync: 他帳號永不借用 / 顯式 mismatch 拒絕 =====

    @Test
    fun validOtherAccountNeverBorrowedForExplicitAccount() = runBlocking {
        val db = memoryDb()
        val provider = "sel-noborrow-p1"
        val accountA = "sel-noborrow-aA"
        val accountB = "sel-noborrow-aB"
        seedProviderAccount(db, provider, accountA)
        seedProviderAccount(db, provider, accountB)
        seedCredential(db, "sel-noborrow-pat-a", accountA, enabled = true)
        // B 的唯一 PAT disabled:即使 A 有有效 PAT,也絕不借給 B.
        seedCredential(db, "sel-noborrow-pat-b-bad", accountB, enabled = false)
        seedConfig(
            db, provider,
            listOf(
                SafeCredentialRef("sel-noborrow-pat-a", "a", CredentialRole.ACCOUNT),
                SafeCredentialRef("sel-noborrow-pat-b-bad", "b-bad", CredentialRole.ACCOUNT),
            ),
        )
        val before = readOnlyCounts(db)
        val err = runCatching {
            NewApiCredentialSelector(db).resolveSync(provider, accountB, null)
        }.exceptionOrNull()
        assertTrue("顯式 B 無有效 PAT 必須 fail-closed,實際:$err", err is IllegalArgumentException)
        assertFalse("拒絕訊息不得回顯他帳號有效 ref", (err?.message ?: "").contains("sel-noborrow-pat-a"))
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun explicitAccountKeyOwnershipMismatchRejected() = runBlocking {
        val db = memoryDb()
        val provider = "sel-mismatch-p1"
        val accountA = "sel-mismatch-aA"
        val accountB = "sel-mismatch-aB"
        seedProviderAccount(db, provider, accountA)
        seedProviderAccount(db, provider, accountB)
        seedCredential(db, "sel-mismatch-pat-a", accountA, enabled = true)
        seedCredential(db, "sel-mismatch-pat-b", accountB, enabled = true)
        seedCredential(db, "sel-mismatch-key-a1", accountA, enabled = true)
        seedCredential(db, "sel-mismatch-key-b1", accountB, enabled = true)
        seedConfig(
            db, provider,
            listOf(
                SafeCredentialRef("sel-mismatch-pat-a", "pa", CredentialRole.ACCOUNT),
                SafeCredentialRef("sel-mismatch-pat-b", "pb", CredentialRole.ACCOUNT),
                SafeCredentialRef("sel-mismatch-key-a1", "ka", CredentialRole.INFERENCE),
                SafeCredentialRef("sel-mismatch-key-b1", "kb", CredentialRole.INFERENCE),
            ),
        )
        val selector = NewApiCredentialSelector(db)
        // A 帳號 + B 的 key:所有權錯配必須拒絕,不導向 B.
        val before = readOnlyCounts(db)
        val err = runCatching {
            selector.resolveSync(provider, accountA, "sel-mismatch-key-b1")
        }.exceptionOrNull()
        assertTrue("跨帳號 key 必須拒絕,實際:$err", err is IllegalArgumentException)
        assertReadOnlyUnchanged(db, before)
        // 不存在帳號 / 不存在 key 亦拒絕.
        val err2 = runCatching {
            selector.resolveSync(provider, "sel-mismatch-no-such-account", null)
        }.exceptionOrNull()
        assertTrue(err2 is IllegalArgumentException)
        val err3 = runCatching {
            selector.resolveSync(provider, accountA, "sel-mismatch-no-such-key")
        }.exceptionOrNull()
        assertTrue(err3 is IllegalArgumentException)
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun crossProviderAccountNeverBorrowed() = runBlocking {
        val db = memoryDb()
        seedProviderAccount(db, "sel-xprov-p1", "sel-xprov-a1")
        seedProviderAccount(db, "sel-xprov-p2", "sel-xprov-a2")
        seedCredential(db, "sel-xprov-pat-1", "sel-xprov-a1", enabled = true)
        seedCredential(db, "sel-xprov-pat-2", "sel-xprov-a2", enabled = true)
        seedConfig(
            db, "sel-xprov-p1",
            listOf(SafeCredentialRef("sel-xprov-pat-1", "p1", CredentialRole.ACCOUNT)),
        )
        seedConfig(
            db, "sel-xprov-p2",
            listOf(SafeCredentialRef("sel-xprov-pat-2", "p2", CredentialRole.ACCOUNT)),
        )
        val before = readOnlyCounts(db)
        // p1 顯式要求 p2 的帳號:跨 provider 必須拒絕.
        val err = runCatching {
            NewApiCredentialSelector(db).resolveSync("sel-xprov-p1", "sel-xprov-a2", null)
        }.exceptionOrNull()
        assertTrue("跨 provider 帳號必須拒絕,實際:$err", err is IllegalArgumentException)
        assertReadOnlyUnchanged(db, before)
    }

    // ===== resolveSync: 省略 account 派生 / 省略 both / legacy 單帳號 =====

    @Test
    fun omittedAccountDerivesExactKeyOwner() = runBlocking {
        val db = memoryDb()
        val provider = "sel-derive-p1"
        val accountA = "sel-derive-aA"
        val accountB = "sel-derive-aB"
        seedProviderAccount(db, provider, accountA)
        seedProviderAccount(db, provider, accountB)
        seedCredential(db, "sel-derive-pat-a", accountA, enabled = true)
        seedCredential(db, "sel-derive-pat-b", accountB, enabled = true)
        seedCredential(db, "sel-derive-key-a1", accountA, enabled = true)
        seedCredential(db, "sel-derive-key-b1", accountB, enabled = true)
        seedConfig(
            db, provider,
            listOf(
                SafeCredentialRef("sel-derive-pat-a", "pa", CredentialRole.ACCOUNT),
                SafeCredentialRef("sel-derive-pat-b", "pb", CredentialRole.ACCOUNT),
                SafeCredentialRef("sel-derive-key-a1", "ka", CredentialRole.INFERENCE),
                SafeCredentialRef("sel-derive-key-b1", "kb", CredentialRole.INFERENCE),
            ),
        )
        val before = readOnlyCounts(db)
        // 省略 account + 顯式 B key:必須精確派生 B,不取 A.
        val resolved = NewApiCredentialSelector(db).resolveSync(provider, null, "sel-derive-key-b1")
        assertEquals(accountB, resolved.accountId)
        assertEquals("sel-derive-pat-b", resolved.pat.value)
        assertEquals("sel-derive-key-b1", resolved.key?.value)
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun omittedBothWithMultiAccountsRejected() = runBlocking {
        val db = memoryDb()
        val provider = "sel-ambig-p1"
        seedProviderAccount(db, provider, "sel-ambig-aA")
        seedProviderAccount(db, provider, "sel-ambig-aB")
        seedCredential(db, "sel-ambig-pat-a", "sel-ambig-aA", enabled = true)
        seedCredential(db, "sel-ambig-pat-b", "sel-ambig-aB", enabled = true)
        seedConfig(
            db, provider,
            listOf(
                SafeCredentialRef("sel-ambig-pat-a", "a", CredentialRole.ACCOUNT),
                SafeCredentialRef("sel-ambig-pat-b", "b", CredentialRole.ACCOUNT),
            ),
        )
        val before = readOnlyCounts(db)
        val err = runCatching {
            NewApiCredentialSelector(db).resolveSync(provider, null, null)
        }.exceptionOrNull()
        assertTrue("多帳號省略 both 必須歧義拒絕,不取首項,實際:$err", err is IllegalArgumentException)
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun singleAccountLegacyOmittedBothResolves() = runBlocking {
        val db = memoryDb()
        val provider = "sel-legacy-p1"
        val account = "sel-legacy-a1"
        seedProviderAccount(db, provider, account)
        seedCredential(db, "sel-legacy-pat", account, enabled = true)
        seedCredential(db, "sel-legacy-key1", account, enabled = true)
        seedConfig(
            db, provider,
            listOf(
                SafeCredentialRef("sel-legacy-pat", "pat", CredentialRole.ACCOUNT),
                SafeCredentialRef("sel-legacy-key1", "k1", CredentialRole.INFERENCE),
            ),
        )
        val before = readOnlyCounts(db)
        val resolved = NewApiCredentialSelector(db).resolveSync(provider, null, null)
        assertEquals(account, resolved.accountId)
        assertEquals("sel-legacy-pat", resolved.pat.value)
        assertEquals("sel-legacy-key1", resolved.key?.value)
        assertReadOnlyUnchanged(db, before)
    }

    // ===== resolveSync: inference key 歧義 / 顯式 / 缺席 =====

    @Test
    fun multipleInferenceKeysWithoutExplicitRejected() = runBlocking {
        val db = memoryDb()
        val provider = "sel-multikey-p1"
        val account = "sel-multikey-a1"
        seedProviderAccount(db, provider, account)
        seedCredential(db, "sel-multikey-pat", account, enabled = true)
        seedCredential(db, "sel-multikey-k1", account, enabled = true)
        seedCredential(db, "sel-multikey-k2", account, enabled = true)
        seedConfig(
            db, provider,
            listOf(
                SafeCredentialRef("sel-multikey-pat", "pat", CredentialRole.ACCOUNT),
                SafeCredentialRef("sel-multikey-k1", "k1", CredentialRole.INFERENCE),
                SafeCredentialRef("sel-multikey-k2", "k2", CredentialRole.INFERENCE),
            ),
        )
        val before = readOnlyCounts(db)
        val selector = NewApiCredentialSelector(db)
        val errOmitted = runCatching { selector.resolveSync(provider, account, null) }.exceptionOrNull()
        assertTrue("多 key 省略顯式必須拒絕,實際:$errOmitted", errOmitted is IllegalArgumentException)
        val errBothOmitted = runCatching { selector.resolveSync(provider, null, null) }.exceptionOrNull()
        assertTrue(errBothOmitted is IllegalArgumentException)
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun explicitKeyResolvesCorrectKey() = runBlocking {
        val db = memoryDb()
        val provider = "sel-expkey-p1"
        val account = "sel-expkey-a1"
        seedProviderAccount(db, provider, account)
        seedCredential(db, "sel-expkey-pat", account, enabled = true)
        seedCredential(db, "sel-expkey-k1", account, enabled = true)
        seedCredential(db, "sel-expkey-k2", account, enabled = true)
        seedConfig(
            db, provider,
            listOf(
                SafeCredentialRef("sel-expkey-pat", "pat", CredentialRole.ACCOUNT),
                SafeCredentialRef("sel-expkey-k1", "k1", CredentialRole.INFERENCE),
                SafeCredentialRef("sel-expkey-k2", "k2", CredentialRole.INFERENCE),
            ),
        )
        val before = readOnlyCounts(db)
        val selector = NewApiCredentialSelector(db)
        val r1 = selector.resolveSync(provider, account, "sel-expkey-k1")
        assertEquals("sel-expkey-k1", r1.key?.value)
        assertEquals("sel-expkey-pat", r1.pat.value)
        val r2 = selector.resolveSync(provider, account, "sel-expkey-k2")
        assertEquals("sel-expkey-k2", r2.key?.value)
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun noInferenceKeyYieldsNullKey() = runBlocking {
        val db = memoryDb()
        val provider = "sel-nokey-p1"
        val account = "sel-nokey-a1"
        seedProviderAccount(db, provider, account)
        seedCredential(db, "sel-nokey-pat", account, enabled = true)
        seedConfig(
            db, provider,
            listOf(SafeCredentialRef("sel-nokey-pat", "pat", CredentialRole.ACCOUNT)),
        )
        val before = readOnlyCounts(db)
        val selector = NewApiCredentialSelector(db)
        val explicit = selector.resolveSync(provider, account, null)
        assertNull(explicit.key)
        assertEquals("sel-nokey-pat", explicit.pat.value)
        val omitted = selector.resolveSync(provider, null, null)
        assertNull(omitted.key)
        assertEquals(account, omitted.accountId)
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun disabledInferenceKeyNeverSelected() = runBlocking {
        val db = memoryDb()
        val provider = "sel-diskey-p1"
        val account = "sel-diskey-a1"
        seedProviderAccount(db, provider, account)
        seedCredential(db, "sel-diskey-pat", account, enabled = true)
        seedCredential(db, "sel-diskey-k-bad", account, enabled = false)
        seedCredential(db, "sel-diskey-k-good", account, enabled = true)
        seedConfig(
            db, provider,
            listOf(
                SafeCredentialRef("sel-diskey-pat", "pat", CredentialRole.ACCOUNT),
                SafeCredentialRef("sel-diskey-k-bad", "bad", CredentialRole.INFERENCE),
                SafeCredentialRef("sel-diskey-k-good", "good", CredentialRole.INFERENCE),
            ),
        )
        val before = readOnlyCounts(db)
        // 顯式 disabled key 必須拒絕,不靜默 fallback.
        val err = runCatching {
            NewApiCredentialSelector(db).resolveSync(provider, account, "sel-diskey-k-bad")
        }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        // 同帳號有效 key 顯式可達.
        val ok = NewApiCredentialSelector(db).resolveSync(provider, account, "sel-diskey-k-good")
        assertEquals("sel-diskey-k-good", ok.key?.value)
        assertReadOnlyUnchanged(db, before)
    }

    // ===== resolveSync: safe-ref 硬邊界 (blank/control/whitespace/sk-/overlong,無回顯) =====

    @Test
    fun blankRefsRejectedWithoutEcho() = runBlocking {
        val db = memoryDb()
        seedProviderAccount(db, "sel-blank-p1", "sel-blank-a1")
        seedCredential(db, "sel-blank-pat", "sel-blank-a1", enabled = true)
        seedConfig(
            db, "sel-blank-p1",
            listOf(SafeCredentialRef("sel-blank-pat", "pat", CredentialRole.ACCOUNT)),
        )
        val selector = NewApiCredentialSelector(db)
        val before = readOnlyCounts(db)
        val badInputs = listOf("", "   ")
        for (bad in badInputs) {
            val e1 = runCatching { selector.resolveSync("", null, null) }.exceptionOrNull()
            assertTrue("blank provider 必須拒絕", e1 is IllegalArgumentException)
            val e2 = runCatching { selector.resolveSync("sel-blank-p1", bad, null) }.exceptionOrNull()
            assertTrue("blank account 必須拒絕:[$bad]", e2 is IllegalArgumentException)
            assertEquals("Account required", e2?.message)
            val e3 = runCatching { selector.resolveSync("sel-blank-p1", "sel-blank-a1", bad) }.exceptionOrNull()
            assertTrue(e3 is IllegalArgumentException)
            assertEquals("Credential ref must not be blank", e3?.message)
        }
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun controlCharRefsRejectedWithoutEcho() = runBlocking {
        val db = memoryDb()
        seedProviderAccount(db, "sel-ctl-p1", "sel-ctl-a1")
        seedCredential(db, "sel-ctl-pat", "sel-ctl-a1", enabled = true)
        seedConfig(
            db, "sel-ctl-p1",
            listOf(SafeCredentialRef("sel-ctl-pat", "pat", CredentialRole.ACCOUNT)),
        )
        val selector = NewApiCredentialSelector(db)
        val before = readOnlyCounts(db)
        val bads = listOf("sel-ctl-\u0000-bad", "sel-ctl-\n-bad", "sel-ctl-\u007F-bad")
        for (bad in bads) {
            val e = runCatching { selector.resolveSync("sel-ctl-p1", bad, null) }.exceptionOrNull()
            assertTrue("control [$bad] 必須拒絕", e is IllegalArgumentException)
            assertFalse("拒絕訊息不得回顯 control ref", (e?.message ?: "").contains("sel-ctl-"))
            val e2 = runCatching { selector.resolveSync("sel-ctl-p1", "sel-ctl-a1", bad) }.exceptionOrNull()
            assertTrue(e2 is IllegalArgumentException)
            assertFalse((e2?.message ?: "").contains("sel-ctl-"))
        }
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun whitespaceRefsRejectedWithoutEcho() = runBlocking {
        val db = memoryDb()
        seedProviderAccount(db, "sel-ws-p1", "sel-ws-a1")
        seedCredential(db, "sel-ws-pat", "sel-ws-a1", enabled = true)
        seedConfig(
            db, "sel-ws-p1",
            listOf(SafeCredentialRef("sel-ws-pat", "pat", CredentialRole.ACCOUNT)),
        )
        val selector = NewApiCredentialSelector(db)
        val before = readOnlyCounts(db)
        val bads = listOf("sel ws pat", "sel-ws-pat\t", " sel-ws-a1")
        for (bad in bads) {
            val e = runCatching { selector.resolveSync("sel-ws-p1", bad, null) }.exceptionOrNull()
            assertTrue("whitespace [$bad] 必須拒絕", e is IllegalArgumentException)
            assertFalse("拒絕訊息不得回顯 whitespace ref", (e?.message ?: "").contains("sel ws"))
            val e2 = runCatching { selector.resolveSync("sel-ws-p1", "sel-ws-a1", bad) }.exceptionOrNull()
            assertTrue(e2 is IllegalArgumentException)
        }
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun skPrefixRefsRejectedWithoutEcho() = runBlocking {
        val db = memoryDb()
        seedProviderAccount(db, "sel-sk-p1", "sel-sk-a1")
        seedCredential(db, "sel-sk-pat", "sel-sk-a1", enabled = true)
        seedConfig(
            db, "sel-sk-p1",
            listOf(SafeCredentialRef("sel-sk-pat", "pat", CredentialRole.ACCOUNT)),
        )
        val selector = NewApiCredentialSelector(db)
        val before = readOnlyCounts(db)
        // 合成哨兵,非真 secret;斷言選擇器把 sk- 當 secret 拒絕且不回顯.
        val skCanary = "sk-sel-canary-0001"
        val e1 = runCatching { selector.resolveSync("sel-sk-p1", skCanary, null) }.exceptionOrNull()
        assertTrue("sk- account 必須拒絕", e1 is IllegalArgumentException)
        assertFalse("sk- 拒絕訊息不得回顯", (e1?.message ?: "").contains(skCanary))
        val e2 = runCatching { selector.resolveSync("sel-sk-p1", "sel-sk-a1", skCanary) }.exceptionOrNull()
        assertTrue(e2 is IllegalArgumentException)
        assertFalse((e2?.message ?: "").contains(skCanary))
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun overlongRefsRejectedWithoutEcho() = runBlocking {
        val db = memoryDb()
        seedProviderAccount(db, "sel-long-p1", "sel-long-a1")
        seedCredential(db, "sel-long-pat", "sel-long-a1", enabled = true)
        seedConfig(
            db, "sel-long-p1",
            listOf(SafeCredentialRef("sel-long-pat", "pat", CredentialRole.ACCOUNT)),
        )
        val selector = NewApiCredentialSelector(db)
        val before = readOnlyCounts(db)
        val overlong = "q".repeat(300)
        val e = runCatching { selector.resolveSync("sel-long-p1", overlong, null) }.exceptionOrNull()
        assertTrue("overlong account 必須拒絕", e is IllegalArgumentException)
        assertFalse("overlong 不得回顯", (e?.message ?: "").contains(overlong.take(16)))
        val e2 = runCatching { selector.resolveSync("sel-long-p1", "sel-long-a1", overlong) }.exceptionOrNull()
        assertTrue(e2 is IllegalArgumentException)
        assertFalse((e2?.message ?: "").contains(overlong.take(16)))
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun storedSkRefNeverAcceptedWithoutEcho() = runBlocking {
        val db = memoryDb()
        val provider = "sel-storedcanary-p1"
        val account = "sel-storedcanary-a1"
        seedProviderAccount(db, provider, account)
        // 合成哨兵列:即使 DB 列存在,config 出現 sk- 仍必須 fail-closed.
        seedCredential(db, "sk-sel-stored-canary-0002", account, enabled = true)
        seedCredential(db, "sel-storedcanary-pat-good", account, enabled = true)
        // 注意:toEntity 的 secret-guard 可能拒寫 sk- JSON;此處刻意只放合法 pat,
        // 另以顯式 sk- 輸入證明邊界.若未來允許寫入,選擇器仍須拒絕.
        seedConfig(
            db, provider,
            listOf(SafeCredentialRef("sel-storedcanary-pat-good", "good", CredentialRole.ACCOUNT)),
        )
        val before = readOnlyCounts(db)
        val skCanary = "sk-sel-stored-canary-0002"
        val e = runCatching {
            NewApiCredentialSelector(db).resolveSync(provider, account, skCanary)
        }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertEquals("Credential ref must not carry secret", e?.message)
        assertFalse((e?.message ?: "").contains(skCanary))
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun pureInferenceNeverSelectedAsPat() = runBlocking {
        val db = memoryDb()
        val provider = "sel-pureinf-p1"
        val account = "sel-pureinf-a1"
        seedProviderAccount(db, provider, account)
        seedCredential(db, "sel-pureinf-key1", account, enabled = true)
        seedConfig(
            db, provider,
            listOf(SafeCredentialRef("sel-pureinf-key1", "k1", CredentialRole.INFERENCE)),
        )
        val before = readOnlyCounts(db)
        val selector = NewApiCredentialSelector(db)
        val err = runCatching { selector.resolveSync(provider, account, null) }.exceptionOrNull()
        assertTrue("純 INFERENCE 不得被選為 PAT,實際:$err", err is IllegalArgumentException)
        assertFalse((err?.message ?: "").contains("sel-pureinf-key1"))
        // perf 視角:無 ACCOUNT ref 即匿名 null,不借 INFERENCE.
        val perf = selector.resolvePerfPat(provider, null)
        assertNull("純 INFERENCE perf 必須匿名 null,不借 key", perf)
        assertReadOnlyUnchanged(db, before)
    }

    // ===== resolvePerfPat: 匿名 / invalid-only 永不匿名 / 同帳號 fallback / B 不取 A =====

    @Test
    fun perfNoPatConfiguredReturnsAnonymousNull() = runBlocking {
        val db = memoryDb()
        val provider = "sel-perfanon-p1"
        val account = "sel-perfanon-a1"
        seedProviderAccount(db, provider, account)
        seedCredential(db, "sel-perfanon-key1", account, enabled = true)
        // config 只有 INFERENCE(== 無 PAT):匿名.
        seedConfig(
            db, provider,
            listOf(SafeCredentialRef("sel-perfanon-key1", "k1", CredentialRole.INFERENCE)),
        )
        val before = readOnlyCounts(db)
        val got = NewApiCredentialSelector(db).resolvePerfPat(provider, null)
        assertNull(got)
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun perfMissingConfigReturnsAnonymousNull() = runBlocking {
        val db = memoryDb()
        seedProviderAccount(db, "sel-perfmiss-p1", "sel-perfmiss-a1")
        // 完全無 provider_configurations 列:匿名,不拋.
        val before = readOnlyCounts(db)
        val got = NewApiCredentialSelector(db).resolvePerfPat("sel-perfmiss-p1", null)
        assertNull(got)
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun perfExplicitValidAccountWithoutConfiguredPatReturnsAnonymousNull() = runBlocking {
        val db = memoryDb()
        val provider = "sel-expanon-p1"
        val account = "sel-expanon-a1"
        seedProviderAccount(db, provider, account)
        seedCredential(db, "sel-expanon-key1", account, enabled = true)
        // config 只有 INFERENCE(真正無 ACCOUNT PAT):explicit valid account 仍匿名 null.
        seedConfig(
            db, provider,
            listOf(SafeCredentialRef("sel-expanon-key1", "k1", CredentialRole.INFERENCE)),
        )
        val before = readOnlyCounts(db)
        val selector = NewApiCredentialSelector(db)
        assertNull(
            "無 ACCOUNT role 時 explicit account 必須匿名,不借 INFERENCE",
            selector.resolvePerfPat(provider, account),
        )
        assertReadOnlyUnchanged(db, before)

        // 邊界:一旦 config 出現 ACCOUNT entry(即使 safe ref 空白 invalid),
        // 就必須 fail-closed,永不降級匿名或借用 INFERENCE.
        db.providerConfigDao().updateSnapshotJson(
            provider,
            ProviderManualConfig(
                providerId = provider,
                workflowId = "wf-$provider-sel",
                authMode = AuthMode.BEARER,
                credentials = listOf(
                    SafeCredentialRef("sel-expanon-key1", "k1", CredentialRole.INFERENCE),
                    SafeCredentialRef("", "empty-pat", CredentialRole.ACCOUNT),
                ),
            ).toEntity().snapshotJson,
        )
        val err = runCatching { selector.resolvePerfPat(provider, account) }.exceptionOrNull()
        assertTrue("configured empty ACCOUNT 不得匿名,實際:$err", err is IllegalArgumentException)
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun perfExplicitValidAccountMissingConfigReturnsAnonymousNull() = runBlocking {
        val db = memoryDb()
        val provider = "sel-expmiss-p1"
        val account = "sel-expmiss-a1"
        seedProviderAccount(db, provider, account)
        // 完全無 provider_configurations 列:explicit valid account 仍匿名 null,不拋.
        val before = readOnlyCounts(db)
        val selector = NewApiCredentialSelector(db)
        assertNull(
            "缺 config 時 explicit account 必須匿名,不拋",
            selector.resolvePerfPat(provider, account),
        )
        assertReadOnlyUnchanged(db, before)

        // 邊界:config 填入 dual-role ACCOUNT+INFERENCE 後必須 fail-closed,
        // 永不匿名,也不得把純 INFERENCE 當 PAT.
        seedConfig(
            db, provider,
            listOf(
                SafeCredentialRef("sel-expmiss-dup", "dual-pat", CredentialRole.ACCOUNT),
                SafeCredentialRef("sel-expmiss-dup", "dual-key", CredentialRole.INFERENCE),
            ),
        )
        val err = runCatching { selector.resolvePerfPat(provider, account) }.exceptionOrNull()
        assertTrue("configured dual-role ACCOUNT 不得匿名/inference,實際:$err", err is IllegalArgumentException)
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun perfInvalidOnlyPatNeverFallsBackToAnonymous() = runBlocking {
        val db = memoryDb()
        val provider = "sel-perfinv-p1"
        val account = "sel-perfinv-a1"
        seedProviderAccount(db, provider, account)
        seedCredential(db, "sel-perfinv-pat-bad", account, enabled = false)
        seedConfig(
            db, provider,
            listOf(SafeCredentialRef("sel-perfinv-pat-bad", "bad", CredentialRole.ACCOUNT)),
        )
        val before = readOnlyCounts(db)
        val err = runCatching {
            NewApiCredentialSelector(db).resolvePerfPat(provider, null)
        }.exceptionOrNull()
        assertTrue("已配置但全無效 PAT 必須拋出,永不靜默匿名,實際:$err", err is IllegalArgumentException)
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun perfValidSameAccountFallbackResolves() = runBlocking {
        val db = memoryDb()
        val provider = "sel-perffb-p1"
        val account = "sel-perffb-a1"
        seedProviderAccount(db, provider, account)
        seedCredential(db, "sel-perffb-pat-bad", account, enabled = false)
        seedCredential(db, "sel-perffb-pat-good", account, enabled = true)
        seedConfig(
            db, provider,
            listOf(
                SafeCredentialRef("sel-perffb-pat-bad", "bad", CredentialRole.ACCOUNT),
                SafeCredentialRef("sel-perffb-pat-good", "good", CredentialRole.ACCOUNT),
            ),
        )
        val before = readOnlyCounts(db)
        val got = NewApiCredentialSelector(db).resolvePerfPat(provider, null)
        assertNotNull(got)
        assertEquals(account, got!!.accountId)
        assertEquals("sel-perffb-pat-good", got.pat.value)
        assertReadOnlyUnchanged(db, before)
    }

    @Test
    fun perfExplicitBMustNotTakeAccountA() = runBlocking {
        val db = memoryDb()
        val provider = "sel-perfba-p1"
        val accountA = "sel-perfba-aA"
        val accountB = "sel-perfba-aB"
        seedProviderAccount(db, provider, accountA)
        seedProviderAccount(db, provider, accountB)
        seedCredential(db, "sel-perfba-pat-a", accountA, enabled = true)
        seedCredential(db, "sel-perfba-pat-b-bad", accountB, enabled = false)
        seedConfig(
            db, provider,
            listOf(
                SafeCredentialRef("sel-perfba-pat-a", "a", CredentialRole.ACCOUNT),
                SafeCredentialRef("sel-perfba-pat-b-bad", "b-bad", CredentialRole.ACCOUNT),
            ),
        )
        val before = readOnlyCounts(db)
        val err = runCatching {
            NewApiCredentialSelector(db).resolvePerfPat(provider, accountB)
        }.exceptionOrNull()
        assertTrue("顯式 B 無有效 PAT 不得借 A,實際:$err", err is IllegalArgumentException)
        assertFalse((err?.message ?: "").contains("sel-perfba-pat-a"))
        // 對照:顯式 A 可達.
        val okA = NewApiCredentialSelector(db).resolvePerfPat(provider, accountA)
        assertNotNull(okA)
        assertEquals("sel-perfba-pat-a", okA!!.pat.value)
        assertReadOnlyUnchanged(db, before)
    }

    // ===== 唯讀 + 取消傳播 =====

    @Test
    fun selectorPerformsNoWritesOnSuccessAndFailure() = runBlocking {
        val db = memoryDb()
        val provider = "sel-readonly-p1"
        val account = "sel-readonly-a1"
        seedProviderAccount(db, provider, account)
        seedCredential(db, "sel-readonly-pat", account, enabled = true)
        seedConfig(
            db, provider,
            listOf(SafeCredentialRef("sel-readonly-pat", "pat", CredentialRole.ACCOUNT)),
        )
        val selector = NewApiCredentialSelector(db)
        val before = readOnlyCounts(db)
        // 成功路徑.
        selector.resolveSync(provider, account, null)
        selector.resolvePerfPat(provider, account)
        assertReadOnlyUnchanged(db, before)
        // 失敗路徑亦不得寫入.
        runCatching { selector.resolveSync(provider, "sel-readonly-nope", null) }
        runCatching { selector.resolvePerfPat(provider, "sel-readonly-nope") }
        assertReadOnlyUnchanged(db, before)
        // 顯式斷言關鍵槽零增長.
        assertEquals(0L, tableCount(db, "samples"))
        assertEquals(0L, tableCount(db, "snapshot_facts"))
        assertEquals(0L, tableCount(db, "snapshot_heads"))
        assertEquals(0L, tableCount(db, "provider_perf_facts"))
        assertEquals(0L, tableCount(db, "provider_perf_heads"))
        assertEquals(0L, tableCount(db, "provider_account_usage_facts"))
        assertEquals(0L, tableCount(db, "provider_account_usage_heads"))
        assertEquals(0L, tableCount(db, "my_models"))
    }

    @Test
    fun cancelledContextPropagatesWithoutWrites() = runBlocking {
        val db = memoryDb()
        val provider = "sel-cancel-p1"
        val account = "sel-cancel-a1"
        seedProviderAccount(db, provider, account)
        seedCredential(db, "sel-cancel-pat", account, enabled = true)
        seedConfig(
            db, provider,
            listOf(SafeCredentialRef("sel-cancel-pat", "pat", CredentialRole.ACCOUNT)),
        )
        val selector = NewApiCredentialSelector(db)
        val before = readOnlyCounts(db)
        val cancelled = Job()
        cancelled.cancel()
        val errSync = runCatching {
            withContext(cancelled) { selector.resolveSync(provider, account, null) }
        }.exceptionOrNull()
        assertTrue("取消必須傳播 (sync),實際:$errSync", errSync is CancellationException)
        val errPerf = runCatching {
            withContext(cancelled) { selector.resolvePerfPat(provider, account) }
        }.exceptionOrNull()
        assertTrue("取消必須傳播 (perf),實際:$errPerf", errPerf is CancellationException)
        assertReadOnlyUnchanged(db, before)
    }

    // ===== 內嵌夾具 (僅本檔、合成 metadata、無網路/無 vault) =====

    private fun memoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AtlasDatabase::class.java,
        ).allowMainThreadQueries().build().also { open += it }

    private suspend fun seedProviderAccount(db: AtlasDatabase, provider: String, account: String) {
        runCatching { db.providerDao().insert(ProviderEntity(provider, "P $provider")) }
        db.accountDao().insert(AccountEntity(account, provider))
    }

    private suspend fun seedCredential(db: AtlasDatabase, credentialId: String, accountId: String, enabled: Boolean) {
        db.credentialDao().insert(CredentialEntity(credentialId, accountId, enabled = enabled))
    }

    private suspend fun seedConfig(db: AtlasDatabase, provider: String, refs: List<SafeCredentialRef>) {
        db.providerConfigDao().insert(
            ProviderManualConfig(
                providerId = provider,
                workflowId = "wf-$provider-sel",
                authMode = AuthMode.BEARER,
                credentials = refs,
            ).toEntity(),
        )
    }

    private fun tableCount(db: AtlasDatabase, table: String): Long {
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use {
            assertTrue("count $table 必須可讀", it.moveToFirst())
            return it.getLong(0)
        }
    }

    private fun readOnlyCounts(db: AtlasDatabase): Map<String, Long> =
        linkedMapOf(
            "samples" to tableCount(db, "samples"),
            "evidence" to tableCount(db, "evidence"),
            "snapshot_facts" to tableCount(db, "snapshot_facts"),
            "snapshot_heads" to tableCount(db, "snapshot_heads"),
            "provider_perf_facts" to tableCount(db, "provider_perf_facts"),
            "provider_perf_heads" to tableCount(db, "provider_perf_heads"),
            "provider_account_usage_facts" to tableCount(db, "provider_account_usage_facts"),
            "provider_account_usage_heads" to tableCount(db, "provider_account_usage_heads"),
            "my_models" to tableCount(db, "my_models"),
            "credential_availability" to tableCount(db, "credential_availability"),
        )

    private fun assertReadOnlyUnchanged(db: AtlasDatabase, before: Map<String, Long>) {
        val after = readOnlyCounts(db)
        assertEquals("唯讀選擇器不得寫入 history/telemetry/membership", before, after)
        if (before.isEmpty()) fail("before 不得為空")
    }
}
