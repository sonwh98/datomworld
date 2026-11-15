Created-GMT: 2026-11-15 20:05:41 UTC
Created-Local: 2026-11-16 03:05 +0700
Coding-Agent: agy
Session-ID: pending (provider-generated)
# Task: J7 implement round — the UDP + DHT composition on jolt
Role: engineer
Implementers:
- Model: gemini-3.8-flash | Assigned: 2026-11-16 03:05 +0700 | Status: active | Rationale: implements per the architect's design

FIRST read collab/jolt/*architect-j7-design.findings.md in this worktree (codex's committed design) and implement its slice 1 (and slice 2 if slice 1 is green). Follow the design's order and contracts exactly; where you must deviate, record why in collab/jolt/<ts>-engineer-j7-impl.findings.md.

Acceptance per slice (run locally in this worktree; mise env first):
- bb bootstrap:jolt; build if the design requires native work.
- jolt -Srepro -M:jolt (PATH via build/tools/jolt-env.sh) loads every new namespace.
- The J0b regression legs the design lists pass on jolt.
- The smoke: `echo '(+ 1 2)' | jolt -Srepro -M:jolt:jolt-yin-repl --index-store dht:/tmp/j7-smoke` boots solo (no --dht-peer) and evaluates the form; the refusal message at yin/shell/dht.cljc:255 is GONE for the composed path.
- JVM lane unchanged: `bb run-clj` equivalent loads yin.shell.dht and yin.repl.
Commit on this branch (fix/j7-udp-dht) per slice with evidence in the message.
