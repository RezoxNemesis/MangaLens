# MangaLens

Android manga reader, local chapter library, OCR/translation companion, embedded browser, media downloads and OREZ assistant. The engineering branch is `engineering/mangalens-production` ([PR #6](https://github.com/RezoxNemesis/MangaLens/pull/6)); `main` has not incorporated this work yet.

See [the engineering handoff](docs/ENGINEERING_HANDOFF.md) for verified builds, test evidence and remaining limitations. Debug engineering builds are available; production release readiness is not claimed.

## Product workflows

- Import images, ZIP/CBZ archives or PDF chapters, read offline, resume progress and manage bookmarks/reading status.
- Open a manga/chapter URL; supported acquisition paths discover catalogs/images. Unsupported or verification-heavy pages can open in Web mode for ordinary user-controlled login/verification.
- Translate pages or chapters with bundled script-aware OCR and on-device language models. Missing translation models require an initial download. Successful chapter pages remain available if another page fails, and Reader can retry translation.
- Download accessible direct or adaptive media. Completed HLS/DASH data uses durable app files storage and the in-app player reads that cache. Protected/DRM media is not supported.
- Use OREZ with optional local generation and public web retrieval. While a reply is running, Stop cancels it and returns the chat to Send. When general search returns no usable results, a Wikipedia encyclopedia fallback provides attributed background excerpts and original page links; it does not verify current news, prices or schedules.

No mandatory paid API, search service, OCR service or translation subscription is used. Source compatibility, network availability and provider restrictions still apply.

## Build environment

The CI configuration is authoritative:

| Component | Version |
| --- | --- |
| JDK | 17 |
| Gradle | 8.9 (installed CLI; no wrapper is currently committed) |
| Android SDK / build tools | API 35 / 35.0.0 |
| Android NDK | 29.0.13113456 |
| CMake | 3.31.6 |
| Minimum Android | API 26 |
| Native APK splits | `arm64-v8a`, `x86_64` |

Configure `ANDROID_HOME`/`ANDROID_SDK_ROOT`, accept SDK licenses and install these packages before building. Native CMake fetches the pinned llama.cpp revision from GitHub; the first build requires internet access.

```sh
gradle --no-daemon clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --stacktrace
python3 .github/scripts/verify-debug-apks.py
```

App APKs are written to `app/build/outputs/apk/debug/`. Use `app-arm64-v8a-debug.apk` for compatible phones and `app-x86_64-debug.apk` for the CI emulator. The optional model and test media fixtures are not shipped inside the application APK.

## Runtime verification

CI launches an isolated API 35 x86_64 emulator, installs the app, checks startup and runs Android instrumentation tests. On an already-running isolated emulator:

```sh
bash .github/scripts/mangalens-startup-smoke.sh
bash .github/scripts/mangalens-device-regression.sh
```

The second script downloads the pinned optional Qwen model (650,379,104 bytes), verifies its SHA-256, stages it in the debug app and runs native-model tests with `require_model=true`. Normal `connectedDebugAndroidTest` runs may skip that optional-model test when the model is absent.

Test evidence covers bounded/resumable transfers, source/routing fixtures, real OCR, English-to-Hindi translation, Room migration, persistent library state, damaged-page translation recovery, secure WebView configuration, download-state guards and playback of an original HLS fixture after its server shuts down. Check the handoff for the exact commit/test totals. HLS fixture certificates/trust settings exist only in instrumentation tests.

## Storage and model lifecycle

Chapter images/manifests, bookmarks/progress, downloads and model resources are app-managed persistent data. Settings' temporary-cache cleanup retains these. Adaptive media uses `files/media_download_cache` with explicit removal rather than an evicting streaming cache.

OREZ features share one native model with feature ownership tracking. Closing an unused or separate feature does not release another feature's model; model cleanup runs off the UI thread. Cancellation is scoped to each generation request and aborts CPU decode through the pinned native library callback. Cancelled partial output is discarded; other model owners can continue generating. Initial model loading and some context allocation/cleanup remain synchronous background operations.

The model URL is immutable and its published size/SHA-256 are checked by the model-provenance workflow. Downloading/importing resource packs does not train or change model weights.

## Release limitations

Private stable production signing, physical ARM64/OEM testing, representative long chapters and translation styles, protected-site session checks, broader adaptive-media/adverse-network coverage and large-corpus profiling remain outstanding. Emulator measurements are not phone benchmarks. Debug signing keys can differ between build environments, so in-place upgrades are not guaranteed across artifacts.

The general search provider can return a verification page. The fallback is the [official MediaWiki REST search endpoint](https://www.mediawiki.org/wiki/API:REST_API/Reference/Sample_code:Search_pages/en), scoped to encyclopedia content and displayed with source attribution. No CAPTCHA bypass is implemented.
