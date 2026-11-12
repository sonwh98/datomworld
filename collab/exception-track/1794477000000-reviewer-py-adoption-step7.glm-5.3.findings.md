Completed-GMT: 2026-11-11 21:08:16 GMT
Completed-Local: 2026-11-12 04:08:16 +0700
Reviewer: glm-5.3 (independent of sol / deepseek-v4-pro / the wiring authors)
Scope: branch `exception-py-r2r3` @ `428bbdd7` verified in `/Users/sto/workspace/datomworld-f12`
(`git log` confirms HEAD = 428bbdd7, clean vs origin). Read-only; no suites run; shiva
xtrack-py3 at this exact SHA accepted as the measurement of record.

## What was reviewed

The adoption arc inside a396ae28..428bbdd7: `04e58031`/`2ab72b31`/`9c97fae3` (rebased
R2/R3+egress — `git range-diff` against the reviewed originals d9a73150/24f2f467/a396ae28
shows `=`, byte-identical patches), `bb563fe1` (pins), `0f0ab852` (forgery contract pins),
`00ca2d1c` (the adoption), `428bbdd7` (scanner-pin regeneration). The other span content
(VM provenance primitive ff8b980a/566c5450/50c9485a, JS R1+R3, clojure cc2, PHP Round C)
belongs to arcs with their own reviews and is treated as landed context, re-consulted only
where the adoption depends on it.

## Per-question verdicts

**Q1 — Provenance wiring correctness: PASS.**
- Mint: `dsg__condition-exception` (prelude.cljc:1044-1053) mints through
  `cell/dsg__new-provenance` with the complete original condition — both call sites
  (prelude.cljc:1074-1076 R2 ForeignException wrap, :1078-1080 R3 TypeError wrap) pass the
  entire condition map delivered by the raise continuation, reason and payload included.
- Egress: the single boundary exit (`py/function-entry`, prelude.cljc:918) reads
  `(cell/dsg__provenance e)` before any forwarding decision; `nil?` → the plain fresh
  `:yang.call/thrown` with the value as payload. The engine reader authenticates first
  (engine.cljc:2828-2833: `authentic-ref?` — heap+seal) and answers nil for forged refs,
  dead ids, and non-refs — never fatal. `nil` ⇔ unmarked is sound because the mint refuses
  nil provenance (engine.cljc:2812-2814), so a marked cell always answers non-nil.
- No `:py/retained-condition` authority reads remain anywhere in python src — the only
  occurrences are the guest-side attack strings in the forgery pins
  (python_exception_test.clj:112-115) and the grandfathered TypeScript surface.
- The two remaining raise-exit calls (prelude.cljc:901, :903) deliver freshly constructed
  protocol refusals of the invocation itself before any body runs — correctly not
  provenance-authenticated. `py/function-entry` is the only `:yang.call/entry` installer in
  the prelude (prelude.cljc:935); there is no second egress.

**Q2 — R2 lifetime rules: PASS on every clause.**
- Identity-owned: provenance is the private `:provenance` heap sibling, installed only at
  allocation (engine.cljc:2811-2824); no content key is consulted.
- Outside source-writable attributes: `py/setattr` writes `:attrs` inside the content map
  via `cell/set!`; `heap-write` preserves the sibling (engine.cljc:2444-2450). The
  overwrite pin (python-retained-overwrite-test) exercises exactly this and the original
  condition still forwards.
- No ambient registry: nothing registered anywhere; the state rides the heap entry, traced
  by GC together with the value (engine.cljc:2580-2582, 2626-2629), so reachability of the
  exception retains the whole condition/payload cluster and dropping it reclaims both.
- Wrapper non-stacking: an already-local BaseException instance is re-raised raw
  (prelude.cljc:1067-1071); only otherwise-foreign payloads wrap, once, at reception; the
  unchanged rethrow forwards the original condition at egress, so no ForeignException ever
  contains another and repeated crossings never accumulate wrappers.
- Formatting never invokes foreign methods: the ForeignException is a locally generated
  class (fixed local message "foreign exception"); typed catches match the local hierarchy
  (`py.b/Exception` base), never guessed foreign names.

**Q3 — Export/alias audit (the sol-named risk): PASS.**
Verified statically, the python twin of the js exported-helper class does not exist:
- `core/exports` (yang/core/prelude.cljc:71-76) keeps only keys in namespace `py`;
  `dsg__condition-exception` is a bare symbol → not in `module-exports`. Nothing else in
  the prelude wraps the mint — exactly four `dsg__` occurrences in the whole python tree
  (host-names declaration, egress read, mint def, its two call sites).
- Guest python source cannot spell the names lexically (`-`/`/` are not identifier
  characters); guest Clojure source is refused by the reserved-prefix checks in any role —
  definition target and binder at yang/clojure.cljc:424-446, variable/binder/store-key/
  definition-key at the shared row admission (yin/vm/macro.cljc:586-644), with the
  exported-helper (yin/variable, yin/lambda, yin/make-def) and macro-assembly routes
  pinned at the VM level (provenance_test.cljc:245-322).
- No dict exposure: the builtins dict is populated only from `builtin-classes`/
  `builtin-functions`/`method-implementations` (prelude.cljc:3626-3640) — none mention the
  mint or the doors; `runtime-keys` (prelude.cljc:3705-3712) is likewise clean. The py
  prelude module is a linker module, never a `py/module-run` object, so it is not in
  `sys.modules` and has no `__dict__` to enumerate; guest module dicts hold only what
  guest bodies define. `host-names` requiring the two doors is a composition-time
  requirement (fail-closed direction), not an exposure.
