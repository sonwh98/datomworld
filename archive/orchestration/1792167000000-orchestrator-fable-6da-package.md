Created-GMT: 2026-10-16 14:35:00 GMT
Created-Local: 2026-10-16 21:35:00 +07
Coding-Agent: zcode (interactive seat)
Session-ID: sess_607c1129-1dcd-49ba-9b34-58565f4f42e4
# Fable's Tuesday diff-confirm package — slice 6d-a (any-pc, reference lane)

For your diff-confirm and sign-off on Tue 2026-10-20. Everything below is verified state as of this note; re-derive from git before acting, per the usual posture.

## What to confirm (the merge gate)

Branch `origin/srvm-6d-a` @ `d5987015` (5 commits on `b9103797`, which is origin/master's tip): `855854c5` carrier (feat), `006929b` tests+fixtures, `2a4fe508` docs, the docs micro-fixes and cljd gates appended after your v2.1 was confirmed. Full diff also staged at `collab/1792166000000-6da-full.diff` (pre-fix snapshot; git is canonical).

1. **Your plan v2.1 → implementation, as amended by the mob**: the 6d-a Change list and Acceptance items 1-14 (see the two engineer reports below for the per-item evidence). Note the plan-amendment vocabulary that leaked into continuation §5.7 was removed in the docs micro-round, and the singular "leading boundary" wording became "leading boundaries" per your ruling 2's general rule.
2. **The ledger golden** (ruling 3): inventory at `target/6da/r2-ledger-diff-inventory.edn` in the worktree `/Users/sto/workspace/datomworld-srvm-6d-a` (classification `:address-only`; the six frame-byte equalities all at `:segment/blake3-…` paths; two independent verifications — glm's diff-pairs inspection and opus's git-line check — agree).
3. **The retained-FFI fixture correction**: the wait entry's actual `:call-id` (`:parked-0`) is carried instead of nil — an assertion-target fix, kept flagged for you per both authors.
4. **The two flagged items you already have rulings on**: row 20's measured 3-boundary leading exclusion (your ruling's example under-counted; both reviewers confirmed the general rule governs) and row 17's require-answered obligation as a like case (confirmed by both, with cmd's caveat recorded: the harness re-lists discharge's answer set and could drift if `discharge!` gains obligation sources — the durable fix, deriving the answer set from `discharge!` itself, is deferred as a 6d-b/6e note).
5. **Intentional deviations to acknowledge**: (a) the lift runs `lift-task*` twice on success (preflight with fake identities → structural zero-serves; ~2× lift cost, documented §7.1, cmd's cost note for 6d-b holder lanes); (b) `restore-task` re-receives the receiver's registry modules via `engine/receive-module` (restore-semantics addition beyond v2.1's letter; tested by byte-equal relift on a two-module tree); (c) the harness composition widens `:primitives` with the stream module's functions; (d) `run-beside` resets `:env` to `:free-env` on the semantic lane.
6. **One open design question answered by glm in your absence** (ratify or overrule): repeated-register agreement ruled non-exercisable by projected programs (the linearizer assigns fresh registers; `zero?` pin stands); 6e item 6 carries your hand-built `[:call r f [x x]]` line from `collab/1792161400000-architect-6da-nit1-glm.glm-5-3.findings.md`.

## Evidence trail

- Engineers: codex gpt-6.1-sol (round 1 + half of round 2, sessions in the reports) and claude-opus-5-5 (round 2 completion + fix rounds, session `dc3e4b88-02d8-428d-b938-541541f0fbe1`); reports `collab/1792146800000-engineer-6d-a.report.md`, `collab/1792154441000-engineer-6d-a-r2-opus.report.md`, `collab/1792159200000-engineer-6da-fixes.report.md` (worktree collab/ for round 1), `collab/1792163000000-engineer-6da-cljdfix.report.md`, `collab/1792161400000-engineer-6da-docs-followup.report.md`.
- Reviews: glm-5.3 APPROVE-WITH-NITS (`collab/1792157104000-reviewer-6da-diff-glm.glm-5-3.findings.md`) and cmd/kimi-k3 APPROVE-WITH-NITS (`collab/1792157104000-reviewer-6da-diff-cmd.kimi-k3.findings.md`) — zero P0-P2 between them; their P3s are all applied or documented above.
- Gates at the time of writing: JVM quick 4,079/269,020/0 (three runs, twice by the orchestrator on the committed tree); Node quick 288/25,318/0 (13 namespaces); Dart quick +267 passed; JVM slow (ten touched namespaces, shiva) green with the every-boundary harness at 131,990 assertions / 0 failures in 1915s; Node slow (jupiter, yin.vm subsystem, 68 namespaces, 6817s): the ONLY failure is the C4 seat's scanners-agree-on-the-c4-modules (known red, ledger) — every yin.vm namespace incl. the boundary harness passed, so the cross-host boundary-count determinism glm asked for holds (Node's measured counts match the JVM-pinned tables). Dart slow (shiva, repo-wide, 74:03 test time): +817 -3, the 3 being exactly the C4 scan-test known reds (goldens-match, scanners-agree, verify-outcomes-agree). dht-import-test and the C4 goldens remain the C4 seat's ledger rows.
- Known reds NOT this slice's: the C4 seat's `dht-import-test` (stale fixture) and the `[:c4 :py]/[:c4 :t]` scanner goldens (ledger rows cover them; they appear as the expected 3 reds in the Dart slow run).

## After your sign-off

The merge to master (owner's standing rule: Architect sign-off of the exact head + green lanes, no separate permission needed), then 6d-b and 6e — both briefs are drafted (`collab/1792165000000-engineer-6d-b.prompt.DRAFT.md`, `collab/1792165000000-engineer-6e.prompt.DRAFT.md`) and their dispatch timing is the owner's call.
