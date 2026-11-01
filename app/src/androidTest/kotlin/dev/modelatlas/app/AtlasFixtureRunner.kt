package dev.modelatlas.app

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner

/** Existing tests own their databases and wire workloads. Factory defaults are
 * exercised explicitly by KiloFactoryDefaultsTest with the real installer. */
class AtlasFixtureRunner : AndroidJUnitRunner() {
    private var expectedMaxHeapBytes: Long? = null
    private fun recordHeap(stage: String): Long {
        val actual = Runtime.getRuntime().maxMemory()
        Log.i("ModelAtlasTestRuntime", """MODELATLAS_TEST_RUNTIME {"stage":"$stage","pid":${android.os.Process.myPid()},"max_heap_bytes":$actual,"expected_max_heap_bytes":$expectedMaxHeapBytes}""")
        return actual
    }

    override fun onStart() {
        expectedMaxHeapBytes = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("expectedMaxHeapBytes")?.toLong()
        val expected = expectedMaxHeapBytes
        val actual = recordHeap("start")
        if (expected != null) check(actual == expected) {
            "Emulator ART heap differs from its configured size: $actual != $expected"
        }
        super.onStart()
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        recordHeap("finish")
        super.finish(resultCode, results)
    }

    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
        return super.newApplication(cl, className, context).also { (it as? AtlasApplication)?.factoryDefaultsEnabled = false }
    }
}
