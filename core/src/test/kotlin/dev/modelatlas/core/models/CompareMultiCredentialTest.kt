package dev.modelatlas.core.models

import dev.modelatlas.core.inference.InferenceAuthField
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.inference.InferenceFailure
import dev.modelatlas.core.inference.InferenceTarget
import dev.modelatlas.core.inference.InferenceTransport
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.PromptMessage
import dev.modelatlas.core.inference.StreamEvent
import dev.modelatlas.core.inference.TelemetrySink
import dev.modelatlas.core.inference.TelemetrySource
import dev.modelatlas.core.inference.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MODEL-008 跨 provider／多 key Compare Live — core 新 slice focused 測試
 * （CORE-TEST worker；唯一新檔，不重複既有 [CompareLiveTest] 基線案例）。
 *
 * TRACEABILITY：MODEL-008（PRODUCT_SPEC §8 Compare Live、§3 統一 telemetry、
 * §6 多 key／selected-context、§10 provenance／FREE 判定）。
 *
 * 對準 docs/TRACEABILITY.md:188「MODEL-008 Compare Live — ACCEPTED」（綠 `73c1c9f`／
 * `36826988202`）之 core 契約＋main integration correction（custom-only attribution）：
 * - 同一實際 offeringId／endpoint 兩把 key／group 以不同 comparisonId 各自 tuple，
 *   指標／sampleId 不混；comparisonId 是唯一鍵（不認 offeringId 唯一）。
 * - 新版 decoy（其他 source／credential／更新 timestamp）不能污染本次結果。
 * - transport 偽造 sampleId 由 engine-owned id 覆寫。
 * - feed 缺／錯 credential／錯 source／錯 outcome 一律 Unknown，不補 0／free。
 * - custom-only key：target.credentialId null＋authFields 安全 refs，telemetry 的
 *   sample.safeCredentialId 必須是 selectedCredentialId anchor（engine 經
 *   InferenceTarget.telemetryCredentialId 歸屬），不是 blank——否則兩把
 *   custom-only key 會在永久 telemetry 混 scope；anonymous NONE 維持 legacy blank。
 * - 同 offering 兩 key 401 隔離；beforeOffering 取消傳播整批且無合成 telemetry；
 *   併發上限 2＋輸入順序保留。
 *
 * 全部使用真 InferenceEngine＋recording TelemetrySink／Feed＋fake transport；
 * 無真 API／真 key／PAT（fixture 只在測試）。驗證以 GitHub Actions 為準，
 * 本 worker 不跑 Gradle／CI、不假稱 CI 證據。
 */
class CompareMultiCredentialTest {

    private val prompt = listOf(PromptMessage("user", "ping-compare-multi-credential"))

    private fun successScript(
        firstDeltaAt: Long,
        secondDeltaAt: Long,
        input: Long,
        output: Long,
    ): List<StreamEvent> = listOf(
        StreamEvent.RequestSent(epochMillis = 5_000L, monotonicMillis = 1_000L),
        StreamEvent.Headers(200, emptyMap(), 1_100L),
        StreamEvent.Delta("Hi", observedMonotonicMs = firstDeltaAt),
        StreamEvent.Delta(" there", observedMonotonicMs = secondDeltaAt),
        StreamEvent.TokenUsage(
            Usage(inputTokens = input, outputTokens = output, totalTokens = input + output),
        ),
        StreamEvent.Finished,
    )

    private sealed interface Behavior {
        data class Script(val events: List<StreamEvent>) : Behavior
        data class Fail(val before: List<StreamEvent>, val error: Throwable) : Behavior
    }

    /** 以 credential 錨點路由（同一 endpoint 多 key／custom-only anchor 各自行為）。 */
    private inner class CredRoutingTransport(
        private val behaviors: Map<String, Behavior>,
        private val keyOf: (InferenceTarget) -> String = {
            it.telemetryCredentialId ?: it.credentialId ?: "anon-${it.group ?: "none"}"
        },
        private val sharedLog: MutableList<String>? = null,
    ) : InferenceTransport {
        val receivedTargets = mutableListOf<InferenceTarget>()
        val receivedAuthFields = mutableListOf<List<InferenceAuthField>>()
        val receivedMessages = mutableListOf<List<PromptMessage>>()
        val callLog = mutableListOf<String>()

        override fun stream(target: InferenceTarget, messages: List<PromptMessage>) = flow {
            val key = keyOf(target)
            val entry = "stream:$key"
            callLog += entry
            sharedLog?.add(entry)
            receivedTargets += target
            receivedAuthFields += target.authFields
            receivedMessages += messages
            when (val behavior = behaviors[key] ?: Behavior.Script(emptyList())) {
                is Behavior.Script -> behavior.events.forEach { emit(it) }
                is Behavior.Fail -> {
                    behavior.before.forEach { emit(it) }
                    throw behavior.error
                }
            }
        }
    }

