package dev.modelatlas.app.ui

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.data.AquaWallpaper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AquaArtworkAssetsTest {
    @After fun releaseArtworkCache() { AquaArtworkLoader.clear() }

    @Test fun actualBundledImagesDecodeWithOriginalDimensionsAlphaAndBoundedCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AquaArtworkLoader.clear()
        for (art in AquaWallpaper.entries) {
            // The production loader verifies the exact original bytes before decode.
            val image = AquaArtworkLoader.load(context, art)
            val expected = when (art) {
                AquaWallpaper.KOTOHA -> 917 to 1280
                AquaWallpaper.ELAINA -> 1259 to 2048
                else -> 1254 to 1254
            }
            assertEquals(expected.first, image.width)
            assertEquals(expected.second, image.height)
            if (art.isChibi) {
                assertTrue(image.hasAlpha())
                assertEquals(0, Color.alpha(image.getPixel(0, 0)))
            }
            assertTrue(AquaArtworkLoader.cachedBytes() <= AquaArtworkLoader.MAX_CACHE_BYTES)
            assertTrue(AquaArtworkLoader.load(context, art) === image)
        }
    }
}
