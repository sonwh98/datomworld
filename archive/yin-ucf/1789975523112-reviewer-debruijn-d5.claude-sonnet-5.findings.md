Completed-GMT: 2026-09-21 07:28:03 GMT
Completed-Local: 2026-09-21 14:28:03 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 2495507c-4154-4edf-9fe2-bf36cb023729

**NOT READY.** There is one P2 gate defect, and one test that doesn't prove the dedupe contract. Everything else is P3. I edited nothing and ran no suites. I did run a small JVM probe of `project-datoms` from a /tmp copy of the committed `debruijn.cljc`, to confirm the P2 gap.

## P2

**P2-1. The complete-batch gate only checks that some root marker exists (`pipeline.cljc:63-71`, `127-130`).**
- **Contract violated:** §7-D5 and the brief require that a partial graph or missing root rejects, and the docstring at lines 18–19 claims a partial batch "rejects the whole call before either side persists". Only the no-root case does.
- **Failing scenario:** call `persist-compiled!` with `:datoms` holding a complete graph plus trailing `:yin/*` datoms of a next, unfinished graph. That is a partial frame at end. I confirmed on the JVM that `project-datoms` accepts this and returns the same fingerprint as the clean graph. So the whole batch, orphan datom included, goes to the named writer, and the projected side reports `:ok` with a fingerprint.
- **Related case:** two concatenated complete graphs pass the gate too. The named writer gets both graphs, and the projected side only diagnoses `:multiple-roots`, after the named write.
- **Why it matters:** `frame-datoms` in the stream path would raise `:partial-frame` on the first case. The pipeline is a second framing path that is weaker than the D4 one.
- **Not caught by the tests:** `partial-and-rootless-batches-reject-before-persistence` only covers `butlast` (the root is last, so removing it removes the marker) and a filter that removes the marker.
- **Smallest fix:** in `persist-compiled!`, replace `root-marker` with `(try (debruijn/frame-datoms datoms) (catch …))`. `frame-datoms` is public and is committed API.
  - Require exactly one frame.
  - Otherwise return `:named {:outcome :rejected :rule <ex-data :rule>}` and `:projected :not-attempted`.
  - That also rejects malformed datoms before the named write, since `check-datom` runs inside `frame-datoms`.
  - Add two tests for the two cases above, plus one for a retract datom.

**P2-2. `equal-projections-dedupe-to-one-envelope` does not prove the contract (`pipeline_test.cljc:181-195`).**
- **What it asserts:** `(= 1 (count @(:store store)))`. The comment says "the second write found :present: nothing overwritten".
- **Why it's weak:** a store where the second write overwrote the first would still have one entry.
- **Mutations that would still pass:** a pre-write `jing/get` that skips `materialize!`, a raw `put-content-fn` call, or an overwriting put. The brief said "second write :present" and "no pre-write lookup"; neither is observable.
- **Smallest fix:** have `mem-store` log each put result and each get call in one shared vector.
  - After two writes assert `[[:put addr] :inserted [:put addr] :present [:get addr]]`. The `[:get addr]` is `materialize!`'s own read-back after `:present`.
  - Assert there is no `:get` before the first put. That fails any pre-write lookup and any overwriting mutation.

## P3

- **P3-1. Nothing shows the named write comes before the projected write on success (`pipeline_test.cljc:110-136`).** The named-failure test only catches projected-writes-before-named on failure. Add one shared event log to the same instrumented store and writer. Assert the writer call precedes the store put.
- **P3-2. `:outcome` can be nil, or the caller's raw value, on the named side (`pipeline.cljc:131-135`).** A writer that returns `nil`, a non-map, or a map without `:dao.stream/outcome` gives `:named {:outcome nil}` and `:projected {:outcome :not-attempted :named-outcome nil}`. That is not a lie about what was persisted, but it is unclassified. Fix: map a missing or non-keyword outcome to `:invalid-writer-answer` and keep the raw answer under `:receipt`.
- **P3-3. `batch-of` (lines 55–60) silently prefers `:ast` when both `:ast` and `:datoms` are given.** The docstring says "Exactly one". The neither-case throws `:bad-request`, and no test covers that path. Fix: throw when both are present, and add a test for both.
- **P3-4. An internal defect and invalid input give the same `:outcome :diagnostic` (`pipeline.cljc:80-96`).**
  - `exception-diagnostic` returns `{:status :internal-error …}`, and the code takes only its `:diagnostic`, whose `:rule` is `:internal-error`. The status is dropped, so callers must inspect `:rule`.
  - Fix: keep `:status` and set `:projected {:outcome :internal-error}` for that case.
