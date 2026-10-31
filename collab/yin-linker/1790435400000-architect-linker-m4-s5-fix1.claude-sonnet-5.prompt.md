Created-GMT: 2026-09-26 13:00:00 GMT
Created-Local: 2026-09-26 20:00:00 +0700
Coding-Agent: claude
Session-ID: 0d5f5a51-7c1e-4b2a-9d3e-5a5a5a5a5a05

# Task: fix Sonnet gate findings on M4 slice S5 (UCF amendments)

Role: Lead System Architect (docs)

Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-26 20:00:00 +0700 | Status: active | Rationale: GLM ~5% budget; claude authorized

Worktree /Users/sto/workspace/datomworld-m4-s5 (branch m4-s5, uncommitted; do NOT commit). Edit ONLY docs/design/yin.vm.universal-continuation-format.md. Gate: collab/1790430690003-architect-linker-m4-s5-gate.claude-sonnet-5.stdout.log (APPROVE-WITH-FIXES). Fix:
1. Must: UCF 7.5.4 says "The kind set is closed" (~lines 961-964); extend it with :forged-resource-reference, :missing-module-store and :unrooted-body (the last is filed under :yin.k/non-portable in linker.md ~1333), with a "(linker M4)" cross-reference.
2. Mark each new M4 block "specified by yin.vm.linker.md; the private resources table, sealed references and install child land with the M4 slices S3 to S4 (S3 ships r8-r11, the install child and link-module; S4 ships manifests)". Do not claim anything else is implemented; the UCF lift/lower of parked link and install entries stays future.
3. State the status :binding-mismatch returns under (a lower-side refusal per linker.md ~588, NOT a :yin.k/non-portable kind, so it must not be added to 7.5.4).
4. State once the mapping between UCF's :yin.k/cell and the linker's :cursor (linker.md ~994-1026).
Smallest diff, ASCII only, 80 cols, keep the doc's table/box formatting. Report to collab/1790435400000-architect-linker-m4-s5-fix1.claude-sonnet-5.report.md.
