# Max — Autonomous Android AI Agent

Self-modifying on-device AI agent. Compose UI, dual local LLM slots via the
Nexa SDK, GitHub-mediated self-modification, biometric-locked owner auth, and a
12-rule immutable **Constitution**.

> **Build status:** ![Android CI](https://github.com/thepotatoninjahost/Max/actions/workflows/android.yml/badge.svg)

## Architecture

```
app/src/main/java/com/max/agent/
├── agency/       # Action parser + executor (gates risk via PermissionGate)
├── auth/         # Biometric OwnerAuth (FragmentActivity-backed)
├── core/         # MaxSystem + MaxIdentity (persisted custom prompt + rules)
├── github/       # Self-mod via GitHub Contents API — token in EncryptedSharedPreferences
├── installer/    # APK self-install via FileProvider
├── models/       # ModelManager — dual Nexa slots (everyday + coder)
├── network/      # NetworkGuard (policy flag) + NetworkStateMonitor
├── safety/       # Constitution, ActionLog, PermissionGate, Sandbox
├── scripting/    # Rhino JS sandbox for owner-authored scripts
├── selffix/      # FailureDetector, PatchGenerator, HotSwapper, SelfCorrectionMachine
├── system/       # SystemController + ResourceMonitor
├── terminal/     # TerminalEngine (capped output, 30s timeout)
├── ui/           # Compose UI + MainActivity + MaxAccessibilityService (bounded recursion)
└── voice/        # TTS + SpeechRecognizer (real gender selection)
```

## Build

CI builds the APK on every push via `.github/workflows/android.yml`.

Local build:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Toolchain

| Component        | Version |
|------------------|---------|
| JDK              | 17      |
| Kotlin           | 1.9.24  |
| Android Gradle   | 8.7.3   |
| Gradle           | 8.9     |
| compileSdk       | 35      |
| targetSdk        | 34      |
| minSdk           | 27      |
| Compose Compiler | 1.5.14  |
| Nexa SDK         | 0.0.24  |
| Rhino            | 1.7.15  |

## Security

- GitHub PAT stored in **EncryptedSharedPreferences** (AES256_GCM, Android Keystore master key).
- Owner identity gated by **BiometricPrompt**.
- All Medium/High risk agent actions routed through `PermissionGate.requestAndAwait`, with 2-minute expiry and approval owner-confirmation.
- Every action recorded in `ActionLog` (append-mostly; `purge` is auditable).
- See `ANALYSIS.md` for the full repair log.