    /** 記錄在途併發峰值：驗證 semaphore 上限 2。 */
    private inner class CountingTransport(
        private val events: Map<String, List<StreamEvent>>,
    ) : InferenceTransport {
        var active = 0
        var maxActive = 0
        val calls = mutableListOf<String>()
        val receivedTargets = mutableListOf<InferenceTarget>()

        override fun stream(target: InferenceTarget, messages: List<PromptMessage>) = flow {
            val key = target.telemetryCredentialId ?: target.credentialId ?: "anon"
            calls += key
            receivedTargets += target
            active++
            if (active > maxActive) maxActive = active
            try {
                events[key].orEmpty().forEachIndexed { index, event ->
                    if (index == 0) yield()
                    emit(event)
                }
            } finally {
                active--
            }
        }
    }

    private class RecordingFeed : TelemetrySink, CompareLiveSampleFeed {
        val saves = mutableListOf<PerformanceSample>()

        override suspend fun save(sample: PerformanceSample) {
            saves += sample
        }

        override fun finalSamples(): List<PerformanceSample> = saves.toList()
    }

    /**
     * 模擬 feed 缺／錯 scope：exact id 查到 real final 後依 lookup 轉換或回 null
     * （對照 production：Room sink＋唯讀 feed view；engine sink 是 backing 本體）。
     */
    private class SelectiveFeed(
        private val backing: RecordingFeed,
        private val lookup: (PerformanceSample) -> PerformanceSample?,
    ) : CompareLiveSampleFeed {
        override fun finalSamples(): List<PerformanceSample> = backing.finalSamples()

        override fun finalFor(sampleId: String): PerformanceSample? {
            val final = backing.saves.firstOrNull { it.id == sampleId && it.completedAt != null }
                ?: return null
            return lookup(final)
        }
    }

    private fun engineOf(transport: InferenceTransport, feed: TelemetrySink): InferenceEngine =
        InferenceEngine(transport, feed)

    private fun keyedOffering(
        comparisonId: String,
        credentialId: String?,
        group: String?,
        offeringId: String = "offering-shared",
        priceState: String = "UNKNOWN",
        quotaState: String = "UNKNOWN",
        selectedCredentialId: String? = credentialId,
        authFields: List<InferenceAuthField> = emptyList(),
    ) = CompareLiveOffering(
        offeringId = offeringId,
        providerId = "provider-compare",
        accountId = "account-compare",
        credentialId = credentialId,
        endpointId = "endpoint-shared",
        endpointUrl = "https://api.fixture.invalid/shared/chat/completions",
        providerModelId = "model-shared",
        group = group,
        priceState = priceState,
        quotaState = quotaState,
        comparisonId = comparisonId,
        selectedCredentialId = selectedCredentialId,
        authFields = authFields,
    )

