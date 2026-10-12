package dev.modelatlas.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X-015 safe backup-export JVM 單測（fixture 僅存於 test sourceSet，不當產品資料）。
 *
 * - 無 secret、無真實 key／PAT：憑證一律 `safe-...` 佔位。
 * - 無 mock 框架：純 JUnit＋純函式 [SafeBackup]／[BackupExporter]。
 * - 覆盖 BACKLOG X-015 acceptance 草案 1～6＋fail-closed。
 */
class BackupTest {

    private fun sampleBackup(
        withResearch: Boolean = false,
    ): AtlasBackup = AtlasBackup(
        schemaVersion = BACKUP_SCHEMA_VERSION,
        exportedAtMillis = 1700000000000L,
        providers = listOf(
            BackupProvider(
                providerId = "p-fixture-1",
                name = "Fixture Provider",
                websiteUrl = "https://example.com",
                group = "default",
                tags = listOf("tag-a", "tag-b"),
                authMode = "BEARER",
                checkinUrl = "https://example.com/checkin",
                requiresDailyCheckin = true,
            ),
        ),
        accounts = listOf(
            BackupAccount(accountId = "a-fixture-1", providerId = "p-fixture-1"),
        ),
        endpointProfiles = listOf(
            BackupEndpointProfile(
                endpointId = "e-fixture-1",
                providerId = "p-fixture-1",
                baseUrl = "https://example.com/v1",
                endpointUrl = "https://example.com/v1/chat/completions",
                protocol = "CHAT_COMPLETIONS",
            ),
        ),
        canonicalModels = listOf(
            BackupCanonicalModel(
                canonicalModelId = "c-fixture-1",
                normalizedName = "fixture-model",
                displayName = "Fixture Model",
            ),
        ),
        aliases = listOf(
            BackupAlias(
                aliasId = "alias-fixture-1",
                providerId = "p-fixture-1",
                providerModelId = "m-fixture-1",
                canonicalModelId = "c-fixture-1",
                source = "MANUAL",
                confidence = "LOW",
                verified = false,
                evidenceId = "ev-fixture-1",
            ),
        ),
        classifications = listOf(
            BackupClassification(
                id = "clazz-fixture-1",
                canonicalModelId = "c-fixture-1",
                source = "MANUAL",
                confidence = "LOW",
                primaryCategory = "CHAT",
                capabilities = listOf("TOOL_USE"),
                modalities = listOf("TEXT"),
                endpointTypes = listOf("CHAT_COMPLETIONS"),
            ),
        ),
        credentialRefs = listOf(
            BackupCredentialRef(
                safeCredentialId = "safe-cred-fixture-1",
                label = "fixture inference",
                role = "INFERENCE",
                accountId = "a-fixture-1",
            ),
        ),
        manualPricing = listOf(
            BackupManualPrice(
                priceId = "price-fixture-1",
                providerId = "p-fixture-1",
                accountId = "a-fixture-1",
                safeCredentialId = "safe-cred-fixture-1",
                modelId = "m-fixture-1",
                group = "default",
                endpointId = "e-fixture-1",
                operation = "CHAT_COMPLETIONS",
                decimalAmount = "0.001",
                currency = "USD",
                perUnit = "INPUT_TOKEN",
                provenance = "USER",
                evidenceId = "ev-fixture-1",
                observedAtMillis = 1700000000000L,
                note = "hand filled",
            ),
        ),
        checkinConfigs = listOf(
            BackupCheckinConfig(
                providerId = "p-fixture-1",
                accountId = "a-fixture-1",
                date = "2026-09-29",
                checkinUrl = "https://example.com/checkin",
                requiresDailyCheckin = true,
                completedAtMillis = null,
            ),
        ),
        notificationSettings = listOf(
            BackupNotificationSetting(
                ruleId = "became_free_now",
                enabled = null,
                threshold = null,
                scope = "global",
            ),
        ),
        notes = listOf(
            BackupNote(
                noteId = "note-fixture-1",
                providerId = "p-fixture-1",
                canonicalModelId = "c-fixture-1",
                accountId = null,
                text = "fixture note",
                updatedAtMillis = 1700000000000L,
            ),
        ),
        appSettings = BackupAppSettings(
            rawSampleWindowDays = null,
            deletesRawEvidence = false,
        ),
        researchKnowledge = if (withResearch) {
            BackupResearchKnowledge(
                strategyProfileId = "strategy-fixture-1",
                summary = "fixture summary",
                recipes = listOf(
                    BackupRecipe(
                        recipeId = "recipe-fixture-1",
                        version = 1,
                        description = "fixture recipe",
                        source = "USER",
                    ),
                ),
            )
        } else {
            null
        },
    )

    @Test
    fun exportDeclaresSchemaVersion() {
        val json = SafeBackup.export(sampleBackup())
        assertTrue(json.contains("\"schemaVersion\""))
        assertTrue(json.contains("\"schemaVersion\": 1") || json.contains("\"schemaVersion\":1"))
        val parsed = SafeBackup.parse(json)
        assertEquals(BACKUP_SCHEMA_VERSION, parsed.schemaVersion)
        assertEquals(1, parsed.schemaVersion)
    }

