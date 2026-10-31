Completed-GMT: 2026-10-15 22:59:09 GMT
Completed-Local: 2026-10-16 05:59:09 +0700
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending (harness-assigned)

# Architect confirm: follow-up-1 round 2 (uncommitted, srvm-followup-1 @ 9ab9e792, 23 files)

Role: Architect. Read-only except this report.

Inputs read: the brief, the engineer round-2 report (`collab/1792104943474-engineer-followup-2.report.md`), my round-1 review, the full `git diff` of all 23 files and `git diff -w` of `parity_test.cljc` / `repl_test.cljc`, `dao.jing.cbor` (`equiv`, `collapse?`, `identifier-key`, `built`, the identifier encoder at :1100), `ucf/rows.cljc` (`emit-node`, `emit`, `emit-numbered`, `check-abstract!`, `decode*`, `outcome!`, the lower's `row`/`keyed` builders), `malformed_rows.cljc`, its consumers, and `rows_test.cljc` helpers.

Verification I ran (JVM only): `clojure -M:test -e :slow -n yin.vm.ucf.rows-test -n yin.vm.semantic.safepoint-harness-test -n yin.vm.semantic.parity-test -n yin.vm.semantic.linearize-test -n yin.repl-test -n yin.vm-test` → 182 tests, 4057 assertions, 0 failures, 0 errors, exit 0; the one printed skip is `SKIP host-collapse: slash-crossed identifiers remain distinct on JVM`. `git diff --check` clean. `cljstyle check` needed an approval I did not have; I rely on the engineer's run plus `git diff -w` (below).

## Verdict in one paragraph

All seven required steps landed as specified, the three P3 fixes landed, the cljstyle pass touched nothing beyond whitespace, and the ledger wording is exact. The lower's re-emit-on-refusal is acceptable (it mirrors `decode*`'s own root recovery at :957 and runs only on the failure branch). SIGN-OFF, with the commit split and the Node/Dart discovery strings below, and one non-blocking P3 observation for the ledger.

## P1 — the seven steps against the diff

