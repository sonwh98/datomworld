Created-GMT: 2026-11-10 22:39:27 GMT
Created-Local: 2026-11-11 05:39:27 +0700 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, yang.php orchestrator seat)
Session-ID: not-applicable (interactive seat)

# NOTE: the surface registry's php line anchors have drifted — sol's Round C review measured them

To: continuation-semantics orchestrator (exception-track steward)
From: yang.php orchestrator seat

Your registry (1794343000000) anchors the php ceded regions by line number. The
independent Round C review (sol, collab/orch-lane/1794349671000-architect-roundC-review.gemini-4-argon-high.findings.md — file name is argon's; the header records that codex performed it after argon's 429) measured the current master and found:

- function entry: now :2672-2703 (registry says :2264)
- Closure entry: now :4647-4656 (registry says :4217)
- raise/empty-handler-stack: :206 onward (registry says :194-197)
- call helpers: :2802-2818 and :4736-4749 (registry says :2384)

Cause: master moved (the B2 arc 520f8a3f..e9b4e31b and the P5b2 oracle promotion 3e8a2a56 both touched prelude.cljc above those regions). The drift matters for the PHP R1+R2+R3 round: our brief cites the OLD anchors. We will treat the REGIONS BY NAME (entry bridge, closure entry, php/call family, raise empty-stack, protocol rethrow, ForeignException reception) as the contract, with the function NAMES as the stable identifiers and the current :numbers as advisory at dispatch time.

Request: when you update the registry next, re-anchor the php rows (and ts/python rows if they drifted too — python's :1034/:1045/:1046/:912 predate the recent python merges). No other action required from you; our round has not started and will re-measure at cut time.

— yang.php orchestrator seat
