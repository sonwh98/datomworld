Created-GMT: 2026-10-11 02:00:00 GMT
Created-Local: 2026-10-11 09:00:00 Asia/Bangkok

# Task: rewrite the phase-5 derivations design for route (a), lower from A (continuing your session)

Worktree `/Users/sto/workspace/datomworld-srvm-p5-design`, branch `srvm-phase5-design` (HEAD ab952ace). Edit ONLY `docs/design/yin.vm.semantic-register-vm.derivations.md` and your report. Do not edit the frozen design; amendments stay as proposed text inside the derivations doc. No git add/commit.

## Owner rulings that change the design (2026-10-11, verbatim in substance)

1. R and H are lowered FROM A. "A, the semantic tuples, is essentially the linearization of the AST tree"; lowering from the tree would re-linearize. One linearization (A), everything else derives from it. R and H are functions of A alone: same A, same R, same H.
2. "We don't care about legacy": NO version bump, no b3/r3/h3, no re-version procedure, no lift-contract preservation question, no `:yin.lower/profile` string question. Change the lowerers in place. Pinned checksums, goldens and any descriptor hashing the lowerer's contract are REGENERATED once; say exactly which, and how a reviewer verifies the regeneration is mechanical, not a compat step.
3. Do not reuse the old lowerers via an adapter. Route (b) (resolve-on-vector, the adapter, byte-equivalence gates, the equivalence-as-blocking-gate decision) is dropped. Write new derivations: A -> R as linear-scan register allocation over A's liveness (the frozen §8.2 interval formulation is the starting point), A -> H as stackification over A's expression structure (frozen §8.3), both pure functions of A. Old lowerers `lower-register`/`lower-stack` and `debruijn-resolve` are deleted at cutover (phase 8); say what keeps them alive until then.
4. New invariant (written into docs/design/datom.world.md on branch worktree-invariant-unitarity): the AST is never lost; it is always retrievable wherever code is lowered. Consequence: no design may assume a receiver without a tree. The scanners and provenance may read the tree; derivations of R and H do not need it. Name which statements in linker.md 222-231, UCF `:yin.k/carried`, the `:trusted` link policy and host modules conflict with the invariant and propose the amendment text (do not edit those documents).
5. Verification replaces the byte oracle. The old compilers are an EXECUTION oracle only: run the corpus (B0, the register corpus, and the C4 linked-prelude corpus by name, read-only) through old and new pipelines and require identical observable results, with a stated common normalization for closure-valued results. Add validators and liveness checks on every output, hand-written goldens for the tricky cases (re-derive them yourself: `(+ 1 2)`, `(f (g x) y)`, `((f))`, an `if`, a tail call, a definition), and a determinism check (same A twice gives the same bytes; two ASTs with the same A give the same R and H, including ASTs that differ only in facts A omits, `:macro?` and shared `:eid`s: verify the claim that those facts cannot affect the lowering, with evidence, and report if they can).

## Keep from the previous design (still valid; reuse, do not re-litigate)

The slice structure idea (5a/5b/5c), the scanner design and its read-only C4 agreement gate (ordered and unordered oracles, join outcome comparison including refusals, discharged-name comparison with `yin/def` excluded: codex's round-2 fixes), public-API and no-private-var-access rules, the portability notes, the numbered mechanically checkable acceptance lists, and the independence from 4b (no file overlap). Re-derive any golden whose numbers change. Remove everything specific to route (b). The codex findings files in `collab/` (round 1-3) list what a reviewer will check; avoid reintroducing those defect classes (unproven universal claims, private vars, unspecified oracle order).

## Owner questions to leave (reduced)

Only genuine ones remain; list them explicitly at the end, or state "none".

Report to `collab/1791660000000-architect-srvm-phase5-route-a.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Then: what was removed, what was added, every hand-derived number and whether you verified it, the facts-A-omits finding, anything unverified.
