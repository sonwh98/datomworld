Completed-GMT: 2026-10-07 18:35:00 GMT
Completed-Local: 2026-10-08 01:35:00 Asia/Ho_Chi_Minh
Coding-Agent: codex
Role: Reviewer

# Adversarial review: Track B Slice S3a-2

Verdict: **ACCEPT**

## Findings

No blocking defect or invariant violation found in the reviewed S3a-2 implementation.

### Board boundary and delegation

`src/cljc/yin/vm/linker/head/board.cljc` is the specified thin domain composition. It requires only `dao.stream` and `dao.stream.remote-channel`; it builds the `/head` spec, the board name, one read-only `#{:reader}` exposure, and delegates server/dial lifecycle operations. The loopback gate remains at the board boundary as specified. I found no `:ws/` keys or transport imports in the board namespace, nor in `yin.vm.linker.head.cljc` or `yin.repl.dht.cljc`.

`board-profile` merges caller overrides over `remote-channel/production-bounds`; `serve`, `serve-step`, `stop!`, `dial`, `dial-step`, `handle`, and `close!` delegate as expected.

### Follower `:polled` / `:answered`

`read-source` marks answers only for cursor `ok`, next `ok`, and next `gap`. A `blocked` or retryable result preserves the answer state. `poll` updates `:polled` for each actual poll and updates `:answered` only when `answered?` is true. New principals and fresh attachments initialize/clear both fields. `heads` reports `:polled`; follower code does not inspect any `:dao.stream.remote/*` key.

### D1: resolve timeout for a connection that never opens

The added `:since` timeout in `remote-channel/dial-step` is sound for the stated stepped-clock contract. It covers the case where a connecting WebSocket refuses every send as full, leaving no outstanding link request for the link deadline machinery to expire. It uses the same `give-up-after` policy and emits the same `channel-gone` loss; nil remains non-expiring. The timeout is checked after the normal resolve step, and an already attached dial is unaffected. The added test checks just-before and at-deadline behavior.

The timer begins at the first dial step rather than at `dial` composition. That is consistent with driver-paced lifecycle: no progress is promised until the driver steps the dial. The shell composes then steps on subsequent node ticks, so the practical delay is bounded by a tick beyond the configured interval.

### D2: one shutdown tick in `yin.repl.dht/close!`

This is a documented deviation from the spec’s suggestion that the shell exit loop observe `:stopped`. It does **not** bypass the required release work: the first stopping tick executes the bounded answering pass, closes sessions and pending connections, and requests listener unbind. The close path does not retain/return the updated server state, so it cannot report whether host confirmation arrived; however, it is process-exit cleanup and the server’s close callback is not needed for resource release. The focused DHT test checks that the listener and all connections are closed. I consider the deviation acceptable for this slice, with confirmation-loop work left for the exit-path slice as the engineer reports.

### Test coverage and verification

The requested tests cover board composition and refusal paths, stop and reattach, blackholed and never-open connections, follower answer timestamps, lifecycle loss, and DHT cleanup. `git diff --check` reports no whitespace errors. I did not rerun the test suites; this review relies on source inspection and the engineer’s reported focused test results.

## Non-blocking note

The completion report notes broader focused-suite failures in Node subprocess startup due to an environment/dependency issue, and that `cljstyle` was not run. These do not identify a defect in the S3a-2 changes, but those checks remain for the orchestrator’s normal integration lane.
