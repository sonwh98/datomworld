Created-GMT: 2026-11-21 18:08:37 GMT
Session-ID: pending (capture conversation_id)
# Task: review P1f — memory-backed DHT on the jolt Gambit backend (bbd35983)

Role: Review (adversarial; author codex gpt-6.2-sol — Gemini family independent)
Implementers:
- Model: gemini-3.8-flash-high | Assigned: see Created-Local

## Inputs

- Worktree /Users/sto/workspace/jolt-ffi-p1f, branch gambit-ffi-p1f, commit
  bbd35983 (base: p1e @ 0aa7f8a4, which carries the probe fixture
  test/gambit/fixtures/yin-dht-probe.jolt + test/gambit/yin-dht-probe.sh).
- Author findings: /Users/sto/workspace/datomworld/collab/jolt/1795215000000-engineer-p1f-dht-gambit-fix.gpt-6.2-sol.findings.md
- The P1e capability map being closed: /Users/sto/workspace/datomworld/collab/jolt/1795213000000-engineer-p1e-dht-gambit-probe.gpt-6.2-sol.findings.md
- C4 constraints binding (blocking FFI refusal; honest degradation only).

## Review priorities

P1 The six blocker fixes are real: shim rows (inet_pton/inet_ntop,
sendto/recvfrom errno-capture six-arg, write-array/read-array) match the
consumer inventory's ABI tuples exactly (shapes.json + generated-shims
consistency); close re-classification — the author kept inline `close`'s C4
refusal and added a NATIVE WORKER that closes datagram sockets instead:
verify the worker's close path is genuinely non-blocking-safe and that no
call-site needed a qualified close is now silently broken.
P2 defcfn in session eval: the loaded-source path reaches the canonical
macro — check the fix doesn't leak the whole stdlib or bypass the
booted-namespace shortcut's other exclusions.
P3 The vector-ref defect fix ("relaxed-vector traversal and record-tag
collisions") — verify it's a type fix, not a blanket catch.
P4 Store admission: the real launcher accepts memory-backed --index-store
dht: — verify durable dht:<dir> STILL refuses with the documented C4
message (negative control), and the capability note says so.
P5 Probe end-to-end: re-run the probe fixture + the stock launcher session
(dht: memory profile, put/get, UDP loopback round trip, blocking-ffi
refusal, session survival) — output-content assertions, watchdogged.
Stage-1 matrix (gambitffi/check/kernel/eval/seed/gen/emittedheads/deadhost)
green.

You may run gates in the worktree (writes under build/ only). READ-ONLY on
sources; never commit/push. Deliverable: findings as your final response
beginning with Completed-* header fields; findings P0-P3 with file:line;
gates re-run; closing RECOMMENDATION: ACCEPT / ACCEPT-WITH-FIXES (list) /
REJECT. The orchestrator promotes it to
/Users/sto/workspace/datomworld/collab/jolt/1795220000000-reviewer-p1f-agyflash.gemini-3.8-flash.findings.md
