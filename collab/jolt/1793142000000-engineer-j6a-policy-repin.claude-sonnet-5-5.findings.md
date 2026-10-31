Completed-GMT: 2026-10-27 21:20:00 GMT
Completed-Local: 2026-10-28 04:20:00 +07
Coding-Agent: claude
Session-ID: cdbb393f-cb95-4334-8e07-0ae689df1e64

Amendments 1–3 are in the working tree, and the harness unit tests pass. I staged and committed nothing, and I touched no other worktree.

**Field check for 7.1.6.** The pinned report can express the Pong assertion, so I locked it. I confirmed this against upstream `fuzzing.py` at commit 6ed6f439, not a pinned-image report:
- The per-case report always contains `rxFrameStats`, and `createStats` is True.
- The stats are keyed by received opcode, so a Pong is the JSON key `"10"`.
- The 7.1.6 exception requires `remoteCloseCode` 1000 and both behaviors INFORMATIONAL, and a Pong count above zero fails it.
- A missing or non-dict `rxFrameStats` also fails.

**Changed files**
- `tools/jolt-autobahn/cases.json`, `exceptions.json`, `pin.json`: 7.1.6 is now exception-listed with client and server rows (six exception rows in total). The 7.13.x reasons cite the jolt row. Both hashes are re-pinned.
- `tools/jolt-autobahn/harness.py`: exception rows may carry `forbid_rx_opcodes`, and the evaluator checks it.
- `tools/jolt-autobahn/test_harness.py`: new `test_7_1_6_close_race_assertions` covers both roles. It includes a Pong after Close, a wrong close code, a missing close code, a wrong behavior, FAILED, null or absent `rxFrameStats`, and a close timeout, all of which fail. The coverage test now asserts 6 exceptions, 298/3/216, the three exception IDs, and 1,204 executions.
- `tools/jolt-autobahn/triage.md`, `README.md`: counts, the 7.1.6 row, the 7.1.6 paragraph, "ignore offers, negotiate none", and the six-row wording.
- `docs/design/yin.repl-jolt-implementation.md`: the amendment note now says 298/3/216 with the re-pin explained, and the §9.6 handshake gate row is added.
- `docs/design/dao.stream.ws.md`: a jolt row in Host wire codes. It lists the accepted set, the 1002 answer plus exactly one terminal `:ws/closed`, labels it a host-profile choice, and distinguishes it from 4002.

**Tests**
- `python3 -m unittest discover -s tools/jolt-autobahn -v`: 9 tests, all OK.
- `python3 -I tools/jolt-autobahn/harness.py check`: 517 cases, 6 exceptions, 301 required.
- The README's `python3 -I tools/jolt-autobahn/test_harness.py` form fails with `ModuleNotFoundError`, because `-I` drops the script directory from the path. I used the README's `unittest discover` form instead.
- I ran no doc or format check, because I did not find the command. `docs/agents/format.md` exists but I did not read it.

**Concerns**
- I had no Docker approval, so the field check rests on the upstream source, not a run against the pinned image. The first real gate run will confirm it.
- The design doc's §9.5 line listing invalid wire close codes is unchanged, since it is still accurate.
- Amendments 4–6 are not implemented, as instructed.
