Completed-GMT: 2026-10-30 20:43 UTC
Completed-Local: 2026-10-31 03:43 +07
Coding-Agent: glm (glm-5.3, session 4e8a1c72-9d35-4b6f-a251-3c7b8e5f0d14; round 2 session 5f9e2b8a-1c47-4d3e-9a62-8b0f7e4c1d93)

# J5c: the ANTLR full-context reach-set leak — closed in the bounded simulator

Engineer seat, worktree /Users/sto/workspace/datomworld-j5b (branch jolt-j5b,
rebased on master b140782d — already up to date). Nothing committed. Two files
changed: `native/jolt-parser/jolt_parser.cpp` (the fix) and
`docs/agents/known-gaps.md` (the leak row's closing-slice column).

## The defect, confirmed in the pinned runtime

`ParserATNSimulator::execATNWithFullContext`
(build/native/src/antlr4-cpp-runtime-4.13.2/runtime/src/atn/ParserATNSimulator.cpp:351-421)
keeps the previous reach set as a raw pointer released from its `reach`
unique_ptr (line 415 `previous = reach.release()`), and frees it on two of its
three exits:

- the normal exits: `delete previous` (lines 387-388) each iteration, then
  `previous = nullptr` before every break, and the surviving set stays owned by
  the `reach` unique_ptr;
- the no-viable-alt exit: `noViableAlt(..., previous, ..., previous != s0)`
  passes the set into `NoViableAltException`, whose shared_ptr conditionally
  deletes it (`deleteConfigs`);
- **the exception exit**: an exception thrown out of
  `reach = computeReachSet(previous, t, fullCtx)` (line 368) unwinds with
  `previous` a live raw pointer and the `reach` unique_ptr holding nullptr —
  nothing frees it.

J5b's lookahead guard throws from exactly that path (`check` → `getEpsilonTarget`
→ `closure` → `computeReachSet`, in full-LL prediction), so each full-LL
parser-budget refusal leaked one prediction set — the constant 181,088 bytes /
3,312 allocations at 0/5/50/100 cycles on shiva.

## The fix (function-level diff)

`BoundedSimulator` (the class both parse entries install) gains a
`computeReachSet` override — the ten-line reach-computation override the sign-off
prescribed. `computeReachSet` is virtual in the pinned runtime
(ParserATNSimulator.h:462), and the full-context loop's call at line 368 is its
only fullCtx=true caller: the override sees exactly the endangered set as its
`closure` argument.

- On the SLL path (`fullCtx=false`, the `computeTargetState` caller at line 292)
  it is a pure pass-through: that caller's set belongs to its DFAState, and its
  reach set is already unique_ptr-owned. Never freed here.
- On the full-context path it wraps the base call and, on any exception out of
  the reach computation, frees the caller's set before rethrowing — except the
  start set. The start set is the one the caller keeps: `execATN` holds it in
  the `s0_closure` unique_ptr (line 245) and frees it on unwind, so
  `computeStartState` (already overridden for `full_base_`) now records
  `full_s0_` and the catch spares it. Every later iteration's set was
  `reach.release()`d — exactly the ones the runtime leaked.
