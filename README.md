# PeerIche

Independent mobile PeerTube client by Arctic Fox Apps. Package: `com.arcticfoxapps.peeriche`.

PeerIche is a continuation of PeerTubeTV by the same author. It is a standalone application and is not affiliated with Framasoft. It has no separate Shorts section.

## Current implementation

- Kotlin, Jetpack Compose, Material 3, Retrofit/Gson, Coil and Media3.
- Public home feed from the selected PeerTube server.
- Search through Sepia Search, PeerSeek, and up to five user configured PeerTube instances, with paging and de-duplication.
- Manual server entry and discovery through the public JoinPeerTube instance directory.
- Adaptive video grid and Home, Search, Subscriptions and Library navigation.
- Room-backed favorite and watch history lists; DataStore-backed server settings.
- Full screen destination with Media3 playback when a playable URL is provided.
- Dark theme and an original PeerIche vector launcher mark.

This is an early implementation. Subscriptions are currently a navigation placeholder; account sign-in, channel pages, persistent playlists, advanced search filters, background playback, Picture-in-Picture, and tablet/DeX validation remain to be implemented. Room and DataStore files are app-private and not shared with PeerTubeTV.

## Build

Requirements: Android Studio, JDK 17, Android SDK Platform 35.

```powershell
./gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Release builds are unsigned by default; configure a signing key locally in Android Studio. Never commit signing keys or local SDK configuration.

## Licenses

PeerIche is authored by Arctic Fox Apps and continues the author's PeerTubeTV project. Third-party libraries retain their own licenses; consult their upstream license files and include the final dependency notices before distribution. No credentials or signing keys are included.
