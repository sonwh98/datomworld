Completed-GMT: 2026-09-10 04:23:44 GMT
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574
Role: Routine Review (Phase 2) | Model: gpt-6-astra

I’ll review the scoped diff against §5, focusing on call cleanup, server refusal handling, unchanged network assertions, and the documented establishment limit. I won’t rerun the verified lanes.

The seven planned `call!` exits use `settle!`, and test 9 checks the stored state after each refusal. There is also an unhandled exit: interruption during `Thread/sleep` leaves the stored RPC state behind the request already sent, allowing the next call to reuse its id. I’m checking the remaining shutdown and test-cleanup paths before finishing.

**One blocking defect remains; not ready to commit.**

1. **P1 — interruption bypasses `settle!` and permits request-id reuse.** In [remote.cljc:540](/Users/sto/workspace/datomworld/src/cljc/dao/jing/remote.cljc:540), `Thread/sleep` can throw `InterruptedException`. The request has already been appended, but the updated allocator, cursor and outstanding table exist only in the loop’s local state. The stored `:rpc` remains unchanged.

   Consequently, interrupting a call and then reusing the client can allocate the same id again. The interrupted call’s late response can satisfy the new call, returning the wrong result.

   Retire the interrupted call from the **latest** state and store it through `settle!` before propagating interruption. Add a deterministic interrupted-call/reuse test that proves the allocator advances and the old response cannot satisfy the next call. All seven planned exits are covered; interruption is the missing eighth.

2. **Nonblocking improvement — test 6’s accepted-socket cleanup races publication.** In [remote_test.cljc:520](/Users/sto/workspace/datomworld/test/dao/jing/remote_test.cljc:520), cleanup reads `@accepted` before closing the listener. The accepter can return a socket but publish it after that read, leaving the test-owned socket unclosed.

   Coordinate acceptance and teardown so a socket arriving during cleanup is also closed. The test otherwise correctly asserts only the deadline failure, without an EOF guarantee.

The remaining scoped checks pass:

- Test 9 checks stored outboxes and outstanding entries after every refusal, equal error data excluding `:request-id`, and advancing ids.
- The traffic cursor is minted before `attach!`; the docstring explains why.
- S5 substitutes a correlated portable error and closes on non-ok append. The second-operation assertion uses the same attachment.
- None of the six existing `network-*` tests’ assertions changed; edits are confined to fixture construction, URLs and teardown.
- The six gated requires, `rpc.ws` alias and stigmergy prose edits are justified.
- The design edits describe the implemented migration and preserve the deferred establishment limitation.
