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

This is an early implementation. Local channel subscriptions load public channel videos; account sign-in, dedicated channel pages, persistent playlists, advanced search filters, Picture-in-Picture, and DeX validation remain to be implemented. Tablet layout has a responsive rail and grid but still needs broader device validation. Room and DataStore files are app-private and not shared with PeerTubeTV.

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

## In-app updates

Version 0.1.1 checks public GitHub Releases on launch, at most once every six hours after a successful check. Settings also has a manual check. Published numeric version tags (including preview releases) with a named PeerIche-VERSION.apk and SHA-256 digest or sidecar are supported. Beta/RC tags are ignored. Downloading requires confirmation. The app validates the checksum, package, higher version code and identical signing certificate before opening the Android installer. Android requires installation approval and may ask to allow installs from PeerIche. No GitHub token is stored in the app. Install 0.1.1 manually once to enable future checks.

## Background playback and captions (0.1.2)

Playback runs in a Media3 foreground service and continues when the display is switched off or another app is opened. The system media panel and Bluetooth headset buttons control play/pause; next/previous seek by ten seconds within the current video. Audio focus is handled by ExoPlayer; disconnecting headphones pauses playback. Closing the video with Back stops playback. Tapping the system media panel returns to the active video.

The player CC button allows selecting supported embedded/HLS tracks and PeerTube WebVTT captions, or disabling subtitles. A failed caption request does not block playback and has a retry action. Captions are only available when supplied by the video/server. Device verification of Bluetooth and screen-off behavior is still pending.
