# Preview 2 engineering checkpoint

This checkpoint repairs issues found in the previous preview and adds receipt-scoped
series memory controls. It is an engineering preview; the full autonomous blueprint
and Android acceptance gates remain open.

## Changed behavior

- Preserve captured source-caption metadata through Web-to-Video navigation. Prefer
  available, matching provider captions before speech recognition; resumed work
  revalidates its exact saved caption track and never silently switches sources.
- Bound Video resolution from the original request, including cancellation of an
  older request. Preserve the existing browser resolver's access restrictions.
- Recover corrupt or missing Reader originals through verified acquisition. ZIP,
  CBZ and PDF page provenance supports scoped recovery from unchanged documents;
  changed documents and obsolete transfers cannot replace current page bytes.
- Keep personal Reader corrections separate from native saved output. Explicit
  series association, chapter order, glossary edits, history, removal and rollback
  use private journals and current source/output/geometry receipts.
- Recheck chapter, model, owner and resource policy immediately before native work.
  Resource deferrals retain durable progress; cleanup can still finish under queue
  saturation. Speech's original 20-second total deadline remains unchanged.
- Preserve negative audio priming timestamps during muxing and retain actual audio
  and video samples. Keep player controls reachable in the retained layouts.
- Package the sixteen used extended icon vectors with pinned upstream byte hashes
  and bundled Apache attribution, instead of all 11,105 extended icon classes.

## Separate self-test installation

`assembleSelfTest` produces ARM64 and x86_64 APKs for `com.mangalens.selftest`, labeled
**MangaLens Self Test**. Its private data and download provider are separate from
`com.mangalens`. A cloud debug signing key differs from the previous preview's key,
so this variant allows testing alongside the existing installation. It does not
import the existing installation's library, models or preferences automatically.
The self-test variant uses the main network policy and omits debug QA activities.

Both main and PR workflows now build, verify and install the self-test variant,
and require its physical Home/Settings coexistence test during full acceptance.
Do not uninstall the existing application to resolve a signing-key mismatch.

## Verification recorded so far

The saved build-3 evidence refers to an explicitly dirty source snapshot based on
`08ca908c35e0a3593c84b683ed967fed1a069715`, rather than a final release commit.
See `evidence/integrated-build-3/verification.json` and its APK/source manifests.

- App JVM results: 1,453 tests, zero failures, errors or skips.
- Native Orez JVM results: 16 tests, zero failures, errors or skips; Gradle reused
  valid unchanged module results. Combined total: 1,469.
- Android lint: zero errors and 77 warnings.
- Both app variants compiled and assembled for both ABIs; archive, signature,
  package identity and required native runtimes were verified.
- The initial full command failed on two Android-test API mismatches. Their narrow
  test-only corrections compiled and packaged successfully in the subsequent run.
  Build 4 then completed the full command successfully, including fixture cleanup
  and bundled attribution. Its before/after 749-file source snapshots match;
  see `evidence/integrated-build-4/verification.json`. A fresh commit build follows.
- Focused Android acceptance is incomplete. The managed host has no KVM. Its earlier
  software-emulator run exceeded the original installation bound and repeatedly
  displayed a System UI ANR. A later launch rendered the earlier candidate's Home;
  this observation does not establish Reader, caption, speech or phone performance.
- The subsequent sealed build-3 run installed both APKs within their original bounds
  and exercised real acquisition. Eleven source-recovery controls and four document
  tests passed, including PDF recovery; same-path pixel refresh also passed. Android
  8 accepted invalid PNG IDAT data as an incomplete Bitmap, exposing a real rejection
  gap. Two null-decoder fixture preconditions also failed. Some Reader UI controls
  were not found within their unchanged deadlines. The final result was 17 passes
  and eight failures. A runner-edit error occurred after instrumentation completed;
  the preserved raw results were independently parsed as failures. These results
  do not apply to build 4.
- A fresh full build of clean commit `0b6223f8b6df3dfcbe54a0eda697e9c181d59aa5`
  succeeded with both variants, both ABIs, lint and the Android-test APK. Its 749
  build inputs were unchanged, package verification passed, and every app APK DEX
  contains that exact commit. Unchanged JVM results were reused by Gradle.
- The subsequent ten-test device filter completed five failures and aborted on a
  browser Main-thread Compose owner exception. Home, Reader correction and series
  editor controls missed their original UI deadlines; the remaining five cases
  were not run. A separate two-test browser run reproduced the same process crash
  during initial page loading, before any caption click. Neither run passed.
- The public PR build failed before Android execution because the Kotlin compiler
  daemon exhausted its one-GiB heap. The next batch explicitly sets a three-GiB
  daemon heap for all APK and unit/lint commands; a new CI run must verify that change.
- The next dirty-source build (6c, still based on `0b6223f`) completed the full
  unit/lint/debug/self-test/Android-test command successfully. Its 808 before/after
  build inputs match. App JVM results contain 1,610 tests and the unchanged native
  module contributes 16, with zero failures, errors or skips. Lint reports zero
  errors and 80 warnings. Both variants and ABIs passed package verification;
  their application DEX bytes match across ABI splits for each variant.
- The sealed 6c browser trace reproduced the Main-thread Compose owner exception:
  zero passes, one failure and abnormal instrumentation completion. The actual
  WebView completed its first measure immediately before the crash. No test
  cleanup or WebView disposal marker preceded it, and the original document
  deadline had not expired. This rules out cleanup as the trigger in that run;
  initial attachment/measurement remains under diagnosis.
- A later matched-APK API26 run passed all 14 raster/chapter-source recovery
  controls with normal instrumentation completion, exact test inventory and
  verified installed APK hashes. It includes the original invalid-PNG raster
  rejection assertions. An earlier attempt stopped before tests when startup
  log collection timed out; a separately sealed collector repair retains the
  existing software first-frame allowance and rejects historical frame events.
- The unchanged rounded-lettering method subsequently completed as a failure:
  its first vertical alignment assertion passed, then UiAutomator threw a stale
  control exception during the Original-toggle sequence. The paged assertions
  were not reached. Its HUD trace records original timer/animation behavior;
  this run establishes neither a complete geometry pass nor a geometry failure.

## Remaining gates

Run unchanged Reader mode/restoration/frame oracles and the new source-recovery,
correction, association and provider-caption tests against matched APKs. Recheck
the previous full CI failures, including original-source OCR quality, native
localization completion/latency and the live speech deadline. Validate ARM64,
capture consent, hardware codecs, thermal behavior and battery use on capable
physical devices before making those claims.

Build 6c includes strict PNG raster checks, native generation completion receipts,
captured memory packets, scoped Orez memory retrieval, the source-caption backend
and bounded startup/Reader/browser diagnostics. Their JVM verification does not
establish Android acceptance. Actual API26 BitmapFactory
qualification confirms the two replacement malformed-WebP fixtures have intact
bounds and rejected pixels; that is fixture qualification, not an app repair pass.

In-place Web caption controls, Library retrieval controls and research remain
under integration after the 6c seal. Advanced Reader tools and
evaluated specialist/Max models remain open. The requirements ledger retains those
distinctions.
