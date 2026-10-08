# Slice S2d reconciliation (B1, B2, same-tick assertion)

Responds to `collab/1791380000000-architect-stream-s2d-signoff.gpt-6-astra.findings.md`.
Branch `stream-crossmachine-s2`, uncommitted on top of `4ca18fec`.

## B1: browser overflow teardown under the DOM close-code contract

**Fix.** `src/cljs/dao/stream/ws/browser.cljs`, `raw-socket` `:close!`: the
DOM `close` only gets a code it accepts (1000 or 3000-4999). The transport's
1008, 1009, and 1013 go out as **1000 with the transport's reason string**
(`dao.stream/outbound-overflow`, `dao.stream/frame-too-large`,
`dao.stream/pending-overflow`). The other transport codes (1000, 4000, 4002)
pass through unchanged. 1011 is only used by served connections, and a
browser cannot serve.

Observable outcome: the DOM socket really closes. Its `close` event reaches
`:closed!` and deposits the terminal `:ws/closed`. The qualified `:ws/error`
reason (`:ws/frame-too-large` or `:ws/outbound-overflow`) is already on the
local stream from `teardown!`. The peer sees close 1000 plus the reason. It
never sees 1008 or 1009.

I mapped to 1000 rather than a private 4xxx code because 4000 and 4002
already mean things to this transport (`ended` and `protocol`). Adding more
private codes would grow the wire vocabulary for a host limitation.

**Tests** (`test/dao/stream/ws/browser_test.cljs`). A new `validating-socket`
fake makes `close` enforce the WHATWG contract: it throws `InvalidAccessError`
for invalid codes and closes nothing. When it accepts a code, it dispatches
the `close` event a real socket would.

- `dom-close-rejects-the-raw-overflow-codes`: the fake rejects 1008, 1009 and
  1013, so it can actually catch the bug the old seam had.
- `inbound-overflow-closes-the-dom-socket`: an oversize whole message
  (`:ws/max-frame-bytes 8`) closes the socket with
  `[1000 "dao.stream/frame-too-large"]`. The stream is `opened`, then
  `error :ws/frame-too-large`, then `closed`.
- `outbound-overflow-closes-the-dom-socket`: when `bufferedAmount` is at or
  over `:ws/max-outbound-bytes`, `append!` answers `closed` and the socket
  closes with `[1000 "dao.stream/outbound-overflow"]`. The stream gets the
  error and then the terminal.

**Doc and gate.** `docs/design/dao.stream.ws.md` has a new "Host wire codes"
paragraph, and the browser's lift status changed from **exact to gated**. The
teardown is demonstrated against a contract-enforcing fake, not a real
browser, so per your instruction the claim stays gated. The ns docstring
records the mapping.

## B2: JVM client cannot send 1009

**Fix.** `src/clj/dao/stream/ws/jvm.clj`, `client-socket` `:close!`. A code
that `java.net.http.WebSocket.sendClose` rejects (the set from its javadoc:
1002, 1003, 1006, 1007, 1009, 1010, 1012, 1013, 1015) is **not chained**.
A new local `abort!` does the following:

1. Claims the connection's failure under the existing once-only claim.
2. Calls `.abort` on the socket immediately, without waiting for any pending
   send.
3. Reports `(:closed! adapter) code reason` locally, for example
   `1009 "dao.stream/frame-too-large"`.

Because the failure is claimed first, the pending sends that `abort` fails
reach `fail!` and report nothing: no second `:ws/error` and no 1006
terminal. Later sends answer `closed`. Codes that `sendClose` accepts (1000,
1008, 4000, 4002) still ride the chain as before.

Observable outcome, stated plainly: **1009 is not transmitted.** The local
stream gets `:ws/error :ws/frame-too-large` and then the terminal
`:ws/closed`. The peer sees the TCP connection drop with no close frame,
which is its own 1006. Among the transport's codes, only 1009 reaches this
path on a client. 1013 is server-only.

**Tests** (`test/dao/stream/ws/jvm_test.clj`):

- `an-unsendable-close-code-aborts-at-once-behind-a-pending-send` (scripted
  socket): this is the pending-send case. With one send in flight and one
  chained, `close! 1009` gives the trace `[send-text] [abort]
  [closed! 1009 …]` right away, with no `sendClose`. When the in-flight
  future later fails, the trace does not change, and a later send answers
  `closed`. A second `testing` block shows that 1008 is still chained behind
  the pending send.
