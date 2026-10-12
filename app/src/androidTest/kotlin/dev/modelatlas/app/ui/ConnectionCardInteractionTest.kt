package dev.modelatlas.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.R as ComposeUiResources
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.ConnectionItem
import dev.modelatlas.app.R
import dev.modelatlas.core.domain.QuotaState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI-INTEL-001A interaction contract for the main-owned compact `ConnectionCard`.
 * TRACEABILITY UI-INTEL-001A; Contract §23.8 / §23.11; PRODUCT_SPEC §2 / §4 / §6 / §18.
 *
 * Companion of `ConnectionCardLayoutTest` (geometry / a11y). This file owns
 * *interaction and callback identity* for the same production composable:
 * - the whole primary face is the single navigation target and emits
 *   `onDetailClick(providerId)` only — never `onSelect`, never an item id;
 * - `More` is a sibling hit target: physical centre and near-corner touches open
 *   the material `ModalBottomSheet` and must not navigate or select;
 * - badge and blank-area touches on the face still navigate (no dead zone);
 * - sheet actions emit exact identities: lifecycle callbacks carry `item.id`,
 *   provider-level sync carries `providerId`, and the dedicated sync tuple is
 *   `(providerId, accountId?, credentialId?, group?)` with blank ids collapsed to
 *   null, per connection, and the selected-scope group forwarded verbatim (never
 *   invented, trimmed, or blank-to-null converted by the card);
 * - selected / disabled / anonymous / saving gates expose the right entries and
 *   keep the read-only `More` path usable;
 * - sync entries are per connection (not per provider), gated by
 *   `supportsSync` / dedicated support, disabled while running, and a failure
 *   renders only its safe category label;
 * - dedicated / New API status is own-op-state scoped: same-provider sibling
 *   error, times, and busy state never leak, and a missing scoped input borrows
 *   no provider aggregate;
 * - delete confirm fires exactly once, cancel fires nothing, sheet dismissal
 *   (scrim or system back) fires nothing at all;
 * - unknown price/quota/health fields render no badge and no invented
 *   0 / 100% / 7d / 30RPM / TTFT / TPS value, while known fields keep an
 *   explicit official-vs-local source label.
 *
 * Host/config changes go through a real [MutableState] per card so the reactive
 * contract (§23.11) is exercised by re-composition rather than by a second
 * mount. The only production path that leaves the composition is the check-in
 * "open" button (`Intent.ACTION_VIEW`); a [RecordingContext] is installed through
 * `LocalContext` so that intent is captured instead of launching a browser — no
 * extra test dependency, no production change. Fixtures live in this test source
 * set only and never become product data.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionCardInteractionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun str(id: Int): String = context.getString(id)

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    private val mountedCards = mutableMapOf<String, CardUnderTest>()

    /** Captures the sheet's only outbound intent instead of launching a browser. */
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var onView: (Uri) -> Unit = {}

        override fun startActivity(intent: Intent) {
            if (intent.action == Intent.ACTION_VIEW) {
                intent.data?.let(onView)
            } else {
                super.startActivity(intent)
            }
        }
    }

    private data class DedicatedSyncCall(
        val providerId: String,
        val accountId: String?,
        val credentialId: String?,
        val groupId: String?,
    )

    private class Host {
        val detailProviderIds = mutableListOf<String>()
        val selectIds = mutableListOf<String>()
        val completeCheckinIds = mutableListOf<String>()
        val syncProviderIds = mutableListOf<String>()
        val dedicatedSyncCalls = mutableListOf<DedicatedSyncCall>()
        val setEnabledCalls = mutableListOf<Pair<String, Boolean>>()
        val deleteIds = mutableListOf<String>()
        val configureCheckinCalls = mutableListOf<Pair<String, String>>()
        val viewedUris = mutableListOf<Uri>()
    }

    /** Reactive per-card inputs; edited in place so the UI must re-compose. */
    private data class CardConfig(
        val isSelected: Boolean = false,
        val dedicated: Boolean = false,
        val saving: Boolean = false,
        val syncingProviders: Set<String> = emptySet(),
        // Provider-level busy input is face-only; scoped results come from the own-OpState below.
        val dedicatedSyncing: Set<String> = emptySet(),
        val dedicatedOpState: OpState = OpState(),
        // null keeps the legacy provider-only New API operation (no scoped result claims).
        val newApiOpState: OpState? = null,
        val dedicatedSyncGroup: String? = null,
    )

    private class CardUnderTest(
        val item: ConnectionItem,
        val config: MutableState<CardConfig> = mutableStateOf(CardConfig()),
    )

    private fun card(
        id: String,
        providerId: String = "prov-$id",
        name: String = "Atlas Provider",
        modelId: String = "atlas-model-$id",
        credentialLabel: String = "Primary Key",
        credentialId: String = "cred-$id",
        accountId: String = "acc-$id",
        enabled: Boolean = true,
        supportsSync: Boolean = false,
        quotaState: String = "UNKNOWN",
        officialHealth: String = "UNKNOWN",
        observedHealth: String = "UNKNOWN",
        checkinUrl: String? = null,
        checkinPending: Boolean = false,
    ) = ConnectionItem(
        id = id,
        providerId = providerId,
        name = name,
        baseUrl = "https://example.invalid/v1",
        modelId = modelId,
        credentialLabel = credentialLabel,
        quotaState = quotaState,
        officialHealth = officialHealth,
        observedHealth = observedHealth,
        checkinUrl = checkinUrl,
        checkinPending = checkinPending,
        accountId = accountId,
        credentialId = credentialId,
        enabled = enabled,
        supportsSync = supportsSync,
    )

    private fun mount(vararg cards: CardUnderTest): Host {
        val host = Host()
        mountedCards.clear()
        cards.forEach { mountedCards[it.item.id] = it }
        val recording = RecordingContext(context).apply { onView = { host.viewedUris += it } }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides recording) {
                AtlasTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            cards.forEach { entry ->
                                val cfg = entry.config
                                ConnectionCard(
                                    item = entry.item,
                                    isSelected = cfg.value.isSelected,
                                    onSelect = { host.selectIds += it },
                                    onCompleteCheckin = { host.completeCheckinIds += it },
                                    saving = cfg.value.saving,
                                    syncingProviders = cfg.value.syncingProviders,
                                    onSync = { host.syncProviderIds += it },
                                    dedicatedSyncing = cfg.value.dedicatedSyncing,
                                    dedicatedOpState = cfg.value.dedicatedOpState,
                                    newApiOpState = cfg.value.newApiOpState,
                                    dedicatedSyncGroup = cfg.value.dedicatedSyncGroup,
                                    onDedicatedSync = { providerId, accountId, credentialId, groupId ->
                                        host.dedicatedSyncCalls +=
                                            DedicatedSyncCall(providerId, accountId, credentialId, groupId)
                                    },
                                    isDedicatedSupported = { cfg.value.dedicated },
                                    onSetEnabled = { id, value -> host.setEnabledCalls += id to value },
                                    onDelete = { host.deleteIds += it },
                                    onConfigureCheckin = { id, url -> host.configureCheckinCalls += id to url },
                                    onDetailClick = { host.detailProviderIds += it },
                                )
                            }
                        }
                    }
                }
            }
        }
        return host
    }

    /** Reactive input edit: the card must re-compose, no re-mount allowed. */
    private fun updateConfig(id: String, transform: (CardConfig) -> CardConfig) {
        val entry = mountedCards[id] ?: error("card '$id' was not mounted")
        compose.runOnIdle { entry.config.value = transform(entry.config.value) }
        compose.waitForIdle()
    }

    private fun cardNode(id: String) = compose.onNodeWithTag("connection_card_$id")

    private fun moreNode(id: String) = compose.onNodeWithTag("connection_more_$id")

    private fun menuNode(id: String) = compose.onNodeWithTag("connection_menu_$id")

    private fun sheetOpen(id: String): Boolean =
        compose.onAllNodesWithTag("connection_menu_$id").fetchSemanticsNodes().isNotEmpty()

    private fun openSheet(id: String) {
        moreNode(id).performScrollTo().performClick()
        menuNode(id).assertIsDisplayed()
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

    private fun statusHasNoText(statusTag: String, text: String): Boolean =
        statusTexts(statusTag).none { it == text }

    private fun dedicatedStatusTag(id: String) = "connection_dedicated_status_$id"

    private fun newApiStatusTag(id: String) = "connection_newapi_status_$id"

    private fun faceStateDescription(id: String): String? =
        cardNode(id).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    /** Target the real Material3 1.3.2 Scrim, whose label is compose-ui's close_sheet. */
    private fun scrimNode() = compose.onNodeWithContentDescription(
        context.getString(ComposeUiResources.string.close_sheet),
    )

    // Phase cleanup uses the real scrim onClick semantics. The dedicated coordinate
    // case below additionally proves a physical outside-sheet tap is side-effect-free.
    private fun dismissSheet(id: String) {
        // performClick injects a centre touch: the full-window scrim's centre can
        // be covered by the sheet. Explicitly invoke its real accessibility action.
        scrimNode().assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { action ->
            assertTrue("scrim must accept its dismiss action", action())
        }
        compose.waitUntil(timeoutMillis = 5_000) { !sheetOpen(id) }
    }

    private fun dismissSheetByScrimTouch(id: String) {
        // Scrim fills the dialog window. Its top-left pixel is outside the sheet's
        // rounded top corner even when a tall expanded sheet fills the viewport.
        // The menu's outer testTag bounds are not the visible sheet offset.
        scrimNode().assertIsDisplayed().performTouchInput { click(Offset(1f, 1f)) }
        compose.waitUntil(timeoutMillis = 5_000) { !sheetOpen(id) }
    }

    /**
     * Real system back. The Compose dialog owns its own back dispatcher, so the
     * genuine hardware key event (what `Espresso.pressBack()` sends) is dispatched
     * to the focused dialog window; routing through the activity dispatcher would
     * finish the activity instead of dismissing the sheet.
     */
    private fun dismissSheetWithBack(id: String) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(timeoutMillis = 5_000) { !sheetOpen(id) }
    }

    @Test
    fun primaryFaceNavigatesWithProviderIdOnlyAndMoreIsASeparateSibling() {
        val host = mount(
            CardUnderTest(card(id = "ic-1a")),
            CardUnderTest(card(id = "ic-1b")),
        )
        // Whole primary face is the navigation target; it emits providerId only.
        cardNode("ic-1a").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("prov-ic-1a"), host.detailProviderIds)
            assertTrue(host.selectIds.isEmpty())
        }
        // More is a sibling: a near-corner physical touch opens the sheet only.
        moreNode("ic-1a").performScrollTo().performTouchInput { click(Offset(6f, 6f)) }
        menuNode("ic-1a").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("prov-ic-1a"), host.detailProviderIds)
            assertTrue(host.selectIds.isEmpty())
        }
        dismissSheetByScrimTouch("ic-1a")
        // A centre touch on More behaves identically, and scrim dismissal is inert.
        moreNode("ic-1b").performScrollTo().performTouchInput { click(center) }
        menuNode("ic-1b").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("prov-ic-1a"), host.detailProviderIds)
            assertTrue(host.selectIds.isEmpty())
        }
    }

    @Test
    fun badgeAndBlankFaceTouchesStillNavigate() {
        val host = mount(
            CardUnderTest(
                item = card(id = "ic-2a", quotaState = "QUOTA_AVAILABLE", officialHealth = "HEALTHY"),
                config = mutableStateOf(CardConfig(isSelected = true)),
            ),
        )
        val face = cardNode("ic-2a").performScrollTo()
        val faceBounds = face.fetchSemanticsNode().boundsInRoot
        val badgeBounds = compose.onNodeWithText(str(R.string.status_selected), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        // Touching the selected badge is still a touch on the primary face.
        face.performTouchInput {
            click(Offset(badgeBounds.center.x - faceBounds.left, badgeBounds.center.y - faceBounds.top))
        }
        // A blank corner of the face is a navigation target too (no dead zone).
        face.performTouchInput { click(Offset(8f, faceBounds.height - 8f)) }
        compose.runOnIdle {
            assertEquals(listOf("prov-ic-2a", "prov-ic-2a"), host.detailProviderIds)
            assertTrue(host.selectIds.isEmpty())
        }
        // Crossing touch slop for a list-scroll gesture must cancel the face click.
        face.performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertEquals("scroll gesture must not navigate", listOf("prov-ic-2a", "prov-ic-2a"), host.detailProviderIds)
            assertTrue(host.selectIds.isEmpty())
        }
    }

    @Test
    fun sheetSelectSyncAndCheckinReportExactIdsAndOnlyHttpsMayOpen() {
        val host = mount(
            CardUnderTest(
                card(
                    id = "ic-3a",
                    supportsSync = true,
                    checkinUrl = "https://checkin.example.com/daily?token=abc123",
                    checkinPending = true,
                ),
            ),
            CardUnderTest(
                card(id = "ic-3b", checkinUrl = "http://insecure.example.com/checkin", checkinPending = true),
            ),
        )
        openSheet("ic-3a")
        compose.onNodeWithTag("connection_select_ic-3a").performScrollTo().performClick()
        compose.onNodeWithTag("connection_sync_ic-3a").performScrollTo().performClick()
        compose.onNodeWithTag("connection_checkin_complete_ic-3a").performScrollTo().performClick()
        compose.onNodeWithTag("connection_checkin_open_ic-3a").performScrollTo().performClick()
        compose.runOnIdle {
            // Selection and check-in completion are connection-scoped; sync is provider-scoped.
            assertEquals(listOf("ic-3a"), host.selectIds)
            assertEquals(listOf("prov-ic-3a"), host.syncProviderIds)
            assertEquals(listOf("ic-3a"), host.completeCheckinIds)
            // The external check-in is launched as the exact plain https URI.
            assertEquals(
                listOf(Uri.parse("https://checkin.example.com/daily?token=abc123")),
                host.viewedUris,
            )
        }
        dismissSheet("ic-3a")
        // Non-https check-in: no open entry at all, while completion stays reachable.
        openSheet("ic-3b")
        compose.onNodeWithTag("connection_checkin_open_ic-3b").assertDoesNotExist()
        compose.onNodeWithTag("connection_checkin_complete_ic-3b").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, host.viewedUris.size) }
    }

    @Test
    fun dedicatedSyncReportsExactPerConnectionScopeWithNullGroup() {
        val host = mount(
            CardUnderTest(
                item = card(
                    id = "ic-4a",
                    providerId = "prov-shared",
                    accountId = "acc-4a",
                    credentialId = "cred-4a",
                ),
                config = mutableStateOf(CardConfig(dedicated = true)),
            ),
            CardUnderTest(
                item = card(
                    id = "ic-4b",
                    providerId = "prov-shared",
                    accountId = "",
                    credentialId = "",
                    credentialLabel = "",
                ),
                config = mutableStateOf(CardConfig(dedicated = true)),
            ),
        )
        openSheet("ic-4a")
        compose.onNodeWithTag("connection_dedicated_sync_ic-4a").performScrollTo().performClick()
        dismissSheet("ic-4a")
        openSheet("ic-4b")
        compose.onNodeWithTag("connection_dedicated_sync_ic-4b").performScrollTo().performClick()
        compose.runOnIdle {
            // Two connections of one provider never borrow each other's scope, blank
            // ids collapse to null, and the group is never invented.
            assertEquals(
                listOf(
                    DedicatedSyncCall("prov-shared", "acc-4a", "cred-4a", null),
                    DedicatedSyncCall("prov-shared", null, null, null),
                ),
                host.dedicatedSyncCalls,
            )
        }
    }

    @Test
    fun toggleDeleteAndCheckinSaveUseItemIdAndDeleteConfirmsExactlyOnce() {
        val host = mount(
            CardUnderTest(card(id = "ic-5a")),
            CardUnderTest(card(id = "ic-5b", enabled = false)),
        )
        openSheet("ic-5a")
        compose.onNodeWithTag("connection_toggle_enabled_ic-5a").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("ic-5a" to false), host.setEnabledCalls) }

        // Delete confirmation names provider + credential; cancel must fire nothing.
        compose.onNodeWithTag("connection_delete_ic-5a").performScrollTo().performClick()
        compose.onNodeWithText(
            str(R.string.connection_card_delete_target, "Atlas Provider", "Primary Key"),
        ).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.action_cancel)).performClick()
        compose.onNodeWithTag("connection_delete_confirm_ic-5a").assertDoesNotExist()
        compose.runOnIdle { assertTrue(host.deleteIds.isEmpty()) }

        openSheet("ic-5a")
        compose.onNodeWithTag("connection_delete_ic-5a").performScrollTo().performClick()
        compose.onNodeWithTag("connection_delete_confirm_ic-5a").performClick()
        compose.runOnIdle { assertEquals(listOf("ic-5a"), host.deleteIds) }

        openSheet("ic-5a")
        compose.onNodeWithTag("connection_configure_checkin_ic-5a").performScrollTo().performClick()
        compose.onNodeWithTag("connection_checkin_input_ic-5a").assertIsDisplayed()
        compose.onNodeWithTag("connection_checkin_input_ic-5a")
            .performTextInput("https://new.example.com/checkin")
        compose.onNodeWithTag("connection_checkin_save_ic-5a").performClick()
        compose.runOnIdle {
            assertEquals(listOf("ic-5a" to "https://new.example.com/checkin"), host.configureCheckinCalls)
        }

        // Disabled connection: no select entry, and the toggle offers the inverted value.
        openSheet("ic-5b")
        compose.onNodeWithTag("connection_select_ic-5b").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.action_enable_credential)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("connection_toggle_enabled_ic-5b").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("ic-5a" to false, "ic-5b" to true), host.setEnabledCalls)
            assertEquals(listOf("ic-5a"), host.deleteIds)
        }
    }

    @Test
    fun syncEntriesArePerConnectionSupportGatedAndDisabledWhileRunning() {
        val matrix = listOf(
            Triple("ic-6a", true, false),
            Triple("ic-6b", false, true),
            Triple("ic-6c", true, true),
            Triple("ic-6d", false, false),
        )
        val host = mount(
            *matrix.map { (id, sync, dedicated) ->
                CardUnderTest(
                    item = card(id = id, providerId = "prov-matrix", supportsSync = sync),
                    config = mutableStateOf(CardConfig(dedicated = dedicated)),
                )
            }.toTypedArray(),
            CardUnderTest(
                item = card(id = "ic-6e", providerId = "prov-matrix", supportsSync = true),
                config = mutableStateOf(
                    CardConfig(dedicated = true, dedicatedSyncing = setOf("prov-matrix"),
                        dedicatedOpState = OpState(syncing = true),
                        syncingProviders = setOf("prov-matrix")),
                ),
            ),
        )
        // Four connections of one provider expose independent support matrices.
        matrix.forEach { (id, hasSync, hasDedicated) ->
            openSheet(id)
            if (hasSync) {
                compose.onNodeWithTag("connection_sync_$id").assertExists()
            } else {
                compose.onNodeWithTag("connection_sync_$id").assertDoesNotExist()
            }
            if (hasDedicated) {
                compose.onNodeWithTag("connection_dedicated_sync_$id").assertExists()
            } else {
                compose.onNodeWithTag("connection_dedicated_sync_$id").assertDoesNotExist()
            }
            dismissSheet(id)
        }

        // Both running entries must reject a second request.
        openSheet("ic-6e")
        compose.onNodeWithTag("connection_dedicated_sync_ic-6e").performScrollTo()
            .assertIsNotEnabled().performTouchInput { click(center) }
        compose.onNodeWithTag("connection_sync_ic-6e").performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle {
            assertTrue(host.dedicatedSyncCalls.isEmpty())
            assertTrue(host.syncProviderIds.isEmpty())
        }
        dismissSheet("ic-6e")

        // Reactive edit: the face announces the running state.
        updateConfig("ic-6e") {
            it.copy(dedicatedSyncing = emptySet(), dedicatedOpState = OpState(syncing = false),
                syncingProviders = setOf("prov-matrix"))
        }
        openSheet("ic-6e")
        compose.onNodeWithTag("connection_sync_ic-6e").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("connection_dedicated_sync_ic-6e")
            .performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(DedicatedSyncCall("prov-matrix", "acc-ic-6e", "cred-ic-6e", null)),
                host.dedicatedSyncCalls)
            assertTrue(host.syncProviderIds.isEmpty())
        }
        dismissSheet("ic-6e")
        cardNode("ic-6e").performScrollTo()
        val runningState = cardNode("ic-6e").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.StateDescription)
        assertNotNull("running sync must be announced on the face", runningState)
        assertTrue("running sync must be announced on the face: $runningState",
            runningState!!.contains(str(R.string.status_syncing)))

        // Reactive edit: a failure shows only its safe category, never the raw code.
        updateConfig("ic-6e") {
            it.copy(syncingProviders = emptySet(), dedicatedOpState = OpState(error = "AUTH"))
        }
        compose.onNodeWithText(str(R.string.connection_card_sync_failed)).assertIsDisplayed()
        openSheet("ic-6e")
        compose.onNodeWithText(str(R.string.error_auth)).performScrollTo().assertIsDisplayed()
        val rawCodeNodes = compose.onAllNodesWithText("AUTH", useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("raw sync error code must not be rendered", rawCodeNodes.isEmpty())
    }

    @Test
    fun anonymousConnectionHidesToggleDeleteButKeepsNavigationSelectAndCheckin() {
        val host = mount(
            CardUnderTest(
                item = card(
                    id = "ic-7a",
                    accountId = "",
                    credentialId = "",
                    credentialLabel = "",
                    checkinUrl = "https://checkin.example.com/anon",
                    checkinPending = true,
                ),
            ),
            CardUnderTest(
                item = card(id = "ic-7b"),
                config = mutableStateOf(CardConfig(isSelected = true)),
            ),
            CardUnderTest(card(id = "ic-7c", credentialLabel = "")),
        )
        cardNode("ic-7a").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("prov-ic-7a"), host.detailProviderIds) }

        openSheet("ic-7a")
        // Anonymous credential: no enable/disable, no delete.
        compose.onNodeWithTag("connection_toggle_enabled_ic-7a").assertDoesNotExist()
        compose.onNodeWithTag("connection_delete_ic-7a").assertDoesNotExist()
        compose.onNodeWithTag("connection_select_ic-7a").performScrollTo().performClick()
        compose.onNodeWithTag("connection_checkin_complete_ic-7a").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("ic-7a"), host.selectIds)
            assertEquals(listOf("ic-7a"), host.completeCheckinIds)
        }
        // System back closes the sheet without mutating anything.
        dismissSheetWithBack("ic-7a")
        compose.runOnIdle {
            assertEquals(listOf("prov-ic-7a"), host.detailProviderIds)
            assertEquals(listOf("ic-7a"), host.selectIds)
            assertEquals(listOf("ic-7a"), host.completeCheckinIds)
            assertTrue(host.setEnabledCalls.isEmpty())
            assertTrue(host.deleteIds.isEmpty())
            assertTrue(host.configureCheckinCalls.isEmpty())
            assertTrue(host.viewedUris.isEmpty())
        }

        // Selected connection: no select entry (already selected), mutators reachable.
        openSheet("ic-7b")
        compose.onNodeWithTag("connection_select_ic-7b").assertDoesNotExist()
        compose.onNodeWithTag("connection_toggle_enabled_ic-7b").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("connection_delete_ic-7b").performScrollTo().assertIsDisplayed()
        dismissSheetWithBack("ic-7b")

        // An existing credential with an empty label is unnamed, never anonymous.
        openSheet("ic-7c")
        val unnamed = str(R.string.connection_card_unnamed_credential)
        compose.onNodeWithText(str(R.string.label_credential) + ": " + unnamed)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("connection_toggle_enabled_ic-7c").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("connection_delete_ic-7c").performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.connection_card_delete_target, "Atlas Provider", unnamed))
            .assertIsDisplayed()
        compose.onNodeWithText(str(R.string.action_cancel)).performClick()
        compose.runOnIdle { assertTrue(host.deleteIds.isEmpty()) }
    }

    @Test
    fun savingBlocksFaceNavigationAndMutatorsButKeepsMoreReadable() {
        val host = mount(
            CardUnderTest(card(id = "ic-8a"), config = mutableStateOf(CardConfig(saving = true))),
            CardUnderTest(card(id = "ic-8b")),
        )
        // While saving, the primary face no longer navigates (touch must be inert).
        cardNode("ic-8a").performScrollTo().performTouchInput { click(center) }
        compose.runOnIdle { assertTrue(host.detailProviderIds.isEmpty()) }

        // More stays available for read-only info and external check-in.
        openSheet("ic-8a")
        compose.onNodeWithText("https://example.invalid/v1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("connection_toggle_enabled_ic-8a")
            .performScrollTo().assertIsNotEnabled().performTouchInput { click(center) }
        compose.onNodeWithTag("connection_configure_checkin_ic-8a").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("connection_delete_ic-8a").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue(host.setEnabledCalls.isEmpty())
            assertTrue(host.deleteIds.isEmpty())
            assertTrue(host.configureCheckinCalls.isEmpty())
        }
        dismissSheet("ic-8a")

        // The saving gate is scoped to the connection that is actually saving.
        cardNode("ic-8b").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("prov-ic-8b"), host.detailProviderIds) }

        // Reactive edit: clearing saving restores navigation for the same card.
        updateConfig("ic-8a") { it.copy(saving = false) }
        cardNode("ic-8a").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("prov-ic-8b", "prov-ic-8a"), host.detailProviderIds)
        }
    }

    @Test
    fun unknownFieldsAreOmittedWhileKnownSourcesStayLabelled() {
        mount(
            // Every intelligence field unknown: identity only, no badge, no invented value.
            CardUnderTest(card(id = "ic-9a")),
            // Known official health wins over the local observation and is labelled official.
            CardUnderTest(
                card(id = "ic-9b", officialHealth = "DEGRADED", observedHealth = "HEALTHY", quotaState = "QUOTA_LOW"),
            ),
            // Local-only observation must say so instead of pretending to be official.
            CardUnderTest(card(id = "ic-9c", observedHealth = "OFFLINE")),
        )
        compose.onNode(hasText("Atlas Provider") and hasAnyAncestor(hasTestTag("connection_card_ic-9a")),
            useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.connection_card_scope, "Primary Key", "atlas-model-ic-9a"))
            .assertIsDisplayed()

        val unknownCardBadges = listOf(
            str(R.string.status_selected),
            str(R.string.credential_disabled),
            str(R.string.notification_rule_pending_checkin),
            str(R.string.connection_card_health_healthy),
            str(R.string.connection_card_health_degraded),
            str(R.string.connection_card_health_unstable),
            str(R.string.connection_card_health_offline),
            str(quotaDisplayLabel(QuotaState.QUOTA_AVAILABLE)),
            str(quotaDisplayLabel(QuotaState.QUOTA_LOW)),
            str(quotaDisplayLabel(QuotaState.QUOTA_EXHAUSTED)),
            str(quotaDisplayLabel(QuotaState.RATE_LIMIT_ONLY)),
            str(quotaDisplayLabel(QuotaState.PUBLIC_POOL)),
            str(quotaDisplayLabel(QuotaState.UNLIMITED_CLAIMED)),
            str(quotaDisplayLabel(QuotaState.NO_KNOWN_CONSUMABLE_QUOTA)),
        )
        val inventedValues = listOf(
            "未知",
            "FREE NOW",
            "\$0",
            "100%",
            "0 RPM",
            "30 RPM",
            "7 天",
            "TTFT",
            "TPS",
            "tokens/s",
        )
        // Node queries stay on the test thread; only the assertion is evaluated here.
        unknownCardBadges.forEach { label ->
            val rendered = compose.onAllNodes(hasText(label) and
                hasAnyAncestor(hasTestTag("connection_card_ic-9a")), useUnmergedTree = true)
                .fetchSemanticsNodes()
            assertTrue("must not render '$label'", rendered.isEmpty())
        }
        inventedValues.forEach { label ->
            assertTrue("must not invent '$label'",
                compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }

        // Known values keep an explicit source label, never a guessed one.
        compose.onNodeWithText(
            str(R.string.connection_card_official_health, str(R.string.connection_card_health_degraded)),
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(quotaDisplayLabel(QuotaState.QUOTA_LOW))).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            str(R.string.connection_card_local_health, str(R.string.connection_card_health_offline)),
        ).performScrollTo().assertIsDisplayed()
        // The official card must not also claim the local source, and vice versa.
        val mislabelled = listOf(
            str(R.string.connection_card_local_health, str(R.string.connection_card_health_healthy)),
            str(R.string.connection_card_official_health, str(R.string.connection_card_health_offline)),
        )
        mislabelled.forEach { label ->
            val rendered = compose.onAllNodesWithText(label, useUnmergedTree = true)
                .fetchSemanticsNodes()
            assertTrue("must not render mislabelled source '$label'", rendered.isEmpty())
        }
    }

    @Test
    fun dedicatedScopedStatusIsPerConnectionAndDoesNotLeakToSibling() {
        val timeA = 1_700_000_000_000L
        val observedA = 1_699_990_000_000L
        val timeB = 1_700_020_000_000L
        val observedB = 1_699_980_000_000L
        mount(
            CardUnderTest(
                item = card(id = "ic-10a", providerId = "prov-shared"),
                config = mutableStateOf(
                    CardConfig(dedicated = true, dedicatedOpState = OpState(
                        lastSynced = timeA, latestObserved = observedA,
                    )),
                ),
            ),
            CardUnderTest(
                item = card(id = "ic-10b", providerId = "prov-shared"),
                config = mutableStateOf(
                    CardConfig(dedicated = true, dedicatedOpState = OpState(
                        error = "AUTH", lastSynced = timeB, latestObserved = observedB,
                    )),
                ),
            ),
        )
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val timeAText = str(R.string.sync_triggered_at_format, fmt.format(Date(timeA)))
        val observedAText = str(R.string.label_observed_health) + ": " + fmt.format(Date(observedA))
        val timeBText = str(R.string.sync_triggered_at_format, fmt.format(Date(timeB)))
        val observedBText = str(R.string.label_observed_health) + ": " + fmt.format(Date(observedB))
        val errorAuth = str(R.string.error_auth)

        // Same provider, two exact scopes: success times coexist with the sibling failure.
        openSheet("ic-10a")
        assertTrue(statusHasText(dedicatedStatusTag("ic-10a"), timeAText))
        assertTrue(statusHasText(dedicatedStatusTag("ic-10a"), observedAText))
        assertTrue(statusHasNoText(dedicatedStatusTag("ic-10a"), errorAuth))
        assertTrue(statusHasNoText(dedicatedStatusTag("ic-10a"), timeBText))
        assertTrue(statusHasNoText(dedicatedStatusTag("ic-10a"), observedBText))
        dismissSheet("ic-10a")

        openSheet("ic-10b")
        assertTrue(statusHasText(dedicatedStatusTag("ic-10b"), errorAuth))
        assertTrue(statusHasText(dedicatedStatusTag("ic-10b"), timeBText))
        assertTrue(statusHasText(dedicatedStatusTag("ic-10b"), observedBText))
        assertTrue(statusHasNoText(dedicatedStatusTag("ic-10b"), timeAText))
        assertTrue(statusHasNoText(dedicatedStatusTag("ic-10b"), observedAText))
        // Only the safe category is rendered, never the raw code.
        assertTrue(compose.onAllNodesWithText("AUTH", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun scopedLoadingIsPerConnectionAndSiblingActionStillCallable() {
        val host = mount(
            CardUnderTest(
                item = card(
                    id = "ic-11a", providerId = "prov-shared",
                    accountId = "acc-11a", credentialId = "cred-11a",
                ),
                config = mutableStateOf(
                    CardConfig(
                        dedicated = true,
                        dedicatedSyncing = setOf("prov-shared"),
                        dedicatedOpState = OpState(syncing = true),
                    ),
                ),
            ),
            CardUnderTest(
                item = card(
                    id = "ic-11b", providerId = "prov-shared",
                    accountId = "acc-11b", credentialId = "cred-11b",
                ),
                config = mutableStateOf(CardConfig(
                    dedicated = true,
                    dedicatedSyncing = setOf("prov-shared"),
                    dedicatedSyncGroup = "vip",
                )),
            ),
        )
        // Provider-level busy is face-only: both faces announce it...
        cardNode("ic-11a").performScrollTo()
        cardNode("ic-11b").performScrollTo()
        assertTrue(faceStateDescription("ic-11a")!!.contains(str(R.string.status_syncing)))
        assertTrue(faceStateDescription("ic-11b")!!.contains(str(R.string.status_syncing)))
        assertTrue(compose.onAllNodesWithTag("connection_sync_progress_ic-11a")
            .fetchSemanticsNodes().isNotEmpty())

        // ...but the action only follows that card's own op key.
        openSheet("ic-11a")
        compose.onNodeWithTag("connection_dedicated_sync_ic-11a")
            .performScrollTo().assertIsNotEnabled().performTouchInput { click(center) }
        dismissSheet("ic-11a")

        openSheet("ic-11b")
        compose.onNodeWithTag("connection_dedicated_sync_ic-11b")
            .performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(DedicatedSyncCall("prov-shared", "acc-11b", "cred-11b", "vip")),
                host.dedicatedSyncCalls,
            )
        }
    }

    @Test
    fun dedicatedSyncGroupForRequiresExactSelectedScopeAndPassesRaw() {
        val target = card(id = "t", providerId = "prov", accountId = "acc", credentialId = "cred")
        val match = card(id = "m", providerId = "prov", accountId = "acc", credentialId = "cred")
        assertEquals("vip", dedicatedSyncGroupFor(target, match, "vip"))
        assertEquals("raw padded group must pass unchanged", " vip ",
            dedicatedSyncGroupFor(target, match, " vip "))
        assertEquals("blank non-null group must be forwarded unchanged", "",
            dedicatedSyncGroupFor(target, match, ""))
        assertNull(dedicatedSyncGroupFor(target, match, null))
        assertNull(dedicatedSyncGroupFor(target,
            card(id = "p2", providerId = "other", accountId = "acc", credentialId = "cred"), "vip"))
        assertNull(dedicatedSyncGroupFor(target,
            card(id = "a2", providerId = "prov", accountId = "other", credentialId = "cred"), "vip"))
        assertNull(dedicatedSyncGroupFor(target,
            card(id = "c2", providerId = "prov", accountId = "acc", credentialId = "other"), "vip"))
        assertNull(dedicatedSyncGroupFor(target, null, "vip"))
    }

    @Test
    fun dedicatedDispatchForwardsGroupVerbatimIncludingBlank() {
        val host = mount(
            CardUnderTest(
                item = card(id = "ic-12a", providerId = "prov-shared"),
                config = mutableStateOf(CardConfig(dedicated = true, dedicatedSyncGroup = " vip ")),
            ),
            CardUnderTest(
                item = card(id = "ic-12b", providerId = "prov-shared"),
                config = mutableStateOf(CardConfig(dedicated = true, dedicatedSyncGroup = "")),
            ),
        )
        openSheet("ic-12a")
        compose.onNodeWithTag("connection_dedicated_sync_ic-12a").performScrollTo().performClick()
        dismissSheet("ic-12a")
        openSheet("ic-12b")
        compose.onNodeWithTag("connection_dedicated_sync_ic-12b").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    DedicatedSyncCall("prov-shared", "acc-ic-12a", "cred-ic-12a", " vip "),
                    DedicatedSyncCall("prov-shared", "acc-ic-12b", "cred-ic-12b", ""),
                ),
                host.dedicatedSyncCalls,
            )
        }
    }

    @Test
    fun scopedNewApiSyncForCapturesOwnScopeAndScopedStateOverridesLegacy() {
        val a = card(id = "na", providerId = "prov-na", accountId = "acc-na", credentialId = "cred-na")
        val b = card(id = "nb", providerId = "prov-nb", accountId = "   ", credentialId = "cred-nb")
        val calls = mutableListOf<DedicatedSyncCall>()
        scopedNewApiSyncFor(a) { p, acc, cred -> calls += DedicatedSyncCall(p, acc, cred, null) }("prov-na")
        scopedNewApiSyncFor(b) { p, acc, cred -> calls += DedicatedSyncCall(p, acc, cred, null) }("prov-nb")
        assertEquals(
            "each card captures its own scope and blank ids collapse to null",
            listOf(
                DedicatedSyncCall("prov-na", "acc-na", "cred-na", null),
                DedicatedSyncCall("prov-nb", null, "cred-nb", null),
            ),
            calls,
        )

        mount(
            CardUnderTest(
                item = card(id = "ic-13a", providerId = "prov-x", supportsSync = true),
                config = mutableStateOf(CardConfig(
                    syncingProviders = setOf("prov-x"), newApiOpState = OpState(syncing = false),
                )),
            ),
            CardUnderTest(
                item = card(id = "ic-13b", providerId = "prov-y", supportsSync = true),
                config = mutableStateOf(CardConfig(
                    syncingProviders = emptySet(), newApiOpState = OpState(syncing = true),
                )),
            ),
            CardUnderTest(
                item = card(id = "ic-13c", providerId = "prov-z", supportsSync = true),
                config = mutableStateOf(CardConfig(newApiOpState = OpState(error = "AUTH"))),
            ),
            CardUnderTest(
                item = card(id = "ic-13d", providerId = "prov-w", supportsSync = true),
                config = mutableStateOf(CardConfig(syncingProviders = setOf("prov-w"))),
            ),
        )
        // Supplied scoped state owns the New API busy/result; null keeps the legacy provider path.
        openSheet("ic-13a")
        compose.onNodeWithTag("connection_sync_ic-13a").performScrollTo().assertIsEnabled()
        assertTrue(statusHasNoText(newApiStatusTag("ic-13a"), str(R.string.error_auth)))
        dismissSheet("ic-13a")

        openSheet("ic-13b")
        compose.onNodeWithTag("connection_sync_ic-13b").performScrollTo().assertIsNotEnabled()
        dismissSheet("ic-13b")

        openSheet("ic-13c")
        compose.onNodeWithTag(newApiStatusTag("ic-13c")).assertExists()
        assertTrue(statusHasText(newApiStatusTag("ic-13c"), str(R.string.error_auth)))
        assertTrue(compose.onAllNodesWithText("AUTH", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        dismissSheet("ic-13c")

        openSheet("ic-13d")
        // Legacy provider-only operation acquires no scoped status node at all.
        compose.onNodeWithTag(newApiStatusTag("ic-13d")).assertDoesNotExist()
        compose.onNodeWithTag("connection_sync_ic-13d").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun missingScopedStateDoesNotInheritProviderAggregate() {
        mount(
            CardUnderTest(
                item = card(id = "ic-14a", providerId = "prov-shared", supportsSync = true),
                config = mutableStateOf(CardConfig(
                    dedicated = true,
                    dedicatedSyncing = setOf("prov-shared"),
                    syncingProviders = setOf("prov-shared"),
                )),
            ),
        )
        cardNode("ic-14a").performScrollTo()
        assertTrue(faceStateDescription("ic-14a")!!.contains(str(R.string.status_syncing)))
        openSheet("ic-14a")
        // No own-op result exists, so the status lines must stay empty (no borrowed aggregate).
        assertTrue(statusTexts(dedicatedStatusTag("ic-14a")).isEmpty())
        compose.onNodeWithTag(newApiStatusTag("ic-14a")).assertDoesNotExist()
        compose.onNodeWithTag("connection_sync_ic-14a").performScrollTo().assertIsNotEnabled()
    }
}
