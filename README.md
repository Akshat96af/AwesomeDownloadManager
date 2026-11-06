# Absolute Download Manager

A fast, modern download manager for Windows. It splits downloads into parallel segments and resumes them,
grabs video from web pages in the best quality, and connects to Chrome and Edge through a browser extension.

<!-- BANNER: add a wide banner image here, e.g. docs/screenshots/banner.png
<p align="center"><img src="docs/screenshots/banner.png" alt="Absolute Download Manager" width="720"></p>
-->

## Features

- **Segmented, resumable downloads.** One file is downloaded over several connections at once; a part that
  finishes early takes over half of a part that is still running. Downloads survive pauses, restarts and dropped connections.
- **Queues and scheduler**, speed limiter, proxy support, per-site credentials, and HLS (`.m3u8`) streams.
- **Video from page links.** Paste a page link, or use the extension's "Download video" button. The app asks
  [yt-dlp](https://github.com/yt-dlp/yt-dlp) for the streams, downloads video and audio, and merges them with
  [ffmpeg](https://ffmpeg.org) into one file.
- **Browser extension (Chrome and Edge).** Captures browser downloads with your cookies so logged-in links work,
  detects media on the page, and adds right-click actions.
- **Optional antivirus check.** Scan every finished file with Microsoft Defender.
- **Designed to feel good.** A resizable 16:10 window, smooth motion that respects the Windows "Animation effects"
  setting, and a progress bar that keeps moving between updates. When the window is closed the app idles in the
  tray at roughly 30 MB.

## Screenshots

<!-- SCREENSHOTS: put your images in docs/screenshots/ and uncomment the block below.

| Downloads | Download in progress |
|---|---|
| ![Home](docs/screenshots/app-home.png) | ![Progress](docs/screenshots/app-download.png) |

| Browser extension | Video quality menu |
|---|---|
| ![Extension](docs/screenshots/extension.png) | ![Menu](docs/screenshots/video-menu.png) |
-->

## Requirements

- Windows 10 or 11 (64-bit).
- For video downloads, install the two helper tools once (the rest of the app works without them):

  ```
  winget install yt-dlp.yt-dlp
  winget install Gyan.FFmpeg
  ```

## Get the app

Releases will be listed under **Releases** on this page. To build it yourself, see below.

## Browser extension

1. Start Absolute Download Manager once (this registers it with your browser).
2. Open `chrome://extensions` (or `edge://extensions`) and turn on **Developer mode**.
3. Click **Load unpacked** and choose the `browser-extension` folder from this repository.
4. The extension's ID must be `kdabifjnfldglmdjonpailjebgnefpfe`. The app only trusts that ID.

## Build from source

You need JDK 25 (for example [Temurin 25](https://adoptium.net)). No Android SDK is needed for the desktop app.

```
gradlew :desktop:app:createDistributableAppArchive
```

The app is created in `desktop/app/build/compose/binaries/main-release/app/AbsoluteDownloadManager/`.
Run the tests with `gradlew :desktop:app:test`.

## Project layout

| Folder | Contents |
|---|---|
| `desktop/` | The Windows app (Compose Desktop UI, tray, native-messaging host, yt-dlp bridge and merge). |
| `downloader/` | The download engine: segmenting, resuming, queues, HLS. |
| `shared/` | Code shared by the app modules: settings, localisation, UI components, motion. |
| `integration/` | The local server and message types the browser extension talks to. |
| `browser-extension/` | The Chrome/Edge extension (Manifest V3). |

## Credits and license

Licensed under the [Apache License 2.0](LICENSE).

Absolute Download Manager is built on [AB Download Manager](https://github.com/amir1376/ab-download-manager)
(Apache-2.0), whose download engine and application framework it extends. The browser extension adapts parts of
[Awesome Downloader](https://github.com/Ahmadkhattak1/awesome-downloader) (MIT). Video support runs
[yt-dlp](https://github.com/yt-dlp/yt-dlp) and [ffmpeg](https://ffmpeg.org) as separate programs; they are not
bundled. See [NOTICE](NOTICE) and `browser-extension/NOTICE`.

Only download content you are permitted to save.
