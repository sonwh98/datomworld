Completed-GMT: 2026-11-07 14:47:08 GMT
Completed-Local: 2026-11-07 21:47:08 Asia/Ho_Chi_Minh

**Ruling: refactor-first for the DHT boundary; independently verified fixes may merge separately. R7 is overturned.** R1–R3 remain storage work within that refactor sequence, subject to the corrections below.

The owner’s boundary diagnosis is supported. The stronger claim that relocation itself fixes every DHT-family red is not established: byte-bound enforcement and repair scheduling require behavioral changes and verification alongside extraction.

Read-only review of master `3d2f20db`, the specified artifacts, and the two workaround commits. No files edited or tests run. This approves a direction and acceptance criteria, not a merge.

| Severity | File:line | Invariant/evidence | Recommended correction |
|---|---|---|---|
| High | [repl.cljc:942](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:942) | Engine construction selects and opens DHT composition, then retains its node. This violates the requested dependency boundary. | Construct the store and transport outside the engine; inject storage and explicit request/result media. |
| High | [repl.cljc:1355](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1355), [query.cljc:66](/Users/sto/workspace/datomworld/src/cljc/yin/repl/query.cljc:66) | Coupling extends beyond the disputed module: linker progression, query services, rollback state, and event filtering carry DHT knowledge. | Extract these adapters too. A namespace move alone fails the boundary gate. |
| High | [repl/index.cljc:368](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:368) | Count-cap compatibility protects frontier reuse. The subsequent `restore` receives no publication budget. | Preserve the comparison against a generic publication policy; thread byte policy through bulk, session, restore, and incremental publication. |
| High | [space/index.cljc:1180](/Users/sto/workspace/datomworld/src/cljc/dao/space/index.cljc:1180) | The recorder contains emitted dirty blobs, not necessarily every reachable blob. Checking it alone cannot certify a reused graph against a newly lowered budget. | Reuse budget verification only under the same policy; otherwise verify the closure or rebuild it before publishing. |
| High | [btree.cljc:2031](/Users/sto/workspace/datomworld/src/cljc/dao/data/btree.cljc:2031) | Byte grouping changes occupancy assumptions. Large separator datoms can prevent branch grouping from reducing the node count. | Specify underfull-node mutation behavior and a terminating refusal for an unrepresentable tree; test both before enabling byte cuts. |
| Medium | [addendum:27](/Users/sto/workspace/datomworld/collab/dao-stream/1794057461000-orchestrator-owner-addendum-stream-of-chunks-not-bytes.md:27) | The earlier proposal promotes transport chunks and acknowledgements into the stream contract. The latest owner instruction supersedes it. | Keep fragmentation/reassembly and transport acknowledgements below the logical stream interface. |
| Medium | [dht_test.cljc:2097](/Users/sto/workspace/datomworld/test/yin/repl/dht_test.cljc:2097) | The “eight distinct” assertion counts **manifest addresses**, not acknowledgement timestamps. | Diagnose failed publication/HEAD advancement; do not relax a supposed timing pin. |

1. **Sequencing and merge gates**

   **DHT-family reds:** begin the boundary refactor without first greening the coupled architecture. Do not merge fix 5’s `64` override or `cb2cbe11` as prerequisite baseline repairs. Their measurements remain useful evidence; their placement and constants are not architectural commitments.

   Before moving behavior, establish the contract pins in point 3 and record the existing failures against exact revisions. Then build a reviewable commit sequence: engine/composition separation, generic storage budget enforcement, composition wiring, and measured repair scheduling. Intermediate commits can remain on the refactor branch. **Merge the completed boundary change only when the affected DHT-family tests pass**, including acknowledgement, Dart ticker-idle, and the identified Node composition failure.

   **Boundary-neutral reds:** the PHP frontend installation, compile-EOF regression pin, golden correction, stack-size prepend, and Linux V8 sizing may merge before or during this work as isolated changes. Gate each on its previously failing tests and affected host behavior. In particular, the prepend must preserve arguments, namespace enumeration, output, and exit status; stack sizing must produce the guest recursion error rather than a host crash.

   **R1–R3:** implementation can proceed against the agreed generic policy while extraction proceeds, but their DHT activation belongs after the boundary is established. They are not a prerequisite patch to the existing REPL/DHT seam. Prefer merging their independently reviewed commits with the completed refactor stack.

   Storage acceptance requires bulk and incremental publication, restore/mutation, empty and singleton trees, oversized input refusal, manifest compatibility, and cross-host determinism. Integration acceptance requires the affected JVM/Node/Dart matrix, with slow test bodies actually enabled. Follow the repository’s scoped gate ladder; repository-wide green is not a prerequisite for starting this work.

