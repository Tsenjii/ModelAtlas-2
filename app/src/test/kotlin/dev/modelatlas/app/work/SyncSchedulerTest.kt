package dev.modelatlas.app.work

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * X-011 request 建構單測（不啟動真 WorkManager、不 enqueue）。
 * 只斷言 [SyncScheduler] 產出的 request 之結構與約束，確保骨架符合 §16 務實排程語意。
 */
class SyncSchedulerTest {

    @Test
    fun periodicRequestIsPeriodicWithConfiguredInterval() {
        val request = SyncScheduler.buildPeriodicRequest()
        assertEquals(
            TimeUnit.MINUTES.toMillis(SyncScheduler.PERIODIC_INTERVAL_MINUTES),
            request.workSpec.intervalDuration,
        )
    }

    @Test
    fun periodicRequestRequiresConnectedNetworkAndBatteryNotLow() {
        val constraints = SyncScheduler.buildPeriodicRequest().workSpec.constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow())
    }

    @Test
    fun constraintsDoNotRequireUnmeteredOrCharging() {
        val constraints = SyncScheduler.constraints()
        assertNotEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresCharging())
    }

    @Test
    fun periodicPolicyKeepsExistingWork() {
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, SyncScheduler.periodicPolicy())
    }

    @Test
    fun oneTimePolicyKeepsExistingWork() {
        assertEquals(ExistingWorkPolicy.KEEP, SyncScheduler.oneTimePolicy())
    }

    @Test
    fun oneTimeRequestIsNotPeriodicAndSharesConstraints() {
        val request = SyncScheduler.buildOneTimeRequest()
        assertEquals(0L, request.workSpec.intervalDuration)
        assertEquals(SyncScheduler.constraints(), request.workSpec.constraints)
    }

    @Test
    fun requestsCarrySyncTagForBothKinds() {
        assertTrue(SyncScheduler.TAG_SYNC in SyncScheduler.buildPeriodicRequest().tags)
        assertTrue(SyncScheduler.TAG_SYNC in SyncScheduler.buildOneTimeRequest().tags)
    }

    @Test
    fun repeatedBuildsProduceDistinctRequestIds() {
        val first = SyncScheduler.buildPeriodicRequest()
        val second = SyncScheduler.buildPeriodicRequest()
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun uniqueWorkNamesAreStableAndDistinct() {
        assertEquals("modelatlas.sync.periodic", SyncScheduler.UNIQUE_PERIODIC_WORK_NAME)
        assertEquals("modelatlas.sync.oneoff", SyncScheduler.UNIQUE_ONE_TIME_WORK_NAME)
        assertNotEquals(SyncScheduler.UNIQUE_PERIODIC_WORK_NAME, SyncScheduler.UNIQUE_ONE_TIME_WORK_NAME)
    }

    @Test
    fun periodicIntervalRespectsWorkManagerFloor() {
        assertTrue(
            "週期不得低於 WorkManager 15 分鐘下限",
            SyncScheduler.PERIODIC_INTERVAL_MINUTES >= 15L,
        )
    }
}
