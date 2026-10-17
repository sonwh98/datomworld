Created-GMT: 2026-10-15 13:50:00 GMT
Created-Local: 2026-10-15 20:50:00 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending
# Task: 8f-2 review of the uncommitted rename diff (sign-off or changes) + ruling on frozen old-name keywords
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-15 20:50 +07 | Status: active | Rationale: sign-off gate before commit; different family from codex

cwd = worktree datomworld-srvm-p8f2 (branch srvm-phase8f2 @ 5804a1dc, uncommitted). Read ./collab/*engineer-8f2.report.md (esp. the danger-classification table). Verify independently: (1) the moves are byte-identical renames and nothing else changed except namespace/path substitutions (compare git diff -M against HEAD); (2) zero live references to yin.vm.semantic-register / semantic_register remain in src, test and every build/config/script file; (3) no hashed/pinned identity moved (descriptor hashes, golden H pairs, A addresses); (4) the ten C4 files are the only test/yang edits, three substitutions each. RULING NEEDED: codex preserved 44 lines of 'frozen model identity and test backend/lane keywords' (e.g. a runtime model keyword like :semantic-register emitted in telemetry datoms; lane/backend labels). The plan is to delete the old kernel and make the register kernel THE semantic kernel: should those keywords be renamed now (and if so to what, and which tests/fixtures move), left, or renamed in a later slice? Decide, with the reason, and if rename is right give the exact edit list for codex. Also flag any other leftover name that will confuse after the rename (e.g. docstrings calling this 'the register kernel' vs 'semantic'). Report ./collab/<ms>-architect-8f2-review.report.md with Completed-GMT/Completed-Local/Coding-Agent/Session-ID, P1/P2/P3, the ruling, ending SIGN-OFF or CHANGES-REQUIRED. Read-only except the report.
