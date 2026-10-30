Created-GMT: 2026-10-19 15:32:06 GMT
Created-Local: 2026-10-19 22:32:06 +07
Coding-Agent: codex
Session-ID: 01a14f30-ca56-7530-bb93-3a870eca7a7b
# Task: jolt host-lane plan, revision r3 (add UDP-over-FFI probe to J0)
Role: Lead System Architect
Implementers:
- Model: gpt-6-astra | Assigned: 2026-10-19 22:32:06 +07 | Status: active | Rationale: plan author; resumed for an owner-requested addition

Owner instruction (verbatim quote): "yes, have the architect add the UDP probe to J0"
Context: the owner asked whether C UDP can be reached over FFI. Orchestrator read of the jolt checkout (build/jolt-spike/jolt, @96d7b866), NOT executed: stdlib/jolt/socket/native.clj already declares socket, bind, setsockopt, getsockname, close, poll (WSAPoll on Windows), ioctl/fcntl via ffi/defcfn, plus sockaddr helpers; it has sock-stream but no SOCK_DGRAM constant and no sendto/recvfrom/recvmsg/sendmsg. stdlib/jolt/socket.clj is a java.net-style TCP layer (non-blocking, parks fibers on jolt.io-poller); no DatagramSocket exists anywhere in stdlib. A recv error reads as EOF there (documented divergence).

Write a NEW file with the full revised plan (not a diff), same header fields:
/Users/sto/workspace/datomworld/collab/1792423926000-architect-jolt-lane-plan-r3.gpt-6-astra.findings.md
Base it on your r2 file (collab/1792423156000-architect-jolt-lane-plan-r2.gpt-6-astra.findings.md). Do not edit earlier files. Write only that file.

Required: add a UDP probe to J0 with falsifiable acceptance: loopback send/receive via FFI sendto/recvfrom on a SOCK_DGRAM socket, one packet per call with boundaries preserved, oversize-datagram truncation detected and surfaced as an error (not silent), EAGAIN/full on send mapped to the dao.stream backpressure outcome (no silent drop), non-blocking wait through the existing poller, and macOS arm64 + Linux x86_64 results recorded. State the Windows declarations as out of scope until Q2 widens platforms. Update J7 and the Q6/§6 UDP text to reflect that the raw transport is a small slice on existing native.clj groundwork while the DHT/datagram-contract work is the real J7 effort; mark the claims about jolt.socket as "read, not run" (R) unless the probe runs them. Keep the other r2 content, owner questions and dispositions intact; add a short r3 change note. No src/ edits; nothing is commissioned.
