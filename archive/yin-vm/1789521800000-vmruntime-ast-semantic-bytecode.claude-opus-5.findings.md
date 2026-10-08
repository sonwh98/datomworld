Created-GMT: 2026-09-15 21:23:20 GMT
Created-Local: 2026-09-16 04:23:20 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 206534e8-0432-4941-8631-0212c8f59132
# Findings: yin.vm/ast->semantic-bytecode (map AST -> flat content-addressed rows)
Role: VM Runtime
Implementer: claude-opus-5 | Status: complete (uncommitted working-tree diff)

## Files and functions

`src/cljc/yin/vm.cljc` (additive; I did not create a new file):
- `:27` added `[dao.jing :as jing]` to the ns `:require`. There is no
  cycle: neither `dao.jing` nor its requires (`dao.stream`,
  `dao.stream.observe`) require any `yin.vm` namespace.
- `:563-718` new section "Semantic bytecode: flat content-addressed rows":
  - `semantic-bytecode-grammar` (public): the §2.3 table as data,
    `tag -> [[field kind] ...]`, with fields in Slots-column order. Both
    directions are driven by this one map, so they cannot disagree about
    positions or field names.
  - `semantic-bytecode-defaults` (private): the §2.4 saturation table,
    `[tag field] -> default`. Buffer uses `default-stream-capacity`.
  - `strip-symbol-meta` (private).
  - `ast->semantic-bytecode` (starts `:607`).
  - `semantic-bytecode->ast` (`:652-718`).
- I did not modify `ast->datoms-with-root`, `ast->datoms`, or `datoms->ast`.

`test/yin/vm_test.cljc`:
- `:5` added the `[dao.jing :as jing]` require.
- `:149-310` added helpers (`error-rule`, `lit`, `local`, `global`, `app`),
  `semantic-bytecode-corpus`, and 6 deftests:
  `semantic-bytecode-corpus-covers-every-tag`,
  `semantic-bytecode-round-trip-law`, `semantic-bytecode-row-shape`,
  `semantic-bytecode-structural-sharing`,
  `semantic-bytecode-strips-and-saturates`, and
  `semantic-bytecode-reconstruction-validates`.

## Projection (map -> rows)

- **Content addressing**: nodes are converted bottom-up. For each node the
  body is `(into [tag] (map slot-value) slots)`:
  - a `node` slot becomes `(convert child)`, i.e. the child's id;
  - a `nodes` slot becomes `(mapv convert children)`;
  - a `bool` slot becomes `(boolean v)`;
  - every other slot is the value unchanged.

  The id is `(jing/segment-key body)` and the row is `(into [id] body)`.
  The result is `{:root id, :rows {id row}}`.
- **Structural sharing**: rows accumulate in a map keyed by id. Identical
  subtrees mint the same id, so they land on one entry, whether they came
  from shared references or were built independently. I added no
  identity/`=` cache on input maps. Such a cache would be unsound: two
  values can be `=` yet print differently (e.g. `[1 2]` and `(1 2)`), so
  they hash differently. Hashing only happens per body, and a body holds
  child ids rather than subtrees, so the work is O(nodes).
- **Collision guard**: if an id already holds a row that is `not=` to the
  new row, it throws `"Semantic bytecode address collision"`. This is a
  cheap tripwire for the §4.2/§4.4 non-injectivity concern. It is not a
  fix for it: the encoder's identity-use block still stands, and I claimed
  no encoder domain.
- **Exclusions (§2.5)**: only the slots in the table are read. Everything
  else is dropped silently: `:eid`, `:macro?`, `:phase-policy`, `:yang/*`,
  and `:tail?` on anything other than `:application`. Reader metadata is
  stripped from `sym` slots and from the symbols in `:lambda :params`.
  Params are also coerced with `mapv`, so a list of params becomes a
  vector. `data` slots (literal values, `:vm/store-put` val, store keys)
  keep their metadata, because §2.2 makes metadata part of `data`.
- **Saturation (§2.4)**: a nil field takes its default. That gives
  `:vm/gensym` prefix `"id"`, `:stream/make` buffer 1024, `:application`
  tail? `false`, and `[]` for the operands of `:application` and
  `:dao.stream.apply/call`. Tail marks are kept only on `:application`, as
  in §5.2.1's profile row. Absent means `false`, which matches the codec
  rule at `v2.cljc:412`: that code emits a mark only when it is true.
- `:global` is handled like any other tag (`[:global name]`, `sym`). An
  unknown `:type` throws `"Unknown AST node type"` (same message as
  `ast->datoms`).

## Reconstruction (rows -> map)

Each reached row is validated in order. A failure throws `ex-info` whose
data is `{:rule ... :id ...}`:
1. `:id-resolves`: the root or child id has no row in the set.
2. `:shape`: the row is not a vector of at least 2 elements.
3. `:content-address`: requires `id == (first row) == (segment-key (subvec row 1))`.
4. `:tag`: the tag is not in the grammar.
5. `:arity`: the slot count differs from the table.
6. `:saturation`: a slot that §2.4 saturates is nil. Nothing is re-defaulted.
7. `:slot-kind`: a `nodes` slot is not a vector. This is the only
   slot-kind check. The full §7.4 checks (`data` slots satisfying
   `plain-data?`, `bool`, `int`, and so on) plus `:acyclic`,
   `:root-reachable`, and `:variable-bounds` are not implemented, because
   the task scoped validation to integrity, resolution, and name
   reconstruction. `:acyclic` is implied anyway: a hash-verified row cannot
   point at its own ancestors.

