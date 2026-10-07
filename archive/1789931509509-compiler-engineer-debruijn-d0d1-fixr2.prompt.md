Created-GMT: 2026-09-20 19:11:49 GMT
Created-Local: 2026-09-21 02:11:49 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: 923b8885-4549-4b46-ad11-0731ebb614ef (resumed — your D0+D1 build session)
# Task: apply the review round's accepted findings to the D0+D1 build
Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-21 02:11:49 +07 | Status: active | Rationale: same implementer, warm session; corrections are narrow

# Task: apply the review round's accepted findings to the D0+D1 build

The review (claude-fable-5-1) returned READY with three P2 and six P3. The
orchestrator accepted P2-1, P2-2, P2-3, P3-1, P3-2, P3-3, P3-4 and P3-5.
Apply them in src/cljc/yin/vm/debruijn.cljc and
test/yin/vm/debruijn_test.cljc only — the box from your build brief is
unchanged.

- **P2-1 (frame-datoms ~357-367)**: datoms from ignored namespaces must not
  count toward a partial frame. A stream that ends in trailing
  `:yin.code/*` (or other non-yin) datoms after the last root marker frames
  cleanly; only `:yin/*` datoms keep a frame open. Add a framing test:
  complete graph, then trailing decoration from another namespace, no
  `:partial-frame`.
- **P2-2 (~606-610)**: emit `:yin.debruijn/macro?` only when the source
  `:yin/macro?` is truthy. An explicit `false` and an absent attribute
  must project identically. Add that test.
- **P2-3 (canonical value table ~59-60, 75, 242) — ORCHESTRATOR RULING**:
  split the `:seq` class into `:vector` and `:list`. A program can
  observe the difference (`vector?`), so merging is an undeclared semantic
  collision, and the design's principle is that identity never merges
  distinguishable values. Update the value table, the classification, any
  descriptor wording that names classes, and the tests. The dimension hash
  re-minting this causes is accepted pre-D2. (The design doc amendment is
  the orchestrator's job, not yours.)
- **P3-1 (~728)**: do not assoc `:yin.debruijn/root true` into the root
  node map — the `:root` wrapper of the return value is the marker, and the
  node map must equal the same term appearing as a subterm. Adjust any test
  that relied on the assoc.
- **P3-2 (~458 with 180-184)**: guard the `:int64` slot check with
  `(number? v)` so a non-number on cljd produces the
  `:unsupported-value` diagnostic instead of a host type error.
- **P3-3 (~142-149)**: the dimension docstring must say the descriptor is
  stable but its digest is transitional until D3 re-pins it over the settled
  encoder.
- **P3-4**: add one docstring line noting that on a JS-compiled Dart target
  `int?` holds for integral doubles, so the safe-integer rule would apply
  there too (the cljd lane is native today).
- **P3-5**: add a memo-purity test — project the same graph with the memo
  active and with the memo forced to miss (cleanest seam you have; e.g. an
  injectable memo or a binding), asserting byte-identical output node maps.

When done, rerun and report exact counts for: focused JVM
(clojure -M:test -n yin.vm.debruijn-test), kondo on both files, and cljstyle
check. The orchestrator will rerun the full three-host lanes itself — you do
not need to. Same environment rules as the build brief (mise exports, one
simple command per step).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact outcomes, any finding you could not apply and why.
