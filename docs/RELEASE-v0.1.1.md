# PeerIche 0.1.1

- Automatically checks GitHub Releases on launch (at most once every six hours after a successful check).
- Manual update check in Settings.
- Downloads APKs inside the app and verifies SHA-256, package name, increasing version code and matching signing certificate.
- Opens the Android installer; installation requires user confirmation. On Android 8+, allow installs from PeerIche when requested, return and tap Install again.

Install this version once manually over the signed 0.1.0 release to enable future update checks. Local library and settings are retained. No GitHub login is needed.

Checks published numeric release tags, including the project’s public preview releases; beta/RC tags are ignored. Automatic checking occurs while the app is opened, not as a background service.