    @Test
    fun unknownSchemaVersionFailsClosed() {
        val bad = sampleBackup().copy(schemaVersion = 999)
        // export 本身即應拒絕未知版本（validate 先擋）。
        assertThrows(IllegalArgumentException::class.java) {
            SafeBackup.export(bad)
        }
        // 若外部竄改 JSON 版本號，parse 亦須 fail-closed。
        val goodJson = SafeBackup.export(sampleBackup())
        val tampered = goodJson.replace("\"schemaVersion\": 1", "\"schemaVersion\": 999")
            .replace("\"schemaVersion\":1", "\"schemaVersion\":999")
        assertTrue(tampered.contains("999"))
        assertThrows(IllegalArgumentException::class.java) {
            SafeBackup.parse(tampered)
        }
    }

    @Test
    fun exportContainsNoVaultCiphertextOrIv() {
        val json = SafeBackup.export(sampleBackup())
        val lowered = json.lowercase()
        assertFalse(lowered.contains("credential_ciphertexts"))
        assertFalse(lowered.contains("ciphertext"))
        assertFalse(lowered.contains("\"iv\""))
    }

    @Test
    fun exportOmitsRawSecretsAndKeepsSafeCredentialRefs() {
        val json = SafeBackup.export(sampleBackup())
        assertTrue(json.contains("safeCredentialId"))
        assertTrue(json.contains("safe-cred-fixture-1"))
        // 本次 fixture 從未放入 raw secret；以下為代表性禁區字樣，匯出不得出現。
        assertFalse(json.contains("sk-test-raw-secret-99999"))
        val lowered = json.lowercase()
        assertFalse(lowered.contains("rawkey"))
        assertFalse(lowered.contains("rawsecret"))
        assertFalse(lowered.contains("client_secret"))
    }

    @Test
    fun roundTripRestoresProviderEndpointsAliasesAndNotes() {
        val original = sampleBackup()
        val restored = SafeBackup.parse(SafeBackup.export(original))
        assertEquals(original.providers, restored.providers)
        assertEquals(original.endpointProfiles, restored.endpointProfiles)
        assertEquals(original.aliases, restored.aliases)
        assertEquals(original.notes, restored.notes)
        assertEquals(original, restored)
    }

    @Test
    fun manualPricingKeepsUserProvenanceAndNeverOfficial() {
        val original = sampleBackup()
        val restored = SafeBackup.parse(SafeBackup.export(original))
        assertEquals("USER", restored.manualPricing.single().provenance)
        assertFalse(restored.manualPricing.single().provenance == "OFFICIAL")
        val bad = original.copy(
            manualPricing = listOf(original.manualPricing.single().copy(provenance = "OFFICIAL")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SafeBackup.export(bad)
        }
    }

    @Test
    fun recipesFieldIsOmittedWhenAbsent() {
        val withoutResearch = sampleBackup(withResearch = false)
        val json = SafeBackup.export(withoutResearch)
        assertFalse(json.contains("researchKnowledge"))
        assertFalse(json.contains("recipes"))
        assertFalse(json.contains("recipe-fixture-1"))
        // 有 research 但 recipes=null 時，只省略 recipes，不省略外層。
        val partial = sampleBackup(withResearch = true).copy(
            researchKnowledge = BackupResearchKnowledge(
                strategyProfileId = "strategy-fixture-1",
                summary = "fixture summary",
                recipes = null,
            ),
        )
        val partialJson = SafeBackup.export(partial)
        assertTrue(partialJson.contains("researchKnowledge"))
        assertFalse(partialJson.contains("recipes"))
    }

    @Test
    fun notificationUndecidedStaysNullAfterRoundTrip() {
        val restored = SafeBackup.parse(SafeBackup.export(sampleBackup()))
        assertNull(restored.notificationSettings.single().enabled)
        val decided = sampleBackup().copy(
            notificationSettings = listOf(
                BackupNotificationSetting(ruleId = "quota_low", enabled = true, threshold = "0.2"),
            ),
        )
        val decidedRestored = SafeBackup.parse(SafeBackup.export(decided))
        assertEquals(true, decidedRestored.notificationSettings.single().enabled)
    }

    @Test
    fun checkinConfigRoundTripsAndPreservesNullCompleted() {
        val restored = SafeBackup.parse(SafeBackup.export(sampleBackup()))
        val cfg = restored.checkinConfigs.single()
        assertEquals("p-fixture-1", cfg.providerId)
        assertEquals("a-fixture-1", cfg.accountId)
        // null＝尚未完成（Unknown≠0，不補 0）。
        assertNull(cfg.completedAtMillis)
    }

    @Test
    fun credentialRefsContainOnlySafeIdsAndSurviveRoundTrip() {
        val restored = SafeBackup.parse(SafeBackup.export(sampleBackup()))
        assertEquals(listOf("safe-cred-fixture-1"), restored.credentialRefs.map { it.safeCredentialId })
        val json = SafeBackup.export(sampleBackup())
        assertFalse(json.lowercase().contains("ciphertext"))
        // BackupExporter 別名與 SafeBackup 同義。
        val viaAlias = BackupExporter.parse(BackupExporter.export(sampleBackup()))
        assertEquals(restored, viaAlias)
    }
}
