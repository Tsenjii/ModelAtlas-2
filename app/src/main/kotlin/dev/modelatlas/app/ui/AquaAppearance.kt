package dev.modelatlas.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.modelatlas.app.R
import dev.modelatlas.app.data.AppearanceSettings
import dev.modelatlas.app.data.AquaWallpaper
import dev.modelatlas.app.data.InterfaceStyle
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shared backdrop/preview decode; classic never calls this loader. */
internal object AquaArtworkLoader {
    const val MAX_CACHE_BYTES = 16 * 1024 * 1024
    private val decoding = Mutex()
    private val cache = object : LruCache<AquaWallpaper, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: AquaWallpaper, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun load(context: Context, artwork: AquaWallpaper): Bitmap = withContext(Dispatchers.IO) {
        decoding.withLock {
            cache.get(artwork)?.let { return@withLock it }
            currentCoroutineContext().ensureActive()
            val raw = context.assets.open(artwork.assetPath).use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(raw)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            check(digest == artwork.sha256) { "Bundled artwork checksum mismatch" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            val expected = when (artwork) {
                AquaWallpaper.KOTOHA -> 917 to 1280
                AquaWallpaper.ELAINA -> 1259 to 2048
                else -> 1254 to 1254
            }
            check(bounds.outWidth == expected.first && bounds.outHeight == expected.second)
            val bitmap = checkNotNull(BitmapFactory.decodeByteArray(raw, 0, raw.size,
                BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }))
            currentCoroutineContext().ensureActive()
            cache.put(artwork, bitmap)
            bitmap
        }
    }

    fun cachedBytes(): Int = cache.size()
    fun clear() = cache.evictAll()
}

private data class AquaImageResult(
    val bitmap: Bitmap? = null,
    val failed: Boolean = false,
    val artwork: AquaWallpaper? = null,
)

@Composable
private fun aquaImage(artwork: AquaWallpaper, enabled: Boolean): State<AquaImageResult> {
    val context = LocalContext.current.applicationContext
    return produceState(AquaImageResult(), artwork, enabled, context) {
        value = AquaImageResult()
        if (enabled) {
            value = try {
                AquaImageResult(AquaArtworkLoader.load(context, artwork), artwork = artwork)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AquaImageResult(failed = true, artwork = artwork)
            }
        }
    }
}

@Composable
internal fun AquaBackdrop(appearance: AppearanceSettings, modifier: Modifier = Modifier) {
    if (appearance.style != InterfaceStyle.AQUA) return
    val image by aquaImage(appearance.wallpaper, appearance.showsArtwork)
    val dark = isSystemInDarkTheme()
    val base = MaterialTheme.colorScheme.background
    BoxWithConstraints(modifier.clipToBounds().testTag(
        if (appearance.reduceTransparency) "aqua_opaque_background" else "aqua_background",
    )) {
        image.bitmap?.takeIf { image.artwork == appearance.wallpaper }?.let { bitmap ->
            // Keep the complete character above the composer, beside the welcome panel.
            // Fit preserves the supplied artwork; neither faces nor transparent edges are cropped.
            val decoration = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 88.dp, end = 8.dp)
                .width((maxWidth * 0.49f).coerceAtMost(320.dp))
                .height((maxHeight * 0.48f).coerceAtMost(360.dp))
            Image(
                bitmap.asImageBitmap(), contentDescription = null,
                modifier = decoration.testTag("aqua_artwork_${appearance.wallpaper.savedValue}"),
                contentScale = ContentScale.Fit,
                alignment = Alignment.TopEnd,
                alpha = if (dark) 0.64f else 0.84f,
            )
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                listOf(base.copy(alpha = 0.48f), base.copy(alpha = 0.06f)),
            )))
        }
    }
}

@Composable
internal fun AppearanceSettingsSection(
    state: AppearanceSettings,
    saving: Boolean,
    saveFailed: Boolean,
    onStyle: (InterfaceStyle) -> Unit,
    onWallpaper: (AquaWallpaper) -> Unit,
    onReduceTransparency: (Boolean) -> Unit,
) {
    val image by aquaImage(state.wallpaper, state.showsArtwork)
    Column(Modifier.fillMaxWidth().testTag("appearance_settings"), verticalArrangement = Arrangement.spacedBy(AtlasSpace.md)) {
        AtlasSectionHeading(stringResource(R.string.appearance_heading), stringResource(R.string.appearance_local_hint))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (LocalDensity.current.fontScale > 1.3f || maxWidth < 240.dp) 1 else 2
            Column(verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                InterfaceStyle.entries.chunked(columns).forEach { choices ->
                    Row(horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                        choices.forEach { style ->
                            AppearanceChoice(
                                stringResource(if (style == InterfaceStyle.CLASSIC) R.string.interface_classic else R.string.interface_aqua),
                                state.style == style, !saving, { onStyle(style) },
                                Modifier.weight(1f).testTag("interface_style_${style.savedValue}"),
                            )
                        }
                    }
                }
                Text(stringResource(R.string.appearance_wallpaper), style = MaterialTheme.typography.titleSmall)
                AquaWallpaper.entries.chunked(columns).forEach { choices ->
                    Row(horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
                        choices.forEach { artwork ->
                            AppearanceChoice(
                                artworkLabel(artwork), state.wallpaper == artwork, !saving, { onWallpaper(artwork) },
                                Modifier.weight(1f).testTag("aqua_wallpaper_${artwork.savedValue}"),
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(112.dp).clip(AtlasRadius.field)
            .background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
            val bitmap = image.bitmap?.takeIf { image.artwork == state.wallpaper }
            if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = artworkLabel(state.wallpaper),
                modifier = Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
            else Text(stringResource(when {
                state.reduceTransparency -> R.string.appearance_opaque_hint
                state.style == InterfaceStyle.AQUA && !image.failed -> R.string.appearance_loading
                else -> R.string.appearance_preview_hint
            }),
                Modifier.padding(AtlasSpace.md), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = AtlasSpace.touch)
            .testTag("appearance_reduce_transparency")
            .toggleable(state.reduceTransparency, enabled = !saving, role = Role.Switch, onValueChange = onReduceTransparency),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
            Text(stringResource(R.string.appearance_reduce_transparency), Modifier.weight(1f))
            Switch(checked = state.reduceTransparency, onCheckedChange = null, enabled = !saving)
        }
        if (saveFailed || image.failed && image.artwork == state.wallpaper) Text(
            stringResource(if (saveFailed) R.string.appearance_save_failed else R.string.appearance_image_failed),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun AppearanceChoice(label: String, chosen: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = AtlasSpace.touch).semantics { selected = chosen },
        shape = AtlasRadius.field,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )) {
        Text(label, textAlign = TextAlign.Center)
    }
}

@Composable
private fun artworkLabel(artwork: AquaWallpaper): String = stringResource(when (artwork) {
    AquaWallpaper.KOTOHA -> R.string.aqua_kotoha_original
    AquaWallpaper.ELAINA -> R.string.aqua_elaina_original
    AquaWallpaper.KOTOHA_CHIBI -> R.string.aqua_kotoha_chibi
    AquaWallpaper.ELAINA_CHIBI -> R.string.aqua_elaina_chibi
})
