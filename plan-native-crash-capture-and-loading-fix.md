# Plan: Native Crash Capture + Real Model-Load Diagnosis

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
