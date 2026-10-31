Completed-GMT: 2026-09-25 22:36:20 GMT
Completed-Local: 2026-09-26 05:36:20 +07
Coding-Agent: claude
Session-ID: 95dfac20-b589-4457-a3b8-1e3019c8e120

# Report: Rule R commit two (M2 on top of Rule R) and conflict resolution

Role: VM Runtime Engineer. Worktree: /Users/sto/workspace/datomworld-ucf-phase2
(branch ucf-phase2, HEAD 0fc931fc). I did not stage, commit, check out,
reset, stash, or merge anything. The stash entry
"m2-uncommitted-before-rule-r-ff" and the archive backup are untouched.
`git status`: content.cljc staged (M2, unchanged by me); linker.cljc and
linker_test.cljc staged (M2) plus my unstaged edits; content_test.cljc still
`UU` (resolved in the file, not added); docs/design/yin.vm.linker.md
unstaged.

## Step 0: the conflict in test/yin/vm/content_test.cljc

The hunk sat in `fetched-vectors-carry-their-address-into-the-vm`:
- upstream (Rule R) had `content/fetch-vector` with `vm/semantic-contract`;
- stashed (M2) had `linker/fetch` and a 2-argument `load-vector`.

I kept M2's `linker/fetch` form. The load now passes the contract that the
format record carries for the fetched content:

```clojure
loaded (semantic/load-vector (semantic/create-vm) (:value res)
                             (:contract linker/semantic-format))
```

**Why the record's contract.**
- The vector came from outside through the linker under
  `linker/semantic-format`. The contract that governs it is the one that
  record implements. Its fetch compared that contract at step 0 before
  admitting anything.
- Using `vm/semantic-contract` here would stamp external input with the
  loader's own constant. That is the pattern codex rejected in commit one.
