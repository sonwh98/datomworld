Created-GMT: 2026-09-27 17:50:00 GMT
Created-Local: 2026-09-28 00:50:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (dao.stream.remote slice 6 fixes)

# Task: Slice 6 — Fix the gate's findings (two P1s, one P2)

Role: Stream & Network Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master; slice 6 is
uncommitted). The gate returned REQUEST CHANGES with three findings.
Read: collab/1790505140337-architect-dao-stream-remote-slice6-gate.gpt-6-sol.findings.md

1. P1 (udp.cljc:295): reassembly rejects a message when
   parts x 1200 exceeds 64 KiB even if its actual encoded payload is
   within the limit — valid near-limit messages get sent and then
   dropped. Fix: bound the ACCUMULATED chunk bytes against
   :dao.stream.udp/max-message-bytes as fragments arrive, and check
   the completed payload's ACTUAL length at delivery; a message whose
   real size is within the bound must deliver.
2. P1 (jvm.clj:24): the receiver reuses one DatagramPacket without
   resetting its length — after a short datagram a longer one is
   truncated. Fix: reset the packet length to the buffer length
   before each receive. Add a JVM adapter test: short datagram then
   long datagram, both delivered whole.
3. P2 (udp.cljc:292): fragment fields (:part, :parts, :bytes) are used
   without validation — a malformed value can throw during
   reassembly, and the Node/Dart adapter callbacks do not catch.
   Fix: validate fragment types, ranges (0 <= part < parts, parts
   >= 1), and byte-payload shape before changing partial state; drop
   malformed datagrams silently (they are best-effort wire input).
   Add host-free tests for the malformed shapes.

Constraints: touch only src/cljc/dao/stream/udp.cljc,
src/clj/dao/stream/udp/jvm.clj, test/dao/stream/udp_test.cljc (plus a
jvm adapter test file if cleaner). Preserve everything the gate
confirmed (the direction key, the 64 default, the deposit+forwarding
shape). ASCII, <= 80 cols, cljstyle/kondo clean (note: the
orchestrator applied cljstyle fix to your files — keep that
formatting), no commit/stage/checkout/reset/stash, no diagnostics.
Verify all three lanes sequentially/solo with exact counts (current:
JVM 2,276/183,262/0; Node 2,185/49,891/0; Dart 2,145).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
