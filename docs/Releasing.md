# Release Guide

This document describes how releases and beta builds are generated and published for **RideCommS (PeerSync)**.

---

## 1. Versioning System

App versions follow the format **`YEAR.MONTH.BUILD`** (e.g. `2026.8.00008`):
- **YEAR**: Current year (e.g., 2026)
- **MONTH**: Current month without padding (e.g., 8)
- **BUILD**: 5-digit zero-padded build counter (e.g., 00008)

Versioning state is persisted in `version.properties` at the root of the repository. Every successful `./gradlew assembleRelease` or `./gradlew assembleDebug` run automatically increments the build counter.

---

## 2. Building a Release APK

To compile the release binary for sharing:

```bash
# Windows
.\gradlew assembleRelease

# Linux / macOS
./gradlew assembleRelease
```

Once compilation completes, the signed APK file is produced at:
`composeApp/build/outputs/apk/release/composeApp-release.apk`

---

## 3. Creating & Publishing a Beta Release on GitHub

Follow these steps to post a new beta release on GitHub for testing:

1. **Commit code and updated `version.properties`**:
   ```bash
   git add composeApp/build.gradle.kts version.properties docs/Releasing.md
   git commit -m "chore: release setup and bump version for 2026.8.00008-beta.1"
   git push origin master
   ```

2. **Create and push a Git Tag**:
   ```bash
   git tag v2026.8.00008-beta.1
   git push origin v2026.8.00008-beta.1
   ```

3. **Publish on GitHub**:
   - Go to your GitHub repository: `https://github.com/WINDY-WINDWARD/PeerSync/releases`
   - Click **Draft a new release**.
   - Select tag **`v2026.8.00008-beta.1`**.
   - Release title: **`RideCommS Beta 1 (v2026.8.00008)`**.
   - Check **Set as a pre-release**.
   - Attach `composeApp/build/outputs/apk/release/composeApp-release.apk`.
   - Click **Publish release**.

---

## 4. Installing on Test Devices

Friends and beta testers can download `composeApp-release.apk` directly from the GitHub Release page onto their Android phones and open it to install.
