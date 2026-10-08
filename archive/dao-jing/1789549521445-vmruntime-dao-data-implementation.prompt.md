Created-GMT: 2026-09-16 09:05:21 GMT
Created-Local: 2026-09-16 16:05:21 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 90e326e8-c9e7-4cc5-8cab-b5cc620c3c9a

# Task: Implement `dao.data` (tag/summarize) and wire it into the v2 telemetry stub

Role: VM Runtime

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-16 16:05:21 +07 | Status: active | Rationale: touches both a generic data-safety utility (tied to dao.stream.transit) and yin.vm telemetry — glm-5.3's stated strengths cover both VM runtime and storage/networking work

## Context

`docs/design/dao.data.md` is a fully reviewed, revised design doc (proposed
but not yet implemented — read its `Status:` line, still says proposed;
that's expected, this task implements it). It went through an architect
discovery pass (fable) and a full adversarial review round (codex/gpt-6-astra)
that found real defects, all since fixed in the doc: a false totality claim
about lazy-sequence bounding, a number-portability gap against the live
`dao.stream.transit` wire codec, several unbounded leaves (keyword/symbol
length, stream identity), a stream-schema key confusion, and boundary-case
inconsistencies. The doc as it stands now is the frozen spec for this task —
implement exactly what it says, not an earlier or simplified version of it.

The motivating insight (owner's own observation, recorded in
`collab/1789566611000-architect-state-snapshot-abstraction.prompt.md`): VM
telemetry isn't a special subsystem, it's just an observer of internal state
appending to a `dao.stream` like any other observer. The `dao.data.md`
"Evidence" section (read it) audits every site the original discovery pass
claimed was duplicated and confirms which ones `tag`/`summarize` can actually
replace versus which were overstated. This task wires the ONE most clearly
justified, most precisely scoped site: `src/cljc/yin/vm/telemetry.cljc`'s
own `type-tag`. Per the Evidence section: "Live classifier duplication —
replaceable... `tag` can take over this responsibility, with a few
deliberate vocabulary changes: `:host-fn` → `:fn`, ad hoc handle detection →
`:stream`, and sets (which v1's `type-tag` mis-tags) → `:set`."

Read `src/cljc/yin/vm/telemetry.cljc` in full before touching anything —
its own docstring already explains this precisely: it's a deliberate stub,
and its docstring literally lists "ordering the three surface protocols
before the `map?` branch" among what's deferred to "the real emit path."
`dao.data/tag` already does exactly that ordering (`stream/descriptor?`
before `map?`/`sequential?`). Swapping in `dao.data/tag` here isn't a
refactor for its own sake — it completes precisely what this stub's own
docstring names as deferred, still without building the real emit path
(`enabled?`/`emit-snapshot` stay stubs; that's separate work for the D2
telemetry plan, explicitly out of scope here).

Also read `src/cljc/yin/vm/ffi.cljc` around line 209 — the one live call
site of `type-tag` (`(mapv type-tag request-args)`, evaluated as an argument
to `emit-snapshot` before the stub's no-op check, so it genuinely runs).

Read `test/yin/vm/ffi_test.cljc` and `test/yin/vm/semantic_ffi_test.cljc`
before editing anything, to check whether either asserts the stub's current
behavior (e.g. that a stream handle tags `:opaque`) in a way your change
would break. If either does, that assertion needs updating to match the new,
correct behavior (a stream handle now tags `:stream`), not preserving the
old one — the old `:opaque`-for-everything-handle-shaped behavior was itself
the thing this stub's docstring called out as deferred/incomplete.

## Task

1. **Create `src/cljc/dao/data.cljc`** implementing `tag` and `summarize`
   exactly per `docs/design/dao.data.md`. Both functions, the full `bounds`
   contract (`{:depth n :items n :chars n}`), every rule in the doc's
   "Rules" section — including the `counted?`-gated `:count` handling, the
   number-portability substitution via `dao.stream.transit/portable-value?`,
   the `:chars`-bounded string/keyword/symbol truncation, and the stream
   node's `(summarize identity bounds)` handling. No reader conditionals.
   Portable across CLJ/CLJS/CLJD — use only core sequence functions,
   `counted?`, `str`, `subs`/`count` on strings, nothing host-specific.

2. **Create `test/dao/data_test.cljc`** (match the existing `.cljc` test
   convention used by `test/dao/jing_test.cljc`/`test/dao/runtime_test.cljc`
   — one shared file across hosts). Cover, at minimum:
   - Every `tag` branch, including ordering (`:stream` before `:map`, a
     stream-shaped value that's also technically map-like still tags
     `:stream`).
   - `summarize`'s `:depth`/`:items`/`:chars` bounds independently, including
     depth `0` (no probe performed on a non-`counted?` sequence at depth 0).
   - `:count` present for `counted?` inputs, absent for non-`counted?` ones
     in BOTH the truncated and the exhausted-during-probe case (the design
     doc is explicit: `:count` is never inferred post-hoc from what a probe
     happened to discover, even when it technically could be).
   - A non-portable number (a ratio, e.g. `1/3`) gets stringified with
     `:truncated?` true; a portable number keeps its literal value.
   - A long string/keyword/symbol gets cut to `:chars` with `:truncated?`
     true; a short one doesn't.
   - `:items`/`:entries` are always vectors, regardless of input collection
     type.
   - A stream handle's `:dao.data/identity` is itself a full summarized
     node (test against a fake/mock value satisfying `stream/descriptor?`
     if a real handle is awkward to construct in a unit test — check
     `test/dao/stream` fixtures for whether one already exists to reuse).

3. **Wire `src/cljc/yin/vm/telemetry.cljc`**: remove its local `type-tag`
   function. Update its docstring — the "what is deferred" paragraph's claim
   about protocol ordering is no longer accurate once this lands; correct it
   precisely, don't just delete it (state what's still actually deferred:
   reading `append!` outcomes, summarising a cursor-ref without descent,
   the actual emit path).

4. **Update `src/cljc/yin/vm/ffi.cljc`** at the `type-tag` call site to
   require and call `dao.data/tag` directly instead. Do not add a
   re-exporting alias in `telemetry.cljc` — call `dao.data/tag` directly
   from `ffi.cljc`, the same reasoning the design doc itself already gives
   for rejecting an `emit` wrapper (a passthrough alias would just rename
   the thing, not add anything).

5. **Do not touch**: `dao.pretty`, `dao.await`/`dao.await`, the handoff
   demo (`datomworld/demo/continuation_handoff.cljc`), any REPL v2 file
   (`yin/repl/*`), `yin/vm/telemetry.cljc` (the deprecated v1 file — do
   not confuse it with the v2 stub this task targets), `yin/repl.cljc` (v1),
   or `yin/vm/telemetry_viewer.cljs`. All of these were explicitly evaluated
   and excluded in `dao.data.md`'s Evidence section — leave them exactly as
   they are.

6. **Verify.** Run the affected test namespaces on the JVM at minimum:
   `test/dao/data_test.cljc`, `test/yin/vm/ffi_test.cljc`,
   `test/yin/vm/semantic_ffi_test.cljc`, and any existing
   `yin.vm.telemetry`-specific test if one exists (search for it — don't
   assume). Also run `clj -M:kondo --lint src/cljc/dao/data.cljc
   src/cljc/yin/vm/telemetry.cljc src/cljc/yin/vm/ffi.cljc
   test/dao/data_test.cljc`. Report exact commands and assertion counts.
   Full-suite CLJD/CLJS verification is a known outstanding item across
   this branch already (blocked on an unrelated pre-existing issue) — note
   whether your specific new/changed namespaces at least *compile* cleanly
   under `clj -M:cljd compile` scoped to `dao.data`/`yin.vm.telemetry`/
   `yin.vm.ffi` if that's feasible without running the full suite; if
   not feasible, say so explicitly rather than skipping silently.

7. Do not stage or commit. Do not touch any file outside the ones named
   above.

## Deliverable

Report back: the exact diff (new files + edits), why each design-doc rule
maps to which line of your implementation (brief, not exhaustive), the exact
verification commands run and their output/assertion counts, confirmation
that steps 5's excluded files are untouched, and any deviation from the
design doc you had to make plus why (there shouldn't be any — `dao.data.md`
is meant to be a complete spec; if you find it's actually ambiguous or wrong
somewhere implementation reveals, say exactly where and what you did instead
rather than silently improvising).
