package dev.modelatlas.app.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.MainActivity
import dev.modelatlas.app.R
import dev.modelatlas.app.dedicatedOpKey
import dev.modelatlas.app.data.ConnectionRecord
import dev.modelatlas.app.data.CredentialAvailabilityEntity
import dev.modelatlas.app.data.OfferingEntity
import dev.modelatlas.app.data.ProviderManualConfig
import dev.modelatlas.app.data.toEntity
import dev.modelatlas.core.network.EndpointPolicy
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.SafeCredentialRef
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.UUID
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

/**
 * UI-INTEL-001A ConnectionCard compact slice — real MainActivity production flow (device).
 *
 * TRACEABILITY: UI-INTEL-001A (PROVIDER_MODEL_INTELLIGENCE_CONTRACT §4 / §6 / §23.8 / §23.11,
 * PRODUCT_SPEC §18), D-017. Unique write scope: this file only. Main owns the UI production.
 *
 * Only the true caller path is exercised: `MainActivity` → real `AtlasViewModel` → real
 * `AtlasApplication` (real Room + real Keystore vault) → `AtlasScreen` → production
 * `ConnectionCard` tags. No navigation/callback lambda is copied into the test; the Providers
 * tab, the primary face, the system Back dispatcher and the More sheet are exercised on the
 * running activity. Fixtures are real persisted rows seeded through real repository methods and
 * cleaned narrowly in FK-safe order (only this file's UUID providers). No mock product data,
 * no real key, no network inference.
 *
 * Cases (4):
 *  1. primary face → correct Provider Detail, UI back / system Back both return, selected
 *     connection unchanged, zero new inference samples;
 *  2. More sheet selects the exact connection id; enable/disable toggle is reactive;
 *  3. delete confirmation: cancel zero effect; target-named confirm removes only the target
 *     credential and all of its offering cards; card-local dialog state stays keyed when an
 *     earlier card disappears while the dialog is open;
 *  4. check-in is account-scoped (B completion leaves A pending) and opening the external
 *     check-in URL (intercepted) records no completion.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionCardFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var app: AtlasApplication
    private lateinit var viewModel: AtlasViewModel
    private val providerIds = mutableListOf<String>()
    private val credentialRefs = mutableListOf<String>()
    private var externalMonitor: Instrumentation.ActivityMonitor? = null

    @Before
    fun setUp() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "this test must not run on the main thread" }
        app = compose.activity.application as AtlasApplication
        runBlocking { withTimeout(TIMEOUT_MS) { app.ready.first { it } } }
        compose.runOnIdle {
            viewModel = ViewModelProvider(compose.activity)[AtlasViewModel::class.java]
        }
    }

    @After
    fun tearDown() {
        externalMonitor?.let {
            runCatching { InstrumentationRegistry.getInstrumentation().removeMonitor(it) }
        }
        externalMonitor = null
        // Close the activity/VM first so its collectors stop before rows disappear.
        runCatching { compose.activityRule.scenario.close() }
        for (ref in credentialRefs) runCatching { app.vault.remove(ref) }
        credentialRefs.clear()
        deleteOwnRows()
        providerIds.clear()
    }

    // ---- 1. 整卡導航是真 caller，不等於選取，也不產生 sample ----

    @Test
    fun primaryFaceNavigatesToCorrectProviderDetailWithoutSelectingOrSamples() {
        val p = UUID.randomUUID().toString()
        val q = UUID.randomUUID().toString()
        val aId = seedConnection(
            providerId = p, availabilityId = "$p-0a", accountId = "$p-acct-0a",
            credentialId = "$p-cred-0a", endpointId = "$p-ep", offeringId = "$p-off",
            modelId = "model-a", name = "Card Flow P", baseUrl = BASE_P,
        )
        val bId = attachKey(
            providerId = p, offeringId = "$p-off", accountId = "$p-acct-0b",
            credentialId = "$p-cred-0b", availabilityId = "$p-1b",
            credentialLabel = "Key B", baseUrl = BASE_P,
        )
        val qId = seedConnection(
            providerId = q, availabilityId = "$q-0x", accountId = "$q-acct-0x",
            credentialId = "$q-cred-0x", endpointId = "$q-ep", offeringId = "$q-off",
            modelId = "model-q", name = "Card Flow Q", baseUrl = BASE_Q,
        )

        openProviders()
        awaitManagedCards(aId, bId, qId)
        compose.waitUntil(TIMEOUT_MS) { viewModel.state.value.selectedId != null }

        val selectedBefore = viewModel.state.value.selectedId
        assertNotNull("fixtures must auto-select a connection", selectedBefore)
        val samplesBefore = sampleCount(providerIds.toList())

        // Primary face of B (same provider, different key): P detail, not Q.
        tapCard(bId)
        awaitText(compose.activity.getString(R.string.provider_detail_back))
        assertTrue("tapping P's card must open P's detail", textExists(BASE_P))
        assertFalse("tapping P's card must not open another provider", textExists(BASE_Q))
        assertFalse("detail must replace the card list", tagExists(cardTag(bId)))
        assertEquals(
            "card navigation must not change the selected connection",
            selectedBefore, viewModel.state.value.selectedId,
        )

        // Actual system Back via the configured dispatcher (AtlasScreen BackHandler).
        pressSystemBack()
        awaitCard(bId)
        assertFalse("system Back must leave detail", textExists(BASE_P))

        // Foreign provider card: correct detail, then UI back button.
        tapCard(qId)
        awaitText(BASE_Q)
        assertTrue(textExists(BASE_Q))
        assertEquals(
            "cross-provider navigation must not change the selected connection",
            selectedBefore, viewModel.state.value.selectedId,
        )

        compose.onNodeWithText(compose.activity.getString(R.string.provider_detail_back)).performClick()
        awaitCard(qId)

        assertEquals(
            "navigation must not create inference telemetry",
            samplesBefore, sampleCount(providerIds.toList()),
        )
    }

    // ---- 2. More sheet：精確選取＋憑證啟停 reactive ----

    @Test
    fun moreSheetSelectsExactConnectionAndCredentialToggleIsReactive() {
        val p = UUID.randomUUID().toString()
        val aId = seedConnection(
            providerId = p, availabilityId = "$p-0a", accountId = "$p-acct-2a",
            credentialId = "$p-cred-2a", endpointId = "$p-ep2", offeringId = "$p-off2",
            modelId = "model-2a", name = "Card Toggle P", baseUrl = BASE_P,
        )
        val bId = attachKey(
            providerId = p, offeringId = "$p-off2", accountId = "$p-acct-2b",
            credentialId = "$p-cred-2b", availabilityId = "$p-1b",
            credentialLabel = "Key B", baseUrl = BASE_P,
        )

        openProviders()
        awaitManagedCards(aId, bId)
        // Establish a known production baseline through the real VM so the More sheet's
        // "select" row is present for B regardless of unrelated legacy rows.
        compose.runOnIdle { viewModel.selectConnection(aId) }
        compose.waitUntil(TIMEOUT_MS) { viewModel.state.value.selectedId == aId }

        openMore(bId)
        awaitTag("connection_menu_$bId")
        assertFalse(
            "opening More must not navigate to detail",
            textExists(compose.activity.getString(R.string.provider_detail_back)),
        )

        tapSheet("connection_select_$bId")
        compose.waitUntil(TIMEOUT_MS) { viewModel.state.value.selectedId == bId }
        assertFalse(
            "select must use the exact connection id, not the provider id",
            viewModel.state.value.selectedId == p,
        )
        compose.waitForIdle()
        scrollListTo(cardTag(bId))
        compose.onNodeWithTag(cardTag(bId)).assertIsSelected()

        // Disable the selected credential through the same open sheet.
        tapSheet("connection_toggle_enabled_$bId")
        compose.waitUntil(TIMEOUT_MS) {
            viewModel.state.value.managedConnections.firstOrNull { it.id == bId }?.enabled == false &&
                viewModel.state.value.selectedId != bId
        }
        assertFalse(
            "a disabled credential must not stay selected",
            viewModel.state.value.selectedId == bId,
        )
        compose.waitForIdle()
        assertTrue(
            "disabled badge must be reactive",
            cardHasText(bId, compose.activity.getString(R.string.credential_disabled)),
        )

        // Re-enable from the still-open sheet.
        tapSheet("connection_toggle_enabled_$bId")
        compose.waitUntil(TIMEOUT_MS) {
            viewModel.state.value.managedConnections.firstOrNull { it.id == bId }?.enabled == true
        }
        compose.waitForIdle()
        assertFalse(
            "disabled badge must clear after re-enable",
            cardHasText(bId, compose.activity.getString(R.string.credential_disabled)),
        )
    }

    // ---- 3. 刪除確認：cancel 零副作用、confirm 只影響目標 credential 的全部 offering ----

    @Test
    fun deleteConfirmationCancelHasNoEffectAndConfirmRemovesOnlyTargetCredentialOfferings() {
        val p = UUID.randomUUID().toString()
        val providerName = "Card Delete P"
        val dCred = "$p-cred-3d"
        val bCred = "$p-cred-3b"
        val sCred = "$p-cred-3s"
        val dId = seedConnection(
            providerId = p, availabilityId = "$p-0d", accountId = "$p-acct-3d",
            credentialId = dCred, endpointId = "$p-ep3", offeringId = "$p-off3",
            modelId = "model-3d", name = providerName, baseUrl = BASE_P,
        )
        val b1 = attachKey(
            providerId = p, offeringId = "$p-off3", accountId = "$p-acct-3b",
            credentialId = bCred, availabilityId = "$p-1b1",
            credentialLabel = CRED_B, baseUrl = BASE_P,
        )
        val b2 = addSecondOffering(
            providerId = p, endpointId = "$p-ep3", accountId = "$p-acct-3b",
            credentialId = bCred, offeringId = "$p-off3b", availabilityId = "$p-1b2",
            modelId = "model-3b2",
        )
        val sId = attachKey(
            providerId = p, offeringId = "$p-off3", accountId = "$p-acct-3s",
            credentialId = sCred, availabilityId = "$p-2s",
            credentialLabel = CRED_S, baseUrl = BASE_P,
        )

        openProviders()
        awaitManagedCards(dId, b1, b2, sId)

        // Cancel path: dialog opens, cancel closes it with zero effect.
        openMore(b1)
        awaitTag("connection_menu_$b1")
        tapSheet("connection_delete_$b1")
        awaitTag("connection_delete_confirm_$b1")
        compose.onNodeWithText(compose.activity.getString(R.string.action_cancel)).performClick()
        awaitGoneTag("connection_delete_confirm_$b1")
        assertEquals("cancel must not delete the credential", 2L, availabilityCount(bCred))
        assertTrue(listHasTag(cardTag(b1)))
        assertTrue(listHasTag(cardTag(b2)))

        // Confirm path: target-named dialog; earlier card removal must not migrate card state.
        openMore(b1)
        awaitTag("connection_menu_$b1")
        tapSheet("connection_delete_$b1")
        awaitTag("connection_delete_confirm_$b1")
        assertTrue(
            "confirmation must name the exact provider + credential target",
            textExists(
                compose.activity.getString(R.string.connection_card_delete_target, providerName, CRED_B),
            ),
        )

        runBlocking { app.credentialLifecycle.delete(dCred) }
        // No list scroll here: the open delete dialog owns the foreground window, so the
        // removal signal is the real VM projection rather than a lazy-node query.
        compose.waitUntil(TIMEOUT_MS) {
            viewModel.state.value.managedConnections.none { it.id == dId }
        }
        assertTrue(
            "keyed card state must survive an earlier card being removed",
            tagExists("connection_delete_confirm_$b1"),
        )
        assertFalse(
            "dialog state must not migrate to the next card",
            tagExists("connection_delete_confirm_$b2"),
        )
        assertFalse(tagExists("connection_delete_confirm_$sId"))

        compose.onNodeWithTag("connection_delete_confirm_$b1").performClick()
        compose.waitUntil(TIMEOUT_MS) {
            viewModel.state.value.managedConnections.none { it.id == b1 || it.id == b2 }
        }
        // Lazy list: a plain node query cannot distinguish "removed" from "scrolled out",
        // so the survivor must be scrolled to and the removed cards must be unreachable.
        assertTrue("unrelated credential must survive", listHasTag(cardTag(sId)))
        assertFalse("target card must leave the lazy list", listHasTag(cardTag(b1)))
        assertFalse("second offering card must leave the lazy list", listHasTag(cardTag(b2)))
        assertEquals("both offering cards of the target credential must go", 0L, availabilityCount(bCred))
        assertEquals("survivor keeps exactly one offering card", 1L, availabilityCount(sCred))
        assertEquals("earlier removed credential keeps no availability", 0L, availabilityCount(dCred))
        assertEquals("target credential metadata must be gone", 0L, credentialRowCount(bCred))
        assertEquals("survivor credential metadata must remain", 1L, credentialRowCount(sCred))
    }

    // ---- 4. 簽到 account 隔離＋外部開啟不得完成 ----

    @Test
    fun checkinIsAccountScopedAndExternalOpenDoesNotComplete() {
        val p = UUID.randomUUID().toString()
        val aId = seedConnection(
            providerId = p, availabilityId = "$p-0ka", accountId = "$p-acct-4a",
            credentialId = "$p-cred-4a", endpointId = "$p-ep4", offeringId = "$p-off4",
            modelId = "model-4", name = "Card Checkin P", baseUrl = BASE_K,
        )
        val bId = attachKey(
            providerId = p, offeringId = "$p-off4", accountId = "$p-acct-4b",
            credentialId = "$p-cred-4b", availabilityId = "$p-1kb",
            credentialLabel = "Key B", baseUrl = BASE_K,
        )
        runBlocking { app.repository.setProviderCheckinConfig(p, CHECKIN_URL, true) }

        openProviders()
        awaitManagedCards(aId, bId)
        awaitCheckinPending(p, aId, bId)
        assertEquals("no check-in record exists yet", 0L, checkinCount(p))

        // External URL open is intercepted (never launches a real browser) and must not complete.
        val monitor = Instrumentation.ActivityMonitor(
            IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("https") },
            Instrumentation.ActivityResult(Activity.RESULT_OK, null),
            true,
        )
        externalMonitor = monitor
        InstrumentationRegistry.getInstrumentation().addMonitor(monitor)
        openMore(aId)
        awaitTag("connection_menu_$aId")
        tapSheet("connection_checkin_open_$aId")
        compose.waitUntil(TIMEOUT_MS) { monitor.hits >= 1 }
        assertEquals("opening the check-in page must not record completion", 0L, checkinCount(p))
        assertTrue("account A must remain pending after opening the URL", pendingOf(aId))
        dismissSheetAndWait(aId)

        // Configure through the actual card → VM → Room caller. One card's edit applies
        // to both accounts of this provider, without recording anyone's completion.
        openMore(aId)
        tapSheet("connection_configure_checkin_$aId")
        awaitTag("connection_checkin_input_$aId")
        assertTrue(textExists(compose.activity.getString(R.string.connection_card_checkin_shared)))
        val updatedUrl = "https://card-flow-checkin.fixture.invalid/updated"
        compose.onNodeWithTag("connection_checkin_input_$aId").performTextReplacement(updatedUrl)
        compose.onNodeWithTag("connection_checkin_save_$aId").performClick()
        compose.waitUntil(TIMEOUT_MS) {
            val rows = viewModel.state.value.managedConnections.filter { it.id == aId || it.id == bId }
            rows.size == 2 && rows.all { it.checkinUrl == updatedUrl && it.checkinPending } &&
                !viewModel.state.value.saving
        }
        assertEquals("configuration must not complete check-in", 0L, checkinCount(p))

        // Complete only account B: its exact account record appears, A stays pending.
        openMore(bId)
        awaitTag("connection_menu_$bId")
        tapSheet("connection_checkin_complete_$bId")
        compose.waitUntil(TIMEOUT_MS) { !pendingOf(bId) && pendingOf(aId) }

        val today = LocalDate.now().toString()
        assertNull(
            "account A must not gain a completion record",
            runBlocking { app.repository.getCheckin(p, today, "$p-acct-4a") },
        )
        assertNotNull(
            "account B must own the completion record",
            runBlocking { app.repository.getCheckin(p, today, "$p-acct-4b") },
        )
        assertEquals("exactly one check-in record for this provider", 1L, checkinCount(p))
    }

    // ---- 5. CR-002 exact-scope dedicated status：A 成功時間／B AUTH，互不外洩 ----

    @Test
    fun dedicatedScopedStatusReactsPerAccountWithoutSiblingLeak() {
        val p = UUID.randomUUID().toString()
        val accountA = "$p-acct-sa"
        val accountB = "$p-acct-sb"
        val credA = "$p-cred-sa"
        val credB = "$p-cred-sb"
        val aId = seedConnection(
            providerId = p, availabilityId = "$p-0sa", accountId = accountA,
            credentialId = credA, endpointId = "$p-ep-sa", offeringId = "$p-off-sa",
            modelId = GROQ_MODEL, name = "Scoped Flow P", baseUrl = BASE_P,
            credentialLabel = "Key A",
        )
        val bId = attachKey(
            providerId = p, offeringId = "$p-off-sa", accountId = accountB,
            credentialId = credB, availabilityId = "$p-1sb",
            credentialLabel = "Key B", baseUrl = BASE_P,
        )
        // Real config routes this UUID provider to the local-only groq family: A carries an
        // INFERENCE key (sync succeeds), B an ACCOUNT key (role rejected -> AUTH). No network.
        runBlocking {
            app.database.providerConfigDao().insert(
                ProviderManualConfig(
                    providerId = p,
                    workflowId = "wf-" + UUID.randomUUID(),
                    authMode = AuthMode.BEARER,
                    group = "groq",
                    credentials = listOf(
                        SafeCredentialRef(credA, "Key A", CredentialRole.INFERENCE, accountA),
                        SafeCredentialRef(credB, "Key B", CredentialRole.ACCOUNT, accountB),
                    ),
                ).toEntity(),
            )
        }

        openProviders()
        awaitManagedCards(aId, bId)
        compose.runOnIdle { viewModel.selectConnection(aId) }
        compose.waitUntil(TIMEOUT_MS) { viewModel.state.value.selectedId == aId }
        compose.waitUntil(TIMEOUT_MS) { !viewModel.state.value.busy && !viewModel.state.value.saving }

        // Drive the real MainActivity → VM → Room scoped operations (group null for both cards).
        compose.runOnIdle {
            viewModel.syncDedicated(p, accountA, credA, null)
            viewModel.syncDedicated(p, accountB, credB, null)
        }
        val keyA = dedicatedOpKey(p, accountA, credA, null)
        val keyB = dedicatedOpKey(p, accountB, credB, null)
        compose.waitUntil(TIMEOUT_MS) {
            viewModel.dedicatedOpLastSynced.value.containsKey(keyA) &&
                keyA !in viewModel.dedicatedOpSyncing.value &&
                viewModel.dedicatedOpError.value[keyB] != null &&
                keyB !in viewModel.dedicatedOpSyncing.value
        }
        assertNull("successful scope A must not carry an op error", viewModel.dedicatedOpError.value[keyA])
        assertNull("failed scope B must not write a trigger time", viewModel.dedicatedOpLastSynced.value[keyB])

        val timeA = viewModel.dedicatedOpLastSynced.value.getValue(keyA)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val timeAText = compose.activity.getString(R.string.sync_triggered_at_format, fmt.format(Date(timeA)))
        val errorAuth = compose.activity.getString(R.string.error_auth)
        val triggerPrefix = compose.activity.getString(R.string.sync_triggered_at_format, "")

        // A's own sheet: success time only, never the sibling's AUTH.
        openMore(aId)
        awaitTag("connection_menu_$aId")
        compose.waitUntil(TIMEOUT_MS) { statusHasText("connection_dedicated_status_$aId", timeAText) }
        assertFalse(
            "successful scope A must not show the sibling AUTH error",
            statusHasText("connection_dedicated_status_$aId", errorAuth),
        )
        dismissSheetAndWait(aId)

        // B's own sheet: AUTH only, never A's success time.
        openMore(bId)
        awaitTag("connection_menu_$bId")
        compose.waitUntil(TIMEOUT_MS) {
            statusHasText("connection_dedicated_status_$bId", errorAuth)
        }
        assertFalse(
            "failed scope B must not borrow A's success time",
            statusHasText("connection_dedicated_status_$bId", timeAText),
        )
        assertFalse(
            "failed scope B must not show any trigger time",
            statusHasTextContaining("connection_dedicated_status_$bId", triggerPrefix),
        )
        dismissSheetAndWait(bId)

        // Owning selected scope also reacts in Provider Detail; sibling AUTH does not leak.
        tapCard(aId)
        awaitText(compose.activity.getString(R.string.provider_detail_back))
        // Status follows the tall header in a LazyColumn; compose it before asserting its text.
        compose.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasTestTag("provider_detail_dedicated_status"))
        awaitTag("provider_detail_dedicated_status")
        compose.waitUntil(TIMEOUT_MS) {
            statusHasText("provider_detail_dedicated_status", timeAText)
        }
        assertFalse(
            "detail for the owning scope must not show the sibling AUTH error",
            statusHasText("provider_detail_dedicated_status", errorAuth),
        )
    }

    // ===== harness =====
    /**
     * Real seed: provider/account/credential/endpoint/offering/availability via the repository.
     * Tracking is registered before the write so a partial failure is still cleaned up.
     */
    private fun seedConnection(
        providerId: String,
        availabilityId: String,
        accountId: String,
        credentialId: String,
        endpointId: String,
        offeringId: String,
        modelId: String,
        name: String,
        baseUrl: String,
        credentialLabel: String = "主要憑證",
        secret: String = FIXTURE_SECRET,
    ): String {
        providerIds += providerId
        credentialRefs += credentialId
        val origin = EndpointPolicy.origin(EndpointPolicy.validate(baseUrl))
        runBlocking {
            app.vault.store(credentialId, origin, secret)
            app.repository.addConnection(
                ConnectionRecord(
                    id = availabilityId,
                    providerId = providerId,
                    accountId = accountId,
                    credentialId = credentialId,
                    endpointId = endpointId,
                    name = name,
                    baseUrl = baseUrl,
                    endpointUrl = EndpointPolicy.chatEndpoint(baseUrl),
                    modelId = modelId,
                    credentialLabel = credentialLabel,
                    offeringId = offeringId,
                ),
            )
        }
        return availabilityId
    }

    /** Second key/account on an existing provider offering (real repository attach). */
    private fun attachKey(
        providerId: String,
        offeringId: String,
        accountId: String,
        credentialId: String,
        availabilityId: String,
        credentialLabel: String,
        baseUrl: String,
        secret: String = FIXTURE_SECRET,
    ): String {
        credentialRefs += credentialId
        val origin = EndpointPolicy.origin(EndpointPolicy.validate(baseUrl))
        runBlocking {
            app.repository.addAccount(providerId, accountId)
            app.vault.store(credentialId, origin, secret)
            app.repository.attachCredential(offeringId, accountId, credentialId, credentialLabel, availabilityId)
        }
        return availabilityId
    }

    /**
     * Extra visible manual offering for the same credential/account: a real Room row set
     * (offering + availability), so the credential owns multiple connection cards.
     */
    private fun addSecondOffering(
        providerId: String,
        endpointId: String,
        accountId: String,
        credentialId: String,
        offeringId: String,
        availabilityId: String,
        modelId: String,
    ): String {
        runBlocking {
            app.database.offeringDao().insert(
                OfferingEntity(
                    id = offeringId,
                    providerId = providerId,
                    endpointId = endpointId,
                    providerModelId = modelId,
                    manual = true,
                    source = "USER",
                    hidden = false,
                    verificationEvidenceId = null,
                ),
            )
            app.database.availabilityDao().insert(
                CredentialAvailabilityEntity(
                    id = availabilityId,
                    offeringId = offeringId,
                    accountId = accountId,
                    credentialId = credentialId,
                ),
            )
        }
        return availabilityId
    }

    private fun openProviders() {
        compose.onNodeWithText(compose.activity.getString(R.string.tab_providers)).performClick()
        compose.waitForIdle()
    }

    /**
     * S19 / PK-20: the Providers list is a `LazyColumn`, so a below-fold card has no
     * semantics node at all until the container composes it. Card lookups therefore
     * address the container (`providers_tab_list`) first and scroll to the target node.
     * Sheet/dialog nodes live in their own window and keep plain lookups.
     */
    private fun scrollListTo(tag: String) {
        compose.onNodeWithTag(PROVIDERS_LIST_TAG).performScrollToNode(hasTestTag(tag))
    }

    /** True only if the tag exists AND the lazy container can scroll it into composition. */
    private fun listHasTag(tag: String): Boolean = runCatching { scrollListTo(tag) }.isSuccess

    private fun cardTag(connectionId: String) = "connection_card_$connectionId"

    /**
     * Lazy-list aware card arrival: the real VM projection is the wait signal (a node
     * query cannot see an uncomposed item), then the container must reach the card.
     */
    private fun awaitCard(connectionId: String) {
        compose.waitUntil(TIMEOUT_MS) {
            viewModel.state.value.managedConnections.any { it.id == connectionId }
        }
        assertTrue(
            "card '$connectionId' must be reachable in the lazy Providers list",
            listHasTag(cardTag(connectionId)),
        )
    }

    private fun openMore(connectionId: String) {
        val tag = "connection_more_$connectionId"
        scrollListTo(tag)
        compose.onNodeWithTag(tag).performClick()
    }

    private fun tapCard(connectionId: String) {
        scrollListTo(cardTag(connectionId))
        compose.onNodeWithTag(cardTag(connectionId)).performClick()
    }

    private fun tapSheet(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun pressSystemBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    private fun dismissSheetAndWait(connectionId: String) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitGoneTag("connection_menu_$connectionId")
        assertTrue("sheet Back must return to its card", listHasTag(cardTag(connectionId)))
        assertFalse("sheet Back must not navigate", textExists(compose.activity.getString(R.string.provider_detail_back)))
    }

    private fun pendingOf(connectionId: String): Boolean =
        viewModel.state.value.managedConnections.firstOrNull { it.id == connectionId }?.checkinPending == true

    /** Wait for the production projection to publish the exact fixture rows. */
    private fun awaitManagedCards(vararg connectionIds: String) {
        compose.waitUntil(TIMEOUT_MS) {
            val managed = viewModel.state.value.managedConnections.map { it.id }.toSet()
            connectionIds.all { it in managed }
        }
    }

    private fun awaitCheckinPending(providerId: String, vararg connectionIds: String) {
        compose.waitUntil(TIMEOUT_MS) {
            viewModel.state.value.managedConnections
                .filter { it.providerId == providerId }
                .let { rows -> rows.isNotEmpty() && rows.all { it.checkinPending } } &&
                connectionIds.all { pendingOf(it) }
        }
    }

    private fun tagExists(tag: String): Boolean =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun textExists(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun cardHasText(connectionId: String, text: String): Boolean {
        val cardTag = cardTag(connectionId)
        // Lazy list: the card must be composed before its subtree can be inspected.
        if (!listHasTag(cardTag)) return false
        return compose.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(cardTag)),
            useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    /** Exact scope-owned status line text, never the surrounding card face. */
    private fun statusHasText(statusTag: String, text: String): Boolean =
        compose.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(statusTag)),
            useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun statusHasTextContaining(statusTag: String, text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true) and hasAnyAncestor(hasTestTag(statusTag)),
            useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun awaitTag(tag: String, timeoutMillis: Long = TIMEOUT_MS) {
        compose.waitUntil(timeoutMillis) { tagExists(tag) }
    }

    private fun awaitGoneTag(tag: String, timeoutMillis: Long = TIMEOUT_MS) {
        compose.waitUntil(timeoutMillis) { !tagExists(tag) }
    }

    private fun awaitText(text: String, timeoutMillis: Long = TIMEOUT_MS) {
        compose.waitUntil(timeoutMillis) { textExists(text) }
    }

    private fun scalar(sql: String, args: Array<String>): Long {
        app.database.openHelper.readableDatabase.query(sql, args).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun sampleCount(providers: List<String>): Long {
        if (providers.isEmpty()) return 0L
        val list = providers.joinToString(",") { "?" }
        return scalar("SELECT COUNT(*) FROM samples WHERE providerId IN ($list)", providers.toTypedArray())
    }

    private fun availabilityCount(credentialId: String): Long =
        scalar("SELECT COUNT(*) FROM credential_availability WHERE credentialId = ?", arrayOf(credentialId))

    private fun credentialRowCount(credentialId: String): Long =
        scalar("SELECT COUNT(*) FROM credentials WHERE credentialId = ?", arrayOf(credentialId))

    private fun checkinCount(providerId: String): Long =
        scalar("SELECT COUNT(*) FROM checkins WHERE providerId = ?", arrayOf(providerId))

    /**
     * Narrow cleanup of this file's own UUID providers in FK-safe order (RESTRICT aware):
     * availability → credentials/offerings → endpoints → accounts → provider-owned rows → providers.
     * Never wildcard-deletes product history; only tables this fixture writes are touched.
     */
    private fun deleteOwnRows() {
        if (providerIds.isEmpty()) return
        val list = providerIds.joinToString(",") { "?" }
        val args = providerIds.toTypedArray<Any?>()
        val byAccount = "accountId IN (SELECT accountId FROM accounts WHERE providerId IN ($list))"
        val db = app.database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM checkins WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM my_models WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM samples WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM provider_configurations WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM credential_availability WHERE $byAccount", args)
            db.execSQL("DELETE FROM credentials WHERE $byAccount", args)
            db.execSQL("DELETE FROM offerings WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM endpoints WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM accounts WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM providers WHERE providerId IN ($list)", args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        /** S19: the lazy Providers list container tag (ProvidersTab `LazyColumn`). */
        const val PROVIDERS_LIST_TAG = "providers_tab_list"
        const val BASE_P = "https://card-flow-p.fixture.invalid/v1"
        const val BASE_Q = "https://card-flow-q.fixture.invalid/v1"
        const val BASE_K = "https://card-flow-k.fixture.invalid/v1"
        const val CHECKIN_URL = "https://card-flow-checkin.fixture.invalid/daily"
        // A model carried by the local-only groq official quota table, so no network is needed.
        const val GROQ_MODEL = "openai/gpt-oss-20b"
        const val FIXTURE_SECRET = "card-flow-fixture-secret-never-persisted"
        const val CRED_B = "刪除目標憑證 B"
        const val CRED_S = "保留憑證 S"
    }
}