2. **Minimum composition API**

   The relocated module can be a small public composition surface—illustratively `open`, `step`, and `close!`—outside `yin.repl`. It owns the engine value alongside transport state; the engine does not own or step that transport state.

   | Boundary | Minimum contract |
   |---|---|
   | Engine construction | A `dao.jing` content-store handle, immutable generic publication policy, validated recovery data, and explicit request/result streams. No peer, cookie, repair-slot, or DHT-node options. |
   | Engine publication output | A correlated publication request containing the round and opaque manifest address, after the necessary content has materialized and read back successfully. |
   | Composition publication result | Explicit local durable-publication success or failure. Network dissemination results remain distinct from local durability. |
   | Recovery/link input | Validated recovery installation and generic content/link completion data. The engine does not decode DHT event keywords. |
   | Composition ownership | Store opening, HEAD persistence, announce, signed-head deposit/following, hydration, transport stepping, scheduling, banners, refusal rendering, and shutdown. |

   Preserve the current publication ordering: content/read-back → durable HEAD → announce/deposit. Promote the engine’s published frontier only after durable HEAD success. Replacing `:after-publish` with request/result data must preserve that ordering and the existing synchronous local-publication behavior; it must not accidentally introduce network waiting or premature success.

   Head following updates the external resolver. It does **not** automatically replace the session’s local index. Explicit pinned hydration installs recovery before evaluation; ordinary following continues without blocking evaluation.

   The existing linker/query adapters must use the same outward dependency direction. Renaming `:dht` to an opaque runner while continuing to manipulate its state inside the engine would preserve the coupling.

   **R5 lives at the transport/store composition boundary.** Resolve the effective transport configuration once and use it consistently for the DHT state, store refusal, and declared payload capability. Currently [space/dht.cljc:281](/Users/sto/workspace/datomworld/src/cljc/dao/space/dht.cljc:281) constructs the store front using defaults; supporting configurable budgets must update this wiring too.

   Derive a payload budget satisfying:

   `maximum encoded store-message size(payload-budget) + margin ≤ effective message bound`

   Use the actual wire encoder, maximal query identifier, cookie, supported address forms, and byte-string header widths. A measured envelope for one payload size is insufficient as a universal subtraction. Preserve final full-message refusal. Neither `dao.jing` nor the engine should acquire DHT semantics; `dao.space` remains responsible for semantic page cuts.

   **Yes, line 369 can be restated without a manifest schema change.** The store composition may supply a generic `dao.space` publication profile containing `:branching-factor` and the byte capability. Strictly, the branching factor is an index construction policy, not an intrinsic byte-store capacity.

   Keep the manifest exactly `{:indexes … :count … :branching-factor …}`. Its branching factor continues to mean the count cap used for construction/restoration. Compare against that declared policy, retaining the other frontier checks. Track budget compatibility in live composition state; changed or unknown policy requires revalidation/reconstruction, not a new content-hashed manifest field.

3. **Invariants to pin before relocation**

   A partially red integration suite permits starting the refactor only when failures are classified and narrower invariant tests provide a trustworthy oracle.

   - **Frontier gate:** manifest identity, published transaction count, count-cap compatibility, cursor ownership, and expected suffix length must agree. Stale/ahead/foreign cursors reconstruct fully. Missing content or failed HEAD persistence must not advance the frontier. Existing fixtures include [index_test.cljc:385](/Users/sto/workspace/datomworld/test/yin/repl/index_test.cljc:385) and [index_test.cljc:421](/Users/sto/workspace/datomworld/test/yin/repl/index_test.cljc:421).
   - **Restore Settings threading:** the manifest’s count cap reaches every restored node and recording storage. Strong session references and durable refault sources remain intact. Recovery derives allocator authority from history rather than saved process cursors. Byte policy reaches the restored publisher separately.
   - **Repair cadence:** one owner advances transport state using supplied time. Fresh writes retain capacity while repairs progress; queues stay bounded; delayed future repairs do not imply perpetual busy status. Preserve the twenty-publication/400-tick fixture’s requirement for both active repair and subsequent idle periods.
   - **Cross-host parity:** identical supported inputs, publication path, and policy yield identical bytes/addresses across JVM, Node, and Dart. Bulk and incremental paths need logical equivalence; they need not produce identical tree shapes.
   - **Lifecycle and causality:** hydration gates input; failed bind/load refuses rather than starting empty; `heads.edn` persistence precedes head installation; relevant completions resume parked work once; rollback preserves already-settled effects; shutdown closes resources once.

   Pin the smaller invariants green before moving their implementation. Keep known failing end-to-end fixtures as failing acceptance tests until the completed refactor fixes them. If a Node compilation/composition failure prevents those pins from running, restore that test path first—absence of results is not evidence of preservation.

