# Online subtitle search

Fermata's default online search requires neither a developer API key nor a user account. Open a video on the phone, choose **Subtitles → Find subtitles online**, select a language, and tap a result to download and apply it.

## How the integration works

- The legacy VLSub extension calls the OpenSubtitles.org XML-RPC service. It starts a session, searches by video hash and file size or by title/season/episode, and downloads a link supplied in the search response.
- The video hash is the wrapping 64-bit sum of the file size and little-endian words in its first and last 64 KiB. Files smaller than 128 KiB and documents that cannot be read or sought fall back to title search.
- Fermata identifies itself as Fermata. The default path starts an anonymous session, tries the fingerprint when available, falls back to title and episode matching, deduplicates results, and ranks exact matches before download count.
- A selected result's HTTPS direct download link is fetched in the background. Gzip and ZIP responses are bounded in size and decoded in memory. SRT/VTT content is parsed before it is persisted or selected for playback.
- Persian results use Windows-1256 as the fallback for legacy files. The existing parser detects supported Unicode encodings first. Downloaded tracks use the same subtitle selection, delay, rendering, and saved-item preferences as manually selected subtitles.
- Cancellation suppresses late UI results and requests worker cancellation. Connection/read timeouts bound outstanding network calls. Errors offer Retry and New search.

The new OpenSubtitles.com VLSub extension includes its application's registered REST API key. That explains why its users never obtain a developer key themselves. Fermata also supports a publisher-provided REST key through FERMATA_OPENSUBTITLES_API_KEY. Its user interface does not request a developer key.

## Service limitations

OpenSubtitles announced the legacy XML-RPC service's retirement in January 2026. Availability remains outside Fermata's control. Search and direct download worked in live host checks on 18 November 2026, using Fermata's own identity and no key or credentials. The separate legacy DownloadSubtitles method returned a one-cue VIP advertisement while reporting success, so Fermata uses the supplied direct link and rejects that placeholder.

A service error, quota notice, unsupported response, or subtitle with no readable cues produces an error. The search form includes the provider's website as a fallback. A fingerprint match improves release matching, and timing still depends on the subtitle that was uploaded.

Online searches are managed from the phone during Android Auto use. On-device interaction, permissions, playback, and head-unit rendering require physical-device checks.

## Verification

The host checks compile the production client and subtitle parser with the project's utility classes, JUnit 4.13.2, the Android API jar, KXML's host parser, and a host JSON implementation. A constructor-only Android Handler stub permits the existing deterministic scheduler tests to execute. They exercise production parsing and scheduling code, while Android UI and framework behavior remain outside these tests.

- 20 host tests passed across existing hashing, REST result handling, subtitle parsing/scheduling, and nine new VLSub cases.
- New cases cover XML escaping and Persian text, false/empty results, service faults, malformed XML and DTD rejection, language and episode mapping, result ordering and deduplication, unsafe links, gzip/ZIP, placeholder rejection, decompression limits, invalid search data, and cancellation.
- Live keyless title search and download parsed 1,744 English cues and 1,458 Persian cues from genuine subtitle files. These were host network checks.
- Android Auto release Java compilation passed. APK packaging and verification are recorded with the build artifact.

## Primary references

- [Original VLSub source](https://github.com/exebetche/vlsub/blob/master/vlsub.lua)
- [Original VLSub usage](https://github.com/exebetche/vlsub#usage)
- [Maintained OpenSubtitles.com VLSub source](https://github.com/opensubtitles/vlsub-opensubtitles-com/blob/main/vlsubcom.lua)
- [Maintained extension FAQ](https://github.com/opensubtitles/vlsub-opensubtitles-com/blob/main/docs/faq.md)
- [OpenSubtitles legacy-service retirement notice](https://forum.opensubtitles.com/t/opensubtitles-org-api-final-shutdown-notice-for-non-vip-users/5045)
- [OpenSubtitles application-key guidance](https://blog.opensubtitles.com/opensubtitles/how-to-build-a-movie-subtitle-finder-cli-with-python-and-the-opensubtitles-api)
- [Android XML external entity guidance](https://developer.android.com/privacy-and-security/risks/xml-external-entities-injection)

The implementation follows VLSub's public protocol and matching approach. Fermata's source remains distributed under GPL-3.0.
