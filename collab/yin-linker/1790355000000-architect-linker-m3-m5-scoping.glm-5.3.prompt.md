Created-GMT: 2026-09-26 02:00:00 GMT
Created-Local: 2026-09-26 09:00:00 +0700
Coding-Agent: glm
Session-ID: 79bedc98-febc-4e0d-a768-e166fb876644

# Task: read-only scoping of yin.vm.linker milestones M3, M4 and M5

Role: Lead System Architect (scoping only; read-only; do not edit files)

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-26 09:00 +0700 | Status: active | Rationale: owner directive to use GLM's remaining credits on milestone work

## Owner statement (verbatim quote)

"GLM is at 86% and resets 2026-09-27 01:26 so don't let its credits go to waste . see if you can delegate some work to for any of the milestones"

## Orchestrator framing (my reading, not the owner's words; challenge it)

M2 (the linker's four format records) is in its final codex gate and will be
committed soon. M3, M4 and M5 come after it in a chain (M1->M2->M3->M4->M5,
docs/design/yin.vm.linker.md section 9). I want to run as much as safely
possible in parallel once M2 lands, using separate worktrees from a committed
base. I need FACTS to decompose the work, not design decisions. Where a real
design choice appears, name it as an owner or Architect decision; do not make
it. You are a scoper here. GLM authored M2 rounds 1-3, so do not review M2.

## Read first
- docs/design/yin.vm.linker.md: sections 6 (linking is a stream exchange), 7,
  8 (8.2 the assertion policy is M4's ENTRY criterion), 9 (milestones M3, M4,
  M5 and their test lists), 10 (file box), 11 (completion criteria), 12 (open
  decisions)
- the tree at /Users/sto/workspace/datomworld-ucf-phase2 (M1 and Rule R
  committed; M2 is UNCOMMITTED in the working tree and is being gated: read it
  as-is but treat it as not final)
- src/cljc/yin/vm/linker.cljc, module.cljc, engine.cljc, content.cljc,
  src/cljc/dao/jing/remote*.cljc, src/cljc/dao/stream/rpc.cljc

## What to produce (concise; cite file:line; mark each statement VERIFIED by
## reading or INFERRED)

A. M4 ENTRY CRITERION (section 8.2 assertion policy). Does any of it exist in
   code today (search for signed assertion, retraction, equivocation,
   :unauthenticated, :ambiguous-name, :asserted-by, principal, verify)? If yes,
   where, and which of the listed tests exist? If no: where the spec says it
   lives, its inputs and outputs, the exact test cases the M4 entry text lists,
   its dependencies (dao.jing signature verify? a composition-supplied verify
   function?), and whether it can be built INDEPENDENTLY of M3's stepped core and
   of linker.cljc. Propose a split into 2-4 independently gate-able slices with
   file ownership, and say which slices could run in parallel from a committed
   base without touching the same functions.
B. M4 REST. Decompose the remainder (manifest and derivation format records,
   register-host-module with profile enforcement, require-handler and the two
   wait states, the install child, link-module, the attach-image changes in the
   four engines, the UCF amendments) into slices with file ownership, the order
   they must land in, what each needs from M3's outputs, and which slices would
   collide in the same files (engine.cljc, the four engines, module.cljc,
   repl.cljc). Group the long M4 test list by slice.
C. M3 AND M5 DEPENDENCIES. Exactly what M3 needs from M2 (functions and shapes
   by name), whether ANY part of M3 could start before M2 is committed, and
   what M5 needs from M4.
D. Traps for implementers: cross-host (JVM, Node, Dart) hazards you see in
   these areas, and anything in the spec that looks contradictory or stale.
E. Which spec section 12 open decisions each slice actually needs settled before
   it can start, versus can defer.

Keep the report tight (a few pages at most); GLM's weekly budget is small, so do
not re-derive what you can cite. Do not edit files. Write your report to
/Users/sto/workspace/datomworld/collab/1790355000000-architect-linker-m3-m5-scoping.glm-5.3.report.md
if you can, and also return it as your final response.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
