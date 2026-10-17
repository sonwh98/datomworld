Created-GMT: 2026-10-10 18:00:00 GMT
Created-Local: 2026-10-11 01:00:00 Asia/Bangkok

# Task: revise the route-(a) phase-5 design after codex review (continuing your session)

Same worktree/branch (HEAD cdb128b2). Edit ONLY `docs/design/yin.vm.semantic-register-vm.derivations.md` and your report. No git add/commit. Codex findings (read all): `collab/1791670000000-architect-srvm-phase5-route-a-review.gpt-6.1-sol.findings.md`. Codex independently confirmed the R goldens and the route; fix the following, verifying each against the code before writing it.

New owner ruling (2026-10-11): R and H are different VMs' bytecode and need NOT equal each other or the old bytes. "H equals today's lower-stack" is NOT an expectation, observation or gate: delete that language everywhere (including from F13, goldens-as-evidence, and the 5a test). H goldens are derived from the new emitter's own rules and checked against the new emitter; a difference from old `lower-stack` is never a finding.

1. H golden targets (lines ~665, ~685): conditional operand uses `[:branch-false 6]` and `[:jump 7]`; tail-call closure uses `[:closure 1 6]`. Re-derive both H images fully, and re-check every other H golden pc by hand twice; state the pc of each label.
2. Liveness agreement law (~320, §6.2, acceptance tests, F8): compare against virtual live-out over ACTUAL successors minus the destination, not `L(p+1)-{rd}`; state that a tail call has live `[]`. Add the `(f (tail-call g x) y)` shape as a pinned row.
3. Lift declarations (~975, F11, F13, F14): both descriptors declare `:dim/lift-to`. Keep version numbers (no bump) but remove or replace the lift declaration when the old lowerers go, and list the mechanical regeneration of descriptor hashes and every affected H/R checksum. Move descriptor-hash coverage out of any test namespace scheduled for deletion. Enumerate the final manifest index shape and every affected entry (not just the R index entry).
4. C4/import gate (~564-567): specify the exact root packet vars, expected outcomes and count from the import tests (not `o t m r e n`); separate module ASTs from import programs; served module bodies still use old images, so either add meaningful execution coverage of newly lowered module bodies by test-local composition (no C4 file edits) or narrow the claimed coverage and say what is not covered.
5. Execution rows (~542): nineteen throw comparisons plus one successful FFI comparison with an explicit zero-argument `:op/ping` bridge (the parity fixture has `:op/echo`); update the slice acceptance wording.
6. Retrievability (~1101-1103): `:trusted` must preserve retrievability, not mandate a fetch: drop the mandatory `jing/get` check; distinguish absence from transient retrieval failure. Define the segment/A-to-tree association: carrier and index shape, completeness checks, and the rule when several source trees project to the same A; canonical vector bytes stay independent of the association. Host modules: a guest-code carve-out wording; keep the existing absent tree field (no explicit nil). Do not imply A uniquely identifies all original AST facts.
7. Allocator pseudocode: keep the frozen scan's explicit "for each starting interval in increasing virtual id" loop, increment `next` only when allocating a never-used slot; do not depend on the single-start property.
8. Evidence anchors (~430 and elsewhere): qualify paths (`semantic_register/linearize_test.cljc` for the cited tests at 83 and 107; tail reading at `linearize.cljc:120-121`; definition handling at 103-105). Projection-invariance tests compare vectors/addresses explicitly, not a whole provenance-bearing result map.
9. F12: paste the complete scanner replacement text instead of referring to a previous revision. Qualify F8-F14 per codex's rulings (F9 ACCEPT; the rest ACCEPT WITH CHANGES).

Report to `collab/1791680000000-architect-srvm-phase5-route-a-revise.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Per item: what changed, what you verified, anything unverified.
