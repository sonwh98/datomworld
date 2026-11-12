Completed-GMT: 2026-11-10 23:00 GMT
Role: Architect (mob — three independent rulings on ONE design question)
# MOB: how should clojure retain a condition without exposing it to guest observation?

## Situation (all findings are reproducible on c9c5533c, both walker and semantic lanes)

Clojure lowers exceptions to outcome tuples. To retain a protocol condition across a call
boundary it now uses a generated wrapper: a task-owned cell containing
`[visible condition witness]`, where `witness` is a compiler-minted closure binding
`{visible, condition, call-token}` and authentication re-derives the witness from the stored
`[visible condition]` and compares by `=` (kernel Closure equality = owner + payload + captured
env). `dsg__visible_of` unwraps for some guest operations so the payload is observable.

Two independent reviewers found, in sequence:
- Round 1 (astra): token transplant onto forged payload → fixed by the closure witness.
- Round 2 (argon): caught binding was the wrapper cell → `(= e original)` false → **regressed a
  Round-0 green cross-language row**. Fixed by dsg__visible_of routing (=, get, keywords,
  predicates at some call sites).
- Round 3 (astra, on the fix): **still broken, two ways**:
  (a) **Content replay / mutation**: `(throw (cell/new (cell/get e)))` forwards the original
      condition from a guest-made cell; `(cell/set! e (cell/get other))` makes the SAME wrapper
      forward a DIFFERENT condition. The witness authenticates contents but not the cell's
      identity as the retained one.
  (b) **Observation leaks**: `(= [e] [orig])` false; `(get {orig :found} e :missing)` misses;
      `(count e)` = 3; `(assoc e :a 2)` loses the update; caught `nil` is truthy; `(vector? e)`
      is true but `((fn [p] (p e)) vector?)` is false — the unwind is per-call-site, so nested
      positions and higher-order predicates still see the wrapper.

## The constraint set

1. §R2(1) of ruling v2: a payload valid in the receiving runtime keeps its ORDINARY catching
   and identity behavior — delivered UNCHANGED. §8.10.7: crossing back restores original type
   and identity. This is the bar the round-trip row measures.
2. R5: shape is never evidence; forged or source-written attributes never authenticate.
3. Slice-5 property: constant-depth tail chains (no mandatory %capture on the error path);
   closures stay cell-free for UCF machine-data validation — though R3 already introduced a
   cell for the wrapper, so the purity bar is about not growing more.
4. v2 does not require the internal encoding; owner decision "(A) now, (B) if R4 proof fails".

## THE QUESTION

Given that a *wrapped* value cannot be made indistinguishable from the payload under every
guest observation (capacity, assoc, truthiness, nested equality, higher-order predicates), is
there a clojure-side retention design that meets §R2(1) — or does this finding constitute the
R4 equivalence FAILURE that authorizes the (B) fallback?

Answer ONE of:
**(A) Patch design**: name a design that closes both (a) and (b) *without* per-call-site
unwinding — e.g. identity-bound retention (authenticate by cell ID + immutable contents, so
replay and mutation fail) plus a normalization discipline that cannot be defeated by nested or
higher-order observation (consider: unwrap at CATCH-BIND time rather than at use sites, and
carry the wrapper only for the rethrow path; or a guest-visible payload that is the real value
with provenance held in a side table keyed by... what, given values have no identity?). Be
concrete about how `throw e` then still forwards the condition.
**(B) This is the R4 failure**: the outcome channel cannot deliver payload identity to ordinary
guest code, so the owner-authorized fallback (clojure onto the raise-exit continuation lane,
where the caught value simply WAS the payload) applies. Say which parts of R3 survive and what
the fallback round must do.
**(C) Narrow the contract**: §R2(1)'s identity requirement is only required for values the
receiving runtime recognizes as its own exceptions; ordinary caught payloads could be
delivered with documented accessor semantics instead. Say exactly what the ledger row
[:clojure :python :round-trip] should then assert and whether that is a legitimate reading of
ownership.

Evidence: `collab/exception-track/1794365000000-reviewer-r3-identity-confirm.gpt-6-astra.stdout.log`
(read it fully), `1794363200000-...gemini-4-argon-high.stdout.log`, and the code at
`src/cljc/yang/clojure.cljc` (wrapper :906-975, unwrap router, catch binder, call-entry) plus
`src/cljc/yin/vm/engine.cljc` (issue-ref/authentic-ref?/cell ops) and `values.cljc`/`data.cljc`
(closure equality). Do not edit. Begin with Completed-GMT/Completed-Local; end with **(A)**,
**(B)** or **(C)** and a one-line reason.
