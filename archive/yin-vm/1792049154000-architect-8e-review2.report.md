Completed-GMT: 2026-10-15 07:25:54 GMT
Completed-Local: 2026-10-15 14:25:54 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending (not exposed inside this -p session; orchestrator fills from the CLI run)

# 8e Architect review, round 2: final review of the uncommitted diff after fix rounds 1 and 2

Worktree /Users/sto/workspace/datomworld-srvm-p8e, branch srvm-phase8e, codex's uncommitted diff (52 tracked paths: 43 modified, 9 deleted; 1142 insertions, 5054 deletions). Read-only except this report. I re-ran kondo on the five files changed since round 1 and `clojure -M:test -n yin.vm-test` on the JVM; both green, counts below.

Verdict in one line: the diff implements the round-2 spec exactly, P2-1 is closed, scope is clean, the ledger is intact, and nothing new appeared. **SIGN-OFF**, conditional only on the gates the orchestrator owns (loopback outside the sandbox; Node and Dart lanes on `yin.vm-test`).

---

## 1. vm.cljc and vm_test.cljc against the round-2 spec

| Spec item | Found in the diff | Match |
|---|---|---|
| Leaf rule `:else (cbor/content= a b)` | src/cljc/yin/vm.cljc `same-content?`, final `cond` branch | exact |
| No `number?` gate, no `==`, no host conditional, no unary minus | none present | yes |
| Rest of `same-content?` kept: metadata `=` at every depth with recursion, map/set/sequential count checks, `contains?` before `find`, type check on `b` | all present | yes |
| Guard `(when (and prior (not (same-content? prior row))) (throw ...))`, message and `{:id :row :prior}` ex-data unchanged | vm.cljc guard | exact |
| `same-content?` docstring: "comparing leaves by `dao.jing.cbor/content=`, the encoder's own kind-strict equality, so canonical NaNs are equal and `0.0`/`-0.0` are not"; NaN-key/set scope sentence and comparator-set boundary kept | present | content exact; see P3-1 for the sentence join |
| `ast->semantic-bytecode`: "Known limitation" paragraph deleted, replacement paragraph inserted | present | verbatim |
| Test require `[dao.jing.cbor :as cbor]` after `dao.jing` | test/yin/vm_test.cljc:7 | yes |
| T1 slot assertion `(is (cbor/content= ##NaN value))`, pinned id `…88491ea0…e55b7` unchanged, NaNs via `(* 1.0 ##NaN)` / `(* -1.0 ##NaN)` | T1 | exact |
| T2 unchanged | T2 | yes |
| T3 `semantic-bytecode-scalar-metadata-collision` deleted, `semantic-bytecode-symbol-metadata-is-address-significant` added | T3 | verbatim to the spec body |

`cbor` is already the alias for `dao.jing.cbor` in vm.cljc (line 28), so no new production require was introduced.

Mutant gate: codex's mutant.log shows the collision error at the pinned id with `(not (= prior row))` in place, and mutant-restoration.txt records byte equality after restoration. The working tree now reads `(not (same-content? prior row))` with no `(= prior row)` left, which is the restored state. I could not run `shasum` in this sandbox to re-derive the recorded SHA-256; the diff content is sufficient evidence.

## 2. Round-1 findings

