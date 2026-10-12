package dev.modelatlas.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.ConnectionItem
import dev.modelatlas.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI-INTEL-001A layout/a11y coverage for the main-owned compact ConnectionCard.
 *
 * Scope is geometry + accessibility only; callback orchestration and the real
 * MainActivity flow belong to the sibling interaction/flow tests. Every size claim is a
 * real measured bound (`fetchSemanticsNode().boundsInRoot`) converted through the
 * `LocalDensity` captured inside the composition — never a screenshot or a visual claim.
 *
 * The device matrix (280/360 dp × fontScale 1/2 × light/dark) runs in ONE composition that
 * is reconfigured through state, because `ComposeTestRule.setContent` may only be called once
 * per test.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionCardLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val cardTag = "connection_card_conn-1"
    private val moreTag = "connection_more_conn-1"
    private val menuTag = "connection_menu_conn-1"
    private val hostTag = "ui001_host"

    private val buttonRole = SemanticsMatcher("Role.Button") {
        it.config.getOrNull(SemanticsProperties.Role) == Role.Button
    }

    private fun selectedProp(expected: Boolean) = SemanticsMatcher("selected=$expected") {
        it.config.getOrNull(SemanticsProperties.Selected) == expected
    }

    private fun stateDescriptionIs(expected: String) =
        SemanticsMatcher("stateDescription is '$expected'") {
            it.config.getOrNull(SemanticsProperties.StateDescription) == expected
        }

    private fun contentDescriptionIs(expected: String) =
        SemanticsMatcher("contentDescription is '$expected'") {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { cd -> cd == expected } == true
        }

    /** `LocalDensity` as read inside the composition; dp→px only uses `.density`, not fontScale. */
    private var composedDensity: Density = Density(density = 1f, fontScale = 1f)

    private fun touchPx(): Float = with(composedDensity) { 48.dp.toPx() }

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun str(id: Int): String = context.getString(id)

    private fun str(id: Int, vararg args: Any?): String = context.getString(id, *args)

    private fun shortItem() = ConnectionItem(
        id = "conn-1",
        providerId = "prov-1",
        name = "Atlas Provider",
        baseUrl = "https://example.invalid/v1",
        modelId = "atlas-model-a",
        credentialLabel = "Key ····1",
        checkinUrl = "https://example.invalid/checkin",
        checkinPending = true,
        accountId = "acc-1",
        credentialId = "cred-1",
        enabled = true,
        supportsSync = true,
    )

    /** Same row, deliberately long display name so the title must wrap/ellipsize next to More. */
    private fun longTitleItem() = shortItem().copy(
        name = "Atlas Provider 全球邊緣節點加速閘道 Long Display Name Extended",
    )

    private fun identityText(item: ConnectionItem) =
        str(R.string.connection_card_identity, item.name, item.credentialLabel, item.modelId)

    private fun moreLabelText(item: ConnectionItem) =
        str(R.string.connection_card_more_label, item.name, item.credentialLabel, item.modelId)

    /** Font scale is kept as an integer hundredths value so map keys never rely on float formatting. */
    private data class Config(
        val widthDp: Int,
        val fontScaleHundredths: Int,
        val dark: Boolean,
        val saving: Boolean = false,
    ) {
        val fontScale: Float get() = fontScaleHundredths / 100f
    }

    private data class Host(
        val detailCalls: MutableList<String> = mutableListOf(),
        val selectCalls: MutableList<String> = mutableListOf(),
        val deleteCalls: MutableList<String> = mutableListOf(),
        val enableCalls: MutableList<Pair<String, Boolean>> = mutableListOf(),
        val configureCalls: MutableList<Pair<String, String>> = mutableListOf(),
    )

    private fun mount(
        item: ConnectionItem,
        initial: Config,
    ): Pair<MutableState<Config>, Host> {
        val config = mutableStateOf(initial)
        val host = Host()
        compose.setContent {
            val current = config.value
            val device = LocalDensity.current
            SideEffect { composedDensity = device }
            CompositionLocalProvider(
                LocalDensity provides Density(device.density, current.fontScale),
            ) {
                AtlasTheme(darkTheme = current.dark) {
                    Surface(modifier = Modifier.width(current.widthDp.dp).testTag(hostTag)) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp),
                        ) {
                            ConnectionCard(
                                item = item,
                                isSelected = true,
                                onSelect = { host.selectCalls += it },
                                onCompleteCheckin = { },
                                saving = current.saving,
                                syncingProviders = emptySet(),
                                onSync = { },
                                onSetEnabled = { id, value -> host.enableCalls += id to value },
                                onDelete = { host.deleteCalls += it },
                                onConfigureCheckin = { id, url -> host.configureCalls += id to url },
                                onDetailClick = { host.detailCalls += it },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        return config to host
    }

    private fun reconfigure(config: MutableState<Config>, next: Config) {
        compose.runOnIdle { config.value = next }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    // ---- 1. eight-configuration device matrix, one composition ----

    @Test
    fun deviceMatrixKeepsSeparateTargetsHitboxesAndGrowingCardWithLongTitle() {
        val item = longTitleItem()
        val (config, _) = mount(item, Config(widthDp = 280, fontScaleHundredths = 100, dark = false))

        val matrix = listOf(280, 360).flatMap { width ->
            listOf(100, 200).flatMap { scale ->
                listOf(false, true).map { dark -> Config(width, scale, dark) }
            }
        }
        assertEquals("matrix must keep all 8 device configurations", 8, matrix.size)

        val faceHeights = mutableMapOf<Config, Float>()
        for (current in matrix) {
            reconfigure(config, current)

            compose.onNodeWithTag(cardTag)
                .assertIsDisplayed()
                .assert(buttonRole)
                .assert(selectedProp(true))
                .assert(stateDescriptionIs(str(R.string.status_selected)))
                .assert(contentDescriptionIs(identityText(item)))
            compose.onNodeWithTag(moreTag)
                .assertIsDisplayed()
                .assert(buttonRole)
                .assert(contentDescriptionIs(moreLabelText(item)))

            val host = bounds(hostTag)
            val face = bounds(cardTag)
            val more = bounds(moreTag)
            val touch = touchPx()

            val faceHitboxOk = face.height >= touch
            assertTrue(
                "[$current] primary hitbox height ${face.height}px must be >= 48dp ($touch px)",
                faceHitboxOk,
            )
            val faceInsideHostOk = face.left >= host.left && face.right <= host.right
            assertTrue(
                "[$current] primary face ${face.left}..${face.right} overflows host ${host.left}..${host.right}",
                faceInsideHostOk,
            )
            val moreHitboxOk = more.height >= touch && more.width >= touch
            assertTrue(
                "[$current] More hitbox ${more.width}x${more.height}px must be >= 48x48dp ($touch px)",
                moreHitboxOk,
            )
            val moreInsideHostOk = more.left >= host.left && more.right <= host.right
            assertTrue("[$current] More overflows host width", moreInsideHostOk)

            // Long title must not run underneath the More button, and the card is not a
            // fixed-height box: 2x font scale has to grow the same face.
            val title = compose.onNodeWithText(item.name, useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val titleClearOfMore = title.right <= more.left || title.bottom <= more.top
            assertTrue(
                "[$current] title ${title.left}..${title.right}/${title.top}..${title.bottom} overlaps More " +
                    "${more.left}..${more.right}/${more.top}..${more.bottom}",
                titleClearOfMore,
            )
            val menuClosed = compose.onAllNodesWithTag(menuTag, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
            assertTrue("[$current] More sheet must stay closed until More is used", menuClosed)

            faceHeights[current] = face.height
        }

        for (width in listOf(280, 360)) {
            for (dark in listOf(false, true)) {
                val base = faceHeights[Config(width, 100, dark)]!!
                val large = faceHeights[Config(width, 200, dark)]!!
                assertTrue(
                    "[${Config(width, 200, dark)}] card height must grow with font scale " +
                        "(2x=$large px, 1x=$base px)",
                    large > base,
                )
            }
        }
    }

    // ---- 2. Unknown fixture face + More sheet scroll reachability ----

    @Test
    fun unknownFaceInventsNothingAndSheetKeepsDeleteAndSaveScrollReachable() {
        val item = shortItem()
        val (_, host) = mount(item, Config(widthDp = 280, fontScaleHundredths = 100, dark = false))
        val touch = touchPx()

        // Unknown fixture face: no 0 / free / 100% invention, and no quota badge at all,
        // because production omits the badge for UNKNOWN on the compact face.
        for (banned in listOf("未知", "FREE NOW", "$0", "100%")) {
            val shown = compose.onAllNodes(hasText(banned), useUnmergedTree = true)
                .fetchSemanticsNodes().size
            assertTrue("compact face must not show '$banned'", shown == 0)
        }

        // More is its own sibling click target: it opens the sheet and never navigates.
        compose.onNodeWithTag(moreTag).performClick()
        compose.onNodeWithTag(menuTag).assertIsDisplayed()
        assertTrue("More must not trigger primary-face navigation", host.detailCalls.isEmpty())

        // Measure visible nodes in this modal before scrolling its information section.
        val sheetTitle = compose.onNodeWithText(str(R.string.connection_card_actions))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val header = compose.onNode(hasText(item.name) and hasAnyAncestor(hasTestTag(menuTag)),
            useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("sheet header must not overlap its title", header.top >= sheetTitle.bottom)

        // Sheet keeps existing Unknown explicit instead of inventing values, and the
        // credential line is the full label, not just the bare model.
        compose.onNodeWithText(
            str(R.string.label_quota) + ": " + str(quotaDisplayLabel(compareQuotaStateOf(item.quotaState))),
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            str(R.string.label_price) + ": " + str(priceBadgeText(comparePriceStateOf(item.priceState))),
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            str(R.string.label_credential) + ": " + item.credentialLabel,
        ).performScrollTo().assertIsDisplayed()

        // Delete is reachable by scrolling and is a real 48dp hitbox; its confirm dialog too.
        val delete = compose.onNodeWithTag("connection_delete_conn-1")
            .performScrollTo().assertIsDisplayed()
        val deleteHeight = delete.fetchSemanticsNode().boundsInRoot.height
        assertTrue("delete hitbox $deleteHeight px must be >= 48dp ($touch px)", deleteHeight >= touch)
        delete.performClick()
        compose.onNodeWithTag("connection_delete_confirm_conn-1").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.action_cancel)).performClick()

        // Save path: configure entry reachable by scroll, save dialog has a real 48dp target.
        compose.onNodeWithTag(moreTag).performClick()
        val configure = compose.onNodeWithTag("connection_configure_checkin_conn-1")
            .performScrollTo().assertIsDisplayed()
        val configureHeight = configure.fetchSemanticsNode().boundsInRoot.height
        assertTrue("configure hitbox $configureHeight px must be >= 48dp ($touch px)", configureHeight >= touch)
        configure.performClick()
        compose.onNodeWithTag("connection_checkin_input_conn-1").assertIsDisplayed()
        val save = compose.onNodeWithTag("connection_checkin_save_conn-1").assertIsDisplayed()
        val saveHeight = save.fetchSemanticsNode().boundsInRoot.height
        assertTrue("save hitbox $saveHeight px must be >= 48dp ($touch px)", saveHeight >= touch)
        compose.onNodeWithText(str(R.string.action_cancel)).performClick()

        assertTrue("no credential may be deleted from a layout test", host.deleteCalls.isEmpty())
        assertTrue("no check-in URL may be written from a layout test", host.configureCalls.isEmpty())
        assertTrue("layout test must not select a connection", host.selectCalls.isEmpty())
    }

    // ---- 3. Saving: nav and mutators disabled, More stays a usable info entry ----

    @Test
    fun savingDisablesPrimaryNavAndMutatorsButMoreStaysReadable() {
        val item = shortItem()
        val (_, host) = mount(item, Config(widthDp = 280, fontScaleHundredths = 200, dark = true, saving = true))
        val touch = touchPx()

        compose.onNodeWithTag(cardTag).assert(
            stateDescriptionIs(
                listOf(str(R.string.status_selected), str(R.string.connection_card_saving)).joinToString("，"),
            ),
        )

        // Primary face navigation is disabled while saving: a real touch must do nothing.
        compose.onNodeWithTag(cardTag).performTouchInput { click(center) }
        compose.runOnIdle {
            assertTrue("saving must block primary-face navigation", host.detailCalls.isEmpty())
        }

        // More is NOT globally disabled: it keeps a full-scope label and a real 48dp hitbox.
        val more = compose.onNodeWithTag(moreTag)
            .assertIsDisplayed()
            .assert(buttonRole)
            .assert(contentDescriptionIs(moreLabelText(item)))
        val moreBounds = more.fetchSemanticsNode().boundsInRoot
        val moreHitboxOk = moreBounds.height >= touch && moreBounds.width >= touch
        assertTrue(
            "More hitbox ${moreBounds.width}x${moreBounds.height} px must stay >= 48x48dp while saving ($touch px)",
            moreHitboxOk,
        )
        more.performClick()
        compose.onNodeWithTag(menuTag).assertIsDisplayed()

        // Readable information while saving, not icon-only guessing.
        compose.onNodeWithText(item.baseUrl).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.label_model) + ": " + item.modelId)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.label_credential) + ": " + item.credentialLabel)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            str(R.string.label_price) + ": " + str(priceBadgeText(comparePriceStateOf(item.priceState))),
        ).performScrollTo().assertIsDisplayed()

        // Mutators are individually disabled while saving.
        compose.onNodeWithTag("connection_toggle_enabled_conn-1").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("connection_configure_checkin_conn-1").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("connection_delete_conn-1").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue("saving must disable enable/disable", host.enableCalls.isEmpty())
            assertTrue("saving must disable delete", host.deleteCalls.isEmpty())
            assertTrue("saving must disable check-in configure", host.configureCalls.isEmpty())
        }
    }
}