- `full_s0_` lives for one prediction like `memo_`/`full_base_` (reset in
  `adaptivePredict`'s Clear). No nested `adaptivePredict` can intervene: the
  Python3 grammar's semantic predicates are the constant-true
  `CannotBePlusMinus`/`CannotBeDotLpEq` stubs (build/antlr/python3/cpp/Python3ParserBase.cpp)
  and `precpred`, none of which parse.
- The delete is the same destructor the runtime's own normal exit runs
  (`delete previous`), on the same 16 MiB parse thread, so the destruction-depth
  constraint is unchanged.

```diff
--- a/native/jolt-parser/jolt_parser.cpp
+++ b/native/jolt-parser/jolt_parser.cpp
@@ BoundedSimulator::adaptivePredict
     struct Clear {
       BoundedSimulator* s;
       ~Clear() {
         s->memo_.clear();
         s->full_base_ = 0;
+        s->full_s0_ = nullptr;
       }
     } clear{this};

@@ BoundedSimulator::computeStartState
     if (fullCtx) {
       ...
       full_base_ = n;
       ++stats_.full_predictions;
+      auto s0 = ParserATNSimulator::computeStartState(p, ctx, true);
+      full_s0_ = s0.get();
+      return s0;
     }
-    return ParserATNSimulator::computeStartState(p, ctx, fullCtx);
+    return ParserATNSimulator::computeStartState(p, ctx, false);

+  // The full-context loop (execATNWithFullContext) holds its previous reach
+  // set as a raw pointer released from a unique_ptr and frees it on its
+  // normal and no-viable-alt exits only (delete previous; the exception's
+  // conditional delete); an exception thrown out of the reach computation —
+  // where the lookahead guard throws — is the one exit that does not, leaking
+  // one prediction set per refusal. Free the caller's set on that exit: not
+  // the start set (the caller's own unique_ptr frees it, which is why
+  // computeStartState records it), and never the SLL caller's set, which
+  // belongs to its DFAState.
+  std::unique_ptr<antlr4::atn::ATNConfigSet> computeReachSet(
+      antlr4::atn::ATNConfigSet* closure, size_t t, bool fullCtx) override {
+    if (!fullCtx)
+      return ParserATNSimulator::computeReachSet(closure, t, false);
+    try {
+      return ParserATNSimulator::computeReachSet(closure, t, true);
+    } catch (...) {
+      if (closure != full_s0_) delete closure;
+      throw;
+    }
+  }

@@ members
   Stats& stats_;
   uint64_t full_base_ = 0;
+  // this prediction's full-LL start set, the one reach input the caller frees
+  const antlr4::atn::ATNConfigSet* full_s0_ = nullptr;
   std::unordered_map<const Ctx*, std::pair<Ref<const Ctx>, uint64_t>> memo_;
```

Plus the cpp header comment's lookahead paragraph gains the ownership sentence
(6 comment lines). The plain guards-off path is unchanged: the override is a
pass-through except on the exception exit; no new exported symbol (the export
lists are `jolt_parser_*` patterns), no CMake change, no .clj file touched.

## Results

All gates on macOS arm64 (this laptop, darwin-arm64, AppleClang 21), at the
rebased bytes (master b140782d + the fix, uncommitted).

**The leak experiment (criterion 1).** The sanitized harnesses were rebuilt
from the fixed `jolt_parser.cpp` (the driver rebuilt its ASan runtime, both
harness variants, and ran). Apple's clang ASan answers **"detect_leaks is not
supported on this platform"** for `ASAN_OPTIONS=detect_leaks=1` — LSan inside
ASan is absent on this Mac (which is why the sanitize driver's documented
macOS route is `leaks --atExit`) — so the shiva-form ASan experiment is the
orchestrator's Linux rerun; the local experiment runs the same three counts
through Apple `leaks --atExit`, plus a pre-fix control built from HEAD's
`jolt_parser.cpp` (build/j5c/prefix-control-src/): the control at cycles 0
already answers **exit 1 with HARNESS ok** — the leak is present and detected
pre-fix on macOS too. (final numbers pending the running legs)

**The budget gates (criterion 2).**

- `bb probe:jolt-j5` (all legs): first full run **55 PASS / 2 FAIL**, exit 1 —
  the two FAILs are one event: "C1' R1c at 10,000 ... within 15 s (the laptop
  limit)" parsed OK (`:outcome :yang.cst/ok`) at **26,329 ms** against the
  15,000 ms wall, plus its parent's "budget gates: child exited 0". The laptop
  was contended at that moment (load average 10.4-21.8; another session's
  Clojure test-runner held a core for ~35 min; `uptime` and `ps` captured).
  Rerun of the two wall-sensitive legs on the quieter machine
  (`bb probe:jolt-j5 budget below`): **42 PASS / 0 FAIL, exit 0**, "ALL PASS",
  with `r1c-10000` at **8,537 ms**, `power-100000` at 23,971 ms, `r4-20000`
  at 13,012 ms — every leg green on at least one run, and the wall margins
  (8.5 s vs 15 s) show the override costs nothing measurable (the pre-fix
  J5b-era baseline was ~11-13 s on this laptop).
