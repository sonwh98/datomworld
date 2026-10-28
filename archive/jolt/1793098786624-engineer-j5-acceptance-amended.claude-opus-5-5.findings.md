# J5 acceptance (amended: known-gaps row 59 inside J5): findings

Engineer: claude-opus-5-5. Branch `jolt-j5`, pushed; head `e4d43e5e`
(the evidence below was taken at `f30e7d72`, plus the one lane commit on top).
Merging is the orchestrator's.

## Commits (on 6ebf53ca)

| sha | change |
|---|---|
| 8c1144dc | fix(jolt): the J3 shell runner's jolt legs run with `--no-state`; stale J4 refusal legs refreshed (separable, a master defect) |
| 272641a4 | test(yin.repl): python_antlr_test's hand-built cst binding registers its primitives (separable, a master defect since 0f948408) |
| 47e391a0 | feat(jolt-parser): the input and nesting budget in the C++ wrapper (row 59) |
| 7b2a6ce0 | feat(jolt): `yang.antlr.cst` lazy load, unavailable-parser answer, budget mapping |
| 63c92cd2 | feat(yin.repl): stage-owned workers; Python on the jolt standard catalog; the banner |
| d555ed14 | test(jolt): `bb probe:jolt-j5`, `bb test:jolt-j5`, the J0c harnesses over the wired parser |
| f30e7d72 | feat(jolt): J5 acceptance: five J5 exclusions retire; spec §7.6/§10.1; row 59 closes |
| e4d43e5e | test(jolt): the J2 lane gives `yin.repl.python-antlr-test` a three-hour step |

## Where each leg ran

Owner directive 2026-10-27 23:5x: local verification stopped mid-rerun;
everything after it ran remotely.

- **macOS arm64 (laptop), before the stop.** The earlier `bb probe:jolt-j5`
  run covered the budget legs, the BELOW comparison against the JVM, the
  injected faults, the abort property and the unavailable legs (37 PASS).
  Its crash-depth child legs failed in the harness only: the lazy load ran
  before any raw binding. After the fix they were verified by hand
  (parens 544/576 → `ERR status -7` on the 536 KiB thread); the driver
  rerun was not repeated on macOS.
- `bb test:jolt-j5`: ALL PASS. The J0c batch (faults, cycles 20000, gc,
  oracle-parity) and the four suites also ran (frontend-catalog 8/42,
  python-antlr 194/194, main 30/288 before the banner fix, yin.repl-test
  260/264).
- The partial rerun after the fixes reached the workers harness and stopped
  there. The fixed reset-stage and close-stage legs had both PASSed by then.
- **Not rerun on macOS after the fixes:** the parity and sanitizer −4 legs
  (now a 600 KB legal source), the shell corpus with `--no-state`, and the
  main-test banner expectation. Their evidence is Linux only.
- **Linux x86_64: shiva (24 cores) and jupiter (48 cores).** Scratch clones
  `~/ci/datomworld-j5` at `f30e7d72`, logs `~/ci/datomworld-j5-logs/`.
  Every leg below ran on both hosts unless marked otherwise.

## Row-59 mechanism (A)

