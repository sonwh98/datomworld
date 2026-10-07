Created-GMT: 2026-09-27 16:55:00 GMT
Created-Local: 2026-09-27 23:55:00 +0700
Coding-Agent: claude
Session-ID: generated at dispatch (record in your report)

# Task: dao.stream.remote Implementation — Slice 6 (UDP channel)

Role: Stream & Network Engineer

Repository: /Users/sto/workspace/datomworld (branch master @ 83cc8bcd
or later; slices 0-2 are committed; slices 3-5 are IN FLIGHT in other
sessions — your files are disjoint from all of them; touch nothing
outside your set).

Implement Slice 6 exactly as the plan defines it. Read first, in order:
- docs/design/dao.stream.remote.md section 3.2 (UDP: the WebSocket
  deposit model; the descriptor; one canonical-CBOR value per datagram
  with the 1200-byte budget covering the whole encoded datagram
  including the fragment envelope; fragmentation channel-internal with
  the fragment map {:dao.stream.remote/id n :dao.stream.udp/part i
  :dao.stream.udp/parts k :dao.stream.udp/bytes b}; reassembly keyed by
  [channel attachment identity, source-address, direction, id]; loss of
  a part is loss of the message, recovered by the link's resend rule;
  bounds :dao.stream.udp/max-message-bytes (default 64 KiB) and
  :dao.stream.udp/max-partial-messages evicting oldest, no clock;
  neither mirror nor link ever sees a fragment; replies go to the
  datagram's source address; explicit destination sends for hole
  punching; unordered and best-effort; attachment identity is the
  remote host:port as the socket saw it) and section 3's channel
  preamble
- docs/design/dao.stream.remote.implementation-plan.md slice-6 row
  (adapters src/clj/dao/stream/udp/jvm.clj,
  src/cljs/dao/stream/udp/node.cljs, src/cljd/dao/stream/udp/dart.cljd;
  proof: the toy over UDP with a 48 KiB value (inside the default
  64 KiB maximum); loss injection recovers by resend; oversize beyond
  the maximum answers transport-error with reason oversize)
- docs/design/dao.jing.dht.node.cljc or its source (the existing
  1200-byte datagram budget precedent)
- src/cljc/dao/stream/remote.cljc section 2's answer shapes (the
  channel carries values; the link's resend rule is the recovery)

Work items:
1. NEW src/cljc/dao/stream/udp.cljc: the channel over the deposit
   model — the descriptor, attach! binding/reusing a local socket,
   the writer path (encode canonical CBOR, fragment if over the
   1200-byte datagram budget, send datagrams to the destination),
   the deposit adapter shape (every datagram deposited as an event
   carrying source address + decoded value onto the wired medium),
   reassembly keyed by [attachment identity, source address,
   direction, id] with the two eviction bounds, oversize ->
   transport-error with reason oversize, explicit-destination sends.
2. NEW host adapters: src/clj/dao/stream/udp/jvm.clj (DatagramSocket),
   src/cljs/dao/stream/udp/node.cljs (dgram),
   src/cljd/dao/stream/udp/dart.cljd (RawDatagramSocket) — thin:
   socket bind/send/receive only; all protocol logic lives in udp.cljc
   so it is testable host-free.
3. NEW test/dao/stream/udp_test.cljc (portable, host-free protocol
   tests): fragmentation/reassembly round trip; loss of any part
   loses the message (recoverable by resend at the link level —
   simulate by re-sending); oversize -> transport-error reason
   oversize; reassembly eviction (max-partial-messages, oldest
   evicted); the 48 KiB toy value inside the default 64 KiB maximum;
   replies to the source address; keying by [identity, source,
   direction, id] (two attachments, equal ids, no cross-delivery).
4. Host-adapter smoke tests per lane where the environment allows
   (real datagrams on loopback); report what ran.

Constraints:
- NEW files only (udp.cljc, the three adapters, udp_test.cljc, plus
  adapter smoke-test files if needed). Touch NOTHING else. If the
  design requires a dao.stream.md or dao.stream.remote.md change,
  STOP and report BLOCKED with the specific gap.
- The mirror step and the link must never see a fragment: fragments
  are channel-internal, per 3.2. Your reassembly delivers only
  complete decoded messages.
- Pure ASCII, <= 80 columns on every added line; cljstyle and kondo
  clean; no commit/stage/checkout/reset/stash; no leftover
  diagnostics; no clock reads (eviction by count, not time).
- Verify: JVM full suite green (current baseline 2,254/183,121/0 plus
  your new tests), Node green, Dart green. Sequential, solo. Exact
  counts. If a lane failure is caused by another session's in-flight
  files (linker.cljc, yin.repl, dao.jing), report which with evidence
  and prove YOUR namespaces green with focused runs.
- Where genuinely ambiguous, minimal reading, noted in your report.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
