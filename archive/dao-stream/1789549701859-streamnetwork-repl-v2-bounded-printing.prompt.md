Created-GMT: 2026-09-16 09:08:21 GMT
Created-Local: 2026-09-16 16:08:21 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: ce2acb52-b7c9-4de7-8e57-4429421fef37

# Task: Bound unbounded operator-text printing in the v2 REPL stack using `dao.data`

Role: Stream & Network

Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-16 16:08:21 +07 | Status: active | Rationale: mechanical, well-scoped consumer wiring against an already-frozen API contract; general-purpose strength is the right fit, no need for a heavier model

## Context — read this whole section before touching anything

`docs/design/dao.data.md` is a fully reviewed, revised design doc (an
architect discovery pass, then a full adversarial review round that found
and fixed real defects) specifying a new namespace `dao.data`
(`src/cljc/dao/data.cljc`) exposing `tag` and `summarize`. Read the whole
doc now — it's short, and every rule in it matters to how you use it.

**A concurrent, independent unit is implementing `src/cljc/dao/data.cljc`
itself right now, in this same working tree, targeting entirely different
files than you (`src/cljc/dao/data.cljc`, `src/cljc/yin/vm/telemetry.cljc`,
`src/cljc/yin/vm/ffi.cljc`).** Your file set is fully disjoint from
theirs, so there's no edit conflict. But this means `dao.data.cljc` may or
may not exist on disk yet when you start, and may not exist when you try to
verify. This is expected, not a defect in your work. Write your code exactly
against `docs/design/dao.data.md`'s frozen API — it will not change. If
`(require [dao.data :as data])` fails to resolve or a test run fails
*specifically* because `dao.data.cljc` doesn't exist yet, note that
explicitly in your report as an expected, known blocker (not something to
work around or stub yourself), and report everything else (the diff itself,
your reasoning per site, lint results that don't depend on `dao.data`
resolving) as normal. Do not create, stub, or guess at `dao.data.cljc`
yourself under any circumstances — that is fully owned by the other unit.

The motivating finding, from `dao.data.md`'s own Evidence section: "Unbounded
operator-text printing — live... Confirmed at `yin/repl/driver.cljc:386`,
`serve.cljc:348`, `connect.cljc:432` — genuine consumers, now that the
lazy-sequence, number-portability, and `:chars` gaps above are closed." The
whole point: these files build operator-facing diagnostic text with raw
`pr-str` calls on values whose size/shape isn't controlled — a huge string,
a deeply nested map, a pathological collection — sails straight into printed
output with no bound at all.

## Task

1. Read `src/cljc/yin/repl/driver.cljc`, `src/cljc/yin/repl/serve.cljc`,
   and `src/cljc/yin/repl/connect.cljc` in full. Find every `pr-str` call
   in these three files (there were roughly a dozen across them as of the
   original discovery pass — re-verify the current count and line numbers
   yourself, don't trust a stale list).

2. For each `pr-str` call, decide — and state your reasoning — whether the
   value being printed is genuinely unbounded/arbitrary internal state
   (an event map, a result map, a diagnostic map, raw user/REPL input text,
   anything whose shape or size isn't fixed by a prior validation step in
   the same function) versus already small and effectively bounded by its
   own type/contract (a port number, a URL the same file already validated
   as a string with `subs`/format checks, a small fixed keyword). Convert
   only the genuinely-unbounded ones. As a starting point for your own
   judgment (verify against the real current file, don't just trust this):
   values built from raw REPL input, `event` maps, `result` maps, and
   diagnostic/error maps are likely candidates; already-validated URLs,
   ports, and small status keywords likely aren't.

3. For each site you convert: replace `(pr-str value)` with
   `(pr-str (data/summarize value bounds))`, requiring `[dao.data :as
   data]` in the namespace's `:require` (check each file's existing
   `ns` form for its current require style and match it — these are
   `.cljc` files, watch for existing reader conditionals and don't
   disturb them). Choose one small, explicit, named `bounds` value per
   file (e.g. `(def ^:private diagnostic-bounds {:depth 3 :items 8 :chars
   200})` near the top, or reuse one if a file already has a similar
   local constant) — do not invent a different ad hoc bound per call
   site, and do not use unbounded/huge numbers that would defeat the
   point.

4. Do not touch any site you decide not to convert — leave it exactly as
   it is, and list it in your report with your reasoning for leaving it.

5. Do not touch any file other than `driver.cljc`, `serve.cljc`, and
   `connect.cljc` in `src/cljc/yin/repl/`. Do not touch `dao.data.cljc`,
   `dao.pretty`, `dao.await`, the v1 REPL (`yin/repl.cljc`), or any v1
   telemetry file.

6. Verify what you can independently of `dao.data.cljc` existing: run
   `clj -M:kondo --lint src/cljc/yin/repl/driver.cljc
   src/cljc/yin/repl/serve.cljc src/cljc/yin/repl/connect.cljc` and
   report the result. If `test/yin/repl/` has existing test files for
   these namespaces, identify them and attempt to run them; report the
   outcome either way, including if the run fails purely because
   `dao.data.cljc` isn't present yet — say so plainly rather than treating
   it as a silent pass or a silent failure of your own work.

7. Do not stage or commit. Do not run `bb test:cljd` (the CLJD lane is
   reserved for one process at a time per this branch's working
   agreement, and this doesn't need it for now).

## Deliverable

Report back: the exact diff, a per-file table of every `pr-str` call found,
whether you converted it and why (or why not), the exact verification
commands run and their output, and explicit confirmation of what's still
unverifiable pending the concurrent `dao.data.cljc` unit landing.
