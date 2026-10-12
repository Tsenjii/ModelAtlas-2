package dev.modelatlas.app.ui

import android.os.Looper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.modelatlas.app.AtlasApplication
import dev.modelatlas.app.AtlasViewModel
import dev.modelatlas.app.MainActivity
import dev.modelatlas.app.R
import dev.modelatlas.app.data.ConnectionRecord
import dev.modelatlas.core.network.EndpointPolicy
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S12: Model Detail system-back contract (device).
 *
 * TRACEABILITY: B-2 / B9, adjudicated REAL in ai11-audit-backhandler-result.md.
 * Adjudication summary: Model Detail is an overlay branch inside single-Activity
 * `AtlasScreen` (AtlasScreen.kt:1745-1791) driven by VM `StateFlow modelDetailRow`
 * (AtlasViewModel.kt:352-353,2449-2468). With no `BackHandler`, the press falls to
 * the Activity default = `finish()`. Fix = `BackHandler(onDetailClose)` in the Model
 * Detail branch — the same pattern as Provider Detail (AtlasScreen.kt:638).
 *
 * Harness precedent (cited by the task): the Provider Detail back test at
 * ConnectionCardFlowTest.kt:160-163 with `pressSystemBack` at :627-629 — real
 * `MainActivity` production flow, fixture seeded through the real repository, system
 * back sent through the activity's real `onBackPressedDispatcher`. Detail entry uses
 * the accepted VM entry point `openModelDetail` (CompareMultiProviderFlowTest.kt:280,
 * Fr002ToggleMembershipTest.kt:141) with a row carrying real detail data.
 *
 * Asserts: detail closed (`modelDetailRow == null` + detail composable absent) AND
 * the app NOT finished (activity still resumed). RED until the same-slice production
 * `BackHandler` lands; GREEN after (no test change needed).
 */
