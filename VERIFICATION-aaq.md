# VERIFICATION-aaq — the younger session's independent baseline (verification lane)

Session 6d17eb18 (the younger duplicate; see DUPLICATE-DISPATCH-aaq.md). This
file is the verification baseline: what I independently confirmed about the
landed inventory (30b2f610), the R5 blast-radius census I will diff against
the elder's migration, and the R2b battery checklist I will verify the
seven-case battery against. No tracked file is touched by this session.

## A. Inventory verification (commit 30b2f610) — ALL CONFIRMED

1. Offset table: `kernel-context-extra-arguments` (latte_oracle_test.clj:1786-1791)
   has exactly the six entries the inventory lists. CONFIRMED.
2. Capacity: `stream/make 1000000` at :1827; default-stream-capacity 1024 is a
   DIFFERENT mechanism (unbounded-make default) — the oracle's explicit 1e6
   stands. slice9_evidence.edn:26-29 carries the 2,024,912 walker / 1,105,792
   semantic measurements and the uniform 4,000,000 acceptance budget. CONFIRMED.
3. `kernel-trace-form` (:1794-1809): body-entry put! is the first form of every
   arity body; arity slot carries `[:variadic idx]` for variadic arities — the
   3-vector migration must preserve that encoding. CONFIRMED.
4. `kernel-executed-arity?` (:1812-1820): consumes the offset table; builds
   2-vector path sets — R6's rewrite target. CONFIRMED.
5. Adapter stub forwarding (:1565-1569): arity-for-arity `(p0…) → name p0…` into
   the context layer. Context layer `registered-definition?` (context.edn:56-59)
   has only /2 and /3 — the adapted /1 is a latent arity error today. CONFIRMED.
6. Upstream pin (gitlibs 86fca0d1…, defenv.cljc:102-105): /1 is exactly
   `(registered-definition? empty-env dname false)`; /2 delegates with
   `(registered-definition? def-env dname false)`; /3 is the real body. The
   inventory's mirrored-stub repair matches upstream's own shape. CONFIRMED.
7. Current gate truth: slice9_evidence.edn (generated 2026-11-04T16:47Z) has
   :gate-status :blocked with EXACTLY ONE unexercised arity —
   [latte-kernel.defenv/registered-definition? 1]; slice9_unexercised.edn
   carries that single pair. (slice9_evidence_refresh.txt and slice9_gate.md's
   "107/165, 58 uncredited" are STALE earlier states — do not cite them.)
   The by-design red pin is (= :pass (:gate-status evidence)) at :2618.

## B. R5/R6 blast-radius census (diff the elder's migration against this)

Mechanical 3-vector migrations in test/yang/latte_oracle_test.clj:
- :1805 `kernel-trace-form` record emitter
- :1812-1820 `kernel-executed-arity?` path sets (R6 rewrite site)
- :1932-1957 `kernel-exercise-entries` — consumes the predicate for
  :direct/:named-integration classification → :gate-status (the "credit set
  misparses" risk)
- :2099, :2113-2114 synthetic `[[gate-syntax-subst 2]…]` literals
  (test-kernel-acceptance-counting)
- :2282-2283 `[fname 2]` / `[fname 1]` pins (test-kernel-unused-arity-execution)
- :2335-2347 F3/F2 witness lists (18 predicate calls)
- :2367-2388 the 19-entry family (predicate calls)
- :2389-2406 F2 refusal pins: two predicate negatives + two
  `'[registered-definition? 2]` contains? pins (one per C lane) + R10 credit
- :2497 `:trace {:record-shape '[guest-function arity]}` declaration

Evidence/artifacts that regenerate or revise:
- slice9_evidence.edn (155 executed-calls matches) — full regeneration
- slice9_unexercised.edn — must regenerate to an EMPTY map on re-credit
  (assertion at :2564-2566 requires unexercised set = its keys)
- kernel_gate.edn :193-226 — the "honest non-crediting" comment + the two
  :guest-call overrides dropped (:h3-3-registered-definition-ambient,
  :h3-3-registered-definition-nonsymbol)
- slice9_gate.md — versioned attribution section (R4)

Semantic revisions (superseded pins, to be listed in the evidence per R5):
- :2355-2356 "F2 stays honestly non-crediting" registration pin
- :2389-2406 the F2/R2 /1 refusal pins flip to credit (the re-credit itself)
- :2618 the by-design red pin turns green for the first time (165/165)

## C. R2b battery checklist (verify each case is a REAL test)

1. explicit /2 with an empty environment value must NOT credit /1 — disjointness
   by site identity + compiled argument shape, never value equality (R3).
2. unexecuted ambient /1 beside executed explicit /2 — no borrowing
   (existence-based consumers).
3. mismatched canonical-source-hash (per-fixture, recorded at instrumentation)
   — attribution refuses; referent = the recorded hash, not a name.
4. missing-lane evidence — attribution refuses (both C lanes required).
5. DECLARED site shape verified against the COMPILED AST argument expression at
   compile time — a site declared ambient whose AST argument is not the ambient
   literal refuses (not value-sniffing).
6. indirect-origin default refusal unless site-linked; static ids make indirect
   sites linkable (they are not exempt); unstamped body entry (:gate-site/none)
   is the recorded refusal site.
7. variable/derived argument shapes — statically unclassifiable → default
   refusal or /2-only; an empty-env LITERAL may claim ambient, anything else
   may not (checked against the AST argument expression).
8. stale table (declared site id never appearing in any trace) — a RECORDED
   DIAGNOSTIC, never an honest-refusal masquerade.

Plus: the /3 self-delegation keeps its own unmapped site id → no credit; the
originating call is the credited event.

## D. Upstream self-delegation census (pinned 86fca0d1, defenv.cljc)

Exactly four adapter clauses are single self-delegations (the only ones the
mirror branch of kernel-adapter-clause fires for):
- fetch-definition /2 → (fetch-definition def-env dname false)
- register-definition /2 → (register-definition def-env rdef false)
- registered-definition? /1 → (registered-definition? empty-env dname false) ← the ONLY ambient one
- registered-definition? /2 → (registered-definition? def-env dname false)
All four reference original param names → all four need the positional rename.
definition?/theorem?/axiom?/notation?/implicit?/latte-definition?/
local-definitions/qualify-def: real bodies, arity-preserving forwards stay.

## E. Remote command list (draft — cross-check the elder's)

1. clojure -M:test -n yang.latte-oracle-test — full oracle namespace; first-time
   green gate assertion; KERNEL-GATE println shows :gate-status :pass and
   unexercised [] (the 165/165 admitted-arity closure).
2. clojure -M:test -n yang.clojure-test — compiler namespace.
3. The yang.base lane (subsystems.edn: yang.clojure yang.frontend yang.safepoint
   yang.stage yang.tails yang.io yang.core) via the seat's standard runner.
4. Capacity restatement confirmation: regenerated slice9_evidence.edn's
   instruction-budget-note with the binding lane's measured step count under
   the 4,000,000 bound.
