# Recording-driven behavior repair

Reviewed the four recordings in Videos_720p_Under20MB.zip against foundation
head fedf253de950afa2350d643d08fa4710667e20f2.

## Findings and changes

- 1000067067, around 10–12 seconds: Web page is selected for a YouTube URL,
  then the video resolver opens. The dialog retains an explicit selection across
  URL updates/paste and passes that selection to both ingestion and navigation.
- 1000067065: "Carryminati videos" receives a capabilities message. Short video
  topic queries now use discovery; unspecified platforms use YouTube search.
  The full later request is "find latest manhwa recap videos", so those video
  results were appropriate. Other-platform searches retain web discovery, with
  Play limited to individual video URLs. Source links open inside MangaLens Web.
  Messages with persisted search results display WEB SOURCES rather than LOCAL.
- 1000067066: translated regions show grey rectangles and residual source text.
  Lettering now keeps the paper reference from the original OCR zone, filters
  reconstruction samples against it, and preserves clean paper texture. OCR
  regions deduplicated across tiles also receive page-space balloon grouping.
  These address concrete mechanisms; complete OCR coverage and semantic Hindi
  quality still require device comparison with original pages.
- 1000067068: a Best available download completes at approximately 949 KB.
  The recording alone cannot establish truncation. Download verification now
  reads actual video/audio samples and checks the declared duration's tail,
  instead of trusting track headers and resolution alone. Verification remains
  bounded in memory and observes cancellation. Valid short videos remain allowed.

## Verification and remaining acceptance

JVM regression tests cover discovery versus translation/playback questions,
site/channel versus individual video links, and truncated versus valid short
media tails. Android regressions cover grey-perimeter contamination and a real
MP4 fixture with its media-data box removed. Existing mux/lettering tests remain.

The PR workflow runs JVM tests, lint, ABI APK assembly, AndroidTest compilation,
native-library/package checks and signature verification. Its existing policy
skips emulator execution. Compiled Android regressions are not runtime results.

Phone acceptance: paste a URL after selecting Web, open and confirm the embedded
browser; search a bare creator query and a recap query; compare translated paper
and source coverage; play a complete offline download with audio and duration;
retry a deliberately truncated file; navigate during translation/download work.

This increment does not complete the full MangaLens/Orez blueprint, guarantee
perfect translation, or establish that every provider offers the chosen quality.
