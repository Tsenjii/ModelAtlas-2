package dev.modelatlas.app.ui

import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasUiState
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.data.CompareKeySelection
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.inference.InferenceTarget
import dev.modelatlas.core.inference.InferenceTransport
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PromptMessage
import dev.modelatlas.core.inference.StreamEvent
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.inference.Usage
import dev.modelatlas.core.network.EndpointPolicy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MODEL-008 cross-provider multi-key Compare Live production flow (device).
 *
 * TRACEABILITY: MODEL-008 (PRODUCT_SPEC §8 Compare Live, §6 selected-context,
 * §3 unified telemetry, §10 FREE). Only real caller path:
 * openModelDetail → explicit compare key selections → requestCompareLive →
 * CompareLiveRunner (compareTestEngine seam, real repository telemetry sink).
 *
 * - Two providers each own credential/account/group; no foreign-key fallback.
 * - Same provider two keys stay distinct (comparisonId), sharing actual offeringId.
 * - Same small prompt + token budget for every row; per-row results carry own scope.
 * - Leaving detail cancels the batch; no stale rows land on a new selection.
 * - Fixture secrets/rows only in this sourceSet; teardown deletes only own data.
 */
@RunWith(AndroidJUnit4::class)
class CompareMultiProviderFlowTest {

    private lateinit var app: AtlasApplication
    private lateinit var viewModel: AtlasViewModel
    private lateinit var viewModelStore: ViewModelStore
    private val transport = FakeCompareTransport()

    private val providerIds = mutableListOf<String>()
    private val credentialRefs = mutableListOf<String>()

