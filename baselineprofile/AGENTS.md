# Baseline Profile Module

## Scope
- `baselineprofile/` is a `com.android.test` module that generates the Amaya cold-start baseline profile via `androidx.benchmark` macrobenchmark.
- It targets `:app` (`targetProjectPath = ":app"`) and feeds `baseline-prof.txt` into `:app` through the `androidx.baselineprofile` plugin applied on `:app`.

## Build
- Generate the profile on a connected device: `./gradlew :baselineprofile:connectedBenchmarkAndroidTest` (or `:app:generateReleaseBaselineProfile`).
- The generated `baseline-prof.txt` is bundled into `:app` as `assets/dexopt/baseline-prof` and AOT-compiled by ART at install time for profileable builds.

## Notes
- Baseline profiles only apply to non-debuggable builds. Use the `:app:benchmark` build type (`profileable = true`, `debuggable = false`) for the fast cold-start build. `installDebug` stays JIT-warmed by design (ART cannot AOT-compile debuggable APKs).
- `automaticGenerationDuringBuild` defaults to false, so normal `:app:assemble*` builds do not require a connected device.
