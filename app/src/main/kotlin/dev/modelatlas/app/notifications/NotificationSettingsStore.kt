package dev.modelatlas.app.notifications

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class NotificationRule {
    BECAME_FREE,
    QUOTA_LOW,
    PENDING_CHECKIN,
    FAVORITE_RECOVERED,
    PRICE_CHANGE,
    OUTAGE_RECOVERY,
}

data class NotificationSettingsState(
    val masterEnabled: Boolean = false,
    val enabledRules: Set<NotificationRule> = emptySet(),
) {
    fun enabled(rule: NotificationRule): Boolean = masterEnabled && rule in enabledRules
}

/**
 * Nonsecret notification choices (X-013 / PRODUCT_SPEC §16 / D-2026-09-30-005).
 * The application owns one instance; callers edit through this store, not the preferences.
 * State starts from saved choices and thereafter reflects only commits acknowledged as successful.
 */
class NotificationSettingsStore(
    context: Context,
    preferencesName: String = PREFS_NAME,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName, Context.MODE_PRIVATE,
    )
    private val writes = Mutex()
    private val mutableState = MutableStateFlow(readInitialState())
    val state: StateFlow<NotificationSettingsState> = mutableState.asStateFlow()

    suspend fun setMasterEnabled(enabled: Boolean): Boolean = update {
        it.copy(masterEnabled = enabled)
    }

    suspend fun setRuleEnabled(rule: NotificationRule, enabled: Boolean): Boolean = update {
        it.copy(enabledRules = if (enabled) it.enabledRules + rule else it.enabledRules - rule)
    }

    private suspend fun update(
        transform: (NotificationSettingsState) -> NotificationSettingsState,
    ): Boolean = withContext(Dispatchers.IO) {
        writes.withLock {
            currentCoroutineContext().ensureActive()
            val next = transform(mutableState.value)
            try {
                // Android can update its in-memory preferences even when commit returns false.
                // Rewrite all owned keys from the last confirmed state, never that failed cache.
                val editor = preferences.edit().putBoolean(MASTER_KEY, next.masterEnabled)
                NotificationRule.entries.forEach { rule ->
                    editor.putBoolean(ruleKey(rule), rule in next.enabledRules)
                }
                currentCoroutineContext().ensureActive()
                if (editor.commit()) {
                    mutableState.value = next
                    true
                } else {
                    false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun readInitialState(): NotificationSettingsState = try {
        // Inspect actual types: missing, malformed, or string "true" values never opt in.
        val saved = preferences.all
        NotificationSettingsState(
            masterEnabled = saved[MASTER_KEY] == true,
            enabledRules = NotificationRule.entries.filterTo(linkedSetOf()) {
                saved[ruleKey(it)] == true
            },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        NotificationSettingsState()
    }

    private fun ruleKey(rule: NotificationRule): String = "rule_${rule.name}"

    companion object {
        const val PREFS_NAME = "notification_settings"
        private const val MASTER_KEY = "master_enabled"
    }
}
