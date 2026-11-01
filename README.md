# PeerIche

Independent mobile PeerTube client by Arctic Fox Apps. Package: `com.arcticfoxapps.peeriche`.

PeerIche is a continuation of PeerTubeTV by the same author. It is a standalone application and is not affiliated with Framasoft. It has no separate Shorts section.

## Current implementation

- Kotlin, Jetpack Compose, Material 3, Retrofit/Gson, Coil and Media3.
- Public home feed from the selected PeerTube server.
- Search through Sepia Search, PeerSeek, and up to five user configured PeerTube instances, with paging and de-duplication.
- Manual server entry and discovery through the public JoinPeerTube instance directory.
- Adaptive video grid and Home, Search, Subscriptions and Library navigation, with a tablet navigation rail.
- Room-backed favorites, watch history and channel subscriptions; DataStore-backed server and theme settings.
- Full screen destination with Media3 playback when a playable URL is provided.
- Graphite, Light and blue Aurora themes, plus automatic Android theme switching.
- Compact header and an original Graphite vector launcher mark.

This is an early implementation. Local channel subscriptions load public channel videos; account sign-in, dedicated channel pages, persistent playlists, advanced search filters, background playback, Picture-in-Picture, and DeX validation remain to be implemented. Tablet layout has a responsive rail and grid but still needs broader device validation. Room and DataStore files are app-private and not shared with PeerTubeTV.

## Build

Requirements: Android Studio, JDK 17, Android SDK Platform 35.

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Release signing reads PEERICHE_KEYSTORE, PEERICHE_STORE_PASSWORD and PEERICHE_KEY_PASSWORD from the local environment (alias: peeriche-release). Without these variables release builds are unsigned. Never commit signing keys, passwords or local SDK configuration.

Build the unsigned release variant with `.\gradlew.bat assembleRelease`; the APK is written to `app/build/outputs/apk/release/app-release.apk (signed) or app-release-unsigned.apk (unsigned)`.

## Licenses

PeerIche is authored by Arctic Fox Apps and continues the author's PeerTubeTV project. Third-party libraries retain their own licenses; consult their upstream license files and include the final dependency notices before distribution. No credentials or signing keys are included.

## Download

Signed APKs are published on the [GitHub Releases page](https://github.com/alex-rek/PeerIche/releases). Android 6.0 or later is required. This is an early release; see the limitations above.
