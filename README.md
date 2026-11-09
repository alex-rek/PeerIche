# PeerIche

Independent mobile PeerTube client by Arctic Fox Apps. Package: `com.arcticfoxapps.peeriche`.

PeerIche is a continuation of PeerTubeTV by the same author. It is a standalone application and is not affiliated with Framasoft. It has no separate Shorts section.

## Current implementation

- Kotlin, Jetpack Compose, Material 3, Retrofit/Gson, Coil and Media3.
- Public home feed from the selected PeerTube server.
- Search through Sepia Search, PeerSeek, and up to five user configured PeerTube instances, with paging and de-duplication.
- Manual server entry and discovery through the public JoinPeerTube instance directory.
- Adaptive video grid and Home, Search, Subscriptions and You navigation, with a tablet navigation rail.
- Room-backed favorites, watch history and channel subscriptions; DataStore-backed server and theme settings.
- Full screen destination with Media3 playback when a playable URL is provided.
- Graphite, Light and blue Aurora themes, plus automatic Android theme switching.
- Compact header and an original Graphite vector launcher mark.
- Watch page with collapsible description, channel subscription, public comment threads and Android sharing.
- You page with PeerTube OAuth account sign-in, two-factor codes, multiple accounts, followed channels, favorites, history and settings. Tokens use Android Keystore AES-GCM; passwords are not saved.
- Server account subscriptions and their paginated feed, account channels, video ratings and comment/reply submission. The subscription source selector keeps account subscriptions separate from subscriptions saved on this device. Remote videos are resolved on the account server before authenticated actions.
- Your videos for signed-in accounts, resumable background file upload with optional cover and quota checks, plus creation of live streams and retrieval of OBS connection settings.
- Your videos includes thumbnails and in-app playback of published videos and active lives, including private items on the account server. Restricted playback uses temporary in-memory file tokens and stops on logout or account switching.
- Signed-in Create (+) navigation with Upload, Broadcast and Channel settings modes. Phone camera broadcasting uses H.264/AAC over RTMP/RTMPS, a foreground service, front/back camera switching, microphone silence, image slate, orientation and supported torch control. Guests retain the Subscriptions button.
- Picture-in-Picture on Android 8+, including automatic entry while playing when leaving the app.
- The player PiP button opens a draggable video window over the previous in-app page; leaving Android opens system PiP. Comments follow the existing metadata translation setting and language.

This is an early implementation. Local channel subscriptions load public channel videos; dedicated channel pages, persistent playlists, advanced search filters, and DeX validation remain to be implemented. Tablet layout has a responsive rail and grid but still needs broader device validation. Room and DataStore files are app-private and not shared with PeerTubeTV.

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

## Tablet layout (0.1.3)

Tablet windows use a navigation sidebar and two or three video columns. The watch page places recommendations beside the player when sufficient width is available; narrower tablet windows stack recommendations below. Tablet fullscreen hides app chrome. Browsing while watching keeps playback in a bottom mini-player with play/pause, progress and close controls. Phone navigation and player layout remain unchanged. All three themes use the same adaptive structure.

## Mobile feed navigation (0.1.4)

Pull down from the top of the Home video grid to refresh the full selection. Home, Search and Subscriptions retain their own grid position and pixel offset while a video is open. New search text resets search scroll to the top; simply closing a video keeps the previous position. Refreshing is guarded against concurrent duplicate requests and its indicator stops when loading finishes or fails.

## Player controls (0.1.5)

The player exposes a fullscreen enter/exit icon in its lower-right controls on phone and tablet. Tablet watch pages no longer have top Back/Full screen links. Their action buttons below the video are ordered Back, Subscribe/Unsubscribe, Favorites, Subtitles, Full screen, wrapping when space is limited. Subtitles opens track selection (including Off); videos without text tracks show an availability message.

On phones the fullscreen button expands playback in the current orientation; rotating the phone controls portrait/landscape. Tablet classification also checks the smallest window dimension, so rotating a phone does not switch it into the tablet layout.

## Scrolling feed panels (0.1.6)

Mobile video grids hide the top controls upward and bottom navigation downward when scrolling into the feed. Reversing the scroll reveals both panels with a 240 ms transition. Small movements are filtered to prevent flicker. Returning from a video keeps the feed position and panel state; changing sections reveals navigation. The observer consumes no scroll distance, preserving Home pull-to-refresh. Tablet navigation stays fixed.

## Full-screen settings (0.2.0)

