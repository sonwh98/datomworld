I’ll read the round-2 review brief from that file and check the requested corrections.

Completed-GMT: 2026-10-11 06:29:42 GMT
Completed-Local: 2026-10-11 13:29:42 Asia/Bangkok

No remaining findings. Reviewed `71eab563` against `695c3ec0`; no files edited or tests run.

1. **Canonical-vector precedence — fixed.** Frozen §7 removes the retired projection claims. UCF §7.3.2 explicitly gives the register-shaped `"v4"` specification precedence and scopes the old stack-vector procedure as historical.

2. **Root validation — fixed.** Carrier step 7 invokes `frame/task-defect` first for each body, including halted bodies without frames, then validates frames. Validation order and diagnostics are preserved.

3. **Dependency roots — fixed.** Both the carrier census and UCF fixed point include the initial free environment. Adding it to the carrier’s lift walk is also correct: lift and lower must agree on roots. Nonempty tree associations remain required.

4. **Load-path residue — fixed.** Code-as-tuples §7.1, its impact table and the UCF matrix now specify two AST lanes plus direct vector loading. No instruction-vector projection acceptance obligation remains operative.

5. **References and digest spellings — fixed.** Both A7 references now name A2. Frozen §8.4’s derivations references are qualified, and the code-as-tuples examples use BLAKE3. The companion’s only change is the permitted reference correction.

The five-document residue grep found only legitimate occurrences: historical clauses explicitly superseded, amendment records, native stack-kernel fields, instruction operand kinds, the current window-based `:kframe`, and the self-describing-address example followed by mandatory BLAKE3 enforcement.

VERDICT: READY_TO_MERGE
