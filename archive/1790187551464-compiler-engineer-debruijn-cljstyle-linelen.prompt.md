Created-GMT: 2026-09-23 18:19:11 GMT
Created-Local: 2026-09-24 01:19:11 +0700
Coding-Agent: claude
Session-ID: 5c8b1433-f39f-432e-a83a-2f34743141b8

# Task: Fix cljstyle and Line-Length Violations in debruijn-type-preservation Worktree

Role: Yang Compiler Engineer

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-24 01:19:11 +0700 | Status: active | Rationale: Fix pure formatting/line-length violations; no logic change

## Context

The `debruijn-type-preservation` branch worktree lives at:

  /Users/sto/workspace/worktree-debruijn-type-preservation

All implementation work for type-preserving de Bruijn projection is
complete and all three host test suites pass. Two classes of mechanical
formatting violation remain before the branch is commit-ready.

**IMPORTANT:** The Orchestrator made one unauthorized partial edit to the
worktree (reformatted `has-integral-number?` map branch via Python script).
That edit is already in the file at the time you read it. Your job is to
verify it is correct per cljstyle, and to complete the remaining fixes
below — you own the full set of formatting fixes from this point forward.

## Violations to Fix

### 1. cljstyle: `has-integral-number?` map branch — debruijn.cljc

File: `src/cljc/yin/vm/debruijn.cljc`

The `#?(:cljs ...)` reader block defines `has-integral-number?`. Its
`(map? v)` cond branch uses an anonymous function. cljstyle requires the
`fn` body on its own line. The Orchestrator's partial fix placed it
as follows — **verify this is what cljstyle actually wants** by running
`cljstyle check src/cljc/yin/vm/debruijn.cljc` and correcting any
remaining issues:

```
       (map? v) (some (fn [[k x]]
                         (or (has-integral-number? k)
                             (has-integral-number? x)))
                       v)
```

If cljstyle still flags this, adjust until `cljstyle check` is clean for
this file. Do not change the logic — only formatting.

### 2. Line length: error message in `datoms->projected` — debruijn.cljc

File: `src/cljc/yin/vm/debruijn.cljc`, in the `#?(:cljs ...)` block
inside `datoms->projected` that throws `:unsupported-value`.

The line containing `"Integral number of unrecoverable class"` is
93 chars (deeply nested). Restructure the `throw`/`ex-info` block so
every added line is <= 80 columns. Preserve the exact error message text
or shorten it if needed to fit — the message is not pinned anywhere.
The `:rule :unsupported-value` keyword and the `:hash`/`:entity` data
fields must be preserved exactly.

### 3. Line length: fingerprint fixture table — debruijn_test.cljc

File: `test/yin/vm/debruijn_test.cljc`,
test `integral-double-records-cross-hosts`.

The four fixture-table rows (lines approximately 1299-1302) are 83-88
chars each. They look like:

```clojure
[[1.0 "3eff0da5d025906b7a668c5b52dbf29fb5df4c6b2ea0a8b1bb733c08e35241cc"]
 [[1.0] "2f9d03004e233a3d9e78eae688350aaaffd1701977097153434e9b5d36b407a2"]
 [{:a 1.0} "411b0a57a72c09a3314fec224a042159523ec7bf3864b6e7d19eb8e269cace58"]
 [#{1.0} "b3ee9e8c72e84771fcd582ec779e46954e59a3351ea0d6eaf385d650716d8394"]]
```

The hex strings are pinned fingerprints and must not change. Reformat
by wrapping each `[value "hex"]` pair so the opening bracket and value
are on one line and the hex string is on the next line, or by pulling
the fixture into a `let` binding with a shorter name. Ensure the
resulting doseq still iterates over `[v jvm-fingerprint]` pairs exactly
as before. Do not change the hex values.

## Non-Negotiable Invariants

- Lines <= 80 columns in all added/modified lines.
- Pure ASCII only.
- Zero kondo errors/warnings (`clj -M:kondo`).
- `cljstyle check` clean on modified files.
- No logic changes — pure formatting.
- Tri-host test suite must still pass after formatting changes.

## Verification Steps (run all)

1. `cljstyle check src/cljc/yin/vm/debruijn.cljc`
2. `cljstyle check test/yin/vm/debruijn_test.cljc`
3. `awk 'length > 80 {print NR": "length" "$0}' src/cljc/yin/vm/debruijn.cljc` — check only ADDED lines (from git diff) exceed nothing
4. `clj -M:kondo --lint src/cljc/yin/vm/debruijn.cljc test/yin/vm/debruijn_test.cljc`
5. `clojure -M:test -n yin.vm.debruijn-test -n yin.vm.pipeline-test` (JVM)

Run from: `/Users/sto/workspace/worktree-debruijn-type-preservation`

## Deliverable

All five checks pass clean. Report the exact cljstyle output before and
after, and the final focused test result.

Begin your final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700 ICT>
Session-ID: 5c8b1433-f39f-432e-a83a-2f34743141b8
