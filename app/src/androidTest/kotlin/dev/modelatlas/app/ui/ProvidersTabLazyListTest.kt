package dev.modelatlas.app.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.R as ComposeUiResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasUiState
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.ConnectionItem
import dev.modelatlas.app.R
import dev.modelatlas.app.onboarding.BatchImportViewModel
import dev.modelatlas.app.onboarding.OnboardingViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S19 / PK-20 — the `ProvidersTab` list is a keyed `LazyColumn`.
 *
 * TRACEABILITY PK-20 (S19 migration); contract §23.8 / §23.11 (UI-INTEL-001A surface).
 *
 * Why this file exists: replacing the eager scrollable `Column` with a `LazyColumn`
 * changes one observable contract — only viewport items are composed, so a below-fold
 * card has no semantics node at all. Anything that asserted "this card exists" without
 * first scrolling the container would silently become vacuous, and a silent reversion
 * to an eager `Column` would go unnoticed. These vectors pin both directions.
 *
 * Harness (deliberately the smallest real seam):
 * - real [AtlasViewModel] / [OnboardingViewModel] / [BatchImportViewModel] over the real
 *   [AtlasApplication] (same construction seam as `ScopedSyncUiBindingTest`);
 * - the VM's init repository collectors are cancelled once (the seam `PendingConnectionHandoffTest`
 *   already uses), then the public [AtlasUiState] projection is written through the VM's own
 *   `StateFlow`. So the card list content is an exact, ordered input — 0, 1 or 12 cards —
 *   with no Room rows, no vault entries and no network for any fixture;
 * - the production `ProvidersTab` renders the real `ConnectionCard`; only the *data* is injected.
 * - VM actions that do not depend on persisted rows stay on the real VM: the scoped New API
 *   trigger still runs `vm::syncNewApi` and still fails closed to `UNSUPPORTED` before the
 *   first transport call, exactly like `ScopedSyncUiBindingTest`.
 * - the form-clear contract is driven through the real `formClearToken` counter that
 *   `saveConnection` bumps (`LaunchedEffect(clearToken)` in `ProvidersTab`); the DB write of a
 *   real save is covered by the VM flow tests, this file only pins the UI clear-on-success path.
 *
 * Vectors (6):
 *  1. `lazyListComposesFewerCardsThanItHolds` — 12 connections must not produce 12 composed
 *     card nodes (guards a silent reversion to the eager Column).
 *  2. `longListScrollsToTheLastCardAndKeepsTheFormReachable` — bottom card + its More sheet are
 *     reachable, and after closing it the form and the first card are reachable again.
 *  3. `removingAScrolledBottomCardKeepsSiblingsIntact` — keyed removal from a scrolled position
 *     shrinks the list without crashing and never re-attributes another card's dialog state.
 *  4. `emptyBranchAppearsAndIsReplacedByTheFirstCard` — the static `empty` item key means the
 *     empty→non-empty transition never misattributes state to a card slot.
 *  5. `clearTokenResetsAllFourFormFields` — the form-state remembers above the list are untouched
 *     by the migration.
 *  6. `scopedSyncFromALazyComposedCardDispatchesItsOwnExactTriple` — the 3-arg scoped path is
 *     still wired per card from inside the lazy list; the sibling scope borrows nothing.
 */
