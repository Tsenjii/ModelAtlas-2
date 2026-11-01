package dev.modelatlas.app.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class InterfaceStyle(val savedValue: String) {
    CLASSIC("classic"), AQUA("aqua");

    companion object {
        fun fromSaved(value: Any?): InterfaceStyle = entries.firstOrNull { it.savedValue == value } ?: CLASSIC
    }
}

/** Only these bundled files are selectable; saved preferences never become paths or URLs. */
enum class AquaWallpaper(val savedValue: String, val file: String, val sha256: String) {
    KOTOHA("kotoha", "aqua-kotoha-v1.jpg", "e606900526c42aa479d930eec612cb829a4828d4e51c3ededf28fc7c3ddde282"),
    ELAINA("elaina", "aqua-elaina-v1.jpg", "7897163cc6f609ffc29161e95b2fefc6d56619b6bda2daafdef808e2bc8f07c6"),
    KOTOHA_CHIBI("kotoha-chibi", "aqua-kotoha-chibi-v1.webp", "81953319738d7b14009fc06768e968fe1321ad6d9322d49f3717ab4626bbe373"),
    ELAINA_CHIBI("elaina-chibi", "aqua-elaina-chibi-v1.webp", "3f46afcba742572cf80b9cffae8af6d4d66648dd3bce35843d1a621d02aa81bc");

    val isChibi: Boolean get() = this == KOTOHA_CHIBI || this == ELAINA_CHIBI
    val assetPath: String get() = "wallpapers/$file"

    companion object {
        fun fromSaved(value: Any?): AquaWallpaper = entries.firstOrNull { it.savedValue == value } ?: KOTOHA
    }
}

data class AppearanceSettings(
    val style: InterfaceStyle = InterfaceStyle.CLASSIC,
    val wallpaper: AquaWallpaper = AquaWallpaper.KOTOHA,
    val reduceTransparency: Boolean = false,
) {
    val showsArtwork: Boolean get() = style == InterfaceStyle.AQUA && !reduceTransparency
}

/** App-owned, nonsecret, device-local choices. No database, sync, research or inference caller. */
class AppearanceSettingsStore(context: Context, preferencesName: String = PREFS_NAME) {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val writes = Mutex()
    private val mutableState = MutableStateFlow(readInitialState())
    val state: StateFlow<AppearanceSettings> = mutableState.asStateFlow()

    suspend fun setStyle(value: InterfaceStyle): Boolean = update { it.copy(style = value) }
    suspend fun setWallpaper(value: AquaWallpaper): Boolean = update { it.copy(wallpaper = value) }
    suspend fun setReduceTransparency(value: Boolean): Boolean = update { it.copy(reduceTransparency = value) }

    private suspend fun update(transform: (AppearanceSettings) -> AppearanceSettings): Boolean =
        withContext(Dispatchers.IO) {
            writes.withLock {
                currentCoroutineContext().ensureActive()
                val next = transform(mutableState.value)
                try {
                    // A failed Android commit may still change its in-memory cache.
                    // Rewrite all owned keys from our last acknowledged state.
                    val editor = preferences.edit()
                        .putString(STYLE_KEY, next.style.savedValue)
                        .putString(WALLPAPER_KEY, next.wallpaper.savedValue)
                        .putBoolean(OPAQUE_KEY, next.reduceTransparency)
                    currentCoroutineContext().ensureActive()
                    if (editor.commit()) {
                        mutableState.value = next
                        true
                    } else false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            }
        }

    private fun readInitialState(): AppearanceSettings = try {
        val saved = preferences.all
        AppearanceSettings(
            style = InterfaceStyle.fromSaved(saved[STYLE_KEY]),
            wallpaper = AquaWallpaper.fromSaved(saved[WALLPAPER_KEY]),
            reduceTransparency = saved[OPAQUE_KEY] == true,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AppearanceSettings()
    }

    companion object {
        const val PREFS_NAME = "interface_appearance"
        const val STYLE_KEY = "interface_style"
        const val WALLPAPER_KEY = "aqua_wallpaper"
        const val OPAQUE_KEY = "reduce_transparency"
    }
}
