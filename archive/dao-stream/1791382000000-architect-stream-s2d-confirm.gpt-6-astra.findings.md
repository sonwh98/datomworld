# Slice S2d final architectural sign-off

**Verdict: ACCEPTED**

Reviewed `stream-crossmachine-s2`, HEAD `4ca18fec`, including the uncommitted reconciliation. This confirmation supersedes the WITHHELD verdict in `collab/1791380000000-architect-stream-s2d-signoff.gpt-6-astra.findings.md` for S2d. B1 and B2 are resolved, and the same-tick adoption assertion is corrected. No remaining blocking finding in this reconciliation scope.

## B1 — Resolved; browser host lift remains gated

`src/cljs/dao/stream/ws/browser.cljs:71–96` validates the requested close code at the host seam and substitutes 1000 for unsupported codes, retaining the transport reason. Existing supported codes pass through. This satisfies the [WHATWG close-code contract](https://websockets.spec.whatwg.org/#dom-websocket-close), which permits explicit 1000 or 3000–4999. The qualified local overflow diagnostic remains transport stream data; no new remote outcome or private wire vocabulary is introduced.

`test/dao/stream/ws/browser_test.cljs:140–201` supplies the missing contract enforcement: invalid raw codes throw without recording a close, while inbound and outbound overflow pass supported arguments and yield error followed by terminal through the wired close event. These tests would catch the previous direct forwarding of 1008/1009.

The fake dispatches closure synchronously and echoes the requested code. It demonstrates mapping and lifecycle wiring, not real-browser timing, peer behavior, or closure under a stalled outbound queue. DOM close does not discard previously queued sends. Accordingly, `docs/design/dao.stream.ws.md:498–514` correctly retains the browser gate and explicitly identifies the fake-only evidence. Acceptance of S2d does **not** authorize lifting that gate. A successful wire handshake uses 1000 plus the reason; abnormal closure need not produce that peer-observed code.

## B2 — Resolved

`src/clj/dao/stream/ws/jvm.clj:133–138,188–192,241–249` routes the explicitly prohibited Java close codes to immediate abort, outside the send chain. The rejection set matches the [Java WebSocket API contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/WebSocket.html#sendClose(int,java.lang.String)). The existing once-only failure claim precedes abort, so failed pending futures do not replace the size diagnostic with a send-failed diagnostic or a second terminal. Further sends return closed.

The adapter reports the requested code and reason locally after abort. This is a host teardown outcome deposited through the existing adapter, not a claim that 1009 was transmitted. The documentation now distinguishes the local terminal from the peer's abnormal connection loss without a close frame.

Two complementary tests establish the resolution:

- `test/dao/stream/ws/jvm_test.clj:156` proves immediate abort with one send in flight and another chained, no sendClose invocation, no duplicate report when the pending future fails, and refusal of later sends. It also preserves normal chaining for 1008.
- `test/dao/stream/ws/jvm_test.clj:599–694` uses a real JVM client and raw loopback peer sending four 600-byte WebSocket fragments against a 1000-unit bound, for both Transit text and CBOR binary. It verifies the size diagnostic, one terminal, no payload or decode-failure, no client close-frame bytes, and peer EOF/reset within the bounded wait. This test passed independently in this review.

The live test alone would not distinguish the former eventual abort after illegal sendClose; the pending-send regression supplies that distinction. Together with inspection of the pre-decode reassembly path, the evidence addresses B2.

## Same-tick adoption assertion — Resolved

`test/dao/stream/ws_project_test.cljc:659–666` now realizes the healthy answer set immediately after tick 2, before stepping tick 3. Both make-media and acknowledgement-append failure tests assert that captured set. A regression postponing healthy work until tick 3 can no longer pass. The existing one-slot fixture remains an acceptable proof of isolation.

## Qualifications retained by this sign-off

- The JVM reassembly bound limits accumulated logical content, checked after appending the current callback fragment. “Bound plus one fragment” is not a strict heap-capacity limit: growable backing arrays may overallocate, the binary path has a temporary chunk, and clearing retains backing capacity until the connection becomes reclaimable. The live test exercises overflow but does not measure retained capacity. This is the previously identified qualification, not a newly demonstrated blocker.
- Browser inbound allocation still precedes the whole-message transport check. Browser, http-kit server, and Dart remain gated as documented.
- Backlog observations and sequential tests do not establish atomic concurrent append admission or eliminate the acknowledgement/reopen race. Threshold checks can admit a payload that crosses the threshold; text character counts are not UTF-8 byte counts.
- Media disposal after acknowledgement failure remains an ownership follow-up. The pre-open JVM deferred-close path does not use the new abort mapping, but the reviewed overflow paths require an opened client and cannot trigger it before onOpen.

The reconciliation preserves the architectural boundary: host restrictions are handled in host seams, diagnostics and lifecycle remain stream data, and portable interpretation gains no application callback channel, scheduler, registry, or remote outcome vocabulary.

## Verification

- Independently read the foundations, prior architectural findings, reconciliation report, relevant implementation/test/documentation diffs, and host API contracts.
- Independently ran `clojure -M:test -n dao.stream.ws.jvm-test -n dao.stream.ws-project-test -n dao.stream.ws-test`: **62 tests, 270 assertions, 0 failures, 0 errors**. The first sandboxed run could not bind loopback listeners (three environment errors); the authorized rerun with socket access passed.
- `git diff --check`: clean.
- Supplied full-lane evidence, not independently rerun here: JVM **3672 tests / 237705 assertions**, Node **3527 tests / 102174 assertions**, Dart **3479 tests**, all passing; cljstyle clean; kondo zero errors. Browser regression execution relies on that supplied Node-lane evidence.

**Final decision: ACCEPTED for Slice S2d with the documented host gates and retained qualifications.** This is not blanket approval to lift every host's loopback gate. Only this report was added; no implementation changes or commit were made.
