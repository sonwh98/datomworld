# Call-site provenance: occurrence contract and durable correspondence

Status: architect design; no implementation or adoption acceptance claimed.
Authority: signed Addendum A v3 and signed attribution ruling v4.
Scope: read-only source investigation; this findings document is the sole output.
Design revision: 1 (2026-11-06).

Citation convention: paths beginning `yang/` or `yin/` are under `src/cljc/`; a shortened filename inherits the nearest explicit namespace/path. `ruling-of-record.md` abbreviates the signed 1794600000000 ruling named in §1; `code-as-tuples.md` abbreviates `docs/design/yin.vm.code-as-tuples.md`. Line ranges identify inspected source, while normative decisions in C1/D1 and the acceptance gates are new design requirements.

## 1. Decision and evidence limits

Adopt one language-neutral application-occurrence contract over the existing occurrence relation, one reserved frontend-metadata key, and the already-ruled third trace field. Keep code content, provenance, attribution evidence, and verification results separate. Publish the durable correspondence as an additional addressable record referring to the oracle's evidence **without changing that evidence's format**. Production adoption includes Clojure, TypeScript, PHP, Python, their preludes, and the additional ingress paths inventoried below.

The authority is `collab/yang-clojure/1794630000000-architect-attribution-addendum-a-RECORD.md:13-29` (A1–A4 and measurements), and `collab/yang-clojure/1794600000000-architect-attribution-as-query-ruling-of-record.md:11-73` (R1–R7). In particular, signed A4 at line 25 explicitly strikes “or atomically with”: **implementation AND independent reconstruction must pass BEFORE acceptance of the first additional relying consumer**. Simultaneous code landing is possible; simultaneous acceptance before reconstruction is not. The oracle remains unblocked.

Read order was addendum, architectural foundations, signed base ruling, then the recorded Astra addendum review. Astra's final review is the JSON agent-message at `1794630000000-architect-attribution-addendum-a.astra-review.stdout.log:48`; signed v3 supersedes its earlier “before, or atomically with” wording. No separate qwen addendum review was present in `collab/yang-clojure/` during this inspection. The available `1794610000000-architect-attribution-as-query-ruling.qwen-review.md` is a base-ruling review, not the missing addendum review. Signed v3 records incorporation of qwen's amendments at lines 3–4.

Source inspection occurred in a live workspace: master initially reported `e158afca`, later `5854869c5117e3659d8ce3bc3f140d017d7b8687`. Citations below identify inspected file locations, not a claim that all work was read from an immutable commit. Rebase/inventory refresh after the cell-ref sweeps and J2 is an implementation prerequisite. No tests, compilers, generators, or publication commands were run for this design.

### 1.1 The oracle format is not yet available to freeze

The inspected worktree `/Users/sto/workspace/datomworld-aaq`, branch `attribution-as-query`, reports HEAD `3eebe844e406ba56bc38a5f4b4a0098d811b5efd`. Its tracked `test/yang/latte_oracle_test.clj:1794-1839` still generates two-field body-entry traces and uses the offset accounting at lines 1786–1819. Its `BRIEF-aaq.md:15-21` requires the future inventory, three-field instrumentation, and versioned mechanically derived site tables; it does not define their schema. Untracked `probe/aaq_probe.clj:17-29` uses exploratory map-key paths, not the canonical row-position paths; it is not a published contract. The local dispatch log `collab/yang-clojure/1794630000000-engineer-attribution-as-query.glm-5.3.stdout.log:1` reports an unrecognized-model error. This does not establish the status of work elsewhere.

Consequently **the oracle evidence schema's exact identifier, field names, and canonical-source-hash algorithm/profile are unknown — the implementation round's inventory decides from the oracle's accepted record**. This document specifies the envelope around that record and its verification obligations, not an independently invented replacement site-table schema. “OracleEvidence” below means the entire accepted versioned oracle value, verbatim, including its site ids, argument-shape declarations, fixture linkage, trace schema, and version. Before durable implementation is accepted, the inventory must pin its concrete file, commit, schema identifier/version, validator, and derivation entry point. Absence of that pin blocks durable acceptance; it does not block the oracle round.

This is the format-race control demanded by A4. An implementation must not adopt a hypothetical table in this document or mistake `throw_sites.edn` for the new caller-site table.

## 2. Occurrence contract C1

The following is the proposed normative contract, grounded in `docs/design/yin.vm.code-as-tuples.md:393-455,658-665,918-931`.

### 2.1 Identity and scope

An application site is `K = [origin root-address path]`.

- `root-address` is the canonical root of the particular final UAST tree being named, not the application's subtree address or an instrumented runtime-map hash.
- `path` is the grammar's structural path: scalar child slot steps are row positions; a child in a `nodes` slot is `[slot-position member-index]`. The root is `[]`. Never substitute map keys, host iteration order, allocation ids, source line numbers, PCs, callee names, or arities.
- `origin` identifies the actual admission or generation event. Admitted syntax uses `[:source medium batch-token j]`; generated syntax uses `[:expansion expansion-record-address]`. Generated post-expansion trees must not be mislabeled as original source or given a fabricated expansion address. If a no-op expansion retains an admitted tree, the original source origin remains valid; “post-expansion” names the pipeline boundary, not proof that a generating event occurred.
- An origin is supplied by the admitting/generating composition. Two separately admitted identical sources have different origins; two identical applications in one tree have different paths; two expansion attempts with identical output have different producing records. Replaying a single recorded admission is different from admitting the same text again.

The implemented origin validator accepts exactly the source and expansion shapes (`src/cljc/yin/vm.cljc:938-958`). `vm/occurrences` returns `[root path node-id]`, one per place, with origin fixed by the composition; it does not return full keys or count only distinct row ids (`vm.cljc:1589-1609`).

Coverage is **every final `:application` occurrence**, including generated wrappers, adapters, dispatcher/helper calls, bundled preludes, imported preludes, recursion, and statically indirect applications. “Source call” and “attributable upstream call” are projections over this coverage, not filters that delete other occurrences. Rule R's `yin/def` is structurally an application but lowers to `:define`; it gets an occurrence annotation and never a fictitious callee-entry event (`semantic/linearize.cljc:106-116`).

Dedicated operations such as `:dao.stream.apply/call`, stream nodes, and continuation nodes retain existing instruction provenance. They are not silently counted as `:application` sites. Their adapters' application occurrences are covered; an extension to count other node kinds must explicitly version the coverage policy (`vm.cljc:1015-1028`; `semantic/linearize.cljc:126-139`).

### 2.2 Minimal persisted annotation

Reserve `:yang/call-site` in the existing frontend-metadata relation:

```clojure
[origin root-address path :yang/call-site 1]
```

The integer `1` versions this key's coverage/interpretation contract. The first three coordinates ARE the site id; the value does not repeat it. This key is distinct from existing `:yang/site`, whose meaning is safepoint marks (`src/cljc/yang/safepoint.cljc:55-75`). It carries no language name, source arity, ambient-context classification, or callee identity. Those are consumer evidence derived by the oracle or another explicitly versioned consumer.

