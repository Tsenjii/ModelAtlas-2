package dev.modelatlas.app.notifications

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android preferences, unique per test; no production preference file is touched. */
@RunWith(AndroidJUnit4::class)
class NotificationSettingsStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferencesName = "notification_settings_test_${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val store = NotificationSettingsStore(context, preferencesName)
    private val additionalPreferences = mutableListOf<String>()

    @After
    fun clean() {
        (additionalPreferences + preferencesName).forEach { name ->
            assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
            assertTrue(context.deleteSharedPreferences(name))
        }
    }

    @Test
    fun freshPreferencesDefaultAllOffWithoutWritingOptIn() {
        assertEquals(NotificationSettingsState(), store.state.value)
        NotificationRule.entries.forEach { assertFalse(store.state.value.enabled(it)) }
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun effectiveRuleRequiresBothMasterAndIndividualChoice() = runBlocking {
        val rule = NotificationRule.QUOTA_LOW
        assertTrue(store.setMasterEnabled(true))
        assertFalse(store.state.value.enabled(rule))
        assertTrue(store.setRuleEnabled(rule, true))
        assertTrue(store.state.value.enabled(rule))
        assertTrue(store.setRuleEnabled(rule, false))
        assertFalse(store.state.value.enabled(rule))
        assertTrue(store.state.value.masterEnabled)
    }

    @Test
    fun choicesCanBeConfiguredWithMasterOffAndSurviveMasterToggles() = runBlocking {
        val choices = setOf(NotificationRule.BECAME_FREE, NotificationRule.PENDING_CHECKIN)
        choices.forEach { assertTrue(store.setRuleEnabled(it, true)) }
        assertEquals(choices, store.state.value.enabledRules)
        NotificationRule.entries.forEach { assertFalse(store.state.value.enabled(it)) }

        assertTrue(store.setMasterEnabled(true))
        choices.forEach { assertTrue(store.state.value.enabled(it)) }
        assertTrue(store.setMasterEnabled(false))
        assertEquals(NotificationSettingsState(false, choices), store.state.value)
        NotificationRule.entries.forEach { assertFalse(store.state.value.enabled(it)) }
        assertEquals(store.state.value, NotificationSettingsStore(context, preferencesName).state.value)
        assertTrue(store.setMasterEnabled(true))
        assertEquals(NotificationSettingsState(true, choices), store.state.value)
    }

    @Test
    fun allSixRulesAreIndependentlyEnabledAndDisabled() = runBlocking {
        assertEquals(6, NotificationRule.entries.size)
        assertTrue(store.setMasterEnabled(true))
        NotificationRule.entries.forEach { selected ->
            assertTrue(store.setRuleEnabled(selected, true))
            assertEquals(setOf(selected), store.state.value.enabledRules)
            NotificationRule.entries.forEach { rule ->
                assertEquals(rule == selected, store.state.value.enabled(rule))
            }
            assertTrue(store.setRuleEnabled(selected, false))
            assertEquals(emptySet<NotificationRule>(), store.state.value.enabledRules)
        }
    }

    @Test
    fun successfulWritesLoadOnRecreationAndPersistOnlyBooleans() = runBlocking {
        val choices = setOf(NotificationRule.PRICE_CHANGE, NotificationRule.OUTAGE_RECOVERY)
        choices.forEach { assertTrue(store.setRuleEnabled(it, true)) }
        assertTrue(store.setMasterEnabled(true))
        val reopened = NotificationSettingsStore(context, preferencesName)
        assertEquals(NotificationSettingsState(true, choices), reopened.state.value)
        choices.forEach { assertTrue(reopened.state.value.enabled(it)) }
        assertEquals(7, preferences.all.size)
        assertTrue(preferences.all.values.all { it is Boolean })
    }

    @Test
    fun separatePreferenceNamesRemainIsolated() = runBlocking {
        val otherName = "notification_settings_test_${UUID.randomUUID()}"
        additionalPreferences += otherName
        val other = NotificationSettingsStore(context, otherName)
        assertTrue(store.setRuleEnabled(NotificationRule.FAVORITE_RECOVERED, true))
        assertTrue(store.setMasterEnabled(true))
        assertEquals(NotificationSettingsState(), other.state.value)
        assertTrue(other.setRuleEnabled(NotificationRule.PRICE_CHANGE, true))
        assertEquals(setOf(NotificationRule.FAVORITE_RECOVERED), store.state.value.enabledRules)
        assertEquals(
            NotificationSettingsState(false, setOf(NotificationRule.PRICE_CHANGE)),
            NotificationSettingsStore(context, otherName).state.value,
        )
    }

    @Test
    fun malformedMasterFailsClosedWithoutLosingValidRuleChoices() {
        val editor = preferences.edit().putString("master_enabled", "true")
        NotificationRule.entries.forEach { editor.putBoolean("rule_${it.name}", true) }
        assertTrue(editor.commit())
        val reopened = NotificationSettingsStore(context, preferencesName)
        assertFalse(reopened.state.value.masterEnabled)
        assertEquals(NotificationRule.entries.toSet(), reopened.state.value.enabledRules)
        NotificationRule.entries.forEach { assertFalse(reopened.state.value.enabled(it)) }
    }

    @Test
    fun malformedRulesFailClosedIndividuallyAndAreRepairable() = runBlocking {
        assertTrue(
            preferences.edit()
                .putBoolean("master_enabled", true)
                .putString("rule_BECAME_FREE", "true")
                .putInt("rule_QUOTA_LOW", 1)
                .putStringSet("rule_PENDING_CHECKIN", setOf("true"))
                .putLong("rule_FAVORITE_RECOVERED", 1L)
                .putFloat("rule_PRICE_CHANGE", 1f)
                .putBoolean("rule_OUTAGE_RECOVERY", true)
                .commit(),
        )
        val reopened = NotificationSettingsStore(context, preferencesName)
        assertEquals(setOf(NotificationRule.OUTAGE_RECOVERY), reopened.state.value.enabledRules)
        NotificationRule.entries.forEach {
            assertEquals(it == NotificationRule.OUTAGE_RECOVERY, reopened.state.value.enabled(it))
        }
        assertTrue(reopened.setRuleEnabled(NotificationRule.QUOTA_LOW, true))
        assertEquals(reopened.state.value, NotificationSettingsStore(context, preferencesName).state.value)
        assertTrue(preferences.all.values.all { it is Boolean })
    }

    @Test
    fun concurrentIndependentEditsDoNotLoseOtherRulesOrMasterChoice() = runBlocking {
        suspend fun editAll(enabled: Boolean) = coroutineScope {
            val start = CompletableDeferred<Unit>()
            val edits = NotificationRule.entries.map { rule ->
                async(Dispatchers.Default) {
                    start.await()
                    store.setRuleEnabled(rule, enabled)
                }
            } + async(Dispatchers.Default) {
                start.await()
                store.setMasterEnabled(enabled)
            }
            start.complete(Unit)
            assertTrue(edits.awaitAll().all { it })
        }
        editAll(true)
        assertEquals(NotificationSettingsState(true, NotificationRule.entries.toSet()), store.state.value)
        assertEquals(store.state.value, NotificationSettingsStore(context, preferencesName).state.value)
        editAll(false)
        assertEquals(NotificationSettingsState(), store.state.value)
        assertEquals(store.state.value, NotificationSettingsStore(context, preferencesName).state.value)
    }

    @Test
    fun failedCommitDoesNotPublishOrLeakItsInMemoryChoiceIntoNextSuccessfulEdit() = runBlocking {
        val fault = CommitFaultContext(context)
        val guarded = NotificationSettingsStore(fault, preferencesName)
        assertTrue(guarded.setRuleEnabled(NotificationRule.PENDING_CHECKIN, true))
        val lastGood = guarded.state.value
        fault.failNextCommit = true
        assertFalse(guarded.setRuleEnabled(NotificationRule.BECAME_FREE, true))
        assertEquals(lastGood, guarded.state.value)
        // Exercise Android's changed preference cache despite an unacknowledged commit.
        assertTrue(preferences.getBoolean("rule_BECAME_FREE", false))
        assertTrue(guarded.setMasterEnabled(true))
        assertEquals(NotificationSettingsState(true, lastGood.enabledRules), guarded.state.value)
        assertFalse(guarded.state.value.enabled(NotificationRule.BECAME_FREE))
        assertEquals(guarded.state.value, NotificationSettingsStore(context, preferencesName).state.value)
        assertTrue(fault.allCommitsOffMain)
    }

    @Test
    fun commitExceptionReturnsFalseAndPreservesLastConfirmedState() = runBlocking {
        val fault = CommitFaultContext(context)
        val guarded = NotificationSettingsStore(fault, preferencesName)
        assertTrue(guarded.setRuleEnabled(NotificationRule.QUOTA_LOW, true))
        val lastGood = guarded.state.value
        fault.nextException = IllegalStateException("Injected preference write failure")
        assertFalse(guarded.setMasterEnabled(true))
        assertEquals(lastGood, guarded.state.value)
        assertEquals(lastGood, NotificationSettingsStore(context, preferencesName).state.value)
        assertTrue(guarded.setMasterEnabled(true))
        assertTrue(guarded.state.value.enabled(NotificationRule.QUOTA_LOW))
    }

    @Test
    fun cancellationIsPropagatedRatherThanReportedAsWriteFailure() = runBlocking {
        val fault = CommitFaultContext(context)
        val guarded = NotificationSettingsStore(fault, preferencesName)
        fault.nextException = CancellationException("Injected cancellation")
        try {
            guarded.setMasterEnabled(true)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(NotificationSettingsState(), guarded.state.value)
            assertEquals(NotificationSettingsState(), NotificationSettingsStore(context, preferencesName).state.value)
        }
        assertTrue(guarded.setMasterEnabled(true))
    }

    /** Fault injection around real preferences; delegates storage and every normal commit to Android. */
    private class CommitFaultContext(base: Context) : ContextWrapper(base) {
        var failNextCommit = false
        var nextException: RuntimeException? = null
        var allCommitsOffMain = true

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val real = super.getSharedPreferences(name, mode)
            return object : SharedPreferences by real {
                override fun edit(): SharedPreferences.Editor {
                    val editor = real.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                            editor.putBoolean(key, value)
                            return this
                        }

                        override fun commit(): Boolean {
                            allCommitsOffMain = allCommitsOffMain && Looper.myLooper() != Looper.getMainLooper()
                            nextException?.let {
                                nextException = null
                                throw it
                            }
                            if (failNextCommit) {
                                failNextCommit = false
                                // Simulate Android's memory update before an unsuccessful disk acknowledgement.
                                editor.apply()
                                return false
                            }
                            return editor.commit()
                        }
                    }
                }
            }
        }
    }
}
