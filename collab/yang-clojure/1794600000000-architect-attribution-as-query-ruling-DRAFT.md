Created-GMT: 2026-11-05 03:40:00 GMT (v1)
Revised-GMT: 2026-11-05 18:20:00 GMT (v2 — astra's four findings)
Revised-GMT: 2026-11-05 20:10:00 GMT (v3 — qwen3.8-max's five amendments)
Revised-GMT: 2026-11-06 02:45:00 GMT (v4 — kimi k3.1-pro's four amendments; verdict: endorse for owner sign-off with these)
# RULING DRAFT v4 — attribution-as-query: CALLER-SIDE site tagging; record, then attribute by query (DRAFT — pending owner sign-off)

Review state: v1 → astra (4 findings); v2 → qwen3.8-max (5 amendments); v3 → kimi k3.1-pro (4 amendments, below). Architecture settled across all three; v4's changes are mechanical corrections to the mechanism and the battery, per kimi: "(1) caller-side AST tagging; (2) case 5 redefined + a seventh case; (3) the trace-shape-induced revision of ALL pins and the accounting rewrite; (4) R1's trigger and format conditions." Central principle (all three accept): derive credit from recorded relationships, require actual body entry in both lanes, refuse ambiguity. Unitarity preserves what flows through the pipeline; it cannot recover provenance never recorded — this ruling records the missing provenance minimally, then attributes by query.

## R1 — The durable correspondence record is DESCOPED, on stated conditions

As v3: the address-linked adaptation manifest is deferred — the adapter is inlined per fixture, addresses need canonicalization nothing yet consumes, and no second consumer exists. TWO CONDITIONS from kimi's review: (a) **the deferred trigger is an OWNER/ARCHITECT call** — "a second consumer exists" is never self-declared by an implementer; (b) **the declared site tables are recorded in a stable, versioned evidence format** so the later R1 landing is an addition to the record, not a migration of it. The long-term stream-citizen direction stands as design intent. The offset table `kernel-context-extra-arguments` is retired — BUT its retirement has mechanical scope (see R6): it serves the context-API arities across the whole family, and its consumers (kernel-executed-arity? and ~30 pinned assertions) are rewritten with it.

## R2 — The mechanism: CALLER-SIDE site tagging in the compiled AST; the callee record carries the caller's site id

**Site ids are assigned at APPLICATION EXPRESSIONS in the compiled AST, post-macroexpansion** — not at callee body entry, and not on raw source forms (the corpus runs under `is`/`testing`/LaTTe macros; source-level tagging would silently miss every macro-expanded call site). The callee-entry trace record is extended to carry the **caller's site id**: the record becomes `[fname arity caller-site-id]`. Rationale (kimi's finding): the ambiguity is one guest callee clause serving two upstream arities — callee-side extension yields identical records for both origins and cannot work. Consequence for the scoping inventory: the **walker/semantic ingress row is updated** — the walker gains site-id stamping at application nodes; it is not "nothing further." Indirect applications (`(:implicit-fn implicit-def)` and kin) have static expression positions, so they receive static site ids like any other call; the callee being runtime-unknown does not affect the id. Recursion is safe (existence-based consumers). The `/3` self-delegation gets its own unmapped site id → no credit — the originating-call rule (R3) is expressible only under this caller-side form, which is why the ruling mandates it.

## R3 — Disjoint attribution patterns, verified by caller-site linkage

A body entry attributes to upstream `/1` only via a site whose compiled argument expression is the ambient literal (`empty-env` supplied by the adapter's declared path); explicit-context sites attribute to `/2`. Disjointness is by recorded site identity and compiled argument shape, never by value equality.

## R2b — The refusal battery: SEVEN cases

1. An explicit `/2` call supplying an empty environment must NOT credit `/1`.
2. An unexecuted ambient `/1` beside an executed explicit `/2`: no borrowing.
3. Mismatched program/adaptation evidence: attribution refuses. **The referent is defined**: the mismatch is the canonical-source-hash of the inlined adapter, computed by the compiler during instrumentation and recorded per fixture — a trace from a fixture whose recorded hash differs refuses.
4. Missing-lane evidence: attribution refuses.
5. **REDEFINED** (kimi: the v3 case was unimplementable — the tagged trace records no arguments): the fixture's declared call shape is verified against the **compiled AST argument expression at that site** — a site declared ambient whose AST argument is not the ambient literal refuses. The check is compile-time, not value-sniffing.
6. Indirect-origin: body entries originating through indirect application refuse unless site-linked; default refusal (static ids make indirect sites linkable — they are not exempt).
7. **NEW (kimi)**: variable/derived argument shapes — a site whose context argument is a variable (bound to `empty-env` on one path, a populated env on another) is statically unclassifiable: **default refusal or `/2`-only attribution**; the declaration is checked against the AST argument expression (an `empty-env` literal may claim ambient; anything else may not).
8. **NEW (kimi, diagnostic)**: a declared site whose id never appears in any trace (stale table after a fixture edit) is a RECORDED DIAGNOSTIC — a mistyped table must not masquerade as honest refusal.

The `/3` self-delegation: the delegating call gets its own unmapped site id → no credit; the originating call is the credited event.

## R4 — The attribution contract is versioned where the rule lives

Unchanged from v3: the differential gate record (slice9_gate.md) versions the attribution section; `yin.cic.spec` v1 is untouched; "mapped" is defined by the declared site tables + caller-site-tagged records; name+arity matching is the degenerate 1:1 optimization.

## R5 — Closure is MEASURED; the revision scope is HONEST about its blast radius

The attribution may expose unsupported credit among previously credited arities; valid witnesses preserved, withdrawn witnesses reported, H3-6 blocked until independent reconstruction succeeds. **The revision scope (kimi's correction of v3's under-statement): the trace shape change (2-vector → 3-vector) touches EVERY pin and path set built as 2-vector literals** — the refusal pins (`contains?` assertions on `[registered-definition? 2]`), every `kernel-executed-arity?` path set across the 20-arity family's ~30 assertions, and the gate-status computation (which must learn the third field or the credit set misparses). All of it is authorized by this ruling and listed in the evidence. "No valid pin regresses" means: pins encoding current semantics do not *silently* regress; pins requiring mechanical shape updates are updated and listed; pins encoding superseded semantics are revised on the record.

## R6 — The retirement of the offset table is SCOPED to its real consumers

`kernel-context-extra-arguments` serves the context-API arities across the family (not just F2) and is consumed by `kernel-executed-arity?`. Retiring it means **rewriting the accounting predicate** — the declared site tables must cover every context-API arity it served, or the retirement is partial and two mechanisms coexist (the failure qwen's amendment 4 warned against). The implementation round states the coverage list explicitly before deleting the table. The refusal pins' shape updates are covered by R5's authorization. (v3's "the refusal pins are untouched" is corrected: they are *mechanically updated* to the 3-vector shape, on the record.)

## R7 — H3-6's audit runs the query — and re-derives the site tables

Unchanged from v3, plus kimi's trust-model hardening: **the declared site tables are DERIVED MECHANICALLY from the compiled AST during instrumentation** (the compiler sees each site's argument expression), and H3-6's independent audit **re-derives them** from the same record — a wrong declaration is a compile/audit failure, not an honor-system risk.

## Scoping inventory (astra + qwen + kimi reviewed)

| Layer | Present now | Missing for the query |
|---|---|---|
| Source/adaptation | pinned upstream source, `context.edn`, host assembly + rewrites | declared site tables (mechanically derived; versioned format); retired offset table |
| Compilation | `kernel-compile` AST per fixture (adapter INLINED; canonical source hash computed) | CALLER-SIDE site-id stamping at application nodes (post-expansion); per-fixture hash recording |
| Walker/semantic ingress | AST → datoms appended to a program medium | **UPDATED**: application-node site-id stamping in the walker; callee records extended with the caller's site id |
| Execution evidence | ordered body-entry pairs, both C lanes, outcomes | the third trace field (caller site id); the declared site tables |
| Trace/budget | `stream/make` 1000000; 2M-bound precedent | restated capacity + instruction bound for the added field |
| Gate accounting | manifest-derived denominator, name/arity matching; 2-vector trace literals throughout | `dao.space.query/q` attribution + the site tables + the 3-vector migration |
| Manifest schema | schema-1, CLOSED key set | nothing (linkage evidence is out-of-band; schema-2 only if R1's durable record later lands) |

## Acceptance

- The SEVEN-case battery + the stale-table diagnostic: all refusals refuse; the disjoint patterns attribute correctly on both lanes.
- The attribution reconstructs the credit set; withdrawn witnesses reported; 165/165 only if every attribution is supported.
- The pin revisions (mechanical shape updates + the semantic /1 re-credit) listed in the evidence; the `:gate-status` assertion PASSES for the first time; H3-6's independent audit re-derives the site tables and completes closure with final architect sign-off.
- No valid pin silently regresses; prior rulings stand; the trace capacity restated before any tagged-trace run; R1's deferred trigger reserved to the owner/architect.

## Implementation ordering

First deliverable: the written inventory confirmation (the instrumentation touch points, the capacity measurement, the offset-table coverage list). Then: caller-side site-id stamping in the walker/compile → the mechanically derived site tables → the seven-case battery + diagnostic → the accounting rewrite + pin migration → the attribution + re-credit → H3-6.
