Created-GMT: 2026-10-28 09:00:07 GMT
Created-Local: 2026-10-28 16:00:07 +07 (+0700)
Coding-Agent: claude
Session-ID: a5c84655-a61f-4a23-9732-4d1bf8e23f3d
# Task: J5b round 3b — wall-time limits are laptop limits
Role: Compiler & AST Engineer (native parser)
Implementers:
- Model: claude-opus-5-5 | Assigned: now | Status: active | Rationale: resume; Linux evidence from shiva (collab/shiva-probe-j5.log here) found a spec-vs-script mismatch

Resume J5b in /Users/sto/workspace/datomworld-j5b. The Linux probe on shiva (collab/shiva-probe-j5.log: 54 PASS, 3 FAIL) shows ONE cause: the Architect's ruling set the r1c-at-10,000 wall limit as 'within 15 s ON THE LAPTOP' (and power at 100,000 within 60 s); your probe applies the numeric limits on every host. On shiva (slower, loaded with other jobs) r1c took 17-18 s (laptop 7.3-7.6 s), the packet equalled the JVM's (parse correct), and the budget-gates child therefore exited 1 and 'below walls on jolt' failed. Fix per the ruling's own wording: gate the numeric wall limits (r1c 15 s, power 60 s) only on macOS arm64 (the laptop); on every other host RECORD the wall times (they go in the §7.6 time paragraph and Platforms block) and assert only a loose sanity bound of 6x the laptop limit (r1c 90 s, power 360 s) so a hang or a true regression still fails. Apply in jolt_j5_below.cljc, jolt_j5_budget.clj and jolt_j5_run.clj wherever the walls are asserted; do not change any other leg, any bound or the parser. Run the macOS budget leg and the below comparison once (foreground, one lane at a time); the Linux reruns are the orchestrator's. Constraints unchanged: no stage/commit, no collab/ cites, cljstyle check on your files if your shell allows. Begin with Completed-GMT / Completed-Local / Coding-Agent / Session-ID lines, then the delta (git status of files you touched this round), exact counts.
