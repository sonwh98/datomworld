Completed-GMT: 2026-09-10 05:31:31 GMT
Completed-Local: 2026-09-10 12:31:31 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 26a798b9-cdc8-4a34-99b8-dbc27cd81708 (resumed)

# dao.jing.remote Phase 2 round 5 — the gate's upper end; the close now bites

Not staged, not committed. This round touched exactly two files:
`src/cljc/dao/jing/remote.cljc` and `test/dao/jing/remote_test.cljc`.

## P1 — the timing domain is now bounded on both ends

New public `max-timing-ms` = **86400000, one day**, beside the three
defaults, with the reasoning in its docstring: a timing option bounds how
long one connect or one call may wait, and a day is the longest duration
that can still be said to do that — anything larger is connection policy,
not a timeout; at a day the deadline arithmetic has nine orders of
magnitude of headroom against long overflow.

`validate-timing-options!` now enforces **int-shaped milliseconds from 1
to `max-timing-ms` inclusive**: `(int? value)` rejects the BigInt and
BigDecimal shapes that satisfy `integer?` but cannot become a JVM sleep
duration, and `<=` against the bound keeps every deadline computation in
the domain safely inside long arithmetic — `Long/MAX_VALUE` no longer
passes to overflow after submission, on either path. The error names the
domain ("must be an integer of milliseconds from 1 to 86400000 (one
day)") and carries `:maximum-ms`. Both gates unchanged in placement:
`call!` before the lock and before `rpc/request!`; `connect-content!`
first form of its body, before the descriptor and before `attach!`.
Both docstrings' option sentences state the domain.

### The proof, and one reading of "at the bound"

Extended `invalid-timing-options-throw-before-the-wire-and-leave-no-trace`
(the recording-handler test — the wire remains the discriminator).
New section:

- **At the bound, accepted on both paths**: a call with
  `:request-timeout-ms max-timing-ms` submits (the handler records
  exactly its own request) and returns `::served`; a connect with
  `:connect-timeout-ms max-timing-ms` establishes and returns a client.
  This is the end-to-end proof that the deadline arithmetic is safe over
  the whole domain.
- **Rejected, nothing submitted or attached, allocator unchanged**:
  `(inc max-timing-ms)`, `Long/MAX_VALUE`, and `1234567890123456789012345N`
  — each throws the gate's message on **both** paths, the allocator never
  moves, and the handler still saw only the accepted requests.

On "at the bound … each rejected": I read your list as *pin the boundary
cases*, and chose an **inclusive** domain — the bound itself is a
supported value, proven working end-to-end, and everything strictly past
it is rejected. If you want the bound exclusive, the change is one
character (`<=` → `<`) plus the message; say so and r6 is two minutes.

**Mutation check, run and reverted** — the gate reverted to r4's
`(and (integer? value) (pos? value))`: eight failures, all six boundary
`thrown-with-msg?`s (the overflowing values now threw `long overflow`
after submission, or — for `inc max`, which does not overflow — the call
*completed normally* and submitted `:too-big` to the wire) plus the
allocator and wire assertions. Restored: 0 failures, twice; stigmergy
still green.

## P2 — the close is now recorded, at the socket the real close! reaches

The reviewer's named mechanism works. The ninth-exit test now installs a
**scripted raw socket by `with-redefs` on `jvm/connect!`** — a plain
function var, which `with-redefs` *can* reach (unlike the protocol fn
`stream/close!`, whose compiled call sites link straight to the interface
method, as r4 probed). `remote.cljc` dereferences `jvm/connect!` as a
value when composing the attacher, so the redef is visible; the scripted
seam returns `{:send! (fn …) :close! recorder}`. Everything real stays
real — attacher, `WsHandle`, protocol dispatch, `stream/close!` — and
the pending establishment followed by the interrupt must reach the
recorder. The test gained `(is (= 1 @closes) …)` and lost the
ServerSocket/accepter/slot machinery: **no network at all, so this test
no longer pays N2's one-JDK-connection-per-run cost either**.

**Mutation check, run and reverted** — deleting `(stream/close! handle)`
from the ninth exit fails exactly the recorder assertion (`closes` 0);
restored, green.

The test ns gained `dao.stream.ws.jvm` under the same
`#?@(:cljd [] :clj […])` splice as `remote.cljc` (no Dart twin); the
emitted `remote-test_test.dart` imports no `ws/jvm` (count 0), and
`remote.dart` stays clean.

## Verification (final tree)

| command | outcome | r4 baseline |
|---|---|---|
| `clojure -M:test` | **1458 tests, 165538 assertions, 0 failures, 0 errors** | 1458 / 165526 |
| `bb test:cljs` | **1360 tests, 35026 assertions, 0 failures, 0 errors**; `Testing dao.jing.remote-test` present (count 1) | same |
| `bb test:cljd` | **+1314: All tests passed** | same |
| `clj -M:cljs -m shadow.cljs.devtools.cli compile demo` | **212 files, 0 warnings** | same |
| `clj -M:kondo --lint src/cljc/dao/jing/remote.cljc test/dao/jing/remote_test.cljc` | **0 errors, 0 warnings** | 0 / 0 |
| `clojure -M:test -n dao.jing.remote-test` (×2) | 34 tests, 237 assertions, 0 failures both runs | 34 / 225 |
| `clojure -M:test -n dao.space.stigmergy-test` | 5 tests, 22 assertions, 0 failures | unchanged |

Test and suite counts are unchanged from r4 on cljs/cljd because the
boundary proof extends an existing JVM-only deftest; clj gains its 12
executed assertions. Closure greps: v1 tokens → 0 in all three code
files; `reset! (:rpc` → one line, inside `settle!`; the require grep →
nothing; the only `dao.stream.ws.jvm` occurrences in the test file
are the comment and the splice itself.

## Left for you

The plan and design docs, as before. Your N11 wording ("argument defects
throw before the wire") is now true without qualification: the domain
rejects every value that could throw after submission, by type or by
magnitude, on both paths.
