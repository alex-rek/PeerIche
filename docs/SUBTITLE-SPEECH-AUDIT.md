# Subtitle speech audit (2026-11-06)

Scope: PeerIche only. Baseline 0.2.4, commit d33f03d. No changes to other repositories.

- Kotlin 2.0.21; compile/target SDK 35; minimum SDK 23; Media3 1.5.1; Compose 1.7.6. No dependency upgrades required.
- `PlaybackService` owns ExoPlayer and MediaSession. Headset commands use a ForwardingPlayer. Background playback and watch-time tracking live here. This is the lifecycle owner for speech.
- `BackgroundVideoPlayer` owns a MediaController, requests PeerTube caption metadata, creates MediaItem.SubtitleConfiguration tracks, and displays Media3 CueGroups. Settings currently control subtitle track, font, colors, positions and video speed.
- `LocalTranslation.subtitle` downloads a bounded WebVTT, prepares ML Kit models, translates cue payloads sequentially, retains identifiers/timestamps, and atomically caches the entire translated file under a URL+target-language hash. It is a batch-before-attachment pipeline, not streaming translation. A mutex prevents duplicate concurrent translation. Original tracks remain attached. A matching native target-language track bypasses translation.
- Original captions: `VideoCaption(language, captionPath, fileUrl)`. Translated captions: cached WebVTT file attached as another subtitle track. Media3 rendering uses CueGroup/Cue, whose text events do not provide the external file's complete cue end-time table.
- Pause/seek/speed/video changes are handled by Media3 and MediaSession; speech needs explicit cancellation hooks for these events. UI recreation must not create another speech session.
- Settings are JSON-backed `AppOptions` exposed as StateFlow; player settings use the existing full-screen settings destination.

Integration: add a service-owned manager, timestamp adapter for existing WebVTT tracks with Media3 cue fallback, a bounded current-cue speech queue, one reusable Android TTS engine per selected engine, and reversible player-volume control. Reuse LocalTranslation and its caches. Keep speech disabled by default. Settings and quick control share persisted options. Test core timeline/queue/audio policy separately from Android engine/device checks.
