Created-GMT: 2026-09-15 21:26:40 GMT
Created-Local: 2026-09-16 04:26:40 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 206534e8-0432-4941-8631-0212c8f59132
# Findings: ast->semantic-bytecode r2 (4 blocking review findings fixed)
Role: VM Runtime
Implementer: claude-opus-5 | Status: complete (uncommitted working-tree diff)

## Method

I reproduced each finding before fixing it. The regression tests below
went into `test/yin/vm_test.cljc` first and were run against the r1
code with `clojure -M:test -n yin.vm-test`. That run gave
**20 tests, 150 assertions, 13 failures**: every new assertion that
describes the fixed behavior failed, and the r1 tests still passed. The
20th test was a temporary probe deftest that printed `dao.jing` hashing
facts (Finding 3). I removed it after the run.

I used tests rather than a standalone script because running an ad-hoc
`clojure -M script.clj` needed approval in this session.
`target/r2-probe.clj` was written for that purpose, never run, and has
been deleted.

## Finding 1: `:lambda :params` kind is `syms`

**Reproduction (r1):**
- The grammar entry was `[:params :data]`.
- A correctly hashed `[:lambda nil body-id]` reconstructed without error
  (`error-rule` returned nil).
- So did `[:lambda (x) body-id]` and `[:lambda [:x] body-id]`.

**Diff (`src/cljc/yin/vm.cljc`):**
- `:576`: `:lambda [[:params :syms] [:body :node]]`.
- `:678`: projection (`slot-value`) gets a general case,
  `:syms (mapv strip-symbol-meta v)`. The special case
  `(if (= [tag field] [:lambda :params]) ...)` is gone.
- `:726-730`: reconstruction (`child`) gets
  `:syms (if (and (vector? v) (every? symbol? v)) v (defect :slot-kind ...))`.

**Tests:** `semantic-bytecode-lambda-params-are-syms` (`v2_test.cljc:320`)
checks four things:
- the grammar entry;
- `:slot-kind` for nil params, for a list of params, and for a vector of
  keywords;
- that a list of params carrying reader metadata projects to a vector with
  no metadata;
- that the result has the same root as `'[x]`.

## Finding 2: sharing silently overwrote retained metadata

**Reproduction (r1):** an `:if` with literal consequent
`^{:meaning 1} x` and alternate `^{:meaning 2} x` projected without error.
The same happened with both symbols nested in vectors. The probe confirmed
the cause: `dao.jing/segment-key` gives the same address for `[x]` and
`[^{:meaning 1} x]`, because it does not hash scalar metadata.

**Diff:**
- `:632-645`: new private `same-meta?`. For two `=` values it compares
  `(meta ...)` at every depth:
  - map entries are matched by key via `find`;
  - set elements are matched via an `=` scan of the other set;
  - sequentials are compared pairwise.

  Order-independent matching matters: two `=` maps that differ only in
  entry order do not falsely collide, which a `pr-str` comparison would get
  wrong.
- `:692-693`: the guard is now
  `(when (and prior (not (and (= prior row) (same-meta? prior row)))) (throw ...))`.
- `:660-667`: the docstring for `ast->semantic-bytecode` documents the
  inherited `dao.jing` limitation. Scalar metadata is not hashed, so such
  pairs share an address. This code throws instead of silently merging
  them. The throw goes away once `dao.jing`'s encoding covers scalar
  metadata.

**Tests:** `semantic-bytecode-sharing-refuses-to-merge-distinct-metadata`
(`v2_test.cljc:355`) checks that:
- both reproductions now throw `"Semantic bytecode address collision"`;
- equal metadata still shares one row (3 rows) and keeps `{:meaning 1}` on
  both reconstructed occurrences, an assertion that looks at metadata
  rather than `=`;
- `:variable` names with different metadata do not collide, because
  `sym` slots are stripped.

## Finding 3: reader provenance inside row bodies

