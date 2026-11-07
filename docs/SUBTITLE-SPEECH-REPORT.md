# Subtitle speech implementation and verification

Scope: PeerIche phone/tablet only. Package/applicationId/name unchanged. No dependency upgrades. Version 0.3.0 (13), Android 6+.

## Files

Added:
- `SubtitleSpeechModels.kt`: persisted settings, speech modes, bounded cue claim policy, rate/volume policies.
- `SubtitleCueAdapter.kt`: bounded WebVTT loading, timestamps, markup cleanup, current-cue selection and existing ML Kit language identification.
- `TtsEngineController.kt`: one Android TTS client per engine, offline voice preference, language availability, UtteranceProgressListener, cancellation, initialization/utterance timeouts and resource shutdown.
- `SubtitleSpeechManager.kt`: service-owned coordination, original/translated timelines, existing translation reuse, pause/seek/item/speed handling, bounded active speech and guarded video holds.
- `SpeechAudioController.kt`: reversible player volume control; system volume is never changed.
- `SubtitleSpeechSettingsPage.kt`: full-screen settings, first-enable automatic-mode suggestion, engine/voice picker, voice test, installation action and quick controls.
- `SubtitleSpeechTest.kt`: timeline, queue, modes, translation timing, rates, audio restoration and persistence tests.
- Audit, report and `RELEASE-v0.3.0.md` documentation.

Modified:
- `AppPreferences.kt`: adds nested settings with backwards-compatible disabled defaults.
- `PlaybackService.kt`: creates speech only when enabled; explicit user-pause and headphone-disconnect cancellation; cleanup on service destruction.
- `BackgroundVideoPlayer.kt`: quick controls; speech can consume text tracks while visual subtitles are hidden.
- `LocalTranslation.kt`: optional explicit target language for the existing methods. Previous metadata calls/defaults remain unchanged. The same mutex, ML Kit engine and URL+language subtitle cache remain in use.
- `SettingsScreen.kt`: adds Player → Subtitles → Speech and back navigation.
- `AndroidManifest.xml`: Android 11+ TTS service visibility query.
- `app/build.gradle.kts`: version/build date only. README updated.

## Build checkpoints

Baseline audit: successful compile and 27 existing tests.
Original speech: successful compile and tests.
Existing translation integration: successful compile and tests.
Synchronization hardening: successful compile and tests.
Audio controller: successful compile and tests.
Settings/quick controls: successful compile and tests.
Final build: signed release APK + debug APK + 37 passing JVM tests; Android lint has zero errors (58 warnings, including existing API/deprecation checks).

## Device verification

Temporary read-only Android API 37 phone emulator, also resized to 1280×800 for tablet checks. Installed [official eSpeak NG 1.52.0](https://github.com/espeak-ng/espeak-ng/releases/tag/1.52.0) solely in this emulator for offline speech verification; it is not a PeerIche dependency or release asset. Tested public Framatube fixtures: `9c9de5e8-0a1e-484a-b099-e80766180a6d` (English/Russian/native target tracks), `0900bd2e-7306-4c39-b48b-2d0cd611742e` (French only, initial model download/delay), and `217eefeb-883d-45be-b7fc-a788ad8507d3` (51 cues, English/French/German only, completed machine translation to Russian). A PeerTube.tv item `038cab32-d6cc-4969-a5ad-0068c9adaed8` returned zero captions and was used for the no-subtitles case.

Confirmed: first-enable prompt; disabled defaults; persisted selected engine/mode after restart; English original and Russian native-target TTS utterance starts; both-language sequential utterances; source ducking to 25% with restoration to 100% on pause/stop; owned video hold in both mode; video resumes when speech is disabled during hold; missing Google Russian voice reports an error without blocking video. The completed Russian translation cache was `fb9b28c2aca1fe5138936d70f913919afe3104e1d8a28cefeadd89e27f313397.vtt`; the engine then started Russian utterances while the displayed original track remained English. Speech settings and controls were inspected in light phone and dark tablet layouts.

| Required scenario | Verification |
|---|---|
| 1. Russian subtitles | Emulator: native Russian cue → `speech_start languages=[ru]` → UtteranceProgressListener start. |
| 2. English subtitles | Emulator: explicit English subtitle preference, original mode → English utterance starts. |
| 3. Translated subtitles | Emulator: English-only source translated by existing ML Kit pipeline/cache, followed by Russian utterance starts. No native Russian track on this fixture. |
| 4. No subtitles | Emulator: `no_subtitles`, original volume 1.0; MediaSession remained PLAYING. |
| 5. Rapid seeks | Emulator: several rapid time-bar seeks; new-position utterances continued. Core queue/timeline tests cover reset and stale-window exclusion. |
| 6. Pause/resume | Emulator: pause stops speech and restores volume 1.0; resume continues current/next cue. |
| 7. Language change | Emulator: changed speech target from following Russian translation language to English through the picker on the same video; subsequent utterances were English. Global translation-language changes are included in the manager reload key. |
| 8. Long/short cues | Emulator: ordinary short cues and sequential two-language hold. Unit tests: rate/deadline cap, overlap policy. Exact duration varies by voice. |
| 9. Video change | Emulator: switched between fixtures; previous item speech stopped and new source/target was used. |
| 10. Disable during speech | Emulator: quick toggle disabled during a two-language owned pause; MediaSession resumed PLAYING. |
| 11. Missing voice | Emulator: Google engine had no installed Russian voice; `missing_voice`, volume 1.0, video unaffected. |
| 12. Translation delays/errors | Emulator: first model preparation used French fallback; shorter English file completed cached Russian translation. Injected unsupported target `xx` produced `translation_error` then English fallback while video remained PLAYING. |
| 13. Bluetooth audio | Implementation uses existing media routing/session and noisy-disconnect cancellation. Physical headset routing/reconnect/buttons require device validation; no Bluetooth hardware is available here. |
| 14. Background/return | Emulator: Home key during playback; English utterances and MediaSession continued, then the app returned to the same video. |
| 15. Volume restoration | Unit tests: duck/mute/unchanged, repeated cleanup and user changes. Emulator: 0.25 while speaking, 1.0 on pause/stop/missing voice/translation failure. No engine crash was injected mid-utterance. |
| 16. Persistence | Unit JSON round-trip + legacy defaults; emulator process restarts and APK upgrade retained selected settings. |

Release APK signature matches prior versions: SHA-256 certificate `57681c30fa42261d1471f54569ce1fc1c432466a551d107f74feba832a70c9bc`. APK SHA-256: `17ae6325ce22dd7bba0cb63cd5e88d0d0df87e12dab88e069c84a7b77c2863fd`.

## Practical limits

- Physical Bluetooth headphones are unavailable in this environment; real routing, reconnect and headset-device behavior still need hardware validation. Speech uses Android media routing and the existing playback service; headphone-disconnect cancellation is implemented.
- Timing is exact for external WebVTT. Embedded Media3 cue events lack cue end metadata; the fallback uses the next event/eight-second safety window.
- TTS duration cannot be exact. Accelerated rate is capped at 2.5×; wait mode has a 30-second watchdog. Resume restarts the current cue.
- Offline speech requires an installed supported voice. Initial translation may download ML Kit models; missing/late translation follows the configured fallback. Old queues/jobs are canceled and never replayed for another item/language.

Android API references used: [TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech), [UtteranceProgressListener](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener). No audio quality/listening assessment is claimed from the headless emulator; runtime evidence uses successful utterance callbacks and player state/volume.
