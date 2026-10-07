# Review task: DaoStream v2 implementation plan

Seat: Routine Review. Sandbox: read-only. Repo: datom.world (Clojure/cljc,
targets clj + cljs + cljd).

## What to review

`docs/design/dao.stream.implementation-plan.md` — a phased migration plan
for implementing the DaoStream contract greenfield in a `dao.stream`
namespace, scoped to one vertical slice (the WebSocket transport).

## Authorities (the plan is subordinate to both)

- `docs/design/dao.stream.md` — the contract, review-approved. Where the plan
  and the contract disagree, the contract wins.
- `docs/design/dao.stream.ws.md` — the WebSocket transport specification,
  itself subordinate to the contract.

Legacy implementation for context only (evidence about behavior, not a
constraint): `src/cljc/dao/stream.cljc` and `src/cljc/dao/stream/*.cljc`.

## What I want

This is a *plan* review, not a code review. Judge it on whether executing it
as written would produce a correct, contract-conforming slice, and whether it
would fail late rather than early.

Specifically:

1. **Contract conformance.** Does any phase assume, require, or produce
   something the contract forbids or does not provide? Does any phase silently
   need a mechanism the contract's public surface has no way to express?
2. **Phase ordering and dependency.** Is any phase's deliverable
   unconstructible at that point? Does any phase build scaffolding a later
   phase discards? Is risk actually front-loaded, as the plan claims?
3. **Underspecification that will be resolved badly under pressure.**
   Decisions the plan defers or assumes away that a Phase-4 implementer would
   have to invent on the spot.
4. **Scope.** Anything in the plan that is not needed for the slice, or
   anything the slice genuinely needs that is missing.
5. **Cross-host risk** (clj / cljs / cljd) the plan does not account for.

Rank findings by cost-if-left-unfixed. Distinguish "this is wrong" from "this
is a judgment call I would make differently." Say explicitly if the plan is
sound as written — do not manufacture findings.

## Second pass: adjudicate the interactive session's findings

The Claude session that raised these is an interested party; treat them as
claims to verify against the authorities, not as given. For each: confirm,
refute, or refine, with the specific contract clause that settles it.

1. **Phase 1's deliverable cannot exist in Phase 1.** It specifies a
   conformance suite "parameterized by transport" but Phase 1 has no
   transport. Further, the suite as described is reader-shaped (cursor
   provenance, multi-reader reads) while the ws handle is writer+closable
   only, so the suite must be surface-aware from the start.
2. **"The seven operations as protocols/functions" conflates two kinds.**
   Five are handle protocol methods; `create!` and `attach!` are per-transport
   entry functions with no handle in hand — which is why the contract's host
   dispatch table maps a type to a `{create attach}` pair. Blurring this
   invites re-growing the retired registry.
3. **Phase 3 may build scaffolding it discards.** The contract says a ring
   buffer's descriptor resolves to `not-found` on another host absent a
   directory some composition keeps. Phase 3's round trip needs "a host
   composition that serves the stream" — which is Phase 4's ws serving side.
   So Phase 3 either builds a throwaway directory or duplicates Phase 4.
4. **Deposit admission has no enforcement mechanism.** Phase 4 enforces
   admission "at assembly, against the destination's declared nature," but the
   contract's public surface offers no way to ask a handle its retention
   policy, and bans stale predicates. Proposed resolution: the host passes the
   declaration as data alongside the handle when composing the boundary,
   keeping introspection out of the contract. Is that the right answer?
5. **Logical-stream identity is needed in Phase 2, not Phase 3.** Phase 2's
   `cursor-mismatch` and Phase 3's stable descriptor identity are the same
   identity; Phase 3 may discover Phase 2 chose unsuitably.
6. **Phase 5's "two processes" is disproportionate harness.** Two independent
   host compositions in one process over a real socket already proves the
   serialization boundary is crossed; real OS processes across both clj and
   cljs is a large test-infra bill for marginal evidence.

## Output

Prose findings, most costly first. No patch, no edits.
