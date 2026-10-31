Completed-GMT: 2026-09-25 16:59:32 GMT
Completed-Local: 2026-09-25 23:59:32 +07
Coding-Agent: claude
Session-ID: resume-of-078d0a96-daf0-4cef-b994-076601d800d6

# Report: Rule R commit one, fix round 1

Role: VM Runtime Engineer. Worktree: /Users/sto/workspace/datomworld-ucf-rule-r
(branch ucf-rule-r). Nothing is staged or committed. The whole uncommitted
diff, commit one plus this round, is 62 tracked files (+1713/-520) and two
new test files.

## Lanes

Each lane ran solo under mise. Dart ran with `rm -rf test/cljd-out` first.
All three are green. The rule-r tests ran on every host.

| Lane | Last round | This round |
|---|---|---|
| JVM | 2045 tests, 180913 assertions | 2049 tests, 180956 assertions, 0 failures, 0 errors |
| Node | 1960 tests, 47956 assertions | 1963 tests, 47972 assertions, 0 failures, 0 errors |
| Dart | 1922 passed | 1925 passed |

Node and Dart each gained three tests: the adapter test, the canonicalize
test, and the definition-key requirement test. The JVM also gained the
negative-fixture audit test.

- **kondo** on every changed file: 0 errors. All 8 warnings exist at HEAD;
  I checked this against HEAD copies of `vm.cljc`, `ast_walker.cljc`,
  `linearize_test`, `macro_test`, and `telemetry_test`.
- **cljstyle check:** clean.
- **ASCII and 80 columns:** every added line in the whole uncommitted diff
  (code, tests, docs) is ASCII and at most 80 columns. The one exception is
  the grid-table rows in the docs, which must be full table width.

## P1: adapters stamped code on the caller's behalf (fixed)

- `linearize/ast-loader` and `linearize/rows-loader` now return
  `(fn [vm code contract])`. Each checks the incoming code's own AST stamp
  (`check-contract!` against `vm/ast-contract`) before it lowers anything.
  Only the linearizer's own lowered output, built from verified input, is
  loaded under `vm/semantic-contract`.
- The only path that stamps code automatically is now explicit:
  `vm/fresh-code-loader [load contract]`. Its docstring restricts it to a
  medium whose only producer is trusted fresh code. It is used in these
  places:
  - `test_utils/run-session`, where `queue-ast!` is the only producer.
  - `test_utils/run-encoder-session`, where the encoder observer is the only
    producer.
  - The test suites that lower their own ASTs (`load-ast` and `load-rows`
    helpers).
- The REPL's `:semantic` loader passes `vm/ast-contract` explicitly, with a
  comment that the expander is the only producer.
- **Other places that stamped on the caller's behalf.** `ucf/canonicalize`
  stamped any batch it was given with `contract-stamp`. That is the same
  defect: an old or unstamped batch came out labelled "v3" and could be
  loaded under that label. It is now `(canonicalize datoms contract)`. It
  checks the batch's stamp first, and the stamp on its output is the one it
  checked. I updated its 10 test call sites.
- **Tests that go through the adapters.** In `rule_r_test`, for both
  `ast-loader` and `rows-loader`:
  - an unstamped input is refused with `:contract-missing`;
  - an input stamped "v2" is refused with `:contract-mismatch` and reports
    `:actual "v2"`;
  - an input stamped "v3" lowers, loads, and runs to 3;
  - the `fresh-code-loader` path runs.

  `canonicalize` gets the same missing and old-stamp tests, plus a check
  that the stamp it returns is the one it verified. `linearize_test` asserts
  that the adapter refuses an unstamped input before the loader sees it.

## P2: the store-write audit was a one-line regex (fixed)

`store_write_audit_test.clj` now reads every file under `src` as forms,
using tools.reader with reader conditionals preserved so that every host's
branch is read.

- **What counts as a store mutation:** a call whose head is `assoc`,
  `assoc-in`, `update`, `update-in`, `merge`, `merge-with`, `into`,
  `dissoc`, `select-keys`, `conj`, `swap!`, `reset!`, `vswap!`, or
  `vreset!`, when either:
  - one of its arguments is `:store`; or
  - one of its arguments is a key path containing `:store`; or
  - its target is a store. That means a `store`, `new-store`, or `store0`
    style symbol, `(:store x)`, or `(get x :store)`.

  Also flagged: every `store-put` call, and every map literal with a
  `:store` key (a map rebuilt around the store).
- **How sites are keyed:** each site is keyed by file, top-level form name,
  and call head, with a count. So the allowlist does not shift when line
  numbers change.
- **How the gate fails:** on any unlisted site, on any listed site that no
  longer exists, and on any file it cannot read. Every source file reads
  cleanly today.
- **Negative fixtures.** 13 must be detected, and are:
  - `(assoc-in state [:store 'yin/def] v)`, codex's example;
  - `update :store`;
  - `update-in [:store ...]`;
  - `assoc :store (conj ...)`;
  - `dissoc (:store vm)`;
  - `select-keys (get vm :store)`;
  - `into store`;
  - a `merge` of `new-store` written across several lines;
  - `swap! a assoc-in [:store ..]`;
  - `reset! a {:store v}`;
  - `merge-with ... {:store m}`;
  - an unlisted `engine/store-put`;
  - a write hidden inside `#?(:clj ...)`.

  The old one-line regex missed at least codex's example and the multi-line
  `merge`.
- **New sites on the allowlist.** Parsing found sites the regex could not
  see. Each is listed with its reason:
  - VM construction: `vm/empty-state`'s FFI-pair map, and the de Bruijn
    `create-vm` maps.
  - `engine/check-wait-set` and the `assoc :store` calls in `handle-make`
    and `handle-cursor`. These carry engine-minted keys.
  - `dao.stream.waitset/check`: its result map, which holds the store its
    `:advance` wrote.
  - `dao.space.query/open-published!`: an index handle, not a VM store.
  - `repl/make-expander`: the expander's macro store.
  - Display projections in two cljs demos.
  - `macro/make-ctx` and `macro/expand-batch`: the checked macro store.
