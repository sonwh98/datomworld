Promoted from the glm session plan file (the delegate's deliverable text); session 13460ea9 lineage noted in the prompt. Closing meta line from stdout: the review is complete, READY, two advisory P3s.

# Static pre-gate review — Python C3-S1 exact-integer module diff (completed)

This task was a READ-ONLY review of the uncommitted C3-S1 state in this
worktree (branch `yang-python-c3-s1`); no implementation plan was needed
and no files were edited. The deliverable is the review report below,
delivered as the final chat response per the prompt's format
(Completed-GMT header, verdict, per-item findings).

## Verdict: READY (2 advisory P3 findings, non-blocking)

### Evidence base reviewed
- `src/cljc/yin/vm/integer.cljc`, `src/cljc/yin/vm/integer/host.cljc`,
  `test/yin/vm/integer_test.cljc` (new, read directly)
- Diffs: `engine.cljc` (scalar? + require), `values.cljc` (kind-of),
  `data.cljc` (data-number?), UCF doc (+6 lines, §7.5.1 scalar arm)
- Cross-checked against both governing collab findings and the live
  module/registration code (`module.cljc:113/191/559`, `vm.cljc:336-373`,
  `data.cljc:44-49/473-494`, engine call sites 699/1943/2050).

### Per-item results (all 7 checks pass)
1. Carrier rule ✓ — promote-before/demote-after on every kernel; JVM
   `bitLength < 64` ⟺ [-2^63, 2^63-1] (exact); Dart `isValidInt`; JS
   `±(2^53-1)` with ±2^53 held as BigInt; no host `=` on big values
   anywhere (all value tests via `compare-big`); -2^63 ÷ -1 pinned.
2. No forbidden additions ✓ — no payload kind / AST tag / UCF marker /
   cell-lift change; recognition in exactly the five ruled surfaces;
   no stream-transport widening anywhere.
3. Module hygiene ✓ — kernels once over the shim; shim requires nothing
   (no Jing privates); :pure/#{} /:none profiles via the same
   `register-host-module` path as `yin.vm.data`; counters untouched;
   no `integer/*` in `vm/primitives`.
4. Limits ✓ — explicit, no default; :bit-limit vs :digit-limit distinct
   ex-info data, fixed message, never operands/host text; early refusal
   demonstrated for mul/shift-left/pow/format detectors (see P3-2 for
   bound tightness).
5. Cross-host hazards ✓ — JS ±2^53/unsafe-Number/-0 handled per Jing's
   classification; Dart isValidInt boundary; CLJS BigInt never hashed
   (scalar leaf, `seen` never sees it), pinned on Node+Dart; :cljd-first
   reader conditionals correct; js-bigint? fully :cljs-gated.
6. Tests ✓ — 18 deftests; carriers pinned both directions (value AND
   carrier — the actual skip-demotion mutation detector on JVM where
   `(= 5N 5)` is true); all five recognition surfaces; nothing vacuous;
   host-built oracles (`big`, `n`), a few legitimate algebraic-law
   expectations.
7. Scope ✓ — zero prelude/lowering/Jing/fixture changes; UCF amendment
   is 6 clarifying lines, no marker, cell-lift refusal preserved.

### Findings (advisory, do not block S1)
- **P3-1 (versioning inert):** `module-version` (integer.cljc:72-75) is
  declared but never threaded into `register-host-module`
  (module.cljc:191 has no version slot; manifest carries none), and the
  profile address hashes only name/class/arities/effects/host-state
  (vm.cljc:336-352) — a future semantics change with unchanged
  signatures would register byte-identically. Wire the version into the
  manifest or profile name before any v2 exists / before the S2 runtime
  profile admission must match it.
- **P3-2 (early-size gates are conservative bounds):** pow's pre-gate
  `(bits(x)-1)·n+1 > max-bits` (integer.cljc:209-214) and format's
  `(bits(x)-1) ≥ ceil(log2 r)·max-digits` (:383-386) are lower bounds;
  borderline oversized results are transiently built (≤ ~2× the bit
  limit for pow, ≤ ~1.6× digits for format) then refused by `out` /
  post-count. `parse` in power-of-two radixes has no pre-build bit gate
  at all (digit gate exempt by design, bit limit only via `out`).
  Nothing oversized is ever returned; refusals are deterministic and
  correctly typed. Tighten (pow's exact result bit count and
  n·log2(radix) for parse are cheap) or document the bounded overshoot.
  Footnote: the guards' native arithmetic is exact for any physically
  realizable limit; only degenerate `::max-bits` near 2^53 could round.

### Next step
None required for S1 acceptance. Forward the two P3s to the S2
(runtime-profile admission / literal lowering) slice where both become
load-bearing.
