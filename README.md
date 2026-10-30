# Adownloader

A lightweight Android video downloader/browser inspired by the familiar workflow of WDownloader, rebuilt from scratch.

## Current v0.1 foundation

- Android WebView browser with address/search bar
- Conservative ad/tracker blocking (media requests are never blocked by the blocker)
- 18+-style popup confirmation before a new window is opened
- Per-site popup allow list
- Network request media sniffing via `shouldInterceptRequest`
- Service Worker request sniffing
- Document-start JavaScript hooks for `fetch`, XHR, media DOM sources and blob creation
- Signature/content-type probing for extensionless media URLs
- MP4/WebM/direct media detection
- HLS / M3U8 detection including master playlists
- DASH / MPD detection (download engine intentionally not enabled yet)
- HLS highest-bandwidth variant selection
- AES-128 HLS segment decryption
- Segment-by-segment validation through HTTP success checks
- MPEG-TS to MP4 remux attempt without re-encoding; TS fallback when Android's muxer cannot handle the source
- fMP4 HLS concatenation path
- Cookie / Referer / request header preservation
- Foreground background downloads and completion notification
- Downloads saved under `Movies/Adownloader`
- Built-in simple player
- DNS / connection failure classification
- User-Agent switch: WebView / Chrome Mobile / Samsung Internet / Desktop Chrome
- System-bar safe area handling from the first build

## App icon

The launcher icon source is kept as the exact uploaded 1536×1536 image bytes encoded in `app/src/main/icon/adownloader_icon.b64`. Gradle decodes it without resizing or recompression before Android resource processing.

## Build

Every push to `main` builds an installable debug APK with GitHub Actions and publishes it to Releases.

## Boundaries

Adownloader does not attempt to bypass DRM/EME/Widevine or SAMPLE-AES protected streams. Detection and downloading are intended for media the user is authorized to access.
