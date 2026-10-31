Created-GMT: 2026-10-15 17:30:00 GMT
Created-Local: 2026-10-16 00:30:00 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending
# Task: 8g documentation review (sign-off or changes)
Role: Architect
Implementers:
- Model: glm-5.3 (partial, usage limit) then gpt-6.1-sol | Rationale: doc review gate; reviewer family differs from both

cwd = worktree datomworld-srvm-p8g (branch srvm-phase8g @ 9fab76c6, uncommitted docs diff, 30 files, +1516/-8964). Read ./collab/*writer-8g-codex.report.md and the original brief ./collab/1793200000000-writer-8g.prompt.md. Verify AS ARCHITECT, against the real code (grep src, not the doc's own claims): (1) every statement about what the code is now is true (A is the only code identity, H/R derived checksums; native kernels stay as backends over the documented fragment, not widened; BLAKE3 vs SHA-256 statements; ucf is code-address only; completion/old kernel/handoff gone; the 7.11 wording); (2) the Q6(c) outcome: frozen design now yin.vm.semantic.md with the v2/v3 history preserved and companion docs renamed; no dangling relative link or path in any touched doc (spot-check 15); (3) the old-citer repointing judgments (9 files) are right; the section-10 Met notes (phases 4-7, items 6/7/8) are accurate against git history; (4) known-gaps entries accurate and complete (frame.cljc:141 NaN gap, binder refusal, malformed-vectors fixture, completion/UCF deletion ledger); (5) the 8964 deleted lines: confirm they are the deleted old v3 design body and renamed-doc noise, nothing of the new design lost (diff the new frozen design against HEAD's yin.vm.semantic-register-vm.md after normalising names: only intended changes); (6) the nine src/test docstring edits are comment-only (git diff -w), acceptance greps in the brief pass: run them. Report ./collab/<ms>-architect-8g-review.report.md with Completed-GMT/Completed-Local/Coding-Agent/Session-ID, P1/P2/P3, ending SIGN-OFF or CHANGES-REQUIRED. Read-only except the report.