The marker certifies that a producer preserved this occurrence under C1; the application predicate itself remains derived from canonical rows. There is no new canonical fact asserting what the grammar already says. A consumer can derive the eligible application set and compare it with the complete marker set. Under a C1 publication claim the sets must be equal; mere absence of annotations in an older publication is not false evidence of zero applications.

Emit precisely one logical marker per eligible occurrence. Duplicate identical transport deliveries may be deduplicated as the same immutable fact; duplicate entries in a declared canonical table, conflicting values, unsupported versions, nonexistent paths, non-application targets, and missing/extra rows under a complete-coverage claim are validation failures. Other frontend metadata is preserved independently.

Implementation may add this marker to temporary frontend map nodes before side-table projection, since the current extractor recognizes namespace `yang` (`vm.cljc:1183-1214`). Prefer deriving the relation directly from validated rows where map attachment would be redundant. `ast->semantic-bytecode` hashes only grammar slots and drops other node keys (`vm.cljc:1113-1123`); no grammar slot, instruction operand, canonical datom slot, or AST contract revision is authorized by C1.

### 2.3 Finalization and transformations

Finalize paths only after macro expansion, generated-name canonicalization, wrappers, module/SCC assembly, and tail marking that determine the named tree. Source-level annotations alone cannot cover generated calls (base ruling R2 at line 17). Low-level functions returning fragments may defer finalization until an owning root and origin exist; each executable/publication ingress must invoke the common finalizer. A frontend cannot claim adoption by marking just its parser or user-call lowering arm.

A subsequent tree-changing transform creates another root and another occurrence relation. It must preserve an explicit old/new occurrence correspondence where it makes an attribution claim, recompute C1 for new applications, and identify generated helper calls separately. It must not retain stale paths or equate every new helper with the nearest source call. If a transform deletes, duplicates, or merges occurrences, its correspondence must record the actual multiplicity; ambiguity refuses attribution.

Macro expansion already records input root, call path, macro root, parent, output and attempt (`src/cljc/yin/vm/macro.cljc:997-1000,1062-1119`). However, final tail marking and stand-in lowering happen later (`:1255-1260`). The implementation inventory must resolve how final published roots are related to these expansion outputs. Whether the existing event/record adapters already provide the complete final-root correspondence is **unknown — the implementation round's inventory decides**. Do not assume an intermediate output record identifies every occurrence in the final assembled tree, and do not mint a fake `:expand` merely to satisfy the shape.

### 2.4 Lowering, reconstruction, and runtime lookup

Persist the existing instruction relation `[segment-address pc origin root-address path]`. The row linearizer already returns `{:address ... :provenance [[pc origin root path] ...]}`; its caller supplies the segment coordinate (`semantic/linearize.cljc:188-214`; code-as-tuples §5.3 at lines 918–931). A call dispatch looks up its **own call instruction's** emitting occurrence, not the preceding operand's instruction or the callee's body. Multiple instructions may belong to one occurrence.

Segment+PC alone is insufficient when identical code is loaded under two origins. The runtime must retain its load/admission origin alongside its image reference when selecting provenance. This is an occurrence-scoped runtime context, not a new code hash. The native image derivations must supply or independently reconstruct a mapping from their call PCs back through semantic PCs to K; PC equality across formats must not be assumed. Current semantic `resolve` preserves PCs, register derivation preserves layout, and stack derivation emits a new sequence (`semantic/derive.cljc:21-42,60-93,154-172`).

Canonical reconstruction deliberately shares nodes by content (`vm.cljc:1477-1486,1522-1525`). Never cache an occurrence annotation by node-id or mutate a shared canonical reconstruction and expect distinct sites to survive. Keep an occurrence cursor/side lookup, or expand occurrence-specific ephemeral images. The datom linearizer currently returns `[pc entity]`, not K (`semantic/linearize.cljc:283-302`); use an explicit entity-to-occurrence bridge with the owning batch, root and path, refusing ambiguity. `encoder/datom-entity-occurrences` and the envelope's `:yin/entity-occurrences` are the starting machinery (`encoder.cljc:67,115-148`).

### 2.5 Ephemeral origin and trace field

Name the nonpersistent domain **ephemeral instrumentation origin**. Its C1 spelling is:

```clojure
[:ephemeral evidence-scope-token compilation-token member-index]
;; ephemeral site:
[ephemeral-origin canonical-compiled-root structural-path]
;; already signed trace shape:
[fname arity caller-site-id]
```

The composition supplies two non-nil plain-data tokens: `evidence-scope-token` names the recording/fixture scope; `compilation-token` names this particular instrumented compilation in that scope. `member-index` is a nonnegative integer. They are explicitly recorded inputs, not inferred from source hash, host object identity, time, lane name, or callee. Both oracle C lanes execute the SAME compiled artifact with the same tokens; a separate compilation gets another token. Replay uses the recorded tokens. A source hash alone would collapse distinct attempts.

This is a precise proposed spelling for the still-unfrozen oracle implementation. It is **not** a third legal persisted occurrence origin: `vm/occurrence-origin?` must continue rejecting it. An EDN evidence file may preserve an ephemeral id as evidence of that run; that does not convert it into a portable code occurrence. Persisted C1 side tables and expansion links require real ruled origins.

The canonical compiled root is computed after code-producing instrumentation that changes the AST, before occurrence stamps are associated into runtime maps. Runtime stamping must not introduce a root-hash self-reference. If instrumentation subsequently changes structure, it must carry an explicit transform mapping or recompute the frozen site table; no path can silently retain its old meaning.

The oracle must finalize the spelling in its own versioned evidence contract and use that format once. If its accepted schema differs by the time implementation starts, preserve its evidence verbatim and publish a reviewed binding of its origin fields to this semantic domain; do not rewrite its tables behind an unchanged version. A semantic conflict (no compilation scope, map-key paths, callee ids) requires correction on the oracle's record, not a silent normalization in durable verification.

Runtime `assoc` stamping is explicitly licensed by §2.6, owner ruling 2026-09-15: the walker holds an ephemeral image and nothing runtime-associated is projected back to canonical rows; runtime keys must be stripped at serialization/projection of continuation frames (`docs/design/yin.vm.code-as-tuples.md:459-474`). Static frontend marker extraction is a separate, pre-execution side-table operation. This clause licenses oracle-specific walker instrumentation; it does not establish ordinary production coverage.

Capture the caller K at actual application dispatch, after operator/operand evaluation, and preserve it until that callee's body-entry record. Nested argument calls must not overwrite it. Tail dispatch carries the new caller K even if it reuses a frame. A helper's call carries the helper's own K; no automatic inheritance of upstream credit. Unknown caller linkage yields no credit. Repeated recursion can record the same site many times; R3's consumer is existence-based, not a proof of invocation-by-invocation causality (`ruling-of-record.md:17-34`).

## 3. A2 inventory: revisions, preservation, and refusal

