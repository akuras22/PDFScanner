# PDF Scanner

Android app for scanning documents, turning them into PDFs, and managing saved files on-device.

## What it does

- Scans documents using Google ML Kit.
- Creates PDF files and saves them to `Downloads/PDFScanner`.
- Includes history actions like open, share, delete, merge, and page reordering.
- Supports phone and tablet layouts.

## Requirements

- Android 14+ (min SDK 34)
- Google Play services (for ML Kit scanner)

## Install (from release)

1. Open the [Releases](https://github.com/akuras22/PDFScanner/releases) page.
2. Download the latest APK.
3. Install it on your Android device.

## Build from source

### Android Studio

1. Open the project in Android Studio.
2. Let Gradle sync finish.
3. Run the `app` configuration on a device/emulator.

### Command line

```bash
./gradlew assembleDebug
```

Debug APK output:

`app/build/outputs/apk/debug/app-debug.apk`

## How it works (high level)

- Uses ML Kit document scanning for capture, crop, and enhancement.
- Produces PDF output from scanned content.
- Stores files with Android MediaStore in `Downloads/PDFScanner`.
- Provides a simple history view for managing saved PDFs.

## Notes

- `compileSdk` / `targetSdk`: 35
- Release APKs are built by GitHub Actions on tags matching `v*`.