**Empirical check of `dao.jing` (the prompt's question):** the probe
printed, on the JVM, against the current working-tree `jing.cljc`:
```
PROBE symbol meta changes hash? false
PROBE top symbol reader meta changes hash? false
PROBE nested vector reader meta changes hash? false
PROBE nested vector semantic meta changes hash? true
PROBE pre-fix nested symbol meta in row: {:line 77, :column 9}
```
- Symbol metadata, whether reader position or semantic, never reaches the
  hash.
- On collections, `order-normalize` drops `:line`/`:column`/... at every
  depth and hashes any other metadata.
- Either way the *address* is never affected by reader positions. What the
  reviewer found is that the positions still sit inside the in-memory row
  body.

**So the projection can strip them itself, and does.** Removing position
keys cannot change any address, and it keeps provenance out of the row
body as §2.5 requires. It is not the same class as Finding 2.

**Reproduction (r1):** the metadata `{:line 77 :column 9 :end-line 77 :end-column 10}`
survived in the row body for:
- a top-level literal symbol;
- a symbol nested in a vector;
- a vector nested in a vector;
- a map value;
- a `:vm/store-put` key.

Semantic `:meaning` metadata came back mixed with the position keys.

**Diff:**
- `:609-629`: new private `strip-reader-positions`. It walks a `data`/`key`
  payload, rebuilding maps, sets, and vectors with `(into (empty x) ...)`
  and lists/seqs with `(apply list ...)`, and passing records through for
  `dao.jing` to reject as before. At every depth it dissocs
  `:line :column :end-line :end-column` from metadata, keeps any other
  keys, and sets metadata to nil when nothing is left.
- `:680`: `(:data :key) (strip-reader-positions v)` in `slot-value`.

**Tests:** `semantic-bytecode-strips-reader-positions-inside-payloads`
(`v2_test.cljc:383`) checks that:
- all five reproductions now have nil metadata;
- `:meaning 1` next to position keys survives as exactly `{:meaning 1}`;
- a payload `[(1 2) #{3} {:a [4]}]` keeps its value and its list and set
  types.

## Finding 4: malformed rows accepted, then changed on re-projection

**Reproduction (r1):** a correctly hashed `:application` row with
`:not-a-bool` in the `tail?` slot reconstructed without error, so
re-projection would have minted `true` and a different address. The nil
params case is covered under Finding 1.

**Diff:** `:731-735`, in `child`:
`:bool (if (or (true? v) (false? v)) v (defect :slot-kind ...))`. A nil
`tail?` is still caught earlier as `:saturation`.

**Tests:** `semantic-bytecode-bool-slot-is-validated` (`v2_test.cljc:346`).
The shared helper `rerooted` (`:313`) adds a correctly hashed body as the
new root.

## Finding 5 (non-blocking): corpus count

The reviewer is right: `semantic-bytecode-corpus` has **28** ASTs, not 27.
It breaks down as:
- 6 literals, 1 variable, 1 global;
- 3 lambdas, 3 applications, 1 if;
- 2 `dao.stream.apply/call`, 2 gensym, 3 store-get, 1 store-put;
- 1 current-continuation, 1 park, 1 resume;
- 1 stream chain and 1 `stream/make`.

The r1 findings file is left as written (historical); this file is the
correction. I added no hand-authored grammar fixtures that don't depend on
the dictionary; that stays a follow-up.

## Results after the fixes

JVM, `clojure -M:test -n yin.vm-test`:
```
Testing yin.vm-test

Ran 19 tests containing 150 assertions.
0 failures, 0 errors.
```
That is the 15 r1 tests plus 4 new ones, all passing.

Node, `clj -M:cljs -m shadow.cljs.devtools.cli compile test` (log
`target/v2-cljs-test-r2.log`):
```
Ran 1358 tests containing 35490 assertions.
2 failures, 0 errors.
```
`yin.vm-test` reports no failures. The 2 failures are the same ones
seen in r1, in `yin.repl.core-test/a-failed-input-is-consumed-exactly-once`
(`test/yin/repl_core_test.cljc:121`): the test expects `(/ 1 0)` to
throw, but JavaScript returns `##Inf`. They are unrelated to this unit.

## Still out of scope (reviewer items not in the 4 blocking fixes)

- Unreachable rows are still accepted and dropped by reconstruction
  (`:root-reachable` is not implemented).
- `:variable-scope`, the renamed rule, is not implemented, and neither is
  `plain-data?` checking of `data`/`key`. `sym` slots are not checked
  either; a non-symbol `sym` re-projects unchanged, so it does not alter
  an address.
- Hashing happens before slot validation, so a payload the encoder rejects
  (e.g. a record) throws `dao.jing`'s ex-info without `:rule`/`:id`.

All of these belong to the separate full §7.4 validator.

## Boundaries

- Edited only `src/cljc/yin/vm.cljc` and `test/yin/vm_test.cljc`,
  plus this file, the deleted scratch probe, and test logs under `target/`.
- I did not touch `dao.jing.cljc`, `dao.jing.md`, or the code-as-tuples doc.
- **`bb test:cljd` NOT run.** I could not check the CLJD lane: the `ps`
  inspection needed approval in this session, so I skipped it as the prompt
  instructs. For whoever runs cljd later: the new code uses `record?`,
  `empty`, `find`, `not-empty`, and `with-meta` on symbols and collections.
- Nothing staged or committed.
