Completed-GMT: 2026-10-27 21:04:09 GMT
Completed-Local: 2026-10-28 04:04:09 Asia/Ho_Chi_Minh

- **Malformed Pong counts | Resolved |** [harness.py](/Users/sto/workspace/datomworld-jolt-j6a/tools/jolt-autobahn/harness.py:124) rejects non-integer, boolean and negative counts. The fixtures cover null, false, `-1` and `"1"` in both roles. | **Remaining action:** none for the original finding.
- **Stale extension wording | Resolved |** All 216 N/A reasons in both `cases.json` and `triage.md` now say “ignore offers, negotiate none”; neither retains “reject extension offers.” | **Remaining action:** none.
- **P2: statistics collection flag | Open |** The evaluator accepts an otherwise valid 7.1.6 report with `createStats: false` or a missing flag and an empty `rxFrameStats`. The [pinned upstream source](https://raw.githubusercontent.com/crossbario/autobahn-testsuite/6ed6f439dc7ed0d7432fe2cf7481b110905ecc5c/autobahntestsuite/autobahntestsuite/fuzzing.py) records that flag and increments frame counts only when it is true. Although collection defaults to true in the pinned suite, such incomplete evidence would make the Pong check pass without demonstrating that statistics were collected. | **Remaining action:** require `createStats is True` when applying the opcode assertion and add missing/false flag fixtures.

Both recomputed hashes match `pin.json`: `cases.json` is `02dc657b48291027a7004ed19101b470e3e606a5099884de334d512a2fca98b7`; `exceptions.json` is `d17d8fe04b74ecd2879ce6b7316017e90020ef70a89100e8d83fd5e6e8bd525d`. I did not rerun the orchestrator’s reported checks.

**Ready to commit: No**, pending the statistics collection check.