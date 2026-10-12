package dev.modelatlas.app

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.AtlasRepository
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.inference.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F29：recoverInterrupted 失敗不再讓 ready 永遠 false。
 *
 * TRACEABILITY：FND-006／PLAY-002 精神——RUNNING telemetry 不丟失、
 * 不靜默改寫為成功，失敗可觀察、可重試自癒。
 * Fixture 僅存於 androidTest，不當產品資料。
 */
@RunWith(AndroidJUnit4::class)
class AtlasStartupRecoveryTest {
    private lateinit var context: Context
    private val apps = mutableListOf<AtlasApplication>()
    private val open = mutableListOf<AtlasDatabase>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        apps.forEach { runCatching { it.applicationScope.cancel() } }
        apps.clear()
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    private fun newApp(): AtlasApplication {
        val app = AtlasApplication()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
        }.invoke(app, context)
        apps += app
        return app
    }

    private fun memoryDb(): AtlasDatabase =
        Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { open += it }

    private fun runningSample(id: String = "f29-s1") = PerformanceSample(
        id = id,
        providerId = "p1",
        accountId = "a1",
        safeCredentialId = "c1",
        endpointId = "e1",
        providerModelId = "m1",
        canonicalModelId = null,
        group = null,
        operation = "CHAT_COMPLETIONS",
        source = TelemetrySource.PLAYGROUND,
        requestStartedAt = 1000L,
        streaming = true,
        usage = Usage(inputTokens = 3L, outputTokens = 7L),
        outcome = Outcome.RUNNING,
        priceState = "UNKNOWN",
        quotaState = "UNKNOWN",
    )

    @Test
    fun successFirstTry_setsReadyWithoutDegraded() = runBlocking {
        val app = newApp()
        var calls = 0
        app.runStartupRecovery(recover = { calls++ }, maxAttempts = 3, delayMsForAttempt = { 0L })
        assertEquals(1, calls)
        assertEquals(1, app.recoveryAttempts.value)
        assertTrue(app.ready.value)
        assertFalse(app.initializationError.value)
        assertFalse(app.recoveryDegraded.value)
    }

    @Test
    fun retriesThenSucceeds_isBoundedNotInfinite() = runBlocking {
        val app = newApp()
        var calls = 0
        app.runStartupRecovery(
            recover = {
                calls++
                if (calls < 3) throw IllegalStateException("transient $calls")
            },
            maxAttempts = 3,
            delayMsForAttempt = { 0L },
        )
        assertEquals(3, calls)
        assertEquals(3, app.recoveryAttempts.value)
        assertTrue(app.ready.value)
        assertFalse(app.recoveryDegraded.value)
        assertFalse(app.initializationError.value)
    }

    @Test
    fun exhaustsRetries_degradedStartupStillReadyButObservable() = runBlocking {
        val app = newApp()
        var calls = 0
        app.runStartupRecovery(
            recover = {
                calls++
                throw IllegalStateException("persistent")
            },
            maxAttempts = 3,
            delayMsForAttempt = { 0L },
        )
        assertEquals(AtlasApplication.MAX_RECOVERY_ATTEMPTS, calls)
        assertEquals(AtlasApplication.MAX_RECOVERY_ATTEMPTS, app.recoveryAttempts.value)
        assertTrue("降級啟動：ready 不可永遠 false", app.ready.value)
        assertTrue(app.recoveryDegraded.value)
        assertTrue(app.initializationError.value)
    }

    @Test
    fun retryAfterDegraded_selfHealsOnSuccess() = runBlocking {
        val app = newApp()
        app.runStartupRecovery(
            recover = { throw IllegalStateException("boom") },
            maxAttempts = 2,
            delayMsForAttempt = { 0L },
        )
        assertTrue(app.recoveryDegraded.value)
        assertTrue(app.ready.value)
        val before = app.recoveryAttempts.value
        var calls = 0
        app.runStartupRecovery(recover = { calls++ }, maxAttempts = 3, delayMsForAttempt = { 0L })
        assertEquals(1, calls)
        assertEquals(before + 1, app.recoveryAttempts.value)
        assertTrue(app.ready.value)
        assertFalse(app.recoveryDegraded.value)
        assertFalse(app.initializationError.value)
    }

    @Test
    fun respectsMaxAttemptsBound() = runBlocking {
        val app = newApp()
        var calls = 0
        app.runStartupRecovery(
            recover = {
                calls++
                throw IllegalStateException("x")
            },
            maxAttempts = 2,
            delayMsForAttempt = { 0L },
        )
        assertEquals(2, calls)
        assertEquals(2, app.recoveryAttempts.value)
        assertTrue(app.recoveryDegraded.value)
    }

    @Test
    fun doesNotSwallowCancellation() = runBlocking {
        val app = newApp()
        var calls = 0
        try {
            app.runStartupRecovery(
                recover = {
                    calls++
                    throw CancellationException("cancel")
                },
                maxAttempts = 3,
                delayMsForAttempt = { 0L },
            )
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertEquals(1, calls)
        assertFalse(app.ready.value)
        assertFalse(app.recoveryDegraded.value)
    }

    @Test
    fun degradedFailure_preservesRunningTelemetryForRetry() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.save(runningSample())
        val app = newApp()
        app.runStartupRecovery(
            recover = { throw IllegalStateException("persistent db locked") },
            maxAttempts = 2,
            delayMsForAttempt = { 0L },
        )
        assertTrue(app.recoveryDegraded.value)
        assertTrue(app.ready.value)
        val row = db.sampleDao().getById("f29-s1")
        assertEquals("RUNNING", row?.outcome)
        assertNull(row?.completedAt)
        assertNull(row?.totalLatencyMs)
        assertEquals(1, db.sampleDao().countById("f29-s1"))
    }

    @Test
    fun successfulRecovery_marksInterruptedWithoutDeleting() = runBlocking {
        val db = memoryDb()
        val repo = AtlasRepository(db)
        repo.save(runningSample())
        val app = newApp()
        app.runStartupRecovery(
            recover = { repo.recoverInterrupted() },
            maxAttempts = 3,
            delayMsForAttempt = { 0L },
        )
        assertFalse(app.recoveryDegraded.value)
        assertTrue(app.ready.value)
        val row = db.sampleDao().getById("f29-s1")
        assertEquals("INTERRUPTED", row?.outcome)
        assertNull(row?.completedAt)
        assertEquals(1, db.sampleDao().countById("f29-s1"))
    }

    @Test
    fun retryRecoveryWith_runsFakeAndUpdatesState() = runBlocking {
        val app = newApp()
        var calls = 0
        val job = app.retryRecoveryWith(recover = { calls++ })
        job.join()
        assertEquals(1, calls)
        assertTrue(app.ready.value)
        assertFalse(app.recoveryDegraded.value)
        assertFalse(app.initializationError.value)
    }

    @Test
    fun recoveryPolicy_isFiniteAndBounded() = runBlocking {
        var calls = 0
        val result = StartupRecovery.run(
            recover = {
                calls++
                if (calls < 2) throw IllegalStateException("t")
            },
            maxAttempts = 3,
            delayMsForAttempt = { 0L },
        )
        assertTrue(result.success)
        assertEquals(2, result.attempts)
        assertEquals(2, calls)
        assertEquals(AtlasApplication.MAX_RECOVERY_ATTEMPTS, 3)
        assertTrue(AtlasApplication.recoveryDelayForAttempt(1) in 0L..500L)
        assertTrue(AtlasApplication.recoveryDelayForAttempt(2) in 0L..1000L)
    }
}
