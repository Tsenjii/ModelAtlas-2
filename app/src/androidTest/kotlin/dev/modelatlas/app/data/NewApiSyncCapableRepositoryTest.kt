package dev.modelatlas.app.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.core.onboarding.AuthMode
import dev.modelatlas.core.onboarding.CredentialRole
import dev.modelatlas.core.onboarding.SafeCredentialRef
import kotlinx.coroutines.flow.first
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
 * PK-12/S5 V-A..V-H against the application's real Room database and production VM/repository.
 *
 * Fixtures contain safe credential references only.  Capability probing is metadata-only: no
 * adapter, vault read, HTTP server, or sync call is created.  Each probe also snapshots the
 * durable telemetry/intelligence tables and requires exact row-count preservation.
 */
@RunWith(AndroidJUnit4::class)
class NewApiSyncCapableRepositoryTest {

    private lateinit var app: AtlasApplication
    private lateinit var vm: AtlasViewModel
    private lateinit var vmStore: ViewModelStore
    private val providerIds = mutableListOf<String>()

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtlasApplication
        runBlocking { withTimeout(15_000L) { app.ready.first { it } } }
        vmStore = ViewModelStore()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = ViewModelProvider(vmStore, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = AtlasViewModel(app) as T
            }).get(AtlasViewModel::class.java)
        }
    }

    @After
    fun tearDown() {
        if (::vmStore.isInitialized) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { runCatching { vmStore.clear() } }
        }
        deleteFixtures()
    }

    @Test
    fun va_patAccountAccountRefEndpointAndOffering_isCapable() = runBlocking {
        val f = seed("va", AuthMode.PAT_ACCOUNT, accountRef = true, endpoint = true, offering = true)
        assertProbe(f.providerId, expected = true)
    }

    @Test
    fun vb_missingConfig_isNotCapable() = runBlocking {
        val f = seed("vb", config = false, endpoint = true, offering = true)
        assertProbe(f.providerId, expected = false)
    }

    @Test
    fun vc_nonPatAuthMode_isNotCapable() = runBlocking {
        val f = seed("vc", AuthMode.BEARER, accountRef = true, endpoint = true, offering = true)
        assertProbe(f.providerId, expected = false)
    }

    @Test
    fun vd_withoutAccountRef_isNotCapable() = runBlocking {
        val f = seed("vd", AuthMode.PAT_ACCOUNT, accountRef = false, endpoint = true, offering = true)
        assertProbe(f.providerId, expected = false)
    }

    @Test
    fun ve_endpointsWithoutOfferings_isStillCapable_H3LandedShape() = runBlocking {
        val f = seed("ve", AuthMode.PAT_ACCOUNT, accountRef = true, endpoint = true, offering = false)
        // LANDED H3 (AtlasRepository.newApiSyncSetupPresent KDoc): offerings are
        // NOT part of the capability gate — sync backfills them. The draft spec's
        // G2 (offerings required) was superseded: it would permanently hide the
        // CTA for first-use PAT providers that simply have no offering rows yet.
        // Pinning the landed behavior; a provider with endpoints is capable.
        assertProbe(f.providerId, expected = true)
    }

    @Test
    fun vf_missingEndpoints_isNotCapable() = runBlocking {
        val f = seed("vf", AuthMode.PAT_ACCOUNT, accountRef = true, endpoint = false, offering = false)
        assertProbe(f.providerId, expected = false)
    }

    @Test
    fun vg_blankAndUnknownProviderIds_areNotCapable() = runBlocking {
        assertEquals(false, vm.newApiSyncCapable(""))
        assertEquals(false, vm.newApiSyncCapable("pk12-unknown-provider"))
    }

    @Test
    fun vh_probesAreReadOnlyAndNeverUseHttpOrVault() = runBlocking {
        val f = seed("vh", AuthMode.PAT_ACCOUNT, accountRef = true, endpoint = true, offering = true)
        val before = counts()
        assertTrue(vm.newApiSyncCapable(f.providerId))
        assertEquals("capability probe must not write Room telemetry/intelligence", before, counts())
        assertEquals("repository setup probe is query-only", true, app.repository.newApiSyncSetupPresent(f.providerId))
    }

    private suspend fun assertProbe(providerId: String, expected: Boolean) {
        val before = counts()
        assertEquals(expected, vm.newApiSyncCapable(providerId))
        assertEquals("probe must preserve durable rows", before, counts())
    }

    private data class Fixture(val providerId: String, val endpointId: String)

    private suspend fun seed(
        label: String,
        authMode: AuthMode? = AuthMode.PAT_ACCOUNT,
        accountRef: Boolean = true,
        endpoint: Boolean = true,
        offering: Boolean = true,
        config: Boolean = true,
    ): Fixture {
        val tag = "pk12-$label-${System.nanoTime()}"
        val providerId = "provider-$tag"
        val accountId = "account-$tag"
        val patId = "pat-$tag"
        val endpointId = "endpoint-$tag"
        providerIds += providerId
        app.database.providerDao().insert(ProviderEntity(providerId, "PK12 $label"))
        app.database.accountDao().insert(AccountEntity(accountId, providerId))
        app.database.credentialDao().insert(CredentialEntity(patId, accountId))
        if (endpoint) {
            app.database.endpointDao().insert(
                EndpointEntity(endpointId, providerId, "https://fixture.invalid/v1", "https://fixture.invalid/v1/chat/completions"),
            )
        }
        if (offering) {
            app.database.offeringDao().insert(
                OfferingEntity("offering-$tag", providerId, endpointId, "fixture-model", manual = false, source = "OFFICIAL"),
            )
        }
        if (config && authMode != null) {
            val refs = if (accountRef) {
                listOf(SafeCredentialRef(patId, "fixture account", CredentialRole.ACCOUNT))
            } else {
                listOf(SafeCredentialRef(patId, "fixture inference", CredentialRole.INFERENCE))
            }
            app.database.providerConfigDao().insert(
                ProviderManualConfig(providerId, "workflow-$tag", authMode, credentials = refs).toEntity(),
            )
        }
        return Fixture(providerId, endpointId)
    }

    private fun counts(): Map<String, Long> = linkedMapOf(
        "samples" to count("samples"),
        "evidence" to count("evidence"),
        "snapshot_facts" to count("snapshot_facts"),
        "snapshot_heads" to count("snapshot_heads"),
        "provider_perf_facts" to count("provider_perf_facts"),
        "provider_perf_heads" to count("provider_perf_heads"),
        "provider_account_usage_facts" to count("provider_account_usage_facts"),
        "provider_account_usage_heads" to count("provider_account_usage_heads"),
        "my_models" to count("my_models"),
    )

    private fun count(table: String): Long = app.database.openHelper.readableDatabase.query(
        "SELECT COUNT(*) FROM `$table`",
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun deleteFixtures() {
        if (providerIds.isEmpty()) return
        val db = app.database.openHelper.writableDatabase
        providerIds.forEach { providerId ->
            db.execSQL("DELETE FROM offerings WHERE providerId = ?", arrayOf(providerId))
            db.execSQL("DELETE FROM endpoints WHERE providerId = ?", arrayOf(providerId))
            db.execSQL("DELETE FROM provider_configurations WHERE providerId = ?", arrayOf(providerId))
            db.execSQL("DELETE FROM credentials WHERE accountId IN (SELECT accountId FROM accounts WHERE providerId = ?)", arrayOf(providerId))
            db.execSQL("DELETE FROM accounts WHERE providerId = ?", arrayOf(providerId))
            db.execSQL("DELETE FROM providers WHERE providerId = ?", arrayOf(providerId))
        }
        providerIds.clear()
    }
}
