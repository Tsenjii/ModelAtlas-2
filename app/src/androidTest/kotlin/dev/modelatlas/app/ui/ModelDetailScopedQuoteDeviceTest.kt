// FR-003 exact selected-scope primary quote, real-Room device leg — T2.
// Test vectors: the committed vector list in this file (T2 device leg; FR-003 ACCEPTED
// — docs/TRACEABILITY.md:19, backend green 2f0785a/37382168445 + renderer af675b1/
// 37413246045; audit row docs/CORRECTNESS_REPAIR_AUDIT.md:131). All [PORT P-*]
// production targets below are LANDED and committed; compile gate is CI (no local
// Gradle per AGENTS.md).
//
// PORT MARKERS (fr003 production — applied in the working tree by the parallel
// fr003-prod-apply owner; every consumer of the landed FR003 production below is
// marked [PORT ...] with the rebased-patch hunk it belongs to):
//   [PORT P-H2] ModelDetailLoader.SelectedOfferingScope (endpointId/operation, defaults
//       null = unknown scope; internal matchesPrimaryPriceScope/selectPrimaryQuote run
//       behind the public seams this file calls).
//   [PORT P-H3] 4-arg loadDetail(db, row, selected, selectedOfferingScope) overload;
//       legacy 3-arg delegates with null (unknown scope → fail-closed → quote null).
//   [PORT P-H4/H5] selectedQuote computation block + builder pass-through; this file
//       consumes ModelDetailState.selectedQuote ([PORT P-S1..S4], applied in the
//       working tree).
//   [PORT P-H6] public loadSelectedQuoteScoped device-test seam (called directly in the
//       empty-candidate test to pin early-null BEFORE the fact join).
//   [PORT P-H7] primaryPriceSqlPredicate / queryNewestPrimaryPriceRows /
//       readPrimaryPriceRow read shape — pinned by the SQL-shape assertions below
//       (SELECT-only, LIMIT present, first bind providerId, ≤400 binds, scope predicate
//       only in quote probes, fact join only on returned snapshot ids).
//   [PORT U3 — NOT referenced by this test; recorded for completeness] AtlasScreen.kt:304
//       3-arg → 4-arg loadDetail + re-key :296-297 offering scope.
//
// Vectors (preland-review §3 T2, unchanged): selectedQuote == exact selected row's latest
// point; foreign scopes remain in priceHistory; crowding (>500 foreign newer points) still
// yields the selected quote (Variant A, relevance BEFORE LIMIT); read-shape invariants
// (SELECT-only, LIMIT present, first bind providerId, ≤400 binds, empty-candidate path
// skips fact join).

package dev.modelatlas.app.ui

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.data.AccountEntity
import dev.modelatlas.app.data.AtlasDatabase
import dev.modelatlas.app.data.CanonicalModelEntity
import dev.modelatlas.app.data.CredentialEntity
import dev.modelatlas.app.data.EndpointEntity
import dev.modelatlas.app.data.EvidenceEntity
import dev.modelatlas.app.data.MyModelEntity
import dev.modelatlas.app.data.OfferingEntity
import dev.modelatlas.app.data.PriceEventEntity
import dev.modelatlas.app.data.PriceSnapshotEntity
import dev.modelatlas.app.data.ProviderEntity
import dev.modelatlas.app.data.QuotaEventEntity
import dev.modelatlas.app.data.QuotaSnapshotEntity
import dev.modelatlas.app.data.SampleEntity
import dev.modelatlas.app.data.SelectedKey
import dev.modelatlas.app.data.SnapshotFactEntity
import dev.modelatlas.app.data.SnapshotHeadEntity
import dev.modelatlas.app.data.storageKey
import dev.modelatlas.core.domain.PriceState
import dev.modelatlas.core.domain.PricingScope
import dev.modelatlas.core.domain.QuotaState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * FR-003 primary-quote real-Room device leg (T2; PORT consumers marked in-line —
 * the fr003 production ports are applied in the working tree, uncommitted at write time).
 * Four bodies against the [PORT P-H3] 4-arg loadDetail / [PORT P-H6] loadSelectedQuoteScoped
 * seam (rebased patch hunks old@160/old@179/old@200/old@314/old@941):
 *
 * 1. selectedQuote == exact selected row's latest point (values, state, source, full 7D
 *    scope); newer FOREIGN account/credential/group/endpoint/operation scopes stay in the
 *    full 7D priceHistory and never become the quote.
 * 2. Crowding: >500 foreign newer points still yield the selected quote (Variant A,
 *    relevance BEFORE LIMIT); the quote may fall outside the bounded newest-500 history.
 * 3. Empty-candidate path: concrete unmatched offering scope → MAX probe runs, then early
 *    null BEFORE the fact join (zero fact reads on the seam; history-leg fact-read count
 *    unchanged through the 4-arg path).
 * 4. selected == null → null quote without any quote SQL; legacy 3-arg delegation stays
 *    zero-visible-change (unknown offering scope → fail-closed quote null, no extra fact
 *    read on concrete-seeded fixtures).
 *
 * Read-shape invariants (harness readOnly + per-test): SELECT-only (no product DML),
 * LIMIT present on every snapshot/sample read, first bind providerId, ≤400 binds, fact
 * joins only on returned snapshot ids. Fixtures are test-only, safe references, never
 * product data. No UI, network, sleeps, or legacy-helper substitutes. Harness seam and
 * seedPrice/seedFact helpers reuse ModelDetailScopedSqlDeviceTest (:58-66/:471-552).
 */
