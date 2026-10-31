Completed-GMT: 2026-09-14 17:16:44 GMT  
Completed-Local: 2026-09-15 00:16:44 Asia/Ho_Chi_Minh  
Coding-Agent: codex  
Session-ID: 01a09fcc-3b9f-7172-b592-f15348d6c88b

**Verdict: REJECT**

The revision makes substantial corrections: runtime bookkeeping leaves canonical tuples, both load paths share validation, ordinary ledger events use legal datom coordinates, and as-of queries use the correct interpreter. However, several original defects remain, and the new restricted encoder domain is demonstrably unsound.

**Numbered findings**

1. **P1 — The revised store-key grammar still narrows the existing contract.**  
   **Draft:** §2.2, [line 119](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:119); validators at [line 662](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:662) and [line 678](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:678).

   Adding keywords fixes the cited corpus case, but restricting keys to symbols or keywords still rejects supported programs. The draft itself acknowledges that the governing operation has no key restriction. The walker performs ordinary `get` and `assoc` ([source, line 401](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ast_walker.cljc:401)).

   I ran the reference linearizer and loader with `{:type :vm/store-put :key 42 :val 11}`. It succeeds, producing `[[11 42 11] [23]]`.

   **Fix:** Define portable store keys using the admitted data domain, subject to the canonical encoder’s restrictions. Apply the same definition to AST validation, instruction validation, dependency extraction, and store-slice encoding. Corpus coverage does not establish the complete key contract.

2. **P1 — “Returns plain data” still does not make the store-update rewrite equivalent.**  
   **Draft:** §3.1, [lines 275–287](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:275).

   The revised exclusion rationale is sound, and the binding conditions address shadowing. The remaining result condition is incorrect: effect descriptors are plain data.

   I verified that `{:effect :vm/store-put :key :x :val 4}` passes `linearize/plain-data?` and `module/effect?`. The latter recognizes any map containing `:effect` ([source, line 77](/Users/sto/workspace/datomworld/src/cljc/yin/vm/module.cljc:77)). Direct store-update stores that map; application interprets it. The direct behavior is explicit at [walker line 410](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ast_walker.cljc:410).

   **Fix:** Require a result that is plain data **and is not an effect descriptor under the applicable execution contract**. Keep the exclusion independently justified by the host-function-bearing syntax.

3. **P1 — The new restricted encoder domain does not prevent collisions.**  
   **Draft:** §4.2, [lines 347–350](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:347); §7.4, [line 664](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:664); §10.1, [line 1100](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1100).

   Rejecting lists is insufficient. `plain-data?` admits collections generally, including non-list sequences and plain metadata ([source, lines 45–58](/Users/sto/workspace/datomworld/src/cljc/yin/vm/linearize.cljc:45)). The encoder converts **every sequential value** to a vector and prints without preserving metadata ([source, lines 45–71](/Users/sto/workspace/datomworld/src/cljc/dao/jing.cljc:45)).

   Executed counterexamples:

   - `(seq [1 2])` is not a list and passes `plain-data?`. Its literal address equals that of `[1 2]`, but `conj` produces `(3 1 2)` versus `[1 2 3]`.
   - Vectors carrying different plain metadata produce the same literal address. The admitted `data` kind explicitly includes plain metadata.

   Furthermore, the proposed checker operates on tree data slots, while §8.2 also addresses arbitrary ledger-record maps.

   **Fix:** Keep identity uses blocked until the codec is fixed, or specify and validate a genuinely restricted domain across **all addressed objects**. That domain must address every admitted collection type and metadata policy, rather than blacklist one sequential type. Add these counterexamples to encoder conformance checks.

4. **P1 — Structural paths identify positions within content, but still do not identify source or generated occurrences.**  
   **Draft:** §2.5, [lines 211–228](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:211); §8.4, [lines 903–921](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:903).

   `[root-address path]` correctly separates identical subtrees at different positions in one tree. It does not separate identical whole trees parsed from different files or admitted in different batches. Both receive the same root address and paths. The separate `[root-address batch-id]` relation cannot repair the ambiguity: joining it to positions produces every matching batch/position combination.

   Nested expansion ancestry remains ambiguous too. If two expansion attempts produce identical output trees, a subsequent `[tree-in-addr path]` does not identify which attempt produced its input. Distinct event entities preserve event count, but do not preserve that parent relationship. The governing macro contract explicitly links nested expansion input to the enclosing expansion’s log output ([macro design, lines 692–708](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:692)).

   **Fix:** Distinguish structural positions from source occurrences. Qualify source positions and declarations with a source/batch occurrence identity; qualify generated positions with the producing expansion event. Carry those qualifiers through instruction provenance and nested-event links, outside canonical code.

