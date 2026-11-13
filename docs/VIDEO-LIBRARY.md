# Video cards, downloads, playlists and Home sources

Video cards now have an avatar, two-line title, one-line metadata and a vertical overflow button. The compact, scrollable action sheet includes Watch later, playlist membership, playlist creation, favorites, download, share, recommendation controls, Play next, local channel blocking, channel navigation, subscriptions and channel pinning. Existing subscription behavior still distinguishes device subscriptions from the signed-in server account.

## Local library and playback

“You → Playlists” contains Watch later and user-created playlists. Videos retain insertion order; adding an existing member does not duplicate or reorder it. A checked picker removes existing membership. Play all starts at the first item, independently of the single-video autoplay preference; selecting a playlist video plays that video and the remaining items. Play next inserts the selected video at the front of the pending queue.

PlaybackService owns the in-memory queue and resolves each next video when the preceding item ends. It continues without an open Activity and updates the foreground player and notification metadata. Missing videos that cannot be resolved are skipped. Closing the player clears its queue. Playlists persist locally; the active queue is not restored after process termination and playlists are not synchronized to PeerTube.

Room schema 3 adds nullable metadata while preserving schema-1 and schema-2 favorites, history and subscriptions. Playlist metadata retains the channel and account identity but omits media URLs and file tokens; each video is resolved again before playback.

## Downloads

Download is available in the card menu and the player action row. The dialog fetches current video details, respects `downloadEnabled`, offers available video file qualities and uses Android’s document picker for the destination. A foreground data-sync service copies one file at a time, reports progress, supports cancellation and removes incomplete output. Private media access uses the existing scoped, in-memory file grant and stops if the account changes. Live streams and HLS manifests are not offered as downloadable files.

PeerTube media URLs beginning with `//` or `/` are normalized against the server before playback or private file access. This also fixes playback on servers such as 0ch.tv that return scheme-relative CDN URLs.

## Navigation and Home

Servers moved from Settings to You. Tapping a server name opens its channel directory; Back returns to the server picker with its saved list state. Connecting to a server remains a separate button.

Settings → General → Home content supports recommendations (default), chosen interests, one server’s local videos, or a channel specified by handle/address. Changing to a dedicated source clears recommendation results; account subscription videos are not mixed into that source. Channel blocking applies to browsing feeds on this device; recommendation exclusions affect Home. Channel blocking and hiding can be reversed in the existing hidden-channel settings. Pinned channels appear in You.

All new interface strings are provided for the eleven supported locales.

## Validation

- Unit regressions cover legacy preferences, source parsing, playlist metadata, queue order/start behavior, download permission/format filtering and relative media URLs.
- `video-library` instrumentation opens a genuine version-2 database, verifies preserved history, checks playlist order/deduplication/deletion and advances the service twice without an Activity using synthetic local audio and an in-memory API.
- `library-ui` instrumentation temporarily seeds a local playlist of public videos and restores settings afterward; it never publishes or uses real credentials.
- Manual emulator checks covered mobile and tablet layouts, English/Russian menus, playlist membership and pinning, server-directory Back navigation, Home-source controls, actual background transition between public playlist videos, and saving a 638,676-byte MP4 through Android’s document picker.

Build and validation logs are local under `release-local/video-library-*.log`. Debug APK output is `app/build/outputs/apk/debug/app-debug.apk`; no release version change is made by this work.
