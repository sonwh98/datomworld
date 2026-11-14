Created-GMT: 2026-11-12 22:20:00 GMT
Created-Local: 2026-11-13 05:20:00 +07 (+0700)
Owner decision requested: ONE ruling. Nothing merges until you answer.

# OWNER DECISION — retire or reinstate the clojure `finally`-refusal row?

## The conflict, stated precisely

- **Ruling v2 §3** (owner-approved constitution): "Clojure's restricted catch surface (single
  catch, no `finally`) is a source-semantics decision this ruling does not change." The kit
  ENFORCES it: `exception_source_test.clj:37-41` + `exception_contract.clj:162,178` +
  `exception_legs.clj:60,106-109` assert `(try 1 (finally 2))` THROWS
  `clojure.lang.ExceptionInfo`; the ledger carries `:clojure-finally-refusal`
  (`:execution :preserve-source-refusal`) and the `:finally-replacement` clojure exclusion.
- **FINAL DIRECTIVE** (owner, same arc): "the port moves clojure toward real Clojure semantics...
  `finally` becomes expressible — the one-catch restriction lifts." The port implemented that.
- **Result**: `clojure-restricted-catch-surface-preserved-test` will FAIL on shiva — a governance
  failure, not a code failure. qwen3.8-max's port review flagged it P1-blocking.

## The decision

**RECOMMENDATION: RETIRE the refusal.** The FINAL DIRECTIVE is the later, more specific owner
authority, and retiring the refusal is its direct consequence ("toward real Clojure" is not
compatible with permanently refusing `finally`). Retiring means:

1. A kit round updates the three kit locations above: `(try 1 (finally 2))` must now COMPILE and
   RUN (finally executes on normal exit AND unwind); the `:clojure-finally-refusal` obligation row
   flips to `:execution :supports` and the `:finally-replacement` clojure exclusion is removed
   (or re-purposed to test real finally semantics).
2. The ledger records the retirement against this packet.
3. Ruling v2 §3 gains a supersession note (append-only): "the source-semantics restriction was
   lifted by the FINAL DIRECTIVE; v2's 'does not change' clause is superseded."

**Alternative (reinvest the refusal):** keep the kit as-is and revert the port's finally-admission
to a refusal — which re-introduces exactly the dialect divergence the directive retired. Not
recommended; recorded for completeness.

## Consequences either way

- Retire: port + kit round + ledger note → shiva can run with an honest claim.
- Reinstate: a second code round to the port (reject finally again) and the FINAL DIRECTIVE's
  "finally becomes expressible" clause stands contradicted — you'd want to say so explicitly.

## Evidence

- qwen's review: `1794554000000-reviewer-port-implementation.qwen3.8-max.findings.md` (P1-1)
- The kit's current assertions: `test/yang/call/exception_source_test.clj:37-41`
- The port's finally lowering: `src/cljc/yang/clojure.cljc` (try-finally) + `clojure/prelude.cljc`
- Ruling v2: `1794960000000-owner-ruling-exception-uniformity-v2.md` §3
- FINAL DIRECTIVE: `1794372000000-owner-decision-r3-vm-retained-seam.md`

---

## DECIDED: **RETIRE** (owner, 2026-11-13 09:20 +07, verbatim: "retire")

The owner ruled RETIRE after the full lineage exchange, and articulated the governing principle
for the record: **a dialect's surface derives from its source language; the proof corpus verifies
admissions, it does not author them.** The corpus-driven minimalism (no prover `finally` → no
dialect `finally`) was sound as a scope cut for kernel hardening (211dded9) and wrong as inherited
policy once the frontend's job became general Clojure — the prover's idioms (single
ExceptionInfo catch, no finally) became permanent law through unexamined inheritance.

Consequences, in order:
1. KIT ROUND (dispatched): remove `:clojure-finally-refusal` and the pair-matrix exclusion
   (`exception_contract.clj:162,178`); convert `exception_source_test.clj:37-41` to assert
   finally WORKS. New clojure-caller finally-replacement pair legs run for the first time —
   report as FINDINGS if red, never as failures of the ruling.
2. CORPUS ROUND (queued): LaTTe oracle/gate gains try/finally fixtures — the admission's proof
   obligation, per the original discipline now correctly inverted: demand first, proof with it.
3. DIALECT-DEFINITION ROUND (queued, owner's insight generalized): the portable-clojure subset
   spec derived from the LANGUAGE (catch classes, finally, rethrow, interop boundary), corpus
   extended to prove it — replaces corpus-residue as the surface source, permanently.
4. v2 §3 gains the supersession note in the kit-round commit; the PORT merge notice follows the
   kit round landing + shiva (port itself: qwen CONFIRMED, all five remote checks green).

---

## ✅ CONFIRMED: RETIRE (owner, 2026-11-13, verbatim: "retire")

The owner confirmed RETIRE after reviewing the lineage, the corpus-driven principle, and the
per-language refusal rule. Already implemented: kit rounds 1+2 (`ba3cc407` on master) — the
`:clojure-finally-refusal` obligation retired, the pair-matrix exclusion removed, the
`finally`-replacement legs EXECUTING for the first time (python/php/typescript → clojure × 3
backends, 9/9 pass in composed tree), the value-1 pin correct (qwen's F3 catch preserved).
The convergence port (`exception-r3-impl` @ `854b74c3`, qwen CONFIRMED) is also on master.
v2 §3 supersession note landed with the kit merge commit.

The per-language principle of record: **a frontend's kit asserts whatever its source language
has — present means implemented-and-pinned; absent means refused-at-the-surface.** Pre-licenses
future refusal rows for languages genuinely lacking the construct.
