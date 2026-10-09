# Orez Event Hints Implementation Plan

> **For agentic workers:** Implement inline under the user's explicit autonomous
> execution instruction, using superpowers:executing-plans. User authorization
> supersedes the skills' ordinary interactive design/plan approval handoff.

**Goal:** Wake only captured Orez work from bounded native hints while keeping
committed state and verified results authoritative.

**Architecture:** Typed process-local subscriptions own small drop-oldest queues
and bounded duplicate sets. Exact task identity filters fence native wakeups;
resource filters explicitly select verified model or memory observations.

**Tech Stack:** Kotlin 2.0.21, kotlinx.coroutines 1.9.0, JUnit 4.13.2; no new dependency.

**Spec:** `docs/spec/orez-event-bus.md`.

## Global Constraints

- No raw source content, secrets, URLs, cookies or private paths in events.
- At most 32 subscriptions, 8 buffered hints and 64 duplicate identities each.
- Hints never dispatch tools, grant authorization or establish completion.
- Keep the existing eight-minute download bound; fallback reads every two seconds.
- Root owns Gradle, adb, full-suite and emulator checks during this slice.

## Review Focus

- Wrong task/source/owner/epoch: no wakeup and no native read/effect.
- Duplicate/flooded hint stream: bounded storage and no extra completed effect.
- Cancellation while a hint is queued: queue disposed and subscriber removed.
- Hint before committed state: observe continuing work, never manufacture a receipt.
- Journal epoch changes during a read: reject the stale result.

### Task 1: Typed bounded bus and exact filters

Files: `core/events/AppEvent.kt`, `AppEventBus.kt`, and `AppEventBusTest.kt`.

- [x] Write failing tests for exact identity isolation, invalid identities,
      bounded drop-oldest delivery, duplicates and subscriber cancellation.
- [x] Run standalone Kotlin/JUnit and observe missing behavior fail.
- [x] Implement `AppEventBus.subscribe(filter): Subscription`, `publish(event)`
      and typed factories without free-form event payloads.
- [x] Run the same tests and inspect the actual report.

### Task 2: Durable snapshot waiter

Files: `core/events/CommittedEventWaiter.kt`, `CommittedEventWaiterTest.kt`.

- [x] Write failing tests for event-only noncompletion, exact hint wakeup,
      lost-hint periodic fallback, cancellation and post-read journal fencing.
- [x] Implement `awaitCurrent(subscription, pollMillis, isCurrent, read)`:
      verify ownership, read durable state, recheck ownership, await hint/fallback.
- [x] Verify using virtual-time tests and real channels.

### Task 3: Committed native hooks and Orez observer

Files: `orez/agent/OrezTaskEventPublisher.kt`, `OrezDownloadTaskWorker.kt`,
`download/MediaDownloadManager.kt`, `MediaDownloadWorker.kt`,
`MangaLensDownloadService.kt`, `orez/OrezModelManager.kt`; coordinate governor hook.

- [x] Add publisher tests for stable download-step ownership and epoch filtering.
- [x] Publish after successful committed DAO updates and verified model activation.
- [x] Subscribe from the captured task/epoch before enqueue, then requery exact row
      on hint or bounded fallback; retain existing media receipt checks.
- [x] Inspect changes for raw payloads, new dispatch paths and unmanaged scopes.
- [x] Report focused tests to root; root runs full build/emulator verification and
      owns the shared engineering commit.

Focused verification: 30 JVM tests passed, including exhausted-observer-capacity
fallback and recovered resource occurrences. Full Android compile, native device
checks and shared commit remain owned by the lead engineer.