- The load now compares the stamp the content was admitted under against
  the stamp the loader runs. Before Step 2 the record still said "v2", so
  the load refused `:contract-mismatch` ("stamped v2, this loader executes
  v3"). That was the correct outcome, and it shows up in the baseline.

After Step 2 the same test also passes the contract in the fetch request
(see below).

## Step 1: merged baseline (resolved tree, before any other change)

| Lane | Result |
|---|---|
| JVM `clojure -M:test` | 2083 tests, 181343 assertions, 2 failures, 6 errors |
| Node `compile slice-peer test` | 1996 tests, 48204 assertions, 2 failures, 6 errors (2 `:fn-arity` compile warnings) |
| Dart `bb test:cljd` (fresh cljd-out) | 0 passed. All 57 test files failed to compile |

JVM and Node failed the same way:
- `fetched-vectors-carry-their-address-into-the-vm`: `:contract-mismatch`,
  v2 vs v3, as expected above.
- `a-module-that-rebinds-yin-def-discharges-nothing-from-it`: the mint side
  now refuses `:reserved-name` before the write.
- `a-constant-key-yin-def-application-is-a-definition` (2 failures):
  `yin/def` is no longer a primitive, so the operator obligation came back
  `:unresolved-free`.
- `fetched-images-execute-like-local-code` (3 errors) and
  `images-transfer-over-dao-stream`: `ast-walker/vm-load-rows` was called
  with 2 arguments, but it now requires the contract.

On Dart those two 2-argument calls are compile errors in the aggregated
test library, so no file loads.

Lane note: the baseline Dart run kept going file by file after its compile
failure. It was still running when I started the final Dart run. I stopped
both, then reran the final Dart lane solo. The final counts below come from
that solo run.

## Final lane counts (after Step 2)

| Lane | Result |
|---|---|
| JVM | 2089 tests, 181424 assertions, 0 failures, 0 errors |
| Node | 2002 tests, 48286 assertions, 0 failures, 0 errors; "Testing yin.vm.linker-test" and "Testing yin.vm.content-test" present; no compile warnings |
| Dart (fresh cljd-out, solo) | +1964, "All tests passed!"; the new linker tests appear in the output |

- kondo (`mise exec -- clojure -M:kondo --lint` on linker.cljc,
  linker_test.cljc and content_test.cljc): 0 errors, 0 warnings.
- `cljstyle check`: passes on all three files.
- Every added line in source, tests and docs is ASCII, at most 80 columns,
  and has no em dash. I checked this over the diff.

## What was implemented (commit two)

**Format records and fetch** (src/cljc/yin/vm/linker.cljc)
- The four records take the new contract names from the `yin.vm`
  constants:
  - `ast-format` gets `vm/ast-contract` ("v3");
  - `semantic-format` gets `vm/semantic-contract` ("v3");
  - `stack-format` gets `vm/stack-contract` ("b2");
  - `register-format` gets `vm/register-contract` ("r2").

  Docstrings are updated to match.
- `fetch` step 0 now requires a contract:
  - a nil or absent `:contract` gives
    `{:status :refused :reason :invalid-request :missing :contract}`;
  - a differing contract still gives `:contract-mismatch`.

  Both refusals come before any read. `:invalid-request` is added to
  `refusal-reasons`.
- I kept the 4- and 5-arity forms. They pass no opts, so they return
  `:invalid-request` (documented in the docstring).
- `fallback-fetch` (used by the trusted and verifying R to H fallbacks)
  passes `{:contract (:contract stack-format)}` explicitly.

**Deleted (the round 3 and round 4 guards)**
- `tree-yin-def-application-query` (round 4).
- `computed-yin-def-write?` (round 4).
- The shadow filter in `tree-definition-occurrences` (round 3). It dropped
  every `yin/def`-derived definition when the footprint bound `yin/def`.
- The `:yin-def?` bookkeeping and the final `dissoc`.
- The docstring's shadow-proof prose. It is replaced by a one-line Rule R
  justification: the validator refuses every rebinding or alias, and no
  engine resolves the operator.

**Kept**
- Constant-key recognition (round 2): `tree-definition-query`, two
  operands with the first a literal.
- The invocation position `(conj path [3 2])` for definition forms
  (round 3).
- `tree-application-sites` at `[3 n]`, and dominance in `undischarged`.
- All earlier fetch-bound fixes are untouched:
  - the byte cap is checked before hashing or decoding;
  - `:max-parts 0` refuses the root before it is read;
  - the parts budget is enforced at enqueue time;
  - `default-bounds` apply;
  - a failing handle is `:absent`.

**Scanners**
- A new private helper, `binding-key`, reads the key of a `:store-put` or
  `:define` at a given slot.
- `vector-definition-occurrences` (semantic and stack) reads `:define`
  beside `:store-put`, at slot 1.
- `register-definition-occurrences` reads `[:define rd key rs]`, at slot 2.
- `tree-free-name-occurrences` removes `vm/reserved-name?` names, so the
  definition operator is never an obligation. Validation has already
  refused every other occurrence of the name. The vector scanners need no
  change: their validators refuse a `:var` or `:load-free` naming
  `yin/def`.

**Docs** (docs/design/yin.vm.linker.md): the omitted-contract refusal has now
landed, so I changed the three places that said it "lands with the M2
format records" (section 4.2 step 0, section 6.3, and criterion 24 of
section 11). The section 6.3 closed key set no longer marks
`:yin.link/contract` as optional.

## Tests added or flipped (test/yin/vm/linker_test.cljc)

I wrote these first and saw them fail on the JVM: 64 failures and 19
errors, with the reserved-name refusals already green because commit one's
validators refuse at step 4. Then I implemented the change and they
passed.

**Mechanical change.** Every `linker/fetch` call site now passes the
contract explicitly: 58 in linker_test and 8 in content_test. The helper
`requested` builds `{:contract (:contract format)}` and can merge in bounds.
The two contract-mismatch cases keep their literal opts.

**Changed tests**
- `format-records-name-their-contract`: asserts "b2", "r2", "v3", "v3",
  and that each record equals its backend loader's `vm/*-contract`.
- `a-request-naming-another-contract-is-contract-mismatch`: "so is an
  absent one" is replaced. A requester naming "v2", "b1" or "r1" is now
  `:contract-mismatch`.

**New tests**
- `a-request-omitting-the-contract-is-invalid-request`, for all four
  formats: the 4-arity and 5-arity forms, nil opts, `{}`, bounds only, and
  `{:contract nil}` are all refused, and the store sees zero reads.