This is a design inventory of the reachable surfaces found in the inspected sources. The implementation's first deliverable refreshes file/symbol locations after the prerequisite merges, expands each wildcard artifact family into an exact list, and assigns every item “changed”, “read/verified”, or “not reached with proof”. Newly discovered producers cannot be excluded merely because this table predates their merge.

### 3.1 Contract revision decisions

| Contract | Decision for this design | Revision trigger and mismatch behavior |
|---|---|---|
| C1 frontend metadata | New reserved key/value version 1 and coverage validator. Existing five-coordinate relation is unchanged. | Unknown marker version or malformed/incomplete claimed annotation refuses C1-dependent attribution. Ordinary legacy code loading remains available. |
| Oracle evidence | The oracle owns the new versioned site-table and three-field trace contract. Durable work imports it verbatim. | Two-field evidence is not silently upgraded to three fields. Wrong schema/hash/fixture/lane/site-shape refuses attribution. Preserve old evidence as historical data. |
| Durable correspondence | New separate schema 1 below. | Unknown schema, missing evidence, failed reconstruction or ambiguous linkage refuses reliance, independently of code loadability. |
| UAST/row/datom grammar | Read and validate; no C1 change. Current AST stamp is `v3`. | Existing `check-contract!` and structural/hash checks remain. Do not add a canonical slot or broaden origins to accept ephemeral ids. |
| Semantic/stack/register execution contracts | Read and validate; no instruction-grammar change specified. Current stamps are `v4`, `b2`, `r2`. | Revise only if implementation changes instruction grammar or execution semantics. A new trace field in a harness stream alone does not revise these. |
| Code lowering profiles | Read, and verify unchanged code output. A separately versioned provenance recipe names any new side mapping. | Revise the relevant code profile if a pinned lowering rule or emitted code changes, even if execution grammar does not. A side-only addition must not masquerade as validation by the old derivation verifier. |
| Linker manifest | Keep schema 1 by publishing correspondence beside it. | Adding a manifest field requires a separately versioned manifest contract and validator; current closed keys reject it. No automatic schema-2 just because R1 is now active. |
| Existing `:derive` record | Read/reference; do not add correspondence keys to it. | Current closed key set rejects extra fields and non-`:derive` ops. Reusing the record-format container for a richer correspondence would require a new/extended format, not an incidental extra field. |
| Frontend catalog revision/profile | Change the frontend revision and relevant annotation/support profile when its publication/coverage promise changes. | Existing revision pinning and conflict/incompatible refusal remain; keep SPI 1 unless its actual request/response protocol changes. |
| Safepoint, site-set and binder contracts | Read; preserve distinct purpose and identities. | Revise only if their serialized content/selection/transform rules change. Do not alias `:yang/call-site` to `:yang/site`. |
| Continuation/UCF/checkpoint contracts | Read/inventory initially. | If a provenance-dependent consumer requires new serialized runtime state, inventory and version that actual state contract before shipping. A transient field cannot be silently serialized or relied on after migration. |
| Parser/CST and `yin.cic.spec` | Read only for C1. | No parser grammar/CST revision or CIC v1 change follows merely from side metadata; R4 explicitly leaves CIC v1 untouched. |

Current constants: `src/cljc/yin/vm.cljc:262-285`. Some design prose still says semantic `v3` (`docs/design/yin.vm.linker.md:1879-1884`); implementation inventory must use the current source stamp, not copy stale prose. Revision principles: `code-as-tuples.md:859-913`, `yin.vm.linker.md:1871-1921`. Concrete closed validators: `linker.cljc:731-759,841-871`. Frontend revision semantics: `yang/frontend.cljc:15-16,87-98,166-200`.

Whether some existing higher-level ledger/publication mechanism can carry this new correspondence without **its own** schema change is **unknown — the implementation round's inventory decides**. The narrower question is answered: the inspected linker's `record-defect` cannot accept extra keys under today's `:yin.ledger/record`. This design avoids making that compatibility claim.

### 3.2 Producer-to-consumer preservation inventory

