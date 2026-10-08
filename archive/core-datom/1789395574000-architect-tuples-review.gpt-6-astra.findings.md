Completed-GMT: 2026-09-14 14:27:29 GMT  
Completed-Local: 2026-09-14 21:27:29 Asia/Ho_Chi_Minh  
Coding-Agent: codex  
Session-ID: 01a09fcc-3b9f-7172-b592-f15348d6c88b

**Verdict: REJECT**

The tuple representation is viable, and most of the AST inventory is accurate. The blocking problems are in its integration: the store-key grammar rejects supported programs, the proposed persistent ledger violates datom coordinates, and provenance, dependency queries, and migration rules need substantive corrections. These findings concern implementation of the seven rulings, not the rulings themselves.

**Numbered findings**

1. **P1 — Symbol-only store keys exclude existing supported programs.**  
   **Draft:** §2.3, [lines 122–123](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:122).

   Both store operations declare `key` as `sym`. The walker, codec, and linearizer preserve the supplied key without that restriction. An existing parity case uses `:parity/k` ([parity_test.cljc, line 99](/Users/sto/workspace/datomworld/test/yin/vm/parity_test.cljc:99)).

   I verified that the reference linearizer and loader accept this case and produce:

   ```clojure
   [[11 :parity/k 11] [23]]
   ```

   **Fix:** Define store keys as the portable data accepted by the store contract, or introduce an explicit compatible key kind. Do not silently narrow them to symbols. Include keyword-key round trips in both-path conformance tests.

2. **P1 — The store-update replacement is not generally equivalent, although excluding the tag is justified.**  
   **Draft:** §3.1, [lines 204–222](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:204).

   The original arm directly applies its stored host function and stores the returned value ([walker, lines 410–415](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ast_walker.cljc:410)). An application instead resolves `f` and `yin/def` through environment/store/primitive/module precedence. Those bindings can be shadowed ([resolve-var](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:46)). Moreover, an application interprets an effect-shaped return value rather than necessarily storing it as data ([semantic apply-call](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:206)).

   “A second address for one program” is also not a valid exclusion argument: this design hashes syntax, not semantic-equivalence classes.

   **Fix:** Retain the exclusion on its sound grounds—host-function-bearing syntax and absence from the portable codec/semantic corpus. Describe the application rewrite only under explicit binding and result-behavior conditions. Do not promise it as a general semantics-preserving migration.

3. **P1 — Runtime frame bookkeeping cannot remain inside the tuple held under `:frame`.**  
   **Draft:** §2.1, [lines 82–86](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:82); §9.1, [lines 680–692](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:680).

   The migration is described principally as replacing field reads. But the walker currently augments the AST map held under `:frame` with `:fn`, `:operator-evaluated?`, and `:evaluated`. Both cold and hot continuation paths do this ([cold path, lines 213–237](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ast_walker.cljc:213); [hot path, line 532](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ast_walker.cljc:532)).

   Keyword associations into a vector are invalid; adding runtime slots would violate fixed arity and mix execution state into canonical syntax.

   **Fix:** Specify revised continuation-frame schemas. Keep the canonical tuple unchanged under `:frame`, and move evaluated operands, operator values, and evaluation position into the surrounding runtime map. Sweep continuation arms in both execution paths, including the hot arms preceding line 600.

4. **P1 — The four dependency queries do not compute the declared code dependencies.**  
   **Draft:** §7.4, [lines 523–543](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:523).

   The introduction explicitly includes `:resume` parked ids, but none of the four queries extracts them. Selecting the tag `:vm/resume` does not recover its parked-id operand. UCF explicitly requires that operand for scheduler closure ([UCF, lines 765–772](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:765)).

   The “code part is exactly these queries” claim also omits the declared effect footprints of callable bindings. Merely finding a variable name does not produce those effects. Recasting the walk as Datalog does not repair UCF’s remaining cross-activation resolution problem.

   **Fix:** Publish executable extraction queries/rules for every dependency category, including parked ids, and specify their composition with callable profiles and the value/module fixed point. State when discovery is incomplete. For unioned segment relations, use the segment-qualified row shape from §6.2; do not mix bare pcs across sources.

5. **P1 — Primary vector loading still lacks a complete shared structural validator.**  
   **Draft:** §7.2–§7.3, [lines 495–519](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:495).

   The AST grammar is a reasonable validator basis. Instruction vectors additionally need nonempty-segment, target/body bounds, final-terminator, and operand constraints corresponding to semantic §2.6. The inherited UCF check still enumerates mnemonic, arity, operand kinds, and saturation ([UCF, lines 313–319](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:313)). A correctly hashed `[[:jump 9]]` demonstrates why tuple shape alone is insufficient.

   Valid-corpus image equality does not establish that both paths reject malformed inputs identically.

   **Fix:** Incorporate segment structural constraints into the instruction grammar’s validation rules. Require both paths to invoke that validator before registration, with matching failure outcomes. Test malformed vectors and multiple loaded segments as well as valid corpus programs. Explicitly supersede UCF’s projection-primary wording to reflect the owner’s direct-primary ruling.

