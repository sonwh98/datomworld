# H3-1 wedge diagnostic, round 2 — the findings report

Created: 2026-11-04 (GMT) · Agent: glm (glm-5.3) · Branch: `hardening3-h3-1` at `8ea857c1`
Task: BRIEF-wedge.md (per the architect's continuation ruling, steps 3–4)
Artifacts: `test/d2_wedge_probe2.clj` (stages A–D, F + gate stage C),
`test/d2_wedge_min_repro.clj` (arms A–E); evidence logs in this directory
(`1794100000000-engineer-h3-1-wedge.probe2-run1|-gate1|-walker1|-walker8m|.minrepro.log`)

## Verdict in one paragraph

There is **no non-terminating loop in the encoder session layer, the observer
coordination, or the semantic evaluator** — and the kernel-c semantic path
contains no encoder session at any commit. The round-1 "evaluator terminates /
wedge lives in the encoder session layer" split was an artifact of a vacuous
probe. The gate stall that motivated this round is real, it reproduces, and it
lives **after** the walker cell's legitimate budget exhaustion: the gate's
`kernel-attempt` hands the exhaustion exception's `:consumer` — the entire
advanced machine — to `evidence-data` → `upstream-record-data`, whose
`clojure.walk/postwalk` re-expands it with five `requiring-resolve` calls per
node and no cycle guard. That host-side walk, not any VM loop, is what burns
2400s+ with zero guest steps. D2's real regression is smaller and factual: it
pushed this fixture's **walker** cell over the 2M budget (the semantic cell
still completes at 1.1M).

## 1. Round 1's probe was vacuous — the layer-location built on it is retracted

`test/d2_wedge_probe.clj` builds `semantic/create-vm` + `tu/make-observer-session`
and calls `observer/run-on-stream` — but **never queues the fixture onto the
medium** (no `tu/queue-rows!` anywhere in the file). An observer session over
an empty medium observes nothing (`:retry`/`:ended`), never calls `load`, never
enters `run`. And a fresh `SemanticVM` is minted `:halted? true`
(`src/cljc/yin/vm/semantic.cljc:432`), so the untouched machine reads "halted".

Measured (stage A, `…-probe2-run1.log` in this directory):

```
:STAGE-A-RESULT {:steps 0, :loads 0, :halted true, :blocked false,
                 :verdict :VACUOUS-no-program-ever-loaded}
```

Zero steps. The "terminates, halted, well under 2M" measurement in commit
`8ea857c1` measured a machine that never executed an instruction.

The same commit message's premise — "kernel-c wraps the machine in
tu/make-encoder-session + encoder/forward-on-stream" — is false at `8ea857c1`
and at every commit since `run-guest-machine` was introduced (`7723c6ae`): the
kernel-c path is `tu/make-observer-session` + `tu/queue-rows!`/`queue-ast!` +
one `observer/run-on-stream` call (`test/yang/clojure_test.clj:1995-2009`),
single stage, no encoder. The only encoder references in `clojure_test.clj`
are the descriptor tests (lines 785, 1028, 1047).

## 2. The real kernel-c semantic cell completes (stage B)

Driving the exact kernel-c semantic call —
`run-guest-machine ast (oracle-registry yang/kind-module) :semantic 2000000` —
instrumented at `vm/step` and at the `run-on-stream` boundary:

```
:WORKER-DONE {:kind :returned,
              :steps 1105792, :in-step-ms 2535, :loads 1, :ready-checks 3,
              :runs [{:entry-steps 0, :exit-steps 1105792, :threw false}],
              :halted true, :blocked false,
              :value [:yin.k/ok [:ok [(<a>) (Π [A ✳] (Π [B ✳] …))]]]}
```

1,105,792 steps (55% of budget), 2.5 s of stepping, correct proof outcome. This
matches the pre-D2 measurement (`0dc54289`: both fixtures complete inside 2M
after the call-lowering fix) — D2 did not make the semantic cell explode.