- `yin.vm.engine.md` section 1.1 now describes the parsed audit.

## P2: definition keys are store-slice requirements (fixed, as designed)

- **Test first.** `query_test/definition-keys-are-store-slice-requirements`
  failed on both sides before the fix. The requirements corpus now also
  includes a definition tree, so tree/segment equality is checked for it.
- **AST side.** The `$ast` `:store-keys` query is now an `or-join`. Its new
  branch reads the literal key of an `:application` whose operator is a
  `:variable` named by a reserved name.
- **Segment side.** The `$code` query adds `[$code _ _ :define ?key]`.
- A definition still contributes no effect identifier, the same as
  `:store-put`. This does not conflict with the completion change from last
  round. Fable's "contributes :vm/store-put syntactically" matches how a
  `:vm/store-put` row is treated: its key enters the store slice, and its
  footprint has no effects.
- **Docs.** code-as-tuples 7.7.1: the table row now says a `:define` key
  goes into the store-slice requirement, like a `:store-put` key. The 7.7
  query listings in the doc match the code. The UCF text at lines 825-829
  already agreed.
- **Precision consequence (please note).** Completion now pulls every
  definition key of every reachable segment into the slice. Previously it
  pulled none, which undercounted. So definitions that share a segment with
  a reachable closure body now come along with it.

  The `completion_test` maker fixture defined `a` and `b` in the same
  segment as `mk`. With this fix, reading `b` would also pull `a` and its
  stream. I split the fixture into two loads: `mk` and `fact` in one, `a`
  and `b` in the other. Every original per-key assertion (`a` pulls the
  stream, `b` does not) still holds unchanged. This is the conservative
  `:store-put` rule applied as designed. I found no correctness conflict.
- **Query-engine finding (not changed).** A plain symbol constant in a
  pattern (`[$ast ?op :variable yin/def]`) did not constrain the match; it
  appears to be treated as a variable. I used a `reserved-name?` predicate
  clause instead. Anyone writing `dao.space.query` patterns with symbol
  constants should know this.

## Also

The UCF header banner (line 4) said "This revision (r3)". It now says r4
(Rule R's "v3" contract), wrapped to 80 columns in ASCII. The UCF 7.3.3 and
yin-repl-design texts now describe the verified adapters and
`vm/fresh-code-loader` instead of "linearizers supply the constant".

## Remaining places that supply a contract implicitly

None of these relabels external input. Each is either a trusted fresh
producer or reports a contract rather than admitting code under it:

- `vm/eval`, walker (`ast_walker.cljc`). It takes an AST map from the
  caller, stamps it `ast-contract`, and validates the whole tree. The design
  names it as a fresh producer.
- `macro/bounded-row-evaluator`, the transformer runner. It loads a stored
  macro body under `ast-contract`. Those bodies came from expander input
  batches, which carry no stamp of their own. The design names it as
  trusted. If expander input ever needs to be stamped, this is where that
  would apply.
- `repl/program-loaders`: expander output. All four VMs load it under the
  current constants.
- The handoff demo sender stamps its registers with `vm/semantic-contract`,
  not with the stamp its code was admitted under. They are the same today,
  because loaders admit only the current contract. The receiver verifies
  the shipped stamp.
- `completion/complete`: `:contract` defaults to `vm/semantic-contract`. It
  describes the quiescent VM, and code fetched through `:fetch` is analysed
  under v3 rules without a stamp. This is analysis only, never admission.
- `ledger/lowering-profile`: records that `lower` (a fresh producer) ran
  under v3. `verify-derivation` compares profiles; it does not assign one.
- The demos: `compilation_pipeline.cljs`, `continuation_stream.cljs`, and
  `register_bench_cljd.cljd` lower fresh yang output.
- `vm/fresh-code-loader` and its callers listed under P1: the one named
  trusted path.

## Files changed this round

- **Source:**
  - `src/cljc/yin/vm.cljc`: `fresh-code-loader`, the extraction queries
    and fns.
  - `src/cljc/yin/vm/linearize.cljc`: the adapters.
  - `src/cljc/yin/vm/ucf.cljc`: `canonicalize`.
  - `src/cljc/yin/repl.cljc`: the `:semantic` loader.
- **Tests:**
  - `test/yin/vm/store_write_audit_test.clj`: rewritten.
  - `test/yin/vm/rule_r_test.cljc`: two new tests.
  - `test/dao/space/query_test.cljc`: new test and corpus entry.
  - `test/yin/vm/completion_test.cljc`: fixture split.
  - `test/yin/vm/ucf_test.cljc`: stamps on `canonicalize` calls.
  - `test/yin/vm/linearize_test.cljc`: adapter arity and refusal.
  - `test/yin/vm/telemetry_test.cljc`: adapter arity.
  - `test_utils`, `semantic_stream_observer`, `semantic_engine`,
    `semantic_ffi`, `stack_parity`, `stack_effects`, `register_test`: the
    `fresh-code-loader` wrappers.
- **Docs:** `yin.vm.code-as-tuples.md` (7.7 queries, 7.7.1 row),
  `yin.vm.universal-continuation-format.md` (header, 7.3.3),
  `yin.vm.engine.md` (1.1), `yin-repl-design.md`.

## Unrun checks

None of the requested checks were skipped. The three lanes, kondo, and
cljstyle all ran. As before, there is no separate parity-harness command;
the parity suites ran inside the three lanes.

Status: COMPLETE