- The exported `py/call-kw` reaches the mint only with conditions the runtime itself
  constructed from genuine foreign invocations — provenance-by-construction, not
  attacker-chosen.

**Q4 — Test weakening: PASS.**
- `test/yang/call/python_exception_test.clj`: +125/−0 across the entire span — purely
  additive. `exception_contract.clj` changed one js guest fixture line (`saved = e` →
  `globalThis.saved = e` — a repair of the retain fixture, strengthening it);
  `exception_contract_test.clj` additions only. No python fixture expected-value changed.
- Deletion-heavy files in the span (programs_p5b2.cljc, float_address_test.cljc,
  clojure_callsite_test.cljc, ts prelude) all map to the other arcs' reviewed commits, not
  the adoption.
- Scanner regeneration: both regenerations (7bccce41, 428bbdd7) are confined to the
  `:c4/:py` group — every hunk lies between the `:py` start (scanner_goldens.cljc:523) and
  the `:pysp` start (:7365); `:register`/`:b0` untouched. The delta is exactly the
  adoption's mechanical signature (free names +`cell/dsg__new-provenance`,
  +`cell/dsg__provenance`, +`dsg__condition-exception`, +`py.b/type` from the reviewed R2
  commit; the egress occurrence swap `[assoc true]`→`[cell/dsg__provenance true]`;
  application-count 4018→4039). Abort-gate mechanics verified in the generator
  (scanner-golden-c4.generate.clj): all eight `:c4` groups plus `:b0`/`:register` are
  recomputed live and compared against the committed pins BEFORE any write, a
  disagreement throws, and the rewrite must read back as the intended pins — the
  "single mover" claim is enforced, not just asserted.

**Q5 — Tail/lane interactions: PASS.**
The adoption commits touched only prelude.cljc and scanner_goldens.cljc; nothing in the
publish/linker path. `u/mark-tails` and `yin/vm/linker/publish.cljc` are untouched in the
span by these commits (publish.cljc's span changes are the clojure cc2 arc's). The egress
rewiring is shape-identical to the old code (binding call + tail `raise-exit` + tail
`if`); the mint is a new value-producing call in a definition body. The golden occurrence
booleans are `:in-body?` marks, not tail marks; the `:tail?` marks the constant-depth
analysis reads are regenerated mechanically by the unchanged marker, and the scan lane
passed at the gate.

## Findings

**F1 — P3 (robustness, reachable from admitted guest source): the reception path has no
fallback for a malformed foreign condition, and nil is fatal at the mint.**
src/cljc/yang/python/antlr/prelude.cljc:1063-1080 + src/cljc/yin/vm/engine.cljc:2812-2814.
The yang.call invoke validates the invocation record but not the condition a callee's
entry hands to the raise exit; the boundary kit's own injected-cell shape
(`(fn [invocation] ((get invocation :yang.call/raise) condition))`, guest-constructible)
shows a guest Clojure module can deliver an arbitrary value as `condition`. In
`py/call-kw`, a nil condition takes the not-thrown branch (`(get nil :yang.call/reason)` →
nil) and reaches `(dsg__condition-exception py.b/TypeError … nil)`, whose mint is the
engine's fatal "Provenance mint refuses nil provenance" — a whole-composition VM death —
while other non-map garbage (a string, `false`) mints opaquely and is later forwarded
verbatim at egress. Fail-closed-fatal on a protocol violation is consistent with the
engine's existing posture (`check-ref!` kills on forged refs the same way), so this is not
a blocker — but the split behavior (nil fatal, other garbage flows) is an artifact of the
mint's nil guard, not a decision, and no green test exercises a malformed condition
(precisely the class this review was asked to find). Suggested follow-up: shape-check the
condition at reception (non-map/nil → the plain fresh TypeError, no provenance) or record
the fatal-on-nil contract explicitly.

**F2 — P3 (test coverage): the python-side host-surface audit is not pinned.**
test/yang/call/exception_contract_test.clj:117-122 pins the js twin
(`module-exports`/`runtime-keys` contain no retention surface); python has no equivalent —
nothing regression-guards "no `py/`-namespaced definition reaches the mint/doors" and
"`runtime-keys`/builtin tables stay clean". The audit is real and passes today (verified
statically under Q3), but a future prelude edit that re-adds a `py/`-prefixed helper
wrapping `dsg__condition-exception` would flip nothing red at the python level. A
one-line host-side assertion in the python kit test (mirroring the ts checks against
`yang.python.antlr.prelude`'s `module-exports` and `runtime-keys`) closes it.

**F3 — P3 (docs): stale deferred-set rationale above the generator's `deferred`.**
test/resources/yin/vm/semantic/scanner-golden-c4.generate.clj:47-53. The dated comment
still concludes "The deferred set is therefore the six moved :c4 groups; :py/:pysp and
:b0/:register did not move" directly above `(def deferred #{:py})`, which it now
contradicts. The file's own header warns "a group left in the set has its movement
absorbed silently", so the comment is the operator's only description of the current set.
Rewrite the note for the adoption regeneration (or move the 2026-11-01 note under it as
history).

## Verdict

The adoption is a clean, minimal rewiring onto the landed primitive: the forgeable stamp
is fully retired as an authority source, every forwarding decision authenticates through
the sealed-heap reader, the R2 lifetime rules hold clause-by-clause, the export surface
exposes no mint path, no assertion was weakened, and the scanner regeneration is gated and
single-mover as claimed. The three findings are follow-up grade (robustness consistency,
a missing regression pin, a stale comment); none is a defect in what shipped, and none
weakens the authenticity guarantee this round was chartered to establish.

**APPROVE WITH FINDINGS**
