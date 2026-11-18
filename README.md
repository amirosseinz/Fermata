# Fermata Auto

Watch YouTube, IPTV, and video files on your car’s Android Auto display. This fork improves subtitle support and playback controls, adds offline Whisper captions, and lets you share a phone screen over your local network.

[Releases for this fork](https://github.com/amirosseinz/Fermata/releases) · [Original Fermata project](https://github.com/AndreyPavlenko/Fermata)

## What's new in this fork

| Feature | What it does |
| --- | --- |
| **Persian and Unicode subtitles** | Improves right-to-left layout, subtitle placement, cue timing, and handling of Unicode and legacy text encodings. |
| **External and online subtitles** | Load SRT or WebVTT through Android's file picker, use matching sidecar files, or search and download OpenSubtitles from the phone without entering an API key. Local video fingerprints are tried before title and episode matching. |
| **Offline Whisper captions** | Choose and download verified models, then generate captions on-device through ExoPlayer. The model manager offers compact English and multilingual options. CPU and Vulkan support depend on the build and device. |
| **Playback and Android Auto controls** | Adds full-screen controls, per-item volume, and buffering adjustments. Keeps the active car video surface attached when unrelated views change. |
| **Nearby Screen** | Share screen video between two Android phones running Fermata on the same local network. Start **Receive an Android screen** on one phone, then **Share this Android screen** on the other and enter the pairing code. |
| **Experimental iPhone AirPlay** | Receive iPhone screen video inside Fermata over the local network. The car’s AirPlay receiver opens only when Android Auto reports Park and permits video. |

Fermata also plays music and playlists on Android and supports Android TV. Other features include CUE and M3U, favourites, bookmarks, saved playback positions, per-item speed and audio effects, and a choice of MediaPlayer, ExoPlayer, or VLC. The IPTV add-on supports XMLTV programme listings and catch-up.

## Getting started

**Fermata 2.1.0 (fork)** is the planned release of these changes, based on upstream Fermata **2.0.2**. The current subtitle-search preview displays `2.1.0-preview.1`.

The test APK runs on ARM64 devices with Android 9 or later and appears in the phone launcher as **Fermata Auto**. Fermata is free.

- **External subtitles** · Start a video on the phone. Open **Subtitles → Load external subtitles** and choose an SRT or VTT file.
- **Find subtitles online** · Start a video on the phone, open **Subtitles → Find subtitles online**, confirm the title and language, then select a result. Fermata downloads and applies it to the active video.
- **Automatic captions** · Enable ExoPlayer and open **Whisper models** on the phone to download a model. Choose **Generate captions** during playback. Model downloads require internet access.
- **iPhone mirroring** · Open **Nearby Screen → Mirror iPhone (AirPlay)** on the receiving Android phone. Connect the devices to a local network that allows discovery. On the iPhone, open **Control Centre → Screen Mirroring**, choose **Fermata**, and enter the displayed PIN. On a compatible parked Android Auto host, use **Fermata Auto → Menu → Mirror iPhone (AirPlay)**.

### Online subtitle search

The default build uses a VLSub-compatible anonymous search session and the direct subtitle download link returned by OpenSubtitles. Users can choose English, Persian, and other supported languages. The download is checked for readable SRT/VTT cues before being applied, and compressed downloads are bounded in size.

OpenSubtitles has announced retirement of its legacy XML-RPC service. This integration depends on its continuing availability. A service rejection, quota notice, or invalid subtitle produces an explicit error with Retry and New search controls. The search dialog also links to the provider's website. A publisher can configure its own registered REST API key at build time using `FERMATA_OPENSUBTITLES_API_KEY`. Users are never asked to obtain a developer key.

The existing video parser, rendering engine, and persisted subtitle preference apply downloaded subtitles. Manage online searches on the phone when using Android Auto.

### AirPlay verification

The receiver handles H.264 screen video. The ARM64 Android Auto build, host tests, and native loopback checks passed. Live iPhone pairing, on-device playback, and physical head-unit behaviour remain unverified. A head unit with unavailable or restricted driving status leaves the car receiver off. Wireless Android Auto can place the phone on a separate network and prevent discovery.

## Build from source

Use JDK 21, Android SDK and Build Tools 36, NDK `29.0.14206865`, and CMake `4.1.2`. Set `ANDROID_SDK_ROOT` to your SDK directory.

```bash
git clone --recurse-submodules https://github.com/amirosseinz/Fermata.git
cd Fermata
./gradlew :fermata:packageAutoReleaseUniversalApk -PABI=arm64-v8a
```

For the optional AirPlay receiver, supply `-PAIRPLAY_SOURCE=/path/to/airplay-dependencies` and `-PAIRPLAY_OPENSSL=/path/to/android-openssl`. The native receiver instructions cover the pinned dependencies, pairing patch, and loopback fixture. [AirPlay build instructions](fermata/src/main/cpp/airplay/README.md)

## Project and licence

This repository is a fork of [Andrey Pavlenko's Fermata](https://github.com/AndreyPavlenko/Fermata). The application source is licensed under [GPL-3.0](LICENSE).

Support the original project through [PayPal](https://www.paypal.com/donate/?hosted_button_id=NP5Q3YDSCJ98N), [CloudTips](https://pay.cloudtips.ru/p/a03a73da), or [Yandex Money](https://money.yandex.ru/to/410014661137336).