5. **P1 — Macro carriage still does not preserve whole-batch admission and harvest.**  
   **Draft:** §8.5, [lines 930–952](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:930); migration adapter at [line 1021](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1021).

   The new envelope handles declarations within one tree, including missing-fact redefinition. Two remaining cases contradict the governing contract:

   - The original admission/harvest operates on **every entity in the batch, including disconnected definitions**, in batch order. A single rooted tree plus paths cannot represent disconnected definitions or their original order. Preorder equals the current codec’s traversal order for ordinary generated trees; it is not equivalent to arbitrary admitted batch order. The requirement is explicit at [macro design lines 229–254](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:229).
   - The adapter strips `:macro?` and emits declarations for flagged lambdas under `yin/def`. It does not require rejection or preservation of a flag outside that position. Such a lambda can silently become a plain lambda, whereas admission must reject it as `:stray-macro-lambda`.

   **Fix:** Specify an ordered batch representation or admission-side catalogue that preserves all definitions and their order, including those outside the selected execution root. Validate original macro marks before stripping them, rejecting stray marks. An intentional narrowing of the old batch contract must be explicit rather than described as unchanged behavior.

6. **P1 — Dependency completion still uses an unsound cross-activation name test.**  
   **Draft:** §7.7, [lines 753–759](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:753) and [lines 772–779](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:772).

   Collecting every name is conservative; declaring it satisfied because **a** carried environment binds it is not. A saved continuation can bind `x` while the active activation lacks `x` and resolves it to a primitive. The rule skips that primitive and its profile despite allowing discovery to be `:complete`.

   I checked the reference resolver: with empty active environment/store, `x` resolves to the supplied primitive; with a separate environment containing `x`, it resolves to that binding. Resolution is activation-specific ([engine, lines 46–65](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:46)).

   This repeats the unresolved rule in [UCF §7.6.1, line 774](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:774). Acknowledging the cross-activation question does not make the new completion criterion safe.

   **Fix:** Associate resolution obligations with relevant code/environment contexts, or conservatively retain all possible primitive/module requirements. When that analysis cannot establish completeness, report `:incomplete`; an unrelated carried binding cannot discharge the obligation.

7. **P1 — The new segment effect query returns mnemonics, not the required effect vocabulary.**  
   **Draft:** §7.7, [lines 744–759](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:744).

   The segment query returns values such as `:stream-put`. Callable profiles and `:yin.k/effects` use effect identifiers such as `:stream/put`. No composition rule maps between them. Thus equivalent tree and segment representations do not currently yield the same dependency vocabulary.

   The reference machine explicitly maps `:stream-put` to `:stream/put` ([semantic execution, line 370](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:370)); the UCF requirement set uses the latter ([UCF, line 751](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:751)).

   **Fix:** Specify a contract-versioned instruction-to-effect-footprint mapping and apply it before unioning syntax-derived effects with callable-profile effects. Include explicit mappings for instructions with no external effect and for FFI behavior. Require equivalent AST and segment inputs to produce the same requirement sets.

8. **P1 — Derivation verification still lacks an unambiguous lowering revision.**  
   **Draft:** §5.2, [lines 423–454](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:423); persistent example at [line 845](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:845).

   The record now carries a stamp, but it specifically reuses UCF’s **execution-contract** stamp. That stamp versions instruction grammar, execution, resolution, effects, and scheduling; its declared scope does not pin the AST-to-instruction lowering algorithm ([UCF, lines 239–261](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:239)).

   Two deterministic lowerers can target the same execution contract while emitting different valid vectors. Determinism within each implementation does not ensure identical output across implementations. The current rule can therefore still label a legitimate lowering revision difference as corruption.

   The persistent example also substitutes `"v2/0"` for the structured stamp in the addressed record, without defining that serialization mapping.

   **Fix:** Give the lowering function a pinned revision/profile covering input grammar, normalization, and exact emission rules, or explicitly extend the governing stamp to include those rules and mandate revision changes. Preserve the exact structured stamp in the event projection. Separate object-hash verification from verification of the asserted derivation.

