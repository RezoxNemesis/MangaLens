# Live English audio subtitles

Native video playback routes Media3's decoded PCM16 audio through `VideoSpeechEngine` into Whisper.cpp 1.7.6. It does not listen to the microphone. Input is downmixed and resampled to 16 kHz mono, grouped into configurable 3–12 second chunks, and translated directly to English with a multilingual model. The queue has two slots; slow inference reports lag and drops additional chunks instead of accumulating unbounded work.

The player has an **English audio CC** control; the local player exposes the same settings under **Tools**. Users can download the free multilingual tiny model or import multilingual GGML tiny, base or small models. Recognition requires that installed model. Model source language, chunk duration, text size, background opacity, subtitle hold time and sync offset are configurable. Recognised cues can be exported as SRT. Importing a larger model improves some recognition results but can make inference slower than playback.

The default model is pinned to `ggerganov/whisper.cpp` model revision `5359861c739e955e79d9a303bcbc70fb988958b1`, file `ggml-tiny.bin`, 77,691,713 bytes, SHA-256 `be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21`. Downloads are bounded, checked before installation, and never require a subscription. Model headers are checked before native parsing; an imported model must also load successfully before replacing the working model.

Whisper source is pinned to commit `a8d002cfd879315632a579e73f0148d06959de36`. The separate native library statically links its private ggml implementation with hidden symbols so it cannot bind to Orez's different ggml version. Its MIT notice ships in `assets/third_party/whisper-LICENSE.txt`.

Cancellation sets a native abort flag. Inference, loading and freeing share a mutex, so the model cannot be freed during inference. Source changes and seeks advance a generation counter; results from a previous generation are discarded. The player supplies playback position for cue display. New speech can be displayed as soon as processing completes; disabling that option displays only cues matching the playback timeline, with the configured offset.

Web mode can submit playback-captured mono 16 kHz chunks to the same engine through `submitPcm16k(samples, startMs)`. Capture requires Android 10+, the user's system screen/audio-capture approval, and capturable site audio. Protected or capture-disabled audio cannot be transcribed by that path. Opening a detected stream in the native player provides direct decoded-audio transcription where supported.

This is short-window speech recognition with processing delay. It does not guarantee perfect accuracy, instantaneous captions, every world language, or compatibility with audio that cannot be decoded/captured. The `tiny` model prioritises speed; difficult accents, music and noisy speech need a larger model and may still produce mistakes.

## Real sample verification

The supplied 23.5-second video was decoded with FFmpeg to 16 kHz mono PCM and processed by a host build of the same pinned Whisper backend and tiny model. The model detected English and emitted eight timestamped English SRT cues in 3.58 seconds of inference on the workspace host. This verifies actual sample audio inference; it is separate from on-device throughput and player UI verification.

`LiveSpeechSampleTest` exercises the actual production Media3 PCM processor and recognizer. Stage video/model fixtures outside the repository, then pass instrumentation arguments `sample_video` and `whisper_model_path`. It checks that production playback produces nonblank timestamped cues and writes `filesDir/sample-English.srt`. No uploaded video, audio, speech model, or derived transcript is committed to the repository.

The optional sample test also checks a caption in the production Compose player and saves a screenshot. Its second test exercises terminal shutdown while real model reloads are queued. `WebPlaybackCaptureTest` accepts the same model argument, routes Android-consented WebView PCM through the production speech engine and writes `filesDir/sample-Web-English.srt` if inference succeeds. Without the private fixtures these tests are skipped; ordinary green CI does not establish their acceptance.

`SpeechAudioProcessorTest` verifies Media3 empty drain buffers, exact PCM passthrough and end-of-stream/reset without requiring a model. It passed in CI run 37200648168 after fixing the empty-buffer self-copy crash. See CORE_MEDIA_TRANSLATION_HANDOFF.md for the exact verified artifact and pending sample status.


## Approved player/subtitle upgrade — 5 October 2026

The player now exposes the approved two-path subtitle design rather than treating real-time
recognition as the only usable path.

1. **Live English Audio CC** continues to use decoded playback PCM and the installed multilingual
   Whisper model. Visual OCR remains independent and cannot contaminate spoken captions.
2. **Generate Full English Subtitles** decodes the video's audio with Android MediaExtractor /
   MediaCodec, downsamples it to mono 16 kHz, processes bounded overlapping windows through the
   same Whisper backend, translates multilingual speech to English, de-duplicates timed cues,
   writes an SRT cache keyed to the source, previews the result, and can attach the generated cues
   to playback or export the SRT. Reopening the same source reuses a valid cached SRT.
3. Local and native online players share the new cinematic subtitle tools/dock. The controls expose
   live CC state, full generation, separate visual OCR, output/export, fit mode and model readiness.

Native online playback was hardened at the same time. Source-page extraction now preserves
yt-dlp/HTML resolver request headers, Cookies and Referer, supports split video+audio by merging
Media3 sources, understands KVS-style direct MP4 download variants (including 4K filename hints),
and automatically re-resolves the source page when a signed/CDN stream is rejected or expires.
A rendered background discovery fallback remains available without forcing the user into the Web
screen. Accessible HTTP(S) MP4/HLS/DASH sources are the target; DRM, inaccessible login-only media,
or servers that intentionally prohibit third-party playback are not claimed as universally playable.

Ad blocking now blocks known ad-network media as well as scripts, expands adult-site ad/popunder
network coverage, suppresses known ad navigations and dynamic popup/window.open patterns, while
leaving ordinary first-party video/image media untouched.