- **P3-5. The diagnostic can be mistaken for a success (lines 80–96).**
  - `projection` holds either the result or the diagnostic, and `(:fingerprint projection)` is the discriminator. That works today, but it's fragile.
  - Fix: use two named bindings or a tagged vector.
- **P3-6. Store-failure diagnostics are inconsistent.**
  - `:diagnostic` is the raw ex-data from `jing/materialize!` (line 93). For `:not-a-result` that includes `:payload`, the whole envelope, so a large diagnostic.
  - It also has no `:rule` on some paths, unlike the projection diagnostics that all carry `:rule`. The test at line 161 locks in that raw shape.
  - Fix: wrap it as `{:rule :projected-write-failed :cause (select-keys ex-data [:result :address])}`.
- **P3-7. The result cannot say whether dedupe happened.** `materialize!` returns only the address, never `:inserted` or `:present`. This only matters if the brief's "report :inserted/:present" was meant literally; the fix would be to wrap `:put-content-fn` (its call is `(put-content-fn address payload)`) and record its answer.
- **P3-8. A note on the physical address's portability.**
  - The physical address comes from jing's transitional print-based hash, which its own docstring says is portable only between implementations sharing that print rule. Non-integral doubles, for example, could print differently on the JVM and in JS.
  - The Merkle fingerprint is the cross-host identity. This deserves one sentence in the namespace docstring.

## Checked and clean

- **Ordering and isolation contract:**
  - The named writer is called before anything projected happens, and `project-datoms` runs only inside `persist-projected!`, after a named `:ok`.
  - A named non-ok gives `:not-attempted` with no projection or store access. No path attempts projected persistence after a named failure.
  - A projection or store throw is caught and cannot fail or roll back the named side.
  - Named and projected are built from the same `datoms` vector, so they can't diverge.
  - Named artifacts are never deduped by fingerprint: every call reaches the writer, and the two-batches assertion in the dedupe test proves it.
- **Envelope and provenance:**
  - No pre-write lookup exists in the code.
  - The envelope is deterministic from the records (`projected->datoms` is a stable walk), so alpha-equivalent programs give byte-equal envelopes and one address.
  - `:provenance` is only echoed, and never enters either artifact. The test asserts that.
- **Other tests that do prove their claim:**
  - Named/projected separation: the writer receives exactly the emission with the root last, and the stored value equals the canonical envelope.
  - Projection isolation: the named write is still `:ok`, the store is empty, and the rule is `:unsupported-value`. It would fail if projection ran first and threw, or if it failed the named side.
  - Write failure would throw or fail if the catch were removed.
  - Different programs reusing `-16` tempids get different identities.
- **Portability:**
  - Static analysis found no host-specific code.
  - The reader-conditional catch idiom matches `debruijn.cljc` and `yin.vm`, which already loads `dao.jing` on all hosts. `ex-message` and `ex-data` are already used on all three hosts by `exception-diagnostic`.
  - There are no host APIs, I/O, or top-level atoms. The only atoms are per-call in the tests.
  - The `(fn [q] q)` literal is unsupported on every host, and jing's `:not-a-result` ex-data is host-independent. The test namespace is auto-discovered, with no registration list to update.
- **Scope:** the only D5 changes are the two new files. `debruijn.cljc` is modified, but that is the concurrent audit-fix work, which I deliberately didn't review. The pipeline consumes only `project-datoms`, `projected->datoms`, `exception-diagnostic`, and the `:fingerprint` key. The pipeline doesn't touch the AST, walker, VM, linearizer, transactor, lease, or waitset code.

Two things I could not confirm:
- I did not check whether `exception-diagnostic` or `projected->datoms` keep their behaviour after the concurrent audit-fix edit lands, because that file is out of scope.
- The `pipeline_test` result for CLJS and CLJD is unrun, as the brief notes.
