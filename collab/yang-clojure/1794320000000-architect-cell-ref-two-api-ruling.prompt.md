Created-GMT: 2026-11-04 17:10:00 GMT
Created-Local: 2026-11-05 00:10:00 Asia/Ho_Chi_Minh
Coding-Agent: codex (gpt-6-astra, architect-review)
Session-ID: not-applicable (orchestrator-dispatched, fresh session)
# Second opinion requested: the cell-ref reference-vs-data collision and the owner's two-API solution

Role: architect second opinion (gpt-6-astra). The owner asks you to critique BOTH the problem framing and the proposed solution — attack freely; a rubber stamp is worthless here. You may read the repo (worktree /Users/sto/workspace/datomworld-cljrt at f35c65c2, plus master /Users/sto/workspace/datomworld), but no suites need running; this is architectural review.

## The architecture (axioms: docs/design/datom.world.md)

One heap, one value language. Frontends (yang.clojure, yang.typescript, yang.php, yang.python) are COMPILERS, not runtimes: they lower source to UAST; at runtime there is only the CESK machine stepping closures in one heap. Axiom 2: "Interpretation Creates Semantics: Data is syntax; semantics only emerge through interpretation (one truth, many perspectives)." Invariant: "Do not collapse interpretation and execution into the same layer."

Cross-language references are cell-ref maps issued by the VM (engine.cljc ~:316): `{:type :cell-ref :id <cell-N> :seal <64-char string>}`. On call, `cell/get` (engine.cljc ~:2725, via check-ref!) validates heap membership + seal, then runs the designated closure. The conformance matrix (test/yang/call/conformance.clj) projects donor exports into caller namespaces as these maps.

## The defect and its history

In Clojure, maps are callable: `({:a 1} :a)` → 1. So the compiler, lowering a call `(f args)`, must decide whether f is a reference (route to cell/get) or data (map lookup). Three implementation rounds attacked this by VALUE INSPECTION at call time:

- r2 (muse): route if the map HAS the keys → data maps with those keys died at cell/get ("Dead or forged cell reference").
- r3 (muse): route on exact key-set shape → full-shape data maps still died; the fallback only fired for shapes the check rejected.
- r4 (glm): route on the minted ID LANGUAGE (3 entries, :type :cell-ref, :id keyword name starts with "cell-", 64-char string seal) → the orchestrator's pinned exposure probe (a program-built `{:type :cell-ref :id :customer :seal <64 zeros>}` called as data) flipped THREW → ANSWERED, but the final gate REJECTED: r4 over-accepts — `{:type :cell-ref :id :cell-7 :seal "a…64"}` (ordinary data imitating the minted form) still routes to admission and aborts. Security was never at stake (the VM's heap+seal check makes capability bypass impossible); the defect is pure source semantics: innocent data aborts instead of answering.

The scissor (why shape checks are doomed): a genuine reference and a perfect imitation are the SAME MAP — field-identical. Any predicate on the value gives both the same answer, but the cases require opposite behaviors (deref vs lookup). Tightening the shape test converges toward exactly the minted form — i.e. toward the set {genuine refs} ∪ {perfect imitations}, which is one set. Three rounds, three precisions, same impossibility.

Options previously presented to the owner: (1) reserve the shape — data imitating the minted language refuses, documented limitation; (2) add a non-fatal authenticity probe to yin.vm (cell-authentic?) so the compiler asks the heap before routing — full fidelity, but a VM boundary change, and arguably collapses interpretation and execution into one layer (the invariant).

## The owner's solution (what you are reviewing)

Wrap the map with TWO APIs — one for heap, one for data:

1. HEAP API: cell/get-style resolution — validate (id, seal) against the heap, run the closure, refuse forgeries. Means "treat as reference."
2. DATA API: ordinary map application — key lookup, always answers. Means "treat as data."

The decisive rule: WHICH API IS CALLED IS DECIDED BY THE PROGRAM'S SYNTAX — the position at lowering time — never by examining the value at runtime. A projected name (`probe(...)`, where the conformance composition declares its export set) lowers to the heap API; a local map called at a key (`({…} :marker)`) lowers to the data API. The `dsg__cell-ref?` value-test (clojure.cljc ~:2268) disappears from the call path. The cell-ref map itself is just data in the shared medium; the two APIs are two interpreters of it, per axiom 2 — semantics created by interpretation at a position, not discovered in the value.

The orchestrator's consequences analysis (critique this too):
- The scissor dissolves: the perfect imitation built by a program is called at a DATA position → data API → answers. To abort, it would have to be called at a REFERENCE position — i.e., be a projected name — which program data never is by itself.
- Security untouched: the heap API still seal-validates; forgeries at reference positions refuse (the gate's own security analysis). Validation was always the heap's job; the error was letting it double as MEANING detection.
- A semantic choice becomes explicit: a genuine reference STORED INTO ORDINARY DATA (`(def bag {:f probe})`, later `(:f bag)` called) is data — lookup answers, never derefs. Reference-ness lives in the position, not the paper. The bearer property survives where the position does (a projected name's value passed along and called at a projected position still resolves); the bag case does not. Documented in §8.10 so nobody "fixes" it back.
- Implementation shape: yang.clojure lowering emits heap-API resolution at projected-name call positions (the projection set flows from the composition's exports/profile into lowering opts), data-API invocation everywhere else; parallel shape-tests in other frontends' preludes audited (ts prelude's `yang.call/eligible?` adjacency); the D2 token-packet/descriptor admission machinery stays untouched (separate landed mechanism; this ruling scopes to the cell-ref position only).

## Your questions (answer each explicitly)

1. PROBLEM FRAMING: is the scissor argument correct? Is there any value-inspection route we missed that could separate genuine from imitation (e.g. nonces, minting-side marks, provenance-carrying values that survive serialization through the medium)? If yes, say how and what it costs.
2. THE SOLUTION: does the two-API/position-decided design hold? Attack it: find programs where position-deciding gives the wrong meaning, where provenance through aliasing breaks it, where the projection set cannot be known at lowering time (dynamic composition? runtime-projected names? re-entrant streams?).
3. AXIOM FIT: does it satisfy axiom 2 and the no-collapse invariant BETTER than options 1 and 2? Is there a reading of the axioms that prefers 1 or 2 instead?
4. BAG SEMANTICS: is "reference stored into data becomes data" acceptable as an explicit semantic, or does it break something the matrix/donor-execution model relies on (check test/yang/call/conformance.clj's composition and the identity-check legs)?
5. MIGRATION: any part of the LANDED D2 machinery (token-packet, dsg__call_token, descriptor admission) that this ruling retroactively undermines or must be reconciled with? Any landmine in the other frontends' call lowering?
6. VERDICT: endorse as the ruling-of-record (with amendments), or propose the alternative you'd rule instead.

Report file/line references for every claim. This is read-only review; commit nothing.