The header gear opens six full-screen settings sections: General, Search, Servers, Language and Translations, Player, and About. General offers Android-following/Graphite/Light/Aurora themes, system/Russian/English interface language, and a country preference for server discovery.

Search supports ordered sources, parallel/sequential/hybrid execution, custom Sepia-compatible indexes, result sorting, availability checks and sensitive-content preferences. Server settings validate manual connections and search the JoinPeerTube directory, with online and video-count sorting. Changing servers refreshes the feed without mixing stale results into search.

Metadata and WebVTT subtitle translation use on-device ML Kit models. Models can be downloaded or deleted, with Wi-Fi-only downloads enabled by default. Initial downloads require network access; translated subtitles retain their original timing. Existing text remains available when translation fails. Videos without captions cannot gain captions through text translation.

Player defaults include quality, speed, autoplay, background playback, control timeout, seek interval, resize mode and subtitle language. Subtitle appearance supports font size, color, horizontal position and bottom margin. About displays the installed version, build date, developer and a manual update check. Preferences survive process restarts and APK upgrades.

## Home feed menu (0.2.1)

A horizontally scrollable row under the header links Home, On server, Live and Subscriptions. Home and Subscriptions open the same destinations as bottom navigation. On server lists local videos from the currently connected instance and supports pagination. Live browses the global Sepia index without a title query, following the PeerTubeTV approach, and checks current video details at each origin to exclude waiting, ended and unavailable streams. If the index is unavailable, the active server's federated live list is used. Each feed has its own scroll position, and the menu follows the mobile panel hide/show behavior.

## Home sorting (0.2.3)

Home combines subscriptions, recommendations and recent videos into one deduplicated feed, newest publication first by default. Its sort menu offers newest, oldest, most viewed, and the previous collection sections. The choice persists across restarts. Missing/invalid dates sort last in date modes. Subscription videos are ordered by publication time before the 30-video limit is applied, preventing the first channel from filling the selection. Sorting changes the order of the loaded selection; it does not fetch the entire network catalog.

## Playback and thumbnails (0.2.4)

The playback service records history after five seconds of actual playing time, including background playback. Pause/buffering time and seek jumps do not count. All Home modes react to history and hide watched videos. Existing history is retained. Ending a video returns to its browsing destination and scroll position.

The player offers Original, 16:9, 4:3, 1:1 and 21:9 display formats independently of fit/zoom/stretch scaling. The selected format also appears in Player settings. Thumbnail loading tries the browsing server and origin for relative URLs, then preview images and origin metadata when needed; unavailable images show a placeholder rather than an unexplained blank surface.

## Subtitle speech (0.3.0)

Android TextToSpeech can speak original or translated subtitles, both sequentially, or automatically use the target language. It is disabled by default. Player → Subtitles → Speech contains engine/voice selection, language, rate, pitch, original-audio duck/mute, synchronization strategy, fallback and voice testing. The player has quick enable and settings controls on phones and tablets. Offline installed voices are preferred; no cloud TTS dependency or voice pack is bundled.

Speech belongs to PlaybackService and uses the existing subtitle translation and cache. The current timed cue replaces stale speech; no unbounded utterance queue is used. Pausing, seeking, changing items/speed, engine errors and disabling speech stop utterances and restore player volume. Long cues can be skipped at their deadline, accelerated up to 2.5×, or hold video temporarily (30-second watchdog). Explicit user pause and headphone disconnect cancel an owned hold. Video resumes when an owned hold ends or speech is disabled, but not after user pause.

## Server and live-feed recovery (0.3.1)

Server connection and local-video listing validate the PeerTube JSON response shape; HTML websites and unrelated JSON are rejected. A previously saved incompatible address shows a clear message and a direct Servers-settings action. PeerIche does not implement Odysee's separate API.

Live discovery prioritizes most-viewed index entries, verifies their origin state, and automatically skips up to four empty index pages while preserving the next-page cursor. Network failures on one origin short-circuit further queued checks against that origin within the batch. When the index is unavailable or has no verified live result, the selected server and the default public PeerTube federation are tried as fallbacks. Network failure and a verified empty selection use different messages.

External WebVTT provides exact start/end timestamps. Media3-only embedded cues have no end-time table: their window ends at the next cue event or an eight-second safety window. Resume restarts the current cue rather than resuming an exact phoneme. Actual TTS duration varies by voice. First-time ML Kit model preparation can delay translated speech; the chosen fallback applies. See `docs/SUBTITLE-SPEECH-AUDIT.md` and `docs/SUBTITLE-SPEECH-REPORT.md` for architecture and verification.
