package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MODEL-005 收藏／watchlist／recently used 持久化（Room v9）。
 * 以 local Room 假 id；不連線、不帶 secret、不當產品資料。
 */
@RunWith(AndroidJUnit4::class)
class FavoriteStoreTest {
    private lateinit var db: AtlasDatabase
    private lateinit var store: FavoriteStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = FavoriteStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun favoritePersistsAndNormalizesKey() = runBlocking {
        assertFalse(store.isFavorite("GPT-4o"))
        assertTrue(store.setFavorite("GPT_4o", true, 1000L))
        assertTrue(store.isFavorite("gpt-4o"))
        assertTrue(store.isFavorite("GPT-4o"))
        assertEquals(setOf("gpt-4o"), store.favoriteIds())
        assertEquals(setOf("gpt-4o"), store.favorites.first())
        // 重複設定冪等，不增列。
        assertTrue(store.setFavorite("gpt.4o", true, 2000L))
        assertEquals(1, db.favoriteDao().favorites().size)
        assertFalse(store.setFavorite("gpt-4o", false, 3000L))
        assertEquals(emptySet<String>(), store.favoriteIds())
    }

    @Test
    fun watchlistIsSeparateFromFavorites() = runBlocking {
        store.setFavorite("a", true, 1L)
        store.setWatchlist("b", true, note = "observe", nowMillis = 2L)
        assertEquals(setOf("a"), store.favoriteIds())
        assertEquals(setOf("b"), store.watchlistIds())
        assertFalse(store.isFavorite("b"))
        assertFalse(store.isWatched("a"))
        store.toggleWatchlist("b", 3L)
        assertEquals(emptySet<String>(), store.watchlistIds())
    }

    @Test
    fun recordUseIncrementsCountAndOrdersByRecency() = runBlocking {
        store.recordUse("a", 100L)
        store.recordUse("a", 200L)
        store.recordUse("b", 150L)
        val a = store.recentOf("a")!!
        assertEquals("a", a.canonicalModelId)
        assertEquals(200L, a.lastUsedAt)
        assertEquals(2, a.useCount)
        assertEquals(listOf("a", "b"), store.recentIds())
        assertEquals(listOf("a", "b"), store.recent.first())
        assertEquals(2, store.clearRecent())
        assertNull(store.recentOf("a"))
    }

    @Test
    fun blankIdIsRejected() = runBlocking {
        var threw = false
        try {
            store.setFavorite("   ", true, 1L)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
