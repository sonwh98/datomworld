Created-GMT: 2026-11-18 15:43:21 GMT
Created-Local: 2026-11-18 22:43:21 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a1ea51-a87d-7062-ae12-e6c1c169b48c

# cc4 r2 bounded run declaration v1 (before budget measurements)

Revision: post `2687ab0cf03ebaa232be49a2333347d12a71d9a3` plus the cc4 test-only patch;
pre-port `8afd82f5e99d0f2b2d38a5f53b292105fdead688` (first parent of
port merge `309fe842`). Input corpus: the eight entries in `replay-artifacts`
in `test/yang/clojure_callsite_test.cljc`: destructuring/float/metadata/duplicate
native and argument-vector programs, lower-unit with bundled library, prelude,
macro twin with its producing log, second-token admission, and freshly published
`yang.call` and `clj` with their complete side artifacts. No runtime tracing.

The cc3 v2.1 baselines are reference observations, NOT cc4 measurements:
rows 312886 B, relation 856315 B, retained compiled-inclusive maximum
8924640 B, peak 272026880 B, pipeline maximum 772 ms. Its per-fixture ceilings
were 2 MB/artifact, 4 MB/aggregate, 12 MB retained, 1 GiB peak, 10 s pipeline.
The following finite ceilings scale these to an eight-member closure and allow
host representation differences. A sample exceeding a bound FAILS this run;
no bound will be raised in place.

| Quantity | Finite ceiling, both revisions, every host/configuration |
|---|---:|
| Unique canonical rows / occurrence positions / application positions, corpus | 50000 / 100000 / 10000 |
| Maximum path depth / encoded application paths, corpus | 256 / 8000000 B |
| Static side bytes per artifact / aggregate closure | 2000000 B / 32000000 B |
| Aggregate retained compiled-inclusive holder | 96000000 B incremental used heap |
| Absolute sampled heap peak | 2147483648 B |
| Absolute process-tree RSS peak, where heap unavailable | 4294967296 B |
| Retained process RSS, where heap unavailable | 4294967296 B (absolute; not heap-comparable) |
| Warm pipeline / cold pipeline (excludes toolchain compilation) | 80000 / 160000 ms |
| Each compile/expand/project/publish/load/query stage | 80000 ms |
| S−B allowed pipeline / retained / aggregate static bytes | 40000 ms / 96000000 B / 32000000 B |
| Trace records / capacity / trace bytes | 0 / 0 / 0 (trace disabled throughout) |

Storage aggregate uses 8 × cc3's 4 MB ceiling; retained uses 8 × 12 MB.
Peak heap uses 2 × cc3's 1 GiB ceiling; process RSS includes runtime/native
allocator/executable pages and therefore has a separately named 4 GiB ceiling.
Warm time uses 8 × 10 s; cold time is twice warm. Depth and input limits are
conservative finite preflight rejection bounds for this small pinned corpus,
including generated/prelude closure. No tagged execution is part of cc4.

B uses the same compiling inputs with C1 metadata removed before projection,
lowering/publication/persistence/load/query; S retains annotations and enables
those side operations. Both disable runtime trace. The production compiler
currently always annotates: the compile cost common to B/S includes that work.
S−B therefore measures downstream static-artifact overhead and cannot establish
the annotation producer's incremental compile cost. This limitation must appear
with the result; no claim of total zero compile overhead is permitted.

Procedure: one cold build, one discarded warmup, 3 warm pipeline samples per
configuration; 5 retained samples where forced GC is available (JVM and Node).
Hold one complete corpus at a time, release each holder before the next sample.
Record every sample and use MAXIMUM for every verdict. JVM: GC-pinned
Runtime totalMemory−freeMemory, 2 ms sampled used heap. Node: exposed GC,
process.memoryUsage().heapUsed and synchronous stage-boundary heap samples
(mark as a lower bound on the true transient peak). Dart/Jolt: named process RSS
if heap counters are unavailable; process RSS is never substituted for a heap
delta without recording the changed metric. Warm time excludes toolchain build;
class-loading/startup must be identified separately. Measure pre and post using
the same harness and inputs; any pre/post shift is allocated to port Phase 2
`d60038ef` as directed, with unrelated base differences stated as confounders.
D1 is dissolved and no correspondence acceptance is claimed.
