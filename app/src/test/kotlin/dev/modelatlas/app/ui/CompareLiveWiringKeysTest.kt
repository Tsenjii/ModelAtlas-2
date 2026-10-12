package dev.modelatlas.app.ui

import dev.modelatlas.app.data.CompareKeySelection
import dev.modelatlas.core.inference.InferenceEngine
import dev.modelatlas.core.inference.InferenceTarget
import dev.modelatlas.core.inference.InferenceTransport
import dev.modelatlas.core.inference.Outcome
import dev.modelatlas.core.inference.PerformanceSample
import dev.modelatlas.core.inference.PromptMessage
import dev.modelatlas.core.inference.StreamEvent
import dev.modelatlas.core.inference.TelemetrySink
import dev.modelatlas.core.inference.Usage
import dev.modelatlas.core.models.CompareLiveOffering
import dev.modelatlas.core.models.CompareLiveRequest
import dev.modelatlas.core.models.CompareLiveRowResult
import dev.modelatlas.core.models.CompareLiveRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PK-29 Compare Live scoped row-key vectors (JVM, pure Kotlin, no Compose).
 *
 * TRACEABILITY: PK-29 (duplicate LazyColumn key crash when one offeringId renders
 * under two scoped comparisonIds). Production fix at HEAD is inline
 * `items(rows, key = { it.comparisonId })` (CompareLiveUi.kt:302); NO compareRowKey()
 * helper exists, so T1 asserts key identity via wiring/rows (comparisonId defaults
 * to offeringId), mirroring the CompareLiveWiringTest harness/fixtures.
 */
class CompareLiveWiringKeysTest {

    private fun wiringFixtureInput(
        offeringId: String,
        providerId: String = "p1",
        modelId: String = "m1",
        credentialId: String = "c1",
        comparisonId: String? = null,
    ) = CompareLiveWiring.CompareOfferingInput(
        offeringId = offeringId,
        providerId = providerId,
        accountId = "a1",
        credentialId = credentialId,
        endpointId = "e1",
        endpointUrl = "https://api.example.com/v1",
        providerModelId = modelId,
        priceState = "UNKNOWN",
        quotaState = "UNKNOWN",
        comparisonId = comparisonId,
    )

    private fun wiringFixtureRow(
        success: Boolean,
        offeringId: String = "o1",
        comparisonId: String = offeringId,
        modelId: String = "m1",
    ) = CompareLiveRowResult(
        offeringId = offeringId,
        comparisonId = comparisonId,
        providerId = "p1",
        endpointId = "e1",
        providerModelId = modelId,
        canonicalId = "canon",
        success = success,
        outcome = if (success) Outcome.SUCCESS else Outcome.RATE_LIMIT,
    )

    // T1 legacy identity: defaulted comparisonId == offeringId (pre-fix key analog).
    @Test
    fun legacyDefaultedComparisonIdEqualsOfferingId() {
        val row = CompareLiveRowResult(
            offeringId = "o1",
            providerId = "p1",
            endpointId = "e1",
            providerModelId = "m1",
            canonicalId = "canon",
            success = true,
            outcome = Outcome.SUCCESS,
        )
        assertEquals("o1", row.comparisonId)
        assertEquals(row.offeringId, row.comparisonId)
    }

    // T1 batch analog: defaulted rows carry all-distinct offeringIds as list keys.
    @Test
    fun legacyBatchDefaultedKeysAreDistinctOfferingIds() {
        val rows = listOf(
            wiringFixtureRow(success = true, offeringId = "o1"),
            wiringFixtureRow(success = false, offeringId = "o2"),
        )
        assertEquals(rows.map { it.offeringId }, rows.map { it.comparisonId })
        assertEquals(rows.size, rows.map { it.comparisonId }.toSet().size)
    }

    // T2 same offering under two credentials: distinct scoped comparisonIds via
    // production scopedComparisonId + pure buildRequest (selections overload
    // buildRequestFromDb needs a Room DB, so it is covered by device-side
    // CompareMultiProviderWiringTest, not here).
    @Test
    fun sameOfferingTwoCredentialsYieldsDistinctScopedKeys() {
        val selC1 = CompareKeySelection(providerId = "p1", accountId = "a1", credentialId = "c1")
        val selC2 = CompareKeySelection(providerId = "p1", accountId = "a1", credentialId = "c2")
        val scopedA = CompareLiveWiring.scopedComparisonId("o1", selC1)
        val scopedB = CompareLiveWiring.scopedComparisonId("o1", selC2)
        assertTrue(scopedA.isNotBlank())
        assertTrue(scopedB.isNotBlank())
        assertNotEquals(scopedA, scopedB)

        val req = CompareLiveWiring.buildRequest(
            "canon",
            listOf(
                wiringFixtureInput("o1", credentialId = "c1", comparisonId = scopedA),
                wiringFixtureInput("o1", credentialId = "c2", comparisonId = scopedB),
            ),
        )
        assertNotNull(req)
        assertEquals(listOf(scopedA, scopedB), req!!.offerings.map { it.comparisonId })

        // Rows keep the repeated offeringId but carry distinct list keys.
        val rows = listOf(
            wiringFixtureRow(success = true, offeringId = "o1", comparisonId = scopedA),
            wiringFixtureRow(success = false, offeringId = "o1", comparisonId = scopedB),
        )
        assertEquals(listOf("o1", "o1"), rows.map { it.offeringId })
        assertEquals(2, rows.map { it.comparisonId }.toSet().size)
    }

