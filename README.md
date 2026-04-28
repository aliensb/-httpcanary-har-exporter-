# HttpCanary HAR Exporter

LSPosed/Xposed module for `com.guoshi.httpcanary`.

## What It Hooks

- Target package: `com.guoshi.httpcanary`
- Hook point: `com.guoshi.httpcanary.ui.others.HistoriesActivity#b(CaptureSession)`
- Behavior: replaces the history-session long-press delete dialog with:
  - `Export HAR`
  - `Delete`
- Hook point: `com.guoshi.httpcanary.ui.a.b/c#a(View, HttpCaptureRecord, int)`
- Behavior: splits the request line in record lists into two lines:
  - `METHOD host`
  - `/uri/path?query`
- Hook point: `com.guoshi.httpcanary.ui.content.HttpContentActivity#onCreate(Bundle)`
- Behavior: adds a default `Preview` tab to packet details. The preview shows request body first, then response body.

## Export Path

The module first writes to:

```text
/sdcard/Download/HttpCanaryHar/
```

If that directory cannot be created, it falls back to HttpCanary's app-specific external files directory:

```text
Android/data/com.guoshi.httpcanary/files/har/
```

## Build

Open this folder in Android Studio:

```text
C:\Temp\httpc\lsposed-har-exporter
```

Then build the `app` module. If you add a Gradle wrapper or have Gradle installed, run:

```text
gradle :app:assembleDebug
```

This workspace currently has no Gradle command installed, so I validated the Java sources with `javac` against the local Android SDK instead.

## Usage

1. Install the generated APK.
2. Enable it in LSPosed.
3. Scope it to `HttpCanary`.
4. Force stop and reopen HttpCanary.
5. Open `Histories`.
6. Long press a capture session.
7. Tap `Export HAR`.

The exported HAR contains all records listed in `CaptureSession.records`.
