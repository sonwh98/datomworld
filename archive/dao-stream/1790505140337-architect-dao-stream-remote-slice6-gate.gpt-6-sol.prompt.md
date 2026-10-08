Created-GMT: 2026-09-27 17:45:00 GMT
Created-Local: 2026-09-28 00:45:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Slice 6 Gate — dao.stream.udp (the UDP channel)

Role: Lead System Architect (review + sign-off)

Slice 6 of the accepted dao.stream.remote implementation is in the
uncommitted working tree of /Users/sto/workspace/datomworld, implemented
by a claude CLI session. New files (5+1): src/cljc/dao/stream/udp.cljc
(the channel: descriptor?, address-key, make-port/send-value!/send-to!
with single-datagram fast path and fragmentation over the 1200-byte
datagram budget, :dao.stream/transport-error +
:dao.stream.remote/oversize beyond :dao.stream.udp/max-message-bytes
(default 64 KiB), receive! reassembly + the deposit-event shape,
projection/step! per attachment, make-attacher/UdpHandle), three thin
host adapters (jvm DatagramSocket, node dgram, dart
RawDatagramSocket — protocol-free), test/dao/stream/udp_test.cljc (10
host-free tests), and two DYNAMIC WARNING type-hints fixed in
dart.cljd. Implementer report (treat as untrusted):
collab/1790502148751-vm-engineer-dao-stream-remote-slice6.claude.findings.md
— it documents THREE minimal-reading ambiguities (the direction key
added to the fragment envelope because attachment identity and source
address collapse on UDP and this peer can be both asker and mirror;
:max-partial-messages default 64 chosen as implementation's own;
deposit medium + forwarding step both implemented per item 1's
literal wording). Judge each.

Adversarial focus:
1. Spec fidelity to 3.2: one canonical-CBOR value per datagram; the
   1200-byte budget covering the whole encoded datagram envelope
   included; fragmentation keyed by [attachment identity, source
   address, direction, id]; loss of a part = loss of the message;
   the two eviction bounds (no clock); replies to the datagram's
   source address never to a payload-claimed address; explicit
   destination sends; unordered best-effort; attachment identity =
   remote host:port as the socket saw it.
2. The mirror step and the link never see a fragment — reassembly
   delivers only complete decoded messages.
3. The oversize path: unsent, transport-error with reason oversize;
   the 48 KiB toy inside the default maximum.
4. The three ambiguity readings for contract fidelity and edge
   defects (especially the direction key: can a direction collision
   still corrupt reassembly?).
5. Invariants (P2P no-privilege — no server/client roles in the UDP
   channel) and hygiene on all added lines (the orchestrator applied
   cljstyle fix to 3 files after the implementer's sandbox could not
   run it; formatting-only).

Orchestrator evidence (do not rerun suites): JVM 2,276/183,262/0;
Node 2,185/49,891/0; Dart 2,145 passed — implementer counts
independently reproduced identically. The udp namespace after
cljstyle-fix: 10 tests / 23 assertions / 0 failures.

Do not edit files. Cite file:line evidence.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report findings as:
P0-P3 | file:line | evidence | concrete fix

End with exactly two lines:
Verdict: READY
Sign-off: GRANTED
or
Verdict: REQUEST CHANGES
Sign-off: DENIED
