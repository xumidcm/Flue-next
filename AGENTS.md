# Repository Guidelines

## Project Structure & Module Organization

Flue is a single-module Android launcher for watches. Code is under `app/src/main`, with Kotlin UI, state, and services in `com/example/wlauncher`, and watch-face runtimes and parsers in `com/flue/launcher`. JVM tests live in `app/src/test/kotlin` and `app/src/test/java`; fixtures are under `app/src/test/resources`. Android resources are in `app/src/main/res`. The browser watch-face agent is in `tools/watchface-agent-web`. Design notes and validation records are in `docs/`.

Entry points: `LauncherActivity`, `SettingsActivity`, and `LauncherViewModel`. Keep features within UI, service, and view-model boundaries.

## Build, Test, and Development Commands

Use PowerShell or Android Studio with JDK 21:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
.\gradlew.bat :app:assembleRelease --no-daemon --console=plain
.\gradlew.bat installDebug
```

The first command runs JVM/Robolectric tests; assemble tasks produce APKs; `installDebug` deploys to a connected device. For the web helper, run `python -W error::ResourceWarning -m unittest test_backend.py` and `npm test` from `tools/watchface-agent-web`.

## Coding Style & Naming Conventions

Follow Kotlin official style and the repository's four-space indentation. Use `PascalCase` for classes and composables, `camelCase` for functions and properties, and `UPPER_SNAKE_CASE` for constants. Keep package placement aligned with the existing feature directory and prefer small, focused Compose components. Store user-facing text in Android resources, including locale variants, rather than hard-coding strings.

## Testing Guidelines

Name JVM tests with the production subject plus `Test` (for example, `WatchStoreSearchFieldsTest`). Add focused tests beside the feature and reuse fixtures in `app/src/test/resources`. Run the affected test class during iteration, then run `:app:testDebugUnitTest` before opening a PR. Unit tests and Release builds do not replace device checks for notifications, watch faces, or animation behavior.

## Commit & Pull Request Guidelines

Use a short Simplified-Chinese commit subject that states the completed behavior, such as `修复表盘列表更新分页越界`. Keep one coherent topic per branch, normally `yzp/<topic>`, and stage reviewed paths explicitly. PRs should explain the user-visible change, list validation commands and device checks, link related issues, and include screenshots or recordings for UI changes. Before requesting review, run `git diff --check` and confirm CI's release build can run without committed signing secrets.

## Security & Configuration Tips

Do not commit passwords, keystores, generated APKs, logs, or local configuration. Release signing values may be supplied through `keystore.properties`, environment variables, or Gradle properties; keep those inputs local and verify the resulting APK separately.
