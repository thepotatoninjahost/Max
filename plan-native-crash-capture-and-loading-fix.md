# Plan: Native Crash Capture + Real Model-Load Diagnosis

## REVISED DIAGNOSIS (2026-06-23) — from master's screenshots Screenshot_20260623_065458/065508

**Evidence (read directly from the two screenshots, not inferred):**
- Screenshot 1 (System tab): model `Qwen_Qwen2.5-7B-Q4_K_M` (5.75 GB) present and assignable;
  `SYS_STATE: [ACTIVE]`, `NET: OFF`; a `RuntimeException: llm create failed` shown under COMPUTE CORES.
- Screenshot 2 (Log tab, ERROR LOG): `java.lang.RuntimeException: Llm create failed,
  error code: -129515192` thrown from `com.nexa.sdk.LlmWrapper` via `kotlinx.coroutines`.

**Decoded error code:** `-129515192` = unsigned `0xF847C148`. This is **NOT** any documented
Nexa error code (documented codes are small: `-100001` INVALID_INPUT, `-100201` MODEL_LOAD,
`-200101` LLM_GENERATION, `-300xxx`/`-400xxx`/`-500xxx`/`-600xxx` per
https://docs.nexa.ai/en/trouble-shooting/error-code). A 32-bit value this large is a **raw
native return value leaking through the Kotlin wrapper** — i.e. the native `Llm.create()`
failed and the SDK wrapped the raw int as `RuntimeException("Llm create failed, error code: …")`.

**This REVISES the original premise of this plan:**
1. **It is NOT a native SIGSEGV.** The process did not die — the exception was caught and
   rendered in the UI. Therefore the original Phase 2 premise ("Java handler can't see native
   SIGSEGV; need NativeCrashWatcher via logcat tombstones") was based on a wrong assumption.
   `NativeLogCapture` (already implemented) is the correct tool: it captures the native
   logcat LIVE during the attempt, and `appendDetailedLog` writes it to `load_diag.log`,
   which the Log tab already renders. **The real failure reason is in `load_diag.log`.**
2. **The original Phase 1 ("change device_id `dev0` → `null` for NPU") was WRONG and must
   NOT be applied.** Verified against the actual SDK source (`InputPluginBase.kt`):
   `enum DeviceIdValue { CPU(null), GPU("gpu"), NPU("dev0") }` and `LlmCreateInput.kt` says
   "When using the CPU_GPU plugin … you can use either GPU or NPU." So for the `cpu_gpu`
   plugin, `device_id = "dev0"` IS the correct NPU value. The current code
   (`Triple("NPU","dev0",999)`) is correct. Applying the old Phase 1 would have broken NPU.
3. **`tokenizer_path = null` is correct** — it is the SDK default (`val tokenizer_path: String? = null`).
   The earlier ANALYSIS.md defect #29 ("required-ish, use \"\"") was over-cautious; null is fine.

**Known-issue confirmation:** GitHub `qualcomm/nexa-sdk` issue #864
("Failed to load OmniNeural-4B-Mobile on Samsung S25 Ultra … Model create() failed error
code xxxx … The same goes with other models that are intended for mobile NPU") is the SAME
device class (Snapdragon 8 Elite) and SAME symptom. It is OPEN and unresolved (assigned to
Nexa engineer zhiyuan8). So this is a known Nexa-on-S25 failure mode, not a master error.

**New leading suspects (to be confirmed from `load_diag.log`, NOT guessed):**
- **A. NPU path fields empty.** `ModelConfig` has NPU-specific fields
  (`system_library_path`, `backend_library_path`, `extension_library_path`,
  `config_file_path`, `embedded_tokens_path`, `npu_lib_folder_path` [doc default =
  `ApplicationInfo.nativeLibraryDir`], `npu_model_folder_path`). All are `""`/`null` in the
  current `ModelConfig(...)` construction. If the SDK does not auto-resolve `nativeLibraryDir`
  when `npu_lib_folder_path` is null, OR the AAR did not ship `libnexa_plugin_npu.so` /
  the QNN HTP libs in `lib/arm64-v8a/`, the NPU attempt cannot locate the runtime → garbage
  return. (This is the original plan Question #3.)
- **B. Genuine memory pressure on the 7B.** Q4_K_M 7B ≈ 5.75 GB + ~20% KV/context ≈ 6.9 GB.
  On a 12 GB S25 with Android + app overhead, available RAM may be only ~5–6 GB. The Java
  pre-load guard may pass (if it reads >6.9 GB free) but native mmap/alloc of the GGUF can
  still fail at the C++ level → raw error code. A smaller model isolates this.
- **C. GGUF + Snapdragon 8 Elite NPU mismatch.** NPU on the 8 Elite is tuned for Nexa's
  `.nexa`/OmniNeural models; a plain community `.gguf` (Qwen2.5-7B) may not have a valid
  QNN-compiled graph, so the NPU attempt fails and (per the code's own comment) "can
  corrupt the QNN runtime state, poisoning subsequent CPU/GPU fallbacks." If `load_diag.log`
  shows the NPU attempt failing first and then GPU/CPU also failing, suspect C poisoning B.

**DECISIVE NEXT DIAGNOSTIC (no code change yet — per this plan's own rule #3):**
1. **Send the contents of `load_diag.log`** from the Log tab (the per-attempt NPU/GPU/CPU
   native logcat block titled e.g. "NPU LOAD FAILED: … --- Native logcat ---"). This is the
   single most valuable artifact — it names the failing .so / function / reason.
2. **Binary isolation test:** try loading a SMALL `.gguf` first (e.g. Qwen2.5-1.5B-Q4_K_M,
   ~1 GB, or 3B ~2 GB) into the PRIMARY slot.
   - If the small model LOADS → the 7B failure is memory/NPU-specific (suspects A/B/C scale).
   - If the small model FAILS with the SAME raw error code → it is a plugin/SDK/NPU-runtime
     issue independent of size (suspect A or a shipped-libs problem).
3. (Original Question #3, still valid): does `libnexa_plugin_npu.so` exist inside the APK's
   `lib/arm64-v8a/`? Checkable by unzipping the APK.

**What is now DEPRIORITIZED (not wrong, just not the blocker):**
- Original Phase 2 NativeCrashWatcher: the crash is caught, not a SIGSEGV. Keep
  `NativeLogCapture` (already in place) as the capture mechanism. A tombstone reader is only
  needed if a future attempt ever produces an actual `Fatal signal 11` in logcat.
- Original Phase 4 Downloads-folder button: unrelated to the load failure; the model file IS
  being found (screenshot 1 lists it). Defer.

> STATUS: PROPOSED — awaiting master approval. No code changes until approved.
> AUDIT METHOD: code-degunker 27-pattern checklist + ground-truth comparison against
> Nexa SDK 0.0.24 source (`/tmp/nexa_src/extracted/`, re-extracted from Maven Central sources jar).

## Open Questions (need master input)

1. **Can you run `adb logcat` on your S25 after a crash and paste the lines containing
   `libc`, `DEBUG`, or `Fatal signal`?** This is the single most valuable diagnostic. A
   native SIGSEGV writes a full backtrace to logcat that my Java crash handler cannot see.
   Without it, I'm fixing blind. With it, I can pinpoint the exact .so and function.
2. **When the crash happens, does the Log tab show ANY entry in `crash.log`?** If yes →
   the SDK init failed (plugins missing). If no → it's a native crash after successful init.
3. **Does the Nexa AAR in your build actually contain `libnexa_plugin_npu.so`?** I can't
   inspect the AAR on your device. You can check by unzipping the APK and looking in
   `lib/arm64-v8a/` for `libnexa_plugin_npu.so`. If it's absent, NPU loading is impossible.

---

## Phase 1: Fix the wrong `device_id` (CRITICAL — my last "fix" was a guess and it's wrong)

**Affected files:** `app/src/main/java/com/max/agent/models/ModelManager.kt`

**Root cause (verified from SDK source `LlmCreateInput.kt`):**
```kotlin
/**
 * Device to use for the model, NULL for default device.
 * When using the [PluginIdValue.CPU_GPU] plugin, The default value is [DeviceIdValue.CPU],
 * you can use either the [DeviceIdValue.GPU] or the [DeviceIdValue.NPU].
 */
override val device_id: String?= null,
```

The `device_id` parameter is ONLY meaningful for the `cpu_gpu` plugin (to select CPU vs
GPU sub-device). For the `npu` plugin, it must be `null` (default device).

My last PR changed it from `""` to `"dev0"` — both are wrong. There is no `"dev0"` device
in the SDK. Passing a bogus device_id to the NPU plugin's native code is a plausible
crash trigger: the C++ side tries to resolve a device that doesn't exist.

**Changes:**
- NPU attempt: `device_id = null` (was `"dev0"`)
- CPU/GPU fallback: `device_id = "gpu"` (correct — GPU sub-device of cpu_gpu plugin)

---

## Phase 2: Native crash capture (the real reason crash logs don't work)

**Affected files:**
- NEW: `app/src/main/java/com/max/agent/safety/NativeCrashWatcher.kt`
- `app/src/main/java/com/max/agent/MaxApplication.kt` (start watcher in onCreate)
- `app/src/main/java/com/max/agent/ui/MaxApp.kt` (surface native crash log in Log tab)

**Root cause (architectural, not a guess):**
My `Thread.setDefaultUncaughtExceptionHandler` in MainActivity only catches **Java/Kotlin
exceptions**. A native crash (SIGSEGV, SIGABRT) from JNI — which is what happens when
`Llm.create()` hits a bad NPU state — kills the process at the OS level. The JVM's
exception handler **never fires**. That's why the crash log is empty.

Android writes native crash backtraces to **logcat** with tags `libc` (`Fatal signal N`)
and `DEBUG` (the full tombstone with backtrace). These persist in the logcat ring buffer
across the crash + restart.

**Solution — `NativeCrashWatcher`:**
A small class that, in `MaxApplication.onCreate`, spawns a `logcat` subprocess filtering
for native crash markers and appends matches to `filesDir/crash.log`:

```kotlin
// Pseudocode — real implementation in the file
val process = Runtime.getRuntime().exec(arrayOf(
    "logcat",
    "-v", "threadtime",
    "libc:E",      // "Fatal signal 11 (SIGSEGV)"
    "DEBUG:I",     // full native backtrace tombstone
    "*:S"          // silence everything else
))
// Read stdout line-by-line, append to crash.log with timestamp
```

On next app start, `MaxApplication` reads `crash.log` and exposes its contents via a
StateFlow that the Log tab renders. The user sees the actual native backtrace —
function name, .so library, offset — instead of a blank log.

**Why this is the right approach (not a guess):**
- It's how Bugsnag/Crashlytics capture native crashes on Android (they hook logcat +
  signal handlers via NDK; we use the lighter logcat-only path).
- It requires no NDK, no new dependencies, no ABI-specific .so files.
- logcat is readable by the app itself on the same process (no special permissions needed
  for reading your own process's logs pre-Android-16; on 16+ we fall back to reading the
  tombstone file at `/data/tombstones/` if accessible, otherwise surface a clear error).

**Risk:** On Android 16+, `logcat` access from apps is restricted. Fallback: attempt to
read `/data/tombstones/tombstone_*` (may need root; if unreadable, surface a clear message
telling the master to run `adb logcat` manually). This is honest about the limitation
rather than silently failing.

---

## Phase 3: Surface SDK init status in the UI (so you can SEE if init failed)

**Affected files:**
- `app/src/main/java/com/max/agent/ui/MaxApp.kt` (Models tab header)

**Root cause:**
`MaxApplication` sets `sdkInitialized` / `sdkInitError` but the UI never reads them. If
the SDK init fails (e.g., `libnexa_plugin_npu.so` missing from the APK), the failure is
written to `crash.log` but you'd never know to look there — and the Log tab only shows
the ActionLog, not crash.log.

**Changes:**
- Models tab: add a status line at the top showing `SDK: READY` (green) or
  `SDK: FAILED — <reason>` (red), reading from `MaxApplication.sdkInitialized` /
  `sdkInitError`. This makes init failures immediately visible without digging.

---

## Phase 4: Fix the Downloads-folder button (investigate, don't guess)

**Affected files:** `app/src/main/java/com/max/agent/ui/MaxApp.kt`

**Current state:** The `folderLauncher` uses `ActivityResultContracts.OpenDocumentTree()`
and calls `scanAndImportFromTree`. The user reports it "doesn't work, never has."

**Honest assessment:** I don't yet know WHY it doesn't work. Possible causes:
1. The launcher callback isn't being invoked (lifecycle issue)
2. `takePersistableUriPermission` is failing
3. The tree walk finds no .gguf (the user's models are in Downloads, which on Android 15
   is a special location — `OpenDocumentTree` returns a tree URI, but the Downloads
   folder may require the user to navigate to it manually in the picker)

**Approach:** Add logging at each step of the callback (launcher fired → URI received →
permission taken → walking → files found → imported count). Surface this in the
`importStatus` text that's already on the Models tab. This makes the failure point
visible instead of silent. ONLY after seeing where it breaks will I propose the actual
fix — not before.

---

## What I will NOT do (lessons from this conversation)

1. **No more guessing on device_id values.** I changed it `""` → `"dev0"` → both wrong.
   The SDK source says `null` for NPU. That's the only correct value.
2. **No more "I think the crash is X" without evidence.** Phase 2 captures the actual
   native backtrace. Until I have it, I fix only what the SDK source proves is wrong.
3. **No more deploying without a plan.** This file exists. You approve before I code.
4. **No more double-CI-watching.** One PR build, merge, done.

---

## Unit tests

Per plan-code-changes conventions, tests are inlined with phases:

- **Phase 1:** No unit test possible (requires a real device + NPU). Verified by:
  compiling (CI) + master reporting whether the crash still occurs.
- **Phase 2:** `NativeCrashWatcherTest` — feed a fake logcat stream containing
  `libc: Fatal signal 11` and assert it's written to the log file. Pure JVM test,
  no device needed. New file: `app/src/test/java/com/max/agent/safety/NativeCrashWatcherTest.kt`
- **Phase 3:** No unit test (UI rendering). Verified by master screenshot.
- **Phase 4:** No unit test (requires SAF picker interaction). Verified by master
  reporting the importStatus text after pressing the button.

---

## Severity summary (code-degunker audit of ModelManager.kt)

| # | Pattern | Severity | Finding |
|---|---------|----------|---------|
| 1 | Hallucinated API value | 🔴 CRITICAL | `device_id = "dev0"` for NPU — no such device in SDK |
| 2 | Missing observability | 🔴 CRITICAL | Java UncaughtExceptionHandler can't catch native SIGSEGV |
| 3 | Silent failure | 🟠 HIGH | SDK init failure written to file but never shown in UI |
| 4 | Happy-path blindness | 🟠 HIGH | Downloads button has no step-level logging; fails silently |
| 5 | Over-defensive code | 🟡 MINOR | `runCatching` around `stopStream`/`close` is correct here (native calls) — keep |