    @Test
    fun sameOfferingTwoKeysYieldDistinctTupleResultsWithoutMetricMixing() = runTest {
        val feed = RecordingFeed()
        val transport = CredRoutingTransport(
            mapOf(
                "safe-key-1" to Behavior.Script(successScript(1_400L, 1_500L, 8L, 12L)),
                "safe-key-2" to Behavior.Script(successScript(1_600L, 1_700L, 16L, 24L)),
            ),
        )
        val rows = CompareLiveRunner.run(
            engineOf(transport, feed),
            CompareLiveRequest(
                canonicalId = "canonical-compare",
                offerings = listOf(
                    keyedOffering("cmp-key-1", "safe-key-1", "free-group", priceState = "FREE_NOW"),
                    keyedOffering("cmp-key-2", "safe-key-2", "default-group", priceState = "PAID"),
                ),
                messages = prompt,
            ),
            feed,
        )

        assertEquals(2, rows.size)
        // 同一實際 offering／endpoint，兩把 key／group 各自 scoped comparisonId tuple。
        assertEquals(listOf("offering-shared", "offering-shared"), rows.map { it.offeringId })
        assertEquals(listOf("cmp-key-1", "cmp-key-2"), rows.map { it.comparisonId })
        assertEquals(listOf("endpoint-shared", "endpoint-shared"), rows.map { it.endpointId })
        assertEquals(listOf("safe-key-1", "safe-key-2"), rows.map { it.credentialId })
        assertEquals(listOf("free-group", "default-group"), rows.map { it.group })
        assertTrue(rows.all { it.accountId == "account-compare" })
        // 共用小 prompt＋預設 64 token limit 對每一 target 一致；canonical 逐列標記。
        assertEquals(listOf(prompt, prompt), transport.receivedMessages)
        assertEquals(listOf(64, 64), transport.receivedTargets.map { it.maxOutputTokens })
        assertTrue(transport.receivedTargets.all { it.canonicalModelId == "canonical-compare" })
        // runner 傳 telemetryCredentialId=selectedCredentialId（keyed 即 actual Bearer
        // ref）；credentialId 維持 actual Bearer ref，不互相覆蓋。
        assertEquals(
            listOf("safe-key-1", "safe-key-2"),
            transport.receivedTargets.map { it.telemetryCredentialId },
        )
        assertEquals(
            listOf("safe-key-1", "safe-key-2"),
            transport.receivedTargets.map { it.credentialId },
        )
        // 指標不混：同 endpoint 兩 key 逐列各自 engine sample，不共用 latest。
        assertNotNull(rows[0].sampleId)
        assertNotNull(rows[1].sampleId)
        assertTrue(rows[0].sampleId != rows[1].sampleId)
        assertEquals(400L, rows[0].ttftMs)
        assertEquals(600L, rows[1].ttftMs)
        assertEquals(110.0, rows[0].tps!!, 1e-9)
        assertEquals(230.0, rows[1].tps!!, 1e-9)
        assertEquals(8L, rows[0].inputTokens)
        assertEquals(12L, rows[0].outputTokens)
        assertEquals(16L, rows[1].inputTokens)
        assertEquals(24L, rows[1].outputTokens)
        // source／scope：全部 COMPARE_LIVE，逐列 scope 對應 selected anchor／group／canonical。
        val key1Final = feed.saves.first { it.safeCredentialId == "safe-key-1" && it.completedAt != null }
        val key2Final = feed.saves.first { it.safeCredentialId == "safe-key-2" && it.completedAt != null }
        assertEquals(TelemetrySource.COMPARE_LIVE, key1Final.source)
        assertEquals(TelemetrySource.COMPARE_LIVE, key2Final.source)
        assertEquals(rows[0].sampleId, key1Final.id)
        assertEquals(rows[1].sampleId, key2Final.id)
        assertEquals("free-group", key1Final.group)
        assertEquals("default-group", key2Final.group)
        assertEquals("canonical-compare", key1Final.canonicalModelId)
        // priceState 逐列：FREE_NOW／PAID 不互相覆蓋。
        assertEquals("FREE_NOW", rows[0].priceState)
        assertEquals("PAID", rows[1].priceState)
        assertEquals("FREE_NOW", key1Final.priceState)
        assertEquals("PAID", key2Final.priceState)
        assertTrue(rows.all { it.success })
        // engine 不捏造 after snapshot。
        assertNull(rows[0].quotaSnapshotAfterId)
        assertNull(rows[1].quotaSnapshotAfterId)
    }