**Original findings 1–12**

“Resolved” below means resolved in the specification, not implemented.

| Original | Status | Verification |
|---|---|---|
| 1 — Store-key domain | **Not resolved** | Keywords added, but portable numeric keys still rejected: [§2.2, line 119](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:119). Finding 1 above. |
| 2 — Store-update equivalence | **Not resolved** | Exclusion and shadowing corrected; plain-data condition still admits effects: [§3.1, line 281](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:281). |
| 3 — Frame mutation | **Resolved** | Immutable `:node`, separate runtime fields, and both continuation paths explicitly swept: [§2.6, line 230](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:230), [§9.1, line 1014](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1014). |
| 4 — Dependency extraction | **Not resolved** | Parked-id queries and profile composition added, but completion and effect normalization remain defective: [§7.7, line 725](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:725). Findings 6–7 above. |
| 5 — Shared validator | **Resolved** | Structural rules, malformed-input conformance, multiple segments, and direct-primary supersession are explicit: [§7.2, line 615](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:615), [§7.5, line 666](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:666). The new encoder rule has a separate defect. |
| 6 — Illegal ledger coordinates | **Resolved** | Local event entities, address values, and transactor-owned time: [§8.1, line 796](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:796), [§8.2, line 836](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:836). |
| 7 — Reserved operation IDs | **Resolved** | Open operations moved into event attributes; validity markers and derivation direction corrected: [§8.2, line 849](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:849). |
| 8 — As-of reassertion | **Resolved** | Uses `query/current` with cutoff; name binding also supplied: [§8.6, line 964](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:964). Executed successfully. |
| 9 — Occurrence identity | **Not resolved** | Structural paths added, but source/batch qualification and generated-parent identity remain absent: [§2.5, line 211](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:211), [§8.4, line 912](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:912). |
| 10 — Macro admission carriage | **Not resolved** | Envelope and redefinition rules added; disconnected definitions, batch ordering, and stray-mark migration remain uncovered: [§8.5, line 935](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:935). |
| 11 — Derivation chain/revision | **Not resolved** | Addressed relationship records now exist; execution stamp still does not explicitly identify lowering revision: [§5.2, line 419](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:419). |
| 12 — Encoder gate | **Regressed** | Lifetime limitation corrected, but the new claimed-safe restricted domain admits demonstrated collisions: [§4.2, line 341](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:341). |

**Verified sound**

- **The frame redesign is implementable.** Keeping operator values and evaluated operands outside immutable syntax fixes the original mutation problem. Evaluation position as the evaluated-operand count matches the cold application/FFI paths and hot application path ([cold source, line 223](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ast_walker.cljc:223); [hot source, line 575](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ast_walker.cljc:575)).
- **Ordinary event persistence is legal.** I verified representative resolved event datoms with `local-datom?`. Addresses occupy `v`, entity IDs are local integers, and ordinary event facts use assertion validity. This matches [the predicate](/Users/sto/workspace/datomworld/src/cljc/dao/datom.cljc:42) and [transactor time ownership](/Users/sto/workspace/datomworld/src/cljc/dao/space/transactor.cljc:74). The optional metadata-provenance path remains explicitly unverified in §10.3; this review does not certify it end to end.
- **The revised as-of mechanism works.** Using a relation source, I ran assertion → retraction → reassertion: cutoff 2 returned no address; cutoff 3 returned the restored address. That agrees with [the current-view contract](/Users/sto/workspace/datomworld/src/cljc/dao/space/query.cljc:204).
- **Direct-primary loading and shared structural validation are now explicit.** Bounds, termination, malformed-input parity, and registration ordering are appropriate conformance obligations. They remain implementation work, rather than falsely claimed test results.
- **The central representation decisions remain sound.** Fixed positional slots preserve the data/node distinction, and addressed derivation records can represent relationships without putting provenance inside canonical code. The unresolved problems concern the surrounding identity, admission, and verification protocols.

The draft is closer to implementable, but §10 still understates the acceptance work. It should include the unresolved key domain, complete occurrence identity, macro batch/admission preservation, conservative dependency completion, effect normalization, and lowering-profile definition. Most critically, it must withdraw the claim that the current list-rejection rule makes address-based identity safe.