- `an-oversize-fragmented-message-aborts-the-jvm-client` (live, no mocks of
  the client): a raw `ServerSocket` peer does a real RFC 6455 upgrade and
  then sends one message as **four real WebSocket fragments** of 600 bytes
  each (one FIN=0 start frame and three continuation frames), for both
  **text** (Transit attacher) and **binary** (CBOR attacher), against
  `jvm/connect!` with `:ws/max-frame-bytes 1000`. The test asserts:
  - the stream starts with `:ws/opened`;
  - the qualified `:ws/error :ws/frame-too-large` is deposited;
  - the last event is `:ws/closed`, and there is exactly one terminal;
  - there is no `:ws/payload`;
  - there is no `:ws/decode-failure`, so nothing was decoded (the filler is
    not valid Transit or CBOR, so a decode would have failed);
  - the peer received **0 bytes** after the upgrade, meaning no close frame;
  - the peer's read ended in EOF or reset well before the 5 s timeout.

  Limitation: on its own, the live test does not tell the new path from the
  old one. The old path also ended in an abort, because the rejected
  `sendClose` future failed into `fail!`, but it got there through a spurious
  1006 report and only after the pending chain drained. The scripted test is
  the one that tells the two apart.

**Retained assembly (note 4).** The overflow check runs after the current
fragment is appended. Retained assembly therefore peaks at the bound plus
one fragment. After the overflow, `discarding?` stops appending and the
socket is aborted. `setLength 0` and `reset` keep the backing capacity, but
that capacity belongs to an aborted connection's listener and is released
with it. The doc paragraph records this. I did not add an instrumented
assertion of the buffer's capacity, because it is private listener state.

**Not changed.** A close requested *before* `onOpen` with an unsendable code
would still go straight to `sendClose` and fail silently. No transport path
requests 1009 or 1013 before a client opens (no frames have arrived yet), so
I left it alone.

**Doc.** In `dao.stream.ws.md`, the JVM client row now reads "reassembly
bounded, `:too-large!`, then abort". The "Host wire codes" paragraph states
the abort semantics, the peer's 1006, and that 1009 is not sent. The 1009
inbound-bound paragraph points to it. The `client-socket` docstring explains
the abort path.

## Same-tick adoption assertion

`test/dao/stream/ws_project_test.cljc`, `adoption-isolated`: `:answered` is
now captured right after tick 2, the tick in which the bad offer is handled,
before tick 3 is stepped. A regression that defers healthy work past tick 2
now fails both isolation tests.

## Not addressed (retained follow-ups from the review)

- Note 2: the acknowledgement-throw path drops allocated media without a
  disposal contract (an ownership follow-up).
- Note 3: concurrent append admission and the ack/reopen race are still
  sequential-only guarantees.
- The browser still allocates a whole message before the transport's size
  check (a DOM limitation, already recorded).

## Verification

- Focused JVM: `clojure -M:test -n dao.stream.ws.jvm-test -n
  dao.stream.ws-project-test -n dao.stream.ws-test` gave **62 tests, 270
  assertions, 0 failures, 0 errors**.
- `bb test:clj` (full JVM fast lane): **3672 tests, 237705 assertions, 0
  failures, 0 errors**.
- `bb test:cljs` (Node): **3527 tests, 102174 assertions, 0 failures, 0
  errors**. `Testing dao.stream.ws.browser-test` appears in the output, and
  the count includes the 3 new browser tests.
- `bb test:cljd` (Dart): **3479 tests, all passed** (`+3479: All tests
  passed!`). The first run hit the harness's 10-minute background limit and
  was killed. The rerun with a longer limit completed. The two
  `osError` dynamic warnings in `yin/repl/state.cljc` were already there.
- `cljstyle check` on the five changed source and test files: clean.
- `clj -M:kondo --lint` on the five changed files: 0 errors. One warning
  remains, at `jvm_test.clj` (unresolved `org.httpkit.server`). It was there
  before this change, in the existing dual-listener test. I fixed the
  redundant-coercion info that my test introduced.

No commit was made.