    @Test
    fun newerDecoyFromOtherSourceOrCredentialCannotPolluteThisRun() = runTest {
        val feed = RecordingFeed()
        val decoyIds = setOf("decoy-manual-1", "decoy-other-cred-1", "decoy-latest-1")
        fun seedDecoy(id: String, source: TelemetrySource, credential: String) {
            feed.saves += PerformanceSample(
                id = id,
                providerId = "provider-compare",
                accountId = "account-compare",
                safeCredentialId = credential,
                endpointId = "endpoint-shared",
                providerModelId = "model-shared",
                canonicalModelId = "canonical-compare",
                group = "free-group",
                operation = "CHAT_COMPLETIONS",
                source = source,
                requestStartedAt = 9_000_000_000_000L + id.length,
                completedAt = 9_000_000_000_001L + id.length,
                outcome = Outcome.SUCCESS,
                httpStatus = 200,
                ttftMs = 999_999L,
                tps = 999.0,
                usage = Usage(
                    inputTokens = 999_999L,
                    outputTokens = 999_999L,
                    totalTokens = 1_999_998L,
                ),
            )
        }
        seedDecoy("decoy-manual-1", TelemetrySource.MANUAL_TEST, "safe-key-1")
        seedDecoy("decoy-other-cred-1", TelemetrySource.COMPARE_LIVE, "safe-credential-other")
        seedDecoy("decoy-latest-1", TelemetrySource.COMPARE_LIVE, "safe-key-2")

        val transport = CredRoutingTransport(
            mapOf(
                "safe-key-1" to Behavior.Script(successScript(1_400L, 1_500L, 8L, 12L)),
                "safe-key-2" to Behavior.Script(successScript(1_600L, 1_700L, 16L, 24L)),
            ),
        )
        val rows = CompareLiveRunner.run(
            engineOf(transport, feed),
            CompareLiveRequest(
                canonicalId = "canonical-compare",
                offerings = listOf(
                    keyedOffering("cmp-key-1", "safe-key-1", "free-group"),
                    keyedOffering("cmp-key-2", "safe-key-2", "default-group"),
                ),
                messages = prompt,
            ),
            feed,
        )

        assertEquals(2, rows.size)
        assertTrue(rows.all { it.success })
        // 本次結果只吃本次 collect 的 engine-owned sampleId；decoy id 不出現。
        val sampleId0 = rows[0].sampleId!!
        val sampleId1 = rows[1].sampleId!!
        assertFalse(decoyIds.contains(sampleId0))
        assertFalse(decoyIds.contains(sampleId1))
        val engineSampleIds = feed.saves.filter { it.id !in decoyIds }.map { it.id }.toSet()
        assertTrue(engineSampleIds.contains(sampleId0))
        assertTrue(engineSampleIds.contains(sampleId1))
        // 指標是本次值，不是 decoy 的 999_999／999.0（decoy 是 triplet 最新，
        // runner 若用 legacy latestFinalFor 會拿到 decoy——被本斷言捕獲）。
        assertEquals(400L, rows[0].ttftMs)
        assertEquals(600L, rows[1].ttftMs)
        assertEquals(110.0, rows[0].tps!!, 1e-9)
        assertEquals(230.0, rows[1].tps!!, 1e-9)
        assertEquals(8L, rows[0].inputTokens)
        assertEquals(12L, rows[0].outputTokens)
        assertEquals(16L, rows[1].inputTokens)
        assertEquals(24L, rows[1].outputTokens)
        assertEquals(Outcome.SUCCESS, rows[0].outcome)
        assertEquals(Outcome.SUCCESS, rows[1].outcome)
        // 本次 engine samples 全部 COMPARE_LIVE；decoy（含 MANUAL_TEST／他 credential）
        // 永久保留不刪。
        assertTrue(
            feed.saves
                .filter { it.id !in decoyIds && it.completedAt != null }
                .all { it.source == TelemetrySource.COMPARE_LIVE },
        )
        assertTrue(feed.finalSamples().map { it.id }.containsAll(decoyIds))
    }

    @Test
    fun transportFakeSampleIdIsOverriddenByEngineOwnedId() = runTest {
        val feed = RecordingFeed()
        val spoofedScript = listOf(
            StreamEvent.RequestSent(
                epochMillis = 5_000L,
                monotonicMillis = 1_000L,
                sampleId = "spoofed-transport-id",
            ),
            StreamEvent.Headers(200, emptyMap(), 1_100L),
            StreamEvent.Delta("Hi", observedMonotonicMs = 1_400L),
            StreamEvent.Delta(" there", observedMonotonicMs = 1_500L),
            StreamEvent.TokenUsage(Usage(inputTokens = 8L, outputTokens = 12L, totalTokens = 20L)),
            StreamEvent.Finished,
        )
        val transport = CredRoutingTransport(
            mapOf("safe-key-1" to Behavior.Script(spoofedScript)),
        )
        val rows = CompareLiveRunner.run(
            engineOf(transport, feed),
            CompareLiveRequest(
                canonicalId = "canonical-compare",
                offerings = listOf(keyedOffering("cmp-key-1", "safe-key-1", "free-group")),
                messages = prompt,
            ),
            feed,
        )

        assertEquals(1, rows.size)
        val engineId = feed.saves
            .first { it.safeCredentialId == "safe-key-1" && it.completedAt != null }
            .id
        // runner 捕捉的是 engine 覆寫後的 sampleId，不是 transport 偽造 id；
        // 偽造 id 不進 telemetry。
        assertEquals(engineId, rows.single().sampleId)
        assertTrue(rows.single().sampleId != "spoofed-transport-id")
        assertFalse(feed.saves.any { it.id == "spoofed-transport-id" })
        // id 未混淆：matched final 被信任，指標正常浮出。
        assertEquals(400L, rows.single().ttftMs)
        assertEquals(110.0, rows.single().tps!!, 1e-9)
        assertEquals(8L, rows.single().inputTokens)
        assertEquals(Outcome.SUCCESS, rows.single().outcome)
    }

