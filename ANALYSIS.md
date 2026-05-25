# Project Max — Forensic Analysis & Repair Log

**Auditor:** Nemesis Protocol
**Date:** 2026-05-25
**Scope:** `app/src/main/java/com/max/agent/**` (~4,300 LOC Kotlin)
**Build:** ✅ Kotlin compile clean. ✅ Debug APK assembles (745 MB — Nexa native libs).

---

## 1. Defects Found & Fixed

| # | Severity | Module | Defect | Fix |
|---|----------|--------|--------|-----|
| 1 | 🔴 CRITICAL | `agency/Agency.kt` | `else -> ActionResult(action, true, "Action executed")` silently succeeded on every unhandled `ActionType`. Constitution-violating actions executed without any approval. `riskLevel` was hard-coded to `Low` regardless of LLM-emitted value. | Rewrote dispatcher. Every `ActionType` has a real implementation OR an explicit refusal. `parseAction` honors `risk` field. `executeAction` routes Medium/High risk through `PermissionGate.requestAndAwait` (2-min owner approval window). No silent stubs. |
| 2 | 🔴 CRITICAL | `agency/Agency.kt` | `WRITE_FILE` allowed paths to escape the vault. | Canonical-path prefix check against `maxVault.canonicalPath`. Same for `READ_FILE`/`LIST_DIR`. |
| 3 | 🔴 CRITICAL | `agency/Agency.kt` | Used `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOCUMENTS)` — broken on API 30+ scoped storage. | Switched to `context.getExternalFilesDir(null)` with `filesDir` fallback. |
| 4 | 🔴 CRITICAL | `safety/ActionLog.kt` | Docstring claimed cryptographic tamper detection — fabricated. `clearLog()` returned `false` (dead) while `forceClearLog()` silently wiped. | Removed false claim. Replaced with `purge(reason, requestedBy)` which writes a final audit entry before wiping; persistence errors surface as log entries instead of being swallowed. |
| 5 | 🔴 CRITICAL | `github/GithubEngine.kt` | GitHub PAT written to filesystem in cleartext under `filesDir/config/github.json`. | Migrated to `EncryptedSharedPreferences` (AES256_GCM via Keystore-backed `MasterKey`). Legacy file best-effort deleted on first configure. |
| 6 | 🟠 HIGH    | `safety/PermissionGate.kt` | No suspending API; agents had no way to actually await approval. | Added `Outcome` enum + `requestAndAwait(...)` that suspends on `_state.first { settled }` under `withTimeoutOrNull(2min)`. |
| 7 | 🟠 HIGH    | `ui/MaxApp.kt` | `generationJob = CoroutineScope(Dispatchers.IO).launch{}` leaked a scope per send. | Switched to `rememberCoroutineScope()` tied to the Composable. |
| 8 | 🟠 HIGH    | `ui/MaxApp.kt` | Forward reference: `permLauncher`'s callback referenced `sendMessage` declared below — compile error. | Added a `sendRef = remember { mutableStateOf<((String)->Unit)?>(null) }`. Launcher calls `sendRef.value?.invoke(...)`. `sendRef.value = ::sendMessage` set after declaration. |
| 9 | 🟠 HIGH    | `ui/MaxAccessibilityService.kt` | Unbounded recursion in `buildNodeTree`. Web-view / deep RecyclerView trees could OOM or stack-overflow. | Bounded recursion: `MAX_DEPTH = 50`, `MAX_NODES = 2000`, shared `budget: IntArray`. |
| 10 | 🟠 HIGH    | `ui/MainActivity.kt` | `ComponentActivity` incompatible with `BiometricPrompt` (needs `FragmentActivity`); MaxApp cast `LocalContext as? FragmentActivity` so biometrics never fired. | Switched to `androidx.fragment.app.FragmentActivity`. |
| 11 | 🟠 HIGH    | `voice/VoiceEngine.kt` | `setVoice(gender, pitch, rate)` silently ignored `gender`. | Real implementation: enumerates `tts.voices`, matches by gender token in name, prefers current locale. Wrapped in `runCatching` (some devices throw on voice listing). |
| 12 | 🟠 HIGH    | `models/ModelManager.kt` | Native `LlmWrapper.destroy()` crash could propagate during slot release. | Wrapped in `runCatching { }.onFailure { Log.e(...) }`. |
| 13 | 🟠 HIGH    | `core/MaxSystem.kt` (`MaxIdentity`) | `customPrompt` and `customRules` lived only in memory; lost on every process restart despite being addressable by the LLM via `ADD_RULE`/`MODIFY_SYSTEM_PROMPT`. | `init(filesDir)` loads from `config/identity.json`. `addRule`, `updatePrompt`, and new `clearRules` persist via gson. Failures logged, not silently swallowed. |
| 14 | 🟠 HIGH    | `selffix/PatchGenerator.kt` | `extractCode` fell back to the full LLM response when no ``` fence was found. Non-code prose was being saved as `.kt` patches. | Empty-string fallback. Caller already treats empty as "no patch". |
| 15 | 🟠 HIGH    | `selffix/SelfCorrectionMachine.kt` | "Permission denied" rule-based fix blindly prepended `sh ` to a command already running under `sh -c`. | Real fix: extracts first token, `chmod +x` with single-quote-escaped path, retries original command. |
| 16 | 🟡 MEDIUM  | `network/NetworkGuard.kt` | Docstring claimed app-wide network block. Reality: in-process boolean flag. | Honest doc: "policy flag, not kernel firewall." Lists what does/doesn't respect it. VpnService implementation noted as future work. |
| 17 | 🟡 MEDIUM  | `github/GithubEngine.kt` | Default branch was `"master"` in `Config` but `"main"` in `configure()` — silent inconsistency. | Normalized to `"main"` everywhere. |
| 18 | 🟡 MEDIUM  | `terminal/TerminalEngine.kt` | `MAX_OUTPUT_CHARS` was `private val` (instance field) with UPPER_SNAKE naming. | Promoted to `private const val` inside `companion object`. |
| 19 | 🟡 MEDIUM  | `agency/Agency.kt` | Per-action timeout was 2 s — too tight for real shell commands and network calls. | Bumped to 30 s; timeout result flagged `isFatal=true` to break the agent loop. |
| 20 | 🟡 MEDIUM  | `agency/Agency.kt` | `ActionType.EXECUTE_SCRIPT` used `return@when` — not valid Kotlin in a `when` expression. | Rewrote to nested `if/else`. |
| 21 | 🟢 LOW     | Various | Many `runCatching { }.getOrNull()` swallowed exceptions with zero diagnostics. | Where consequential, added `.onFailure { Log.e(...) }` so failures are at least logged. |

## 2. Build Infrastructure (Created From Scratch)

The original drop was source-only — no Gradle, no manifest, no resources, no
wrapper. Couldn't compile. Added:

- `build.gradle.kts` (root) — AGP 8.7.3 + Kotlin 1.9.24
- `app/build.gradle.kts` — compileSdk 35, minSdk 27, Compose BOM 2024.10.01, Compose compiler 1.5.14, full dep graph
- `settings.gradle.kts`
- `gradle.properties`
- `gradle/wrapper/gradle-wrapper.{properties,jar}` (Gradle 8.9)
- `gradlew` (canonical script from gradle v8.9.0)
- `app/src/main/AndroidManifest.xml` — full permissions (RECORD_AUDIO, REQUEST_INSTALL_PACKAGES, USE_BIOMETRIC, WRITE_SETTINGS, accessibility service, FileProvider, etc.)
- `app/src/main/res/xml/{file_paths,data_extraction_rules,accessibility_service_config}.xml`
- `app/src/main/res/values/{strings,themes}.xml`
- `app/src/main/res/drawable/ic_launcher_{foreground,background}.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (adaptive)
- `.github/workflows/android.yml` — JDK 17, Android SDK 35, lint + assembleDebug, uploads `max-debug-apk` artifact
- `.gitignore` — excludes `build/`, `.gradle/`, `local.properties`, keystores, etc.
- `README.md` + this file