| Surface and inspected locations | Required implementation work / read-only dependency |
|---|---|
| Clojure `compile-form`, `compile`, `compile-program`, `lower-unit`: `src/cljc/yang/clojure.cljc:2021,2164,2839,2993` | Cover native and argument-vector/outcome compilation, library/adapters, linked unit wrapping and global publication. Finalize at executable roots, after expansion. Existing fragment APIs need not invent origins. |
| TypeScript packet/body/unit/multi-unit/stage: `yang/typescript/antlr/lower.cljc:2262-2363` | Cover bundled and linked runtime paths, canonical names and tail marks, all units and generated runtime calls. Preserve packet/source association separately. |
| PHP ANTLR body/program/packet/module/SCC/stage: `yang/php/antlr/lower.cljc:3737,3759,3800,3840,3855,3929,3998-4041` | Cover includes, class-map units, strict-types variants, SCC bundles/aliases, helpers and linked/bundled paths. |
| Legacy PHP `compile-program/compile`: `yang/php.cljc:640-685` | Additional live frontend, not retired by the presence of ANTLR. Annotate all generated combinator/wrapper applications too. |
| Python module/body/packet/SCC/stage: `yang/python/antlr/lower.cljc:2018,2030,2097,2139,2198,2298,2334-2373`; session `repl.cljc:86-108` | Cover stateless and already-primed sessions, imports, bundles/aliases and generated body wrappers; first-session prelude coverage cannot stand in for subsequent submissions. |
| REPL frontend catalog: `yin/repl/frontends.cljc:24-110` | Covers single-form vs multi-form Clojure, legacy PHP, Python session lowering and Jolt native parser selection. Standard catalog does not currently register TypeScript; direct TS paths still require adoption. |
| Common UAST/prelude/call/module emitters: `yang/core/prelude.cljc:44-69,138-159`, `yang/core/call.cljc:184-191`, `yang/core/module.cljc:31-99` | These produce wrapper/helper/prelude code outside source-call arms. Apply common finalization at the owning publication, including direct hand-built UAST accepted as frontend output. |
| Common streaming stage: `yang/core/stage.cljc:25-59` | It currently supplies medium plus packet unit as batch token. Audit distinct re-admissions of the same unit: C1 requires fresh admission identity while retry keeps the token; packet identity alone is insufficient if reused for distinct admissions. |
| Encoder map/rows/datoms: `yin/vm/encoder.cljc:25-43,67-148,230-258` | Map projection emits side tables; already-projected rows currently return empty tables; envelope projection replaces table fields. Preserve/validate supplied metadata and finalize generated output instead of silently dropping it. Maintain batch member j. |
| Macro pipeline: `yin/vm/macro.cljc:997-1005,1062-1119,1196-1261` | Keep expansion attempt provenance, resolve final-root ownership, annotate generated occurrences and retain retrievable expansion records. Post-expansion is a required integration point. |
| Safepoint insertion: `yang/safepoint.cljc:234-256` | Current derived envelope explicitly clears frontend metadata, source positions and entity occurrences. New root needs fresh C1 plus verified input/output mapping; preserve the safepoint record's separate semantics. |
| Projection and row validation: `yin/vm.cljc:1113-1159,1217-1259,1262-1456,1589-1609` | Reuse grammar traversal, plain-data checks and occurrence enumeration. Add a side-table coverage validator, not C1 requirements to canonical row validation. Unannotated legacy rows remain valid. |
| Canonical and datom reconstruction: `yin/vm.cljc:705,818-851,1477-1555` | Side metadata does not reappear from content alone. Attach provenance context out of band; do not collapse occurrence ids via shared reconstruction/entity ids. Round-trip canonical hashes must remain equal. |
| Semantic row and datom lowering: `yin/vm/semantic/linearize.cljc:106-128,188-221,283-302` | Persist returned provenance, supply origins explicitly, bridge datom entities with occurrences; nil-origin legacy calls remain loadable but cannot satisfy C1 attribution. |
| Native lowering: `yin/vm/semantic/derive.cljc:21-42,60-120,154-184` | Produce/reconstruct complete native-call-PC provenance; compose with semantic mapping. Sparse variable/closure diagnostics do not establish call coverage. Inventory any legacy direct de Bruijn lowering still reached after merges. |
| Runtime dispatch and resume: `yin/vm/ast_walker.cljc:273-332,493,625-711,776`; `yin/vm/semantic.cljc:102,142,333-380` | Cold/hot application routes, operator/operand frames, linked image attachment and resumption all need occurrence context for traced execution. Native engines and UCF lifting must be audited too; no claim they already preserve caller K. |
| Existing derivation verification: `yin/vm/ledger.cljc:156-215`, `yin/vm/linker.cljc:2164-2255` | Existing verification re-lowers code and compares identities, not side metadata. Keep code verification and add independent provenance/correspondence verification. `:trusted` does not satisfy this new acceptance gate. |
| Generic publication: `yin/vm/linker/publish.cljc:115-218` | Currently takes only `:vector` from `project-rows` at line 157. Publish complete side artifacts and origin/record closure beside code; do not discard provenance or change manifest keys casually. Validate before making a C1 availability claim. |
| Unit publication: `yang/core/publication.cljc:92-170`, PHP `publication.cljc:27-70`, Python `publication.cljc:27-80` | Publish records for actual bundle roots; aliases cannot borrow unrelated paths. Coordinate side artifacts with index/discovery publication. PHP currently has nil site-set/binder hooks; Python's site-set hook is safepoint-specific. |
| Site-set discovery/signing precedent: `yang/python/antlr/siteset.cljc:5-31,65-101` | Reuse the separate-record-by-manifest pattern and declared-principal verification where appropriate. Its exact key schema is not the caller-site or oracle schema. Inventory discovery/fetch/retention paths for the new record explicitly. |
| Code index / republishing: `yin/repl/ast_index.cljc:3-24,108`; `yin/vm/linker/publish.cljc:225-300` | Query views can be rebuilt from rows but cannot recover omitted admission provenance. Session/index-to-module publication must preserve or explicitly remap occurrence roots, and retain original evidence sources. |
| Oracle instrumentation/accounting: `test/yang/latte_oracle_test.clj:1786-1855,2341-2405,2432-2468` | Oracle-owned trace migration, offset retirement across the full family, both lanes, refusal pins, gate accounting and evidence regeneration per R5/R6. Audit table derivation independently per R7. |

### 3.3 Persisted artifacts and publication blast radius

“Side tables are additive” is the compatibility policy, not permission to skip republishing coverage evidence. **Unannotated persisted artifacts remain loadable under their supported existing code contracts.** A provenance-requiring query returns missing provenance/refuses credit; it never invents identity from names, hashes, arities, values or source lines. Old provenance may be reconstructed only when the actual admission/expansion inputs are retained, and the result is a newly verified artifact.

The implementation inventory must enumerate, regenerate where changed, and verify these families on **all four host lanes**:

1. `yang.call`, Clojure `clj` and its library, TypeScript `js/jssp`, Python `py/pysp`, and PHP `php` plus its hook/runtime publications. Shared emitter anchors: `yang/core/call.cljc:184`, `yang/clojure/prelude.cljc:125-143`, TS `prelude.cljc:4168-4260`, Python `prelude.cljc:3645-3739`, PHP `prelude.cljc:5328-5411`. Include PHP's definition lists `prelude_module.cljc:386` and `prelude_p9.cljc:558`; their inclusion must not escape measurement because they are emitted through the main prelude.
2. For each host publication: canonical row closure, semantic vector, stack/register stored images and checksums, derivation records, schema-1 manifest, pinned dependency addresses, new C1 side tables/provenance/correspondence, and discovery/index/name/signature records where used. The actual publication sequence is `yin/vm/linker/publish.cljc:156-209`. Side-only annotation should leave old code addresses unchanged; measure this rather than assume it. New side objects still need publication, retrieval and retention.
3. Fresh process-local prelude caches, linked harnesses and golden addresses: TS `test/yang/typescript/antlr/linked_harness.cljc:55-78`, Python `test/yang/python/antlr/linked_harness.cljc:55-77`, PHP `test/yang/php/antlr/linked_harness.cljc:38-56`. Reusing a realized pre-change delay is not a republication test.
4. Imported user modules, SCC bundles and aliases, class maps, safepointed derivatives, session/module exports, signed discovery entries and DHT fixtures. Specifically `test/resources/yang/python/dht-import-v1.edn` and its generator `dht-import-v1.generate.clj:1-20`, which says to regenerate whenever lowering/publication/prelude changes addresses. Preserve old fixtures as compatibility inputs where practical, rather than rewriting away the only legacy-load test.
5. Oracle evidence: new versioned caller-site tables; `slice9_evidence.edn`, `slice9_verification.edn`, `slice9_declarations.edn`, `slice9_unexercised.edn`, `fixture_closure.edn`, gate markdown and captured verification/test outputs; `context.edn`, `kernel_gate.edn`, `manifest.edn` and earlier slice evidence as input/history or regeneration targets according to actual dependency. Every changed trace-bearing field/pin must be listed; blanket rewriting of historical evidence is not required.
6. Explicitly inventory `test/yang/latte_oracle/throw_sites.edn:1-7` and `case_arms.edn:1-11`. They currently contain bare vectors of source inventory maps, with no top-level schema stamp. Their producer is `src/dev/latte_oracle_tables.clj:15-24,52-64`; throw-site equality checking is `latte_oracle_test.clj:679-699`. They are **read/validated unchanged** if their meaning/shape remains unchanged. If caller-site coordinates or new fields are added, version their evidence contract and update producer, validator, readers, generators and pins together. They are not automatically the R1 versioned application-site table, and source `:line` is not K.

The addendum names TypeScript `publication.cljc`, but no `src/cljc/yang/typescript/antlr/publication.cljc` exists in the inspected checkout. TS prelude publication is presently visible in its linked harness above; its design describes later publication work (`docs/design/yang.typescript.md:1347,1545`). Whether an in-flight seat supplies a new file, renamed path, or equivalent hook is **unknown — the implementation round's inventory decides**. It remains a named mandatory inventory item, not a silent omission or an invented present file.