6. **P1 — Most proposed ledger rows cannot be committed as datoms.**  
   **Draft:** §8.1–§8.3, [lines 561–615](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:561).

   The mapping puts code addresses and occurrence identifiers in `e`. Persistent datoms require a non-negative integer, stream-local entity coordinate; foreign/content references belong in `v` ([datom contract, lines 140–180](/Users/sto/workspace/datomworld/docs/design/datom.md:140)). The transactor enforces this through `local-datom?` ([implementation](/Users/sto/workspace/datomworld/src/cljc/dao/datom.cljc:42)).

   I verified:

   ```clojure
   [16 :yin/code :segment/example 0 1]                    ; accepted
   [:segment/output :yin.code/derived-from
    :segment/input 0 2]                                  ; rejected
   ```

   Likewise, the literal `:assert`/`:derive` values shown in `m` are not accepted integers, and caller-supplied non-nil `t` is rejected by the transactor ([pad-datom](/Users/sto/workspace/datomworld/src/cljc/dao/space/transactor.cljc:74)).

   **Fix:** Keep address-to-address ledger tuples as a logical relation, then define an explicit datom representation using local event/ref entities with addresses in value slots. Alternatively, specify a deliberate governing-contract amendment; the present design claims compatibility. Distinguish logical notation from actual transactor input.

7. **P1 — Open ledger operations cannot be implemented by composition-local allocation of reserved `m` ids.**  
   **Draft:** §8.2, [lines 586–601](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:586); §10, lines 767–770.

   Reserved ids have globally fixed meanings; compositions cannot independently allocate them while preserving interoperable interpretation. The existing extension mechanism is a local metadata entity at id 16 or above carrying `:db/op` and provenance ([dao.datom namespace contract](/Users/sto/workspace/datomworld/src/cljc/dao/datom.cljc:2); [reserved-entity rules](/Users/sto/workspace/datomworld/docs/design/datom.md:205)).

   Merely adding ids 3 and 4 also leaves their validity and hashing treatment unspecified. Current-state resolution removes only retractions; it does not ignore every unknown operation ([query.cljc, lines 76–103](/Users/sto/workspace/datomworld/src/cljc/dao/space/query.cljc:76)). Thus the proposed generic “ignore unknown ops” rule is not the existing fold.

   The derive row is internally reversed too: §8.2 says `v` was computed from `e`, while `[segment :derived-from tree …]` means the opposite.

   **Fix:** Separate event operation from fact validity. Use the established metadata-entity mechanism for open domain operations, or fully specify globally standardized new markers, including validity, hashing, indexing, and compatibility behavior. Correct derivation direction. Allocation alone is not the acceptance blocker.

8. **P1 — The as-of query incorrectly removes reasserted references.**  
   **Draft:** §8.5, [lines 651–661](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:651).

   Its negation rejects any address with any retraction before the cutoff, even if a later assertion restored it. For assertion at `t=1`, retraction at `t=2`, and reassertion at `t=3`, I ran the published query against the current query implementation: it returns the empty set. The existing current-as-of view correctly returns the address.

   **Fix:** Use `query/current` with its as-of argument, or select the latest event per `[e a v]` before interpreting its operation. This is already the implemented contract ([query/current](/Users/sto/workspace/datomworld/src/cljc/dao/space/query.cljc:204)).

   Also bind the intended name in §6.4’s example: its comment names `my.ns/f`, but the query leaves `?e` unconstrained.

9. **P1 — Content identity does not replace source-occurrence or expansion-event identity.**  
   **Draft:** §2.5, [lines 189–196](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:189); §4.4, lines 304–307; §8.3, [lines 617–625](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:617).

   Two identical subtrees at different source positions have one content address. Joining `[segment pc node-address]` to `[node-address file line col]` therefore cannot identify which occurrence produced an instruction. A distinct-subtree relation similarly identifies content, not unique call sites.

   Macro events have the same problem. The existing contract records one event per attempt, including repeated identical expansions and failures, and links that event to its output ([macro contract, lines 666–709](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:666)). Replacing the output link with rows keyed only by `tree-out` leaves the retained event attributes disconnected and conflates repeated attempts.

   **Fix:** Preserve occurrence information outside canonical objects: source/batch identity, root address, structural path, and expansion-event identity. Link events to input/output/macro addresses. Use content addresses for sharing and cross-media content correspondence, not as substitutes for occurrence identity.

