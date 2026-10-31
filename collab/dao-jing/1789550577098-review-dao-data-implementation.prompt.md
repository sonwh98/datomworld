Created-GMT: 2026-09-16 09:22:57 GMT
Created-Local: 2026-09-16 16:22:57 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: pending (provider-generated)

# Task: Independent review of the `dao.data` implementation and its two consumer wirings

Role: Adversarial Review

Implementers:
- Model: gemini-3.1-pro-high | Assigned: 2026-09-16 16:22:57 +07 | Status: active | Rationale: cross-family review (implementers were glm-5.3 and claude-sonnet-5; GPT-family review capacity is low tonight, using Gemini instead)

## Context

`docs/design/dao.data.md` is a fully reviewed design doc (an architect
discovery pass, then a full adversarial review round with real defects found
and fixed, then a second orchestrator-driven revision pass closing every
remaining finding). Read it in full — it is the frozen spec this
implementation claims to match exactly.

Two independent, concurrently-run implementation units produced the current
uncommitted working-tree diff:

1. `src/cljc/dao/data.cljc` (new) + `test/dao/data_test.cljc` (new) — the
   `tag`/`summarize` implementation itself, plus wiring it into
   `src/cljc/yin/vm/telemetry.cljc` (removed the stub's own `type-tag`)
   and `src/cljc/yin/vm/ffi.cljc` (now calls `dao.data/tag` directly).
2. `src/cljc/yin/repl/driver.cljc`, `serve.cljc`, `connect.cljc` — bounds
   previously-unbounded `pr-str` calls on arbitrary internal state using
   `dao.data/summarize`, per `dao.data.md`'s own Evidence section citing
   these as genuine live consumers.

Both units were verified locally by the orchestrator (not just trusted from
delegate reports): `clj -M:kondo --lint` clean on all seven touched/new
files; `clj -M:test -n dao.data-test -n yin.vm.ffi-test -n
yin.vm.semantic-ffi-test` → 38 tests, 202 assertions, 0 failures; `clj
-M:test -n yin.repl.driver-test -n yin.repl.serve-test -n
yin.repl.connect-test` → 51 tests, 244 assertions, 0 failures, run AFTER
`dao.data.cljc` existed (confirming the two units combine correctly). CLJD
compiles scoped to `dao.data`, `yin.vm.telemetry`, `yin.vm.ffi`
individually (full-suite CLJD remains blocked by a known, pre-existing,
unrelated bench-file issue).

One real design/implementation contradiction was found by the first unit's
own implementer and already resolved by the orchestrator, not silently
accepted: `dao.data.md`'s stream rule originally claimed the `:opaque`
fallback defends against a descriptor implementation that's "malformed or
throwing." The implementation only defends the malformed case (a non-`:ok`
outcome) — defending the throwing case would need a host-specific `catch`
clause (confirmed against this project's own precedent,
`dao/stream/ws.cljc:89`, which needs a three-way reader conditional for
exactly this), directly conflicting with `dao.data`'s own
no-reader-conditionals constraint. The orchestrator resolved this by editing
the doc to drop the "or throwing" claim and state the limitation honestly,
keeping the no-reader-conditionals rule as the harder constraint (matching
how the doc already treats an adversarial/non-terminating lazy sequence
elsewhere — caller's responsibility to guard against, not this namespace's).
Verify this resolution is sound, not just accepted on the orchestrator's say-so.

## Task

This is architectural/implementation review, read-only, of the current
uncommitted working-tree diff. Read the actual diff yourself
(`git diff src/cljc/dao/data.cljc test/dao/data_test.cljc
src/cljc/yin/vm/telemetry.cljc src/cljc/yin/vm/ffi.cljc
src/cljc/yin/repl/driver.cljc src/cljc/yin/repl/serve.cljc
src/cljc/yin/repl/connect.cljc` — note `dao/data.cljc` and
`test/dao/data_test.cljc` are new/untracked files, use `git diff --no-index
/dev/null <file>` or just read them directly since `git diff` alone won't
show untracked file contents).

Evaluate:

1. **Fidelity to `dao.data.md`.** Does `src/cljc/dao/data.cljc` implement
   every rule in the doc correctly? Check each rule against the actual code
   — the `counted?` gate and its "never inferred post-hoc" requirement, the
   `items`+1 probe discipline (and that it genuinely never probes more than
   that, and never probes at all at depth 0 for a non-counted sequence), the
   number-portability substitution via the real `dao.stream.transit`
   predicate, the `:chars` truncation shared across string/keyword/symbol,
   the stream node's identity-vs-descriptor distinction and its own
   recursive `summarize` call, `:items`/`:entries` always being vectors, and
   `:count` conditional presence. Cite file:line for anything wrong.

2. **`test/dao/data_test.cljc` coverage.** Does it actually exercise the
   subtle cases the design doc calls out as easy to get wrong — a chunked
   vs. non-chunked lazy source for the probe-count claim, a fully-realized
   `LazySeq` still being non-`counted?`, the exhausted-during-probe case
   specifically NOT reporting `:count`, both portable and non-portable
   numbers, both short and long strings/keywords/symbols, a conforming and a
   non-conforming stream descriptor? Are there gaps?

3. **The telemetry.cljc/ffi.cljc wiring.** Does removing the stub's own
   `type-tag` and calling `dao.data/tag` directly actually preserve or
   correctly change behavior? The v2 stub's own docstring (read it) says
   handles were deliberately all tagged `:opaque` because "distinguishing
   surfaces... belongs to the real emit path and not to a stub" — the new
   code now tags a stream handle `:stream` via `dao.data/tag`. Is that a
   correctness improvement (matching what the docstring called deferred) or
   does it change stub behavior in a way that could surprise a caller
   depending on the old `:opaque`-for-everything-handle-shaped behavior?
   Check `src/cljc/yin/vm/ffi.cljc` around the `:arg-shape` usage this
   feeds into for any downstream assumption about the old tag vocabulary.

4. **The REPL v2 print-site wiring.** For each of the ~13 converted
   `pr-str` sites (`driver.cljc`, `serve.cljc`, `connect.cljc`) and the 4
   sites deliberately left unconverted, independently verify the
   left-unconverted reasoning holds (re-read the actual current code, don't
   trust the delegate's stated reasoning) — especially the `serve.cljc`
   "unknown endpoint event kind" site, which the delegate claims is
   unreachable with a non-nil value based on tracing its only caller; verify
   that trace yourself. Is the shared `diagnostic-bounds` constant
   (`{:depth 3 :items 8 :chars 200}`) a reasonable choice for operator
   diagnostic text, or too tight/loose for any specific site?

5. **The `:opaque`-for-throwing-descriptor resolution.** Was dropping the
   "or throwing" claim (rather than, say, allowing a reader conditional
   here as a narrow, justified exception) the right call? Argue the
   alternative if you disagree.

6. **Anything else** — reuse of existing patterns vs. reinvention,
   cross-host portability risk beyond what's already been checked, any
   place the two independently-developed diffs interact in a way neither
   implementer could have seen alone.

Do not edit any file. Do not run `bb test:cljd` (reserve that lane). You may
re-run the exact `clj -M:kondo`/`clj -M:test` commands above yourself to
independently confirm, or trust the orchestrator's reported output above and
spend your budget on static review instead — your choice, state which you
did.

## Deliverable

A findings list, most severe first: what's wrong (or confirmed correct, to
close out a question above), file:line evidence, and severity (blocking /
real gap / minor-nonblocking / already correct). End with an overall
verdict: ready to proceed toward Architect sign-off as-is, or does something
need fixing first — and if so, exactly what.