### 3.4 Failure separation

Preserve the existing failure categories: malformed canonical content fails structural validation; wrong bytes/address fails hash verification; unknown execution contract refuses load; differing lowering profile reports profile mismatch or `:unverified-derivation`; wrong recomputed output reports derivation mismatch (`vm.cljc:283-285`; `ledger.cljc:156-180`; `linker.cljc:2175-2215`). New evidence-specific failures must be distinguishable: unsupported C1/evidence version, missing provenance, bad occurrence, incomplete coverage, wrong source/adaptation hash, missing lane, ambiguous correspondence, stale declared site, or reconstruction mismatch.

A corrupted required provenance artifact refuses the provenance-dependent operation even if code itself verifies. An absent optional annotation does not make old code unloadable. A signature authenticates a publisher; it does not verify a correspondence. A trusted code link is not evidence of a recomputed correspondence.

## 4. R1 durable correspondence schema D1

### 4.1 Additive envelope

Define a new separately addressed plain-data object. The following field names and types specify D1; angle-bracket placeholders are metavariables, not values to persist.

```clojure
{:yang.correspondence/schema 1
 :yang.correspondence/evidence <Jing address of OracleEvidence>
 :yang.correspondence/recipe <Jing address of reconstruction recipe>
 :yang.correspondence/inputs [<Jing address> ...]
 :yang.correspondence/roots [<canonical UAST root address> ...]
 :yang.correspondence/origin-bindings
 [[<ephemeral site id from OracleEvidence> <persisted occurrence key>] ...]
 :yang.correspondence/artifacts
 [{:format <existing format keyword>
   :identity <that format's identity>
   :storage <Jing address of retrievable artifact>
   :derivation <Jing address of applicable derivation record>} ...]
 :yang.correspondence/metadata <Jing address of C1 frontend-metadata rows>
 :yang.correspondence/instruction-provenance
 [<Jing address of an instruction-provenance relation> ...]}
```

The D1 key set is closed; vectors preserve declared order and contain no duplicate declarations. Empty artifact/provenance vectors are allowed for a tree-only consumer, whose claimed coverage must say tree-only. `artifacts` lists lowered images only; canonical roots are retrievable row closures listed separately. Native image identity need not equal its storage address: the publisher already distinguishes checksums and stored images (`publish.cljc:159-185,204-208`). The record's address is the ordinary Jing content address of the whole map; no self-address field.

`evidence` resolves to **OracleEvidence verbatim**: same EDN data shape, schema/version, field names, vectors, site ids, source-hash value and classifications. Do not project a subset or copy its rows into a second durable site-table shape. Store the whole value through a lossless codec and prove round-trip equality; preserve the original checked-in EDN bytes separately as a source artifact when byte-level archival is needed. Jing storage addressing does not replace the oracle's own canonical-source-hash algorithm. New adoption evidence must use the oracle format as frozen; unsupported representational needs require an explicit versioned extension owned by that format, never an ad hoc competing table.

`inputs` names the retrievable source/adaptation/fixture/profile inputs required by the recipe, including the inlined adapter and declarations that the oracle actually hashed. A repository commit string alone is not a fetchable reconstruction input. `roots` names the produced code trees. Expansion records and admitted source-envelope identities required by the bindings must be in the retrievable input closure. The input/source artifacts may be data blobs; they must not be represented as UAST addresses unless they actually are UAST.

The recipe is a closed versioned data description, proposed as:

```clojure
{:yang.correspondence.recipe/schema 1
 :yang.correspondence.recipe/function <qualified interpreter identifier>
 :yang.correspondence.recipe/profile <address of exact reconstruction profile>
 :yang.correspondence.recipe/input-roles [[<role keyword> <input index>] ...]
 :yang.correspondence.recipe/evidence-contract <oracle's exact schema identity>
 :yang.correspondence.recipe/coverage <address of declared coverage policy>}
```

The recipe profile pins frontend/compiler revision, grammar/options, expansion environment and recorded origins/attempts, all code-producing transformations, source canonicalization, site-table derivation, and lowering/provenance rules. The consumer must implement that exact recipe/profile. A missing upstream canonicalization rule is not repaired by choosing a plausible hash. The exact oracle schema identity remains the dependency in §1.1.

`origin-bindings` is an added relation binding evidence-local sites to actual durable occurrences; **it is not a replacement attribution table**. It preserves the oracle's ephemeral ids literally. Validate every endpoint, reconstructed tree and path. A site's mapping must be unique within the claimed compilation. Multiple replay records can name the same durable occurrence only where they explicitly replay the same admission; distinct admissions must remain distinct. An oracle run without durable expansion/source records cannot be promoted merely by changing `:ephemeral` to `:expansion`. It may remain useful oracle evidence without a durable binding. To accept a relying consumer, reproduce the actual compilation under recorded durable admissions/expansions and verify its correspondence; do not retroactively assert an unrecorded event.

### 4.2 Independent reconstruction and discovery

The D1 producer is the compilation/publication composition that can retain inputs, origins and outputs. The canonical compiler and lowerer remain oblivious to attribution policy except for the side artifacts they emit. D1 is an ordinary record on a separate publication/ledger stream, discoverable by its address and its code/evidence associations. The Python site-set record is a concrete precedent for “record names manifest, manifest keeps schema” (`siteset.cljc:5-22,79-101`). The implementation must pin the actual discovery schema, signature/index projection and retention owner before acceptance; this document does not pretend the current linker automatically fetches D1.

Verification proceeds in this order:

1. Fetch D1, recipe, OracleEvidence and every required source/row/provenance artifact; verify their addresses and structural schemas. Reject an unsupported oracle schema before interpreting any row.
2. Verify each code image under its own execution contract and its existing derivation chain. Report content integrity separately from code derivation.
3. Reconstruct the source/adaptation compilation under the exact recipe, using recorded source admissions and expansion attempts; recompute final roots and eligible occurrence sets. Recompute the oracle's canonical-source-hash using its exact rule.
4. Independently re-derive the oracle site declarations from compiled AST argument expressions. Compare with OracleEvidence verbatim under that schema's comparison rules. Do not make the producer's serialized classifications the verifier's input oracle.
5. Reconstruct every claimed ephemeral-to-persisted binding, C1 marker set and instruction mapping. Verify exact coverage and all multiplicities, including helpers that receive no upstream credit.
6. Execute the attribution query over separately scoped evidence/occurrence/row relations; require actual body entry in both required oracle C lanes. Reconstruct the credit set and report withdrawn witnesses and stale declarations. R7 requires re-derivation, not just re-running a query over trusted declarations (`ruling-of-record.md:48-50`).
7. Publish a separate verification result naming D1's address, recipe/profile, verifier revision, inputs, outcomes and evidence. D1 cannot mark itself “verified”. The first additional consumer's acceptance must cite this completed result.

An implementation may share the schema decoder and canonical primitives, but a verifier must not call the producer's table derivation as its only “independent” check. The existing table generator resolves the oracle's own private derivation vars (`latte_oracle_tables.clj:15-18`); regeneration through it alone does not satisfy R7.

