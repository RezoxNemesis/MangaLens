# MangaLens Next — current engineering state

This continuation uses `engineering/mangalens-next-orez-foundation`, descended
from the mature 2.2 runtime recovery line. Legacy `main` remains an obsolete base.
Product review is PR #12. PR #13 was originally a main-targeted validation copy.

## Implemented in this continuation

- Fixed the missing task-store import responsible for the previous CI failure.
- Applied the approved original logo asset to branding and adaptive launchers.
- Replaced shared red/neon tokens with restrained graphite, slate and blue.
- Compact Home and Orez actions are real Compose components and scroll horizontally.
- Home/Library/Watch/Web/Orez navigation preserves existing Reader, Video,
  Downloads, Settings and Protection routes.
- Persisted compact/balanced/comfortable panel density, seven accent choices,
  AMOLED/high-contrast modes and reduced-motion navigation.
- Orez receives active chapter availability independently of translated text.
- Translation commands preserve their target language and do not clear the
  current source URL for local navigation actions.
- Runtime-owned tool descriptors validate routes, capability, risk and arguments.
- Optional installed Lite/Core models can suggest a single structured tool for
  direct action requests. Unknown or malformed calls fall back to conversation;
  models cannot grant themselves permissions or turn opening into downloading.
- Persistent task encoding now has a decoder and terminal handoff states.
  Navigation dispatch does not mean translation/playback has finished.
- Download requests queue WorkManager jobs, use a deterministic transfer ID,
  observe real download state, and record completion/failure. Native mature
  workers retain provider extraction, muxing, verification and source recovery.
- Cancelled task journals reject late worker writes. Dismissing monitoring leaves
  its actual transfer accessible in Downloads.
- Model transfers check disk headroom, hash on the IO dispatcher, resume complete
  partials locally, and atomically promote verified files. Pause preserves partials.
- Model worker state is reconciled from WorkManager; failures are visible in Orez.
- Chat role delimiters in source material are escaped before local inference.
- Web has a validated address/search dialog, active-page context sync and an app back action.
- Android share targets handle links, images, PDF/ZIP/CBZ and video content URIs.
- Device-video scans run off the UI thread and handle permission denial.
- Preview version 2.3 includes exact source SHA/channel in Settings; PR builds
  embed head SHA rather than the synthetic merge commit.

## Work preserved

The mature reader/library, OCR fusion/reconstruction, translation quality gates,
Whisper paths, provider downloader, adaptive AV handling, WebView sessions and
protection engine remain in place. Their presence is not a claim of perfection.

## Still outstanding against the full master blueprint

- durable background chapter translation with per-page reconstructed output;
- a verified multi-step planner/executor across reader, vision, research and media;
- automatic discovery of next chapters and series glossary/RAG management;
- runtime browser DOM tooling with context-tagged evidence;
- complete home module hide/reorder controls and remaining appearance options;
- Max model pack, vision/inpainting packs, rollback and model signatures;
- measured training/fine-tuning, held-out multilingual and manga model evaluations;
- semantic library search, advanced panel-guided reading and global search;
- visual/device acceptance of every core screen, downloader and real subtitles.

Do not mark the overall blueprint complete from build success. Current Orez
supports bounded single-tool selection and a durable download workflow; it does
not yet execute arbitrary multi-step plans. Existing chapter translation is
owned by the app ViewModel and is not guaranteed to resume after process death.

## Acceptance

Run JVM tests, lint, both APK assemblies, AndroidTest compilation, signature,
archive/native library verification, SHA-256 and APK size checks. Then on Android:
open every navigation route, deny video permission, import/share content, change
appearance, translate an untranslated chapter into a requested language, pause
and resume model downloads, and interrupt/reopen an Orez download. Verify the
build source identity before comparing against older APKs.
