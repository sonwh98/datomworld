Completed-GMT: 2026-09-10 04:48:52 GMT
Completed-Local: 2026-09-10 11:48:52 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 26a798b9-cdc8-4a34-99b8-dbc27cd81708 (resumed)

# dao.jing.remote Phase 2 round 3 — the ninth exit: fixed and proved

N2's close-before-throw now holds at all three failure exits of
`connect-content!`. Not staged, not committed. This round touched exactly
two files: `src/cljc/dao/jing/remote.cljc` and
`test/dao/jing/remote_test.cljc`.

## The fix

`connect-content!`'s establishment loop, the `:pending` branch's sleep —
the same shape as the eighth exit:

- `(stream/close! handle)` **first**, so the handle's phase is `:closed`
  and no handle, medium or state escapes — N2's promise, now honoured at
  every failure exit, not two of three;
- `(.interrupt (Thread/currentThread))` — the flag re-asserted, not
  swallowed, not lost;
- `(throw (ex-info "the content endpoint connect was interrupted"
  {:url url, :reason :dao.jing.remote/interrupted}))` — the same
  `{:url …}` shape as the loop's other two throws.

The catch never returns, so the `recur` after the `try` runs only after a
full sleep (and stays outside the `try`, as `recur` requires). The
docstring's failure paragraph now reads "In **all three** failure cases
stream/close! has run on the handle before the throw" and names the
interruption throw with its shape. Nothing stored needs settling here —
unlike `call!` there is no client state to retire; the handle was the
thing at risk.

## The proof

`connect-throws-on-interruption-with-nothing-escaping`, placed beside its
sibling deadline test, reusing the r2 three-state `slot` teardown against
the same accepts-and-never-writes `ServerSocket`. Deterministic, no
second thread: the test sets its own thread's interrupt flag **before**
`connect-content!` — nothing from URL parsing through `attach!` and the
first `await-established-step` is interruptible, so the loop's first
sleep throws on entry and the wait can only be pending against that peer.
Asserts:

- the flag is preserved, captured inside the catch before any reporting
  machinery (the runner wrinkle from r2, applied here too);
- the throw is the composition's error — `(map? (ex-data error))`, with a
  `::returned` sentinel in the try's success position so a regression to
  *returning* fails this assertion too: because the constructor throws,
  no client value escaped to the caller (N2);
- `{:url url, :reason :dao.jing.remote/interrupted}` exactly.

**On closedness observability, said rather than invented**: the handle's
`:closed` phase has no external seam here — the handle, its traffic
medium and its RPC state are all local to `connect-content!` until it
returns, and the peer never completes the handshake so the close request
the JDK seam stores never crosses the wire. No seam is invented; the
close and the reason are thrown from the same exit, which the ex-data
assertion pins, and the no-escape assertion is the structural one. The
test's body comment records this.

**Mutation check, run and reverted**: with the catch removed (the raw
sleep restored), the test fails all three assertions — flag false, the
raw `InterruptedException` escaping (`ex-data` nil), and the reason map
not matching — 3 failures, then 0 with the fix restored (twice).

## Not touched, per scope

The ticker's `Thread/sleep` (daemon thread, retired through `running`,
no handle of its own, nothing stored — left as instructed); the
eighth-exit fix and its test; the teardown-race fix; the seven planned
exits; the six `network-*` tests (the ninth-exit test is new, a sibling
of the deadline test, and changes no existing assertion); the aliases;
the r1 prose edits; the four design documents; the plan — §5.1's exit
list, N2's exit list, and `dao.jing.md`'s exit sentence are yours to
reconcile, now with a **ninth** exit to add:
`connect-content!` interrupted sleep → `stream/close!` → re-interrupt →
`{:url url, :reason :dao.jing.remote/interrupted}`.

## Verification (final tree)

| command | outcome | r2 baseline |
|---|---|---|
| `clojure -M:test` | **1457 tests, 165515 assertions, 0 failures, 0 errors** | 1456 / 165512 |
| `bb test:cljs` | **1359 tests, 35025 assertions, 0 failures, 0 errors**; `Testing dao.jing.remote-test` present (count 1) | 1358 / 35024 |
| `bb test:cljd` | **+1313: All tests passed** | +1312 |
| `clj -M:cljs -m shadow.cljs.devtools.cli compile demo` | **212 files, 0 warnings** | 212 / 0 |
| `clj -M:kondo --lint src/cljc/dao/jing/remote.cljc test/dao/jing/remote_test.cljc` | **0 errors, 0 warnings** | 0 / 0 |
| `clojure -M:test -n dao.jing.remote-test` (×2) | 33 tests, 214 assertions, 0 failures both runs | 32 / 211 |

Closure greps: `dao\.stream\.rpc\|dao\.stream\.ws\|rpc-ws/\|rpc-client/` →
0 in all three code files; `reset! (:rpc\|swap! (:rpc` → one line, inside
`settle!`; the `src/cljc/dao/jing/` require grep → nothing.

Deltas over r2: +1 test on every host (the ninth-exit proof; 3 new
executed assertions on clj, 1 on cljs/cljd). `docs/agents/roles/orchestrator.md`
is modified in the working tree again by the owner's parallel editing; I
have not touched it, and none of my seven files from r1 changed beyond
the two named above.