D1 does not revise old site tables, force old module manifests to schema 2, or automatically validate the oracle's historical evidence. If actual integration requires changing a closed publication record, that specific schema revision returns to the A2 inventory. Unknown recipes refuse verification; `:trusted` is never substituted for `:verifying` (linker design `:1902-1921`).

## 5. A3 adoption briefs and ordering

**Scheduling:** frontend adoption starts after the in-flight cell-ref sweeps and the J2 re-run land. Refresh this inventory on that merged base before changing shared prelude/publication paths. The current sweeps, J2 and oracle continue under their existing scopes. This design does not merge, pause, or extend their tasks (signed A3/A4 at lines 21–25).

Each adoption brief must cite this findings file and C1/D1 by revision. The shared occurrence finalizer/validator and provenance publication work precede or accompany frontend implementation; D1 independent verification precedes acceptance of the first frontend consumer that relies on D1. A frontend may adopt static annotation without consuming oracle attribution, but cannot call that durable correspondence acceptance.

### 5.1 Mandatory host sets

These are acceptance requirements, not claims that current tests already pass. **No frontend may drop Jolt because shared code compiled on another host.** Host lanes and VM execution formats are separate axes.

| Frontend | JVM | Node | Dart | Jolt |
|---|---|---|---|---|
| Production Clojure | Reader/source, all production entry points, publication/load/query and trace fixtures | Same portable source/forms and paths, publication/load/query and trace fixtures | Same | Same; run current reader limitations as explicit refusals, not substitute parsers |
| TypeScript / JS dialect | Local source parser plus portable CST lowering, bundled/linked/prelude publications and load/query | Same pinned source-derived CST through portable lowering; parserless import/load; fresh prelude publication | Same packet/import/publication gate | Same packet/import/publication gate; Jolt load/parity is required |
| PHP ANTLR + legacy PHP | Local ANTLR source parser, portable packet paths, includes/class-map/SCC, both compiler families and publications | Same source-derived CST for ANTLR; legacy source compiler; fresh publications and imports | Same | Same packet/legacy source/publication/load gate |
| Python | Local source parser, packet/module/import/SCC/session paths, publications | Local generated parser plus all packet/session/publication paths | Local generated parser plus all packet/session/publication paths | Native C++ Python parser plus packet/session/publication/load paths; unavailable parser is a failed required source gate with a recorded reason, not a green skip |

TS local parser restriction is recorded at `docs/design/yang.typescript.md:113-116,517-518`; its packet-level execution policy at `:1325-1326`. Python's host selection is implemented in `yin/repl/frontends.cljc:71-110`; Jolt's native Python adapter describes its actual parser and refusal in `src/jolt/yang/antlr/cst.jolt:1-28`. PHP's local ANTLR parser is `src/clj/yang/php/antlr/cst.clj`, while the independent portable legacy compiler remains registered at `yin/repl/frontends.cljc:69-70`. Existing Jolt applicability exclusions are explicit per-test data (`test/resources/yin/repl/jolt-applicability.edn:1-5,46-48`); they cannot be expanded to evade these new gates.

On each host run static occurrence/provenance checks for AST, semantic, stack and register artifacts where the existing format admits the fixture. Execute direct walker and all three vector paths for the portable correctness corpus. Linked paths exercise all supported linked formats and pin existing unsupported/refusal cases explicitly. For example, the inspected TS linked harness documents a walker `:undeclared-free` limitation while using three vector VMs (`test/yang/typescript/antlr/linked_harness.cljc:11-15`); this design does not silently promise that old unrelated limitation is fixed. If a missing lane prevents the required new provenance test, report adoption incomplete with the named dependency.

### 5.2 Cross-host equality without collapsing admissions

Build a portable replay fixture containing the SAME source or source-derived CST, grammar/options, compiler/profile revision, canonical naming inputs, source-medium/batch/member coordinates, and expansion environment/attempt inputs. The source origin contract explicitly makes these coordinates supplied plain data (`code-as-tuples.md:405-437`; `vm.cljc:938-958`).

Require:

- Identical final canonical roots and the full set of `[origin root path]` keys on JVM, Node and Dart; include Jolt in the same comparison for the mandatory portable paths.
- Identical C1 rows, reconstructed site declarations, and per-format instruction provenance. Different formats have different PCs, but they must resolve to the same intended application K.
- For expansion fixtures, identical expansion-record addresses under replayed identities and identical producing-record chains. Normalize neither origin nor generated names away after the fact.
- For TS/PHP packet routes, compare compilation of the same source-derived validated CST; record the JVM parser artifact. This proves compilation equality, not nonexistent local-parser parity. Python additionally compares source parsing/export across its real local parsers, following the existing exporter conformance model (`test/yang/antlr/conformance.cljc:1-20`).
- A separate negative fixture admits identical text twice with distinct supplied admission tokens and demands different origins. Cross-host equality is replay equality; it must not turn occurrences into content identities.

Host-specific source/reader conditionals that intentionally yield different programs must have separately declared cohorts and expected refusals. They cannot explain divergence for the identical portable input cohort. Include floats, metadata, generated temporaries and duplicate trees in the portable cohort because root equality is part of the gate, not just path equality.

### 5.3 Frontend-specific work packages

**Clojure.** Inventory native/argument-vector/outcome compile options, `compile` vs `compile-program`, `compile-form` callers, macro expansion, `lower-unit`, `library-for`, direct walker/datoms/rows lanes, REPL single/multi-form and linked runtime publication. Show a non-oracle production fixture traversing the full pipeline, including macro-generated and helper calls. Republish `clj` and `yang.call` on each host. Oracle-specific stamping is instantiation #1 only; it does not close this brief. Anchors: `yang/clojure.cljc:2021-2034,2164-2200,2791,2839-2862,2993-3021`.

**TypeScript.** Cover `lower-module-body`, `lower-unit`, `lower-units`, `lower-packet`, streaming stage, bundled and linked `js`, `yang.call`, and safepoint runtime. Track new publication/SCC/session paths from the seats when they land; do not infer their implementation from design prose. Generated member/constructor/runtime dispatch applications get their own K; source-call attribution is a separate query. Anchors: `lower.cljc:1167-1211,2262-2363`; `prelude.cljc:4168-4260`.

**PHP.** Cover ANTLR and still-registered legacy compiler, include/classmap/import publication, SCC bundles/aliases, strict-types variants, prelude definition lists and P9 additions. Test by-reference/indirect calls, generators and exceptional helper paths without borrowing source-call credit. Republish `php`, dependencies and all include/import fixtures. Anchors: `php.cljc:640-685`; ANTLR `lower.cljc:1710,1812,2082,2207,3737-4041`; `publication.cljc:27-70`; `prelude_module.cljc:386`; `prelude_p9.cljc:558`.

