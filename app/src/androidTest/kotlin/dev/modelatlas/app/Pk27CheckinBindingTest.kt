package dev.modelatlas.app

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.modelatlas.app.data.AccountEntity
import dev.modelatlas.app.data.CheckinEntity
import dev.modelatlas.app.data.CredentialAvailabilityEntity
import dev.modelatlas.app.data.CredentialEntity
import dev.modelatlas.app.data.EndpointEntity
import dev.modelatlas.app.data.OfferingEntity
import dev.modelatlas.app.data.ProviderEntity
import dev.modelatlas.app.data.ProviderManualConfig
import dev.modelatlas.app.data.toEntity
import dev.modelatlas.core.onboarding.AuthMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/**
 * S26 / PK-27, PRODUCT_SPEC §11: MainActivity -> AtlasScreen load/host -> header button
 * -> real VM -> Room exact (provider, device-local date, account) check-in.
 * A sorts before B. No copied selector/callback, network, inference or production fixture.
 * Null/unknown/disabled selection boundaries are injected only into the test's VM StateFlow:
 * the normal VM repairs/rejects those states, but the production UI must still fail closed.
 * ConnectionCardInteractionTest, ConnectionCardFlowTest and ProviderManagementViewModelTest
 * remain the TV-9/10 regression suites.
 */
