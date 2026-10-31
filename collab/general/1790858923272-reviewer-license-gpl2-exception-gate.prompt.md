Created-GMT: 2026-10-01 12:48:43 GMT
Created-Local: 2026-10-01 19:48:43 +0700
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: Review — relicense to GPL-2.0-only with a drafted linking exception (pre-owner-approval)

Role: Adversarial Code Reviewer and Security Auditor (licence-text review)

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-01 19:48:43 +0700 | Status: active | Rationale: non-Claude review of Claude-drafted licence text before it is shown to the owner

Read-only review in /Users/sto/workspace/datomworld-license (branch chore-license-gpl2; uncommitted). Do not edit. Change:
git diff (LICENSE GPL-3.0 -> GPL-2.0 text; package.json "license") plus the new file LICENSE-EXCEPTION.

OWNER (verbatim): "i want datom.world to have same license as Linux kernel because it is infrastructure at that level";
then "option 1" = GPL-2.0-only plus a linking exception for EPL-1.0 and Apache-2.0 dependencies, keeping all current
dependencies. The owner is the sole commit author. The owner will see the exact exception text before anything is committed.
Brief: /Users/sto/workspace/datomworld/collab/1790857898202-subagent-license-gpl2-exception.prompt.md
Writer report (untrusted): /Users/sto/workspace/datomworld/collab/1790857898202-subagent-license-gpl2-exception.claude-opus-5-5.report.md
Orchestrator-verified: package.json parses under node ("SEE LICENSE IN LICENSE"); LICENSE is 338 lines headed "Version 2,
June 1991" with SHA-256 edaef632cbb643e4e7a221717a6c441a4c1a7c918e6e4d56debc3d8739b233f6 matching the writer's single
gnu.org fetch; an independent re-fetch from gnu.org timed out (pending).

Review (this is not a request for legal advice; flag risks for the owner and counsel):
1. Does LICENSE-EXCEPTION achieve option 1's intent: datom.world's own code stays GPL-2.0-only (no "or later"), while
   combination/distribution with EPL-1.0, EPL-2.0 and Apache-2.0 independent modules is permitted? Ambiguities, loopholes
   (could someone use it to relicense datom.world code under EPL/Apache, or to combine with proprietary code?), missing
   pieces vs well-known exceptions (Classpath, GCC Runtime, the FSF FAQ template), and wording that conflicts with
   GPL-2.0 section 6.
2. Is "SEE LICENSE IN LICENSE" the right package.json value, given LICENSE holds only the GPL text and the exception sits
   in LICENSE-EXCEPTION? Would a pointer inside LICENSE (or naming both files) be better without breaking the verbatim
   GPL text requirement?
3. Anything in the writer's caveats that is wrong or missing (prior GPL-3.0/ISC distributions, inbound contribution
   policy, Datomic peer licence, dependency audit).
Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Findings as P0-P3 | file:line | evidence | concrete fix, or "No actionable findings". End with Verdict: READY / REQUEST
CHANGES and Sign-off: GRANTED / WITHHELD.

## Round 2 (consensus follow-up) — 2026-10-01 20:07 +0700
Resume of thread 01a0f782-a3c1-7173-9857-646af3b75f30. Read-only; do not edit.
OWNER (verbatim): "I'm not using project.clj"; options chosen, verbatim: "Delete it (Recommended)" (project.clj) and
"Yes, update it (Recommended)" (package-lock.json root license only).
Writer round 2 (report: /Users/sto/workspace/datomworld/collab/1790857898202-subagent-license-gpl2-exception.claude-opus-5-5.report-r2.md):
LICENSE-EXCEPTION rewritten (whole-work waiver for the permitted combination; datom.world and modified versions stay
GPL-2.0-only with section 3 options preserved; independent modules' source obligations solely under their own licences);
package.json and package-lock.json root -> "SEE LICENSE IN LICENSE-EXCEPTION"; project.clj deleted; Leiningen mentions
removed from three yin/vm docs. LICENSE unchanged (sha256 edaef632...; gnu.org re-fetch still pending).
Re-read the changed files (git diff in /Users/sto/workspace/datomworld-license plus LICENSE-EXCEPTION). Confirm your P1/P2
findings are resolved and flag any new ambiguity in the rewritten exception. Findings as P0-P3 | file:line | evidence |
fix, or "No actionable findings"; end with Verdict: READY / REQUEST CHANGES and Sign-off: GRANTED / WITHHELD.
Begin exactly with Completed-GMT / Completed-Local.
