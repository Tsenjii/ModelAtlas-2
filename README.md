# ModelAtlas

Android application for monitoring AI model providers, accounts, quotas, pricing, model availability and inference performance.

Android (Kotlin, Jetpack Compose, Room) + Kotlin/JVM core. Includes a Playground, provider onboarding, New API adapters, dedicated provider integrations, model comparison, and LMSpeed discovery support.

## Build

Requirements: JDK 17, Android SDK 35, Gradle 8.14.4.

```sh
gradle :core:test :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

The Android GitHub Actions workflow also builds the instrumentation APK and runs emulator tests. Build status must be checked on the latest commit; migration does not imply tests have passed.

## Security

Do not commit provider credentials, OAuth tokens, local.properties, keystores, local account data, or private operational handoff logs. Configure personal accounts locally in the app. The repository uses new Git history and intentionally does not include the old private repository's historical commits.

## Project status

This repository is a public clean-history migration of the ModelAtlas codebase. The application is under active development; Playground multimodal UX, background synchronization, full crawler acceptance and assistant/reflection phases are still being worked on.

## License

No license has been granted for reuse or redistribution beyond permissions automatically supplied by the hosting service. An explicit license may be added later by the repository owner.
