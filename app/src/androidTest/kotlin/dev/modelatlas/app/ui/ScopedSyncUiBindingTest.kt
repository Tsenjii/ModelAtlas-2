package dev.modelatlas.app.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.R as ComposeUiResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.ConnectionItem
import dev.modelatlas.app.R
import dev.modelatlas.app.newApiSyncKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S4 scoped-sync UI wiring device test (card level, real [AtlasViewModel]).
 * TRACEABILITY S4; Contract `PROVIDER_MODEL_INTELLIGENCE_CONTRACT.md` scoped-sync lane.
 *
 * Seam naming: the task brief predicted `scopedNewApiStateFor`. What actually
 * landed in HEAD is the VM selector `AtlasViewModel.newApiOpState()` plus the
 * UI ports `newApiOpStateFor` / `onScopedNewApiSync` (`ProvidersTab`,
 * `ProviderDetailHost`) and the pure dispatcher `scopedNewApiSyncFor`. The
 * `AtlasScreenContent` binding (collecting the VM op-key flows) lands
 * concurrently via the Astra owner; this file targets only what landed, so it
 * stays valid regardless of that binding's local name. The local bridge below
 * is named `scopedNewApiStateFor` and mirrors `scopedDedicatedStateFor`
 * exactly (collect four op-key flows as the recompose subscription, key by
 * [newApiSyncKey] with blank ids collapsed to null).
 *
 * Vectors (exactly 4 tests):
 * 1. `scopedSyncErrorIsKeyedByExactTripleAndSiblingBorrowsNothing` — two cards
 *    of one provider with different (account, credential): a real sheet-tap
 *    drives the real 3-arg `vm::syncNewApi` and only the tapped scope flips to
 *    `UNSUPPORTED`; the sibling's VM state stays null, its status node stays
 *    empty, and its button stays enabled.
 * 2. `scopedTriggerDispatchesThreeArgPathWithExactScope` — the `ProvidersTab`
 *    line-1143 expression (`scopedNewApiSyncFor(connection, scoped)`) against
 *    a recording 3-arg endpoint: each card dispatches its own exact triple and
 *    blank ids collapse to null at dispatch.
 * 3. `blankIdsTriggerAndReadTheSameNullCollapsedScope` — end-to-end agreement
 *    of dispatch collapse and read collapse: a blank-id card's real sync flips
 *    the null-collapsed VM scope, and the card renders it.
 * 4. `legacyProviderOnlySyncBindingIsUnchanged` — the `?: onSync` / `else null`
 *    branches: legacy binding still emits providerId only, acquires no scoped
 *    status node, and provider busy still disables the button.
 *
 * Zero-transport note: the fixtures use an unseeded provider on purpose.
 * `AtlasRepository.syncNewApi` validates provider/config/scope before the
 * first network request (throws `IllegalArgumentException`, never burns
 * quota), which the VM classifies to the closed `UNSUPPORTED` code — so the
 * op-state flip is the deterministic signal here. Multi-leg transport counts
 * belong to `NewApiExactAccountSyncTest` / `Cr002TwoAccountSyncTest` and are
 * not re-implemented. No Room rows are written by these vectors; only
 * in-memory VM op-key flows change.
 */
