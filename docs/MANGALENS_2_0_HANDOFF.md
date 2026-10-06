# MangaLens 2.0 upgrade handoff

Date: 2026-10-05

This handoff records the user-approved 2.0 upgrade applied on `engineering/mangalens-production`.
Existing clean media, reader, subtitle and chapter flows were preserved where possible; this work
targets the observed weak points from the supplied screenshots and playback recording.

## 2.0 visual system

- App version is now 2.0.0 (versionCode 6).
- The launcher foreground is a resolution-independent MangaLens 2.0 vector mark.
- The shared dark palette is deeper navy/black with crimson, violet and cyan neon accents.
- Shared cards, hero panels, headers, navigation indicators, OREZ, Home, Library, Downloads,
  Settings and reader controls use the same glass/neon design language.
- Navigation and reader HUD transitions use bounded fade/slide/scale motion instead of abrupt swaps.

## OREZ AI 2.0

The 650 MB local model is no longer allowed to dominate the application indefinitely.

- Local model loading is guarded by memory headroom and serialized with a load mutex.
- Installed models may warm in the background, but low-memory devices skip warm-up.
- Local prompts now have smaller history/retrieval windows and bounded output token budgets.
- Generation and routing have response budgets and native cancellation remains active.
- OREZ offers Local Lite, Hybrid Auto and Web Assist routing modes.
- Hybrid Auto uses local intelligence opportunistically, then falls back instead of freezing chat.
- The UI exposes engine state, warm-start/fallback status, working stages and a stop action.

## Video/download reliability

- Download quality defaults to **Best available**, not a fixed 360p/1080p/2160p assumption.
- yt-dlp selection permits split high-resolution video + audio and no longer requires AVC-only video.
- High-resolution MP4 video codecs can be remuxed with AAC on supported Android devices.
- The stable yt-dlp extractor is refreshed at most once per day when a YouTube URL is resolved,
  reducing failures caused by stale YouTube signature/player extractors.
- Existing source-page headers, cookies, referer handling, retries and split-stream playback remain.

Highest available means the highest non-DRM source MangaLens can access. Protected/private media
and servers that reject third-party access are intentionally not described as universally downloadable.

## Ad-blocking

- Activity timestamps now use 12-hour time with AM/PM.
- Additional known advertising/exchange hosts and ad-path markers are blocked.
- DOM cleanup now also suppresses known third-party ad fetch/XHR/beacon/window-open traffic.
- Rules retain first-party media exemptions unless the URL itself is an explicit ad/preroll path.
- Unit coverage verifies both stronger network blocking and ordinary media pass-through.

## Manga OCR/translation

The screenshot evidence showed two separate problems: semantic/register drift and visual blending.

Semantic changes:
- Hindi output no longer invents respectful `आप`/polite imperatives when the English source is
  neutral or hostile.
- Explicit respectful source cues such as Sir/Madam/Lord still preserve formal register.
- Hostile dialogue can use the appropriate informal register instead of sounding deferential.
- OREZ refinement instructions explicitly preserve relationship, tone and social distance.

Visual changes:
- Original-glyph masking is more sensitive and slightly more dilated.
- Balloon/background reconstruction samples horizontal, vertical and diagonal clean pixels.
- This reduces leftover source glyphs and improves fills on colored/gradient speech bubbles.
- Existing style preservation and geometry-aware lettering remain in place.

## Subtitle/video OCR separation

The previously approved subtitle implementation remains intact:
- English Audio CC is decoded-audio speech recognition/translation.
- Generate Full Subtitles performs whole-video audio processing, caching and SRT export.
- Live OCR is a separate visual-text feature and does not feed visual text into speech captions.
- The cinematic player dock remains the common control surface for these features.

## Validation added

New or extended tests cover:
- neutral vs hostile vs explicitly respectful Hindi dialogue register,
- stronger ad-network blocking without breaking normal video URLs,
- high-resolution split MP4 + AAC source parsing,
- Best available as the default extractor quality.

The final acceptance gate is the branch CI build/tests/APK workflow. Do not mark the upgrade complete
until the latest head commit is green.
