# Slice S2d architectural sign-off

**Verdict: WITHHELD**

Reviewed branch `stream-crossmachine-s2`, HEAD `4ca18fec`, including the twelve modified files in the supplied scope. The portable implementation substantially follows S2d, but two host API restrictions invalidate the claimed overflow teardown behavior. The independent ACCEPT verdict does not account for those restrictions. Green tests do not resolve them because the relevant doubles accept close codes that the real APIs reject.

## Blocking findings

### B1 — Browser overflow closes the handle locally but fails to close the socket

Locations: `src/cljs/dao/stream/ws/browser.cljs:76`; `src/cljc/dao/stream/ws.cljc:194`, `:328`, `:343`, and `invoke-close!` at `:144`.

The new outbound-overflow path requests close 1008; inbound frame overflow requests 1009. The browser seam forwards both directly to DOM `WebSocket.close`. That API permits an explicit code of 1000 or 3000–4999; otherwise it throws `InvalidAccessError` before initiating closure. This is a host contract, not a hypothetical network failure. See the [WHATWG close algorithm](https://websockets.spec.whatwg.org/#dom-websocket-close).

`teardown!` has already set the handle phase to closed, and `invoke-close!` catches the exception without a fallback. Thus the append can report `closed` while the host socket remains open, no closing handshake has begun, and no terminal host event is caused by this teardown. The peer can continue delivering traffic. This violates §1.4/§2 teardown and the D5 requirement to demonstrate host bounds and teardown before lifting its gate.

Verification in this review: Node v26.1.0's built-in WHATWG `WebSocket` rejected 1008, 1009 and 1013 with `InvalidAccessError: invalid code`. This was an API validation probe, not an actual browser integration test. The normative algorithm establishes the browser behavior independently. The fake browser socket in `browser_test.cljs` merely records close arguments and cannot detect this error.

Required resolution: introduce a browser-supported teardown mapping, preserving the qualified local diagnostic, and document the host-specific wire-code exception. A browser cannot meet the literal 1008/1009 wire-code promise through this API. Keep the browser's lift claim gated until its supported teardown is demonstrated. Add tests with DOM close-code validation and verify that overflow initiates a real closure and yields the terminal lifecycle event.

### B2 — JVM close-through-chain cannot send the promised 1009

Locations: `src/clj/dao/stream/ws/jvm.clj:225`, `:244`; `src/cljc/dao/stream/ws.cljc:343`.

The reassembly overflow calls `:too-large!`, which routes 1009 through the normal `sendClose` chain. Java's `WebSocket.sendClose` explicitly disallows 1009 and completes exceptionally for an illegal status code. See the [Java WebSocket API contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/WebSocket.html#sendClose(int,java.lang.String)).

Once that close reaches the head of the chain, the existing failure observer calls `fail!`: it emits socket error, aborts the socket, and reports local close 1006 with `dao.stream/send-failed`. It does not deliver close 1009 to the peer. If an earlier send remains pending, even this fallback waits behind it. Consequently, the author's rationale that normal chaining preserves the 1009 close, and the independent review's acceptance of that rationale, are incorrect.

Required resolution: explicitly define a supported JVM overflow teardown and its observable outcome, amend the impossible wire-code promise for this host, and test an actual JVM client receiving oversized fragmented text and binary messages. Verify no decode, bounded retained assembly, the qualified size diagnostic, and the documented terminal/peer behavior. Include a pending-send case so teardown does not rely on an untested assumption that the chain will drain. Do not describe this as successful transmission of 1009.

## Architectural assessment of the remaining implementation

| Requirement | Assessment |
|---|---|
| Five optional bounds on endpoint and attacher | Implemented, nil defaults preserved; positive-integer validation is a reasonable composition check. |
| Raw inbound size checked before decode | Implemented; text uses character count as the spec explicitly requires. |
| Pending count/size decision in the existing atomic phase/queue update | Implemented, including the closed phase on overflow and byte-counter reset on draining. Sequential release-before-ack behavior is covered. |
| High-water refusal and maximum teardown selection | Portable outcome selection matches the spec; actual host teardown has B1/B2. |
| Optional backlog seam and cumulative fallback | Implemented across the stated hosts. High-water is correctly inactive without a drainable backlog signal. |
| JVM send-chain accounting | Submission/completion accounting is implemented; focused test passes. |
| Node host reassembly limits | `maxPayload` is wired for client and server; server options must be composed consistently with endpoint bounds. |
| http-kit and Dart | Fallback limitations and continued host gates are recorded. |
| Adoption exception isolation | Both `make-media` and acknowledgement append are caught; failed adoption closes the offered handle and registers no session. The loop continues. |
| Foundational invariants | No new registry, scheduler, application callback channel, source gap, or remote outcome vocabulary. Bounds are composition data; diagnostics remain stream data. |

The pre-send threshold interpretation is acceptable under §2's “at or above” wording. It is not a strict cap including the newly submitted frame: one encoded payload can cross the threshold. “Exact” should describe the observed backlog, not a maximum allocation guarantee. Likewise, text character accounting is spec-compliant but is not UTF-8 byte accounting. Browser whole-message inbound allocation remains outside the transport's pre-decode check.

## Additional review notes

1. **The adoption tests do not prove their same-tick assertion.** In `test/dao/stream/ws_project_test.cljc:658–664`, tick 2 handles the throwing offer, but `:answered` is captured only after tick 3. A regression that skips healthy work for tick 2 could pass. Capture the healthy answer immediately after tick 2; the one-slot fixture itself is an acceptable alternative to the spec's two-offer setup.
2. The acknowledgement-throw path discards successfully allocated media without cleanup. Retain this as an ownership follow-up; a local ring becoming unreachable is not by itself proof of a permanent heap leak, but externally owned resources need an explicit disposal contract.
3. Concurrent append admission is not reserved atomically with submission, and the existing acknowledgement/reopen race is not ruled out by a release pass that ran earlier. These are retained concurrency limitations, not newly proven failures in this review. Do not promote the sequential tests into concurrency guarantees.
4. JVM buffers are checked after appending the current fragment; clearing logical length does not release backing capacity. Record the transient/retained capacity qualification and exercise it in the host-bound tests. The browser similarly cannot refuse allocation before receiving a whole message.

## Verification and evidence

- Read the architectural foundations, S2 specification, author report, independent review, build/test guide, and the implementation/test/documentation diffs.
- Independently reran the focused suites: **60 tests, 245 assertions, 0 failures, 0 errors**. The initial sandboxed attempt could not bind two loopback listeners; the rerun with socket permission passed.
- `git diff --check`: clean.
- User-supplied full-lane evidence: JVM 3670 tests / 237680 assertions; Node 3524 tests / 102165 assertions; Dart 3479 tests; all passed. User also reports cljstyle clean and kondo zero errors. Those full lanes and style checks were not independently rerun in this architectural pass. The supplied cljstyle result supersedes the author's earlier missing-check caveat.
- Independently checked the host API contracts and ran the WHATWG close-code probe described above. No live oversized JVM-fragment integration test was run.

## Sign-off decision

S2d is **not approved for final architectural sign-off in its current form**. Resolve B1 and B2 with explicit host-supported teardown semantics, accurate host-gate documentation, and tests that exercise host restrictions. Tighten the same-tick adoption assertion during reconciliation. The bulk of the portable bounds work is acceptable; neither successful local outcome tests nor the current host matrix demonstrates the promised cross-host teardown.

Only this report was added by this review. No implementation changes or commit were made.
