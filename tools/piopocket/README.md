# PIO Pocket — self-contained Android build

This build uses GitHub Actions to build a debug APK (Android SDK 35, AGP 8.9.2, Gradle 8.13, Java 17).
The app provides GitHub-based PlatformIO remote build dispatch, status checks, editing source files and platformio.ini with SHA conflict control, and USB-OTG serial monitor.
The app does NOT yet flash firmware via USB. Use the full original PIO Pocket source archive separately for the experimental flasher. GitHub tokens are not persisted or logged by the app.
The target firmware project must contain a workflow at `.github/workflows/pio-mobile.yml` with an `environment` workflow_dispatch input.

Build: Actions > Build PIO Pocket APK > Run workflow > artifact `PIOPocket-debug-APK`.