    @Test
    fun feedMissingOrMismatchedScopeYieldsUnknownMetricsNeverZeroOrFree() = runTest {
        val feed = RecordingFeed()
        val selective = SelectiveFeed(feed, lookup = { final ->
            when (final.safeCredentialId) {
                "safe-key-1" -> null
                "safe-key-2" -> final.copy(safeCredentialId = "safe-credential-other")
                "safe-key-3" -> final.copy(source = TelemetrySource.PLAYGROUND)
                "safe-key-4" -> final.copy(outcome = Outcome.CANCELLED)
                else -> final
            }
        })
        val transport = CredRoutingTransport(
            mapOf(
                "safe-key-1" to Behavior.Script(successScript(1_400L, 1_500L, 8L, 12L)),
                "safe-key-2" to Behavior.Script(successScript(1_600L, 1_700L, 16L, 24L)),
                "safe-key-3" to Behavior.Script(successScript(1_800L, 1_900L, 24L, 36L)),
                "safe-key-4" to Behavior.Script(successScript(2_000L, 2_100L, 32L, 48L)),
            ),
        )
        val rows = CompareLiveRunner.run(
            engineOf(transport, feed),
            CompareLiveRequest(
                canonicalId = "canonical-compare",
                offerings = listOf(
                    keyedOffering("cmp-missing", "safe-key-1", "free-group"),
                    keyedOffering("cmp-wrong-cred", "safe-key-2", "default-group"),
                    keyedOffering("cmp-wrong-source", "safe-key-3", "vip-group"),
                    keyedOffering("cmp-wrong-outcome", "safe-key-4", "svip-group"),
                ),
                messages = prompt,
            ),
            selective,
        )

        assertEquals(4, rows.size)
        // feed 缺／任一 scope／source／outcome 不符：指標一律 Unknown（null），
        // 永不補 0／free；列 outcome 維持本次執行觀察（SUCCESS），不採 hostile sample。
        assertTrue(rows.all { it.success })
        assertTrue(rows.all { it.outcome == Outcome.SUCCESS })
        assertTrue(rows.all { it.ttftMs == null })
        assertTrue(rows.all { it.tps == null })
        assertTrue(rows.all { it.inputTokens == null })
        assertTrue(rows.all { it.outputTokens == null })
        assertTrue(rows.all { it.priceState == "UNKNOWN" })
        assertTrue(rows.all { it.quotaState == "UNKNOWN" })
        // row 仍帶本次 engine-owned sampleId（feed 沒服務它，但 id 是真關聯）。
        val keys = listOf("safe-key-1", "safe-key-2", "safe-key-3", "safe-key-4")
        val expectedTtft = mapOf(
            "safe-key-1" to 400L,
            "safe-key-2" to 600L,
            "safe-key-3" to 800L,
            "safe-key-4" to 1000L,
        )
        rows.forEachIndexed { index, row ->
            val real = feed.saves.first { it.safeCredentialId == keys[index] && it.completedAt != null }
            assertEquals(real.id, row.sampleId)
        }
        // backing 的真 final 逐列保留原 scope／指標（feed 未污染、未刪 telemetry）。
        keys.forEach { key ->
            val real = feed.saves.first { it.safeCredentialId == key && it.completedAt != null }
            assertEquals(TelemetrySource.COMPARE_LIVE, real.source)
            assertEquals(expectedTtft[key], real.ttftMs)
        }
    }