## 3. Verification

Local toolchain build was run in the conversation sandbox:

```
> Task :app:compileDebugKotlin
BUILD SUCCESSFUL in 48s

> Task :app:assembleDebug
BUILD SUCCESSFUL in 4m 59s
app/build/outputs/apk/debug/app-debug.apk  (745 MB)
```

Warnings remaining are non-blocking: deprecated `WifiInfo.connectionInfo` (system API choice), unused parameter `requestedBy` in `requestAndAwait` (intentional — accepted for caller ergonomics), `Icons.Filled.Send` deprecated in favor of `AutoMirrored.Send` (cosmetic).

## 4. Outstanding (Future Work, Not Repaired)

These are documented for transparency. None block build or core function.

- **Cryptographic ActionLog tamper detection.** Add a Keystore-backed HMAC chain so log truncation is detectable.
- **True app-level NetworkGuard enforcement.** Requires a `VpnService` implementation.
- **Lint clean-up.** Three deprecated APIs in `NetworkStateMonitor.kt`, one in `MaxAccessibilityService.kt` recycle(), one in `MaxApp.kt` Icons.Send.
- **APK size.** 745 MB is driven by Nexa native libs for every chipset (~95 `.so` files). Recommend `abiFilters` (e.g. arm64-v8a only) or per-ABI splits in `app/build.gradle.kts` for releases.

---

## Summary

21 defects identified, 21 fixed. Build infrastructure created from scratch.
Local debug APK builds clean. CI pipeline configured for GitHub Actions.
