Created-GMT: 2026-11-20 (dispatch-time)
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: J7 slice 4 — the jolt-inclusive DHT process test and the post-ping stall
Role: engineer
## State of record (all measured on this host)
- The CLI parser fix (555715a7) works: a jolt node with --index-store dht: composes with a peer, SENDS exactly one datagram, and resolves a published name through the publisher WebSocket head board (require reaches the link phase, "link [:t0 1]"). Name resolution cross-host: WORKS.
- The stall: after that single sendto (the initial ping), NO further datagram leaves the jolt node — strace-verified — while the JVM publisher (tick-fed, stepping) never receives a fetch. A JVM reader in the same topology advances to link and gets served (1-of-2 acked). The gap is jolt post-ping: either the cookie reply is never processed (receive path), or the node stops stepping after the first engine round.
- The post-fix stepper test (test/yin/repl/dht_step_test.cljc) passes: one seam step per owner round is verified — so the node IS stepped; the receive-processing inside the step is where to look (the cookie reply handling in dao.jing.dht vs what the binder delivers through the raw cursor on jolt).
## Deliverables
1. A jolt-inclusive DHT process test modeled on test/yin/repl/dht_process_test.clj: JVM anchor + JVM publisher, jolt reader process driven programmatically (stdin piped, no human), asserting require-by-name -> call across hosts, runnable from this worktree with --no-state isolation. It must fail today on the post-ping stall (that is the point) and pass when the stall is fixed.
2. Fix the stall: instrument or reason where the cookie reply dies on jolt (binder receive cursor vs dao.jing.dht ping state), make the fix, keep it inside the bounded-stepping contract. If the fix lands elsewhere than jolt arms (shared code), it must keep JVM/Node/Dart green.
3. Acceptance with EVIDENCE in collab/jolt/j7-slice4.findings.md:
   a. strace a jolt dht node during a full require: sendto > 1 (ping + fetches).
   b. The process test: JVM publishes, jolt requires by name and CALLS (f 1) => 4201.
   c. The JVM dht_process_test legs still green; dao.stream.udp-test 0 failures; probes 37/37 + 83/83.
Commit per deliverable on this branch. Deviations from the committed design recorded in findings.
