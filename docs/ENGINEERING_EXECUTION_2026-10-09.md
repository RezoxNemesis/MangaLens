# MangaLens engineering execution — 9 October 2026

## Authorized goal and constraints

Complete the attached full application specification through functioning native paths and meaningful tests. Continue from `engineering/mangalens-next-orez-foundation` at `b08b9ced12f08cf980194afb7d940bd24c8950c6`, which contains the protected mature ancestor `625b7d6efc602737ee3e53e7f4a3e459de816bba`. Preserve existing data, native routes, logo and download guarantees. No paid APIs, paid compute, Gallery tools for Orez, secret exports or production self-modification.

The supplied blueprint authorizes autonomous implementation decisions and independent parallel agents. It supersedes routine skill approval handoffs. This document records execution choices; the comprehensive feature contract is tracked in `MANGALENS_REQUIREMENTS_LEDGER.md` and runtime coverage in `ACCEPTANCE_MATRIX.md`.

## Baseline reconciliation

- Full Git checkout restored and protected ancestry checked on 9 October.
- The stopped uncommitted prototype and supplied visual recordings are absent from this session. Reimplement its intended behavior against verified source; do not claim recovery of those files.
- Workspace initially contained neither Android SDK, Gradle nor full JDK. Free official SDK/JDK/Gradle tooling is installed with archive checksums verified; exact environment is recorded below.
- `/dev/kvm` is absent. Evaluate a real software emulator and record boot/runtime results; hardware performance and phone thermal behavior remain separate acceptance.
- The dated successful CI run compiled instrumentation but skipped execution. That historical result cannot establish this candidate's runtime acceptance.

## Reliability design

1. Translation work moves from whole-chapter ViewModel ownership into WorkManager. Capture immutable language/style/OCR/refinement options; serialize heavy OCR; use per-page atomic journals and generation fencing. Persist cleaned PNG surfaces and lettering geometry/style while keeping original sources. Completed pages survive restart; partial pages preserve successful bubbles and label rejected work.
2. Reader loads cleaned surfaces only for visible translated pages, draws saved lettering at the requested scale, retains Original, and restores task progress. Switching chapters/configuration must not cancel unrelated tasks or publish stale results.
3. OCR waits for native completion before recycling a bitmap on cancellation, opens recognizers sequentially, evaluates overlapping regions independently, and applies selective crop retries. Promotional footers do not hide substantial story dialogue.
4. Playback attempts installed extraction first. One deadline and request-scoped cancellation bound resolution; Cancel/Back/Open source remain available. Navigation handoff never claims completed work.
5. CI removes the obsolete PR emulator skip and retains source identity, unit/lint/build/package checks plus actual instrumentation reports and screenshots.

## Implementation and validation sequence

- [x] Restore correct complete checkout and validate protected ancestry.
- [x] Install SDK35, NDK/CMake, platform tools, JDK17, Gradle8.9 and emulator images.
- [x] Run baseline/targeted JVM tests and record actual failures (checkpoint 3 full gate: app454/native10 pass, no failures/errors/skips; later fixes require a new gate).
- [x] Implement generation-fenced translation store, processor and worker with persistence/fault tests; device completion and quality acceptance remain separate.
- [ ] Integrate ViewModel task restoration, reader surfaces/lettering and controls; test actual UI paths.
- [ ] Repair extraction deadlines/cancellation and promotional/OCR defects; run regression tests.
- [x] Update executable CI/emulator acceptance and repeatable workspace scripts; independent provider host workflow is published.
- [x] Run checkpoint 3 JVM/lint/ARM64+x86_64 builds, instrumentation compilation, archive/native/signature checks; repeat for subsequent source changes.
- [ ] Install source-traceable x86_64 APK in a booted emulator, execute instrumentation, inspect screenshots/logcat and record resource observations.
- [ ] Review source changes independently, address material findings, commit coherent increments and maintain the authorized draft PR.
- [ ] Continue the next unresolved blueprint milestone; do not equate this reliability slice with full product completion.

## Evidence discipline

Keep exact commands, exit codes, reports, artifact hashes and test profiles. Environment setup errors are distinct from failing application tests. Compile-only, mock-only and unavailable-model checks do not establish inference quality. Any blocked acceptance stays blocked until execution evidence resolves it.

## Current execution checkpoint

- JDK17.0.20.1, Gradle8.9, Android SDK35, build-tools35/34, pinned NDK29.0.13113456, CMake3.31.6/3.22.1, platform-tools37.0.1 and emulator37.2.12 installed. Environment: `source /workspace/android-env.sh`. Repository wrapper pins Gradle8.9 distribution checksum. Setup archives/logs stay outside version control.
- Pristine mature source debug x86_64 APK and AndroidTest APK rebuilt successfully (5m35s and31s). This is baseline evidence, not modified candidate acceptance.
- Modified native AI and speech modules built for ARM64 and x86_64 (7m33s). Current complete app/lint/instrumentation compile gates await final reviewed source.
- First Google-API35 software emulator booted but had system-service ANRs; observed system failures do not establish an app crash verdict. It was stopped. Lean AOSP API28 x86_64 software emulator booted, installed baseline and displayed real MangaLens Home. QA profile720x1280 density280; no KVM available. Hardware thermal/performance remains untested.
- Verified actual pinned491,400,032-byte Qwen Lite file, SHA256 `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db`, official revision `872f8a96064a1242ac3a3359cad77c3042548405`, Apache2.0. Baseline native-model instrumentation has started; no result is recorded as passed until its real completion.
- Fresh native lease registry actual Gradle RED8/8 followed by GREEN8/8 establishes path/file identity rejection and last-owner release. It does not establish model language quality.
- Durable chapter store, request controls, full configuration selection, OCR reliability, Web lifecycle, Reader surfaces, native Orez chapter chain and executable acceptance scripts are implemented or in final review. Individual isolated/targeted test evidence remains distinct from pending whole-app/device gates.
- User required real Manhwa, YouTube and Instagram download/playback/Hindi-Hinglish/subtitle scenarios and partial results are recorded in [USER_ACCEPTANCE_FIXTURES_2026-10-09.md](USER_ACCEPTANCE_FIXTURES_2026-10-09.md). Manhwa host download passed for15 original pages; the app's tall-image attempt crashed and needs rerun. Actual GitHub provider attempts reached YouTube/Instagram but were denied by bot-sign-in/audience restrictions; no video download/playback or subtitle acceptance passed.
- Durable full-video subtitles are being implemented in isolated `engineering/mangalens-subtitle-durability`; Hinglish output target is staged separately. Neither is claimed complete.