    @Before
    fun setUp() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "this test must not run on the main thread" }
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication
        runBlocking { withTimeout(TIMEOUT_MS) { app.ready.first { it } } }
        transport.reset()
        transport.mode = FakeCompareTransport.Mode.COMPLETE
        viewModelStore = ViewModelStore()
        onMain {
            viewModel = ViewModelProvider(viewModelStore, CompareFlowViewModelFactory(app, transport))
                .get(AtlasViewModel::class.java)
        }
    }

    @After
    fun tearDown() {
        runCatching {
            onMain {
                viewModel.cancelCompareLive()
                viewModel.closeModelDetail()
            }
        }
        if (::viewModelStore.isInitialized) onMain { viewModelStore.clear() }
        for (ref in credentialRefs) runCatching { app.vault.remove(ref) }
        credentialRefs.clear()
        deleteOwnRows()
        providerIds.clear()
    }

    // ---- 1. 跨 provider 各自 key：同 prompt、同 budget、逐列 scope 分離 ----

    @Test
    fun crossProviderKeysRunSamePromptWithOwnScopesAndTelemetry() {
        val first = saveFixture("cmp-flow-a")
        val second = saveFixture("cmp-flow-b")
        val row = awaitDetailRow(first.providerId, second.providerId)
        openDetailAndAwaitChoices(row, minChoices = 2)

        selectOnly(first, second)
        onMain { viewModel.requestCompareLive(row) }
        val rows = await { viewModel.compareRows.first { it.size == 2 } }

        assertEquals(2, transport.callCount)
        val targets = listOf(transport.targetAt(0), transport.targetAt(1))
        assertEquals(
            setOf(first.credentialId, second.credentialId),
            targets.map { it.credentialId }.toSet(),
        )
        assertEquals(transport.messagesAt(0), transport.messagesAt(1))
        assertEquals(CompareLiveWiring.comparePrompt(), transport.messagesAt(0))
        assertEquals(
            listOf(CompareLiveWiring.COMPARE_MAX_OUTPUT_TOKENS, CompareLiveWiring.COMPARE_MAX_OUTPUT_TOKENS),
            targets.map { it.maxOutputTokens },
        )
        // No foreign key: each target keeps its own provider/account/endpoint.
        assertEquals(
            setOf(first.providerId, second.providerId),
            targets.map { it.providerId }.toSet(),
        )
        val byProvider = targets.associateBy { it.providerId }
        assertEquals(first.accountId, byProvider.getValue(first.providerId).accountId)
        assertEquals(second.accountId, byProvider.getValue(second.providerId).accountId)

        // Results echo per-key scope; comparisonIds distinct.
        assertEquals(2, rows.map { it.comparisonId }.toSet().size)
        val byCred = rows.associateBy { it.credentialId }
        assertEquals(first.providerId, byCred.getValue(first.credentialId).providerId)
        assertEquals(second.providerId, byCred.getValue(second.credentialId).providerId)
        assertTrue(rows.all { it.success })

        // Real telemetry per key scope, source COMPARE_LIVE.
        val finals = compareFinals()
        assertEquals(2, finals.size)
        assertEquals(
            setOf(first.credentialId, second.credentialId),
            finals.map { it["safeCredentialId"] }.toSet(),
        )
        finals.forEach {
            assertEquals(TelemetrySource.COMPARE_LIVE.name, it["source"])
            assertEquals(Outcome.SUCCESS.name, it["outcome"])
        }
        assertNoSecretInOwnRows()
    }

    // ---- 2. 同 provider 兩把 key：同一 offeringId，不同 comparisonId 與結果 ----

    @Test
    fun sameProviderTwoKeysStayDistinctUnderOneOffering() {
        val first = saveFixture("cmp-flow-same-a")
        val secondKey = attachSecondKey(first)
        val row = awaitDetailRow(first.providerId)
        openDetailAndAwaitChoices(row, minChoices = 2)

        val both = viewModel.compareChoices.value.map { it.selection }
            .filter { it.providerId == first.providerId }
        assertTrue("同一 provider 必須列出兩把 key", both.size >= 2)
        onMain {
            both.forEach { viewModel.setCompareSelection(it, true) }
        }
        await { viewModel.compareSelections.first { it.size >= 2 } }
        onMain { viewModel.requestCompareLive(row) }
        val rows = await { viewModel.compareRows.first { it.size >= 2 } }
            .filter { it.providerId == first.providerId }

        assertEquals(2, rows.size)
        assertEquals("offering 不得被改寫", 1, rows.map { it.offeringId }.toSet().size)
        assertEquals(2, rows.map { it.comparisonId }.toSet().size)
        assertEquals(setOf(first.credentialId, secondKey), rows.map { it.credentialId }.toSet())
        assertNoSecretInOwnRows()
    }

    // ---- 3. 離開 detail：取消在途比測，不留過期結果 ----

    @Test
    fun leavingDetailCancelsInFlightCompareWithoutStaleRows() {
        val first = saveFixture("cmp-flow-leave-a")
        val second = saveFixture("cmp-flow-leave-b")
        transport.mode = FakeCompareTransport.Mode.GATE
        val row = awaitDetailRow(first.providerId, second.providerId)
        openDetailAndAwaitChoices(row, minChoices = 2)

        selectOnly(first, second)
        onMain { viewModel.requestCompareLive(row) }
        await { transport.awaitStarted(0) }
        assertTrue(viewModel.compareRunning.value)

        onMain { viewModel.closeModelDetail() }
        transport.releaseAll()
        await { viewModel.compareRunning.first { !it } }
        assertTrue(viewModel.compareRows.value.isEmpty())
        assertNoSecretInOwnRows()
    }

    // ---- 4. 改選即取消舊批次：舊 key 結果不得掛到新選擇 ----

    @Test
    fun changingSelectionCancelsPreviousBatch() {
        val first = saveFixture("cmp-flow-reselect-a")
        val second = saveFixture("cmp-flow-reselect-b")
        transport.mode = FakeCompareTransport.Mode.GATE
        val row = awaitDetailRow(first.providerId, second.providerId)
        openDetailAndAwaitChoices(row, minChoices = 2)

        selectOnly(first, second)
        onMain { viewModel.requestCompareLive(row) }
        await { transport.awaitStarted(0) }

        // Drop one key mid-flight: batch must cancel, rows stay empty.
        val drop = viewModel.compareSelections.value.first { it.credentialId == second.credentialId }
        onMain { viewModel.setCompareSelection(drop, false) }
        transport.releaseAll()
        await { viewModel.compareRunning.first { !it } }
        assertTrue(viewModel.compareRows.value.isEmpty())
        assertNoSecretInOwnRows()
    }

    // ===== harness =====

    private data class Saved(
        val connectionId: String,
        val providerId: String,
        val accountId: String,
        val credentialId: String,
        val endpointId: String,
        val offeringId: String,
    )

    private fun saveFixture(label: String): Saved {
        val name = "Compare Flow $label ${UUID.randomUUID()}"
        onMain { viewModel.saveConnection(name, BASE_URL, MODEL_ID, FIXTURE_SECRET) }
        val item = awaitUi { state ->
            !state.saving && state.connections.any { it.name == name }
        }.connections.first { it.name == name }
        providerIds += item.providerId
        credentialRefs += item.credentialId
        val record = runBlocking {
            withTimeout(TIMEOUT_MS) {
                app.repository.connections.first { rows -> rows.any { it.id == item.id } }
                    .first { it.id == item.id }
            }
        }
        return Saved(
            record.id, record.providerId, record.accountId, record.credentialId,
            record.endpointId, record.offeringId,
        )
    }

    /** 同一 provider／account／offering 掛第二把 key（真 repository＋真 vault）。 */
    private fun attachSecondKey(first: Saved): String {
        val secondCred = "cmp-flow-cred-${UUID.randomUUID()}"
        val availabilityId = "cmp-flow-avail-${UUID.randomUUID()}"
        val origin = EndpointPolicy.origin(EndpointPolicy.validate(BASE_URL))
        runBlocking {
            app.vault.store(secondCred, origin, FIXTURE_SECRET_2)
            app.repository.attachCredential(first.offeringId, first.accountId, secondCred, "第二把 key", availabilityId)
        }
        credentialRefs += secondCred
        return secondCred
    }

    private fun awaitDetailRow(vararg providerIds: String): dev.modelatlas.app.ui.ModelRow {
        val rows = await {
            viewModel.allModelRows.first { all ->
                providerIds.all { id -> all.any { r -> r.providerId == id && r.providerModelId == MODEL_ID } }
            }
        }
        return rows.first { it.providerId == providerIds[0] && it.providerModelId == MODEL_ID }
    }

    private fun openDetailAndAwaitChoices(row: dev.modelatlas.app.ui.ModelRow, minChoices: Int) {
        onMain { viewModel.openModelDetail(row) }
        await { viewModel.compareChoices.first { it.size >= minChoices } }
    }

    private fun selectOnly(vararg saved: Saved) {
        onMain {
            viewModel.compareSelections.value.toList().forEach { viewModel.setCompareSelection(it, false) }
        }
        val wanted = saved.map { s ->
            viewModel.compareChoices.value.first {
                it.selection.providerId == s.providerId && it.selection.credentialId == s.credentialId
            }.selection
        }
        onMain {
            wanted.forEach { viewModel.setCompareSelection(it, true) }
        }
        await { viewModel.compareSelections.first { it.size == wanted.size } }
    }

    private fun compareFinals(): List<Map<String, String?>> = queryRows(
        "SELECT * FROM samples WHERE source = ? AND providerId IN (${providerIds.joinToString(",") { "?" }})" +
            " AND completedAt IS NOT NULL",
        arrayOf(TelemetrySource.COMPARE_LIVE.name) + providerIds.toTypedArray(),
    )

    private fun queryRows(sql: String, args: Array<String>): List<Map<String, String?>> {
        val out = mutableListOf<Map<String, String?>>()
        app.database.openHelper.readableDatabase.query(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                val row = LinkedHashMap<String, String?>(cursor.columnCount)
                for (index in 0 until cursor.columnCount) {
                    row[cursor.getColumnName(index)] = cursor.getString(index)
                }
                out += row
            }
        }
        return out
    }

    private fun assertNoSecretInOwnRows() {
        for (providerId in providerIds) {
            val rows = queryRows("SELECT * FROM samples WHERE providerId = ?", arrayOf(providerId)) +
                queryRows("SELECT * FROM evidence WHERE providerId = ?", arrayOf(providerId)) +
                queryRows("SELECT * FROM offerings WHERE providerId = ?", arrayOf(providerId))
            for (row in rows) {
                for ((column, value) in row) {
                    assertFalse(
                        "telemetry 不得含測試憑證值（欄位 $column）",
                        value?.contains(FIXTURE_SECRET) == true || value?.contains(FIXTURE_SECRET_2) == true,
                    )
                }
            }
        }
    }

    private fun deleteOwnRows() {
        if (providerIds.isEmpty()) return
        val list = providerIds.joinToString(",") { "?" }
        val ids = providerIds.toTypedArray<Any?>()
        val byAccount = "accountId IN (SELECT accountId FROM accounts WHERE providerId IN ($list))"
        val db = app.database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM samples WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM evidence WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM provider_notices WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM provider_events WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM checkins WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM provider_configurations WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM credential_availability WHERE $byAccount", ids)
            db.execSQL("DELETE FROM credentials WHERE $byAccount", ids)
            db.execSQL("DELETE FROM offerings WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM endpoints WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM accounts WHERE providerId IN ($list)", ids)
            db.execSQL("DELETE FROM providers WHERE providerId IN ($list)", ids)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
    }

    private fun <T> await(timeoutMillis: Long = TIMEOUT_MS, block: suspend () -> T): T {
        val waiter = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pending = waiter.async { withTimeout(timeoutMillis) { block() } }
            val deadline = SystemClock.uptimeMillis() + timeoutMillis
            while (!pending.isCompleted && SystemClock.uptimeMillis() < deadline) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    onMain { }
                }
            }
            return runBlocking { pending.await() }
        } finally {
            waiter.cancel()
        }
    }

    private fun awaitUi(predicate: (AtlasUiState) -> Boolean): AtlasUiState =
        await { viewModel.state.first(predicate) }

    private class FakeCompareTransport : InferenceTransport {
        enum class Mode { COMPLETE, GATE }

        @Volatile
        var mode: Mode = Mode.COMPLETE

        private val targets = CopyOnWriteArrayList<InferenceTarget>()
        private val prompts = CopyOnWriteArrayList<List<PromptMessage>>()
        private val started = Array(MAX_PROBES) { CompletableDeferred<Unit>() }
        private val gates = Array(MAX_PROBES) { CompletableDeferred<Unit>() }
        private val probeIndex = AtomicInteger(0)

        val callCount: Int get() = targets.size

        fun reset() {
            targets.clear()
            prompts.clear()
            probeIndex.set(0)
            for (slot in 0 until MAX_PROBES) {
                started[slot] = CompletableDeferred()
                gates[slot] = CompletableDeferred()
            }
        }

        fun targetAt(index: Int): InferenceTarget = targets[index]

        fun messagesAt(index: Int): List<PromptMessage> = prompts[index]

        suspend fun awaitStarted(index: Int) {
            started[index].await()
        }

        fun releaseAll() {
            for (slot in 0 until MAX_PROBES) gates[slot].complete(Unit)
        }

        override fun stream(target: InferenceTarget, messages: List<PromptMessage>): Flow<StreamEvent> = flow {
            val slot = probeIndex.getAndIncrement()
            require(slot < MAX_PROBES) { "fake transport exceeded $MAX_PROBES probes" }
            targets += target
            prompts += messages
            emit(StreamEvent.RequestSent(epochMillis = System.currentTimeMillis(), monotonicMillis = monotonic()))
            emit(
                StreamEvent.Headers(
                    status = 200,
                    rateLimits = mapOf("x-ratelimit-remaining-requests" to "9"),
                    observedMonotonicMs = monotonic(),
                ),
            )
            emit(StreamEvent.Delta("OK", observedMonotonicMs = monotonic()))
            emit(StreamEvent.TokenUsage(Usage(inputTokens = 3L, outputTokens = 2L, totalTokens = 5L)))
            started[slot].complete(Unit)
            when (mode) {
                Mode.COMPLETE -> emit(StreamEvent.Finished)
                Mode.GATE -> {
                    gates[slot].await()
                    emit(StreamEvent.Finished)
                }
            }
        }

        private fun monotonic(): Long = System.nanoTime() / 1_000_000

        private companion object {
            const val MAX_PROBES = 8
        }
    }

    private class CompareFlowViewModelFactory(
        private val app: AtlasApplication,
        private val transport: InferenceTransport,
    ) : ViewModelProvider.Factory {
        @Suppress("DEPRECATION")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            val engine = InferenceEngine(transport, app.repository)
            return AtlasViewModel(app, null, engine) as T
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val BASE_URL = "https://compare-flow.fixture.invalid/v1"
        const val MODEL_ID = "compare-flow-shared-model"
        const val FIXTURE_SECRET = "compare-flow-fixture-secret-never-persisted"
        const val FIXTURE_SECRET_2 = "compare-flow-fixture-second-secret-never-persisted"
    }
}
