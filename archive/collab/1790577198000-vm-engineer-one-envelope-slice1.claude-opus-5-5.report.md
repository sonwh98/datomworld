Completed-Local: 2026-09-28 15:50 +0700
Model: claude-opus-5-5 | Session-ID: ae26f3c6-a6a2-41ad-839e-3f5c615718d8

# One-envelope slice 1: rpc adopts apply envelopes (BREAKING). Report

Status: done. All lanes green. Nothing is staged or committed.

## Changes

### src/cljc/dao/stream/rpc.cljc
- ns docstring (:1-25): the wire values are now `dao.stream.apply` envelopes. Events, diagnostics, and completions stay rpc-local with `:dao.stream.rpc/*` keys. Safe-id and outstanding-cap policy are documented as client policy. Requires `[dao.stream.apply :as apply2]`, following the repo's alias convention.
- `default-max-outstanding` = 64 (:49): the documented finite default.
- The request/answer vocabulary (:81-105) is now thin aliases: `request-value`, `request-value?`, `request-id/op/args`, `success-answer`, `error-answer`, `answer-id/ok/error` alias `apply2/request`, `request?`, and so on. The public names yin.repl calls keep working.
  - `answer?` (:96) = `apply2/response?` (which validates the error body) plus rpc `safe-id?`.
  - `answer-ok?` checks `apply2/ok-key`.
- `client-state` (:~120-150): the 4-arity takes opts `{:max-outstanding n}`, which must be a positive integer.
  - A non-positive value throws `ex-info`.
  - The limit is stored at `:max-outstanding`.
- `request!` (:~282-310): new branch after the invalid-request check. When `(count :outstanding)` is at or above `:max-outstanding`, it returns `:dao.stream.rpc/backpressure` with the state unchanged: no id allocated, no unsent request, no append.
  - A retained unsent retry is checked before this branch, so it still proceeds.
- `answer-diagnostic` (:342): a valid apply response whose id is not `safe-id?` gets diagnostic `:dao.stream.rpc/unsafe-response-id`. Anything else gets `:dao.stream.rpc/malformed-response`, including an invalid error body, which is rejected.
  - Used by `normalize-event` (:359) and `handle-event` (:410).
  - The existing advance-before-decode rule consumes each diagnostic exactly once.
- `transport-error-reason` (:~430-443): adds remote `no-surface` → `:dao.stream.apply/no-surface` and remote `oversize` → `:dao.stream.apply/oversize`.
  - Both are terminal, because `rebind` still clears only `/detached`.
  - `not-found`, `ended`, and generic `transport-error` are unchanged.
- Unchanged: the state machine, event names, completion API and keys, ID allocator (random-safe-id, retry bound, safe-id?), cursor minting, and rebind.

### Wire consumers
- `src/cljc/yin/repl/adapter.cljc:117`: `request-outcome` maps `:dao.stream.rpc/backpressure` → `:yin.repl.adapter/backpressure`.
  - Before this change, backpressure fell through to `:yin.repl.adapter/transport-result`.
  - The driver's default branch publishes it as "request not sent: backpressure".
  - `completion-event` (:169) needed no change: it reads rpc-local completion keys and uses the aliased accessors.
- `src/cljc/yin/repl/serve.cljc:520,524`: the malformed-request path now reads the id through `rpc/request-id`, i.e. the apply id key, instead of the literal `:dao.stream.rpc/id`.
  - `evaluate` and `request-value?` need no edits: they go through the aliases, and received requests are now validated by `apply2/request?`.
- `yin.repl.connect` (:422, :516): no change. It builds and rebinds the client with the default cap.
- `yin.repl.driver` (:383): no change. It reads the rpc-local result key `:dao.stream.rpc/id`.
- `dao.stream.observe`: no change. It only names `rpc/poll!` and `apply/serve-once!` in a docstring (:28), with no wire contract.

### Tests
- `test/dao/stream/rpc_test.cljc`: the existing 13 tests pass unchanged through the aliases. New pins:
  - `wire-values-are-apply-envelopes-preserved-whole` (:304): the value emitted by `request!` passes `apply2/request?` and equals `apply2/request`. The answer accepted by `poll!` passes `apply2/response?`, and the whole envelope, with an extra `:trace/hop` key, is kept verbatim in both the result and the completion.
  - `an-invalid-error-body-is-rejected-and-leaves-the-request-outstanding` (:323): an unqualified error code gives a `malformed-response` diagnostic. The request stays outstanding and no completion is produced.
  - `an-unsafe-reply-id-is-a-diagnostic-consumed-once` (:341): a string-id apply response gives an `unsafe-response-id` diagnostic. The request stays outstanding and the next poll is idle.
  - `at-the-outstanding-limit-request-answers-backpressure` (:359): with `{:max-outstanding 1}`, the second request returns backpressure.
    - The returned state is `identical?` to the input: no id, no unsent request, the prior outstanding entry untouched.
    - The request stream holds only `:op/a`.
    - The default cap is applied.
    - `{:max-outstanding 0}` throws.
  - `no-surface-and-oversize-keep-their-own-terminal-words` (:388): no-surface and oversize map to their own words. `rebind` refuses no-surface, oversize, and not-found; detached alone rebinds.