## Executed checkpoint 3 and follow-up fixes

The immutable checkpoint 3 artifact/source bundle is at `/workspace/android-setup/candidate-checkpoint3`. Its fresh full Gradle gate passed in2m28, with464 JVM tests, lint, both ABI APKs and AndroidTest compilation. Archive signatures and16KB alignment passed. This does not cover later source changes or prove native model quality.

Actual Android18-test native/durability execution completed15 pass/3 failures, no skips. Hindi/Hinglish lettering proof survived real journal and Room reopening. Native extractor startup timed out20sec; journal deletion failed on an Android9 `.new` sidecar; one90sec chapter worker timeout occurred after OCR and before the first page commit. Root fixed explicit sidecar removal. A separate allocation regression reproduced the reconstruction hotspot and a reviewed primitive kernel is being staged without changing the test deadline. Software-device timing remains distinct from phone performance.

A completed product rerun reproduced Home restoring Library after Downloads→Library. Root changed only Home's saved-state restoration. Explicit page retranslation previously reused completed results; a scoped fresh-generation option now resets the requested page while retaining other results and original files, with39 store regressions passing. These changes need the next whole-app build and installed rerun.

Provider host diagnostics were committed as `bd350c8a52a2d66f92202ac7b4daf68ffcf85ef1` and `80fb43fd0797d79d9c9b2cd3b968cd7b9154ff78`. The second actual GitHub run passed setup and12 regressions, attempted both metadata and complete best-quality downloads, and truthfully failed on provider access restrictions. Main working app changes were preserved during public fetch/HEAD synchronization. No approval handoff is pending.


## Executed checkpoint 4

The full local Gradle gate passed after fixing one JVM-test compilation issue: AGP omits JDK management APIs from its Android compilation surface, so the glyph allocation test now reflects on the actual test JVM's public allocation interface. The original failed log is retained; the bounded allocation assertion remains enforced. The successful retry ran 623 app JVM tests and 10 native ownership tests with zero failures, errors or skips, plus lint, both ABI debug APKs and AndroidTest packaging. All 438 source-file hashes and the source set were unchanged during the successful gate.

The checked candidate uses dirty source on parent `80fb43fd0797d79d9c9b2cd3b968cd7b9154ff78`; it is not represented as a clean published commit. ARM64 APK SHA-256: `b763f828cc803cc37bf3d404651b7633629367a2cd32aa9cf56333e2d3ce0967`; x86_64: `ff66bd54b3f983b8de047535173d9ef17134238e5043fc9a3ce282ce8a6a0335`; test APK: `3e6cc6a9764fbe85231c7e28754e5ea0bb6055535eb7ac8efeaf872c0c1b4fef`. Signature and 16 KiB ZIP alignment checks passed. Archive comparison verified updated app dex and the actual packaged Whisper native library. Parent evidence is `/workspace/android-setup/candidate-checkpoint4/` with `source-manifest-retry1.json`, `build-assessment.json`, signatures and alignment records.

The integrated source includes native Orez-owned subtitle generation/export/recovery, model-transfer control journals, resumable range/source identity checks, Home customization, provider-specific failure/recovery and actual-provider test drivers, and bounded software tiles for tall manga pages. Each stage was independently source-reviewed and hash-checked before integration. Their Android runtime claims remain separate from compile/JVM evidence.

A real controlled local-video live-subtitle test on checkpoint 3 failed with zero captions. Investigation found that upstream `detect_language=true` requests detection only, and initial automatic language detection ignored the reduced audio context and abort callback. The reviewed exact-source Whisper patch preserves KV reset and the public API while applying the context before detection and forwarding abort callbacks through native backend execution. Real pinned host Whisper/Tiny/JFK checks recognized the exact 22-word reference on three reused-context runs (WER 0.0; 604/586/535 ms). A held active-cancellation regression changed from a late 299 ms abort to a 16 ms encoder abort. These are host results, not Android live latency or provider-caption acceptance.

Checkpoint 4 is installed on the retained-data AOSP API28 x86_64 software emulator. Core navigation, appearance, restored Reader, Home customization and tall-image tests are in progress. Native subtitle ownership/export, model transfer, real speech, all 15 supplied manhwa pages and Hindi/Hinglish quality, and opt-in YouTube/Instagram player/download tests still need actual device outcomes. Real GitHub-host provider downloads remain blocked by YouTube's sign-in check and Instagram's audience restriction; no downloaded-media or 1080p success is claimed. Full product completion remains pending.