    @Test
    fun customOnlyKeysAttributedToSelectedAnchorRefWithoutBearerAuth() = runTest {
        val feed = RecordingFeed()
        val customAuth1 = listOf(
            InferenceAuthField(
                name = "X-Credential-Ref",
                credentialId = "safe-cred-ref-1",
                query = false,
                overrideConfirmed = true,
            ),
        )
        val customAuth2 = listOf(
            InferenceAuthField(
                name = "X-Credential-Ref",
                credentialId = "safe-cred-ref-2",
                query = false,
                overrideConfirmed = true,
            ),
        )
        val transport = CredRoutingTransport(
            mapOf(
                "anchor-custom-1" to Behavior.Script(successScript(1_400L, 1_500L, 8L, 12L)),
                "anchor-custom-2" to Behavior.Script(successScript(1_600L, 1_700L, 16L, 24L)),
                "anon-none" to Behavior.Script(successScript(1_800L, 1_900L, 24L, 36L)),
            ),
        )
        val rows = CompareLiveRunner.run(
            engineOf(transport, feed),
            CompareLiveRequest(
                canonicalId = "canonical-compare",
                offerings = listOf(
                    keyedOffering(
                        comparisonId = "cmp-custom-1",
                        credentialId = null,
                        group = "custom-group",
                        selectedCredentialId = "anchor-custom-1",
                        authFields = customAuth1,
                    ),
                    keyedOffering(
                        comparisonId = "cmp-custom-2",
                        credentialId = null,
                        group = "custom-group",
                        selectedCredentialId = "anchor-custom-2",
                        authFields = customAuth2,
                    ),
                    keyedOffering(
                        comparisonId = "cmp-anon",
                        credentialId = null,
                        group = null,
                        selectedCredentialId = null,
                    ),
                ),
                messages = prompt,
            ),
            feed,
        )

        assertEquals(3, rows.size)
        // anchor 不產生 Bearer：transport 只收 credentialId=null＋安全 authFields refs；
        // runner 傳 telemetryCredentialId=selectedCredentialId 供 telemetry 歸屬。
        assertTrue(transport.receivedTargets.all { it.credentialId == null })
        assertEquals(
            listOf("anchor-custom-1", "anchor-custom-2", null),
            transport.receivedTargets.map { it.telemetryCredentialId },
        )
        assertEquals(customAuth1, transport.receivedAuthFields[0])
        assertEquals(customAuth2, transport.receivedAuthFields[1])
        assertTrue(transport.receivedAuthFields[2].isEmpty())
        // row echo：selected anchor 逐列；anonymous NONE 維持 null（不捏造 credential）。
        assertEquals(listOf("anchor-custom-1", "anchor-custom-2", null), rows.map { it.credentialId })
        assertEquals("custom-group", rows[0].group)
        assertEquals("custom-group", rows[1].group)
        assertNull(rows[2].group)
        // 永久 telemetry 的 sample.safeCredentialId 必須是 selected anchor ref（不是
        // blank）：兩把 custom-only key 逐列分離；anonymous NONE 維持 legacy blank。
        val anchor1Final = feed.saves.first { it.safeCredentialId == "anchor-custom-1" && it.completedAt != null }
        val anchor2Final = feed.saves.first { it.safeCredentialId == "anchor-custom-2" && it.completedAt != null }
        val anonFinal = feed.saves.first { it.safeCredentialId.isEmpty() && it.completedAt != null }
        assertEquals(TelemetrySource.COMPARE_LIVE, anchor1Final.source)
        assertEquals(TelemetrySource.COMPARE_LIVE, anchor2Final.source)
        assertEquals(TelemetrySource.COMPARE_LIVE, anonFinal.source)
        assertEquals("anchor-custom-1", anchor1Final.safeCredentialId)
        assertEquals("anchor-custom-2", anchor2Final.safeCredentialId)
        assertEquals(rows[0].sampleId, anchor1Final.id)
        assertEquals(rows[1].sampleId, anchor2Final.id)
        assertEquals(rows[2].sampleId, anonFinal.id)
        // 指標依 anchor 歸屬逐列不混。
        assertEquals(400L, rows[0].ttftMs)
        assertEquals(600L, rows[1].ttftMs)
        assertEquals(800L, rows[2].ttftMs)
        assertEquals(8L, rows[0].inputTokens)
        assertEquals(16L, rows[1].inputTokens)
        assertEquals(24L, rows[2].inputTokens)
        assertTrue(rows.all { it.success })
        // group／custom 字樣不推導 FREE。
        assertTrue(rows.all { it.priceState == "UNKNOWN" })
    }

