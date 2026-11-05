Created-GMT: 2026-11-05 03:40:00 GMT (v1)
Revised-GMT: 2026-11-05 18:20:00 GMT (v2 — astra's four findings)
Revised-GMT: 2026-11-05 20:10:00 GMT (v3 — qwen3.8-max's five amendments)
# RULING DRAFT v3 — attribution-as-query: call-site-tagged evidence; record, then attribute by query (DRAFT — pending owner sign-off)

Review state: v1 reviewed by gpt-6-astra (REVISE BEFORE SIGN-OFF; four findings). v2 reviewed by qwen3.8-max (AMEND; five findings). Both incorporated. Central principle (both reviewers accept): derive credit from recorded relationships, require actual body entry in both lanes, and refuse ambiguity. Unitarity preserves what flows through the pipeline; it cannot recover provenance never recorded — so this ruling RECORDS the missing provenance in its minimal sufficient form, then attributes by query.

## The problem (recorded)

The executed-arity rule credits an upstream function-arity when a trace names it at the exact arity in both C lanes. The matcher keys on NAME+ARITY STRINGS, and the instrumentation emits only `[fname arity]`. For `latte-kernel.defenv/registered-definition?` this fails structurally: upstream has `/1` (ambient `empty-env`) and `/2` (explicit); the guest adaptation (context.edn) collapses both into one 2-arg callable; an explicit `/2` call supplying an empty environment is value-identical to an ambient `/1` call. 164/165 credited; the gate honestly `:blocked` on the one; H3-6 blocked behind this ruling.

## R1 — The durable correspondence record is DESCOPED until a second consumer needs it

v1 required an address-linked adaptation manifest. Qwen's review found no stated consumer beyond the gate audit and a real obstacle: the adapter is INLINED into each fixture's program (`kernel-compile` assembles context.edn + the declared rewrites per fixture), so UAST addresses differ per fixture — joinability needs canonicalization (canonical source hash + per-fixture derivation) that nothing yet consumes. Ruling: the durable correspondence record (upstream revision/source identity, function and arity, adapter canonical identity, transform identity/options, call-shape correspondence) is DESCOPED — it lands when a consumer beyond the gate audit needs address-linked correspondence (other frontends, future slices). Until then the correspondence lives in the evidence's declared site table (R2). The long-term direction (the adaptation as a full stream citizen under the linker's §8.1 machinery) is preserved as the design intent, not mandated now. The hardcoded offset table `kernel-context-extra-arguments` (latte_oracle_test.clj:1786) is RETIRED into the declared site table — one mapping mechanism, not two.

## R2 — The attribution mechanism is NAMED: call-site-tagged trace records + a declared site table

`kernel-trace-form` is extended to emit `[fname arity call-site-id]` — a compile-time-unique site tag (one instrumentation change). Each fixture declares a site→upstream-arity table, recorded in the evidence. Attribution: a body entry credits the upstream arity its call-site-id maps to — verifiable against both C lanes, re-derivable by the independent audit from the same raw traces. Adapter-address stability across the 106 fixtures is by canonical source hash + per-fixture derivation, recorded with the site table. The retired offset table's effects are absorbed into the declared tables.

## R3 — Disjoint attribution patterns, verified by site linkage

A body entry attributes to upstream `/1` only via a site tagged as the ambient-shape call (`empty-env` supplied by the adapter's declared path); explicit-context sites attribute to `/2`. Disjointness is by recorded site identity, never by value equality — equal arguments cannot distinguish origins, and the ruling never asks them to.

## R2b — The refusal battery: SIX cases

1. An explicit `/2` call supplying an empty environment must NOT credit `/1`.
2. An unexecuted ambient `/1` beside an executed explicit `/2`: no borrowing.
3. Mismatched program/adaptation evidence: attribution refuses.
4. Missing-lane evidence: attribution refuses.
5. (qwen) Declared-call-shape-vs-recorded-invocation mismatch: a fixture declaring the ambient shape while the recorded call carries a caller-supplied context refuses — the value-identical case, caught by site evidence.
6. (qwen) Indirect-origin: body entries originating through indirect application (higher-order calls through registries and extension closures — the trace includes indirect calls) refuse unless site-linked; default refusal.

The `/3` self-delegation distinction is preserved: the originating call, not the delegation, is the credited event.

## R4 — The attribution contract is versioned where the rule lives

The executed-arity accounting rule belongs to the LaTTe differential gate record (test/yang/latte_oracle/slice9_gate.md) and the H3 closure requirements — NOT `yin.cic.spec` v1, which is untouched. The gate record's attribution section is versioned: "mapped" is defined by the declared site table + call-site-tagged records per R2/R3; name+arity matching is the degenerate 1:1 optimization.

## R5 — Closure is MEASURED; the F2 pin revisions are AUTHORIZED

The attribution query may expose unsupported credit among previously credited arities; every valid witness is preserved, every withdrawn witness reported with its reason, H3-6 blocked until independent reconstruction succeeds. **The H3-3 pins that encode the superseded non-crediting semantics ("F2 stays honestly non-crediting", `not (kernel-executed-arity? f2 … 1)`, the named-integrations exclusion) are explicitly revised by this ruling as part of the re-credit** — "no valid pin regresses" means pins encoding CURRENT semantics do not silently regress; pins encoding superseded semantics are revised on the record, listed in the evidence. The documented absence of ambient namespace lookup is preserved; F2's missing-name result does not establish general ambient-resolution equivalence.

## R6 — No weakening rides along; the trace budget is restated

The D2 packet machinery, `cell/authentic?`, the evidence-walk bounds, and the refusal pins are untouched. The origin linkage increases per-call trace volume (the precedent: the 1M→2M bound existed because body-entry writes add guest instructions) — the implementation round MUST restate the stream capacity (`stream/make`) and instruction bound with measured headroom, or traces truncate silently.

## R7 — H3-6's audit runs the query over the completed record

The closure audit reconstructs the credit set by executing the attribution over the final tree's recorded site tables and tagged traces — after R2's mechanism exists and the evidence has regenerated.

## Scoping inventory (astra + qwen reviewed)

| Layer | Present now | Missing for the query |
|---|---|---|
| Source/adaptation | pinned upstream source, `context.edn`, host assembly + rewrites | declared site→upstream-arity tables (per fixture); retired offset table |
| Compilation | `kernel-compile` AST per fixture (adapter INLINED) | call-site-id tags at instrumentation; canonical adapter hashing |
| Walker/semantic ingress | AST → datoms appended to a program medium | nothing further for THIS ruling (site ids ride the traces) |
| Execution evidence | ordered body-entry pairs, both C lanes, outcomes | the third trace field; the declared site tables |
| Trace/budget | `stream/make` 1000000; 2M-bound precedent for trace writes | restated capacity + instruction bound for the added field |
| Gate accounting | manifest-derived denominator, name/arity matching | `dao.space.query/q` attribution + the site-table record |
| Manifest schema | schema-1, CLOSED key set | nothing (linkage evidence is out-of-band; schema-2 only if R1's durable record later lands) |

## Acceptance

- The SIX-case battery: all refusals refuse; the disjoint patterns attribute correctly on both lanes.
- The attribution reconstructs the credit set on the final tree; withdrawn witnesses reported; 165/165 only if every attribution is supported.
- The F2/H3-3 pin revisions listed in the evidence; the gate reads 165/165 credited, zero unexercised; the `:gate-status` assertion PASSES for the first time; H3-6's independent audit and final architect sign-off complete closure.
- No valid pin silently regresses; prior rulings stand; the trace capacity restated before any tagged-trace run.

## Implementation ordering

First deliverable: the written inventory confirmation (what the instrumentation change touches, the capacity measurement). Then: the call-site-id instrumentation → the declared site tables → the six-case battery → the attribution + re-credit → H3-6.