* **P1-1** NaN collision: closed by the round-2 leaf rule. `yin.vm-test` 36 tests, 487 assertions, 0 failures, 0 errors (my run and codex's). `int-ops-test -e :slow` 13 tests, 49 assertions, green per fix2 (T5). `float-address-test` green inside the 35-namespace run (T4).
* **P1-2** final gates: the 35-namespace changed command (631 tests, 13,155 assertions, 0 failures, 0 errors) and the whole `yin.vm.*` quick tier (104 namespaces, 1,874 tests, 32,330 assertions, 0 failures, 1 error) were rerun after all fixes. The one error is `head-board-test/the-board-crosses-a-real-loopback-socket`, `SocketException: Operation not permitted` at `Net.bind0`: the sandbox denial, unchanged code. Orchestrator reruns it where binding is allowed.
* **P2-1** `tu/resolved-of` and `last-resolved`: deleted together with the `debruijn-resolve` require in test/yin/vm/test_utils.cljc. `rg` over src and test finds no remaining reference to either, nor to `same-meta?`. Closed.
* **P2-2, P2-3**: doc/known-gaps items for the orchestrator, unchanged; no code expected.
* **P3-2** stale lowerer prose in debruijn_register_code_test: rewritten ("Register contract facts retained through projected semantic vectors", "Resolution and stack lowering preserve the semantic vector's address order"). Done.
* **P3-3** trailing newline: `git diff` shows no "No newline at end of file" for any of the five files changed since round 1. Done.
* **P3-5** blank lines in linker_test around the removed lifts: gone. Done.
* **P3-1, P3-4, P3-6**: skipped, as permitted (optional).

## 3. Scope

* Under test/yang exactly the ten authorised C4 files: e2e_c2_test, e2e_test, float_address_test, int_contract_test, int_heap_test, int_literal_test, int_ops_test, linked_harness, prelude_parity_test, safepoint_test.
* `git diff --name-only` over docs, src/yang, src/cljc/yin/vm/{semantic,linearize,code}.cljc: empty.
* Nothing staged (`git diff --cached --stat` empty). `git diff --check` clean.
* The only hit for the deleted namespaces is the forbidden-namespace regex in production_pipeline_test.clj:12, a guard, not a dependency (round-1 P3-6).

## 4. Ledger since round 1

The only files that changed between round 1 and now are vm.cljc, vm_test.cljc, test_utils.cljc, debruijn_register_code_test.cljc and linker_test.cljc (fix1 report's SHA audit; fix2 baseline.json says every other path is byte-identical or still deleted, and `git status` still shows the same nine deletions). None of the five removes a deftest: vm_test gains three; debruijn_register_code_test and linker_test lose only a dead helper and the two lift legs already accounted for in round 1. Spot count of ledger "fact lives here now" names: 10 of 10 in debruijn_code_test, 20 of 20 in debruijn_register_code_test, all 20 named derive_test deftests present. The 84-row ledger stands as reviewed in round 1.

## 5. New in the diff since round 1

Only the items in sections 1 and 2. I read all five changed files' hunks in full; nothing outside the two rulings.

---

## Findings

**P1**: none.

**P2**: none.

**P3**

* **P3-1 Docstring sentence join** (src/cljc/yin/vm.cljc, `same-content?`). The inserted clause ends "…`0.0`/`-0.0` are not,\n and carry `=` metadata at every depth…", so the sentence reads "comparing leaves by …, and carry = metadata". Grammatical only; a future docstring pass can rephrase as "True when `a` and `b` have equal content and equal metadata: leaves compare by …; metadata compares with `=` at every depth…". Not blocking; vm.cljc is not on the docs freeze but this is not worth a third round.
* **P3-2 Known-gaps entries still owed by the orchestrator**: round-1 P2-2 (register design doc §2/§2.1 still describes resolved-tuple derivation) and P2-3 (native binder refusal now collapses to the projection's `:slot-kind` rule). Record in docs/agents/known-gaps.md at commit time.

## Gates remaining (orchestrator)

1. `head-board-test` loopback case outside the sandbox.
2. Node and Dart lanes on `yin.vm-test` (T1 pinned id and the new T3 are the cross-host claims; the leaf rule itself is already carried cross-host by `dao.jing.cbor-content-equality-test`).
3. Commit per phase-8 rules once 1 and 2 are green; collab/ stays uncommitted.

## Invariants

A is the only code identity (the guard now defers to the encoder's own equality); tree always available (untouched); derive, don't persist (no new row structure); minimal diff (one leaf expression, two docstrings, one test swapped, one require, one dead helper removed). Native kernels remain backends; no fragment widened.

SIGN-OFF
