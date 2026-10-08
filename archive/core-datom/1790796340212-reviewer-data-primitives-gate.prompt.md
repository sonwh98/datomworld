Created-GMT: 2026-09-30 19:25:40 GMT
Created-Local: 2026-10-01 02:25:40 +0700
Coding-Agent: glm
Session-ID: 739a8ff9-8f79-446b-8f7b-69394ebf9782
# Task: Gate — pure data primitives host module (collections + code-point strings)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: glm-5.3 | Assigned: 2026-10-01 02:25:40 +0700 | Status: active | Rationale: non-Claude reviewer for Claude-authored code; codex budget reserved for the cell-slice gate

Read-only review in THIS worktree (/Users/sto/workspace/datomworld-data-prims, branch vm-data-primitives at master
9a69e58f; the change is two NEW uncommitted files). Do not edit files. Review src/cljc/yin/vm/data.cljc and
test/yin/vm/data_test.cljc.

OWNER (verbatim): "dispatch the data primitives module in parallel too"; earlier "accept all recommendations" (pure data
primitives live in a :pure host module named by the language runtime profile, not in vm/primitives).
Ruling: collab/1790778866412-architect-mutable-objects.claude-fable-5-1.findings.md (Q5, Q6).
Brief: collab/1790794940418-vm-engineer-data-primitives.prompt.md
Report (untrusted): collab/1790794940418-vm-engineer-data-primitives.claude-opus-5-5.report.md
(all three in this worktree's collab/)

Orchestrator-verified (do not rerun): cljstyle clean; kondo 0/0 on both files; no clash with master's subvec (that is a
private query builtin in dao/space/query.cljc). Full JVM/Node/CLJD lanes running in the orchestrator's seat.
Implementer-claimed: JVM 2441/185386/0, Node 2346/51786/0; mutation proof M1-M12.

Check: code-point correctness incl. surrogate pairs, lone surrogates, U+10FFFF, empty strings; identical behaviour across
CLJ/CLJS/CLJD (the :cljd branches have never compiled — read them against ClojureDart idioms, e.g. .codeUnitAt,
StringBuffer, num .isFinite/.round/.toInt, :cljd listed FIRST in reader conditionals); index coercion (1.0 as 1; NaN,
infinities, > 2^53); no host-map iteration exported (into refusing map/set->vector); refusal ex-data identical across
hosts and no host exception text; arity checks; every export :pure with #{} effects and absent from vm/primitives;
performance traps (O(n) decode per call). Rule on: the implementer's concern that key equality uses host = (1 vs 1.0
differs JVM vs JS/Dart) is deferred to the language profile — acceptable?
Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: 739a8ff9-8f79-446b-8f7b-69394ebf9782
Findings as P0-P3 | file:line | evidence | concrete fix, or "No actionable findings". End with Verdict: READY /
REQUEST CHANGES and Sign-off: GRANTED / WITHHELD.

## Round 2 (consensus follow-up)
Your r1: REQUEST CHANGES — P2 index-coercion guard untested; P3 docstring cost note; P3 pop/nth ::index sentinel.
Orchestrator lanes on r1 code also found one CLJD failure: non-bmp-code-points-test got [63 97] — a lone-surrogate
source literal emitted as "?" in the generated Dart source.
Implementer Round 2 (section "Round 2" of collab/1790794940418-vm-engineer-data-primitives.claude-opus-5-5.report.md in
this worktree): lone-surrogate inputs built at run time via a host one-unit constructor (from-units) with an input guard;
NaN/±Inf/±2^53 refusal tests + 2^53-1 boundary; docstring bullet; ::index = the position the call would access (pop
reports count-1, -1 on empty). Mutation proof M13-M16; M14 (drop JVM Double/isFinite) survives as an equivalent mutant
because the ±2^53 bound comparison already rejects NaN/±Inf — rule whether the redundant clause should stay or go.
Orchestrator-verified: cljstyle clean on both files (per file); kondo 0/0. Lanes (incl. CLJD) running in the
orchestrator's seat. Re-read only the changed parts (git diff is not available for untracked files: read the two files).
Findings as P0-P3 | file:line | evidence | fix, or "No actionable findings"; end with Verdict: READY / REQUEST CHANGES and
Sign-off: GRANTED / WITHHELD. Begin exactly with Completed-GMT / Completed-Local / Coding-Agent: glm / Session-ID:
739a8ff9-8f79-446b-8f7b-69394ebf9782.
