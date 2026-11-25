# Nova IPTV

Nova IPTV is an Android TV and mobile IPTV client. It contains no channels, playlists, trial content, ads, or expiring subscription. Add a playlist or provider that you are authorized to use.

## Current features

- Import M3U/M3U8 from device storage or an HTTP(S) URL.
- Play live streams with Media3 ExoPlayer, including stream-provided subtitle tracks through the player controls.
- Search, filter by playlist category, mark favorites, and keep a local recent-playback list.
- Connect Xtream-compatible providers for live TV, movies, series, and episode listings.
- Save multiple provider profiles with credentials encrypted by an Android Keystore key; delete profiles from the provider dialog.
- Import XMLTV EPG files and match listings using each M3U channel's `tvg-id`; Xtream live channels can load their short EPG automatically.
- Dark interface with Android TV launcher support and no third-party advertising or analytics SDK.

## Build and test

Requirements: JDK 17 and Android SDK platform 35. The Gradle Wrapper downloads and verifies Gradle 8.9 using its pinned SHA-256.

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs the same tests and build on pushes to `main`, pull requests, and manual dispatch, then uploads the APK as a workflow artifact.

## EPG and playlists

Import an XMLTV `.xml` file with the EPG button. M3U channels need a matching `tvg-id` attribute for schedule lookup. The guide is shown while the matched channel is playing. Provider URLs and credentials are entered by the user and are not included in this repository.
