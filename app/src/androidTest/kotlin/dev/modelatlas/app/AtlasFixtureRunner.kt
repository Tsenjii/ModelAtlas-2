package dev.modelatlas.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Existing tests own their databases and wire workloads. Factory defaults are
 * exercised explicitly by KiloFactoryDefaultsTest with the real installer. */
class AtlasFixtureRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
        return super.newApplication(cl, className, context).also { (it as? AtlasApplication)?.factoryDefaultsEnabled = false }
    }
}