**Python.** Cover packet and module lowering, import units, SCC aliases, bundled/linked `py`, `yang.call`, `pysp`, hook staging/site-set discovery, and both fresh/primed REPL sessions. Do not conflate `:yang/site` hook sites with complete application occurrence coverage. Republish the DHT fixture and validate parserless imports on other hosts. Anchors: `lower.cljc:1111-1120,1775,2018-2373`; `repl.cljc:86-108`; `publication.cljc:51-80`; `siteset.cljc:5-31`.

Each brief's acceptance bundle contains: exact producer/artifact checklist; input/commit/profile pins; C1 coverage counts and root/hash invariance; four-host results and cross-host equality report; correctness battery results; before/after budgets and measurements; legacy-load/missing-provenance refusal; and D1 verification result if it relies on correspondence. No frontend can satisfy this bundle merely by running the oracle test.

## 6. Measurement and declared budgets

These are measurement requirements and design cost models, **not measured performance claims**. Signed A3 requires a budget impact per frontend, and line 29 requires static/runtime separation. This read-only design has no authority to invent benchmark results. Numeric per-corpus totals remain **unknown — the implementation round's inventory decides by measuring the pinned baseline before tagged runs**.

### 6.1 Counts and cost model

For each fixture/prelude/module, host, and format record:

- `U`: distinct canonical rows; `O`: all occurrence-tree positions; `A`: `:application` occurrences; `D`: maximum path depth; `L`: total encoded length of application paths; separate user, helper, wrapper, prelude, macro-generated and Rule-R counts as derived diagnostic groups.
- `P_f`: instruction count/provenance-row count for format f; report shared semantic/native mapping structures separately if used.
- `S_static`: exact encoded bytes of C1 rows, provenance tables, retained origin records, D1 and its reconstruction inputs; distinguish fresh bytes from already retained shared content, index memory and storage/dedup savings.
- `E`: actual callee-entry records, per-lane total bytes and maximum record size; `Q_peak`: peak retained trace records and bytes, dropped/blocked writes and drain schedule.
- Guest instruction counts and host-side work counters; wall compile/expand/project/publish/verify/load/query time; process/heap peak and retained memory, including trace buffers, indexes and pending continuations.

Occurrence counts can greatly exceed unique row counts because identical subtrees are shared; an `O(U)` annotation-size promise would be false (code-as-tuples `:393-404,636-665,947-949`). A direct marker representation costs one row per A plus encoded paths/origins: storage proportional to `A + L + coordinate bytes`; traversal work proportional to visited occurrences, not unique content. Instruction mapping adds up to `P_f` rows per claimed format with path/coordinate payload. The exact peak depends on streaming vs retaining both trees/tables and must be measured.

### 6.2 Experiment separation

Use the same pinned code-producing inputs and three configurations:

1. **B — baseline:** existing code, trace disabled.
2. **S — static annotation:** C1/provenance production, persistence, loading and query enabled; runtime trace disabled.
3. **T — tagged tracing:** same S outputs plus caller propagation and body-entry recording.

Also compare the oracle's existing two-field tracing with T to isolate the extra caller-site field from the original body-entry instrumentation. Report S−B and T−S separately, and the old-trace→tagged-trace delta. If instrumentation itself changes code, keep its canonical image and compile cost separate from side-only annotation. Static C1 must change **zero canonical code bytes/addresses and zero guest instructions** for otherwise identical input; runtime tracing has no such zero-cost claim.

Measure cold and warm compile/load/query separately; use fresh publication caches for cold tests, fixed workload/repetition count and host/version, and publish distribution plus raw samples rather than an unqualified single timing. Report query indexes/build time separately from query execution. Do not call a host trace hook “zero overhead” because guest instruction count did not increase.

### 6.3 Bounds published before any tagged run

The implementation preflight record must contain actual finite numbers (no “TBD”, unlimited trace, or post-run adjustment) for:

| Bound | Required derivation / failure |
|---|---|
| Maximum input rows, occurrences, A, D and total path bytes | Derive from the pinned static corpus; reject over-bound traversal before instrumentation. Include generated/bundled prelude expansion, not only source calls. |
| Static side bytes and retained memory | Measure S without tracing; declare maximum per artifact/module and aggregate closure. Serialization size may be computed exactly before execution. Stop/revise on exceeding the declared storage/memory bound. |
| Maximum records E and trace capacity C | Bound accepted trace writes for the fixture, declare C in records AND bytes including worst allowed site-id/path length, and declare drain behavior. No credit from a truncated/overflowed trace. |
| Guest instruction ceiling and added work | Bound trace instructions per event/dispatch under each executed format, plus fixed setup/drain costs. Declare a ceiling for each host/format run before T; report baseline, absolute delta and ratio. |
| Compile/expand/publish/verify/load/query time | Declare finite absolute time limits and allowed deltas against B/S in the preflight record. Pilot B/S measurements may set these; a tagged timeout is a failed bounded run. |
| Peak process/heap/trace/index/continuation memory | Declare finite ceilings and measurement method; if a host lacks a heap counter, use a named process-memory measure and mark comparability limits. Missing measurement is not zero usage. |

For the oracle specifically, current code uses trace capacity **1,000,000 records**, drains after halt, and allows **4,000,000 guest instructions per C lane** (`latte_oracle_test.clj:1827,1847-1854,2453-2454`). These are the inspected baseline settings, not evidence the tagged run fits. Its own note records 2,024,912 walker and 1,105,792 semantic steps for the older binding fixture at line 2454. Restate both ceilings, the new record byte bound and the occupancy argument before T. If the implementation cannot establish enough capacity under those ceilings, publish revised bounds and their justification before running tagged evidence. Never silently raise fuel to get credit, or let a full trace stream park the fixture and misclassify it as semantic failure.

For other frontends, no oracle 4M/1M setting is inherited by default. Their adoption preflight must give numeric ceilings for each required fixture/prelude/host and format. Exceeding a declared bound blocks that acceptance run; changing bounds creates a new versioned run declaration and preserves the failed run.

### 6.4 Budget impact allocated per frontend

| Frontend | Mandatory static budget scope | Mandatory dynamic budget scope |
|---|---|---|
| Clojure | Native and argument-vector/outcome roots, macro outputs, guest library, linked `clj/yang.call`, repeated REPL admissions; `A_clj + L_clj + sum(P_f)` plus retained expansion/input closure | Nested/multi-arity/indirect/helper/tail calls in both production and oracle-specific fixtures; oracle owns its existing ceilings |
| TypeScript | User units plus bundled and separately published `js/yang.call/jssp`; source→packet and packet→UAST timing separately; include post-seat publication additions | Constructor/member/indirect dispatch, exception helpers and admitted suspension paths; prelude/helper volume counted |
| PHP | Both compiler families, includes/class-map/SCC, complete `php` including module/P9 definitions and shared runtime; peak publication closure | By-reference and indirect dispatch, include/autoload helpers, generator and exception paths |
| Python | Fresh/primed session roots, imports/SCC, `py/yang.call/pysp`, site-set staging and DHT publication closure | Indirect descriptors, generated call dispatch, generators and hook/stream suspension paths |