@RunWith(AndroidJUnit4::class)
class ProvidersTabLazyListTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: AtlasApplication
    private lateinit var vmStore: ViewModelStore
    private lateinit var viewModel: AtlasViewModel
    private lateinit var onboardingViewModel: OnboardingViewModel
    private lateinit var batchImportViewModel: BatchImportViewModel

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun str(id: Int): String = context.getString(id)

    @Before
    fun setUp() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "must not run on main thread" }
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as AtlasApplication
        runBlocking { withTimeout(TIMEOUT_MS) { app.ready.first { it } } }
        vmStore = ViewModelStore()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel = ViewModelProvider(vmStore, object : ViewModelProvider.Factory {
                @Suppress("DEPRECATION")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AtlasViewModel(app) as T
                }
            }).get(AtlasViewModel::class.java)
        }
        onboardingViewModel = OnboardingViewModel(app)
        batchImportViewModel = BatchImportViewModel(app)
        // Deterministic list input: stop the init repository collectors so the real DB
        // projection cannot overwrite the injected managed-connection list mid-test.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val job = viewModel.viewModelScope.coroutineContext[Job]
                ?: throw AssertionError("viewModelScope must expose a Job")
            job.children.forEach { it.cancel() }
        }
    }

    @After
    fun tearDown() {
        if (::vmStore.isInitialized) {
            InstrumentationRegistry.getInstrumentation()
                .runOnMainSync { runCatching { vmStore.clear() } }
        }
    }

    // ---- harness ----

    @Suppress("UNCHECKED_CAST")
    private fun injectState(state: AtlasUiState) {
        compose.runOnIdle {
            val field = AtlasViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
            (field.get(viewModel) as MutableStateFlow<AtlasUiState>).value = state
        }
        compose.waitForIdle()
    }

    /** Mount the real screen, publish the exact card list, then open the Providers tab. */
    private fun mountProviders(connections: List<ConnectionItem>) {
        compose.setContent {
            AtlasScreen(
                viewModel = viewModel,
                onboardingViewModel = onboardingViewModel,
                batchImportViewModel = batchImportViewModel,
            )
        }
        injectState(
            AtlasUiState(
                connections = connections,
                managedConnections = connections,
                selectedId = null,
                connectionsLoading = false,
            ),
        )
        compose.onNodeWithText(str(R.string.tab_providers)).performClick()
        compose.waitForIdle()
    }

    private fun card(
        id: String,
        providerId: String = "prov-$id",
        accountId: String = "acc-$id",
        credentialId: String = "cred-$id",
        supportsSync: Boolean = false,
    ) = ConnectionItem(
        id = id,
        providerId = providerId,
        name = "Lazy Provider",
        baseUrl = BASE_URL,
        modelId = "lazy-model-$id",
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

    private fun cards(count: Int): List<ConnectionItem> = (1..count).map { card(cardId(it)) }

    private fun cardId(index: Int) = "lazy-%02d".format(index)

    // ---- lazy-list addressing ----

    private fun listNode() = compose.onNodeWithTag(LIST_TAG)

    /**
     * The only supported way to reach a lazy list item: scroll the container first, then act on
     * the now-composed node. A bare node lookup on a below-fold item is not a valid assertion.
     */
    private fun scrollToTag(tag: String) {
        listNode().performScrollToNode(hasTestTag(tag))
    }

    private fun scrollToText(text: String) {
        // A list that fits entirely (1 card) exposes no scroll action — the node is already
        // composed; skip the scroll instead of throwing.
        runCatching { listNode().performScrollToNode(hasText(text)) }
    }

    /** True only if the tag exists AND the container can scroll it into composition. */
    private fun listHasTag(tag: String): Boolean = runCatching { scrollToTag(tag) }.isSuccess

    private fun cardTag(id: String) = "connection_card_$id"

    private fun moreTag(id: String) = "connection_more_$id"

    private fun composedCardCount(): Int = compose.onAllNodes(
        SemanticsMatcher("connection_card_*") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("connection_card_") == true
        },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().size

    private fun formField(labelRes: Int) =
        compose.onNode(hasSetTextAction() and hasText(str(labelRes)))

    private fun fieldText(labelRes: Int): String? =
        formField(labelRes).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.EditableText)?.text

    private fun openSheet(id: String) {
        scrollToTag(moreTag(id))
        compose.onNodeWithTag(moreTag(id)).performClick()
        // Sheet open animation races the assert: wait for existence, not display.
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag("connection_menu_$id"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * 關閉 More sheet。若 sheet 已（由取消確認等流程）關閉，冪等通過——上游
     * :333 的 menu-empty 等待即 sheet 關閉的證據，此時 scrim 永不出現是正確狀態。
     */
    private fun closeSheet(id: String) {
        val menuGone = compose.onAllNodes(hasTestTag("connection_menu_$id"), useUnmergedTree = true)
            .fetchSemanticsNodes().isEmpty()
        if (menuGone) return
        val scrimDesc = context.getString(ComposeUiResources.string.close_sheet)
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodes(hasContentDescription(scrimDesc)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(scrimDesc)
            .assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { action ->
                assertTrue("scrim must accept its dismiss action", action())
            }
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag("connection_menu_$id"), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    // ---- 1. laziness guard ----

    @Test
    fun lazyListComposesFewerCardsThanItHolds() {
        val seeded = cards(12)
        mountProviders(seeded)
        compose.waitForIdle()

        val composed = composedCardCount()
        assertTrue(
            "a LazyColumn must not compose every card up front (composed=$composed of ${seeded.size})",
            composed < seeded.size,
        )
        // The container itself is the real scroll target the migrated tests address.
        listNode().assertIsDisplayed()
    }

    // ---- 2. long-list scroll + sheet + back to the form ----

    @Test
    fun longListScrollsToTheLastCardAndKeepsTheFormReachable() {
        val seeded = cards(12)
        mountProviders(seeded)

        val last = seeded.last().id
        val first = seeded.first().id

        // The bottom card is only reachable through the container scroll.
        scrollToTag(moreTag(last))
        compose.onNodeWithTag(moreTag(last)).assertExists()
        compose.onNodeWithTag(cardTag(last)).assertExists()

        openSheet(last)
        closeSheet(last)

        // Scrolling back must not have lost the static header/form items.
        scrollToText(str(R.string.form_title))
        compose.onNodeWithText(str(R.string.form_title)).assertIsDisplayed()
        scrollToText(str(R.string.action_save))
        compose.onNodeWithText(str(R.string.action_save)).assertIsDisplayed()

        scrollToTag(cardTag(first))
        compose.onNodeWithTag(cardTag(first)).assertIsDisplayed()
    }

    // ---- 3. keyed removal while scrolled ----

    @Test
    fun removingAScrolledBottomCardKeepsSiblingsIntact() {
        val seeded = cards(12)
        mountProviders(seeded)

        val bottom = seeded[seeded.size - 1].id
        val sibling = seeded[seeded.size - 2].id
        val survivor = seeded.first().id

        // The real More sheet + delete confirmation must still be usable from a lazy item.
        scrollToTag(moreTag(bottom))
        compose.onNodeWithTag(moreTag(bottom)).performClick()
        compose.onNodeWithTag("connection_menu_$bottom").assertExists()
        compose.onNodeWithTag("connection_delete_$bottom").performScrollTo().performClick()
        // Sheet content may need internal scroll on the small test screen; existence is the contract.
        compose.onNodeWithTag("connection_delete_confirm_$bottom").assertExists()
        compose.onNodeWithText(str(R.string.action_cancel)).performClick()
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag("connection_delete_confirm_$bottom"), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        closeSheet(bottom)
        // Close the sheet before any container scroll: the sheet owns the foreground window.
        assertTrue(
            "cancel must not remove the card",
            listHasTag(cardTag(bottom)),
        )

        // Model the production callback result: the list shrinks by exactly the removed item.
        val shrunken = seeded.filterNot { it.id == bottom }
        injectState(
            AtlasUiState(
                connections = shrunken,
                managedConnections = shrunken,
                connectionsLoading = false,
            ),
        )

        assertFalse("the removed card must leave the list", listHasTag(cardTag(bottom)))
        assertTrue("the adjacent sibling must stay", listHasTag(cardTag(sibling)))
        assertTrue("the first card must stay", listHasTag(cardTag(survivor)))
        // A still-open sheet on another card must not inherit the removed card's dialog state.
        assertTrue(
            "no stale delete dialog may survive the keyed removal",
            compose.onAllNodes(hasTestTag("connection_delete_confirm_$bottom"), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    // ---- 4. empty branch ⇄ first card ----

    @Test
    fun emptyBranchAppearsAndIsReplacedByTheFirstCard() {
        mountProviders(emptyList())

        scrollToTag(QUICK_ADD_TAG)
        compose.onNodeWithTag(QUICK_ADD_TAG).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.empty_providers)).assertIsDisplayed()

        val first = card(cardId(1))
        injectState(
            AtlasUiState(
                connections = listOf(first),
                managedConnections = listOf(first),
                connectionsLoading = false,
            ),
        )

        // The static `empty` item is gone for good; it must not linger behind the first card.
        compose.onNodeWithTag(QUICK_ADD_TAG).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.empty_providers)).assertDoesNotExist()
        scrollToTag(cardTag(first.id))
        compose.onNodeWithTag(cardTag(first.id)).assertIsDisplayed()

        // And back to empty, so the branch is reachable in both directions.
        injectState(AtlasUiState(connectionsLoading = false))
        scrollToTag(QUICK_ADD_TAG)
        compose.onNodeWithTag(QUICK_ADD_TAG).assertIsDisplayed()
    }

    // ---- 5. form state above the list is untouched ----

    @Test
    fun clearTokenResetsAllFourFormFields() {
        mountProviders(cards(1))

        formField(R.string.label_name).performTextInput("Lazy Provider")
        formField(R.string.label_base_url).performTextInput(BASE_URL)
        formField(R.string.label_model).performTextInput("lazy-model-x")
        formField(R.string.label_key).performTextInput("lazy-secret-value")
        compose.waitForIdle()

        assertEquals("Lazy Provider", fieldText(R.string.label_name))
        assertEquals(BASE_URL, fieldText(R.string.label_base_url))
        assertEquals("lazy-model-x", fieldText(R.string.label_model))
        // Key field uses PasswordVisualTransformation: EditableText reads masked bullets.
        // The masked value is non-empty iff the raw text was entered.
        assertTrue("key field should hold the typed secret (masked)", !fieldText(R.string.label_key).isNullOrBlank())

        // The whole form item is still a composed, reachable list item. (IME from the
        // four performTextInput calls can cover it: assert existence/reachability, not display.)
        scrollToText(str(R.string.action_save))
        compose.onNodeWithText(str(R.string.action_save)).assertExists()

        // The production clear trigger is the `formClearToken` counter, bumped by a successful
        // save; bumping it here proves the four remembers above the list still own the fields.
        injectState(
            AtlasUiState(
                connections = cards(1),
                managedConnections = cards(1),
                formClearToken = 1,
                connectionsLoading = false,
            ),
        )

        assertEquals("", fieldText(R.string.label_name))
        assertEquals("", fieldText(R.string.label_base_url))
        assertEquals("", fieldText(R.string.label_model))
        assertEquals("", fieldText(R.string.label_key))
        // The card list must survive the clear, and the form item stays reachable.
        assertTrue(listHasTag(cardTag(cardId(1))))
        scrollToText(str(R.string.form_title))
        // IME may cover the title after the typing above; composed+reachable is the contract.
        compose.onNodeWithText(str(R.string.form_title)).assertExists()
    }

    // ---- 6. scoped sync wiring from a lazily composed card ----

    @Test
    fun scopedSyncFromALazyComposedCardDispatchesItsOwnExactTriple() {
        val a = card("lazy-sc-a", providerId = "prov-lazy-scoped", accountId = "acc-lazy-a",
            credentialId = "cred-lazy-a", supportsSync = true)
        val b = card("lazy-sc-b", providerId = "prov-lazy-scoped", accountId = "acc-lazy-b",
            credentialId = "cred-lazy-b", supportsSync = true)
        mountProviders(listOf(a, b))

        // The card is below the form: the sheet is only reachable after a container scroll.
        openSheet(a.id)
        compose.onNodeWithTag("connection_sync_${a.id}").performScrollTo().assertIsEnabled()
            .performClick()

        // Unseeded provider: `AtlasRepository.syncNewApi` validates scope before the first
        // transport call, so this flips the VM op-key to UNSUPPORTED without any network.
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            viewModel.newApiOpState(a.providerId, a.accountId, a.credentialId).error != null
        }
        compose.runOnIdle {
            assertEquals(
                "tapped scope must carry the pre-network UNSUPPORTED code",
                "UNSUPPORTED",
                viewModel.newApiOpState(a.providerId, a.accountId, a.credentialId).error,
            )
            assertNull(
                "sibling scope must not borrow the tapped scope's error",
                viewModel.newApiOpState(b.providerId, b.accountId, b.credentialId).error,
            )
        }
        closeSheet(a.id)

        // The sibling's own sheet is still callable with its own scope.
        openSheet(b.id)
        compose.onNodeWithTag("connection_sync_${b.id}").performScrollTo().assertIsEnabled()
            .performClick()
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            viewModel.newApiOpState(b.providerId, b.accountId, b.credentialId).error != null
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        /** The `ProvidersTab` LazyColumn container tag. */
        const val LIST_TAG = "providers_tab_list"
        const val QUICK_ADD_TAG = "provider_empty_quick_add"
        const val BASE_URL = "https://lazy-list.fixture.invalid/v1"
    }
}