@RunWith(AndroidJUnit4::class)
class ModelDetailScopedQuoteDeviceTest {
    private lateinit var db: AtlasDatabase
    private val queries = CopyOnWriteArrayList<SqlRead>()
    private var lastReads: List<SqlRead> = emptyList()
    @Volatile private var captureReads = false

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java,
        ).allowMainThreadQueries()
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, args ->
                    if (captureReads) queries += SqlRead(sql, args.toList())
                },
                Executor { it.run() },
            ).build()
        db.withTransaction {
            for (provider in listOf(A, B)) {
                db.providerDao().insert(ProviderEntity(provider, "FR003 fixture $provider"))
                for ((suffix, protocol) in listOf("chat" to CHAT, "responses" to RESPONSES)) {
                    db.endpointDao().insert(
                        EndpointEntity(
                            endpointId = "$provider-$suffix", providerId = provider,
                            baseUrl = "https://fr003.fixture.invalid/v1",
                            endpointUrl = "https://fr003.fixture.invalid/v1/$suffix", protocol = protocol,
                        ),
                    )
                }
            }
            for ((account, provider) in listOf(AA to A, AA2 to A, BA to B)) {
                db.accountDao().insert(AccountEntity(account, provider))
            }
            for ((credential, account) in listOf(AK to AA, AK2 to AA, A2K to AA2, BK to BA)) {
                db.credentialDao().insert(CredentialEntity(credential, account, label = "Safe fixture ref"))
            }
            for (canonical in listOf(C1, SHARED)) {
                db.canonicalModelDao().insert(CanonicalModelEntity(canonical, canonical))
            }
            // Durable sentinels: detail reads must neither add membership nor touch old history/heads.
            seedPair("preserved", preservedScope, 1L)
            db.myModelsDao().put(MyModelEntity(A, "preserved-model", 1L, "USER"))
            db.priceEventDao().insert(
                PriceEventEntity("preserved-price-event", preservedScope.storageKey(),
                    fromState = "UNKNOWN", toState = "PAID", observedAt = 1L, source = "USER"),
            )
            db.quotaEventDao().insert(
                QuotaEventEntity("preserved-quota-event", preservedScope.storageKey(),
                    fromState = "UNKNOWN", toState = "QUOTA_LOW", observedAt = 1L, source = "USER"),
            )
            seedSample("preserved-sample", A, "preserved-model", null, 1L)
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun selectedQuoteIsExactSelectedScopeLatestPointAndForeignScopesRemainInHistory() = runBlocking {
        val target = row(A, "primary", C1)
        val selectedScope = scope(A, "primary", AA, AK, "vip", CHAT)
        val exactOld = seedPrice("quote-selected-old", selectedScope, 10L, state = PriceState.PAID)
        val exactNew = seedPrice("quote-selected-new", selectedScope, 20L, state = PriceState.FREE_NOW)
        // Newer-or-equal FOREIGN scopes must never become the quote — but they stay in the
        // full 7D priceHistory (each isolates one predicate dimension).
        val fixtures = listOf(
            exactOld,
            exactNew,
            seedPrice("quote-foreign-account", scope(A, "primary", AA2, A2K, "vip", CHAT), 30L),
            seedPrice("quote-foreign-credential", scope(A, "primary", AA, AK2, "vip", CHAT), 25L),
            seedPrice("quote-foreign-group", scope(A, "primary", AA, AK, null, CHAT), 25L),
            seedPrice("quote-foreign-offering", scope(A, "primary", AA, AK, "vip", RESPONSES), 25L),
            seedPrice("quote-foreign-empty-account", scope(A, "primary", "", "", "vip", CHAT), 25L),
        )
        val selected = SelectedKey(AA, AK, "vip")

        // [PORT P-H3] 4-arg loadDetail (landed in the working tree); [PORT P-H2] SelectedOfferingScope.
        val detail = readOnly {
            ModelDetailLoader.loadDetail(
                db, target, selected,
                ModelDetailLoader.SelectedOfferingScope(endpointId = "$A-chat", operation = CHAT),
            )
        }

        // [PORT P-S1..S4] state.selectedQuote: exact selected scope's latest point only —
        // values, state, source and full 7D scope are the selected row's, never a sibling's.
        assertEquals(exactNew.price, detail.selectedQuote)
        // Foreign scopes remain in the full 7D priceHistory (never filtered/trimmed).
        assertPriceHistory(detail.priceHistory, fixtures)
        // [PORT P-H7] Read shape: history leg (7 ids → 1 chunked fact read) + quote leg
        // (unique newest → 1 bounded fact read).
        assertEquals(2, lastReads.count { it.isPriceFactRead() })
        assertTrue("quote leg must join facts bounded to the selected quote row only",
            lastReads.filter { it.isPriceFactRead() }.any { it.args.toSet() == setOf(exactNew.priceId) })
        assertFactReads(fixtures)
        // Scope predicate only in the quote probes; per chunk: MAX probe LIMIT 1 + rows probe LIMIT 2.
        val quoteProbes = lastReads.filter { it.from("price_snapshots") && it.sql.contains(QUOTE_SCOPE_PREDICATE) }
        assertEquals(2, quoteProbes.size)
        assertTrue("MAX probe must keep LIMIT 1", quoteProbes.any { it.sql.contains("LIMIT 1") })
        assertTrue("tie probe must keep LIMIT 2 and re-bind observedAt",
            quoteProbes.any { it.sql.contains("LIMIT 2") && it.sql.contains("\"observedAt\" = ?") })
        assertFirstBindProviderId(A)
    }

    @Test
    fun crowdingForeignNewerPointsStillYieldsSelectedQuoteVariantA() = runBlocking {
        val target = row(A, "crowded", C1)
        val exact = seedPrice(
            "quote-crowded-selected", scope(A, "crowded", AA, AK, "vip", CHAT), 100L,
            state = PriceState.PAID, value = 42L,
        )
        // 501 newer FOREIGN-scope points: enough to erase the older exact-scope point from
        // the newest-500 history if relevance were applied after LIMIT (Variant B).
        val decoys = db.withTransaction {
            (0..500).map { i ->
                val (account, credential) = when (i % 3) {
                    0 -> AA2 to A2K // Other account.
                    1 -> AA to AK2 // Same account, sibling key.
                    else -> null to BK // Missing account never legitimises a foreign credential.
                }
                seedPrice(
                    "quote-crowded-decoy-${padded(i)}",
                    scope(A, "crowded", account, credential, "vip", CHAT), 10_000L + i,
                )
            }
        }
        val selected = SelectedKey(AA, AK, "vip")

        val detail = readOnly {
            ModelDetailLoader.loadDetail(
                db, target, selected,
                ModelDetailLoader.SelectedOfferingScope(endpointId = "$A-chat", operation = CHAT),
            )
        }

        // Variant A: relevance BEFORE LIMIT — the exact-scope read is not crowded out.
        assertEquals(exact.price, detail.selectedQuote)
        // The bounded newest-500 history holds only foreign points; the older exact-scope
        // quote legitimately falls outside it (history is not the quote).
        assertEquals(500, detail.priceHistory.size)
        assertEquals((10_001L..10_500L).toSet(), detail.priceHistory.map { it.observedAt }.toSet())
        assertFalse(detail.priceHistory.contains(exact.price))
        // [PORT P-H7] Read shape: history 500 ids → 2 chunked fact reads; quote leg → 1
        // bounded fact read; the 501st (cut) decoy id must never enter a fact join.
        assertEquals(3, lastReads.count { it.isPriceFactRead() })
        assertTrue("quote leg must join facts bounded to the selected quote row only",
            lastReads.filter { it.isPriceFactRead() }.any { it.args.toSet() == setOf(exact.priceId) })
        assertFactReads(decoys.filter { it.price!!.observedAt > 10_000L } + listOf(exact))
        assertFirstBindProviderId(A)
    }

    @Test
    fun emptyCandidateScopeSkipsFactJoinAndYieldsNullQuote() = runBlocking {
        val target = row(A, "empty-candidate", C1)
        val historyFixtures = listOf(
            seedPrice("quote-ec-chat-a", scope(A, "empty-candidate", AA, AK, "vip", CHAT), 10L),
            seedPrice("quote-ec-chat-b", scope(A, "empty-candidate", AA, AK, "vip", CHAT), 20L),
        )
        val selected = SelectedKey(AA, AK, "vip")

        // [PORT P-H6] public seam: concrete unmatched offering scope → SQL yields no rows.
        val seamQuote = readOnly {
            ModelDetailLoader.loadSelectedQuoteScoped(
                db = db, providerId = A, modelIds = setOf("empty-candidate"), selected = selected,
                offeringScope = ModelDetailLoader.SelectedOfferingScope(
                    endpointId = "$A-responses", operation = RESPONSES,
                ),
            )
        }
        assertNull(seamQuote)
        // The empty-candidate path ran exactly one MAX probe (LIMIT 1) and returned BEFORE
        // the fact join: no rows probe, zero fact reads.
        assertEquals(1, lastReads.count { it.from("price_snapshots") && it.sql.contains(QUOTE_SCOPE_PREDICATE) })
        assertFalse(lastReads.any { it.from("price_snapshots") && it.sql.contains("LIMIT 2") })
        assertEquals(0, lastReads.count { it.isPriceFactRead() })
        assertFirstBindProviderId(A)

        // [PORT P-H3/H4/H5] Full 4-arg production path: quote null, retained history untouched.
        val detail = readOnly {
            ModelDetailLoader.loadDetail(
                db, target, selected,
                ModelDetailLoader.SelectedOfferingScope(endpointId = "$A-responses", operation = RESPONSES),
            )
        }
        assertNull(detail.selectedQuote)
        assertPriceHistory(detail.priceHistory, historyFixtures)
        assertEquals(1, lastReads.count { it.isPriceFactRead() }) // History leg only.
        assertEquals(1, lastReads.count { it.from("price_snapshots") && it.sql.contains(QUOTE_SCOPE_PREDICATE) })
        assertFirstBindProviderId(A)
    }

    @Test
    fun selectedNullYieldsNullQuoteAndThreeArgDelegationStaysZeroVisibleChange() = runBlocking {
        val target = row(A, "null-selected", C1)
        val historyFixtures = listOf(
            seedPrice("quote-null-sel-a", scope(A, "null-selected", AA, AK, "vip", CHAT), 10L),
            seedPrice("quote-null-sel-b", scope(A, "null-selected", AA, AK, "vip", CHAT), 20L),
        )

        // [PORT P-H4] selected == null → null quote WITHOUT any quote SQL (guard runs first).
        val detail = readOnly {
            ModelDetailLoader.loadDetail(
                db, target, null,
                ModelDetailLoader.SelectedOfferingScope(endpointId = "$A-chat", operation = CHAT),
            )
        }
        assertNull(detail.selectedQuote)
        assertPriceHistory(detail.priceHistory, historyFixtures) // selected==null must not trim history.
        assertEquals(0, lastReads.count { it.from("price_snapshots") && it.sql.contains(QUOTE_SCOPE_PREDICATE) })
        assertEquals(1, lastReads.count { it.isPriceFactRead() })
        assertFirstBindProviderId(A)

        // [PORT P-H3] Legacy 3-arg delegates with null offering scope → unknown scope →
        // fail-closed quote null, history untouched, and no extra fact read on
        // concrete-seeded fixtures (vector xiii).
        val legacy = readOnly { ModelDetailLoader.loadDetail(db, target, SelectedKey(AA, AK, "vip")) }
        assertNull(legacy.selectedQuote)
        assertPriceHistory(legacy.priceHistory, historyFixtures)
        // Unknown-scope predicate reaches exactly one MAX probe (point endpoint is concrete
        // → no candidates); rows probe and quote fact join are both skipped.
        assertEquals(
            1,
            lastReads.count { it.from("price_snapshots") && it.sql.contains(UNKNOWN_SCOPE_PREDICATE) },
        )
        assertFalse(lastReads.any { it.from("price_snapshots") && it.sql.contains("LIMIT 2") })
        assertEquals(1, lastReads.count { it.isPriceFactRead() })
        assertFirstBindProviderId(A)
    }

    // Shared typed fixtures: constructors and FK ordering are real; expected structs are
    // literal fixture values, never recomputed with production loader/builder/state helpers.
    private data class SnapshotFixture(
        val priceId: String? = null, val quotaId: String? = null,
        val price: DetailPricePoint? = null, val pool: DetailQuotaPool? = null,
    )

    private suspend fun seedPair(
        id: String, s: PricingScope, at: Long,
        priceState: PriceState = PriceState.PAID, quotaState: QuotaState = QuotaState.QUOTA_LOW,
        priceSource: String = "newapi/api/pricing", quotaSource: String = "newapi/api/usage/token",
        value: Long = at,
    ): SnapshotFixture {
        val price = seedPrice("price-$id", s, at, priceState, priceSource, value)
        val quota = seedQuota("quota-$id", s, at, quotaState, quotaSource, value = value)
        return SnapshotFixture(price.priceId, quota.quotaId, price.price, quota.pool)
    }

    private suspend fun seedPrice(
        id: String, s: PricingScope, at: Long, state: PriceState = PriceState.PAID,
        source: String = "newapi/api/pricing", value: Long = at,
        withFact: Boolean = true, unknownNumbers: Boolean = false,
    ): SnapshotFixture {
        val input = if (unknownNumbers) null else value.toDouble() + 0.25
        val output = if (unknownNumbers) null else value.toDouble() + 1.5
        val fixed = if (unknownNumbers) null else 0.0
        val cache = if (unknownNumbers) null else 0.125
        db.priceDao().insert(
            PriceSnapshotEntity(
                id = id, providerId = s.providerId, accountId = s.accountId, credentialId = s.credentialId,
                modelId = s.modelId, group = s.group, endpointId = s.endpointId, operation = s.operation,
                observedAt = at, input = input?.toString(), output = output?.toString(),
                fixedRequest = fixed?.toString(), cacheRead = cache?.toString(), source = source,
                evidenceId = if (withFact) "evidence-$id" else null,
            ),
        )
        if (withFact) seedFact(id, s, at, "PRICE", state.name, source)
        return SnapshotFixture(
            priceId = id,
            price = DetailPricePoint(at, input, output, fixed, cache,
                if (withFact) state else PriceState.UNKNOWN, expectedSource(source),
                s.providerId, s.accountId, s.credentialId, s.modelId, s.group, s.endpointId, s.operation),
        )
    }

    private suspend fun seedQuota(
        id: String, s: PricingScope, at: Long, state: QuotaState = QuotaState.QUOTA_LOW,
        source: String = "newapi/api/usage/token", kind: String = "TOKEN_QUOTA", value: Long = at,
        withFact: Boolean = true, unknownNumbers: Boolean = false,
    ): SnapshotFixture {
        db.quotaDao().insert(
            QuotaSnapshotEntity(
                id = id, providerId = s.providerId, accountId = s.accountId, credentialId = s.credentialId,
                modelId = s.modelId, group = s.group, endpointId = s.endpointId, operation = s.operation,
                observedAt = at, state = state.name, source = source,
                evidenceId = if (withFact) "evidence-$id" else null,
            ),
        )
        if (withFact) seedFact(id, s, at, kind, state.name, source, value, unknownNumbers)
        return SnapshotFixture(
            quotaId = id,
            pool = if (!withFact) null else DetailQuotaPool(
                kind = if (kind == "ACCOUNT_QUOTA") DetailQuotaKind.ACCOUNT_QUOTA else DetailQuotaKind.TOKEN_QUOTA,
                providerId = s.providerId, accountId = s.accountId, credentialRef = s.credentialId, state = state,
                remainingRaw = if (unknownNumbers) null else value,
                usedRaw = if (unknownNumbers) null else value + 1,
                grantedRaw = if (unknownNumbers) null else value + 2,
                unlimited = if (unknownNumbers) null else value % 2 == 0L,
                expiresAtRaw = if (unknownNumbers) null else at + 60_000,
                observedAt = at, sourceLabel = expectedSource(source), modelId = s.modelId,
                group = s.group, endpointId = s.endpointId, operation = s.operation,
            ),
        )
    }

    private suspend fun seedFact(
        snapshotId: String, s: PricingScope, at: Long, kind: String, state: String, source: String,
        value: Long? = null, unknownNumbers: Boolean = false,
    ) {
        val evidenceId = "evidence-$snapshotId"
        db.evidenceDao().insert(
            EvidenceEntity(evidenceId, "request-$snapshotId", s.providerId, s.modelId, "OK",
                status = 200, timestamp = at, source = source),
        )
        db.snapshotFactDao().insert(
            SnapshotFactEntity(
                evidenceId = evidenceId, scopeKey = s.storageKey(), kind = kind, observedAt = at,
                snapshotId = snapshotId, state = state, status = "OK", confidence = "LOW",
                providerVersion = null, pricingVersion = null, itemPricingVersion = null,
                quotaPerUnit = null, groupRatio = null, statusObservedAt = null,
                remainingRaw = if (unknownNumbers) null else value,
                usedRaw = if (unknownNumbers) null else value?.plus(1),
                grantedRaw = if (unknownNumbers) null else value?.plus(2),
                unlimited = if (unknownNumbers) null else value?.let { it % 2 == 0L },
                expiresAtRaw = if (unknownNumbers || value == null) null else at + 60_000,
                expiryMeaning = null, expressionHash = null, priceUnit = null,
            ),
        )
        db.snapshotFactDao().upsertHead(SnapshotHeadEntity(s.storageKey(), kind, evidenceId, at, snapshotId, at))
    }

    private suspend fun row(provider: String, model: String, canonical: String): ModelRow {
        db.offeringDao().insert(OfferingEntity("offering:$provider:$model", provider, "$provider-chat", model))
        return ModelRow(provider, model, canonical, displayName = "FR003 fixture $model")
    }

    private suspend fun seedSample(
        id: String, provider: String, model: String, canonical: String?, started: Long,
    ) {
        db.sampleDao().upsert(
            SampleEntity(
                id = id, providerId = provider, accountId = AA,
                safeCredentialId = AK, endpointId = "$provider-chat",
                providerModelId = model, canonicalModelId = canonical, source = "MANUAL_TEST",
                operation = CHAT, group = null, requestStartedAt = started,
                completedAt = started + 1,
                outcome = "SUCCESS", httpStatus = 200,
            ),
        )
    }

    private fun scope(
        provider: String, model: String, account: String? = null, credential: String? = null,
        group: String? = null, operation: String = CHAT,
    ) = PricingScope(provider, account, credential, model, group,
        "$provider-${if (operation == CHAT) "chat" else "responses"}", operation)

    private fun expectedSource(source: String): String = when (source) {
        "USER" -> "manual"
        "newapi/api/pricing", "newapi/api/user/self", "newapi/api/usage/token" -> "official"
        else -> ""
    }

    private fun assertPriceHistory(history: List<DetailPricePoint>, fixtures: List<SnapshotFixture>) {
        val prices = fixtures.mapNotNull { it.price }
        assertEquals(prices.size, history.size)
        assertEquals(prices.toSet(), history.toSet())
        assertEquals(prices.map { it.observedAt }.sorted(), history.map { it.observedAt })
    }

    private fun assertFirstBindProviderId(providerId: String) {
        for (read in lastReads.filter { it.from("price_snapshots") || it.from("quota_snapshots") }) {
            assertEquals("first bind must be providerId", providerId, read.args.firstOrNull())
        }
    }

    private data class SqlRead(val sql: String, val args: List<Any?>) {
        fun from(table: String): Boolean = Regex("\\bFROM\\s+[`\"]?$table[`\"]?\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(sql)
        fun isPriceFactRead(): Boolean = from("snapshot_facts") && sql.contains("'PRICE'")
    }

    /** Counts for every real table plus exact durable sentinels and actual read SQL. */
    private suspend fun <T> readOnly(block: suspend () -> T): T {
        val before = tableCounts()
        val membership = db.myModelsDao().all()
        val head = db.snapshotFactDao().head(preservedScope.storageKey(), "PRICE")
        val priceEvent = db.priceEventDao().getById("preserved-price-event")
        val quotaEvent = db.quotaEventDao().getById("preserved-quota-event")
        val sample = db.sampleDao().getById("preserved-sample")
        queries.clear()
        captureReads = true
        val result = try { block() } finally { captureReads = false }
        lastReads = queries.toList() // Direct executor: no asynchronous logging barrier or sleep.
        assertTrue("real Room queries must have executed", lastReads.isNotEmpty())
        assertFalse("detail must execute no product DML", lastReads.any {
            Regex("^\\s*(INSERT|UPDATE|DELETE|REPLACE)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.sql) &&
                !it.sql.contains("room_table_modification_log", ignoreCase = true)
        })
        for (query in lastReads.filter { it.from("price_snapshots") || it.from("quota_snapshots") ||
            it.from("samples") || it.from("snapshot_facts") }) {
            assertTrue("scoped query exceeds 400 total binds: ${query.args.size}", query.args.size <= 400)
            if (query.from("snapshot_facts")) {
                assertTrue("facts must join only bounded snapshot IDs", query.sql.contains("IN (", ignoreCase = true))
                assertTrue(query.args.isNotEmpty())
            } else {
                assertTrue("snapshot/sample reads must be bounded", query.sql.contains("LIMIT", ignoreCase = true))
            }
        }
        assertEquals("all table counts remain unchanged", before, tableCounts())
        assertEquals(membership, db.myModelsDao().all())
        assertEquals(head, db.snapshotFactDao().head(preservedScope.storageKey(), "PRICE"))
        assertEquals(priceEvent, db.priceEventDao().getById("preserved-price-event"))
        assertEquals(quotaEvent, db.quotaEventDao().getById("preserved-quota-event"))
        assertEquals(sample, db.sampleDao().getById("preserved-sample"))
        return result
    }

    private fun assertFactReads(fixtures: List<SnapshotFixture>) {
        val priceIds = fixtures.mapNotNull { it.priceId }.toSet()
        val quotaIds = fixtures.mapNotNull { it.quotaId }.toSet()
        assertEquals("only returned prices may enter fact joins", priceIds,
            lastReads.filter { it.isPriceFactRead() }.flatMap { it.args }.toSet())
        assertEquals("only returned quotas may enter fact joins", quotaIds,
            lastReads.filter { it.from("snapshot_facts") && sqlHasQuotaKind(it.sql) }.flatMap { it.args }.toSet())
    }

    private fun sqlHasQuotaKind(sql: String): Boolean =
        sql.contains("'ACCOUNT_QUOTA'") || sql.contains("'TOKEN_QUOTA'") ||
            sql.contains("'MANUAL_QUOTA'") || sql.contains("'DEDICATED_ACCOUNT_QUOTA'") ||
            sql.contains("'DEDICATED_TOKEN_QUOTA'")

    private fun tableCounts(): Map<String, Long> {
        val sql = db.openHelper.readableDatabase
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return tables.associateWith { table ->
            sql.query("SELECT COUNT(*) FROM `$table`").use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }
        }
    }

    private fun padded(value: Int): String = value.toString().padStart(4, '0')

    private val preservedScope get() = scope(A, "preserved-model", AA, AK, "preserved")

    companion object {
        /** [PORT P-H7] scope-predicate literals only the quote probes emit (patch verbatim). */
        private const val QUOTE_SCOPE_PREDICATE = "\"credentialId\" IS NULL OR \"credentialId\" = ?"
        private const val UNKNOWN_SCOPE_PREDICATE =
            "\"endpointId\" IS NULL OR UPPER(TRIM(\"endpointId\")) IN ('', 'UNKNOWN')"
        private const val A = "fr003-provider-A"
        private const val B = "fr003-provider-B"
        private const val AA = "fr003-account-A"
        private const val AA2 = "fr003-account-A2"
        private const val BA = "fr003-account-B"
        private const val AK = "fr003-ref-A"
        private const val AK2 = "fr003-ref-A-sibling"
        private const val A2K = "fr003-ref-A2"
        private const val BK = "fr003-ref-B"
        private const val C1 = "fr003-canonical-one"
        private const val SHARED = "fr003-canonical-shared"
        private const val CHAT = "CHAT_COMPLETIONS"
        private const val RESPONSES = "RESPONSES"
    }
}
