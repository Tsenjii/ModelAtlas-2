package dev.modelatlas.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner

/** Existing tests own their databases and wire workloads. Factory defaults are
 * exercised explicitly by KiloFactoryDefaultsTest with the real installer. */
class AtlasFixtureRunner : AndroidJUnitRunner() {
    override fun onStart() {
        val expected = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("expectedMaxHeapBytes")?.toLong()
        val actual = Runtime.getRuntime().maxMemory()
        Log.i("ModelAtlasTestRuntime", "maxHeapBytes=$actual expectedMaxHeapBytes=$expected")
        targetContext.cacheDir.resolve("instrumentation-runtime.json").writeText(
            """{"max_heap_bytes":$actual,"expected_max_heap_bytes":$expected}"""
        )
        if (expected != null) check(actual == expected) {
            "Emulator ART heap differs from its configured size: $actual != $expected"
        }
        super.onStart()
    }

    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
        return super.newApplication(cl, className, context).also { (it as? AtlasApplication)?.factoryDefaultsEnabled = false }
    }
}