- `bb calibrate:jolt-j5`: **exit 0, 15/15 shape records `:ok true`, 0 false**
  (record at build/j5b/calibration.edn; worst at-bound stack 1,633,357 bytes
  of the 4,194,304 quarter — deep-unary, unchanged from J5b's margins).
- `bb test:jolt-j5`: **5 PASS / 2 FAIL**, exit 1 — both FAILs are the two
  unavailable-library legs' check `(str/includes? out "yin> 3")`. Manual
  reproduction of the exact legs (build/j5c/repro-refusal.log) shows the
  substance is correct — the banner names unavailable-parser, `(lang
  :yang/python)` answers the refusal, Clojure answers `3`, exit 0 — but the
  macOS jolt REPL prints the answer as a bare `3` line (no `yin> ` prompt
  before it): `yin> Error: ...\n3\nyin> Bye` where the check (written from
  Linux, where shiva passed it at round 3b) expects `yin> 3`. No parser
  library is loaded in these legs, so the fix is not implicated; J5b never
  completed this test on macOS (the harness-limit waiver), so this is the
  first macOS observation of a pre-existing host prompt-shape divergence.
  Reported, not fixed (out of J5c's scope: it is jolt-REPL behavior, not the
  parser).

**The sanitizer soak (criterion 3).** `bb src/dev/jolt_j0c_sanitize.clj 5`:
**exit 0** — the ASan+UBSan harness printed `cycles 5` / `HARNESS ok`
(exit 0), and the macOS `leaks --atExit` pass (leaks ON, with
MallocStackLogging) printed `Process ...: 0 leaks for 0 total leaked bytes.`,
`LEAKS exit 0`, `HARNESS ok`. This is the point of the fix: the corpus reaches
HARNESS ok with leaks ON and nothing reported.

**cljstyle (criterion 4).** No `.clj`/`.cljc`/`.cljd` file changed by this
slice (`git status`: only `native/jolt-parser/jolt_parser.cpp` and
`docs/agents/known-gaps.md`). No new exported symbol (the export lists are
`jolt_parser_*` glob patterns), no CMake change. `cljstyle check src/` flags
7 pre-existing files at HEAD (dao/space/query.cljc, dao/stream/waitset/cadence.cljc,
yin/vm/encoder.cljc, sign.jolt, posix/file.jolt, stream/cbor/boring.jolt,
yang/antlr/cst.jolt) — none touched by J5c; reported, not fixed (other lanes'
bytes).

**known-gaps (criterion 5).** The leak row's closing-slice column now reads
"closed: J5c's `BoundedSimulator::computeReachSet` override frees the caller's
previous full-context reach set on the exception exit, the one exit the
runtime leaks". The row itself stays open for the orchestrator's Linux
evidence.

## Round 2 (verification)

Completed-GMT: 2026-10-30 20:43 UTC
Completed-Local: 2026-10-31 03:43 +07
Coding-Agent: glm (glm-5.3, session 5f9e2b8a-1c47-4d3e-9a62-8b0f7e4c1d93)

Every gate re-run fresh from the unchanged two-file patch (`git diff` still
jolt_parser.cpp + known-gaps.md only). Round 1's leak legs, pending at the last
write, had landed on disk (fixed 0/50/100: 0 leaks / 0 bytes each; control 0:
3,312 / 234,240 — build/j5c/leg-*.log); this round re-ran the whole experiment
and every gate firsthand (logs at build/j5c/round2-*.log). One operational note:
the first gate launches this round hit the harness's default 10-minute
background cap and were killed; all numbers below are from the restarted
2-hour-budget runs.

**Criterion 1 — the leak experiment reaches zero.** `bb build:jolt-native`
exit 0 first; the sanitize driver then rebuilt both sanitized harnesses from
the fixed jolt_parser.cpp. Apple clang ASan carries no LSan — the `fixed-asan`
leg answers `detect_leaks is not supported on this platform` (exit 1 at init;
round2-leg-fixed-asan-0.log) — so the experiment runs the same three counts
through Apple `leaks --atExit` (the driver's own macOS route), plus everything
the platform does give ASan:

- fixed-leaks 0 / 50 / 100: each **exit 0, `HARNESS ok`, `0 leaks for 0 total
  leaked bytes`** — the prior signature (constant on shiva; 3,312 / 234,240 on
  round 1's macOS control) is gone at every cycle count.
- control-leaks 0 (the pre-fix binary, provenance re-verified:
  prefix-control-src/jolt_parser.cpp diff-equal to `git show HEAD:...`):
  **exit 1, `HARNESS ok`, 1,696 leaks for 121,792 bytes**, stack roots through
  execATNWithFullContext → computeReachSet → closure_ → ATNConfigSet::add —
  the exact ParserATNSimulator signature.
- control-leaks 0, isolated rerun (nothing else running): **exit 1, 3,312
  leaks for 234,240 bytes — round 1's numbers exactly.** So the control's leak
  count varies with machine concurrency (the 1,696 leg ran beside five
  harnesses): thread interleaving changes how many refusals route through
  full-LL prediction, where the leak lives. Reported as an observation; the
  gate's substance — pre-fix leaks in exactly this path, fixed leaks zero at
  0/50/100 — is unaffected either way, and the orchestrator's Linux rerun
  pins the shiva-form numbers (constant 181,088 / 3,312 at every count).
- fixed-asan-run 0 / 50 / 100 (ASan+UBSan, halt-on-error, no LSan ask — the
  most Apple ASan permits): each **exit 0, `HARNESS ok`**.

**Criterion 2 — the sanitizer soak reaches HARNESS ok.**
`bb src/dev/jolt_j0c_sanitize.clj 5` **exit 0**: the ASan+UBSan harness exit 0,
`cycles 5`, `HARNESS ok`; the macOS leaks-ON pass (`leaks --atExit`,
MallocStackLogging) exit 0, `HARNESS ok`, `Process: 188 nodes malloced for
32 KB`, `0 leaks for 0 total leaked bytes` — leaks ON, nothing reported, the
fix's whole point.

**Criterion 3 — the J5 gates.**

- `bb probe:jolt-j5` (all legs): **57 PASS / 0 FAIL, exit 0, "ALL PASS"** —
  every leg green in one run, the wall legs included (`r1c-10000` 9,520 ms of
  15,000; `power-100000` 26,030 ms of 60,000; `r4-20000` 13,438 ms), and this
  while the sanitize soak's asan/leaks phases ran beside it at load ~6-10.
  Round 1's 2 FAILs were its load-10-21 contention; no rerun was needed this
  round.
- `bb calibrate:jolt-j5`: **exit 0, 15/15 shape records `:ok true`, 0 false**;
  worst at-bound stack 1,633,357 B of the 4,194,304 B quarter (deep-unary,
  unchanged).
- `bb test:jolt-j5`: exit 1, **2 PASS / 2 FAIL** — the two unavailable-library
  legs again, their transcripts byte-identical to round 1's log (diff empty).
  The raw REPL rerun (round2-repro-refusal.log) confirms the substance on this
  host: the banner names unavailable-parser, `(lang :yang/python)` answers the
  refusal, Clojure answers `3` on a bare line (no `yin> ` prompt; the check
  wants `yin> 3`), exit 0 — the pre-existing macOS host prompt-shape
  divergence, no parser library loaded in these legs. Reported, not fixed.
  (Correction to round 1's write-up: its "5 PASS / 2 FAIL" should read 2 PASS
  / 2 FAIL — its own log shows exactly these.)

**Criterion 4 — style.** No repo file touched this round (git status: the
round-1 two-file patch + collab/, unchanged). The one script edited
(build/j5c/leak_leg.clj, gitignored — the leg driver gained a `fixed-asan-run`
mode) is `cljstyle check` clean; no collab path in any comment. The 7
pre-existing cljstyle findings at HEAD stand as round 1 reported them.

**Residual leaks observed elsewhere:** none — every leaks-ON run of the fixed
binary (the soak at cycles 5, the legs at 0/50/100) reported 0 leaks / 0
bytes; the only exit-1 leaks results are the two pre-fix control runs, which
are supposed to leak.
