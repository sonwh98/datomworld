Completed-GMT: 2026-09-10 05:10:20 GMT
Completed-Local: 2026-09-10 12:10:20 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 26a798b9-cdc8-4a34-99b8-dbc27cd81708 (resumed)

# dao.jing.remote Phase 2 round 4 — the tenth exit gated; the vacuous pin made honest

Not staged, not committed. This round touched exactly two files:
`src/cljc/dao/jing/remote.cljc` and `test/dao/jing/remote_test.cljc`.

## P1 — timing options validated before anything is submitted

New private `validate-timing-options!` (gated `#?(:cljd nil :clj …)`):
each entry it is given must be a positive integer of milliseconds, else
`ex-info {:option k :value v :options …}` with a message naming the
option. Two gates:

- **`connect-content!`**: first form of the arity-2 body, over the three
  defaulted options — before `content-descriptor`, before any medium,
  before `attach!`. A bad `:connect-timeout-ms` previously threw in the
  establishment arithmetic **after** `attach!` — the ninth exit's door —
  and a bad `:request-timeout-ms`/`:poll-interval-ms` would have been
  stored into the client for `call!` to trip over later.
- **`call!`**: before the lock and before `rpc/request!`, over
  `:request-timeout-ms` and `:poll-interval-ms` read once at entry (the
  loop's deadline let reuses the validated locals). The client is a value
  a caller can `assoc` onto, so the public entry re-validates; the
  requirement — throw **before** the wire — is met by both doors closing.

Both new catches (eighth, ninth) still catch only `InterruptedException`;
they are now safe to, because the argument defects that could pass them
throw at the gate, before submission. `call!`'s and `connect-content!`'s
docstrings name the gate and why.

## P1's proof

`invalid-timing-options-throw-before-the-wire-and-leave-no-trace`, over a
`serve-content!` whose single op records its argument into an atom — the
**wire side is the discriminator**, because the client-side state alone
cannot distinguish "never sent" from "sent and abandoned" (the abandoned
state exists only in the loop's locals either way). Asserts, in order:

- the constructor's own gate (bad `:connect-timeout-ms`, port 1 never
  contacted);
- both broken clients (`:request-timeout-ms "bad"`, `:poll-interval-ms
  -5`) throw the option message;
- allocator unchanged (`:next-id` 0), `:outstanding`/`:completed`/
  `:diagnostics` empty;
- after a 100 ms grace, **`@seen` is still empty** — no request reached
  the handler; the grace makes this the assertion the mutation cannot
  dodge on timing;
- the intact client's next call answers `::served`, exactly one request
  (`[:fresh]`) ever crossed the wire, and the corrected call consumed the
  first id (`:next-id` 1).

**Mutation check, run and reverted** — deleting `call!`'s gate fails six
assertions, and two of them are the defect itself, live: the wire saw
`[:refused :refused]` (both broken calls **sent**), and the second broken
call **returned instead of throwing** — it had re-allocated id 0, the
first abandoned call's response satisfied it, and `settle!` stored the
miscorrelated completion (`:next-id` 1). The eighth exit's failure mode,
reached through the arithmetic door, exactly as you said. Fix restored:
0 failures, twice; stigmergy's five scenarios still green.

## P2 — the wrap is impossible for this var; the comment now says what is and is not tested

I implemented your suggested test-only wrap (`with-redefs` over
`stream/close!`, delegating to the real function, counting invocations)
and it **counted zero while the exit demonstrably ran** (the reason map
was thrown). Probed outside the runner as well, same result. Cause:
`close!` is a `defprotocol` method, and Clojure links protocol calls at
compiled sites **directly to the interface method**, bypassing the var —
so `with-redefs` is invisible to `remote.cljc`'s call site. This is not a
runner wrinkle; it is the language.

Per your pre-authorized alternative, the test keeps its three valid
assertions and the comment now states exactly what they cover — the
reason, the flag, and no-escape-by-throw — and that the handle's
closedness is **statically reviewed, not tested**: the close is the line
directly above the throw in the same straight-line branch, no seam
exists (handle, medium and state are all local until the constructor
returns), and the wrap cannot see it. No claim of coverage it lacks
remains.

## Left for your reconciliation (nothing touched)

N11's wording may now want the tenth exit folded in — my reading is that
it is *covered* by N11's "whatever the caller does next" spirit but is an
entry-gate rather than an exit: the defect was an exit that bypassed
`settle!`, and the fix makes it throw **before the function has any state
to settle**. `call!`'s and `connect-content!`'s docstrings carry the
gate's sentence; the plan and `dao.jing.md` are yours.

## Verification (final tree)

| command | outcome | r3 baseline |
|---|---|---|
| `clojure -M:test` | **1458 tests, 165526 assertions, 0 failures, 0 errors** | 1457 / 165515 |
| `bb test:cljs` | **1360 tests, 35026 assertions, 0 failures, 0 errors**; `Testing dao.jing.remote-test` present (count 1) | 1359 / 35025 |
| `bb test:cljd` | **+1314: All tests passed** | +1313 |
| `clj -M:cljs -m shadow.cljs.devtools.cli compile demo` | **212 files, 0 warnings** | 212 / 0 |
| `clj -M:kondo --lint src/cljc/dao/jing/remote.cljc test/dao/jing/remote_test.cljc` | **0 errors, 0 warnings** | 0 / 0 |
| `clojure -M:test -n dao.jing.remote-test` (×2) | 34 tests, 225 assertions, 0 failures both runs | 33 / 214 |
| `clojure -M:test -n dao.space.stigmergy-test` | 5 tests, 22 assertions, 0 failures | unchanged |

Closure greps: v1 tokens → 0 in all three code files; `reset! (:rpc` →
one line, inside `settle!`; the `src/cljc/dao/jing/` require grep →
nothing.

Deltas over r3: +1 test on every host (the gate's proof; +11 executed
assertions on clj, +1 on cljs/cljd) and the P2 comment correction.
Everything else in the cleared set is byte-identical to r3.