- `test/yin/repl/serve_test.cljc`: error-code assertions now read `:dao.stream.apply/code`, and the malformed-request fixture uses `:dao.stream.apply/id`.
- `test/yin/repl/adapter_test.cljc`: the emitted request is compared against `apply2/request`.
- The embed, driver, main, and connect tests go through the aliases and are unchanged.

## Commands and counts (all via mise)

1. Focused JVM: `mise exec -- clj -M:test -n dao.stream.rpc-test -n yin.repl.adapter-test -n yin.repl.serve-test -n yin.repl.connect-test -n yin.repl.driver-test -n yin.repl.embed-test -n dao.stream.observe-test` → **Ran 77 tests, 386 assertions, 0 failures, 0 errors.**
2. Full JVM, first run: `mise exec -- clj -M:test` → 2287 tests, 183331 assertions, **3 failures** in yin.repl.main-test. The Node lane that followed failed `a-node-client-attaches-to-a-dart-server`.
   - **Cause of the Dart-peer failures:** `build/yin-repl-peer` is a prebuilt Dart executable (built 10:26 today, before this change). It still spoke the old `:dao.stream.rpc/*` wire vocabulary. This breaking change requires rebuilding it.
   - Fix: `mise exec -- bb build:yin-repl-peer`, which compiled and regenerated the exe. This also confirms rpc.cljc compiles under ClojureDart.
   - The other two failures, `killing-the-connection-is-observable-and-requests-are-lost` (:608) and `reattaching-resumes-the-served-stream-and-the-deposit-medium` (:638), use a JVM peer from the current classpath. They failed once more in a focused main-test rerun right after the rebuild, then passed in the next focused rerun (18 tests, 134 assertions, 0 failures) and in the full rerun below.
   - I am calling these the known yin.repl.main-test intermittent. **Flag:** they failed on two back-to-back runs before going green, so the gate may want to watch them.
3. Full JVM, final: `mise exec -- clj -M:test` → **Ran 2287 tests, 183331 assertions, 0 failures, 0 errors.**
   - One loud SKIP, not caused by this change: `a-dart-dialer-reads-a-jvm-server` (build/ws-project-peer absent).
4. Node lane: `mise exec -- npx shadow-cljs compile test` (359 files, 0 warnings), then `mise exec -- node target/node-tests.js` → **Ran 2193 tests, 49957 assertions, 0 failures, 0 errors.**
   - The rpc, adapter, and serve test namespaces appear as "Testing …" in the output.
5. `mise exec -- cljstyle check` on the 6 touched source and test files: clean.
6. `mise exec -- clj -M:kondo --lint` on the same 6 files: 0 errors, 0 warnings.

Logs: target/slice1-jvm.log (first run), target/slice1-jvm2.log (final), target/slice1-node.log, target/slice1-main-rerun.log.

## Protected list: respected
- No changes to yin.vm.*, dao.stream.apply.cljc, remote.cljc, or remote_pair.cljc.
- The apply predicates were not tightened, and no rpc fields were added to the envelope.

## Note for the orchestrator
Any Dart peer binary built before this commit (`build/yin-repl-peer`) is stale by construction. Rebuild it (`bb build:yin-repl-peer`, or `bb test`, which rebuilds) before gating the cross-host main-test cases.

## git status --short
```
 M docs/orchestrator-log.md            (pre-existing, not mine)
 M src/cljc/dao/stream/rpc.cljc
 M src/cljc/yin/repl/adapter.cljc
 M src/cljc/yin/repl/serve.cljc
 M test/dao/stream/rpc_test.cljc
 M test/yin/repl/adapter_test.cljc
 M test/yin/repl/serve_test.cljc
?? collab/1790577198000-vm-engineer-one-envelope-slice1.claude-opus-5-5.stdout.log
?? collab/1790577198000-vm-engineer-one-envelope-slice1.prompt.md
?? collab/1790577198000-vm-engineer-one-envelope-slice1.claude-opus-5-5.report.md
```
(`build/yin-repl-peer` was regenerated but is not tracked.)
