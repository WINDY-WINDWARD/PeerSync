# PeerSync

PeerSync is a real-time audio collaboration platform enabling synchronized playback and recording across multiple devices via Wi-Fi Direct.

## Versioning

Versions are Gradle-managed using the format **YEAR.MONTH.BUILD** (e.g., 2026.7.00001):
- **YEAR**: Full year (e.g., 2026)
- **MONTH**: Month without padding (e.g., 7 for July)
- **BUILD**: Zero-padded 5-digit counter (00001–99999) that increments on every successful build and resets to 00001 each month

### Version Increment Behavior

- **Same month**: Each successful build increments the BUILD counter by 1
- **New month**: First build of the month automatically resets BUILD to 00001 and bumps to 00002 on that build's success

Versioning state is persisted in `version.properties` at the repository root. This file is read by Gradle at configuration time and updated after every successful `assembleDebug` or `assembleRelease` task.

### Pushing to Master

When pushing or merging to `master`, your version **must be strictly higher** than master's current version. Never push a version that is equal or lower than master's.

If your branch is behind (e.g., you have 2026.7.00042 but master is at 2026.7.00057):
1. Merge or rebase master to pick up its `version.properties`
2. Run `./gradlew assembleDebug` (or `assembleRelease`) locally — this bumps your version to 2026.7.00058
3. Commit and push the updated `version.properties` along with your code

### APK Version Details

The APK's `versionCode` is computed to ensure monotonic ordering across month rollovers:
```
versionCode = (year % 100) × 10,000,000 + month × 100,000 + min(buildNumber, 99,999)
```

This keeps the version code under Android's 2.1 billion limit while maintaining total ordering.

### Workflow

1. **No git automation**: Version bumps are not triggered by git hooks or branch events — only by successful Gradle builds
2. **Commit frequently**: Always commit `version.properties` to git alongside version changes so the counter is synchronized across all team members and branches
3. **Verify in Settings**: After a build, open the app and navigate to **Settings → About** to confirm the current version
