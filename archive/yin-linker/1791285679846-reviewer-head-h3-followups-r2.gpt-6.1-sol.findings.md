Completed-GMT: 2026-10-06 11:23:05 GMT
Completed-Local: 2026-10-06 18:23:05 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a1103a-2d0e-7f82-b9a3-8f50d00edd21

Both P3s are resolved. No actionable findings.

The prefix scan is linear: constant-size character slices, one final substring, and one linear search for remaining marks. Focused JVM probes took **41 ms** for the near-limit BOM-only file and **19 ms** for the large prefix followed by a valid record.

Non-leading marks now receive the explicit refusal before EDN parsing. Mixed/trailing marks refused; overlong and surrogate encodings still received the UTF-8 refusal. U+FFFE is distinct and did not bypass record validation. Size and existing refusal precedence remain intact.

The timing tests are non-vacuous because they also assert the expected parsing results. Their wall-clock deadline can false-fail under a sufficiently long scheduling pause, but the measured margin is substantial.

No repository files edited; no new P0–P2 findings.

Ready to commit once the Node and Dart lanes pass.