Field names come from the grammar positions. A row reached from several
parents is rebuilt once, memoized by id, and the resulting map is shared.

**Canonical map shape (a decision to note)**: the table says a tag's slot
list is exactly the map's key set beyond `:type`. So reconstruction always
emits every slot key. In particular, an `:application` always carries
`:tail? false|true` and explicit `:operands`. `yang/compile` omits
`:tail?` when it is false, so its output is not canonical in this sense.
`map -> rows -> map` is the identity only on canonical input, which is
what §2.1 and §7.2 say. The walker ignores `:tail?`, so an explicit
`false` changes no behavior.

## Round-trip corpus and test results

The corpus is 27 canonical map ASTs in `semantic-bytecode-corpus`. A test
asserts that the set of tags appearing in the projected rows equals
`(keys semantic-bytecode-grammar)`. Every §2.3 tag is exercised:
- `:literal`: int, string, nil, keyword, a nested vector/list/set, and a map.
- `:variable` and `:global`.
- `:lambda` with 0, 1, and 3 params.
- `:application` with 0, 1, and 2–3 operands, with tail? both true and
  false, and with a lambda operator.
- `:if`.
- `:dao.stream.apply/call` with 0 and 2 operands.
- `:vm/gensym` with the default prefix and with a custom one.
- `:vm/store-get` with symbol, keyword, and numeric keys.
- `:vm/store-put`, whose data val is a nested collection.
- `:vm/current-continuation`, `:vm/park`, and `:vm/resume`.
- `:stream/make` with 16 and 1024.
- A single nested chain covering `:stream/put`, `:stream/cursor`,
  `:stream/next`, and `:stream/close`.

Assertions made:
- Both round-trip identities hold for every corpus entry: rows -> map is
  compared with `=` to the input AST, and rows -> map -> rows with `=` to
  the original `{:root :rows}`.
- The §2.1 example has the row shape `[A :lambda [x] B]`,
  `[B :application C [D E] true]`, and so on, with 5 rows, where every key
  equals its row's first element and is in the `segment` namespace.
- Sharing: an `:if` whose test, alternate, and application operand are
  three independently built `{:type :literal :value 1}` nodes produces one
  id for all three, exactly one `:literal` row, and 4 rows in total. It
  still reconstructs to the input.
- Strip/saturate: `:tail?`, `:eid`, and `:yang/pos` on a literal do not
  change its bytecode; an `:application` with no operands and no tail?
  equals the saturated one; `:macro?` is dropped; reconstructing gensym
  and make gives the saturated defaults.
- Validation: each rule above (content-address, tampered and misplaced;
  id-resolves, missing child and missing root; tag; arity; slot-kind;
  saturation) and the unknown-type throw each have a test.

JVM (`:clj`), command `clojure -M:test -n yin.vm-test`:
```
Testing yin.vm-test

Ran 15 tests containing 127 assertions.
0 failures, 0 errors.
```
That run covers the 9 existing tests plus the 6 new ones, all passing.

Node (`:cljs`), command
`clj -M:cljs -m shadow.cljs.devtools.cli compile test` (log at
`target/v2-cljs-test.log`):
```
Testing yin.vm-test          ; no failures reported in this namespace
...
Ran 1354 tests containing 35466 assertions.
2 failures, 0 errors.
```
Both failures are in `yin.repl.core-test/a-failed-input-is-consumed-exactly-once`
(`test/yin/repl_core_test.cljc:121`), once for `:semantic` and once
for `:ast-walker`. The test evaluates `(/ 1 0)` and expects an error. On
JavaScript that expression returns `##Inf` (`actual: "before\n##Inf"`).
This is host arithmetic in the REPL/evaluator path, and neither function
added here is on that path. I did not re-run on a clean tree to prove the
failure predates this change, because `dao/jing.cljc` and its test have
concurrent uncommitted edits I must not stash.

## Tags not cleanly implemented

None. All 18 tags in the §2.3 table are implemented and round-trip. Caveats:
- Address-based sharing inherits the §4.2 encoder block. Addresses are
  computed and carried for this conformance work, and the collision guard
  is only a tripwire. Nothing here lifts the block.
- The §7.4 validator is partial, as listed under reconstruction. A full
  row validator that returns defects with paths (like
  `code/well-formed?`) is a separate unit.

## Boundaries confirmed

- Files edited: only `src/cljc/yin/vm.cljc` and `test/yin/vm_test.cljc`
  (plus this findings file).
- I did not touch `src/cljc/dao/jing.cljc`, `test/dao/jing_test.cljc`,
  `docs/design/yin.vm.code-as-tuples.md`, `ast_walker.cljc`,
  `linearize.cljc`, `semantic.cljc`, or `code.cljc`. The pre-existing
  modifications shown in `git status` for the jing and doc files belong to
  the concurrent work.
- **`bb test:cljd` was NOT run**, and neither was any cljd build: the
  ClojureDart generated output is owned by a concurrent process.
  Cross-host notes for whoever verifies cljd later:
  - `with-meta` on symbols is used.
  - Tests catch `Object` on cljd, following the existing `error-message`
    helper.
- No side-table emission, S1/S2 bundling, or AST indexer was implemented.
- Nothing staged or committed, and no subagents used.
