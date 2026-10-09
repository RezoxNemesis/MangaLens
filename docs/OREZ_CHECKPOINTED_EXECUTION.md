# Orez checkpointed execution

This increment follows master blueprint sections 10.2–10.6: typed execution,
per-step validation, persisted results, interruption recovery and cancellation.

## Available workflow

Request: `Download these at 720p https://example.com/a.mp4 and https://example.com/b.mp4`

Orez retains each distinct supplied URL (up to eight), validates the entire plan,
and submits each transfer only after the previous step completes. Source URLs
and quality ceilings remain governed by the trusted tool registry. Conflicting
qualities, oversized batches and invalid later URLs reject the whole request
before any transfer starts. Open requests mentioning downloads remain open requests.

Every step checkpoints RUNNING before an effect. Its deterministic transfer ID
survives a crash between native enqueue and journal update. Replays observe the
same transfer; completed steps are skipped. Native workers continue owning large
transfers, resumable network requests, source refresh, audio/mux, media checks and
publication. Orez accepts completion only for the expected transfer, with a
storage result. Direct files must remain readable; adaptive downloads retain
their existing SDK-backed cache representation.

Paused tasks become WAITING. Failed tasks retain completed steps and errors.
Resume task restarts only unfinished work using the same IDs. Dismiss task stops
Orez's remaining steps/monitoring; its current transfer stays in Downloads.
Cancelling/removing a native transfer also cancels its owning Orez plan, before
the transfer row is removed, so a later recovery cannot recreate that download.
Android stopping WorkManager preserves the checkpoint rather than recording
user cancellation. Conditional journal writes reject late writes after dismissal.

Orez task cards show verified step counts and the latest error/wait reason.
Completed results are checkpointed as structured outputs, independent of chat.
Journal schema 1 reads older entries without outputs; no Room table migration is
required because outputs remain in the existing planJson field.

## Verification

JVM regressions cover interruption after an effect, stable replay identities,
skipping completed work, cancellation during execution, a malformed later step,
wrong-transfer evidence, pause/resume, failed-step retry, result persistence,
explicit quality, duplicate URLs, batch limits, ambiguous quality and untrusted
content. Existing runtime, task journal, download and translation checks remain.
An Android regression also closes/reopens a real Room database between steps
and checks restoration plus the conditional cancellation write. It is compiled
by CI; device execution remains pending.

Device acceptance still requires: batch two real sources, interrupt/reopen the
app, pause/resume the second transfer, fail/retry one source, dismiss midway, and
play both saved videos with audio. CI compilation is not phone runtime evidence.

## Remaining blueprint work

The executor currently admits download plans only. Reader/vision/research and
translation need durable tool providers before mixed-capability plans can run.
Chapter translation remains ViewModel-owned. No new Gallery enumeration tool,
filesystem access tool, paid-service dependency or model-granted permission is
introduced by this increment.