## 3. The observer/encoder layer cannot be the loop (code + measurement)

The brief's candidate — the budget throw caught by `carry-session` and fed back
via `consumer-left-by-run` — does not exist:

- `carry-session` (`src/cljc/dao/stream/observer.cljc:211-214`) **rethrows**:
  `(throw (ex-info … (assoc (ex-data e) :session session) e))`. Both catch
  sites in `run-on-stream` (`observer.cljc:250-255` and `277-282`) call it
  directly; neither recurs.
- `consumer-left-by-run` (`observer.cljc:183-196`) only reads `:consumer` from
  the exception's data to attach to the carried `:session`. It is never a loop
  variable; no caller re-enters `run` with it inside this harness.
- `run-on-stream`'s loop re-enters only on `:advance` (a batch observed), `:gap`
  (recovery cursor), or not-ready→run→ready. This harness queues exactly one
  batch: measured `loads 1, runs 1`.
- Arm C of the minimized reproduction drives the semantic cell at a 10,000-step
  budget: the exhaustion throw **propagates out** of `run-on-stream` with
  `:consumer` (advanced machine), `:session` (partial session), and the cause
  preserved. Recovery is opt-in data for callers; there is no retry cycle.

## 4. Where the gate actually stalls — the exact site, caught in the act

Stage C drove the full 106-fixture differential gate
(`test-kernel-differential-gate`) under the same instrumentation with a
freeze detector. It froze mid-corpus, in `check-assume-have-discharge`
(fixture 50 of 106), **inside the walker C cell** — the opposite lane from the
D2 report's attribution:

```
:LEG :a …wall-ms 5      (check-assume-have-discharge)
:LEG :b …wall-ms 89
:LEG :compile …wall-ms 51
:C-ENTER {:lane :walker, :at-steps 3786531, :total-s 91}
   … steps advance to 5786531 (= 3786531 + exactly 2000000) in ~20 s …
   … then zero steps for ≥300 s; no :C-EXIT ever prints; detector fired
```

The captured worker stack (top frames, full dump in
`1794100000000-engineer-h3-1-wedge.probe2-gate1.log` in this directory):

```
clojure.lang.Var.getThreadBinding → requiring-resolve
yang.latte_oracle_test$upstream_fn.invokeStatic        (latte_oracle_test.clj:286)
yang.latte_oracle_test$upstream_record_data$…invoke    (latte_oracle_test.clj:1025)
clojure.walk$postwalk → into → reduce → LazySeq.force → clojure.walk$postwalk …
```

The causal chain, with line references:

1. `run-bounded` (`test/yang/clojure_test.clj:1987-1993`) counts 2,000,000
   walker steps — the walker cell of this fixture now **exhausts** the budget
   (it completed inside 2M before D2) — and throws
   `"Guest instruction budget exhausted"` with `{:lane … :steps … :consumer v}`
   (`clojure_test.clj:1991-1992`; the `:consumer` addition is the H3-1 fix
   round's own one-line harness change).
2. `run-on-stream`'s run-catch (`observer.cljc:277-282`) wraps via
   `carry-session` — a cheap rethrow adding `:session`.
3. `kernel-c`'s body runs inside `kernel-attempt`
   (`test/yang/latte_oracle_test.clj:1659-1671`), whose catch builds
   `:data (evidence-data (dissoc (ex-data e) :session))` — it strips `:session`
   but **not `:consumer`**, so the entire advanced machine enters
   `evidence-data`.
4. `evidence-data` (`latte_oracle_test.clj:1645-1656`) hits `(record? x)` — the
   machine is a record — and calls `upstream-record-data`
   (`latte_oracle_test.clj:1014-1028`): a `clojure.walk/postwalk` over the
   whole machine that calls `(upstream-fn pred)` — i.e.
   `requiring-resolve` (`latte_oracle_test.clj:284-286`) — **five times per
   node**, then `evidence-data` recurses into every child, and every child
   record (each VM `Closure`/`Continuation` is a record) triggers another full
   `postwalk` of its subtree.

That is the "wedge": an unbounded, zero-guest-step, host-side expansion of the
exhausted machine. Whether it is truly non-terminating (a cyclic closure
environment — recursive guest functions whose captured env binds their own
closure record, on which `postwalk`, which has no seen-set, never terminates)
or merely hours-scale (per-record re-walk of shared env chains × five
`requiring-resolve` per node) is settled by the cycle scan in §6.

This also re-explains the D2-era observations without the encoder theory: the
2400 s/5400 s "semantic cell wall-clock explosion" runs were this same
post-exhaustion expansion, mid-corpus, with no per-cell attribution recorded
("guest-step counts NOT yet captured" in `805d81d6`'s message).

## 5. The exhausted walker cell in isolation (stage D) — bounded state capture

Same driver, walker lane, alone (no corpus context):

```
:WORKER-DONE {:kind :threw, :message "Guest instruction budget exhausted",
              :steps 2000000, :in-step-ms 10591, :loads 1,
              :runs [{:entry-steps 0, :exit-steps 2000000,
                      :threw "Guest instruction budget exhausted"}],
              :data-keys [:lane :steps :consumer :session],
              :consumer {:halted? false, :blocked? false, :cont-depth 1,
                         :control {}, :wait-set 0, :ready-queue 0,
                         :value ["_" 6]}}
```

The exhaustion itself is fast (≈11 s) and well-behaved: the throw propagates,
`:consumer`/`:session` ride along exactly as the recovery design intends. The
brief's requested capture at wedge time: the advanced consumer is **neither
halted nor blocked**, continuation depth 1, mid-walk control, gensym value
`["_" 6]` — the walker died inside fresh-supply generation under the proof
loop, not in any stream or admission machinery.

## 6. Minimized reproduction — `test/d2_wedge_min_repro.clj`

Five arms, each runnable alone (`clojure -M:test -n d2-wedge-min-repro -v
d2-wedge-min-repro/test-h31-wedge-min-repro`, ≈4–6 min):

- **arm A** — round 1's drive, verbatim (session, no queue):
  `{0 steps, halted true}` — the false negative in three lines.
- **arm B** — the same session plus the one omitted line (`tu/queue-rows!`):
  completes at **1,105,792 steps**, halted, correct outcome.
- **arm C** — semantic at a 10,000-step budget: the throw escapes
  `run-on-stream` carrying `:consumer` + `:session`; recovery is opt-in.
- **arm D** — the gate freeze mechanism, isolated: drive the walker cell to its
  exhaustion throw, then time exactly kernel-attempt's
  `(evidence-data (dissoc ex-data :session))` under a 120 s cap with a stack
  dump. Result: **unfinished after the 120 s cap** — stack shows the same
  `clojure.walk$postwalk` recursion the gate freeze showed. The walker drive
  itself took ≈11 s; the post-throw expansion is >10× that and still running
  when capped.
- **arm E** — an identity-based DFS cycle scan over the exhausted `:consumer`
  (the same shapes `evidence-data`/`postwalk` traverse), reporting unique
  container count, off-path sharing, and whether any node re-enters the DFS
  path (a true cycle ⇒ `postwalk` never terminates). Result:
  `{:unique-containers 78913, :shared-revisits 3442, :cycle nil}` — **no
  cycle**. The machine is a finite DAG, so the walk terminates *eventually*;
  its cost is the re-expansion: every record child (each VM Closure/
  Continuation) triggers `upstream-record-data`'s own full postwalk of its
  subtree, and the 3,442 sharing points make those subtrees overlap —
  finite-but-astronomical, which is exactly the shape "2400 s × 2 stalled,
  5400 s never returned" describes: not an infinite loop, an expansion that
  had not finished inside the trial window.

## 7. How far over the budget is the walker? (stage F — bounded trial)

At an 8,000,000-step budget the walker cell **completes at 2,024,912 steps**
(11.8 s in-step) with the same correct outcome as the semantic cell:

```
:WORKER-DONE {:kind :returned, :steps 2024912, :halted true,
              :value [:yin.k/ok [:ok [(<a>) (Π [A ✳] …)]]]}
```

The walker misses the 2M budget by **24,912 steps — 1.2%**. D2's per-call
packet dispatch pushed a cell that previously completed inside 2M (commit
`0dc54289`) just barely over, and the harness's post-exhaustion expansion
turned that near-miss into the multi-hour "wedge". The semantic cell needs
1,105,792 (55% of budget).

## 8. Recommendation

1. **No yin.vm / observer / encoder architectural ruling is needed.** Nothing
   in `src/cljc/**` non-terminates or misbehaves; the observer recovery
   protocol did exactly what it is designed to do. No shared-runtime change is
   requested.
2. **The gate stall is a one-line harness defect** in
   `test/yang/latte_oracle_test.clj:1667`: `kernel-attempt`'s catch feeds the
   exhaustion's `:consumer` machine to `evidence-data`. The comment above that
   very line already classifies `:session` as "diagnostic machinery, not a
   portable domain payload; recursively expanding its closure environments
   duplicates the whole program" — `:consumer` is the same class of payload.
   Proposed next-round fix: `(dissoc (ex-data e) :session :consumer)`, or
   replace `:consumer` with a bounded snapshot (halted/blocked/cont-depth/
   control) before expansion. latte_oracle_test.clj is out of bounds THIS
   round by the brief, so this is a recommendation, not a change.
3. **The D2 landing decision should be re-based on these numbers:** semantic
   cell 1,105,792 steps (completes); walker cell >2,000,000 (exhausts; see §7
   for the true count). The "wall-clock explosion" was the harness amplifying
   the walker's exhaustion, but the exhaustion itself is D2's per-call packet
   dispatch cost on the walker lane. Whatever the landing round decides
   (budget raise is DEFERRED by ruling; trimming the dispatch is a compiler
   change requiring its own scope), it no longer needs to explain a semantic
   wedge.
4. **Correct the record:** commit `8ea857c1`'s conclusion line ("the wedge
   lives in the encoder session layer") should be superseded by this report;
   `test/d2_wedge_probe.clj` should either gain its missing queue line or be
   retired in favor of `d2_wedge_probe2.clj` (this round leaves it untouched —
   it is the previous round's committed artifact and the kimi gate reviews
   before landing).

## Reproduction ladder (all at `8ea857c1`, JVM 17, -Xss64m, this worktree)

| run | command | outcome |
|-----|---------|---------|
| stage A+B | `clojure -M:test -n d2-wedge-probe2 -v d2-wedge-probe2/test-h31-wedge-probe2` | A vacuous (0 steps); B returns at 1,105,792 steps |
| stage C (gate) | `clojure -M:test -n d2-wedge-probe2 -v d2-wedge-probe2/test-h31-wedge-probe2-gate` | frozen mid-corpus in the walker cell of check-assume-have-discharge; stack = evidence-data/postwalk; exit 3 by design |
| stage D (walker isolated) | `… -v d2-wedge-probe2/test-h31-wedge-probe2-walker` | throws at exactly 2,000,000 steps in ≈11 s |
| stage F (8M trial) | `… -v d2-wedge-probe2/test-h31-wedge-probe2-walker-8m` | returns at 2,024,912 steps (1.2% over the 2M budget) |
| min-repro A–E | `clojure -M:test -n d2-wedge-min-repro -v d2-wedge-min-repro/test-h31-wedge-min-repro` | A 0 steps; B 1,105,792; C throw propagates; D >120 s unfinished (stack = postwalk); E 78,913 containers, 3,442 shared, no cycle |

clj-kondo on both new files: 0 errors, 0 warnings (run through the lane's
ProcessBuilder shim, deleted after).
