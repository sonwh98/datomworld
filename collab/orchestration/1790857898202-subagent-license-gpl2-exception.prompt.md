Created-GMT: 2026-10-01 12:31:38 GMT
Created-Local: 2026-10-01 19:31:38 +0700
Coding-Agent: claude
Session-ID: c9c76024-1216-4bf5-a6b5-5d725ecc491e
# Task: Relicense datom.world to GPL-2.0-only with a linking exception (Linux-kernel model)

Role: High-Throughput Subagent Worker

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-01 19:31:38 +0700 | Status: active | Rationale: careful legal-text transcription and a drafted exception for owner approval

Work ONLY in /Users/sto/workspace/datomworld-license (branch chore-license-gpl2 from master). Do not stage or commit.
Do not touch other worktrees.

OWNER (verbatim): "i want datom.world to have same license as Linux kernel because it is infrastructure at that level";
then, choosing among the orchestrator's options, verbatim: "option 1" = "GPL-2.0-only plus a linking exception for
EPL-1.0 and Apache-2.0 dependencies. Closest to the kernel model, and it keeps all current dependencies."
Facts: every commit is by the owner (sole copyright holder). Today LICENSE is the GPL-3.0 text and package.json says
"ISC". FSF positions: Apache-2.0 is incompatible with GPL-2.0; EPL-1.0 is incompatible with GPL-2.0 and GPL-3.0.
Dependencies under EPL-1.0 include Clojure itself, core.async, tools.reader, data.json, instaparse, nREPL, cider-nrepl,
shadow-cljs; under Apache-2.0: http-kit, transit-clj/cljs, the Datomic peer.

Authorized files:
- LICENSE (replace)
- LICENSE-EXCEPTION (new)
- package.json (only the "license" field)

Acceptance criteria:
1. LICENSE is the canonical, verbatim GNU GPL version 2 text (June 1991), fetched from https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt;
   record the fetch URL and SHA-256 in your report. No edits to the GPL text itself.
2. LICENSE-EXCEPTION: a short, plain-English additional permission modelled on established GPLv2 linking exceptions
   (cite which ones you modelled it on, e.g. the GNU Classpath exception and the classic "As a special exception, the
   copyright holders give permission to link ..." form). It must: state the project is GPL-2.0-only (no "or later");
   grant permission to combine/link datom.world with independent modules licensed under EPL-1.0, EPL-2.0 and
   Apache-2.0 and to distribute the combination, without those modules' licence terms becoming GPL obligations, while
   datom.world's own code stays GPL-2.0-only; say the exception does not extend to modifications that remove it unless
   the modifier chooses to; name the copyright holder ("Copyright (C) <years> Sonny To"; years from git history).
   Keep it under ~40 lines. Mark it clearly as a DRAFT pending owner approval at the top of your report (not in the file).
3. package.json "license": an SPDX expression for GPL-2.0-only plus a custom exception — use
   "GPL-2.0-only WITH LicenseRef-datomworld-linking-exception" only if that form is valid SPDX; otherwise
   "SEE LICENSE IN LICENSE"; justify the choice against the SPDX spec.
4. Do NOT add per-file SPDX headers, README sections, or anything else (owner prefers minimal diffs).
Report: the exact full text of LICENSE-EXCEPTION, the package.json line, the GPL text source + digest, and any legal
caveats you see (state that this is not legal advice and recommend counsel review before a public release).
Run in the FOREGROUND: a check that LICENSE is byte-identical to the fetched canonical text; ASCII-only check on
LICENSE-EXCEPTION; package.json still parses (node -e JSON.parse).

Write the report to /Users/sto/workspace/datomworld-license/collab/1790857898202-subagent-license-gpl2-exception.claude-opus-5-5.report.md
and give it as your final response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: c9c76024-1216-4bf5-a6b5-5d725ecc491e

## Round 2 (orchestrator) — review REQUEST CHANGES (gpt-6.1-sol) + owner scope decisions
Review (copy in this worktree's collab/): 1790858923272-reviewer-license-gpl2-exception-gate.gpt-6.1-sol.findings.md,
thread 01a0f782-a3c1-7173-9857-646af3b75f30. Orchestrator: node parses package.json; LICENSE digest matches your fetch;
gnu.org re-fetch still times out (byte check pending, orchestrator will redo it before commit).
OWNER (verbatim): "I'm not using project.clj"; options chosen, verbatim: "Delete it (Recommended)" and "Yes, update it
(Recommended)" (package-lock.json root license only).
Authorized files this round: LICENSE-EXCEPTION, package.json ("license" only), package-lock.json (ROOT package
"license" only; no dependency entries, versions or integrity hashes), project.clj (DELETE), and the Leiningen/project.clj
mentions in src/cljc/yin/vm/docs/documentation_index.md (lines ~103, ~227), src/cljc/yin/vm/docs/summary.md (~36),
src/cljc/yin/vm/docs/yin_vm_tests.md (~21, 32, 112-113) — remove or rewrite only those lines so nothing points at a
missing file or at lein (use clj/bb equivalents only where the surrounding text needs a command; say what you chose).
LICENSE stays byte-identical to the GPLv2 text.
Fix:
1. P1 clause (a): explicitly waive GPLv2's whole-work licensing requirement for the permitted combination, while
   datom.world's own code and modifications to it remain GPL-2.0-only; distinguish those modifications from the
   combination (model on the Classpath exception's treatment of the resulting executable, without its "terms of your
   choice" applying to datom.world code).
2. P2 clause (a) "including its corresponding source": preserve GPLv2 section 3's compliance options for datom.world, and
   state that independent modules' source obligations arise solely under those modules' own licences.
3. P2 package.json and package-lock.json root: "SEE LICENSE IN LICENSE-EXCEPTION" (that file names GPL-2.0-only and
   points to LICENSE).
4. Fold the reviewer's corrections of your caveats into the report (third-party material, historical metadata, inbound
   policy, transitive/generated-code audit).
Checks in the FOREGROUND: LICENSE unchanged since round 1 (sha256 edaef632...); LICENSE-EXCEPTION ASCII-only; package.json
and package-lock.json parse (python json.load; node if permitted); git shows project.clj deleted and no remaining tracked
reference to project.clj or lein outside collab/archive. Append "Round 2" to the report with the FULL final text of
LICENSE-EXCEPTION; give the full report as your final response (same header).