    @Test
    fun authFailureOnOneKeyIsIsolatedFromSiblingKeyOfSameOffering() = runTest {
        val feed = RecordingFeed()
        val transport = CredRoutingTransport(
            mapOf(
                "safe-key-1" to Behavior.Fail(
                    // Real transports always emit RequestSent first (engine overwrites its id);
                    // without it the failure path has no engine-owned sampleId to correlate.
                    listOf(
                        StreamEvent.RequestSent(epochMillis = 5_000L, monotonicMillis = 1_000L),
                        StreamEvent.Headers(401, mapOf("retry-after" to "0"), 1_120L),
                    ),
                    InferenceFailure(Outcome.AUTH, 401),
                ),
                "safe-key-2" to Behavior.Script(successScript(1_600L, 1_700L, 16L, 24L)),
            ),
        )
        val rows = CompareLiveRunner.run(
            engineOf(transport, feed),
            CompareLiveRequest(
                canonicalId = "canonical-compare",
                offerings = listOf(
                    keyedOffering("cmp-key-1", "safe-key-1", "free-group"),
                    keyedOffering("cmp-key-2", "safe-key-2", "default-group"),
                ),
                messages = prompt,
            ),
            feed,
        )

        assertEquals(2, rows.size)
        // 同一實際 offering 兩把 key：401 只隔離在該列，sibling key 不受影響。
        assertEquals("offering-shared", rows[0].offeringId)
        assertEquals("offering-shared", rows[1].offeringId)
        assertEquals("cmp-key-1", rows[0].comparisonId)
        assertEquals("cmp-key-2", rows[1].comparisonId)
        assertFalse(rows[0].success)
        assertEquals(Outcome.AUTH, rows[0].outcome)
        assertEquals(401, rows[0].httpStatus)
        assertEquals("AUTH", rows[0].errorKind)
        assertTrue(rows[1].success)
        assertEquals(Outcome.SUCCESS, rows[1].outcome)
        assertNull(rows[1].errorKind)
        // 無生成內容：TTFT unavailable＝null，不補 0。
        assertNull(rows[0].ttftMs)
        // 失敗 final 照常保存（source／scope／outcome／status 完整）。
        val failedFinal = feed.saves.first { it.safeCredentialId == "safe-key-1" && it.completedAt != null }
        assertEquals(Outcome.AUTH, failedFinal.outcome)
        assertEquals(TelemetrySource.COMPARE_LIVE, failedFinal.source)
        assertEquals(401, failedFinal.httpStatus)
        assertEquals("safe-key-1", failedFinal.safeCredentialId)
        assertEquals(rows[0].sampleId, failedFinal.id)
        assertTrue(rows[1].sampleId != rows[0].sampleId)
        // 兩把 key 的 row echo 逐列分離。
        assertEquals("safe-key-1", rows[0].credentialId)
        assertEquals("safe-key-2", rows[1].credentialId)
        assertEquals("free-group", rows[0].group)
        assertEquals("default-group", rows[1].group)
    }

    @Test
    fun beforeOfferingCancellationPropagatesAndLeavesNoSyntheticTelemetry() = runTest {
        val feed = RecordingFeed()
        val order = mutableListOf<String>()
        val transport = CredRoutingTransport(
            behaviors = mapOf(
                "safe-key-1" to Behavior.Script(successScript(1_400L, 1_500L, 8L, 12L)),
            ),
            sharedLog = order,
        )
        var thrown: Throwable? = null
        try {
            CompareLiveRunner.run(
                engineOf(transport, feed),
                CompareLiveRequest(
                    canonicalId = "canonical-compare",
                    offerings = listOf(
                        keyedOffering("cmp-key-1", "safe-key-1", "free-group"),
                        keyedOffering("cmp-key-2", "safe-key-2", "default-group"),
                    ),
                    messages = prompt,
                ),
                feed,
            ) { offering ->
                order += "before:${offering.comparisonId}"
                if (offering.comparisonId == "cmp-key-2") {
                    // 等第 1 列確實完成（final 已落 sink）後再取消：已完成列不污染。
                    var spins = 0
                    while (
                        feed.saves.none {
                            it.safeCredentialId == "safe-key-1" &&
                                it.completedAt != null &&
                                it.outcome == Outcome.SUCCESS
                        } &&
                        spins < 1_000
                    ) {
                        spins++
                        yield()
                    }
                    throw CancellationException("scope revalidation cancelled batch")
                }
            }
        } catch (e: CancellationException) {
            thrown = e
        }

        assertTrue(thrown is CancellationException)
        // callback 在 permit 內、engine 執行前呼叫（第 1 列 before 先於 stream）。
        assertTrue(order.indexOf("before:cmp-key-1") < order.indexOf("stream:safe-key-1"))
        assertTrue(order.contains("before:cmp-key-2"))
        // 重驗取消的那列不進 engine／transport，無合成 telemetry。
        assertTrue(order.none { it == "stream:safe-key-2" })
        assertTrue(feed.saves.none { it.safeCredentialId == "safe-key-2" })
        // 已完成列的 SUCCESS final 完整保留。
        assertTrue(
            feed.saves.any {
                it.safeCredentialId == "safe-key-1" &&
                    it.completedAt != null &&
                    it.outcome == Outcome.SUCCESS
            },
        )
    }