The C++ wrapper `native/jolt-parser/jolt_parser.cpp` (header section "THE
BUDGET") refuses before any ANTLR object exists, at the top of
`jolt_parser_export` and in `jolt_parser_parse`:

- **Input bound.** `N_bytes` = 4 MiB, status −6, message
  `input <measured> <bound>`. `yang.antlr.cst/export-records` mirrors the
  check before the FFI call.
- **Nesting bound.** `N_depth` = 256, status −7, message
  `nesting <measured> <bound>`. The prescan is one flat O(bytes),
  O(1)-space pass with no recursion and no allocation.
  - Each carrier is charged one level at the worst per-level cost: brackets
    `( [ {`; a unary run `- + ~ not` in operand position; `**`; `if` after
    an operand; `lambda`; INDENT.
  - INDENT is counted from leading whitespace (tabs to 8), never inferred
    from size.
  - Strings and comments are skipped; explicit line joins are followed.
  - The carrier table and the derivation are in spec §7.6 and in the wrapper
    header.
- **Derivation.** The shallowest r5 crash bracket on the 512 KiB macOS jolt
  thread is 512 levels (call, unary). 256 leaves a 2x margin, about 1 KiB a
  level at the worst cost.
- **Stack clamp.** `thread_stack_size()` (Apple `pthread_get_stacksize_np`;
  glibc `pthread_getattr_np`) is now production code. `depth_bound()` scales
  256 down when the stack is under the 512 KiB basis and never raises it.
  `jolt_parser_budget` reports `{input, depth, depth-here, stack, basis}`:
  - macOS: `[4194304 256 256 8372224 524288]`
  - shiva: stack 8376320, depth-here 256
  - jupiter: stack 8380416, depth-here 256
- **Time.** Derived, not interrupted. The worst at-bound legal parse is unary
  at 256 levels:

  | host | unary at 256 | 4 MiB comment |
  |---|---|---|
  | macOS | 1470 ms (3501 ms in an earlier, loaded run) | 897 ms (1614 ms) |
  | shiva | 2755 ms | 1764 ms |
  | jupiter | 1970 ms | 1286 ms |

  The other shapes at 256 ran in 28–360 ms on all hosts.
- **Outcome.** `{:diagnostic :parser-budget :axis :input|:nesting :bound n
  :measured m}`; the frontend's parse adds `:lang :yang/python`.
  - It reaches the REPL through the same operational channel as
    unavailable-parser: thrown at the parse stage and rendered as
    `Error: parser-budget: nesting 300 is over the bound 256 nesting levels (the native Python parser refuses before parsing)`.
  - It is never a SyntaxError and produces no partial packet. The session
    goes on evaluating Python (`x * 6` → 42), Clojure and PHP.
  - The completeness probe reads a budget refusal as `:early`.
- **Lifecycle.**
  - `yang.python.antlr.parser/open-stage` owns its worker: `close-stage`
    disposes it, and `reset-stage` disposes it and installs a fresh one.
  - The convenience `parse-source` and `first-failure` dispose the worker
    they make.
  - A closed worker refuses (`jolt parser worker is closed`); a second close
    is a no-op.
- **Unavailable helper.** `cst.jolt` loads the library on first use; nothing
  loads at namespace load.
  - A missing, wrong-layout (≠ 2) or unloadable library answers
    `unavailable-parser … {:diagnostic :unavailable-parser :reason :library}`
    and is retried on the next call.
  - The jolt standard catalog installs `:yang/python` only when the library
    is available. Otherwise `(lang :yang/python)` answers the catalog's
    unavailable-parser text, never another parser.

## Per-axis gates (B)

`bb probe:jolt-j5` (`src/dev/jolt_j5_run.clj`) runs each gate in a child
process. Results: macOS 37 PASS (pre-fix crash legs as noted above); shiva
ALL PASS (940 s); jupiter ALL PASS (658 s).

- **Below.** All 9 carrier shapes at depth 32 parse, and their packets equal
  the JVM's under strict equality (`src/dev/jolt_j5_below.cljc` run on both
  hosts).
- **At.** For each shape, the source whose prescan measures exactly 256
  parses. 257 refuses with exactly
  `{:diagnostic :parser-budget :axis :nesting :bound 256 :measured 257}`.
- **Input axis.** 4 MiB parses. 4 MiB + 1 refuses
  `{:axis :input :bound 4194304 :measured 4194305}` through the mirror; with
  the mirror bypassed, the native answer is −6, `input 4194305 4194304`.
- **Indent is counted.** 257 INDENT levels refuse on the nesting axis while
  far under the input bound.
- **Above, at 10x the old crash depths, in process.** Each is refused three
  times with identical data and no crash:
  - 100,000 nested parens: nesting, measured 100000;
  - 100,000 unary prefixes: nesting, measured 100000;
  - a 5,000-deep INDENT source (12.5 MB): the input axis.

  The native status for 100,000 parens is −7, `nesting 100000 256`.
- **Old crash depths in child processes, on the jolt thread.** parens
  544/576/1024, call 512/544, list 544/576, indent 1280/1344 and unary
  512/1024 each print `ERR status -7` and exit 0, with no SIGSEGV.
  - On Linux the jolt thread has 8 MiB of stack, so the fixed 256 bound
    refuses there, not the clamp.
  - On macOS (536 KiB thread) this was verified by hand after the harness
    fix, as noted above.
- **Fault-injection harness property.**
  - Injected C++ modes 1–3 throw −2/−3/−2 with no packet, and the same
    worker parses again.
  - A forced abort is judged a failure (exit 134, SIGABRT) and keeps its
    pre-crash report (`REPORT before-abort parsed :yang.cst/ok`).
  - Both pass on all three hosts.

