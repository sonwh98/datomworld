Created-GMT: 2026-11-12 23:35:00 GMT
Created-Local: 2026-11-13 06:35:00 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a1d1ee-523c-7be2-b321-8eb8aae65cb7 (resumed thread — apply captured-id rule if resume fails)
# Task: engineer — PORT P2-4: re-record depth evidence attributed to the final SHA
Role: Engineer (small, precise; the re-review's one pre-merge obligation)
Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-13 06:35 +0700 | Status: active | Rationale: you own the depth harness and the comparison; this closes the attribution gap qwen flagged.

## The obligation

qwen's re-review (APPROVE WITH FINDINGS): `1794562000000-...qwen3.8-max.findings.md`. P2-4: the
155/155 depth comparison was recorded at the Phase-3r-era SHA, **not attributable to the final
port tip `d4e31b74`**. Re-record it.

## Do exactly this

1. At `d4e31b74` in `/Users/sto/workspace/datomworld-r3-orch` (your launch cwd): run the depth
   harness (`clj -M:test -n yang.latte-oracle-test`) so the post-port EDN records its source SHA
   = `d4e31b74`. Fix the harness annotation drift qwen noted (P3-1: hand-annotated headers not
   reproducible from the writer — make `write-kernel-gate-depths!` emit the SHA header itself).
2. Regenerate `test/yang/latte_oracle/depth_comparison.md` from the two EDNs (baseline
   `fbd8da0f` vs post `d4e31b74`), all 155 workloads, ratio column, and an explicit
   `none-exceeds-10x / max-ratio: <value>` footer **computed by the writer, not hand-written**.
3. Commit as a `test(yang.clojure)`/`feat` appropriate commit on `exception-r3-impl`; push.
4. No gates beyond the harness run; no kit/VM files.

End with Completed-GMT/Completed-Local and: **DEPTH RECORDED @ d4e31b74: max-ratio <x>** or
**BLOCKED: <what>**.
