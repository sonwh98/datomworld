Completed-GMT: 2026-10-12 06:02:58 GMT
Completed-Local: 2026-10-12 13:02:58 +07 (Asia/Ho_Chi_Minh)

**APPROVE-WITH-NITS**

No open in-scope P0/P1.

Reviewed [round-3 design](/Users/sto/workspace/datomworld/collab/1792234000000-architect-admission-stream-r3.claude-fable-5-1.findings.md) against the specified sources. This approves the architecture and acceptance requirements; implementation remains unverified.

| Requirement | Disposition |
|---|---|
| **P1-1 — Retirement** | **FIXED.** R3:58–78 uses `base <= k < parked-counter`, normalizes absent counters to zero, includes upper bounds, and matches child origins by equality or delimiter-qualified prefix. This matches [mint-link-id](/Users/sto/workspace/datomworld/src/cljc/yin/vm/module.cljc:492) and [child-origin allocation](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:1287). `:t0.10` cannot match `:t0.1`. Stopping at the first unrelated request preserves [serve ordering](/Users/sto/workspace/datomworld/src/cljc/yin/repl/link.cljc:451). Acceptance covers zero counters, nonzero counters, nested origins, same-pair reuse, and prefix isolation. |
| **P1-2 — Answer-drain starvation** | **FIXED.** R3:48/85 persists the successor cursor and matching answers after a bounded consuming drain. Irrelevant traffic advances without calling `recheck-pending`, so it cannot increment `:checks`. Gap recovery adopts [the transport’s earliest-retained cursor](/Users/sto/workspace/datomworld/src/cljc/dao/stream/ringbuffer.cljc:91). R3:92 covers an allow behind one full irrelevant window, gap recovery, and unchanged checks. |
| **P2 — Error rollback** | **FIXED in the design.** R3:96 carries the full admission successor through [link-raise](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1232) and [carry-served](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1249), with state updated before the raising `vm/run`. Acceptance checks serial monotonicity, cleanup, and absence of replay progress. |
| **P2 — Registry record** | **FIXED in the design.** R3:52 identifies the exact insertion point before `receive-module`; inheritance and dependency transfer retain the entry’s admission record. The F1 acceptance now compares manifest values correctly. |
| **P2 — ShiBi limits and executable checks** | **FIXED in the design.** R3:104 states composition-bounded synchronous verification over one snapshot, with no opening, fetching, or waiting, consistent with [ShiBi’s contract](/Users/sto/workspace/datomworld/docs/design/dao.shibi.md:53). R3:110 exercises argument identity and absent/ended behavior. AD-0 now specifies an executable comparison against reason vocabulary and actual budget/capacity values. |

F1’s retained-address binding and F5’s portable two-interpreter driver remain intact. The lost-test rewrite obligation and “must not land before L-e” restrictions remain explicit.

The last pass found only these **non-blocking** issues:

1. **P2 — Specify admission-ID comparisons consistently. Owner: admission interpreter engineer and VM engineer.**

   R3:48 says an answer’s id is “under `:asked`,” but `:asked` is keyed by **link id**, while answers use `[link-id serial]`. R3:85 similarly mixes a key’s link-id component with pending entries’ full admission ids.

   **Exact fix:** Retain answer `a` only when:
   ```clojure
   (= (:yin.admit/id a)
      (:yin.admit/id (get asked (:yin.link/id-component a))))
   ```
   Here `:yin.link/id-component` denotes the first component of the answer’s admission id, not a new datum field. Prefer spelling the actual extraction directly. Trigger the host recheck when the **full answer-map key** equals a pending entry’s `:admission` value. Add a stale-serial/same-link-id acceptance case.

2. **P2 — Narrow the expected `:retired` set in the nested-install acceptance. Owner: VM engineer.**

   R3:78(ii) requires exactly the run’s root and child ids in `:retired`. A root request that already received its response and spawned an install child is behind the link cursor; the proposed retirement walk cannot report it as newly retired.

   **Exact fix:** Assert that `:retired` contains exactly the run-owned requests still encountered from the current cursor before the first unrelated request. Separately assert that no run-owned admission remains and that the unrelated trusted require completes. Exercise root retirement in the unanswered-root case.

3. **P2 — Correct the answer-ring eviction claim. Owner: architect and host-composition engineer.**

   R3:48 says eviction needs 1024 answers between two ticks. With [evict-oldest transport behavior](/Users/sto/workspace/datomworld/src/cljc/dao/stream/ringbuffer.cljc:125), backlog can accumulate across ticks whenever production exceeds draining; fewer than 1024 arrivals per tick can eventually evict an unread answer.

   **Exact fix:** Replace that claim with: eviction occurs when the unread backlog exceeds capacity. State that host composition must bound accumulated backlog through its production rate and drain cadence. Keep the explicitly chosen lost-answer behavior—pending until retirement—and the gap-recovery acceptance.

Read-only architectural review completed. No files edited or suites run.
