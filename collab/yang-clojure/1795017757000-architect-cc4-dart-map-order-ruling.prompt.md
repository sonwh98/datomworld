Created-GMT: 2026-11-18 16:02:37 GMT
Created-Local: 2026-11-18 23:02:37 +0700
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: Architect ruling — the canonical ordering policy for constructed-map operands (cc4's reproduced Dart divergence)

Role: Architect (compiler provenance)

## Context (read first)

- The defect report: `/Users/sto/workspace/datomworld-cc4/collab/yang-clojure/1795017484194-engineer-cc4-r2-findings.md` (cc4 r2, STOPPED per its binding rule on a reproduced cross-host divergence).
- The isolated bottommost difference: the `assoc`-constructed arity-error map's final two key/value pairs order differently per host. Suspect emitter: `compile-constructed-collection` at `src/cljc/yang/clojure.cljc:302-323` (`(interleave (keys form) (vals form))`, no stable ordering rule); suspect producer: `arity-error-form` at `yang/clojure.cljc:1087`.
- Evidence: `test/resources/yang/clojure/cc4-replay-dart-divergences.edn` + `collab/yang-clojure/1795017275897-cc4-r2-evidence/` in the cc4 worktree.
- Governing design: `collab/yang-clojure/1794670000000-architect-callsite-generalization-design.gpt-6-astra.findings.md` (§5.1–5.3: cross-host byte-identical C1 equality) and the C1 contract as landed (cc1–cc3, master `2c80a421`/`fb493d52`).

## Questions to rule on (deliver RULING entries, normative text where it binds)

1. **The ordering policy**: what canonical ordering must constructed-map application operands follow so C1 roots/keys are host-independent? Options to weigh (at least): (a) a content-canonical order computed at the emitter (e.g. sort operand pairs by their canonical content address), (b) preserve source-written order by attaching order metadata at read/expansion time, (c) sort by key representation (fragile across hosts?), (d) another policy you judge better. Rule ONE with reasons; state precisely where in the pipeline the normalization happens (which function(s), which pass) and what invariant it must preserve (runtime evaluation semantics unchanged — the VM consumes the operands; note how ordering interacts with evaluation order if at all).
2. **Scope of the rule**: does the policy apply to ALL constructed collections with nondeterministic host ordering (maps; sets?) or only map literals/constructed maps? Name the emitter functions covered.
3. **Fix-round contract**: the acceptance criteria for the fix round — the exact property tests (cross-host equality for the arity-error map and for constructed maps generally), which files are in bounds (yang/clojure.cljc is currently forbidden for cc4; the fix round is a NEW round — specify its bounds), what must be re-run (cc4's battery as the regression gate), and whether already-recorded cc4 digests are re-pinned after the fix (they must be: the fix changes canonical bytes — confirm and say so normatively).
4. **Compatibility**: does the ordering change affect already-published artifacts / stored addresses (a one-time canonical shift)? If yes, state the migration/legacy-load expectation (legacy artifacts still load; addresses are not stable across compiler versions unless pinned — confirm what the contract already guarantees).

## Bounds

READ-ONLY ruling: edit nothing. Deliverable: `collab/yang-clojure/1795017757000-architect-cc4-dart-map-order-ruling.gpt-6-astra.findings.md` in the MAIN checkout (`/Users/sto/workspace/datomworld`) — write it there, not the cc4 worktree.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
