Created-GMT: 2026-09-25 06:45:00 GMT
Created-Local: 2026-09-25 13:45:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (ucf v2 revision history)

# Task: Publish the UCF v2 contract revision history + :reasons options

Role: Compiler & AST / Docs (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master, clean except
docs/orchestrator-log.md which you must NOT touch).

Context: the yin.vm.linker epic is in flight (M2 gating; M4 = require
lowering approaches). The M1-architect sign-off left two Phase 2 gates
(collab/1790268690622-reviewer-ucf-phase1.glm-flash.findings.md and the
M1 gate round): (a) P2 — the `v2` stamp at src/cljc/yin/vm/ucf.cljc:38
names a contract whose complete revision history is not yet published,
which UCF section 7.11 requires before cross-host lowering; (b) the
static `:reasons` in ucf.cljc name frame reasons the reference machine
does not mint (only :next and :put are produced of the six-value
:yin.k/reason enum) — the lift driver needs this resolved as either
"align runtime reasons" or "name them as safepoint kinds".

Work items:

1. Draft `docs/design/yin.vm.ucf-revisions.md`: the complete revision
   history of the canonical instruction vector contract (the `v2`
   stamp's referent). Sources, all in-tree: the UCF sections of
   docs/design/yin.vm.semantic.md (7.1-7.4, 7.5, 7.11), the UCF phase 1
   implementation brief and report
   (collab/1790243232166-vm-engineer-ucf-phase1.*), the M1/M2 linker
   gate findings that touch the contract, and the git history of
   src/cljc/yin/vm/ucf.cljc and semantic.cljc (git log -p as needed).
   The document must state, per revision: what changed, which sections
   of the contract it amended, the commit or artifact that carried it,
   and whether already-published images remain valid under it
   (contract-version semantics per UCF 7.11). ASCII, <= 80 columns.
2. Document the two :reasons options with their consequences (a short
   section in the same file): Option A align the runtime-minted reasons
   to the design enum; Option B rename ucf.cljc's :reasons to
   safepoint kinds and keep them distinct from :yin.k/reason. State
   what each choice costs the M4 lift driver. Do NOT choose — the
   architect adjudicates at the M4 gate.
3. Do not modify src/ or test/; do not modify ucf.cljc; do not touch
   docs/orchestrator-log.md, collab/, or the worktrees.

Constraints: pure ASCII, <= 80 columns on every line you add; no
commit/stage; the new file is the only file you create; if the sources
disagree on the revision sequence, record the disagreement verbatim
with both citations rather than inventing an order.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
