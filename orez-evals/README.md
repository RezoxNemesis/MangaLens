# Orez evaluations

The Android JVM gate runs the real deterministic planner, model-output decoder,
tool registry, policy and durable task codec. These are executable behavior tests,
not proof of model intelligence or device performance.

Covered boundaries:
- library questions stay in conversation rather than opening a screen;
- explicit player mode overrides URL heuristics;
- requested translation language reaches the tool;
- unknown tools, forged permission metadata and credential URLs are rejected;
- untrusted documents cannot initiate app mutation;
- model chat delimiter tokens remain data;
- dismissed tasks cannot be resurrected by a late worker checkpoint;
- download intents resolve the active source into a durable job.

`OrezModelPlanDecoderTest` evaluates structured model outputs against the same
decoder used in production. Real Lite/Core inference success rate, latency, RAM,
thermal behavior and multilingual tool choice still require device evaluation.

`MediaDownloadRequestIdentityTest` is an Android instrumentation regression for
queue recovery and deterministic transfer identity. CI assembles it; it must run
on a device before runtime acceptance is claimed.

Training runs must be evaluated separately against held-out requests. Do not
equate a larger conversation dataset with a better tool-using model.
