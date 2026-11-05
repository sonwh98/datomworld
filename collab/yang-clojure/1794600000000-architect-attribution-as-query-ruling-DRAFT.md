Created-GMT: 2026-11-05 03:40:00 GMT (v1)
Revised-GMT: 2026-11-05 18:20:00 GMT (v2 — incorporating the gpt-6-astra review of v1, 1794600000000-...astra-review.stdout.log)
# RULING DRAFT v2 — attribution-as-query: derive credit from recorded relationships; record what was never recorded (DRAFT — pending owner sign-off)

Review state: v1 reviewed by gpt-6-astra (verdict: REVISE BEFORE SIGN-OFF — four findings, all incorporated here). Central principle ACCEPTED: "derive credit from recorded relationships, require actual body entry in both lanes, and refuse ambiguity." Astra's correction is the ruling's first rule: unitarity preserves what flows through the pipeline; it cannot recover provenance never recorded. The upstream→adapter correspondence and the call-origin linkage were never recorded — the ruling requires recording them, then attributing by query.

## The problem (recorded)

The executed-arity rule credits an upstream function-arity when a trace names it at the exact arity in both C lanes. The matcher keys on NAME+ARITY STRINGS. For `latte-kernel.defenv/registered-definition?` this fails structurally: the guest adaptation (context.edn) has arities 2 and 3; upstream has `/1` (ambient) and `/2` (explicit); the correspondence between the two worlds is prose, not data; and the instrumentation emits only `[fname arity]` — no callable address, no call-site or argument-origin identity. An explicit `/2` call supplying an empty environment is value-identical to an ambient `/1` call. 164/165 credited; the gate honestly `:blocked` on the one; H3-6 (165/165) blocked behind this ruling.

## R1 — Record the adaptation provenance as NEW, address-linked data (not the linker's lowering derivations)

The linker §8.1 derivation records connect the canonical tree to lowered images (semantic/de Bruijn formats) — they do NOT express upstream-function-to-adapter correspondence, and the oracle's walker/semantic ingress paths do not invoke the module-manifest linker at all. Ruling: the adaptation's provenance is recorded as ordinary, address-linked data — per adapted function: upstream revision/source identity, function and arity, adapter identity (its UAST address), transform identity/options (the `context-upstream-form` rewrites, declared with revisions), and call-shape correspondence. Retain the existing lowering chain unchanged alongside. Naming upstream namespaces is insufficient. The dependency inventory includes `latte_kernel.proof` (context assembly uses it).

## R2 — Call-origin preservation is an OBLIGATION with a verification battery, not an established fact

The trace must gain a verifiable connection between the executed body entry and its originating call, scoped to the run, lane, and exact program/adaptation identities. Where the call shape is demonstrably unambiguous, the connection may be derived; otherwise additional execution evidence is required. **Missing or ambiguous provenance yields NO credit.** Required regression battery: an explicit `/2` call supplying an empty environment (must NOT credit `/1`); an unexecuted ambient `/1` beside an executed explicit `/2` (no borrowing); mismatched program/adaptation evidence (attribution refuses); missing-lane evidence (attribution refuses). The `/3` self-delegation distinction is preserved (the originating call, not the delegation, is the credited event).

## R3 — The ambient correspondence is a declared, disjoint attribution pattern — verified, not assumed

A `[registered-definition? 2]`-shape body entry attributes to upstream `/1` ONLY under the R2 linkage tying that entry to the fixture's declared ambient call shape; explicit-context entries attribute to `/2`. Disjointness is by recorded provenance, not by value equality — equal arguments cannot distinguish origins, and the ruling never asks them to.

## R4 — The attribution contract is versioned where the rule lives: the differential gate record

The executed-arity accounting rule belongs to the LaTTe differential gate (test/yang/latte_oracle/slice9_gate.md) and the H3 closure requirements — NOT to `yin.cic.spec` v1 (the CIC checker's logical theory), which is untouched. The gate record's attribution section is versioned: "mapped" is defined by the recorded derivation + linkage data per R1/R2, with name+arity matching demoted to the degenerate 1:1 optimization.

## R5 — Closure remains a MEASURED result; credits can be withdrawn

The attribution query may expose unsupported attribution among previously credited arities. F2's `/1` credit and 165/165 are CONDITIONAL on regenerated, passing evidence. Every valid witness is preserved; any withdrawn witness is reported with its reason; H3-6 stays blocked until independent reconstruction succeeds. The documented absence of ambient namespace lookup is preserved — F2's missing-name result does not establish general ambient-resolution equivalence.

## R6 — No weakening rides along

The D2 packet machinery, `cell/authentic?`, the evidence-walk bounds, and the refusal pins are untouched. The query adds attribution power and required recording; it removes nothing.

## R7 — H3-6's audit runs the query over the completed record

The closure audit reconstructs 165/165 by executing the attribution query over the final tree's recorded datoms — after R1/R2's recording exists and the evidence has regenerated.

## Scoping inventory (astra-reviewed, per layer)

| Layer | Present now | Missing for the query |
|---|---|---|
| Source/adaptation | pinned upstream source, `context.edn`, host assembly + rewrites | recorded upstream→adapter correspondence; complete transform provenance |
| Compilation | `kernel-compile` produces an AST per fixture | adaptation manifest + retained attribution links |
| Walker ingress | AST → datoms appended to a program medium | attribution joins to upstream source + invocation origin |
| Semantic ingress | canonical rows → semantic vector | recorded adaptation/lowering chain in this path |
| Execution evidence | ordered body-entry pairs, both C lanes, outcomes | address/origin linkage distinguishing ambiguous calls |
| Gate accounting | manifest-derived denominator, name/arity matching | `dao.space.query/q` attribution + independently reproducible audit inputs |

## Acceptance

- The R2 battery: all four refusal cases refuse; the disjoint patterns attribute correctly on both lanes.
- The attribution query reconstructs the credit set on the final tree; any withdrawn witness reported; 165/165 only if every attribution is supported.
- The gate reads 165/165 credited, zero unexercised; the by-design `:gate-status` assertion PASSES for the first time; H3-6's independent audit and final architect sign-off complete closure.
- No existing valid pin regresses; prior rulings stand.

## Implementation ordering (first deliverable unchanged in spirit)

The implementation round's FIRST deliverable is the written datom inventory per the table above (what exists, what must be recorded), then R1's recording, then R2's linkage + battery, then the query, then the re-credit and H3-6.