## Lifecycle (C)

- **J0c workers harness.** The 12 worker tests plus section 6
  (reset-stage/close-stage) and section 7 (two composed REPL sessions, one
  reset, isolated). SUMMARY ok on shiva (993 s) and jupiter (654 s). On
  macOS the run stopped at the directive, after the section 6 legs had
  passed.
  - The section 6 check first failed by comparing against packets made with
    another grammar id; it now compares the packet before and after the
    reset.
- **cycles 20000.** Including the new convenience-call flatness phase:
  SUMMARY ok on macOS, shiva and jupiter.
- **Sanitizer (ASan+UBSan, 50 cycles).** HARNESS ok on shiva and jupiter.
  - The 70 MiB source now answers −6. The −4 output-bound leg moved to a
    legal 600 KB source (300,000 `1\n`, about 129 output bytes per input
    byte).
  - The parity harness carries the same −4 leg: 171 s on shiva, 126 s on
    jupiter.
  - The macOS sanitizer run predates the fix: its −4 leg failed (status −6),
    and it was not rerun on macOS after the directive.
- **gc, faults, parity.** SUMMARY ok on shiva and jupiter. parity reports
  0 diverged.

## Unavailable helper (D)

In the probe and in `bb test:jolt-j5`, both on all three hosts:

- **Library absent.** The REPL starts and the banner reads
  `python: unavailable-parser (…)`. `(lang :yang/python)` answers
  `Error: unavailable-parser: Yin REPL language :yang/python is not in the frontend catalog; installed: :clojure :php …`,
  and then PHP and Clojure evaluate in the same session.
- **Wrong layout** (a stub library answering layout 99): the same answers.
- **Retry.** Python is unavailable first; the built library then appears,
  the next selection picks it up, and `1 + 2` gives 3.

## Retired exclusions and counts (E)

- **Retired.** The five `:owner "J5"` entries are gone from
  `jolt-applicability.edn` and their bodies run unchanged:
  - frontend_catalog: `the-standard-languages-select-by-id-test`,
    `the-antlr-id-is-installed-on-every-host-test`;
  - main: `the-interactive-composition-holds-every-standard-language`;
  - repl: `language-and-compile-commands-behave-as-they-do-in-v1`,
    `a-python-submission-cannot-see-a-clojure-defined-name`.
- `yin.repl.python-antlr-test` joins the J2 lane.
- The main.cljc banner arm now names the native parser, and the J3-row
  cross-reference is retired in the spec.

Count identity with the JVM: per-namespace JVM runs with
`DATOM_SLOW_TESTS=1` on jupiter, against the jolt lane.

| suite | JVM tests/assertions | jolt | note |
|---|---|---|---|
| frontend-catalog | 8 / 42 | 8 / 42, 0 fail | identical |
| python-antlr | 21 / 194 | 21 / 194, 0 fail | identical |
| yin.repl-test | 52 / 264 | 52 / 264, 4 fail | identical counts; the 4 fails are not J5's (below) |
| main | 48 / 397 | 30 / 288, 0 fail | not identical: 18 fewer tests on jolt. `jolt-applicability.edn` has 20 main-test entries (J3 2, J3/J6 1, J6 7, J7 10) and none is J5's. The 20-vs-18 difference was not reconciled per deftest |

- **python-antlr runtime.** The suite took 7218 s alone on jupiter
  (`jolt_test_runner`), against 1231 s for all four JVM suites. The lane's
  60-minute default step timed it out in both lane runs, so `e4d43e5e` gives
  it a three-hour floor. Shiva's lane run of just this suite at `e4d43e5e`
  (`bb test:jolt-core --only '^yin\.repl\.python-antlr-test$'`): ok, 21/194,
  0 fail, 0 timeouts, in 10166 s. That is 2.8 h, inside the three-hour
  floor but with little headroom on shiva.

## Lanes

- **jolt yin.repl lane** (`--only` the yin.repl suites plus oracle-parity),
  on both hosts: 17 namespaces, 0 load failures, 1957/1961.
  - The 4 failures are yin.repl-test.
  - Timeout: python-antlr (fixed in `e4d43e5e`, see above).
- **Full jolt lane, jupiter:** 176 namespaces, 0 load failures, 3101 tests,
  210548 assertions, 4 fail and 3 errors.
  - 4 fail: yin.repl-test reader.
  - 3 errors: yin.vm.data-test surrogates.
  - Timeout: python-antlr, as above.
