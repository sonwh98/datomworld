Created-GMT: 2026-11-01 19:08:06 GMT
Created-Local: 2026-11-02 02:08:06 +07
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: datagram-linux-diagnosis
Role: Scoped Investigator (read-only analysis; run tests allowed, no source edits)

Implementers:
- Model: gpt-6-astra | Assigned: 2026-11-02 02:08:06 +07 | Status: active | Rationale: owner instruction — investigate on Linux, where the failure reproduces; astra for the hardest diagnostic.

GOAL: explain, with measured Linux evidence, why `dao.stream.datagram.dart-test/refusals-and-lifecycle-events` fails on Linux and pass on macOS — and propose the minimal correct fix or guard.

THE FAILURE: at test/dao/stream/datagram/dart_test.cljd:289-301, the subtest "a bind on a taken port deposits bind-failed" binds a second datagram seam (`bind! "loser" t3 dest 1200`) on a port already held by seam-a, then polls t3 for 5s for a `:dao.stream.datagram/bind-failed` event and asserts `(some? failed)`. On Linux no bind-failed event is deposited. The surrounding subtests (send-failed, lifecycle events) pass.

WHAT IS ALREADY KNOWN (verify, do not trust): docs/agents/build-n-test.md's "Known differences from the laptop" says a second UDP bind on a taken port evidently behaves differently on Linux than macOS, and that this is INFERRED, NOT CONFIRMED — it is treated as a known Linux-only failure until fixed or guarded.

INVESTIGATE, on THIS Linux host (do not delegate to the laptop; do not reason only from the source):
1. Reproduce it here: run the failing test alone (the repo's Dart lane tooling: `bb src/dev/cljd_agg.clj` or the flutter/dart invocation it uses; pick the narrowest runner that executes this one namespace). Record the exact command, output, and elapsed time.
2. Find the host seam: read src/cljd/dao/stream/datagram/*.cljd (or wherever the dart:io RawDatagramSocket binding lives) and the test's bind! helper. Determine what the seam does on a bind failure and how a bind-failed event is supposed to be deposited.
3. Establish the platform difference with a minimal native probe — a tiny dart script (in /tmp, not the repo) that (a) binds a RawDatagramSocket to 127.0.0.1:0, (b) reads its port, (c) attempts a SECOND bind to that port, and (d) prints what happens: exception? which type/message? silent success? a different errno path? Compare that against what the seam expects. If dart's bind is asynchronous, check whether the error surfaces asynchronously (listener event vs thrown) and whether the seam's error path is reachable at all on Linux.
4. Determine whether SO_REUSEPORT/SO_REUSEADDR semantics differ (Dart may set reuse flags by default), or whether the second bind simply SUCCEEDS on Linux when it fails on macOS — test that directly rather than assuming.
5. Conclude: is this (a) a genuine platform behaviour difference that the test should encode (with the assertion split per platform or the expectation corrected), (b) a seam bug on Linux that should deposit bind-failed and does not, or (c) a race/timing issue? State which, with the evidence that distinguishes them.
6. Propose the minimal fix or guard, and say which of them the repository's conventions prefer (test-only guard vs seam fix vs documented known-failure update). Do NOT edit files.

Deliverable: a report starting exactly with
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
then: the verdict (a/b/c), the measured evidence including your /tmp probe's exact output, the platform facts you established, the proposed minimal fix/guard with its file:line, and the exact commands you ran plus any you could not run.