4. **R1–R6 adaptation**

   | Requirement | Disposition |
   |---|---|
   | **R1** | **Core survives in storage; not wholly as written.** Encode-once length accounting is sound for legal canonical leaf/branch encodings. Include separator datoms, addresses, framing, and level encoding. An oversized singleton may exist temporarily during planning, but must not escape as a successful publication. Branch construction must reduce or explicitly refuse. |
   | **R2** | **Survives with completion requirements.** Check before intake emission; rebuild an oversized incremental index through the bounded bulk path. Use clean staging so rejected blobs/cached addresses cannot leak into the replacement publication. Validate the manifest too. Handle reused clean subgraphs under changed budgets. Recorder length checks need no extra encodes; rebuilding does. |
   | **R3** | **Schema/count-cap rule survives as written.** Preserve configurable count caps, including existing small-factor fixtures; 512 remains the default, not a universal requirement. Retire the DHT-specific 64 override when bounded publication is active. Underfull restore/mutation behavior remains a required proof. |
   | **R4** | **Measurement principle survives; sequencing is replaced.** Neither blanket preservation nor blanket reversal of `cb2cbe11` is justified. Measure repair capacity in the new composition before merge. No entitlement attaches to 16, 40, or 48 slots. |
   | **R5** | **Moves into composition design.** Transport derives the capability; storage consumers receive generic policy. Full-message verification survives. Hardcoded REPL/index transport budgets die. |
   | **R6** | **Survives and broadens.** Measure real REPL leaf and branch sizes, full wire sizes, counts per index, distinct addresses per round, repair occupancy, and idle behavior before pinning configuration. Include bulk, suffix, and restart paths. |

   **R7 is explicitly overturned:** byte-cutting the existing coupled REPL composition on `nightly-reds-fix` before extraction is no longer the approved sequence. Worktree reconciliation, independent review, and cross-host verification remain valid requirements.

   The older addendum’s “stream of acknowledged chunks” restatement also dies. Respect the latest owner ruling that transport chunking does not redefine the byte-stream abstraction. The existing generic `dao.stream` API additionally describes whole values; this task does not authorize rewriting those values or seven operations into a universal chunk protocol.

5. **Nightly exposure during the refactor window**

   Maintain an exact failure ledger keyed by revision, host/runtime, test identifier, assertion signature, reproduction command, evidence path, and responsible change. Distinguish master failures from workaround-branch regressions: the recorded Dart evidence says ticker-idle passed on master `3d2f20db` and failed with fix 5.

   Continue executing affected lanes and retain complete logs. Classify a result as a **known boundary failure** only when its signature matches the recorded failure. A new assertion, timeout, compile failure, missing namespace, skipped slow body, or incomplete runner summary is a new or unverified result—even inside an already-red namespace.

   Reproduce new failures narrowly, then compare base and candidate under the same environment. Hold the responsible change until resolved; do not widen exclusions, weaken pins, or treat an unchanged aggregate failure count as “no regression.” Unrelated verified fixes may continue merging. The boundary merge requires its known failures closed and no unexplained affected-test regressions.

The properties that **passed architectural review** are transport ignorance in the engine, explicit composition ownership, transport-derived framing limits, semantic page cutting in storage, unchanged manifest schema/count-cap meaning, independent evaluator/index observers, and separation of local durability from network acknowledgement. Runtime preservation and readiness to merge remain unverified.