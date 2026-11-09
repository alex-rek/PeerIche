# Publication request fix — 2026-11-08

The public configuration of `https://tube.articfox.cc/api/v1/config` reports PeerTube 8.3.2 with live streaming and replay enabled. No authenticated requests, uploads or broadcasts were made during this investigation.

Both resumable uploads and live creation used `CreatorMetadata.json()`, which always sent `description: ""` for the default empty editor. PeerTube 8.3.2 validates a supplied description against a 3–10000 character constraint; its `toValueOrNull` sanitizer only converts the literal string `null`, not an empty string. This causes a validation error before upload initialization or live creation.

Blank descriptions are now omitted. Nonblank descriptions shorter than three characters are rejected locally with an actionable message. Broadcast validation errors are reported without escaping the button handler. Structured description/publication validation errors and server/user live limits receive dedicated messages, without displaying private values returned in server errors.

Regression tests cover JSON upload initialization and multipart live creation with empty/whitespace-only descriptions, preservation of valid descriptions, minimum length, and structured errors. Real server account permissions and RTMP connectivity still require verification on the user's device after installing the fix.

## Empty upload progress response

After filling the description, the user's upload reached a second failure at 0 bytes. PeerTube 8.3.2 depends on `@uploadx/core ^7.0.3`. Its PUT status handler emits `Range: bytes=0-${file.bytesWritten - 1}`, including `bytes=0--1` for an empty session. PeerIche rejected that response before sending the first chunk and displayed a misleading sign-in compatibility error.

The exact empty sentinel is now accepted only on zero-length status probes. It remains invalid after sending a chunk; other malformed ranges, overflows and redirects are still rejected. Malformed upload responses now use a publication-specific error. Regression tests cover initialization, empty probe, partial chunk and completed video in sequence, plus rejection of invalid sentinels and malformed completion responses.

Protocol source: https://github.com/kukhariev/node-uploadx/blob/v7.0.3/packages/core/src/handlers/uploadx.ts

Upstream sources:

- https://github.com/Chocobozzz/PeerTube/blob/v8.3.2/server/core/helpers/custom-validators/videos.ts
- https://github.com/Chocobozzz/PeerTube/blob/v8.3.2/server/core/helpers/custom-validators/misc.ts
- https://github.com/Chocobozzz/PeerTube/blob/v8.3.2/server/core/initializers/constants.ts
- https://github.com/Chocobozzz/PeerTube/blob/v8.3.2/server/core/middlewares/validators/videos/videos.ts
- https://github.com/Chocobozzz/PeerTube/blob/v8.3.2/server/core/middlewares/validators/videos/video-live.ts
