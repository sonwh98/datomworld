Completed-GMT: 2026-09-16 09:27:00 GMT (approx)
Coding-Agent: agy
Session-ID: c7f9a7f2-51e5-48f2-9810-a95b17aad342

# Review: `dao.data` implementation and its two consumer wirings

Role: Adversarial Review
Model: gemini-3.1-pro-high

(First turn returned SUCCESS with an empty response — a known AGY headless
quirk per orchestrator.md. Resumed with an explicit instruction to answer
directly; this is that resumed turn's full response.)

## 1. Fidelity to `dao.data.md` — Already correct

`src/cljc/dao/data.cljc` lines 45-105:
- `counted?` gate & `:count`: checked prior to any realization (line 87);
  `:count` attached only when `counted?` is true (line 103), regardless of
  what the probe materialized — matches spec exactly.
- Probe discipline: depth 0 skips element inspection entirely (line 88);
  depth > 0 requests exactly `items + 1` elements to determine truncation
  (line 92) without over-evaluating.
- Number portability: uses `dao.stream.transit/portable-value?` (line 58)
  — safe ranges preserved, non-finite/ratio/out-of-range values stringified
  with `:truncated? true`.
- String/keyword/symbol truncation: shared `char-node` (lines 121-123);
  over-bound keyword/symbol correctly becomes a cut plain string (line 48).
- Stream identity: `stream-node` recurses into `summarize` for
  `:dao.stream/identity` with the same bounds (line 73).
- `:items`/`:entries`: always vectors via `mapv` (lines 97-101), no set
  collapsing or map restructuring.

## 2. `test/dao/data_test.cljc` coverage — Already correct

Non-chunked `counting-seq` verifies the items+1 probe bound (lines 159-173);
fully-realized `LazySeq` confirmed non-`counted?` (line 153);
exhausted-during-probe case confirmed to omit `:count` (line 147); portable
vs. non-portable numbers (lines 119-135); string/keyword/symbol truncation
(lines 96-116); conforming vs. non-conforming stream descriptors (lines
203-224).

## 3. FFI & telemetry wiring — Already correct (confirmed improvement)

Relocating classification into `dao.data/tag` is a genuine correctness
improvement, not just a refactor: the v2 stub previously flagged all stream
descriptors unconditionally as `:opaque` specifically because it deferred
surface-ordering to "the real emit path" (per its own docstring).
`dao.data/tag` already orders `stream/descriptor?` before the map/sequence
branches, so this wiring completes exactly what the stub deferred.

## 4. REPL v2 print-site wiring — Already correct

The `serve.cljc` "unknown endpoint event kind" left-unconverted site was
independently re-traced: `lifecycle-transition` receives `kind` from
`(when (contains? event-kinds kind) kind)` at its only call site, so `kind`
is always either `nil` or a member of a small, fixed keyword set — never
arbitrary. Leaving it unconverted is correct. The three other left-
unconverted sites (`connect.cljc`'s `:dao.stream/outcome`, `:terminal`, and
already-validated `:url`) were independently re-verified as bound to fixed
keyword vocabularies or pre-validated strings. The shared `diagnostic-bounds`
(`{:depth 3 :items 8 :chars 200}`) is well-suited: enough for a useful
operator diagnostic, small enough to guarantee no runaway output.

## 5. `:opaque`-for-throwing-descriptor resolution — Already correct

Allowing the "or throwing" claim would require a reader-conditional-gated
`catch`, directly breaching the namespace's own no-reader-conditionals
constraint. Correcting the doc rather than the code, and treating a broken
third-party descriptor implementation the same way as an adversarial lazy
sequence (caller's responsibility), is the correct resolution.

## Overall verdict

**Ready to proceed toward Architect sign-off as-is.** The implementation
matches the frozen design spec, test coverage is robust against the
documented edge cases, the telemetry wiring is a genuine correctness
improvement (not just duplication removal), and the REPL v2 print-site
wiring — both converted and deliberately-left-unconverted — was
independently re-verified. No gaps or defects found.
