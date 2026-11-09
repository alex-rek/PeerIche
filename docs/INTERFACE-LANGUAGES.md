# Interface languages

PeerIche supports English, Russian, Spanish, French, German, Portuguese, Turkish, Ukrainian, Simplified Chinese, Arabic and Hebrew. The language picker uses native language names and retains Follow Android. Regional locales such as es-MX, pt-BR and fr-CA fall back to the corresponding base-language catalog. Hebrew uses the Android legacy resource qualifier values-iw with the modern he language tag.

Legacy bilingual Compose strings were migrated to Android resources, including settings, account screens, uploads, broadcasts, comments, player controls, TTS controls and error messages. Runtime values use numbered format arguments. Upload and broadcast notifications also use string resources. Android 13+ uses the framework per-app locale as the source of truth, including changes made from Android settings; older Android versions retain the existing saved-language configuration context.

The nine new static catalogs were initially prepared on the isolated Android emulator using the existing ML Kit on-device translation dependency. Only generic, bundled English interface strings were processed. Core navigation, playback, account, privacy and voice terms, plus formatted messages, received manual corrections recorded in tools/interface_translation_overrides.json, tools/interface_translation_zh_ar_he.json and tools/interface_translation_key_overrides.json. Further native-speaker review would improve wording in longer messages.

Arabic and Hebrew use right-to-left layouts, mirrored Back and forward chevrons, and relative spacing for the player controls. Floating video dragging remains in physical screen coordinates so dragging follows the finger in either layout direction. Video pixels are not mirrored by the interface language.

These catalogs ship as ordinary XML resources. No language-model download, network call or interface text translation runs when a user changes the interface language. Existing video title, description, comment and subtitle translation remains separate and unchanged.

Build-time tools:

- tools/migrate_interface_strings.py extracts legacy labels.
- Android instrumentation argument interface-translations exports draft static catalogs using local models, with resumable per-language output.
- tools/import_interface_translations.py validates and imports the draft catalogs with reviewed overrides.
- tools/verify_interface_locales.py checks coverage, declarations, duplicates and numbered format arguments.
- Android instrumentation argument interface-locales checks actual resource lookup, formatted arguments and regional fallback on the device.

Validation completed: debug app and Android-test APK builds succeeded; all 134 unit tests passed; lint reported zero errors. The Android locale smoke test passed for all 11 languages, RTL layout direction, localized numeric formatting and regional resource fallback. Interactive Android 13+ per-app locale switching was checked on emulator 5556 in French, Arabic and Hebrew at 1280×800, and in Arabic and Hebrew at 393×852. The native language label in General settings matched the system-selected app locale. RTL screenshots were inspected for text clipping and navigation direction. Emulator size, density and original Russian app locale were restored afterward. No physical user device or external account publication was used.
