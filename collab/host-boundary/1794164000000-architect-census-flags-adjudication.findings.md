Completed-GMT: 2026-11-08 17:25 GMT  
Completed-Local: 2026-11-09 00:25 +0700

**Ruling: sanction the re-record with an F1 correction and an F3 provenance correction.** This adjudication closes F1–F3; implementation verification remains outstanding.

1. **F2 — approve the `yang.php.antlr.` prefix row.** Add `[:deferred-stage "yang.php.antlr."]` to the generator. The PHP grammar adapter, lowering, and prelude family warrants the same deferred-stage classification as Python under §9.3. Per-file classification entries are unnecessary.

   Record this explicitly as a §3.1 amendment in [the design](/Users/sto/workspace/datomworld/docs/design/dao.stream.host-boundary.md:453), and update the generator’s prefix-row documentation. Classification does **not** grant blanket permission for reader arms or effects: list-1 and list-2 remain independently enforced.

2. **F1 — correct the recommendation: `cst.cljc` needs no list-1 entry.** Its closure class is `:deferred-stage`, but its reader conditionals are exempt. The require/import conditionals occur inside `ns`; the two `:start` branches at [cst.cljc:27](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/cst.cljc:27) differ only in `^PhpParser` versus `^js` metadata. Clojure equality ignores that metadata, so the gate’s existing `(apply = arms)` condition returns `:hint-only`. See [exemption](/Users/sto/workspace/datomworld/test/dao/host/gate_test.clj:206).

   Unlike the deleted `.clj` file, `.cljc` receives no automatic whole-file arm. Therefore remove the old entry without adding its replacement. `:deferred-scope :stays` would be appropriate **if a non-exempt stage arm existed**, but none exists here. Adding the proposed entry would fail exact list-1/observed-arm equality.

3. **F3 — confirm the current graph as intentional census state.** `91c5c8cd` unconditionally requires `prelude_p9` and incorporates its definitions, supporting membership on all three lanes. The Node landing supplies JavaScript recognizers, a shared JVM/Node adapter, and explicit frontend installation guards.

   Correct the attribution: **`873c8bcd` first excluded PHP requires on Dart**; `28bdc3d0` preserved that exclusion, added Node, and aligned the install guard. Census the resulting state: `cst.cljc`, `lower`, and `scope` on clj+cljs; `prelude_p9` on all lanes. No frontend rollback is warranted by this census issue.

The sanctioned re-record is:

- Amend §3.1 and the generator prefix row, then regenerate all three EDNs.
- Set **union/gate files 175; clj 163; cljs 161; cljd 159; shared 152**. Update both union assertions and the descriptive census text.
- **List-1: 73 → 72 entries.** Delete `src/clj/yang/php/antlr/cst.clj`; add no `cst.cljc` entry. Retain `lower.cljc` and `quoted.cljc` as `:deferred-scope :stays`. Add none for `builtins`, `prelude`, `prelude_module`, `prelude_p9`, or `scope`.
- **List-2 unchanged; bounded exceptions unchanged: eight files, twelve bare lane rows.**

The orchestrator must verify the exact re-recorded revision remotely:

- Generator `--check` exits successfully, with byte-for-byte agreement across all three EDNs.
- Run only **`clojure -M:test -n dao.host.gate-test`**, requiring zero failures and errors.
- That namespace must establish traversal equality on every lane, the stated totals, all 175 files scanned without errors, exact observed-arm equality with the 72-entry list-1, no category problems, unchanged effect allowances, unchanged exception census, and passing negative fixtures and library checks.

The supplied analysis’s “`cst.cljc` holds a non-exempt arm” conclusion and “list-1 stays 73” proposal are superseded. This was static, read-only adjudication; no files were edited and no tests were run.