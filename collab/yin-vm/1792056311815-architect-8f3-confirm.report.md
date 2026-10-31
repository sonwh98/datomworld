Completed-GMT: 2026-10-15 09:25:11 GMT
Completed-Local: 2026-10-15 16:25:11 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: unavailable (not exposed by this runtime)

# 8f-3 confirm: post-sign-off fix rounds 1 (Architect P3 1-5) and 2 (GLM P3-1, P3-2)

Scope: the two codex rounds on top of the signed-off 8f-3 tree (`1792056067234-engineer-8f3b.report.md`, `1792056156494-engineer-8f3c.report.md`). Read-only; I re-ran the four touched test namespaces.

## 1. Which files changed after the sign-off

`find src test docs -newer collab/1792055212830-architect-8f3-review.report.md` lists exactly the six files the two reports name and nothing else:

```
src/cljc/yin/vm.cljc
src/cljc/yin/vm/ucf.cljc
test/yin/vm/semantic_register/effects_test.cljc
test/yin/vm/semantic_register/linearize_test.cljc
test/yin/vm/semantic_register/vm_test.cljc
test/yin/vm/semantic_stream_observer_test.cljc
```

No docs, no `src/yang`, no `test_utils.cljc`, nothing staged. The codex SHA-snapshot claim is consistent with mtimes; I have no pre-round byte snapshot, so the "every other hunk byte-identical" claim is checked only by mtime plus reading the hunks below.

## 2. Hunk-by-hunk

| P3 | Hunk | Says what was asked? | Assertion real? |
|---|---|---|---|
| A-1 | `src/cljc/yin/vm.cljc:974-980` `loaded-code-floor` docstring now reads "(`semantic-register/load-ast` for projected AST batches, `semantic-register/load-vector` for canonical instruction vectors)". | Yes, the suggested text verbatim plus "instruction". No deleted var named any more. | n/a (prose) |
| A-2 | `test/yin/vm/semantic_stream_observer_test.cljc:284-290`: new `testing` block appends `{:yin/batch [(vm/ast->semantic-bytecode (lit 1))] :yin/root 2}` to `(:rows session)` and asserts `(= :batch-envelope (:rule (throws-ex-data #(tu/run-encoder-session session))))`. | Yes. Appending to `:rows` bypasses `encoder/project-envelope` (`encoder.cljc:129-137`, which only sees the program medium), so the only `:batch-envelope` thrower on this path is the inline consumer loader at `test_utils.cljc:171-175`, which is the branch P3 #2 said was unexercised. | Real: `throws-ex-data` returns nil when nothing throws, so a loader that accepted root 2 (or threw with another `:rule`) fails the `=`. |
| A-3 | `test/yin/vm/semantic_register/effects_test.cljc:158-162` `blocked-put`: `(is (= entry (edn/read-string (pr-str entry))))`, then the wake is driven from `(assoc blocked :wait-set [(edn/read-string (pr-str entry))])` instead of the in-memory `blocked`; the existing window and `:second` assertions stay. | Yes, exactly the two lines proposed; the original in-memory wake was replaced rather than duplicated, which is the right minimal shape (the revived entry is the stronger of the two). | Real: equality fails if the writer entry carries anything non-EDN (a stream handle, a fn, a record without a reader); the wake fails if `check-wait-set`/`register-restore` need anything the revived entry lost. |
| A-4 | `src/cljc/yin/vm/ucf.cljc:7-8` `code-address` docstring: "The dao.jing segment address of a canonical vector. UCF mints no second addressing scheme." | Yes, the old one-liner minus the `:yin.code/hash` name. | n/a |
| A-5 | `linearize_test.cljc`: 3 lines removed, no additions. | **Partly.** The file still has three runs of three blank lines, at lines 230-232, 326-328 and 382-384 (before `lower-rows-evaluation-order-matches-the-w…`, `path-row`, `unsupported-nodes-are-rejected-naming-the…`). HEAD has none, so this is 8f-3's own residue; it looks like codex shortened four-blank runs to three rather than three to two. Style only, kondo-silent. | n/a |
| G-1 | `test/yin/vm/semantic_register/vm_test.cljc:78,86-87` `attach-load-ast-and-reset`: `reset` is now `(vm/reset (vm/run b))`; adds `(is (= {:segment (:program b) :pc 0} (vm/control reset)))` and `(is (= 42 (vm/value (vm/run reset))))`. | Yes: control restored to pc 0 of the program segment, and a reset machine re-runs to its value. Resetting a *run* (halted) machine rather than the mid-step `b` is the stronger choice: it proves reset revives a finished machine, which is the old `reset-test` fact. | Real: `b`'s program is the `:literal` golden (`[[:const 0 42] [:halt 0]]`), so the value is 42 only if control was actually put back at pc 0 and `halted?` cleared; a reset that left `control` at the halt pc or `halted?` true returns nil. The existing `(nil? (:value reset))` already rules out a stale value. |
| G-2 | `test/yin/vm/semantic_register/linearize_test.cljc:114` `shared-occurrences-expand-positionally`: `(is (nil? (nth (first provenance) 1)))` on `(linearize/project-rows bc)` with no origin. | Yes. Provenance rows are `[pc origin root path]` (`linearize.cljc:194`), so slot 1 is the origin. | Real: an origin-less projection that minted a default origin, or shifted the row shape, fails it. Placed in the one test that already projects without an origin; no new fixture. |

Nothing else changed in these hunks: the `:refer` widening in `linearize_test` (`def! if-node`) belongs to the base 8f-3 diff already signed off, not to these rounds.

## 3. Verification

Re-run here, JVM, foreground:

```
clojure -M:test -e :slow -n yin.vm.semantic-stream-observer-test -n yin.vm.semantic-register.effects-test -n yin.vm.semantic-register.vm-test -n yin.vm.semantic-register.linearize-test
Ran 102 tests containing 1615 assertions. 0 failures, 0 errors.
```

Codex's kondo runs (0/0 on the six files) and `git diff --check` not repeated. Node and Dart lanes remain unrun for the whole 8f-3 tree, as in the sign-off: the orchestrator's three-host lane before commit still applies. The two rounds add nothing host-specific (EDN round trip of plain maps, keyword/vector equality).

## 4. Findings

- P1/P2: none.
- P3 residue: A-5 is not finished. Three triple-blank runs remain in `test/yin/vm/semantic_register/linearize_test.cljc` (230-232, 326-328, 382-384). Delete one blank line at each, or ledger it for 8g; it does not gate the commit.
- GLM P3-3 (writer EDN composite) is closed by A-3; GLM P3-4 (`malformed/malformed-vectors` dead fixture) was explicitly left untouched per the round-2 brief and stays ledgered.

SIGN-OFF
