# Own video playback

The Your videos page displays thumbnails and a Watch button for published videos and active live streams, including private items. Processing, waiting and ended live items keep their server status; a replay becomes watchable when PeerTube publishes it as a video. The Website and stream settings actions remain available.

CreatorRepository fetches full video details through the authenticated account API. Restricted items retain only a nonsecret account identifier in the Video model. Public and unlisted details continue using ordinary playback.

The Media3 data source resolves restricted file requests using PeerTube's `/api/v1/videos/:uuid/token` endpoint. A file token is cached only in memory until its expiry and renewed as needed on the loader thread. It is restricted to the account's HTTPS origin, exact video file paths and the video's UUID-specific HLS directory. It is never added to Video, saved history, sharing text, media item URLs or notification intents. OAuth access tokens are not sent to media URLs. Private storage on a separate CDN is not supported by this initial implementation.

Account changes and logout stop restricted playback. Private descriptions and caption listings use authenticated account endpoints. Tests cover authenticated details, public/private handling, HLS/MP4 token reuse, origin and path restrictions, account switching, malformed expiry, and keeping tokens out of history and sharing. Real account playback still requires device verification after installation.

API reference: https://docs.joinpeertube.org/api-rest-reference.html

Upstream token client: https://github.com/Chocobozzz/PeerTube/blob/v8.3.2/client/src/app/shared/shared-main/video/video-file-token.service.ts
