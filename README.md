<p align="center">
  <img src="docs/readme/hero.svg" alt="Absolute Download Manager: one file split into parallel segments that finish together" width="100%">
</p>

<p align="center">
  <img src="docs/readme/typing.svg" alt="Split one file into 16 parallel streams. Pause tonight, resume next week. Wi-Fi + Ethernet + phone, all at once. Every YouTube quality, one click away." width="720">
</p>

<p align="center">
  <img alt="Windows 10 and 11" src="https://img.shields.io/badge/Windows-10%20%7C%2011-7C6CFF?style=for-the-badge&logo=windows11&logoColor=white&labelColor=0B0B14">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Compose%20Desktop-5B8DEF?style=for-the-badge&logo=kotlin&logoColor=white&labelColor=0B0B14">
  <img alt="JDK 25" src="https://img.shields.io/badge/JDK-25-3FD8F2?style=for-the-badge&logo=openjdk&logoColor=white&labelColor=0B0B14">
  <img alt="License Apache 2.0" src="https://img.shields.io/badge/License-Apache%202.0-22D3EE?style=for-the-badge&labelColor=0B0B14">
</p>

<p align="center">
  <a href="#get-the-app"><b>Get the app</b></a> &nbsp;·&nbsp;
  <a href="#browser-extension"><b>Browser extension</b></a> &nbsp;·&nbsp;
  <a href="#build-from-source"><b>Build from source</b></a>
</p>

<img src="docs/readme/divider.svg" width="100%" alt="">

## ✦ What it does

<p align="center">
  <img src="docs/readme/features.svg" alt="Parallel segments, adaptive connections, every network at once, resume anywhere, YouTube in every quality, cinematic but light" width="100%">
</p>

<details>
<summary><b>The details</b></summary>
<br>

- **Segmented, resumable downloads.** One file is downloaded over several connections at once; a part that
  finishes early takes over half of a part that is still running. Downloads survive pauses, restarts and dropped connections.
- **Adapts to the server.** Starts with 8 connections, grows while every one of them is delivering, and backs off
  when a server starts refusing, instead of hammering it.
- **Every network at once (optional).** With *Use all network connections* on, connections are spread over Wi-Fi,
  Ethernet and a USB-tethered phone together, so their speeds add up. Large downloads suggest it when a second network is connected.
- **Queues and scheduler**, speed limiter, proxy support, per-site credentials, and HLS (`.m3u8`) streams.
- **YouTube in every quality.** Point at a playing YouTube video and the extension's "Download video" menu lists
  each quality ("MP4 file, quality 1080p HD 60 fps", "MKV file, quality 1440p HD 60 fps", audio only, or
  *Download all*). The app streams the chosen video and audio over the player's own session, with no extra
  downloader, and merges them with [ffmpeg](https://ffmpeg.org) into one file.
- **Browser extension (Chrome and Edge).** Captures browser downloads with your cookies so logged-in links work,
  detects media on the page, and adds right-click actions.
- **Optional antivirus check.** Scan every finished file with Microsoft Defender.
- **Designed to feel good.** Spring-driven motion throughout (tactile presses, menus that pop from their anchor,
  content that unfolds instead of blinking on), all of it respecting the Windows "Animation effects" setting, and a
  progress bar that keeps gliding between updates. When the window is closed the app idles in the tray at roughly 30 MB.

</details>

<img src="docs/readme/divider.svg" width="100%" alt="">

## ✦ A look inside

<p align="center">
  <img src="docs/readme/screens/downloads.png" alt="The download list: files of every kind, one downloading at 20 MiB/s, the rest finished" width="100%">
</p>

<table>
  <tr>
    <td width="42%" align="center"><img src="docs/readme/screens/progress.png" alt="A download in progress: 45%, 26 MiB/s, resumable"><br><sub><b>Live progress.</b> Speed, time left and a bar that glides between ticks.</sub></td>
    <td width="58%" align="center"><img src="docs/readme/screens/completed.png" alt="A finished download with its completion dialog and notification"><br><sub><b>Landing.</b> A completion beat, a notification, and one click to open.</sub></td>
  </tr>
</table>

<img src="docs/readme/divider.svg" width="100%" alt="">

## Requirements

- Windows 10 or 11 (64-bit).
- To merge video and audio, install ffmpeg once (the rest of the app works without it):

  ```
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
| `desktop/` | The Windows app (Compose Desktop UI, tray, native-messaging host, YouTube streaming and merge). |
| `downloader/` | The download engine: segmenting, resuming, queues, HLS. |
| `shared/` | Code shared by the app modules: settings, localisation, UI components, motion. |
| `integration/` | The local server and message types the browser extension talks to. |
| `browser-extension/` | The Chrome/Edge extension (Manifest V3). |

## Credits and license

Licensed under the [Apache License 2.0](LICENSE).

Absolute Download Manager is built on [AB Download Manager](https://github.com/amir1376/ab-download-manager)
(Apache-2.0), whose download engine and application framework it extends. See [NOTICE](NOTICE).

Only download content you are permitted to save.
