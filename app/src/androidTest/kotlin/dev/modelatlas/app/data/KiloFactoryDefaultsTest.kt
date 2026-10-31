package dev.modelatlas.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import dev.modelatlas.core.network.KiloFactoryPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KiloFactoryDefaultsTest {
    private lateinit var room: AtlasDatabase
    private lateinit var defaults: KiloFactoryDefaults
    @Before fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        room = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        defaults = KiloFactoryDefaults(context, room)
    }
    @After fun close() { room.close() }
    private fun count(table: String): Int = room.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    @Test fun freshRoomCreationMarkerSurvivesInstallerRecreationBeforeMetadataCommit() = runBlocking {
        room.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        room = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).addCallback(KiloFactoryDefaults.creationCallback).build()
        // Open Room, then recreate the installer as after a stopped startup.
        assertEquals(0, room.providerDao().all().size)
        val pending = KiloFactoryDefaults(context, room, freshMarkerRequired = true)
        assertFalse(pending.info().installed)
        assertTrue(pending.install(automatic = true)); assertEquals(15, count("my_models"))
    }

    @Test fun existingEmptyWorkspaceWithoutFreshMarkerNeedsExplicitAddition() = runBlocking {
        val old = KiloFactoryDefaults(ApplicationProvider.getApplicationContext(), room, freshMarkerRequired = true)
        assertFalse(old.install(automatic = true)); assertEquals(0, count("providers"))
        assertFalse(old.info().installed)
        assertTrue(old.install(automatic = false)); assertEquals(15, count("my_models"))
    }

    @Test fun newWorkspaceGetsFifteenRealAnonymousSelectionsWithoutSecretsOrSamples() = runBlocking {
        assertTrue(defaults.install(automatic = true))
        val records = room.connectionDao().observe().first()
        assertEquals(15, records.size)
        assertTrue(records.all { it.anonymous && it.credentialId.isEmpty() && it.providerId == KiloFactoryPolicy.PROVIDER && it.accountId == KiloFactoryPolicy.PROVIDER && it.endpointUrl == KiloFactoryPolicy.BASE_URL + "/chat/completions" })
        assertTrue(records.all { it.priceState == "UNKNOWN" && it.quotaState == "UNKNOWN" })
        assertEquals(0, count("credentials")); assertEquals(0, count("samples")); assertEquals(0, count("evidence"))
        assertEquals(15, count("my_models")); assertEquals(15, count("offerings"))
    }

    @Test fun removalAndHidingSurviveRepeatedInstallAndInstallerRecreation() = runBlocking {
        defaults.install(automatic = true)
        val first = room.offeringDao().byProvider(KiloFactoryPolicy.PROVIDER).first()
        room.myModelsDao().delete(first.providerId, first.providerModelId)
        room.openHelper.writableDatabase.execSQL("UPDATE offerings SET hidden=1 WHERE id=?", arrayOf(first.id))
        val again = KiloFactoryDefaults(ApplicationProvider.getApplicationContext(), room)
        assertFalse(again.install(automatic = true)); assertFalse(again.install(automatic = false))
        assertEquals(14, count("my_models"))
        assertEquals(14, room.connectionDao().observe().first().size)
        assertTrue(again.info().installed)
    }

    @Test fun existingWorkspaceNeedsExplicitAdditionAndKeepsOriginalProfile() = runBlocking {
        room.providerDao().insert(ProviderEntity("personal", "Original private provider"))
        assertFalse(defaults.install(automatic = true)); assertEquals(1, count("providers")); assertEquals(0, count("my_models"))
        assertTrue(defaults.install(automatic = false)); assertEquals(2, count("providers"))
        assertEquals("Original private provider", room.providerDao().getById("personal")!!.name)
    }

    @Test fun reservedIdCollisionCannotOverwriteExistingData() = runBlocking {
        room.providerDao().insert(ProviderEntity(KiloFactoryPolicy.PROVIDER, "Personal same ID"))
        assertNotNull(runCatching { defaults.install(automatic = false) }.exceptionOrNull())
        assertEquals("Personal same ID", room.providerDao().getById(KiloFactoryPolicy.PROVIDER)!!.name)
        assertEquals(0, count("offerings")); assertEquals(0, count("accounts"))
    }

    @Test fun secondModelStorageFailureRollsBackProviderMembersAndMarker() = runBlocking {
        room.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_factory_row BEFORE INSERT ON offerings WHEN NEW.modelId='recursive/flint-v1.0:free' BEGIN SELECT RAISE(ABORT,'fixture unavailable'); END")
        assertNotNull(runCatching { defaults.install(automatic = true) }.exceptionOrNull())
        assertEquals(0, count("providers")); assertEquals(0, count("accounts")); assertEquals(0, count("offerings")); assertEquals(0, count("my_models"))
        room.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_factory_row")
        assertTrue(defaults.install(automatic = true)); assertEquals(15, count("my_models"))
    }

    @Test fun configurationAndCapabilitiesRemainTypedProviderClaimsNotVerification() = runBlocking {
        defaults.install(automatic = true)
        val config = room.providerConfigDao().getByProvider(KiloFactoryPolicy.PROVIDER)!!.toManualConfig()
        assertTrue(config.anonymousConfirmed); assertTrue(config.credentials.isEmpty()); assertTrue(config.customAuthFields.isEmpty())
        assertEquals("NONE", config.authMode.name)
        room.openHelper.writableDatabase.query("SELECT COUNT(*) FROM provider_model_aliases WHERE verified!=0 OR confidence!='LOW'").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        room.openHelper.writableDatabase.query("SELECT COUNT(*) FROM offerings WHERE verificationEvidenceId IS NOT NULL").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        assertEquals(15, defaults.info().models)
    }
}
