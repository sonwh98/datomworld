# D1 continuation r3: durable-store and replay pilot

Status: **partial; corpus record and companion not produced**. This continues r2 `1794422563000-engineer-d1-correspondence-r2.gpt-6.1-terra.findings.md`, which remains unchanged. No verifier, bounds declaration, tagged execution, or commit. This is the worktree mirror; it has not been promoted to canonical collab. Nothing here establishes reliance.

## Durable retention test

`test/yang/correspondence_test.cljc` now writes the complete checked-in version-1 evidence plus a *synthetic* recipe and correspondence through the existing `yin.repl.store/open` file store. It closes and reopens the store, then compares both decoded values and the actual stored canonical byte arrays, by address. The temporary store is deleted after the test. This demonstrates the file backend's close/reopen behavior; it is not retention or publication of a real corpus record. The evidence file's SHA-256 remains `e435ac4072ba0a3c278fbe91918d22184b43f0a8d6c1e397c09f68dd629beb75`.

## Live admitted compilation and the order-sensitive replay finding

`kernel-compile` has an opt-in input-capture argument; ordinary callers do not retain extra copies. A static pilot captures the *actual* fixture, forms, call, assembled program, compiler options, and source-origin coordinates from the live `:beta-eq-false` compilation. It stores the input, the actual validated row closure via `vm/materialize-tree!`, and C1 metadata in a file store, closes and reopens it, and fetches them. No guest lane runs and no new historical origin is assigned.

A material replay hazard was found before asserting a recipe. Directly recompiling the decoded Jing `:program` produces a different root even though the decoded value compares equal: in the observed run original root `:segment/blake3-174be253477fc45e9cfc97b2feb271d76de682049c1c7ba9ad22f80d8c44bb4b`, decoded replay root `:segment/blake3-540da09d07af75a62b39c0d315a8cdfc8d3b947de6366975da2be34780c7bd45`. A dynamic map at program path `[48 4]` changed iteration order across the canonical codec; `yang.clojure/compile-constructed-collection` lowers the iteration order into operands. This is not a Jing corruption: the data compares equal, but collection iteration order is observable by this compiler.

The opt-in capture also records the program's printed EDN with metadata, before storage. In the single pilot, reading that text after reopening yields `dao.jing.cbor/content=` to the captured original and recompiles to the original root; recompiling the original in-memory input does too. This is a **single-fixture feasibility result**, not a corpus-wide guarantee that EDN printing/reading preserves all source shapes, metadata, ordering or semantics. The original direct-data replay failure is a real limitation; no record is declared reconstructable solely because values compare equal.

## Unresolved items before a corpus record

- The exact reconstruction profile and runnable interpreter have not been implemented and pinned. In particular, the ordering-preserving representation needs an explicit specified contract and per-input replay/refusal gate across all corpus fixtures; the one pilot is insufficient. The exact code-producing frontend/compiler/dependency and expansion environment/attempt closure still needs retention and a determination of which expansion events actually occur; do not invent an expansion record or assume a commit string is a retrievable closure.
- The corpus spans separate admissions and repeated integer site IDs. Its durable association from each fixture/compilation to its actual retained admission, root, and evidence-local site scope still needs a concrete, independently checkable representation and complete per-fixture coverage. Existing producer-side `cc3-oracle-binding` is a useful starting point but not reconstruction verification.
- A reproducible durable-store location and retention/discovery owner for the committed companion and all referenced content are not pinned. A path under `target` deleted by a test is not an available closure for a companion. No placeholder companion or fake record was written.
- r2's row-lane restriction and uncompleted inventory remain in force. The unchanged site table remains historical oracle evidence, not a source of historical admissions.

## Tests

Red first: the file-backed test initially failed to compile because its test fixture writer did not yet exist; the live-capture test failed because the compilation had no retained input. With direct decoded input, replay then failed with the root mismatch above; the single-fixture ordered EDN pilot passed after capturing the input text. The live static pilot passed 1 test / 9 assertions, including close/reopen and root replay; no tagged lane was run. The combined correspondence and call-site battery passed 47 tests / 614 assertions. The earlier static table/binding test passed 1 test / 12 assertions. clj-kondo 2026.08.04 reported 0 errors and 0 warnings on all three code/test files. Full oracle suite was not run.
