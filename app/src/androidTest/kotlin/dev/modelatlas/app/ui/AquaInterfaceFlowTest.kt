package dev.modelatlas.app.ui

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.ConnectionItem
import dev.modelatlas.app.MainActivity
import dev.modelatlas.app.R
import dev.modelatlas.app.data.AppearanceSettings
import dev.modelatlas.app.data.AppearanceSettingsStore
import dev.modelatlas.app.data.AquaWallpaper
import dev.modelatlas.app.data.InterfaceStyle
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual MainActivity → Settings → VM → committed local preferences.
 * System/UI capture changes require an explicit disposable-emulator opt-in;
 * production FLAG_SECURE stays unchanged. Every owned setting is restored. */
@RunWith(AndroidJUnit4::class)
class AquaInterfaceFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: AtlasApplication
    private lateinit var vm: AtlasViewModel
    private lateinit var previous: AppearanceSettings
    private lateinit var previousRaw: Map<String, *>
    private var previousNight = UiModeManager.MODE_NIGHT_NO
    private var previousFont = "1.0"
    private var previousResolvedFontScale = 1f
    private var previousSize: String? = null
    private var restoreArmed = false
    private var previousSecure = false
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var bodyPassed = false
    private var fixture: ConnectionItem? = null
    private var samplesBefore = 0L
    private val pictures = JSONArray()
    private var reportName = "aqua-ui-incomplete.json"
    private val preferences get() = app.getSharedPreferences(AppearanceSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)

    @Before fun prepareOwnedFixture() {
        check(InstrumentationRegistry.getArguments().getString("allowAquaSystemTest") == "true") {
            "Aqua system/capture QA requires allowAquaSystemTest=true on a disposable emulator"
        }
        app = compose.activity.application as AtlasApplication
        runBlocking { withTimeout(15_000) { app.ready.first { it } } }
        samplesBefore = samples()
        previous = app.appearanceSettings.state.value
        previousRaw = preferences.all.toMap()
        previousNight = app.getSystemService(UiModeManager::class.java).nightMode
        check(previousNight in listOf(UiModeManager.MODE_NIGHT_AUTO, UiModeManager.MODE_NIGHT_NO, UiModeManager.MODE_NIGHT_YES))
        previousFont = shell("settings get system font_scale")
        check(previousFont == "null" || previousFont.matches(Regex("[0-9]+(?:\\.[0-9]+)?")))
        previousResolvedFontScale = compose.activity.resources.configuration.fontScale
        previousSize = overrideSize()
        previousSecure = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        previousOrientation = compose.activity.requestedOrientation
        assertTrue("production activity must protect its real user window", previousSecure)
        restoreArmed = true
        setNight(false)
        shell("settings put system font_scale 1.0")
        runBlocking {
            assertTrue(app.appearanceSettings.setStyle(InterfaceStyle.CLASSIC))
            assertTrue(app.appearanceSettings.setWallpaper(AquaWallpaper.KOTOHA))
            assertTrue(app.appearanceSettings.setReduceTransparency(false))
        }
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[AtlasViewModel::class.java] }
        AquaArtworkLoader.clear()
        val name = "Aqua native ${UUID.randomUUID()}"
        compose.runOnIdle { vm.saveConnection(name, "https://aqua.fixture.invalid/v1", "atlas-aqua-fixture", "aqua-synthetic-fixture-key") }
        compose.waitUntil(15_000) { !vm.state.value.saving && vm.state.value.connections.any { it.name == name } }
        fixture = vm.state.value.connections.first { it.name == name }
        compose.runOnIdle { vm.selectConnection(checkNotNull(fixture).id) }
        compose.waitUntil(15_000) { vm.state.value.selectedId == fixture?.id && !vm.state.value.connectionsLoading }
    }

    @After fun restoreEverything() {
        if (!restoreArmed) return
        if (!bodyPassed) runCatching {
            instrumentation.runOnMainSync { compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                output("failure-${reportName.removeSuffix(".json")}.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
            }
        }
        val failures = mutableListOf<Throwable>()
        fun restore(block: () -> Unit) { runCatching(block).exceptionOrNull()?.let(failures::add) }
        restore { shell(if (previousSize == null) "wm size reset" else "wm size $previousSize") }
        // Restore the effective configuration before its raw setting. On API29,
        // deleting a changed font_scale can persist the default back as "1.0".
        // Let that configuration update finish, then restore an originally absent key.
        restore {
            shell("settings put system font_scale ${if (previousFont == "null") previousResolvedFontScale else previousFont}")
            compose.waitUntil(15_000) {
                kotlin.math.abs(compose.activity.resources.configuration.fontScale - previousResolvedFontScale) < 0.001f
            }
        }
        restore { shell("cmd uimode night " + when (previousNight) {
            UiModeManager.MODE_NIGHT_AUTO -> "auto"
            UiModeManager.MODE_NIGHT_YES -> "yes"
            else -> "no"
        }) }
        restore { compose.runOnIdle { compose.activity.requestedOrientation = previousOrientation } }
        restore {
            compose.waitForIdle()
            if (previousFont == "null") shell("settings delete system font_scale")
        }
        restore { runBlocking {
            assertTrue(app.appearanceSettings.setStyle(previous.style))
            assertTrue(app.appearanceSettings.setWallpaper(previous.wallpaper))
            assertTrue(app.appearanceSettings.setReduceTransparency(previous.reduceTransparency))
        } }
        restore { restoreRawPreferences() }
        restore { compose.runOnIdle {
            compose.activity.window.setFlags(if (previousSecure) WindowManager.LayoutParams.FLAG_SECURE else 0,
                WindowManager.LayoutParams.FLAG_SECURE)
        } }
        restore { assertEquals(samplesBefore, samples()) }
        restore { assertEquals(previousFont, shell("settings get system font_scale")) }
        restore { assertEquals(previousSize, overrideSize()) }
        restore { assertEquals(previousNight, app.getSystemService(UiModeManager::class.java).nightMode) }
        restore { assertEquals(previousRaw, preferences.all) }
        restore {
            val secure = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            assertEquals(previousSecure, secure)
            writeJson(reportName, JSONObject().put("result", if (bodyPassed && failures.isEmpty()) "PASS" else "FAIL")
                .put("pictures", pictures).put("pid", android.os.Process.myPid())
                .put("system_settings_restored", failures.isEmpty())
                .put("preferences_restored", preferences.all == previousRaw)
                .put("production_secure_before", previousSecure).put("secure_restored", secure == previousSecure)
                .put("night_before", previousNight).put("night_after", app.getSystemService(UiModeManager::class.java).nightMode)
                .put("font_before", previousFont).put("font_after", shell("settings get system font_scale"))
                .put("resolved_font_before", previousResolvedFontScale)
                .put("resolved_font_after", compose.activity.resources.configuration.fontScale)
                .put("no_inference", samples() == samplesBefore).put("cache_bytes", AquaArtworkLoader.cachedBytes()))
        }
        restore { compose.activityRule.scenario.close() }
        restore { cleanOwnRows() }
        AquaArtworkLoader.clear()
        assertTrue("Aqua QA restoration failed: ${failures.map { it.javaClass.simpleName }}", failures.isEmpty())
    }

    @Test fun actualFourArtworkButtonsKeepScopeChatAndProviderDrafts() = qaCase {
        reportName = "aqua-ui-light.json"
        val scopeBefore = scope()
        val draft = "切換四張背景仍保留的草稿"
        compose.onNodeWithTag("playground_composer_text").performTextReplacement(draft)
        capture("01-classic-light.png")
        assertEquals(0, AquaArtworkLoader.cachedBytes())
        changeStyle(InterfaceStyle.AQUA)
        for ((index, art) in AquaWallpaper.entries.withIndex()) {
            changeArtwork(art)
            assertEquals(scopeBefore, scope())
            compose.onNodeWithTag("playground_composer_text").assertTextContains(draft)
            capture(listOf("02-kotoha-light.png", "03-elaina-light.png", "04-kotoha-chibi-light.png", "05-elaina-chibi-light.png")[index])
        }
        openSettings()
        compose.onNodeWithTag("interface_style_aqua").performScrollTo()
        capture("06-settings-four-choices-light.png")
        closeSettings()
        tab(R.string.tab_providers); capture("07-providers-light.png")
        tab(R.string.tab_models); capture("08-models-light.png")
        tab(R.string.tab_providers)
        compose.onNodeWithText(compose.activity.getString(R.string.onboarding_quick)).performScrollTo().performClick()
        val providerDraft = "https://appearance-draft.fixture.invalid/v1"
        val field = hasSetTextAction() and hasText(compose.activity.getString(R.string.label_base_url))
        compose.onNode(field).performScrollTo().performTextReplacement(providerDraft)
        changeStyle(InterfaceStyle.CLASSIC)
        changeStyle(InterfaceStyle.AQUA)
        compose.onNode(field).assertTextContains(providerDraft)
        capture("09-provider-form-retained-light.png")
        changeStyle(InterfaceStyle.CLASSIC)
        compose.onNode(field).assertTextContains(providerDraft)
        capture("10-classic-draft-retained-light.png")
        assertEquals(scopeBefore, scope())
        assertEquals(samplesBefore, samples())
    }

    @Test fun actualDarkRecreationNarrowLargeTypeAndOpaqueRemainReadable() = qaCase {
        reportName = "aqua-ui-dark-large.json"
        changeStyle(InterfaceStyle.AQUA)
        changeArtwork(AquaWallpaper.ELAINA_CHIBI)
        setNight(true)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { tagExists("aqua_artwork_elaina-chibi") }
        assertEquals(AppearanceSettings(InterfaceStyle.AQUA, AquaWallpaper.ELAINA_CHIBI),
            AppearanceSettingsStore(app).state.value)
        capture("11-relaunch-retained-dark.png")
        val density = compose.activity.resources.displayMetrics.density
        shell("wm size ${(320 * density).roundToInt()}x${(740 * density).roundToInt()}")
        shell("settings put system font_scale 2.0")
        compose.waitUntil(15_000) {
            compose.activity.resources.configuration.screenWidthDp == 320 &&
                compose.activity.resources.configuration.fontScale >= 1.99f
        }
        compose.waitUntil(15_000) { tagExists("aqua_artwork_elaina-chibi") }
        capture("12-playground-320-large-dark.png")
        assertComposerControlsReachable()
        openSettings()
        compose.onNodeWithTag("interface_style_aqua").performScrollTo()
        capture("13-settings-320-large-dark.png")
        compose.onNodeWithTag("appearance_reduce_transparency").performScrollTo().performClick()
        compose.waitUntil(15_000) { !vm.appearanceSaving.value && app.appearanceSettings.state.value.reduceTransparency }
        closeSettings()
        compose.waitUntil(15_000) { tagExists("aqua_opaque_background") && !tagExists("aqua_artwork_elaina-chibi") }
        capture("14-reduced-transparency-dark.png")
        openSettings()
        compose.onNodeWithTag("appearance_reduce_transparency").performScrollTo().performClick()
        compose.waitUntil(15_000) { !vm.appearanceSaving.value && !app.appearanceSettings.state.value.reduceTransparency }
        closeSettings()
        compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(15_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        compose.waitUntil(15_000) { tagExists("aqua_artwork_elaina-chibi") }
        capture("15-landscape-large-dark.png")
        assertComposerControlsReachable()
    }

    private fun qaCase(body: () -> Unit) { body(); bodyPassed = true }

    private fun assertComposerControlsReachable() {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("PDF").performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
                assertTrue(get(layouts))
            }
        assertTrue("The attachment label must stay complete at 200% font scale: " +
            layouts.joinToString { "size=${it.size}, paragraphWidth=${it.multiParagraph.width}, paragraphHeight=${it.multiParagraph.height}, lines=${it.lineCount}, widthOverflow=${it.didOverflowWidth}, heightOverflow=${it.didOverflowHeight}, font=${it.layoutInput.style.fontSize}, scale=${it.layoutInput.density.fontScale}, constraints=${it.layoutInput.constraints}" },
            layouts.isNotEmpty() && layouts.all { it.lineCount == 1 && !it.hasVisualOverflow })
        compose.onNodeWithTag("playground_composer_controls").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("playground_composer_text").performScrollTo().assertIsDisplayed()
    }

    private fun tagExists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun openSettings() {
        if (!tagExists("appearance_settings")) compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_settings)).performClick()
        compose.waitUntil(15_000) { tagExists("appearance_settings") }
    }
    private fun closeSettings() {
        compose.onNodeWithTag("settings_close").performClick()
        compose.waitUntil(15_000) { !tagExists("appearance_settings") }
    }
    private fun changeStyle(style: InterfaceStyle) {
        openSettings()
        compose.onNodeWithTag("interface_style_${style.savedValue}").performScrollTo().performClick()
        compose.waitUntil(15_000) { !vm.appearanceSaving.value && app.appearanceSettings.state.value.style == style }
        closeSettings()
        compose.waitUntil(15_000) { tagExists("interface_${style.savedValue}") }
    }
    private fun changeArtwork(art: AquaWallpaper) {
        openSettings()
        compose.onNodeWithTag("aqua_wallpaper_${art.savedValue}").performScrollTo().performClick()
        compose.waitUntil(15_000) { !vm.appearanceSaving.value && app.appearanceSettings.state.value.wallpaper == art }
        closeSettings()
        compose.waitUntil(15_000) { tagExists("aqua_artwork_${art.savedValue}") }
    }
    private fun tab(label: Int) {
        val text = compose.activity.getString(label)
        compose.onNode((hasText(text) or hasContentDescription(text)) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
    }
    private fun setNight(dark: Boolean) {
        shell("cmd uimode night ${if (dark) "yes" else "no"}")
        compose.waitUntil(15_000) {
            (compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) == dark
        }
    }
    private fun scope(): List<String?> = listOf(vm.state.value.selectedId, vm.selectedAccountId.value,
        vm.selectedCredentialId.value, vm.selectedGroup.value)
    private fun samples(): Long = app.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM samples").use { it.moveToFirst(); it.getLong(0) }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    private fun overrideSize(): String? = Regex("Override size: ([0-9]+x[0-9]+)").find(shell("wm size"))?.groupValues?.get(1)
    private fun output(name: String): File {
        val root = checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        return File(root, "aqua-ui/$name").also { check(it.parentFile?.mkdirs() == true || it.parentFile?.isDirectory == true) }
    }
    private fun capture(name: String) {
        compose.runOnIdle {
            val activity = compose.activity
            val focus = activity.currentFocus
            activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(
                focus?.windowToken ?: activity.window.decorView.windowToken, 0,
            )
            focus?.clearFocus()
        }
        compose.waitForIdle()
        // UiAutomation documents a nullable result even after a submitted frame.
        // A new activity's display surface can still be unavailable. Wait for a
        // real, non-null display capture within the existing 15-second budget;
        // every attempt follows a new hardware frame, with no renderer fallback.
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 15_000
        var attempts = 0
        var captured: Bitmap? = null
        while (captured == null && SystemClock.elapsedRealtime() < deadline) {
            val committed = CountDownLatch(1)
            compose.runOnIdle {
                val activity = compose.activity
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                val decor = activity.window.decorView
                check(decor.isHardwareAccelerated)
                decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                decor.invalidate()
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            assertTrue("The actual window frame must commit before screenshot capture",
                remaining > 0 && committed.await(remaining, TimeUnit.MILLISECONDS))
            instrumentation.waitForIdleSync()
            compose.waitForIdle()
            if (SystemClock.elapsedRealtime() >= deadline) break
            attempts++
            captured = instrumentation.uiAutomation.takeScreenshot()
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        val bitmap = checkNotNull(captured) {
            "Actual display capture unavailable for $name after $attempts attempts in ${elapsed}ms (15000ms budget)"
        }
        assertTrue("Display capture exceeded its original readiness budget", elapsed < 15_000)
        val file = output(name)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        pictures.put(JSONObject().put("file", name).put("width", bitmap.width).put("height", bitmap.height)
            .put("capture_method", "UiAutomation.takeScreenshot")
            .put("capture_attempts", attempts).put("capture_null_results", attempts - 1)
            .put("capture_ms", elapsed).put("capture_budget_ms", 15_000)
            .put("screen_width_dp", compose.activity.resources.configuration.screenWidthDp)
            .put("font_scale", compose.activity.resources.configuration.fontScale)
            .put("dark", compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }))
        bitmap.recycle()
    }
    private fun writeJson(name: String, value: JSONObject) { output(name).writeText(value.toString(2) + "\n") }
    private fun restoreRawPreferences() {
        val editor = preferences.edit().clear()
        for ((key, value) in previousRaw) when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
        assertTrue(editor.commit())
    }
    private fun cleanOwnRows() {
        val own = fixture ?: return
        app.vault.remove(own.credentialId)
        val db = app.database.openHelper.writableDatabase
        val args = arrayOf<Any?>(own.providerId)
        db.beginTransaction()
        try {
            for (table in listOf("checkins", "my_models", "samples", "provider_configurations"))
                db.execSQL("DELETE FROM $table WHERE providerId=?", args)
            for (table in listOf("credential_availability", "credentials"))
                db.execSQL("DELETE FROM $table WHERE accountId IN (SELECT accountId FROM accounts WHERE providerId=?)", args)
            for (table in listOf("offerings", "endpoints", "accounts", "providers"))
                db.execSQL("DELETE FROM $table WHERE providerId=?", args)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