- **Full jolt lane, shiva:** identical to jupiter. 176 namespaces, 0 load
  failures, 3101 tests, 210548 assertions, 4 fail (yin.repl-test reader),
  3 errors (yin.vm.data-test), and the python-antlr timeout (8398 s).
- **JVM slow suites:** 129 tests, 897 assertions, 0 failures on both hosts.
- **Shell corpus** (`bb test:jolt-shell`, with the `--no-state` fix): rc 0
  on shiva (1512 s) and jupiter (1058 s). It includes `:python` and
  `:python-eval` on both hosts.
- **End-to-end acceptance** (`bb test:jolt-j5`): ALL PASS on macOS, shiva
  and jupiter.
  - One Python session runs through each host's own REPL entry point with
    the standard catalog, no hand-built catalog: arithmetic, definitions
    across submissions, a multiline function, `print` with non-ASCII and
    astral text, an exception that keeps the session, big-integer shifts,
    floats, a class, a syntax error, then PHP and Clojure.
  - The jolt output equals the JVM's line for line (prompts normalized,
    banner and Host line excluded).
  - The completeness-probe sequences (open block, bracket, triple quote, an
    early error under an open bracket) match the JVM's.

## §7.8 parity at this head

`jolt_j0c_parity.clj` SUMMARY ok, 0 diverged, on shiva and jupiter:

- zero pins, JVM canonical;
- the corpus packets and the completeness-probe texts equal the oracle's;
- repl-probe's parser is the tracked `parser.cljc`, with the native
  rule-name table (the stand-in stub is retired).

`yang.python.antlr.oracle-parity-test` passed 8/8 on macOS.

## Docs and ledger (F)

- **Spec §7.6** gains "Enforced (J5)": the carrier table, both bounds, the
  derivation, the clamp, the walls, the outcome shape and the divergence
  from the JVM above the bounds.
- **Spec §10.1.** The J5 row now reads "landed on jolt-j5, review pending",
  with the amended scope noted. The J3 row now reads "selectable since J5",
  and the J3 implementation note is amended.
- **known-gaps row 59** is closed in place with the evidence. Following the
  union rule, a new P3 row lists the limits that survive (next section).
  The file was not rewritten.

## J5 declaration (G)

**Local REPL milestone.** On jolt the standard catalog loads,
`(lang :yang/python)` works over the native C++ ANTLR4 parser library
(native/jolt-parser, in process; local-build-only packaging), and the
frontend_catalog, python_antlr and main suites and the end-to-end acceptance
run hold on macOS arm64 and Linux x86_64.

Disclosures, verbatim:

- **No process crash isolation.** An uncatchable native fault ends the jolt
  process.
- **No worker-restart claim.**
- **No cancellation.** An under-bound parse runs to completion; time is
  bounded only through the input and nesting axes.
- **TSan and LSan.** TSan was not run, and LSan has no positive control.
  The FFI cleanup cannot recover when no pointer is obtainable or when the
  release primitive itself fails.
- **Divergence from the JVM above the bound.** The JVM, Node and Dart
  parsers have no budget: a source over 4 MiB, or nesting more than 256
  levels, that the JVM parses is refused on jolt with `:parser-budget`. This
  is a recorded divergence, not a parity pin.

Not claimed: J6 and J7.

## Diagnosed, not fixed (outside J5)

- **yin.repl-test reader (4 fails, jolt only).** `%` arguments and tagged
  literals/reader conditionals inside `%` forms read differently from the
  JVM (`a-percent-reads-alike-in-every-symbol-position`,
  `tagged-literals-and-reader-conditionals-refuse-alike` ×3). They predate
  J5 and are not Python.
- **yin.vm.data-test (3 errors, jolt only).** Surrogate code points; they
  predate J5.
- **Shell runner defects** (fixed in 8c1144dc, also on master):
  - Without `--no-state`, the jolt refusal legs persisted `--port 8080` and
    `file:x` into `~/.yin/node/state.edn` and broke every later jolt leg.
  - `:file-refusal` opened a store named `x` in the repository root, since
    J4 lets `file:` open on jolt.
  - Locally, the polluted state file was moved to
    `/tmp/yin-state-polluted-by-jolt-shell.edn` and the `x/` directory was
    removed.
- **Disk.** Shiva's root filesystem is at 93%.