@RunWith(AndroidJUnit4::class)
class Pk27CheckinBindingTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var app: AtlasApplication
    private lateinit var viewModel: AtlasViewModel
    private val providers = mutableListOf<String>()

    @Before
    fun setUp() {
        app = compose.activity.application as AtlasApplication
        runBlocking { withTimeout(TIMEOUT_MS) { app.ready.first { it } } }
        compose.runOnIdle {
            viewModel = ViewModelProvider(compose.activity)[AtlasViewModel::class.java]
        }
    }

    @After
    fun tearDown() {
        compose.activityRule.scenario.close()
        if (providers.isEmpty()) return
        // UUID-owned rows only; stop collectors first and delete children before parents.
        val placeholders = providers.joinToString(",") { "?" }
        val args = providers.toTypedArray<Any?>()
        val byProvider = "providerId IN ($placeholders)"
        val byAccount = "accountId IN (SELECT accountId FROM accounts WHERE $byProvider)"
        val db = app.database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM checkins WHERE $byProvider", args)
            db.execSQL("DELETE FROM provider_configurations WHERE $byProvider", args)
            db.execSQL("DELETE FROM credential_availability WHERE $byAccount", args)
            db.execSQL("DELETE FROM credentials WHERE $byAccount", args)
            db.execSQL("DELETE FROM offerings WHERE $byProvider", args)
            db.execSQL("DELETE FROM endpoints WHERE $byProvider", args)
            db.execSQL("DELETE FROM accounts WHERE $byProvider", args)
            db.execSQL("DELETE FROM providers WHERE $byProvider", args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Test
    fun bothUnchecked_selectedBHeaderPending_completeWritesOnlyB() {
        // TV-1 / TV-4: the second connection, never the provider's first connection.
        val f = seedPair()
        openDetail(f)
        choose(f.b, LABEL_B)
        assertHeaderPending(true)
        completeHeader()
        awaitPending(f, a = true, b = false)
        assertHeaderEventuallyNotPending()
        assertOnlyB(f)
        assertAListBadgePending(f)
    }

    @Test
    fun bAlreadyChecked_aPending_headerNotPending_andCompleteStillTargetsB() {
        // TV-2: false-positive pending and provider-first writes are independently guarded.
        val f = seedPair(bChecked = true)
        val oldB = checkin(f, f.accountB)!!
        openDetail(f)
        choose(f.b, LABEL_B)
        assertHeaderPending(false)
        completeHeader()
        compose.waitUntil(TIMEOUT_MS) { checkin(f, f.accountB)?.completedAt != oldB.completedAt }
        assertOnlyB(f)
        assertAListBadgePending(f)
    }

    @Test
    fun aAlreadyChecked_bPending_headerPending_andARecordIsUnchanged() {
        // TV-3: false-negative badge plus the exact write target, including A's timestamp.
        val f = seedPair(aChecked = true)
        val oldA = checkin(f, f.accountA)!!
        openDetail(f)
        choose(f.b, LABEL_B)
        assertHeaderPending(true)
        completeHeader()
        awaitPending(f, a = false, b = false)
        assertHeaderEventuallyNotPending()
        assertEquals(oldA, checkin(f, f.accountA))
        assertNotNull(checkin(f, f.accountB)?.completedAt)
        assertEquals(setOf(f.accountA, f.accountB), rows(f).map { it.accountId }.toSet())
    }

    @Test
    fun clearedSelection_hidesPendingAndComplete_withoutFallback() {
        val f = seedPair()
        openDetail(f)
        assertHeaderPending(true)
        injectSelection(null)
        assertScopeAbsent(null)
        assertTrue(rows(f).isEmpty())
    }

    @Test
    fun unknownSelection_hidesPendingAndComplete_withoutFallback() {
        val f = seedPair()
        openDetail(f)
        assertHeaderPending(true)
        val missing = "${f.provider}-missing"
        injectSelection(missing)
        assertScopeAbsent(missing)
        assertTrue(rows(f).isEmpty())
    }

    @Test
    fun disabledSelectedB_hidesPendingAndComplete_withoutBorrowingEnabledA() {
        // TV-6 uses a truly disabled persisted credential, not an invented connection row.
        val f = seedPair(bEnabled = false)
        openDetail(f)
        assertHeaderPending(true)
        assertFalse(viewModel.state.value.managedConnections.single { it.id == f.b }.enabled)
        injectSelection(f.b)
        assertScopeAbsent(f.b)
        assertTrue(rows(f).isEmpty())
    }

    @Test
    fun reselectBToA_headerFollowsA_andCompletionFollowsTheNewSelection() {
        // TV-7: B completed, A pending; both selection changes go through connectionChoices.
        val f = seedPair(bChecked = true)
        val oldB = checkin(f, f.accountB)!!
        openDetail(f)
        choose(f.b, LABEL_B)
        assertHeaderPending(false)
        choose(f.a, LABEL_A)
        assertHeaderPending(true)
        completeHeader()
        awaitPending(f, a = false, b = false)
        assertHeaderEventuallyNotPending()
        assertNotNull(checkin(f, f.accountA)?.completedAt)
        assertEquals(oldB, checkin(f, f.accountB))
        assertEquals(2, rows(f).size)
    }

    @Test
    fun anonymousNone_selectedB_stillCompletesOnlyItsExactAccount() {
        // TV-8: true offering/account anonymous UNION rows, no dummy credential or vault key.
        val f = seedPair(anonymous = true, aChecked = true)
        val oldA = checkin(f, f.accountA)!!
        compose.runOnIdle { viewModel.selectConnection(f.b) }
        compose.waitUntil(TIMEOUT_MS) { viewModel.state.value.selectedId == f.b }
        openDetail(f, selectA = false)
        assertTrue(viewModel.state.value.managedConnections.single { it.id == f.b }.credentialId.isEmpty())
        assertHeaderPending(true)
        completeHeader()
        awaitPending(f, a = false, b = false)
        assertHeaderEventuallyNotPending()
        assertNotNull(checkin(f, f.accountB)?.completedAt)
        assertEquals(oldA, checkin(f, f.accountA))
        assertEquals(2, rows(f).size)
    }

    @Test
    fun selectedConnectionFromAnotherProvider_hidesPendingAndComplete() {
        val f = seedPair()
        val foreign = seedPair()
        openDetail(f)
        compose.runOnIdle { viewModel.selectConnection(foreign.b) }
        compose.waitUntil(TIMEOUT_MS) { viewModel.state.value.selectedId == foreign.b }
        assertScopeAbsent(foreign.b)
        assertTrue(rows(f).isEmpty())
        assertTrue(rows(foreign).isEmpty())
    }

    private data class Fixture(
        val provider: String,
        val accountA: String,
        val accountB: String,
        val a: String,
        val b: String,
        val date: String,
    )

    private fun seedPair(
        aChecked: Boolean = false,
        bChecked: Boolean = false,
        bEnabled: Boolean = true,
        anonymous: Boolean = false,
    ): Fixture {
        val p = "pk27-${UUID.randomUUID()}"
        providers += p
        val offering = "$p-offering"
        val accountA = "$p-account-a"
        val accountB = "$p-account-b"
        val f = Fixture(
            p, accountA, accountB,
            if (anonymous) "anon:$offering:$accountA" else "$p-0a",
            if (anonymous) "anon:$offering:$accountB" else "$p-1b",
            LocalDate.now().toString(),
        )
        assertTrue("fixture A must sort first", f.a < f.b)
        runBlocking {
            app.database.withTransaction {
                val db = app.database
                db.providerDao().insert(ProviderEntity(p, "PK27 Provider", checkinUrl = CHECKIN_URL, requiresDailyCheckin = true))
                db.accountDao().insert(AccountEntity(accountA, p))
                db.accountDao().insert(AccountEntity(accountB, p))
                db.endpointDao().insert(EndpointEntity("$p-endpoint", p, BASE_URL, "$BASE_URL/chat/completions"))
                db.offeringDao().insert(OfferingEntity(
                    id = offering, providerId = p, endpointId = "$p-endpoint",
                    providerModelId = "pk27-model", manual = true, source = "USER", hidden = false,
                ))
                if (anonymous) {
                    db.providerConfigDao().insert(ProviderManualConfig(
                        providerId = p, workflowId = "$p-workflow", authMode = AuthMode.NONE,
                        anonymousConfirmed = true,
                    ).toEntity())
                } else {
                    db.credentialDao().insert(CredentialEntity("$p-key-a", accountA, LABEL_A))
                    db.credentialDao().insert(CredentialEntity("$p-key-b", accountB, LABEL_B, enabled = bEnabled))
                    db.availabilityDao().insert(CredentialAvailabilityEntity(
                        id = f.a, offeringId = offering, accountId = accountA, credentialId = "$p-key-a",
                    ))
                    db.availabilityDao().insert(CredentialAvailabilityEntity(
                        id = f.b, offeringId = offering, accountId = accountB, credentialId = "$p-key-b",
                    ))
                }
                if (aChecked) db.checkinDao().upsert(CheckinEntity(p, f.date, completedAt = 1L, accountId = accountA))
                if (bChecked) db.checkinDao().upsert(CheckinEntity(p, f.date, completedAt = 2L, accountId = accountB))
            }
        }
        compose.waitUntil(TIMEOUT_MS) {
            val state = viewModel.state.value
            val mine = state.managedConnections.filter { it.providerId == p }
            mine.map { it.id } == listOf(f.a, f.b) &&
                state.connections.any { it.id == f.a } &&
                (!bEnabled || state.connections.any { it.id == f.b }) &&
                mine.single { it.id == f.b }.enabled == bEnabled &&
                mine.single { it.id == f.a }.checkinPending == !aChecked &&
                mine.single { it.id == f.b }.checkinPending == !bChecked
        }
        return f
    }

    private fun openDetail(f: Fixture, selectA: Boolean = true) {
        if (selectA) {
            compose.runOnIdle { viewModel.selectConnection(f.a) }
            compose.waitUntil(TIMEOUT_MS) { viewModel.state.value.selectedId == f.a }
        }
        compose.onNodeWithText(text(R.string.tab_providers)).performClick()
        // PK-20 後列表為 LazyColumn：未組合的卡片無法 performScrollTo——先用
        // performScrollToNode 把列表捲到該卡（觸發組合），再點擊。
        compose.onNodeWithTag("providers_tab_list").performScrollToNode(hasTestTag("connection_card_${f.b}"))
        compose.onNodeWithTag("connection_card_${f.b}").performClick()
        compose.waitUntil(TIMEOUT_MS) { exists(text(R.string.action_open_checkin)) }
        headerToTop()
    }

    private fun choose(connectionId: String, label: String) {
        headerToTop()
        // Compose the sticky tab row first (huge header + status lines on the small test
        // screen can keep it out of the composition window; scrollTo composes it).
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        compose.onNode(
            hasText(text(R.string.provider_detail_models)) and hasClickAction() and
                hasAnyAncestor(hasScrollToIndexAction()),
        ).performScrollTo().performClick()
        // Content-addressed (S4 wired the scoped-status item: positional index(2) is stale —
        // the choice label itself is the stable address).
        val choice = hasClickAction() and hasText(label, substring = true)
        compose.onNode(choice).performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) { viewModel.state.value.selectedId == connectionId }
        // A current choice becomes disabled only after the selected action scope is loaded.
        compose.waitUntil(TIMEOUT_MS) {
            runCatching { compose.onNode(choice).assertIsNotEnabled() }.isSuccess
        }
        headerToTop()
    }

    private fun headerToTop() {
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText(text(R.string.action_open_checkin)).assertExists()
    }

    private fun assertHeaderPending(pending: Boolean) {
        headerToTop()
        val badge = compose.onNodeWithText(text(R.string.provider_detail_checkin_pending))
        if (pending) badge.assertExists() else badge.assertDoesNotExist()
        compose.onNodeWithText(text(R.string.action_complete_checkin)).assertExists()
    }

    private fun assertHeaderEventuallyNotPending() {
        headerToTop()
        compose.waitUntil(TIMEOUT_MS) { !exists(text(R.string.provider_detail_checkin_pending)) }
        assertHeaderPending(false)
    }

    private fun completeHeader() {
        compose.onNodeWithText(text(R.string.action_complete_checkin)).performScrollTo().performClick()
    }

    private fun awaitPending(f: Fixture, a: Boolean, b: Boolean) {
        compose.waitUntil(TIMEOUT_MS) {
            val rows = viewModel.state.value.managedConnections
            rows.singleOrNull { it.id == f.a }?.checkinPending == a &&
                rows.singleOrNull { it.id == f.b }?.checkinPending == b
        }
        assertNull(viewModel.state.value.error)
    }

    private fun assertOnlyB(f: Fixture) {
        assertNull(checkin(f, f.accountA))
        val row = rows(f).single()
        assertEquals(f.provider, row.providerId)
        assertEquals(f.accountB, row.accountId)
        assertEquals(f.date, row.date)
        assertEquals("USER", row.source)
        assertNotNull(row.completedAt)
    }

    private fun assertAListBadgePending(f: Fixture) {
        compose.onNodeWithText(text(R.string.provider_detail_back)).performClick()
        compose.onNodeWithTag("providers_tab_list").performScrollToNode(hasTestTag("connection_card_${f.a}"))
        compose.onNodeWithTag("connection_card_${f.a}").performScrollTo()
        compose.onNode(
            hasText(text(R.string.notification_rule_pending_checkin)) and
                hasAnyAncestor(hasTestTag("connection_card_${f.a}")),
            useUnmergedTree = true,
        ).assertExists()
        assertTrue(viewModel.state.value.managedConnections.single { it.id == f.a }.checkinPending)
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectSelection(id: String?) {
        compose.runOnIdle {
            val field = AtlasViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
            val state = field.get(viewModel) as MutableStateFlow<AtlasUiState>
            state.value = state.value.copy(selectedId = id)
        }
    }

    private fun assertScopeAbsent(selectedId: String?) {
        headerToTop()
        compose.waitUntil(TIMEOUT_MS) {
            !exists(text(R.string.action_complete_checkin)) &&
                !exists(text(R.string.provider_detail_checkin_pending))
        }
        assertEquals("boundary selection must not have been repaired during this assertion", selectedId, viewModel.state.value.selectedId)
        compose.onNodeWithText(text(R.string.action_open_checkin)).assertExists()
        compose.onNodeWithText(text(R.string.action_complete_checkin)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.provider_detail_checkin_pending)).assertDoesNotExist()
    }

    private fun checkin(f: Fixture, account: String): CheckinEntity? = runBlocking {
        app.database.checkinDao().get(f.provider, f.date, account)
    }

    private fun rows(f: Fixture): List<CheckinEntity> = runBlocking {
        app.database.checkinDao().listByProviderDate(f.provider, f.date)
    }

    private fun text(id: Int): String = compose.activity.getString(id)

    private fun exists(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val BASE_URL = "https://pk27.fixture.invalid/v1"
        const val CHECKIN_URL = "https://pk27.fixture.invalid/checkin"
        const val LABEL_A = "PK27 Key A"
        const val LABEL_B = "PK27 Key B"
    }
}