    // T3 scopedComparisonId length-prefix encoding vectors.
    @Test
    fun scopedComparisonIdEncodingVectors() {
        val base = CompareKeySelection(providerId = "p1", accountId = "a1", credentialId = "c1")
        // group=null shape: "2:o1" + "2:p1" + "2:a1" + "2:c1" + "-1:".
        assertEquals(
            "2:o12:p12:a12:c1-1:",
            CompareLiveWiring.scopedComparisonId("o1", base),
        )
        // group="" normalizes to null (identical string).
        assertEquals(
            CompareLiveWiring.scopedComparisonId("o1", base),
            CompareLiveWiring.scopedComparisonId("o1", base.copy(group = "")),
        )
        // group="g1" suffixes "2:g1"; differs from null and from "g".
        val g1 = CompareLiveWiring.scopedComparisonId("o1", base.copy(group = "g1"))
        val g = CompareLiveWiring.scopedComparisonId("o1", base.copy(group = "g"))
        assertTrue(g1.endsWith("2:g1"))
        assertNotEquals(CompareLiveWiring.scopedComparisonId("o1", base), g1)
        assertNotEquals(g, g1)
        // c1 vs c2 distinct.
        assertNotEquals(
            CompareLiveWiring.scopedComparisonId("o1", base),
            CompareLiveWiring.scopedComparisonId(
                "o1",
                base.copy(credentialId = "c2"),
            ),
        )
        // null credential ("-1:") never collides with empty credential ("0:").
        val nullCred = CompareLiveWiring.scopedComparisonId("o1", base.copy(credentialId = null))
        val emptyCred = CompareLiveWiring.scopedComparisonId("o1", base.copy(credentialId = ""))
        assertNotEquals(nullCred, emptyCred)
        assertTrue(nullCred.endsWith("-1:-1:"))
        assertTrue(emptyCred.endsWith("0:-1:"))
    }

    // T4 acceptance seam: same offeringId twice with distinct comparisonIds kept in
    // order; duplicate comparisonId rejects the whole batch.
    @Test
    fun buildRequestAcceptsDistinctScopedIdsKeepsOrderRejectsDuplicates() {
        val req = CompareLiveWiring.buildRequest(
            "canon",
            listOf(
                wiringFixtureInput("o1", credentialId = "c1", comparisonId = "2:o12:p12:a12:c1-1:"),
                wiringFixtureInput("o1", credentialId = "c2", comparisonId = "2:o12:p12:a12:c2-1:"),
            ),
        )
        assertNotNull(req)
        assertEquals(
            listOf("2:o12:p12:a12:c1-1:", "2:o12:p12:a12:c2-1:"),
            req!!.offerings.map { it.comparisonId },
        )
        assertEquals(listOf("o1", "o1"), req.offerings.map { it.offeringId })

        assertNull(
            CompareLiveWiring.buildRequest(
                "canon",
                listOf(
                    wiringFixtureInput("o1", credentialId = "c1", comparisonId = "dup"),
                    wiringFixtureInput("o2", credentialId = "c2", comparisonId = "dup"),
                ),
            ),
        )
    }

    // T5 runner invariant (core CompareLiveRunner.require): duplicate offeringId
    // allowed with distinct comparisonIds; duplicate comparisonId throws.
    // Existing core keeper: CompareMultiCredentialTest
    // comparisonIdIsOnlyUniquenessKeyAndConcurrencyBudgetTwoPreservesInputOrder.
    @Test
    fun runnerRequiresDistinctComparisonIdsButAllowsRepeatedOfferingId() {
        val engine = stubEngine()
        var rejected: Throwable? = null
        runBlocking {
            try {
                CompareLiveRunner.run(
                    engine,
                    CompareLiveRequest(
                        canonicalId = "canon",
                        offerings = listOf(
                            stubOffering("o1", "dup-key"),
                            stubOffering("o1", "dup-key"),
                        ),
                        messages = listOf(PromptMessage("user", "ping")),
                    ),
                )
            } catch (e: IllegalArgumentException) {
                rejected = e
            }
        }
        assertTrue(rejected is IllegalArgumentException)
        assertTrue(rejected!!.message.orEmpty().contains("comparisonIds must be distinct"))

        val rows = runBlocking {
            CompareLiveRunner.run(
                engine,
                CompareLiveRequest(
                    canonicalId = "canon",
                    offerings = listOf(
                        stubOffering("o1", "key-c1"),
                        stubOffering("o1", "key-c2"),
                    ),
                    messages = listOf(PromptMessage("user", "ping")),
                ),
            )
        }
        assertEquals(2, rows.size)
        assertEquals(listOf("o1", "o1"), rows.map { it.offeringId })
        assertEquals(listOf("key-c1", "key-c2"), rows.map { it.comparisonId })
        assertTrue(rows.all { it.success })
    }

    private fun stubOffering(offeringId: String, comparisonId: String) = CompareLiveOffering(
        offeringId = offeringId,
        comparisonId = comparisonId,
        providerId = "p1",
        accountId = "a1",
        credentialId = "c1",
        endpointId = "e1",
        endpointUrl = "https://api.example.com/v1",
        providerModelId = "m1",
    )

    private fun stubEngine(): InferenceEngine {
        val transport = object : InferenceTransport {
            override fun stream(
                target: InferenceTarget,
                messages: List<PromptMessage>,
            ): Flow<StreamEvent> = flow {
                emit(StreamEvent.RequestSent(epochMillis = 5_000L, monotonicMillis = 1_000L))
                emit(StreamEvent.Headers(200, emptyMap(), 1_100L))
                emit(StreamEvent.Delta("Hi", observedMonotonicMs = 1_400L))
                emit(
                    StreamEvent.TokenUsage(
                        Usage(inputTokens = 8L, outputTokens = 12L, totalTokens = 20L),
                    ),
                )
                emit(StreamEvent.Finished)
            }
        }
        val sink = object : TelemetrySink {
            override suspend fun save(sample: PerformanceSample) = Unit
        }
        return InferenceEngine(transport, sink)
    }
}
