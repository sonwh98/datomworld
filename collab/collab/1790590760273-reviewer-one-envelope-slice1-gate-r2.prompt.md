Created-GMT: 2026-09-28 10:19:20 GMT
Created-Local: 2026-09-28 17:19:20 +0700
Coding-Agent: codex
Session-ID: 01a0e731-6d8e-75b0-a570-44c871381a23 (resume of your round-1 gate)

# Correction round — one-envelope migration slice 1 gate

Your round-1 findings (REQUEST CHANGES) were reconciled as follows.

## P1 (unsafe request ID evaluable) — FIXED

src/cljc/yin/repl/serve.cljc (~:509): the evaluate branch now requires
BOTH the apply request shape and rpc's safe-id policy:

    (and (rpc/request-value? request)
         (rpc/safe-id? (rpc/request-id request)))

An apply-shaped request whose id is not a safe integer falls through
to the existing correlatable-malformed branch (which also requires
safe-id? and therefore fails) and lands in the drop-as-diagnostic
branch: never evaluated, never answered. This restores the pre-diff
enforcement that the old rpc/request-value? carried.

Pinned by test/yin/repl/serve_test.cljc
`a-well-formed-request-with-an-unsafe-id-is-dropped-not-evaluated`:
the request is built with `rpc/request-value` (so it is genuinely
well-formed per apply) with id "opaque-id"; the test asserts no answer
was appended and the "malformed request dropped" diagnostic was
published.

## P3 (pin did not isolate body validation) — FIXED

test/dao/stream/rpc_test.cljc
`an-invalid-error-body-is-rejected-and-leaves-the-request-outstanding`
now has two testing blocks:

1. A VALID apply error body (`apply2/error-response` with a qualified
   code and string message) completes the request with
   :dao.stream.rpc/responded; the completion's response fails
   answer-ok? and carries the apply code/message. This isolates body
   validation: the invalid case below is rejected for its body, not
   its apply keys.
2. The invalid body case (unchanged shape) plus a second
   `rpc/poll!` asserting :dao.stream.rpc/idle -- the malformed answer
   is consumed exactly once.

## Fresh verification (orchestrator-run, post-fix; do not rerun)

- Focused JVM (rpc, serve, adapter, connect, driver, embed, observe):
  78 tests, 393 assertions, 0 failures.
- Full JVM: 2288 tests, 183338 assertions, 0 failures, 0 errors.
- Node lane: 2194 tests, 49964 assertions, 0 failures.
- CLJD lane (clean test/cljd-out): 2156 tests, all passed.
- cljstyle check on serve.cljc, serve_test.cljc, rpc_test.cljc: clean.

You may read anything in the repo and run read-only git commands. Do
not edit files. Do not run test suites.

Confirm whether the corrections resolve your P1 and P3. End with
EXACTLY two lines:
Verdict: READY
Sign-off: GRANTED
or
Verdict: REQUEST CHANGES
Sign-off: DENIED
