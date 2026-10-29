package dev.modelatlas.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceSettingsStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "aqua-store-test-${UUID.randomUUID()}"
    private val preferences get() = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @After fun cleanOwnPreferences() { assertTrue(preferences.edit().clear().commit()) }

    @Test fun freshChoicesStayClassicWithoutWritingDefaults() {
        assertEquals(AppearanceSettings(), AppearanceSettingsStore(context, name).state.value)
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun malformedSavedTypesCannotEnableStyleOrOpenAnArbitraryPath() {
        preferences.edit().putBoolean(AppearanceSettingsStore.STYLE_KEY, true)
            .putString(AppearanceSettingsStore.WALLPAPER_KEY, "https://untrusted.invalid/picture.png")
            .putString(AppearanceSettingsStore.OPAQUE_KEY, "true").commit()
        val before = preferences.all.toMap()
        val store = AppearanceSettingsStore(context, name)
        assertEquals(AppearanceSettings(), store.state.value)
        assertEquals(before, preferences.all)
        assertEquals("wallpapers/aqua-kotoha-v1.jpg", store.state.value.wallpaper.assetPath)
    }

    @Test fun successfulChoicesSurviveStoreReconstructionAndOpaqueKeepsTheArtwork() = runBlocking {
        val store = AppearanceSettingsStore(context, name)
        assertTrue(store.setStyle(InterfaceStyle.AQUA))
        assertTrue(store.setWallpaper(AquaWallpaper.ELAINA_CHIBI))
        assertTrue(store.setReduceTransparency(true))
        val saved = AppearanceSettings(InterfaceStyle.AQUA, AquaWallpaper.ELAINA_CHIBI, true)
        assertEquals(saved, AppearanceSettingsStore(context, name).state.value)
        assertFalse(saved.showsArtwork)
        assertTrue(store.setReduceTransparency(false))
        assertEquals(AquaWallpaper.ELAINA_CHIBI, store.state.value.wallpaper)
        assertTrue(store.state.value.showsArtwork)
    }

    @Test fun concurrentIndependentEditsKeepEveryConfirmedChoice() = runBlocking {
        val store = AppearanceSettingsStore(context, name)
        listOf(
            async(Dispatchers.Default) { store.setStyle(InterfaceStyle.AQUA) },
            async(Dispatchers.Default) { store.setWallpaper(AquaWallpaper.KOTOHA_CHIBI) },
            async(Dispatchers.Default) { store.setReduceTransparency(true) },
        ).awaitAll().forEach { assertTrue(it) }
        assertEquals(AppearanceSettings(InterfaceStyle.AQUA, AquaWallpaper.KOTOHA_CHIBI, true),
            AppearanceSettingsStore(context, name).state.value)
    }

    @Test fun failedCommitRetainsConfirmedStateAndNextEditRepairsTheFailedCache() = runBlocking {
        val failOnce = AtomicBoolean(true)
        val wrapped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(file: String, mode: Int): SharedPreferences {
                val real = super.getSharedPreferences(file, mode)
                return object : SharedPreferences by real {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = real.edit()
                        lateinit var proxy: SharedPreferences.Editor
                        proxy = Proxy.newProxyInstance(AppearanceSettingsStoreTest::class.java.classLoader,
                            arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                            val result = method.invoke(editor, *(args ?: emptyArray()))
                            when {
                                method.name == "commit" && failOnce.getAndSet(false) -> false
                                result is SharedPreferences.Editor -> proxy
                                else -> result
                            }
                        } as SharedPreferences.Editor
                        return proxy
                    }
                }
            }
        }
        val store = AppearanceSettingsStore(wrapped, name)
        assertFalse(store.setStyle(InterfaceStyle.AQUA))
        assertEquals("aqua", preferences.getString(AppearanceSettingsStore.STYLE_KEY, null))
        assertEquals(AppearanceSettings(), store.state.value)
        assertTrue(store.setWallpaper(AquaWallpaper.ELAINA))
        assertEquals(AppearanceSettings(wallpaper = AquaWallpaper.ELAINA),
            AppearanceSettingsStore(context, name).state.value)
    }
}
