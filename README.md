# PDF Scanner Android App

Starter Android app that scans documents, auto-crops/enhances them (via ML Kit), converts them to PDF, and saves PDFs on the user's device.

## Features

- Document scanning with edge detection and auto-crop.
- Automatic enhancement (color/contrast cleanup handled by scanner flow).
- PDF output generation.
- Save PDF to `Downloads/PDFScanner` on device.
- Built-in `History` screen listing previously saved PDFs.
- One-tap `Open` and `Delete` actions for every saved PDF.
- One-tap `Share` action for every saved PDF.
- Tablet-compatible adaptive UI (phone and tablet layouts).

## Tech Stack

- Kotlin
- Jetpack Compose
- Google ML Kit Document Scanner API (`play-services-mlkit-document-scanner`)
- MediaStore for scoped-storage save

## Project Structure

- `app/src/main/java/com/akuras/pdfscanner/MainActivity.kt`: scan flow + save to Downloads + adaptive UI.
- `app/src/main/java/com/akuras/pdfscanner/ui/theme/*`: Compose theme setup.
- `app/src/main/res/*`: manifest resources, themes, launcher icon assets.

## Build And Run

1. Open this folder in Android Studio.
2. Let Gradle sync.
3. Run the `app` configuration on a phone/tablet emulator or real device.

Notes:

- This app targets Android SDK 35 and min SDK 26.
- The scanner relies on Google Play services and works best on Play-enabled devices/emulators.

## How It Works

1. User taps `Scan Document`.
2. ML Kit scanner UI opens and handles detection, crop, and enhancement.
3. Scanner returns a PDF `Uri`.
4. App copies that PDF into MediaStore at `Downloads/PDFScanner/scan_YYYYMMDD_HHMMSS.pdf`.

## Tablet Support

- The app uses window size classes and switches to a two-pane layout on wider screens.

## Next Improvements

- PDF list/history screen (load from app-indexed records).
- Rename actions after save.
- Optional per-page image export.
- UI tests and instrumentation tests.