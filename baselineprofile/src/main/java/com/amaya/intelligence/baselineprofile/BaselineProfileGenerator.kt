package com.amaya.intelligence.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the Amaya cold-start baseline profile by exercising the startup path:
 * Compose runtime, AmayaTheme, NavHost, and first ChatScreen composition.
 *
 * The captured hot methods (Compose snapshot machinery, theme, navigation, first
 * frame) are exactly the ones that run interpreted+JIT on a debuggable build and
 * cause the reported ~5s post-splash jank. AOT-compiling them at install removes it.
 *
 * Run on a connected device:
 *   ./gradlew :app:generateReleaseBaselineProfile
 *   # or directly:
 *   ./gradlew :baselineprofile:connectedBenchmarkAndroidTest
 *
 * Output is bundled into :app as assets/dexopt/baseline-prof and AOT-compiled by
 * ART at install time for profileable (benchmark/release) builds.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = "com.amaya.intelligence",
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
        // Let first composition + startup work settle so hot paths are captured.
        device.waitForIdle()
        Thread.sleep(3_000)
    }
}
