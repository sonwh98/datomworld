Completed-GMT: 2026-10-15 22:30:42 GMT
Completed-Local: 2026-10-16 05:30:42 +0700
Coding-Agent: codex
Session-ID: 01a141a4-07b7-72e2-afb4-dec4a522a1f3

# Engineer follow-up-1 (uncommitted)

Worktree: `/Users/sto/workspace/datomworld-srvm-followup`, branch `srvm-followup-1`, base `9ab9e792`.
Read the requested prompt, three Architect reports, master architecture, build/test guide, and ledger. No commits or staging. The pre-existing collab inputs/log were left alone; only the final report is added in collab.

## Part 1 — key equality; binder sub-part STOPPED

`src/cljc/yin/vm/ucf/rows.cljc`: key distinctness in `decode*` now uses `cbor/content-key`. The expanded collection-key check also normalizes literal leaves through that same function: otherwise its host `distinct` still refuses signed zeros after the wire check passes. The existing encoded-byte duplicate check remains. Encoder canonicalisation, row shapes, profiles, descriptors and goldens are unchanged.

Throwaway JVM namespace `yin.vm.followup-probe-test` was created, run and deleted. Before the fix, host distinct counts were 1 for `[0.0 -0.0]` and 2 for `[1 1.0]`; content-key counts were 2 for both. Both signed-zero map/set rows emitted successfully and decode refused `:duplicate-key`, at their row ids. Both int/float rows decoded successfully on JVM. An initial probe without an anchored cursor was a fixture error (`:unresolved-cursor-cells`); adding an independently reachable binding anchor isolated the requested key behaviour.

`collection-row-keys-use-content-equality` pins map and set roundtrips for both pairs (12 assertions). It uses `float64-from-bits` for signed zeros and explicit `float64` for 1.0, retaining those distinctions on JavaScript. Source reasoning: CBOR `numeric-content-key` distinguishes numeric kinds and float sign bits on every host; raw JS Numbers cannot preserve integral-float kind, so wrappers are necessary. Dart host equality merges 1/1.0, while content keys keep them apart. Node/Dart were not run (assigned to orchestrator); no cross-host execution is claimed.

**STOPPED 1(b)** under the prompt's design-decision escape. Native closure formats have no binder names: stack `[:closure arity body-pc]`, register `[:closure rd arity body-pc]`; register bodies have only `:locals :registers :start :end`. `semantic/derive.cljc` `addresses` replaces the parameter vector with its count, and the stack emitter retains parameter names only in diagnostics. The native validators cannot distinguish two source binders that have the same arity. `:sym` checks already protect `:define`/`:load-free`, which are not lambda parameter binders. Adding names or a source-provenance admission requirement would be a design/format change; fabricating a binder field on an image would not test the actual native format. No native validator/runtime edits or misleading refusal tests were added. The ledger retains this open item and records why it stopped. Architect direction is required to complete that sub-part without moving frozen descriptors.

## Part 2 — tests only

- `park-beside-a-live-reader-wait`: row lift/lower preserves an explicit park and a reader that has already consumed A and is waiting for B. Resume the park to `"unparked"`, verify the reader remains, append B and run the reader to completion (8 assertions).
- `noise-before-the-correlated-ffi-response`: a transferred sent-FFI waiter remains blocked on an unrelated response, then wakes to `"echo!"` on its correlated response, without sending a request on the receiver's own pair (7 assertions).
- Deleted unused `yin.vm.malformed-rows/malformed-vectors`; reader search before deletion found only its definition. Afterwards the only source/test substring is the unrelated `scan-test/malformed-vectors-throw` test name. Updated the fixture namespace docstring to describe the remaining row fixtures.
- `parity_test.cljc`: removed the redundant corpus alias, consistently using the existing refers (plus `programs`); assertion forms/expected values unchanged. Baseline kondo on the original contents with the original filename reported **46 infos**, rather than the brief's historical 38. Changed files now report zero infos.

## Part 3 — prose only

- UCF §7.11 clause 10 records the Stage E host matrix as run, citing the 2026-10-14/15 orchestrator log: jupiter, four JVM/Node pairs, 196 SIGKILLed lives; separate Node/Dart lane evidence is identified as recorded evidence, not rerun here.
- Linker DHT acceptance points at the surviving semantic scan footprint assertions and records completion's deletion.
- H/R wording says checksums, A says code identity; scan docstring distinguishes linker-side Datalog over the admitted vector from independent evaluator/index peer observation; VM grammar docstring identifies the Universal AST row-slot table.
- Linker missing-tree prose matches `:yin.k/unsatisfied` with `:yin.k/missing {:yin.k/trees ...}`.
- All eight listed citation sites (including both JFR references) retargeted to history or the current projection/migration section, with only comments/docstrings/assertion messages changed. Optimisation and rows-inspector prose now marks deleted predecessors historical.
- Ledger closes keys, successor tests, dead fixture, citations and prose; leaves binder stopped/open; adds stale C4 `[:c4 :py]`/`[:c4 :t]` scanner pins, yang.python seat/muse, regeneration once at the end of I5/I6 with Architect review. Pins untouched.

## Validation and mutations

- Throwaway probe: requested original JVM behaviour reproduced; namespace deleted.
- Before key fix: rows namespace 39 tests / 694 assertions, 2 failures (signed-zero map/set), 0 errors. After fix: 39 / 696 / 0 / 0.
- Three selected new tests: 3 tests / 27 assertions / 0 failures / 0 errors.
- Required focused lane: **181 tests / 2936 assertions / 0 failures / 0 errors**.
- `bb gen:python-antlr` unrestricted: exit 0, generated four Java files and compiled classes under build.
- Kondo on all 16 changed Clojure-family files: **0 errors, 0 warnings, 0 infos**.
- Full JVM quick tier, unrestricted `clojure -M:test -e :slow`: **4019 tests / 249862 assertions / 0 failures / 0 errors**, exit 0. Printed skips: missing `build/ws-project-peer` Dart dialer and `build/yin-repl-peer` Dart client executables; three host-collapsed set cases, one host-collapsed map integer-width case, and the JS-only unsafe integer case are N/A on JVM. Dart builds were not run, per brief.
- `git diff --check`: clean. No src/yang, test/yang, scanner_goldens, test/resources, descriptor or pinned golden edits. No Node/Dart lane, commits or staging.

Mutation runs each restored the original working file in a finally block. Each ran the three selected tests:

| mutation | result |
|---|---|
| revert wire key normalization to host distinct | 2 failures, signed-zero map/set |
| revert expanded literal key normalization to host values | 2 failures, signed-zero map/set |
| drop restored parked map | park successor errors when its missing continuation is resumed |
| route an FFI response to the first waiter without comparing ids | noise successor errors with response correlation refusal |

The int/float assertions do not kill the old host-equality mutant on JVM because JVM already distinguishes those kinds; Dart execution must verify that regression. JVM can hide the prior identical-NaN row-order issue; these new tests concern key equality, not a new NaN-class fix. Mutation logs and lane logs are under `/tmp/followup-*` in this session.

Tracked diff: 22 files, +135/-77. The changes remain three logical review slices: (1) rows codec and its key test; (2) successor/fixture/parity tests; (3) documentation and ledger (including prose-only test/source edits). No split commits were made, per instruction.

BLOCKED