- `a-definition-discharges-its-reads-in-every-format`, for all four
  formats: the fetch is ok with no obligations, and the fetched image runs
  on its own backend (walker, semantic, stack, register) to store `x = 1`.
  It is loaded under the record's contract through the `run-fetched`
  helper.
- `a-constant-key-yin-def-application-is-a-definition`, now AST
  scanner-level:
  - the definition is at `[[3 0] [3 2]]`;
  - the obligations hold only the read of `x`, and the operator is never
    an occurrence.

  Its old fetch assertion, which expected a `[yin/def]` obligation, is
  gone.
- `vector-definitions-read-the-define-instruction`: on SEM, H and R, the
  `:define` is the unconditional definition and it precedes the read.
- `a-yin-def-value-operand-read-precedes-the-definition`, extended from
  AST only to all four formats: `:use-before-definition`.
- `a-definition-whose-value-is-the-quoted-symbol-links`, for all four
  formats. It covers `(yin/def 'x 'yin/def)` followed by a read of `x`:
  - the fetch is ok with no obligations;
  - each backend stores `x` as the symbol `yin/def`.

**Flipped to refusal tests.** Each tree is published raw, because the mint
side refuses before the write, and each fetch gives `:descriptor-defect`
with rule `:reserved-name`.
- `a-module-that-rebinds-yin-def-discharges-nothing-from-it` is now
  `a-module-that-rebinds-yin-def-is-reserved-name`. It also asserts that
  `materialize-tree!` throws.
- `a-direct-store-put-of-yin-def-discharges-nothing-from-it` is now
  `a-direct-store-put-of-yin-def-is-reserved-name`.
- `a-computed-key-yin-def-write-discharges-nothing-from-it` is now
  `a-computed-key-yin-def-write-is-reserved-name`.
- New `an-aliased-yin-def-call-is-reserved-name` covers
  `((fn [setter] (setter 'yin/def 0)) yin/def)`. This is codex's round 4
  alias case, which had no fixture in the tree.
- New `a-vector-store-or-definition-key-yin-def-is-reserved-name`: on SEM,
  H and R, a `:store-put` or `:define` whose key is renamed to `yin/def`
  is refused.

The fixtures were renamed from `*-then-apparent-def` to `*-then-def`, and
their docstrings now describe the Rule R refusal.

**Stale loader calls fixed.** `fetched-images-execute-like-local-code` and
`images-transfer-over-dao-stream` now run fetched AST, SEM and H images
through `run-fetched`, under the record's contract.
- Lifted vectors (`dl/lift`, `rc/lift`) still load under
  `vm/semantic-contract`. The lift is a fresh-code producer.
- content_test: the `requested` helper and explicit contracts on all 8
  fetches, plus the Step 0 resolution.

## Deviations

1. **Shorter fetch arities kept.** They now always return
   `:invalid-request` rather than being removed. The design says an
   omitted contract is `:invalid-request`, and keeping them makes that
   refusal observable at the API boundary. The owner could remove them
   instead.
2. **Contracts from constants.** The records take `vm/*-contract` rather
   than string literals, so the record and the loader cannot drift. The
   test pins the literal names too.
3. **Docs.** The three sentences in linker.md were not named in the
   prompt. I edited them because they would otherwise state that the
   omitted-contract refusal has not landed.
4. **TDD order.** Tests were written and seen red on the JVM before the
   source edits. The per-site contract threading was done by a
   rewrite-clj script (target/add_contract.clj, gitignored scratch) and
   then reformatted with cljstyle.

## Unrun or partial checks, and findings

- The M2 gate itself (codex) is for the orchestrator.
- I found no separate parity-harness command. Parity ran inside the three
  suites.
- Baseline Dart overlapped my first final Dart attempt (see the lane note
  above). The reported final Dart count comes from a clean solo rerun.
- **Finding, not changed (out of scope):**
  src/cljc/yin/vm/completion.cljc:299 still has a docstring naming the
  retired `yin.vm.content/fetch-vector` / `load-rows`.
- Nothing from the M4 bucket was touched. The reserved set is still only
  `yin/def`, and `require` stays an ordinary primitive.

Status: COMPLETE
