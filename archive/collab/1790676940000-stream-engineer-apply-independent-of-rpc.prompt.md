Created-GMT: 2026-09-29 10:15:40 GMT
Created-Local: 2026-09-29 17:15:40 +07 (+0700)
Coding-Agent: claude
Session-ID: e2ea0334-6c5f-4339-bd39-f3b0c39625b8
# Task: Make dao.stream.apply independent of rpc — one breaking commit per the Architect ruling

Role: DaoStream and Distributed Protocol Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-29 17:15:40 +07 (+0700) | Status: active | Rationale: OWNER selected "Dispatch now (Recommended)"

Implement in /Users/sto/workspace/datomworld (master 643b1ba6). IMPLEMENTATION: edits authorized in the named files
only. Do not stage or commit. docs/orchestrator-log.md is orchestrator bookkeeping (modified, uncommitted): do not touch.

OWNER INVARIANT (verbatim): "dao.stream.apply needs to be independent of the concept of rpc because it can use a
framebuffer".

GOVERNING RULING (authoritative; read in full; implement its ordered migration plan 1-5 exactly):
collab/1790675432000-architect-apply-independent-of-rpc.gpt-6-sol.findings.md
It supersedes the apply-qualified reason words of collab/1790575143000-architect-one-envelope-ruling.gpt-6-sol.findings.md.

Summary (the ruling wins on any difference):
1. rpc completion/terminal reasons become :dao.stream.rpc/{not-found,detached,ended,no-surface,oversize,transport-error};
   translation at the rpc boundary (remote not-found/channel-gone/no-surface/oversize -> not-found/detached/no-surface/
   oversize; stream end -> ended; unclassified -> transport-error); :dao.stream/gap stays distinct; only detached
   rebinds; :dao.stream.remote/* untouched.
2. rpc/request! on an unresolved standard-anchor response cursor returns :dao.stream.rpc/cursor-pending with the
   IDENTICAL state (no id, no allocation, no append); same gate before retrying any :unsent envelope; resolution via
   rpc/poll! (retryable mint stays pending; terminal mint completes outstanding work via the existing terminal path).
   Map the new outcome in yin.repl.adapter; remove the driver's independent send-safety guard (dce6282c) while keeping
   queue/cadence behaviour, preferably reading pending state through an rpc predicate. The driver race regression test
   (driver_test.cljc ~210) must keep passing (adapt it to the new mechanism without weakening it).
3. VM FFI loss error code :dao.stream.apply/ended -> :yin.vm.ffi/response-lost, keeping :yin.vm.ffi/loss
   :dao.stream/end|:dao.stream/gap; update responder.cljc documentation.
4. apply.cljc: remove "transport-neutral" (~line 2) and the RPC-client comparison in correlation-id? (~24-26); state the
   id is any non-nil opaque value whose allocation policy belongs to its user. Keep all of apply's own medium-neutral
   words (incl. gap) and serve-once! unchanged in behaviour.
5. Docs: docs/design/dao.stream.md (~923) boundary text; stale apply-qualified examples in
   docs/design/dao.jing.remote.implementation-plan.md (~749).

Allowed files (from the ruling): src/cljc/dao/stream/{apply,rpc}.cljc; src/cljc/yin/repl/{adapter,driver,connect}.cljc
(+ serve.cljc description text only if needed); src/cljc/yin/vm/ffi.cljc; src/cljc/yin/vm/ffi/remote_serve/responder.cljc;
tests test/dao/stream/{rpc,apply}_test.cljc, test/yin/repl/{adapter,driver,connect}_test.cljc,
test/yin/repl/serve_connect_wire_test.clj, test/yin/vm/ffi_test.cljc, test/yin/vm/ffi/remote_serve/responder_test.cljc;
the two docs above. Also allowed: any OTHER test file that asserts one of the renamed words (grep for them; list each
in the report). Anything else: STOP and report.

Acceptance (ruling section 5; each must fail if broken; prove key ones by temporary mutation, revert, grep):
- A test asserts dao.stream.apply's namespace text and docstrings contain no rpc/transport vocabulary and that apply has
  no rpc dependency.
- Apply request/response/serve-once! work over a plain local (framebuffer-like) medium with no rpc namespace in scope;
  opaque non-numeric ids remain valid.
- Each remote reason maps to its distinct rpc reason; gap distinct; only detached rebinds.
- request! on an unresolved anchor returns cursor-pending without allocating/appending/changing state; retryable mint
  stays pending; successful mint permits exactly one request; terminal mint prevents sending; the REPL race regression
  still holds.
- VM response end and gap raise :yin.vm.ffi/response-lost with distinct loss facts; remote serving still transports the
  original apply request and correlated response.
- After the change, grep -rn ':dao.stream.apply/\(not-found\|detached\|ended\|no-surface\|oversize\|transport-error\)'
  src test docs finds nothing (report the command output).

Portable CLJC: on CLJD #?(:clj ...) is NOT excluded — use #?(:cljd nil :clj ...) with :cljd first; no cross-ns
#'private access.

Verify and report exactly: kondo on changed files; cljstyle check (say if blocked); focused JVM over dao.stream.apply-test,
dao.stream.rpc-test, dao.stream.observe-test (if present), yin.repl.adapter-test, yin.repl.driver-test,
yin.repl.connect-test, yin.repl.serve-connect-wire-test, yin.repl.serve-test, yin.repl.main-test, yin.vm.ffi-test,
yin.vm.ffi.remote-serve.responder-test, yin.vm.ffi.remote-serve-test; full clj -M:test; bb test:cljs. Not bb test:cljd.
Note: if you change the driver, build/yin-repl-peer is stale for cross-host tests — say so; the orchestrator rebuilds it.

Write the report to collab/1790676940000-stream-engineer-apply-independent-of-rpc.claude-opus-5-5.report.md and give it as your
final response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: e2ea0334-6c5f-4339-bd39-f3b0c39625b8
Report changed files, every renamed word and where, test outcomes, acceptance -> tests, modified existing tests with
reasons, concerns, incomplete work.
