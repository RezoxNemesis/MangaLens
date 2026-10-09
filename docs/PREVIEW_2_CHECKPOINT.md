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
  were not found within their unchanged deadlines. The aggregate result remains
  incomplete until the runner finishes; these results do not apply to build 4.

## Remaining gates

Run unchanged Reader mode/restoration/frame oracles and the new source-recovery,
correction, association and provider-caption tests against matched APKs. Recheck
the previous full CI failures, including original-source OCR quality, native
localization completion/latency and the live speech deadline. Validate ARM64,
capture consent, hardware codecs, thermal behavior and battery use on capable
physical devices before making those claims.

In-place Web captions, captured glossary/dialogue generation packets, scoped Orez
memory retrieval, broader search/research, advanced Reader tools and evaluated
specialist/Max models remain follow-on work. Their outside-checkout preparations
are not delivered features. The requirements ledger retains those distinctions.
