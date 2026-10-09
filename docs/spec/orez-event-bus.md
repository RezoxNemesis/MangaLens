# Orez task-relevant event hints

The autonomous mission blueprint §10.22 requires CHAPTER_LOADED,
OCR_LOW_CONFIDENCE, DOWNLOAD_FAILED, DOWNLOAD_COMPLETE, MODEL_READY,
STREAM_EXPIRED, SUBTITLE_TRACK_CHANGED and MEMORY_PRESSURE. This increment gives
those hints a bounded typed transport and connects real download task wakeups,
verified model activation and the resource monitor. Chapter/subtitle producers
remain separate work; existing stores and durable journals are preserved.

Events cannot authorize or dispatch tools, grant capabilities, complete a task,
or replace a native receipt. A native-owned task hint contains only domain-hashed
opaque task/source/owner identities and the captured nonnegative execution epoch.
Raw URLs, headers, cookies, titles, OCR/dialogue, error messages and private paths
are absent from the schema. Model readiness carries the verified publisher hash;
memory pressure carries a typed level. Model and pressure observations carry a
bounded scalar occurrence so a verified reactivation or recovered pressure
transition can notify an existing observer again. Global observations are separate
from exact task-owned hints and require an explicit resource filter.

The process-local bus keeps at most 32 subscriptions. Each owns a queue of at
most 8 hints and a bounded 64-entry duplicate set; overload drops old hints.
There is no replay or persistence. Subscriptions close on coroutine cancellation
and close discards queued hints. Each task filter matches every captured field
exactly; there are no task, owner, source or generation wildcards.

The Orez download worker subscribes before enqueueing its already-authorized
native effect. Its observer rechecks current journal ownership and reads exactly
the captured native download row after a relevant hint or a two-second fallback.
An eight-minute existing outer wait remains bounded. It returns only committed
terminal state; the existing published-media evidence checks and executor
checkpoint fencing still decide whether work completed. Lost, duplicated,
premature and stale hints cannot create a download or receipt.
If all subscription slots are occupied, the same task continues through its
bounded periodic committed-state reads without obtaining a hint subscription.

Native download producers emit only after a successful committed completion or
failure row update. The publisher resolves the owning task and current epoch
from Room, verifies the stable request ID belongs to its download step, and
silently skips missing/cancelled/superseded ownership. It performs no new network
work and installs no UI-owned observer. Model activation emits only after its
verified atomic journal commit; the resource monitor emits observations only.

Validation covers exact identity isolation, queue/subscriber/duplicate bounds,
cancelled subscription disposal, event-only noncompletion, lost-hint polling,
post-read journal fencing and current-generation receipt selection. Standalone
Kotlin/JUnit runs avoid concurrent Gradle or adb; the lead engineer runs the
whole app build/test and emulator gates after integration.
