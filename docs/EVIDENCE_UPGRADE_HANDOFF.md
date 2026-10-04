# Evidence-driven upgrade

This change addresses the supplied chapter, OREZ, local-video, download, and Settings evidence. Private screenshots, video, model files, and generated transcripts are excluded from the repository.

## Implemented

- Readers share one ordered page list across vertical, horizontal LTR, and RTL modes; preserve position; support tap navigation, pinch/double-tap zoom, and visible failed-page retry. Static hidden carousel slides and bounded rendered observations are collected without admitting explicit CAPTCHA, placeholder, or advertisement images. MangaDex uses its ordered public manifest.
- OREZ retrieval keeps bounded top matches, checks cancellation, caches a small number of queries, and caps foreground scanning. Native inference uses memory mapping, chunked prefill, conservative threads, and low-memory eviction. ML translation remains the fast default; optional local dialogue refinement has a timeout. Conversation imports stream to EOF rather than silently stopping at 100,000 records.
- Public video discovery supplies real title/channel/thumbnail metadata and Open/Play actions through the existing yt-dlp dependency.
- Audio subtitles use overlapping PCM windows, an energy activity gate, Whisper no-speech confidence, overlap deduplication, and measured inference status. Visual OCR is explicitly separate and initially disabled. Playback restores position, offers speed/lock tools, and suppresses app navigation on parameterized local-player routes.
- Downloads accept supported separate AVC/AAC tracks, locally mux without recompression, verify actual output resolution/audio, and expose stage/retry details. Known advertisement media candidates are excluded. Database migration preserves existing downloads.
- Settings consolidate module switches and collapse advanced imports and diagnostics. Shared surfaces, contrast, typography, and spacing are revised. A lightweight shortcut widget opens translation, OREZ, Library, or Downloads without polling.

## Validation and limits

Focused regressions cover chapter ordering and filtering, MangaDex manifests, speech-window policy, result metadata, separate-track parsing, Android muxing, and reader mode transitions. CI generates deterministic media fixtures; private media tests derive metadata from the staged sample rather than assuming the older video's dimensions or duration. The new supplied video is 758.689 seconds, 640×360, H.264/AAC; a private first-30-second excerpt was prepared for speech testing.

Local build/test results are recorded after execution below. Android device execution and semantic translation/subtitle accuracy must be distinguished from compilation and JVM tests. Existing older-device results do not validate this new revision.

No 2× performance claim is made. The gzip conversation corpus still has bounded front-of-file scanning rather than an index; comprehensive late-corpus recall and a smaller quantized model tier remain future work. Lazy carousels without exposed image manifests may still require source-specific adapters. Speech activity filtering is a heuristic plus Whisper confidence, not a separate neural VAD. Separate-stream muxing intentionally supports AVC/AAC only. Download heights are ceilings and can be lower when the source lacks the selected format. Widget shortcuts require the app's normal model/media setup.
