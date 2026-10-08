Created-GMT: 2026-09-26 10:12:20 GMT
Created-Local: 2026-09-26 17:12:20 +0700
Coding-Agent: glm
Session-ID: 07ad56e7-d862-4f2d-b838-d36933efd577

# Task: yin.vm.linker M4 slice S5, the UCF design amendments (documentation only)

Role: Technical Writer for a design specification (documentation only)

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-26 17:12:20 +0700 | Status: active | Rationale: owner directive "use glm for S5 and A3"

Repository: the worktree /Users/sto/workspace/datomworld-m4-s5 (branch m4-s5, from master 9428c3d2). Collab files:
/Users/sto/workspace/datomworld/collab/ (absolute paths; the worktree has no collab/). Do NOT touch any other worktree.
Parallel workers are doing S3 (Opus), S4 (GLM) and A3 (GLM) in other worktrees. You edit EXACTLY ONE file:
docs/design/yin.vm.universal-continuation-format.md. No code, no test, no other doc.

## Owner statement (verbatim quote)

"use glm for S5 and A3"

## What to do
The linker design (docs/design/yin.vm.linker.md) records decisions that amend the UCF design. Port them, as
amendments to the UCF sections named below, without changing any decision. Sources of truth: the linker design's
M4 paragraph in section 9 (the sentence "the UCF table amendments"), its section 10 file box entry for the UCF doc
(near line 2060: "sections 7.4.1 and 7.4.3; 7.5.1 marker keys :yin.k/binding and :yin.k/store-of, and the sealed
references; 7.5.1, 7.5.3, and 7.6.2 resource decode targets and store model"), and section 7.3 of the linker design
where each amendment is decided (it says "amended below", "an amendment to UCF section 7.5.1", "added to the list
in section ..." near lines 1140, 1259, 1384, 1453, 1458, 1481 and 1514). Amend these UCF sections:
- 7.4.1 and 7.4.3: the :link-request, :link-response and :install safepoints, and the pending-wait variants;
- 7.5.1: the :yin.k/binding and :yin.k/store-of marker keys, and re-sealed references (sealed reference markers);
- 7.5.3 and 7.6.2: the private resources table, the decode targets, and the store model (isolated module stores).
Preserve everything else in the UCF document exactly, including its "Proposed / Deferred" status, section 7.11's
acceptance matrix and the Rule R "v3" contract text. Each amendment must be traceable: state the rule once in the UCF
section, and do not invent behavior. If the linker design is ambiguous or two of its statements conflict, STOP and
report BLOCKED with the exact quotes.

## Format rules (docs/agents/format.md and the house style)
Markdown design document. Any table is an ASCII box table (+---+ borders, a +===+ header rule), never a markdown
pipe table, at most 170 columns. Prose wraps at about 80 columns like the neighbouring text. Plain ASCII. Do NOT cite
paths under collab/ (untracked working files); cite only committed docs and source. Do not mention the review process
or which model wrote what.

## Verification
No test lane applies. Check yourself: no lines over 80 columns that are not table rows, pure ASCII, no em dashes, no
conflict markers, and that git diff shows only the one file. Report the sections you changed with their line ranges.

Rules: pure ASCII and <= 80 columns on every line you add or edit (Markdown grid rows excepted), no em dashes. Do NOT commit, stage, checkout, reset, stash or merge. If the spec conflicts with the tree or is ambiguous, STOP and report BLOCKED with the exact conflict; do not improvise a design. GLM's weekly budget is small and resets 2026-09-27 01:26 +0700: be efficient, do not re-read what you can cite, keep the report tight. The orchestrator independently verifies your work and sends it to a non-GLM reviewer.

## Report
Write your final report to
/Users/sto/workspace/datomworld/collab/1790417540103-architect-linker-m4-s5-ucf-amendments.glm-5.3.report.md
(same header fields) and return it as your final response, with the sections amended (and line ranges), any place
the linker design was ambiguous and how you resolved it or why you stopped, and unrun checks.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED - <reason>