10. **P1 — Removing macro flags lacks the replacement admission contract needed to preserve macro behavior.**  
    **Draft:** §2.5, [lines 177–181](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:177); §8.3, line 613; §9.1, line 692.

    The proposed macro-definition ref is plausible, but the document does not define how the expander associates it with a particular incoming definition, its ordering, or plain redefinition. Two otherwise identical definitions can have identical canonical trees while one is a macro definition and the other is not.

    The governing macro contract distinguishes those cases during admission and harvest and requires a plain redefinition to remove a macro ([macro contract, lines 229–254](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:229)). Stripping the flag before establishing its replacement loses the distinguishing information.

    **Fix:** Specify the frontend output and expander input composition carrying canonical trees plus occurrence-bound declaration facts. Define ordering, missing-fact behavior, lexical scope, and redefinition. Keep the flag outside canonical code as ruled. The blocker is this replacement protocol; building the entire expander need not block tuple evaluation without macros.

11. **P1 — The claimed hash chain and revision-aware derivation are not represented by the objects or rows.**  
    **Draft:** §5.2, [lines 332–346](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:332); §8.1, [lines 578–582](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:578).

    AST children are inline, and canonical instruction vectors exclude their source address. Their content does not contain the predecessor addresses claimed in §8.1. The relationship exists in external ledger rows. Those rows do not themselves establish a content-addressed history chain.

    The document also permits two lowering revisions but records no revision in the derivation row. A consumer recomputing its own `lower(tree)` can reject a valid segment produced by another revision.

    **Fix:** Define the ledger objects/links that realize the owner’s hash-chain ruling, rather than attributing those links to code objects that exclude them. A derivation record must identify the source object, output object, and lowering contract/profile. Verify using that profile; do not report a legitimate revision difference as content corruption.

12. **P1 — The canonical-encoder blocker is correctly identified but incorrectly limited to durable ledgers.**  
    **Draft:** §4.2, [lines 266–276](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:266); §10.1, [lines 758–764](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:758).

    The list/vector collision is honestly disclosed. However, the final limitation says it blocks identities in ledgers that outlive one process. The same collision can already corrupt a process-local address cache, merge flat-projection rows, or resolve the wrong literal object.

    **Fix:** Gate every identity/deduplication use on a type-preserving codec or an explicitly validated restricted value domain. Process lifetime is irrelevant. Retain this as an inherited blocker rather than claiming the tuple representation closes it.

**Verified sound**

- **The dispatch inventory is otherwise accurate.** The table covers the cold and five hot syntax arms, with the two explicit boundary decisions. `:vm/store-put` carries unevaluated data; `:vm/resume` carries an evaluated child node. The codec and `ast-children` agree.
- **Application-only `tail?` is correct for this lineage.** The linearizer reads it only for application-to-call lowering ([linearize.cljc, lines 99–108](/Users/sto/workspace/datomworld/src/cljc/yin/vm/linearize.cljc:99)). Dropping non-application marks preserves the behavior currently observed by the evaluators.
- **Keeping `:stream/close` and adding the walker arm is justified.** Codec encode/decode, linearization, semantic execution, and engine handling already support it. The proposed evaluate-source-then-effect behavior matches the existing stream continuation pattern.
- **The addressing grain and operand ordering are reasonable.** Whole-tree hashing needs no per-lambda materialization to work. Vector order is retained; the type-preservation caveat remains essential.
- **The main query mechanics match source.** Ordinary patterns are exact-arity; the implementation also supports the final `& tail` form used in §7.4. I ran that rest-pattern query successfully. The three-slot fast path requires a fact index. `match` does not bind `?vars`, and supplied function keys are symbols. Predicate arguments must already be bound.
- **The three datom-projection query classes are justified by the current implementation:** attribute-variable queries, EAV navigation through `pull`/`entity-attrs`, and existing covered indexes. They are implementation capabilities, not reasons to make EAV canonical again.
- **The hot loop can remain query-free.** Projection and dependency analysis belong outside execution. Direct/projection conformance is the right obligation, expanded to malformed inputs and runtime-frame handling.
- **All listed migration counts match the working tree.** They count matching lines, not total edits or AST nodes. The sweep is understated specifically around runtime frame writes and macro admission.
- **The observer warning is appropriately cautious.** `run-on-stream` is shape-agnostic. The actual REPL still converts semantic AST input through `ast->datoms` ([core.cljc, line 446](/Users/sto/workspace/datomworld/src/cljc/yin/repl/core.cljc:446)). Conversely, repository search found no caller of the v2 `append-program-datoms` beyond its own overload, so a tuple counterpart should not be presumed necessary without identifying a live use.
- **Per-lambda subterm addressing is correctly a follow-up.** It is not required for the chosen whole-tree design.

The main additional acceptance blockers are a legal persistent ledger representation, defined operation/validity semantics, occurrence-preserving provenance, macro declaration carriage, complete dependency extraction, and runtime-frame migration. The draft’s central tuple decision remains sound; these surrounding protocols need correction before acceptance.