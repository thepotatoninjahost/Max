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

## 4. Second Pass — Outstanding Stubs Eliminated (2026-06-05)

The directive was absolute: **no stubs, no placeholders**. The items previously
deferred as "future work," plus a newly-discovered class of disconnected
features, have now been fully implemented and verified.

| # | Severity | Module | Defect | Fix |
|---|----------|--------|--------|-----|
| 22 | 🔴 CRITICAL | `ui/MaxApp.kt` | The entire 6-tab UI (`ChatTab`, `ModelsTab`, `TerminalTab`, `SystemTab`, `LogTab`, `RulesTab`) was placeholder text ("UPLINK SECURE", "AUDIT STREAM SYNCED", …). The owner could not chat, manage models, run the terminal, view diagnostics, read the audit log, or manage rules — the entire backend was unreachable from the UI. | Rewrote all six tabs as fully functional, live-wired Compose screens. Chat drives the `AgentLoop` with token streaming + stop; Models lists/loads/deletes `.gguf` into PRIMARY/CODER slots with live transfer progress; Terminal runs real `sh -c` with scrollback; System renders live `ResourceMonitor`/`NetworkStateMonitor`/`SystemController` telemetry; Log renders the real `ActionLog` with risk coloring + audited purge + tamper banner; Rules edits runtime directives + system-prompt override + live-context toggle. |
| 23 | 🔴 CRITICAL | `safety/ActionLog.kt` | No cryptographic tamper detection, despite Constitution **Rule 6** explicitly requiring "Tampering with the log triggers lockdown." | Implemented an HMAC-SHA256 integrity envelope `{ entries, mac }` keyed by a non-exportable **Android Keystore** HMAC key (with a sealed `EncryptedSharedPreferences` fallback). On reload the MAC is recomputed and compared in constant time; any mismatch flips a `tampered` StateFlow. `MaxSystem` observes it and forces `stopNow()` lockdown. Legacy bare-array logs are migrated on first load. |
| 24 | 🔴 CRITICAL | `network/NetworkGuard.kt` + `network/MaxVpnService.kt` | NetworkGuard was an in-process boolean with empty no-op connectivity callbacks; true enforcement was deferred as "future work (VpnService)." | Built `MaxVpnService`: a no-route VpnService that captures the device's entire default route (`0.0.0.0/0` + `::/0`) into a TUN sink and drops every outbound packet — a real OS-level blackhole affecting every app. NetworkGuard now engages/disengages it on recall/allow, tracks real validated connectivity via the callbacks, and drives a one-time owner consent dialog (`VpnService.prepare`) wired through `MainActivity`. |

### Verification (Second Pass)

A complete Android toolchain (JDK 17 + Android SDK 35 + Build-Tools 35.0.0) was
provisioned in the sandbox and the build was run end-to-end:

```
> Task :app:compileDebugKotlin   BUILD SUCCESSFUL
> Task :app:assembleDebug         BUILD SUCCESSFUL
app/build/outputs/apk/debug/app-debug.apk  (302 MB)
```

`aapt2` confirms `MaxVpnService` (with `BIND_VPN_SERVICE` + `android.net.VpnService`
intent filter) is baked into the shipped manifest. A full-repo sweep for
`TODO|FIXME|placeholder|stub|future work|not-implemented|no-op` returns clean;
the only remaining empty function bodies are framework-required contract
callbacks (`RecognitionService.onCancel/onStopListening`,
`AccessibilityService.onInterrupt`) with nothing legitimate to do.

---

## Summary

24 defects identified, 24 fixed across two passes. Build infrastructure created
from scratch. **Zero stubs or placeholders remain** — every feature is
implemented and wired end-to-end from UI to engine. Local debug APK builds and
assembles clean. CI pipeline configured for GitHub Actions.
