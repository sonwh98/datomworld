Created-GMT: 2026-11-19 (dispatch-time)
Coding-Agent: agy
Session-ID: pending (provider-generated)
# Task: J7 slice 3 — wire the DHT node stepper into jolt and fix dht join
Role: engineer
## The evidence (all measured on this host today)
- A jolt dht node binds, registers its peer, goes fetch-only, and a pending require NEVER sends:
  strace -f -e sendto over a 60s pending require = 0 sendto calls; a UDP spy relay between the
  node and its peer captured zero packets; require ends "Module link refused: absent".
- The same topology with a JVM reader delivers (publications acked 1-of-2; the reader reached
  the link phase). The dht_process_test JVM legs are green. So the JVM wire path is sound.
- dao/jing/dht.cljc:1432 steps the node via (get-in node [:seam :step!]) — on jolt nothing
  provides or drives it.
- Second defect: "dht join <token>" on jolt composes SOLO (no --dht-peer) even though the
  grammar is listed in --help and explicit --dht-peer flags work. The subcommand-to-flags
  mapping is not applied on the jolt path.
## Fix requirements
1. Wire the node step/pump into jolt: whatever integrates the binder step!/port-step! and the
   node stepper into the engine round on JVM/Node (find the exact integration point in the
   shell — how pending requires drive node steps on Node — and provide the same on jolt via
   the udp seam step! from src/jolt/dao/stream/udp.jolt). Keep it inside the bounded-stepping
   contract of the committed design (dao.stream.datagram.md; no unbounded loops).
2. Wire dht init|serve|join subcommand sugar into jolt arg parsing to the same flags the JVM
   path derives (join -> --dht-peer + --dht-principal + --dht-follow).
3. Acceptance (run and EVIDENCE in collab/jolt/j7-slice3.findings.md):
   a. strace a jolt dht node during a pending require: sendto > 0.
   b. The UDP spy relay (/tmp/udp-spy.py, logs /tmp/udp-spy.log) shows JOLT->A and A->JOLT packets.
   c. END-TO-END: with the live JVM publisher (topology per /tmp/j7-probe3.sh + /tmp/j7-reader.sh,
      token in /tmp/j7-token.txt), jolt requires my.lib by NAME and (f 1) => 4201. If name
      resolution still fails after the stepper fix (head-follow gap), demonstrate the furthest
      reachable point honestly and record what remains.
   d. bb probe:jolt-udp 37/37 and bb probe:jolt-udp-failures 83/83 still green.
   e. JVM lane unchanged: dao.stream.udp-test 0 failures.
Commit per fix on this branch with evidence. Do not widen scope beyond these two defects.

## ORCHESTRATOR ADDENDUM (round 3: rerouted to codex)
agy failed twice on mechanistic grounds (turn 1: idled backgrounding its own test lane; turn 2:
--conversation resume DROPPED the permissions.allow grant — denied_actions records every Bash
denied — and idled 2.4h). Rerouted to codex. Implementation guidance retained from the round:
the integration point is the binder step!/port-step! (src/jolt/dao/stream/udp.jolt) driven from
the same engine-round hook Node uses; dht join maps to --dht-peer + --dht-principal + --dht-follow.
All prior acceptance requirements stand. Note the fresh-worktree trap: clj -M:antlr-gen before
any JVM shell boot.