@RunWith(AndroidJUnit4::class)
class ScopedSyncUiBindingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: AtlasApplication
    private lateinit var vmStore: ViewModelStore
    private lateinit var vm: AtlasViewModel

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun str(id: Int): String = context.getString(id)

    @Before
    fun setUp() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "must not run on main thread" }
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication
        runBlocking { withTimeout(TIMEOUT_MS) { app.ready.first { it } } }
        vmStore = ViewModelStore()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = ViewModelProvider(vmStore, object : ViewModelProvider.Factory {
                @Suppress("DEPRECATION")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AtlasViewModel(app) as T
                }
            }).get(AtlasViewModel::class.java)
        }
    }

    @After
    fun tearDown() {
        if (::vmStore.isInitialized) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { runCatching { vmStore.clear() } }
        }
    }

    private fun card(
        id: String,
        providerId: String = PROVIDER,
        accountId: String = "acc-$id",
        credentialId: String = "cred-$id",
        supportsSync: Boolean = true,
    ) = ConnectionItem(
        id = id,
        providerId = providerId,
        name = "Atlas Provider",
        baseUrl = "https://example.invalid/v1",
        modelId = "atlas-model-$id",
        credentialLabel = "Primary Key",
        quotaState = "UNKNOWN",
        officialHealth = "UNKNOWN",
        observedHealth = "UNKNOWN",
        checkinUrl = null,
        checkinPending = false,
        accountId = accountId,
        credentialId = credentialId,
        enabled = true,
        supportsSync = supportsSync,
    )

    /**
     * Live-VM mount: the exact `ProvidersTab` wiring (`scopedNewApiSyncFor`
     * dispatch + per-connection `newApiOpStateFor` read) with
     * `vm::syncNewApi` as the scoped endpoint. The 3-arg overload is selected
     * by the declared `(String, String?, String?) -> Unit` type, so the legacy
     * arity-1 reference is never a candidate here.
     */
    private fun mountScoped(vararg items: ConnectionItem) {
        compose.setContent {
            AtlasTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val opSyncing by vm.newApiOpSyncing.collectAsState()
                        val opError by vm.newApiOpError.collectAsState()
                        val opLastSynced by vm.newApiOpLastSynced.collectAsState()
                        val opObserved by vm.newApiOpLatestObserved.collectAsState()
                        val scopedNewApiStateFor: (ConnectionItem) -> OpState = { connection ->
                            val opKey = newApiSyncKey(
                                connection.providerId,
                                connection.accountId.takeIf { it.isNotBlank() },
                                connection.credentialId.takeIf { it.isNotBlank() },
                            )
                            OpState(
                                syncing = opKey in opSyncing,
                                error = opError[opKey],
                                lastSynced = opLastSynced[opKey],
                                latestObserved = opObserved[opKey],
                            )
                        }
                        val onScopedNewApiSync: (String, String?, String?) -> Unit = vm::syncNewApi
                        items.forEach { connection ->
                            ConnectionCard(
                                item = connection,
                                isSelected = false,
                                onSelect = {},
                                onCompleteCheckin = {},
                                saving = false,
                                syncingProviders = emptySet(),
                                onSync = scopedNewApiSyncFor(connection, onScopedNewApiSync),
                                onSetEnabled = { _, _ -> },
                                onDelete = {},
                                onConfigureCheckin = { _, _ -> },
                                newApiOpState = scopedNewApiStateFor(connection),
                            )
                        }
                    }
                }
            }
        }
    }

    private data class ManualCard(
        val item: ConnectionItem,
        val onSync: (String) -> Unit,
        val newApiOpState: OpState?,
        val syncingProviders: Set<String> = emptySet(),
    )

    /** Direct card mount for dispatch-identity and legacy-branch vectors. */
    private fun mountManual(vararg cards: ManualCard) {
        compose.setContent {
            AtlasTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        cards.forEach { entry ->
                            ConnectionCard(
                                item = entry.item,
                                isSelected = false,
                                onSelect = {},
                                onCompleteCheckin = {},
                                saving = false,
                                syncingProviders = entry.syncingProviders,
                                onSync = entry.onSync,
                                onSetEnabled = { _, _ -> },
                                onDelete = {},
                                onConfigureCheckin = { _, _ -> },
                                newApiOpState = entry.newApiOpState,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun moreNode(id: String) = compose.onNodeWithTag("connection_more_$id")

    private fun menuNode(id: String) = compose.onNodeWithTag("connection_menu_$id")

    private fun sheetOpen(id: String): Boolean =
        compose.onAllNodesWithTag("connection_menu_$id").fetchSemanticsNodes().isNotEmpty()

    private fun openSheet(id: String) {
        moreNode(id).performScrollTo().performClick()
        menuNode(id).assertIsDisplayed()
    }

    /** Target the real Material3 1.3.2 Scrim, whose label is compose-ui's close_sheet. */
    private fun dismissSheet(id: String) {
        compose.onNodeWithContentDescription(
            context.getString(ComposeUiResources.string.close_sheet),
        ).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { action ->
            assertTrue("scrim must accept its dismiss action", action())
        }
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) { !sheetOpen(id) }
    }

    /** Exact scope-owned status lines only: never the surrounding card face text. */
    private fun statusTexts(statusTag: String): List<String> =
        compose.onAllNodes(
            hasAnyAncestor(hasTestTag(statusTag)),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()
        }

    private fun statusHasText(statusTag: String, text: String): Boolean =
        statusTexts(statusTag).any { it == text }

    private fun newApiStatusTag(id: String) = "connection_newapi_status_$id"

    @Test
    fun scopedSyncErrorIsKeyedByExactTripleAndSiblingBorrowsNothing() {
        mountScoped(
            card(id = "s4a-a", accountId = "acc-s4-a", credentialId = "cred-s4-a"),
            card(id = "s4a-b", accountId = "acc-s4-b", credentialId = "cred-s4-b"),
        )
        // Real sheet tap drives the real 3-arg VM path for scope A only.
        openSheet("s4a-a")
        compose.onNodeWithTag("connection_sync_s4a-a").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            vm.newApiOpState(PROVIDER, "acc-s4-a", "cred-s4-a").error != null
        }
        compose.waitForIdle()

        // VM lane: exact triple flipped, sibling untouched, no provider aggregate written.
        compose.runOnIdle {
            assertEquals(
                "tapped scope must carry the pre-network UNSUPPORTED code",
                "UNSUPPORTED",
                vm.newApiOpState(PROVIDER, "acc-s4-a", "cred-s4-a").error,
            )
            assertNull(
                "sibling scope must not borrow the tapped scope's error",
                vm.newApiOpState(PROVIDER, "acc-s4-b", "cred-s4-b").error,
            )
        }

        // UI: A's scoped status renders the safe category, never the raw transport detail.
        assertTrue(statusHasText(newApiStatusTag("s4a-a"), str(R.string.error_sync_unavailable)))
        dismissSheet("s4a-a")

        // UI: sibling borrows nothing — empty scoped status, callable button, no face failure.
        openSheet("s4a-b")
        assertTrue(
            "sibling status must stay empty: ${statusTexts(newApiStatusTag("s4a-b"))}",
            statusTexts(newApiStatusTag("s4a-b")).isEmpty(),
        )
        compose.onNodeWithTag("connection_sync_s4a-b").performScrollTo().assertIsEnabled()
        dismissSheet("s4a-b")

        // An error never latches the tapped button disabled, and the face announces the failure.
        openSheet("s4a-a")
        compose.onNodeWithTag("connection_sync_s4a-a").performScrollTo().assertIsEnabled()
        dismissSheet("s4a-a")
        compose.onNodeWithText(str(R.string.connection_card_sync_failed)).assertIsDisplayed()
    }

    @Test
    fun scopedTriggerDispatchesThreeArgPathWithExactScope() {
        val scopedCalls = mutableListOf<Triple<String, String?, String?>>()
        val a = card(id = "s4b-a", accountId = "acc-s4b-a", credentialId = "cred-s4b-a")
        val blank = card(id = "s4b-c", accountId = "   ", credentialId = "")
        mountManual(
            ManualCard(
                item = a,
                onSync = scopedNewApiSyncFor(a) { p, acc, cred -> scopedCalls += Triple(p, acc, cred) },
                newApiOpState = OpState(),
            ),
            ManualCard(
                item = blank,
                onSync = scopedNewApiSyncFor(blank) { p, acc, cred -> scopedCalls += Triple(p, acc, cred) },
                newApiOpState = OpState(),
            ),
        )
        openSheet("s4b-a")
        compose.onNodeWithTag("connection_sync_s4b-a").performScrollTo().assertIsEnabled().performClick()
        dismissSheet("s4b-a")
        openSheet("s4b-c")
        compose.onNodeWithTag("connection_sync_s4b-c").performScrollTo().assertIsEnabled().performClick()
        dismissSheet("s4b-c")
        compose.runOnIdle {
            assertEquals(
                "each card must dispatch its own exact triple; blank ids collapse to null",
                listOf(
                    Triple(PROVIDER, "acc-s4b-a", "cred-s4b-a"),
                    Triple(PROVIDER, null, null),
                ),
                scopedCalls,
            )
        }
    }

    @Test
    fun blankIdsTriggerAndReadTheSameNullCollapsedScope() {
        mountScoped(card(id = "s4c", accountId = "", credentialId = ""))
        openSheet("s4c")
        compose.onNodeWithTag("connection_sync_s4c").performScrollTo().assertIsEnabled().performClick()
        // Dispatch collapsed blank to null, so the null-collapsed VM scope must flip.
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            vm.newApiOpState(PROVIDER, null, null).error != null
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(
                "blank-id card must read back the same null-collapsed scope it triggered",
                "UNSUPPORTED",
                vm.newApiOpState(PROVIDER, null, null).error,
            )
        }
        assertTrue(statusHasText(newApiStatusTag("s4c"), str(R.string.error_sync_unavailable)))
        dismissSheet("s4c")
    }

    @Test
    fun legacyProviderOnlySyncBindingIsUnchanged() {
        val legacySyncIds = mutableListOf<String>()
        mountManual(
            ManualCard(
                item = card(id = "s4d-idle"),
                onSync = { legacySyncIds += it },
                newApiOpState = null,
            ),
            ManualCard(
                item = card(id = "s4d-busy"),
                onSync = { legacySyncIds += it },
                newApiOpState = null,
                syncingProviders = setOf(PROVIDER),
            ),
        )
        // Idle legacy card: provider-only identity, and no scoped status node at all.
        openSheet("s4d-idle")
        compose.onNodeWithTag(newApiStatusTag("s4d-idle")).assertDoesNotExist()
        compose.onNodeWithTag("connection_sync_s4d-idle").performScrollTo().assertIsEnabled().performClick()
        dismissSheet("s4d-idle")
        compose.runOnIdle { assertEquals(listOf(PROVIDER), legacySyncIds) }

        // Provider busy still disables the legacy button (legacy aggregate path intact).
        openSheet("s4d-busy")
        compose.onNodeWithTag(newApiStatusTag("s4d-busy")).assertDoesNotExist()
        compose.onNodeWithTag("connection_sync_s4d-busy").performScrollTo().assertIsNotEnabled()
        dismissSheet("s4d-busy")
        compose.runOnIdle {
            assertEquals("disabled legacy button must not emit", listOf(PROVIDER), legacySyncIds)
        }
    }

    private companion object {
        const val PROVIDER = "prov-s4-scoped"
        const val TIMEOUT_MS = 10_000L
    }
}
