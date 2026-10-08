Completed-GMT: 2026-09-10 04:39:12 GMT
Completed-Local: 2026-09-10 11:39:12 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 26a798b9-cdc8-4a34-99b8-dbc27cd81708 (resumed)

# dao.jing.remote Phase 2 round 2 — the eighth exit: fixed and proved

Both findings addressed; nothing else in the cleared set was touched. Not
staged, not committed. The tree's HEAD moved to `aa232ab` mid-session —
the owner's own `docs(agents)` commit of the roster edits that appeared
during round 1; it touches none of my files, and my working set is the
same seven as round 1 (r2 edits only `remote.cljc` and
`remote_test.cljc`).

## P1 — interruption now leaves through `settle!`

`src/cljc/dao/jing/remote.cljc`, the loop's `:pending` branch. The sleep is
wrapped in its own `try`; on `InterruptedException` the catch **retires the
call from the step's latest state** — `retire-call` with reason
`:dao.jing.remote/interrupted`, so the id leaves `:outstanding` (its late
response is classified unsolicited and dropped) and a still-`:unsent`
envelope is abandoned — **stores it through `settle!`**, then re-asserts
the thread's interrupt flag via `.interrupt` and throws
`{:request-id id :reason :dao.jing.remote/interrupted}`. The interruption
is not swallowed and not lost: the flag survives for the caller, and the
error carries correlation data. `settle!` remains the only way out —
eight exits now, all through it — and remains the only `reset! (:rpc`
site (grep: one line, inside `settle!`).

Shape note: the catch never returns (its `raise` always throws), so the
`recur` after the `try` is reached only after a full sleep. That is also
why the sleep's `try` is not wrapped around the `recur` — `recur` cannot
cross a `try` boundary — and the comment in the code says so. The `call!`
docstring gained the eighth-exit sentence, including why id reuse without
it returns the wrong result to a later caller.

### The proof, deterministic, no real interrupt race

`an-interrupted-call-retires-and-permits-no-id-reuse`
(`test/dao/jing/remote_test.cljc`). No second thread: the test sets the
interrupt flag **on its own thread** after the connect and before the
call — `Thread/sleep` throws on entry when the flag is already set, so
the loop deterministically leaves through the interrupted exit at its
first sleep, over a latch-gated server that keeps the call genuinely
pending. It asserts, in order:

- the stored state after the exit: `:next-id` advanced to 1 (the
  interrupted call's id is consumed), `:outstanding` empty, `:completed`
  and `:diagnostics` empty;
- the interrupt flag is preserved (captured inside the catch — see the
  note below);
- after the latch releases — the server answering the interrupted call
  late with `:gated-late` — the next call `:fast/op` returns `:fast-now`,
  never `:gated-late`: a fresh id carries it and the late response cannot
  satisfy it.

**Mutation check, run and reverted**: with the catch removed (the r1
sleep restored verbatim), the test fails with exactly the reviewer's
predicted symptoms — flag false, the raw `InterruptedException` escaping
(`ex-data` nil), `:next-id` still 0, and **`:gated-late` returned to the
next call**: the wrong result, not merely a leak. 4 failures, then 0
after restoring the fix.

**One test-design fact worth recording**: under cognitect.test-runner the
interrupt flag did not survive between the catch and a later assertion
read — a bare-REPL call preserves it (verified against the real client),
and a minimal `is` probe preserves it, but inside the runner's reporting
path something consumes it. I did not chase the consumer into the runner;
the test captures the flag inside its own catch, before any reporting
machinery runs, which is the more honest read anyway — the flag state at
the moment `call!` returned. The catch's `Thread/interrupted` also clears
it there, and the test's `finally` clears it again defensively so no
later test on the same thread inherits it.

## P2 — test 6's teardown no longer races publication

The `accepted` atom is replaced by a three-state `slot` coordinating the
accepter and the `finally` so exactly one of them closes the accepted
socket, in every interleaving:

- accepter: on accept, `(swap! slot (fn [old] (if (= ::teardown old) old s)))`
  — if the finally already marked teardown, the accepter closes the
  socket it holds itself; otherwise it publishes it;
- finally: `(swap! slot (fn [old] (if (nil? old) ::teardown old)))` — if
  a socket was already published, the finally closes it; if not, it marks
  teardown and the accepter self-closes when its accept lands.

A socket arriving during cleanup is now closed too; the assertions are
unchanged (deadline failure only, no EOF claim), and the body comment
still names the once-per-run JDK leak N2 states.

## Plan delta, for you to reconcile (plan untouched)

§5.1's exit enumeration — `request-undeliverable`, `invalid-request`,
`allocator-error`, `terminal`, the deadline, `:done`, `:terminal` — is
now eight: **interrupted sleep → `retire-call` `:dao.jing.remote/interrupted`
→ `settle!` → re-interrupt → throw**. The same goes for N11's exit list in
§2 and for `call!`'s docstring (which I did update). One knock-on I did
**not** make because the design documents were in the cleared set:
`dao.jing.md`'s new paragraph says "every exit of the blocking driver —
return, timeout, terminal, or an immediate refusal at the writer —
drains"; it could now name interruption as a fifth. One word when you
reconcile. I also left `connect-content!`'s own establishment-loop sleep
uncaught, deliberately: an interruption there has no allocated id, no
outstanding request, and no returned client to reuse, so it has no
correctness defect of P1's class — it just propagates the interrupt
before a client exists. Say the word if you want it closed-and-thrown
there too, for symmetry with N2's failure paths.

## Verification (final tree)

| command | outcome | r1 baseline |
|---|---|---|
| `clojure -M:test` | **1456 tests, 165512 assertions, 0 failures, 0 errors** | 1455 / 165505 |
| `bb test:cljs` | **1358 tests, 35024 assertions, 0 failures, 0 errors**; `Testing dao.jing.remote-test` present (count 1) | 1357 / 35023 |
| `bb test:cljd` | **+1312: All tests passed** | +1311 |
| `clj -M:cljs -m shadow.cljs.devtools.cli compile demo` | **212 files, 0 warnings** | 212 / 0 |
| `clj -M:kondo --lint src/cljc/dao/jing/remote.cljc test/dao/jing/remote_test.cljc` | **0 errors, 0 warnings** | 0 / 0 |
| `clojure -M:test -n dao.jing.remote-test` (×2) | 32 tests, 211 assertions, 0 failures both runs | 31 / 204 |

Closure greps: `dao\.stream\.rpc\|dao\.stream\.ws\|rpc-ws/\|rpc-client/` →
0 in `remote.cljc`, `remote_test.cljc`, `stigmergy_test.clj`;
`reset! (:rpc\|swap! (:rpc` → one line, inside `settle!`; the
`src/cljc/dao/jing/` require grep → nothing.

Deltas over r1: +1 test on every host (the interruption deftest; 7 new
executed assertions on clj, 1 on cljs/cljd) and the two files' edits
above. Nothing else changed: the seven planned exits, test 9's
stored-state assertions, the cursor mint order and docstring, S5's
correlated error and non-ok retirement, the six `network-*` tests' six
assertion sets, the six gated requires, the `rpc.ws` alias, both prose
edits, and all four design documents are byte-identical to r1.