@RunWith(AndroidJUnit4::class)
class ModelDetailBackHandlerTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var app: AtlasApplication
    private lateinit var viewModel: AtlasViewModel
    private val providerIds = mutableListOf<String>()
    private val credentialRefs = mutableListOf<String>()

    @Before
    fun setUp() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "this test must not run on the main thread" }
        app = compose.activity.application as AtlasApplication
        runBlocking { withTimeout(TIMEOUT_MS) { app.ready.first { it } } }
        compose.runOnIdle {
            viewModel = ViewModelProvider(compose.activity)[AtlasViewModel::class.java]
        }
    }

    @After
    fun tearDown() {
        runCatching { onMain { viewModel.closeModelDetail() } }
        // Close the activity/VM first so its collectors stop before rows disappear.
        runCatching { compose.activityRule.scenario.close() }
        for (ref in credentialRefs) runCatching { app.vault.remove(ref) }
        credentialRefs.clear()
        deleteOwnRows()
        providerIds.clear()
    }

    @Test
    fun systemBackClosesModelDetailWithoutFinishingActivity() {
        val p = UUID.randomUUID().toString()
        seedConnection(
            providerId = p, availabilityId = "$p-0a", accountId = "$p-acct-0a",
            credentialId = "$p-cred-0a", endpointId = "$p-ep", offeringId = "$p-off",
            modelId = MODEL_ID, name = "Back Handler P", baseUrl = BASE_URL,
        )

        // The Models tab hosts the detail overlay (AtlasScreen ModelsTab branch).
        openModels()

        // A model with real detail data, opened through the real VM entry point.
        val row = awaitDetailRow(p)
        onMain { viewModel.openModelDetail(row) }
        compose.waitUntil(TIMEOUT_MS) { viewModel.modelDetailRow.value != null }
        val title = compose.activity.getString(R.string.model_detail_title)
        awaitText(title)
        assertNotNull("detail state must be set after open", viewModel.modelDetailRow.value)

        // Actual system Back via the configured dispatcher (AtlasScreen BackHandler).
        pressSystemBack()
        compose.waitUntil(TIMEOUT_MS) { viewModel.modelDetailRow.value == null }
        assertNull("system Back must close model detail state", viewModel.modelDetailRow.value)
        assertFalse("system Back must remove the detail composable", textExists(title))

        // The app must NOT have finished: the activity is still resumed.
        compose.runOnIdle {
            assertFalse("system Back from detail must not finish the app", compose.activity.isFinishing)
            assertFalse("system Back from detail must not destroy the activity", compose.activity.isDestroyed)
        }
    }

    // ===== harness (mirrors ConnectionCardFlowTest) =====

    private fun onMain(action: () -> Unit) {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
    }

    /**
     * Real seed: provider/account/credential/endpoint/offering/availability via the repository.
     * Tracking is registered before the write so a partial failure is still cleaned up.
     */
    private fun seedConnection(
        providerId: String,
        availabilityId: String,
        accountId: String,
        credentialId: String,
        endpointId: String,
        offeringId: String,
        modelId: String,
        name: String,
        baseUrl: String,
        credentialLabel: String = "主要憑證",
        secret: String = FIXTURE_SECRET,
    ): String {
        providerIds += providerId
        credentialRefs += credentialId
        val origin = EndpointPolicy.origin(EndpointPolicy.validate(baseUrl))
        runBlocking {
            app.vault.store(credentialId, origin, secret)
            app.repository.addConnection(
                ConnectionRecord(
                    id = availabilityId,
                    providerId = providerId,
                    accountId = accountId,
                    credentialId = credentialId,
                    endpointId = endpointId,
                    name = name,
                    baseUrl = baseUrl,
                    endpointUrl = EndpointPolicy.chatEndpoint(baseUrl),
                    modelId = modelId,
                    credentialLabel = credentialLabel,
                    offeringId = offeringId,
                ),
            )
        }
        return availabilityId
    }

    private fun openModels() {
        compose.onNodeWithText(compose.activity.getString(R.string.tab_models)).performClick()
        compose.waitForIdle()
    }

    /** Wait for the production projection to publish the fixture's model row. */
    private fun awaitDetailRow(providerId: String): ModelRow {
        compose.waitUntil(TIMEOUT_MS) {
            viewModel.allModelRows.value.any { it.providerId == providerId && it.providerModelId == MODEL_ID }
        }
        return viewModel.allModelRows.value.first { it.providerId == providerId && it.providerModelId == MODEL_ID }
    }

    private fun pressSystemBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    private fun textExists(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun awaitText(text: String, timeoutMillis: Long = TIMEOUT_MS) {
        compose.waitUntil(timeoutMillis) { textExists(text) }
    }

    /**
     * Narrow cleanup of this file's own UUID providers in FK-safe order (RESTRICT aware):
     * availability → credentials/offerings → endpoints → accounts → provider-owned rows → providers.
     * Never wildcard-deletes product history; only tables this fixture writes are touched.
     */
    private fun deleteOwnRows() {
        if (providerIds.isEmpty()) return
        val list = providerIds.joinToString(",") { "?" }
        val args = providerIds.toTypedArray<Any?>()
        val byAccount = "accountId IN (SELECT accountId FROM accounts WHERE providerId IN ($list))"
        val db = app.database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM checkins WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM my_models WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM samples WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM provider_configurations WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM credential_availability WHERE $byAccount", args)
            db.execSQL("DELETE FROM credentials WHERE $byAccount", args)
            db.execSQL("DELETE FROM offerings WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM endpoints WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM accounts WHERE providerId IN ($list)", args)
            db.execSQL("DELETE FROM providers WHERE providerId IN ($list)", args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val BASE_URL = "https://model-back-handler.fixture.invalid/v1"
        const val MODEL_ID = "model-back-handler-shared-model"
        const val FIXTURE_SECRET = "model-back-handler-fixture-secret-never-persisted"
    }
}