    @Test
    fun comparisonIdIsOnlyUniquenessKeyAndConcurrencyBudgetTwoPreservesInputOrder() = runTest {
        // comparisonId 唯一：重複即拒，且 require 先擋（零 engine 呼叫、零 telemetry）。
        val dupFeed = RecordingFeed()
        val dupTransport = CredRoutingTransport(emptyMap())
        var rejected: Throwable? = null
        try {
            CompareLiveRunner.run(
                engineOf(dupTransport, dupFeed),
                CompareLiveRequest(
                    canonicalId = "canonical-compare",
                    offerings = listOf(
                        keyedOffering("cmp-dup", "safe-key-1", "free-group"),
                        keyedOffering("cmp-dup", "safe-key-2", "default-group"),
                    ),
                    messages = prompt,
                ),
            )
        } catch (e: IllegalArgumentException) {
            rejected = e
        }
        assertTrue(rejected is IllegalArgumentException)
        assertTrue(dupTransport.receivedTargets.isEmpty())
        assertTrue(dupFeed.saves.isEmpty())

        // 同一 offeringId 兩把 key × 兩個 offering：comparisonId 是唯一鍵；
        // 併發上限 2、輸入順序保留、每列各自 engine-owned sampleId 與 anchor。
        val feed = RecordingFeed()
        val transport = CountingTransport(
            mapOf(
                "safe-key-1" to successScript(1_400L, 1_500L, 8L, 12L),
                "safe-key-2" to successScript(1_600L, 1_700L, 16L, 24L),
                "safe-key-3" to successScript(1_800L, 1_900L, 24L, 36L),
                "safe-key-4" to successScript(2_000L, 2_100L, 32L, 48L),
            ),
        )
        val rows = CompareLiveRunner.run(
            engineOf(transport, feed),
            CompareLiveRequest(
                canonicalId = "canonical-compare",
                offerings = listOf(
                    keyedOffering("cmp-p1-key1", "safe-key-1", "free-group", offeringId = "offering-shared"),
                    keyedOffering("cmp-p1-key2", "safe-key-2", "free-group", offeringId = "offering-shared"),
                    keyedOffering("cmp-p2-key1", "safe-key-3", "default-group", offeringId = "offering-shared-2"),
                    keyedOffering("cmp-p2-key2", "safe-key-4", "default-group", offeringId = "offering-shared-2"),
                ),
                messages = prompt,
            ),
            feed,
        )

        assertEquals(4, rows.size)
        assertEquals(
            listOf("cmp-p1-key1", "cmp-p1-key2", "cmp-p2-key1", "cmp-p2-key2"),
            rows.map { it.comparisonId },
        )
        assertEquals(
            listOf("offering-shared", "offering-shared", "offering-shared-2", "offering-shared-2"),
            rows.map { it.offeringId },
        )
        assertTrue(rows.all { it.success })
        assertEquals(2, transport.maxActive)
        assertEquals(4, transport.calls.size)
        assertEquals(4, rows.map { it.sampleId }.toSet().size)
        assertEquals(
            listOf("safe-key-1", "safe-key-2", "safe-key-3", "safe-key-4"),
            transport.receivedTargets.map { it.telemetryCredentialId },
        )
    }
}
