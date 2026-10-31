Completed-GMT: 2026-09-14 21:32:53 GMT
Completed-Local: 2026-09-15 04:32:53 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a09fcc-3b9f-7172-b592-f15348d6c88b

**Verdict: REJECT**

Reviewed `docs/design/yin.vm.code-as-tuples.md`, r8, 1955 lines. The owner's ontology is accepted as the governing decision: map ASTs are the semantic representation; flat per-node rows are canonical AST code; there is no nested tuple-tree representation. Instruction vectors remain the separately specified canonical representation of lowered code.

The overview, main representation definition, and walker migration follow that decision. Four executable contracts do not yet agree with it. These are inconsistencies in the implementation of the ruling, not challenges to the ruling or objections to unfinished implementation work.

## Findings

1. **P1 — Derivation integrity still hashes the semantic tree instead of verifying canonical rows.**

   **Draft:** §5.2.2, [lines 725–734](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:725); governing definitions in [§2.1, lines 166–190](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:166) and [§4.1, lines 532–539](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:532).

   The check `(segment-key tree) == :yin.ledger/input` is retained from the old representation. Under r8, `tree` denotes the reconstructed map AST, which §2.1 expressly forbids hashing directly. Its address is the root row's ID, calculated from that row's body with child IDs. Hashing a row set instead would also produce a different address.

   This is observable even for a literal: the hash of `[:literal 1]` differs from the hash of `{:type :literal :value 1}` and from the hash of the singleton full-row set. A conforming derivation would therefore fail the published integrity check.

   **Fix:** Verify the claimed root ID against the root row and verify each required row as `row.id == segment-key(row-body)`, recursively validating the reachable closure before reconstructing the semantic map. Then lower that map under the pinned profile and verify the resulting instruction-vector address. Make the per-row address check normative in §7.4: its current reference to a check “whenever it runs” at [lines 1007–1013](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:1007) does not supply the missing content-integrity procedure. Keep explicit temporary allocated-ID operation, if supported, separate from verified content-addressed loading.

2. **P1 — The ID names the row body, but the declared stored payload is the full row.**

   **Draft:** §2.1, [lines 183–199](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:183).

   The draft defines `id = segment-key([tag & slots])` and says the stored value is `[id tag & slots]`, stored in `dao.jing` under that ID. The existing Jing contract hashes the actual payload it receives. `materialize!` calculates `segment-key(payload)` and stores that same payload ([source, lines 251–279](/Users/sto/workspace/datomworld/src/cljc/dao/jing.cljc:251)). Passing the full row therefore stores it under the hash of the full row, not the ID prepended to it. Child references computed from row bodies will not name those stored payloads.

   The declared pack-versus-individual-row open question does not resolve this mismatch. It concerns fetching and grouping; the individual row's hash preimage and materialization contract still need to agree.

   **Fix:** Specify the exact storage boundary. One compatible option is to materialize the flat body `[tag & slots]` in Jing and reconstruct the full logical/stream/query row by prepending the returned address; state explicitly that the ID is an address envelope outside the hashed payload. If full rows must be Jing payloads, define an explicit verified addressed-envelope capability and its relationship to Jing rather than assuming the current opaque-payload API already supports it. Neither option requires or permits a nested tuple tree.

3. **P1 — The row validator still applies body arities to full rows.**

   **Draft:** §2.3, [lines 267–279](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:267); §7.4, [line 993](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:993).

   The grammar's introductory text says arity counts tag and ID, while its numeric column remains the old body arity: literal is 2, application is 4, and park is 1. The validator says to reject when the row count differs from the tag's arity. But the canonical rows are `[id :literal value]` (3), `[id :application operator operands tail?]` (5), and `[id :vm/park]` (2). The literal example is rejected by a literal implementation of the validator.

   **Fix:** Either label the existing column `Body arity` and require `count(row) = body-arity + 1`, or increment the column and consistently call it row arity. Apply the same convention to validation, query patterns, and row-slot coordinates. The distinction must be explicit because ID is present in a canonical row but absent from its hash preimage.

4. **P1 — Macro admission still requires the removed tuple-node representation.**

   **Draft:** §8.5, [lines 1686–1690](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:1686).

   The declaration binding rule specifies an operator `[:variable yin/def]` and literal/lambda operand nodes. That operator value is neither the semantic map `{:type :variable :name 'yin/def}` nor a canonical row `[id :variable yin/def]`. In a canonical application row, the operator slot is an address, not an inline node at all.

   This is the old tuple-tree matching rule surviving in an admission contract. Implementing it literally rejects correctly represented declarations. The walker and codec establish the actual semantic fields: map applications carry `:operator` and `:operands`, and variables carry `:name` ([walker, lines 361–375](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ast_walker.cljc:361); [codec, lines 416–428](/Users/sto/workspace/datomworld/src/cljc/yin/vm.cljc:416)).

   **Fix:** State the match on reconstructed semantic maps, or define the equivalent row joins explicitly: resolve the application's operator ID to a `:variable` row naming `yin/def`, then resolve operand IDs to a literal-symbol row and a lambda row. Remove `[:variable yin/def]` as a required runtime/admission node shape. Row bodies remain valid hash payload notation; they are not semantic nodes.

## Verified consistent with the ruling

- **Semantic maps remain maps.** §2.1, [lines 149–170](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:149), identifies them as per-consumer semantic images, and [lines 208–217](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:208) keep runtime frame bookkeeping out of canonical content. This matches the walker's existing named-field reads and immutable map updates.
- **The main row grammar has no inline child syntax.** Child slots carry addresses; operand collections carry ordered address vectors. The `node`/`nodes` dictionary explicitly distinguishes row addresses from semantic child maps ([lines 183–190](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:183), [lines 229–232](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:229)).
- **The main loader boundary is correct.** Rows are received, validated, and reconstructed into map ASTs; no tuple node is handed to the walker ([§7.1, lines 910–925](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:910)). Frontends retain map ASTs and gain a standing map-to-row boundary ([§9.1, lines 1797–1802](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:1797)).
- **Macro carriage uses row sets.** The batch envelope now holds `<rows-j>` rather than nested canonical trees ([§8.5, lines 1603–1606](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:1603)). Occurrence coordinates and harvest/declaration metadata stay outside code content.
- **Datoms are a secondary projection, not the canonical AST representation.** §6.5 and §7.1 preserve the row-primary distinction ([lines 882–901](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:882), [lines 928–933](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:928)).
- **The separate instruction vector is not a forbidden nested AST tuple tree.** §5.1's distinction between per-node AST rows and lowered instruction vectors is consistent with the ruling ([lines 651–658](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:651)).

## Verification performed

Read the r8 document and relevant source contracts; searched for retained nested-node notation, tree hashing, row/ID semantics, and stream/load paths. Ran a Babashka probe using the actual `dao.jing/segment-key`:

```clojure
{:row-arity 3
 :table-arity 2
 :body-id-equals-map-hash false
 :body-id-equals-full-row-hash false
 :body-id-equals-rowset-hash false}
```

The probe used body `[:literal 1]`, full row `[id :literal 1]`, and semantic map `{:type :literal :value 1}`. It demonstrates representation mismatches, not a test of the still-unimplemented row codec. The generic working-diff scan found no tracked working-tree changes; it does not constitute verification of the committed r8 design. No design or source file was modified by this review.

The ontology is clear at the top level but not consistently applied to hashing, materialization, arity validation, and macro admission. Correct those four contracts while retaining the accepted map/row separation. The already-declared encoder and packaging work need not be completed merely to finish this ontology review.