1. **cbor visibility only.** `git diff src/cljc/dao/jing/cbor.cljc` is one hunk: `(defn- collapse?` → `(defn collapse?`, docstring and body untouched. No byte-level change, so descriptors, fixtures and the conformance digest are unaffected. ✔
2. **`decode*` :931-935.** The canonical-bytes clause now stands alone and still refuses `:duplicate-key` at `[id]`; the host-`distinct` clause is replaced by `(when (cbor/collapse? keys) (bad! :equality-collapse [id]))`. The kind set is unchanged (`:map :bindings :store :parked-table :window :cursor-cells :set`), so bindings/store/window keys get the `equiv` rule too. Order matters and is right: this row-level loop runs before `expand`/`check-abstract!`, so the published path is `[id]`, not the abstract path. ✔
3. **`check-abstract!` :811.** `(when (cbor/collapse? decoded) (bad! :equality-collapse path))` replaces the host-`distinct` check; :783 shows no diff, i.e. the leaf is back to `[:literal x]`. The existing expanded vector/list-key assertion (`rows_test.cljc:278`) now names `:equality-collapse`. Since `emit` → `emit-node` does not run `check-abstract!`, "emit refuses" in the ledger wording means the lift path (`lift-task*` → `emit`) plus `decode*`'s abstract check on the expanded tree; the literal twin is refused by `cbor/encode` itself. That is the same coverage I asked for. ✔
4. **Lower guard** (`rows.cljc:2117-2131`). `built` is called for `:set` with `(count ss)` and for `:map` with `(quot (count ss) 2)`, refuses `:yin.k/undecodable` / `:host-collapse` with `:yin.k/path [row-id]` through the lower's existing `outcome!` (the same status/kind/path vocabulary as `:pending-try`, `:reserved-name` etc.), and returns `coll` unchanged otherwise. **Re-emit judgment: acceptable.** The decoded node carries no id (`expand` builds `(node kind slots)`), so the only ways to name the row are to thread ids through every node or to re-hash; `decode*` already re-hashes for the same reason (`(emit-node task {} {})` at :957). Row ids are content addresses and the lower's nodes keep the wire's cursor-cell ids (`value` maps them through `cell-keys` only at use), so `(emit-node n {} {})` reproduces the wire id exactly; nothing is attached or executed on that branch; the cost is paid only on refusal. The refusal does carry the row id only, which is what the test pins. **JS-only test** `collection-row-lower-refuses-host-collapse`: the premise holds in code: `identifier-key` is `[kind ns name]`, the encoder writes `[ns name]` (cbor :1100-1103), so `(keyword "a/b" "c")` and `(keyword "a" "b/c")` survive encode/decode and `equiv` as distinct, while CLJS keyword `=` compares the joined fqn `a/b/c` and merges them in `into {}`/`into #{}`. Decode passes, lower refuses at the row id. JVM/Dart print the skip. ✔ (Node execution is the orchestrator's, as planned.)
5. **Separate collection, confirmed.** `malformed-row-sets` is the Universal AST row grammar (`{:root :rows}` values keyed by `jing/segment-key`, rules `:tag :arity :slot-kind :saturation :id-resolves :acyclic :root-reachable`), consumed by `vm_test.cljc:432,540`, `semantic_stream_observer_test.cljc:322` and `linearize_test.cljc:176`; none of those readers decode a UCF carrier, so putting a UCF `:equality-collapse` wire there would have broken them. The new `malformed-ucf-row-sets` (`{name [expected-refusal carrier]}`) is the right home; its one entry is the `:map` row with keys `1` and `(cbor/float64 1.0)`, expected verdict exact (`:yin.k/undecodable` / `:equality-collapse` / `[id]`). The §7.2 part-3 same-refusal conformance for the new rule is covered by the new consumer `malformed-ucf-rows-refuse-the-published-rule` (`rows_test.cljc:1344`), which decodes every published UCF carrier and compares the full `verdict` on every host. The existing AST fixtures and readers are byte-for-byte unchanged apart from the namespace docstring and the dead `malformed-vectors` deletion. ✔ One note for the orchestrator's Dart lane: `yin.vm.malformed-rows` now requires `yin.vm.ucf.rows` at load, so `vm-test`, `semantic-stream-observer-test` and `linearize-test` transitively load the codec; no cycle (rows.cljc requires no test namespace), already green on the JVM.
6. **Replacement test** `collection-row-keys-refuse-equality-collapse`: both pairs (signed zero via `float64-from-bits`, 1 / `(cbor/float64 1.0)`) × `:map`/`:set`, each asserting `content=` keeps them apart, kind `:equality-collapse`, and path equal to the row id read from the wire (12 assertions); the JVM literal twin asserts `::cbor/refusal :equality-collapse` from `rows/emit` of `[:lit {1 :a (cbor/float64 1.0) :b}]` (13th); JS/Dart print the requested skip. The mutant claim stands as I specified: reverting both checks to host `distinct` / `:duplicate-key` admits 1/1.0 on the JVM, misnames signed-zero as `:duplicate-key` on every host, and on JS the Float64 carriers pass host `distinct` so no refusal at all; the engineer executed it on the JVM (6 failures) and the Node/Dart deaths follow by rule name from the same source. ✔
7. **Ledger wording.** Row 25 and row 21 match my round-1 text character for character (row 21 keeps my trailing period). Every other row is as round 1 left it. ✔

## P2 — P3 fixes and the cljstyle pass

- UCF §7.11 clause 10 now cites "2026-10-14 14:30 +07 handoff and 2026-10-16 19:30 +07 landing" and no longer the 2026-10-15 continuation. ✔
- `linker.md:31-32` and :243-245 now say the carrier refuses `:yin.k/unsatisfied` with `:yin.k/missing {:yin.k/trees ...}` "lift against the source index, lower at the dependency check"; the quoted `:tree-unavailable` in `derivations.md` is untouched. ✔
- `linearize_test.cljc:286` points to `yin.vm.semantic.derivations.md §6.3 (hand-derived goldens, row 6)`. ✔
- `parity_test.cljc`: `git diff -w` is the alias removal and the `:refer` list only; continuation columns now sit under `app`/`lam`; every assertion and expected value unchanged. ✔
- `repl_test.cljc`: `git diff -w` shows one line, the §8 → "§10 phase 8" citation; the second hunk (:718) is a pre-existing over-indented continuation of `evaluate`'s vector, whitespace only. **Acceptable**: the file is already in the docs commit for the citation, the change is whitespace, and leaving a known cljstyle violation in a file we touch would fail the next `cljstyle check`. Mention it in the docs commit body.

The other round-1 diffs (linker.dht.md, vm.cljc, linker.cljc, layout.cljc, scan.cljc, checkpoint.cljc, the two benchmark notes, the cljd/cljs/bench citations, continuation_handoff) are unchanged from what I accepted in round 1.

## P3 — one observation, non-blocking (ledger candidate, not this slice)

The lower's `keyed` builder (`rows.cljc:2135-2137`) turns `:bindings`, `:store`, `:window` and cursor-cell census rows into host maps with no `built`-style count guard. Decode now refuses `equiv` collapses for those kinds (step 2), so numbers are covered; the one residual is the same JS identifier merge for slash-crossed *symbols* in a hand-crafted carrier (`(symbol "a/b" "c")` vs `(symbol "a" "b/c")` as two binding names). The reader cannot produce such symbols and `:syms` validation (`:slot-kind`) does not split on `/`, so this is crafted-carrier only. I did not require it in round 1 and do not now; suggest a P3 ledger row "lower `keyed` builder has no host-collapse guard for identifier keys on JS" for a later prose-free slice, at the orchestrator's discretion.

