Created-GMT: 2026-09-16 13:54:37 GMT
Created-Local: 2026-09-16 20:54:37 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 03643531-72f9-42c4-9b2b-a6568acd8655

# Task: U5 — port tests off the v1 VM, pin parity values

Role: VM Runtime

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-16 20:54:37 +07 | Status: active | Rationale: VM-evaluator-facing test port plus a parity-pinning mechanism, matches VM Runtime strengths

## Context

`docs/design/yin.vm.v1-retirement.implementation-plan.md` — read section
"### D4 — parity against a deleted v1 becomes pinned values" in full (the
exact mechanism to implement) and section "### U5 — test ports off the v1
VM" (the acceptance criteria) before starting. Also read the Phase 0
pre-checks section's item 2 ("Capture the parity column (D4): run the four
parity deftests' v1 side once and record the values in the corpus") — this
unit IS that capture, done now while v1 still exists in the tree.

**A concurrent, independent unit (U1: deleting `dao.await` v1) is running
right now in this same working tree.** Your file set is fully disjoint —
U1 touches `src/cljc/dao/await.cljc` and `test/dao/await_test.cljc` only,
neither of which you touch. **Do not run `bb test:cljd` or any ClojureDart
compile/test command** — U1 owns the CLJD lane tonight. Verify on JVM
(`clj -M:test`) and, if genuinely needed for this unit's own criteria,
CLJS (`bb test:cljs`) — check the actual plan criteria for which hosts
this specific unit requires before assuming you need all three.

## Task

Per D4's disposition and U5's scope, exactly:

1. **Pin `yin.vm.parity-test`'s corpus.** Read `test/yin/vm/parity_test.cljc`
   in full first — understand its current shape (`[name ast]` rows compared
   live against v1) before changing it. Extend each corpus row `[name ast]`
   to `[name ast expected]`, with `expected` computed by actually running
   v1 (`yin.vm`/`yin.vm.ast-walker`, still present in the tree right now)
   once and recording the literal captured value in the source — not a
   formula, the actual value. Rewrite the four deftests referenced by D4
   to compare v2 against the `expected` column instead of live v1. The FFI
   and stream round-trip cases (also named in D4 — find them in the file)
   get the same treatment. Keep the file's name; its docstring should say
   what the `expected` column is and when it was captured (today's date).
2. **Port the three `yang` tests** (`test/yang/{clojure,python,php}_test.clj`
   — confirmed in tonight's Phase 0 sweep to `:require [yin.vm :as vm]
   [yin.vm.ast-walker :as ast-walker]`) onto `yin.vm` — same assertions,
   evaluator underneath changes from v1's `ast-walker` to `yin.vm`'s
   evaluator(s). Confirm which v2 evaluator(s) these should run against
   (check whether the existing `yin.vm.parity-test` or another v2 test
   file already establishes the right pattern for how a v1-style corpus
   test gets ported, and mirror it).
3. **Port `test/yin/module_test.cljc`** similarly — same treatment.
4. **`ast_conversion_test` coverage check** — confirmed in tonight's Phase 0
   sweep that `test/yin/vm/ast_conversion_test.cljc` exists (a v1 test,
   slated for deletion in U6). Confirm `test/yin/vm_test.cljc` (the v2
   test file) already covers its two cases; if it doesn't, port the
   missing coverage into `v2_test.cljc` now — this task's job is to make
   sure nothing is lost when the v1 file is deleted in U6, not to defer
   that check.
5. `test/README.md:137,143`'s template should name `yin.vm` instead of
   `yin.vm` — small doc fix, part of this unit per its own stated criteria.

**Every deftest's assertion count must stay unchanged**, except where a
case was deliberately ported into `v2_test.cljc` (state exactly which
cases, if any). v1 itself stays completely untouched and still present in
the tree — this unit only adds/changes tests and the parity corpus, it
does not delete anything.

## Verify

- `clj -M:kondo --lint` on every file you touch.
- `clj -M:test` — full suite must pass; report the exact count, and
  confirm it's unchanged from before this unit except for the specific,
  named cases you ported.
- `bb test:cljs` only if the plan's own U5 criteria actually require CLJS
  verification for this specific unit — check before running; if not
  required, skip it and say so.
- Do NOT run `bb test:cljd` or any `clj -M:cljd` command under any
  circumstances for this unit.
- Do not stage or commit. Do not touch `src/cljc/dao/await.cljc`,
  `test/dao/await_test.cljc`, or delete any v1 file — v1 stays present
  until U6.

## Deliverable

Report back: the exact diff, the captured `expected` parity values and how
you captured them (exact command run against v1), the exact verification
commands and output, and the exact assertion-count-before/assertion-count-
after comparison the plan's own criteria demand.
