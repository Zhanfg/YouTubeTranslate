# YouTubeTranslate

YouTubeTranslate is an experimental LSPosed module for automatically translating comments in the official YouTube Android app.

## Current milestone: V0.1 Hook Probe

This branch does **not** translate comments yet. V0.1 establishes the runtime hook path and records enough UI/binding context to identify a stable YouTube comment renderer/binder before the translation layer is added.

### Target

- Package: `com.google.android.youtube`
- Modern Xposed API: 102
- Minimum Android: 8.0 (API 26)
- Build/target SDK: Android 16 (API 36)
- Build Tools: 36.0.0
- Build JDK: 21

### What V0.1 does

- Loads only in the YouTube package scope.
- Hooks `android.widget.TextView#setText(CharSequence, BufferType)` inside the YouTube process.
- Deduplicates repeated binds per `TextView`.
- Emits bounded probe records containing text, resource ID, view class, parent chain and a compact caller stack.
- Stops detailed probing after a fixed event budget to reduce performance impact.

### Build

GitHub Actions builds the debug APK automatically. Locally, use Gradle 9.5.1 with JDK 21 and Android SDK 36:

```bash
gradle :app:assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Test on device

1. Install the debug APK.
2. Enable **YouTubeTranslate** in LSPosed.
3. Confirm the scope contains only **YouTube**.
4. Force-stop YouTube and reopen it.
5. Open a video and expand the comments section.
6. Capture logs:

```bash
adb logcat -c
adb logcat -s YouTubeTranslate
```

Look for records beginning with `PROBE #`.

> V0.1 intentionally uses a broad `TextView` probe. It is a temporary discovery mechanism, not the final hook. The next milestone will replace it with a YouTube-specific renderer/binder hook, then add DexKit-based compatibility discovery.