## Commit split (exact)

**Commit A, code, `fix(yin.vm.ucf): map/set row keys refuse :equality-collapse under cbor/equiv on every host; lower refuses :host-collapse (gap-list row 25)`:**
- `src/cljc/dao/jing/cbor.cljc` (whole diff)
- `src/cljc/yin/vm/ucf/rows.cljc` (whole diff)
- `test/yin/vm/ucf/rows_test.cljc` (whole diff: the `malformed` require, the :278 rule rename, the three new deftests)
- `test/yin/vm/malformed_rows.cljc`: hunk 1 **only the two added requires** (`dao.jing.cbor`, `yin.vm.ucf.rows`) and hunk 2 **only the added `malformed-ucf-row-sets` def**; use `git add -p` and split hunk 2 (the `malformed-vectors` deletion in the same hunk belongs to B). If `-p` cannot split cleanly, it is acceptable to put the entire `malformed_rows.cljc` diff in commit A and say so in the message ("also deletes the dead `malformed-vectors` fixture, gap-list row 26").
- `docs/agents/known-gaps.md`: the row-25 line only (the `decode*` row).

**Commit B, tests, `test(yin.vm.semantic): name the 8d partial-mapping successor tests; delete the dead malformed-vectors fixture (gap-list rows 22, 26)`:**
- `test/yin/vm/semantic/safepoint_harness_test.cljc` (whole diff)
- `test/yin/vm/semantic/parity_test.cljc` (whole diff)
- `test/yin/vm/malformed_rows.cljc`: the namespace docstring edit and the `malformed-vectors` deletion.
- `docs/agents/known-gaps.md`: the row-22 (8d partial mappings) and row-26 (dead fixture) lines.

**Commit C, docs, `docs(yin.vm): retarget v3 citations to yin.vm.semantic.md#history; close binder-refusal row 21 as not applicable; UCF 7.11 clause 10 and linker tree-unavailable wording (gap-list rows 21, 27, 28)`:**
- `docs/design/yin.vm.linker.dht.md`, `docs/design/yin.vm.linker.md`, `docs/design/yin.vm.universal-continuation-format.md`
- `src/cljc/datomworld/demo/continuation_handoff.cljc`, `src/cljc/yin/vm.cljc`, `src/cljc/yin/vm/docs/bytecode-vm-optimization.md`, `src/cljc/yin/vm/docs/jfr_benchmark_analysis.md`, `src/cljc/yin/vm/linker.cljc`, `src/cljc/yin/vm/semantic/layout.cljc`, `src/cljc/yin/vm/semantic/scan.cljc`, `src/cljc/yin/vm/ucf/rows/checkpoint.cljc`, `src/cljd/yin/register_bench_cljd.cljd`, `src/cljs/datomworld/demo/continuation_stream.cljs`, `test/bench/yin_vm_bench.cljc`, `test/yin/repl_test.cljc` (citation + the whitespace hunk, note it), `test/yin/vm/semantic/linearize_test.cljc`
- `docs/agents/known-gaps.md`: the remaining lines (row 21 binder, rows 27/28 citations and historical prose, the new C4 scanner-pin row).

Nothing under `collab/` in any commit. Order A, B, C (B and C are independent of A; A first so the ledger row-25 closure rides with its code). After the three commits the tree must equal the current working tree (`git diff` empty).

## What Node and Dart must show (orchestrator, one lane at a time, before merge)

Discovery strings:
- `Testing yin.vm.ucf.rows-test`
- `Testing yin.vm.semantic.safepoint-harness-test`
- `Testing yin.vm.semantic.parity-test`
- `Testing yin.vm.semantic.linearize-test`
- `Testing yin.vm-test` (consumes the unchanged AST fixtures through the now-heavier `malformed-rows` namespace)

Printed skips, verbatim:
- Node: `SKIP literal equality-collapse twin: JS cannot construct the two-key host map` — and **no** `SKIP host-collapse` line (the lower guard test runs on Node: 4 assertions, map and set).
- Dart: `SKIP literal equality-collapse twin: Dart cannot construct the two-key host map` and `SKIP host-collapse: slash-crossed identifiers remain distinct on Dart`.

Zero failures, zero errors on both. If Node reports a failure in `collection-row-lower-refuses-host-collapse`, that is the one prediction not executed yet (the JS identifier merge at lower); report it back rather than patching, per the Q6 rule.

SIGN-OFF