The storage/work formulas are the design's budget impact; numbers require the pinned post-sweep corpus and are not asserted here. Shared `yang.call` must be reported once as retained shared content and separately as each frontend's dependency, avoiding both double charging and disappearance. Prelude republishing on four hosts is mandatory even when code hashes remain unchanged: the new occurrence/provenance artifacts need independent production/load evidence.

## 7. Correctness and refusal gates

Every gate compares trace-disabled program outcomes first, then occurrence/provenance correctness, then any attribution. A marker is not proof of execution. These extend the signed oracle battery; they do not replace its seven cases and stale-table diagnostic (`ruling-of-record.md:23-34,64-69`).

| Gate | Required witness and refusal |
|---|---|
| Duplicate content / admission | Two identical applications at different paths; identical roots at two batch indices; identical source admitted twice; identical expansion outputs from two attempts. Distinct K where required, shared content allowed. Tamper one origin/root/path and refuse cross-borrowed evidence. |
| Macro generation | A call introduced only by expansion, nested expansions, repeated expansion attempts, final tail/stand-in transformations, and unexpanded source retained in the same compilation. Reconstruct real producing records; raw-source site counts cannot pass. |
| Operand/operator nesting | `f(g(), h())` and an operator expression that itself calls a function. Each entered body records the caller application's K; outer pending K survives inner evaluation. If operand evaluation fails, no outer callee entry is invented. |
| Helper/adapter delegation | A source-originating call and its adapter/helper/self-delegation use distinct Ks. Oracle `/3` self-delegation is unmapped/no credit; helper executions never donate credit to an unexecuted source site. |
| Indirect application | Multiple runtime callees from one site and one callee from multiple sites. Static site exists independently of callee resolution. Unlinked indirect evidence refuses by default; resolve linkage without requiring a literal callee name. |
| Tail calls and recursion | Direct/mutual tail calls, non-tail recursion, generated trampolines/adapters and hot/cold dispatch. Frame reuse must not retain the previous caller's K; repeated K is allowed, stack growth need not be introduced. |
| Suspension/resumption | Park during operator evaluation, operand evaluation, before body entry, and inside the callee; ready-queue interleaving of two callers; resume/continuation re-entry. No global last-call variable; capture/restore caller context with the correct continuation. Resuming a body is not automatically a new body-entry event. |
| Serialized continuation / migration | Strip ephemeral map-node stamps at canonical/continuation projection as §2.6 requires. If a consuming trace mode promises post-resume attribution, verify the separately carried durable context and its source artifact on restore. Missing context refuses attribution; do not claim migration support by retaining forbidden runtime keys. |
| Reconstructed/shared/linked code | Load identical code under two origins; reconstruct shared row nodes; load from published preludes and another host's artifacts. Caller K is selected using the invocation's origin, never a node-id or bare segment/PC cache. |
| Transform/staging | Safepoint insertion, module/SCC wrapping, imported code, alias projection and name canonicalization cannot leave stale roots/paths. Prove correspondence or refuse; annotate new helpers independently. |
| Oracle disjointness | Explicit `/2` with empty value does not credit `/1`; unexecuted ambient beside executed explicit cannot borrow; wrong adapter canonical-source-hash and missing C lane refuse; declared ambient with non-ambient compiled argument fails; indirect unlinkage refuses; variable/derived context shape defaults to refusal or `/2` only. |
| Stale table and schema | A declared id never observed in a trace produces the recorded diagnostic; unsupported versions, fabricated expansions, extra/conflicting/incomplete marker rows, missing retained inputs and wrong profiles produce distinct failures. Do not silently treat malformed evidence as an honest unexecuted site. |
| Canonical invariance / old artifacts | Adding/removing static C1 metadata leaves canonical rows, roots, instruction vectors and executable outcomes unchanged. Supported unannotated artifacts still load; C1-dependent credit refuses as missing. |
| Trace completeness | Buffer exhaustion, loss, interrupted run, missing trace lane or partial drains never become positive attribution. Trace-writing infrastructure must not recursively instrument its own bookkeeping into an unbounded trace. |

The relevant runtime paths are real, not hypothetical surface names: walker `apply-function` and operand frames (`ast_walker.cljc:273-332`), park/resume and ready queue (`:214-231,467-473,625-711`), semantic restoration (`semantic.cljc:102-130`), and native `:call/:ffi-call/:resume` lowering (`semantic/derive.cljc:92-102`). Frontend-specific suspension fixtures must use supported operations; unsupported language constructs remain explicit refusals. Continuation replay can produce repeated execution events, so the three-field trace is deliberately insufficient for invocation causality; D1 must not claim more than the oracle's existence-based attribution.

## 8. Acceptance sequence and owner risks

The design deliverable selects C1 and D1, enumerates the known integration surfaces, and gives each frontend a bounded adoption brief. Implementation remains outside this round.

Acceptance sequence:

1. Oracle owns and records its versioned evidence format, origin spelling and exact source-hash/argument-shape derivation. Resolve §1.1's absent schema pin; import it verbatim into the D1 implementation.
2. After the cell-ref sweeps/J2 land, refresh the exact producer, validator, artifact and host inventory; identify every changed contract, every read-only dependency, and any required format revision. Resolve final expansion-root ownership and native provenance completeness.
3. Implement common occurrence/side-table production, preservation, verification and publication; retain old artifact loading. Declare numeric run bounds from the pinned baseline before tagged runs.
4. Implement and independently reconstruct D1, including the oracle's actual tables and source/adaptation linkage. Publish verification evidence before accepting the first relying consumer.
5. Accept each production frontend only after its complete four-host brief, cross-host equality, measurements and correctness/refusal battery pass. Preserve any unsupported lane or withdrawn witness as a failure/dependency, not an unreported skip.

Risks requiring the owner's attention:

- **One format, not two:** no stable caller-site evidence schema was found in the inspected oracle worktree. D1's payload contract is therefore deliberately bound by reference, with an explicit schema-freeze landing prerequisite. This design does not claim verbatim field-level integration has already been verified.
- **Real origin ownership is unfinished:** final macro output, later safepoint transforms, SCC assembly and repeated unit admissions can change roots or reuse tokens. Portable identities need actual retained records; shape-valid fake origins would defeat the ruling.
- **Preservation is not currently end-to-end:** encoder replacement/empty-table paths, safepoint clearing, publication's discarded provenance and content-shared reconstruction are concrete integration hazards. Native and continuation paths need their own reconstruction evidence.
- **Prelude/host budget is unmeasured:** four-host republication is the broadest artifact obligation. No numeric annotation/trace overhead or passing lane is claimed by this design; preflight must record actual per-frontend bounds.
- **Live-source drift:** the workspace advanced during inspection and the named TypeScript publication file is absent here. The post-sweep inventory must reconcile source, current contracts, new files and citations before implementation acceptance. The signed v3 “BEFORE acceptance” requirement governs throughout.

Architect verdict: **proceed with implementation/adoption briefs using this design, subject to the explicit inventory and evidence-schema landing prerequisites above; do not grant implementation or frontend acceptance from this document alone.** No canonical AST slot or blanket execution/module-schema bump is proposed.
