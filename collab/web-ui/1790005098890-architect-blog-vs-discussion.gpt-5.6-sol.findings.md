Completed-GMT: 2026-09-21 15:38:?? GMT  
Completed-Local: 2026-09-21 22:38:?? +07 (Indochina Time)  
Coding-Agent: codex  
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a  

The blog is justified as a statement of architectural intent, but not as an unsourced factual account of the current implementation.

## 1. Is the blog justified by the discussion?

### Supported by both discussion and repository

These claims are justified:

- Named Universal AST and De Bruijn projection coexist.
- The projection is derived, lossy, non-bijective data used for alpha-equivalence, Merkle identity, and deduplication.
- Causality flows downstream from the named AST; projected data must not mutate the source form.
- “Interpretation Creates Semantics” is a real foundational axiom (`docs/design/datom.world.md:14-19`).
- The named form preserves source-level names and remains the renderable/query-oriented representation.
- Bound local renames can preserve projected identity.

These correspond to `docs/de-bruijn.md:7-12` and `docs/design/yin.vm.debruijn-projection.md:16-18, 22-25, 98-104`.

### Discussion-supported but only speculative or future

The discussion itself presents these as future possibilities, but the blog states or implies them as present capabilities:

- Datalog queries directly over `:yin.debruijn/*` projection facts.
- Grouping projected programs by root fingerprint and joining back to named programs.
- `yin.vm.linearize-de-bruijn`.
- A purely De Bruijn compiler or VM.
- Remote execution of deduplicated projected code without named semantic tuples.
- A persistent diagnostic side-index joining projected identities to named sources.

The discussion’s own lines 24-31 are the source of these claims. They must be labelled designed or speculative, not implemented today.

### Unsupported by both discussion and repository

These go beyond what the discussion establishes:

- Present global distribution or cross-node code sharing.
- Dependency-by-hash references for yin.vm.
- The assertion that all variable renames preserve identity.
- The assertion that the current projected store is already a Datalog-native relational database.
- The claim that arbitrary structural querying is impossible in Unison.
- The claim that Unison is merely a fragile UI side-dictionary.

The discussion is therefore itself a source of several errors, rather than evidence that makes the blog factual.

## 2. Accuracy of `docs/de-bruijn.md`

- **Projection storage:** inaccurate in present tense. D5 writes a projected envelope through `dao.jing/materialize!`; the envelope contains projected datoms, but there is no current DaoSpace relational index over those records (`src/cljc/yin/vm/pipeline.cljc`, `docs/design/yin.vm.debruijn-projection.md:261-265`).

- **Diagnostic side-index:** not implemented. The design says the source-to-projected mapping is ephemeral by default and separate only if later persisted (`docs/design/yin.vm.debruijn-projection.md:149-154`). No implementation currently persists or queries one.

- **Grouping by `:yin.debruijn/hash`:** technically incomplete and misleading. Every projected entity carries `:yin.debruijn/hash`; only the root entity carries `:yin.debruijn/root true`. The root fingerprint is therefore obtained from the root-marked record or the envelope’s fingerprint, not by grouping every record hash. Even then, identifying all named programs that share a fingerprint requires lineage/provenance data that is not implemented.

- **Execution:** partly accurate. `yin.vm.linearize` consumes named AST datoms, and `yin.vm.ast-walker` evaluates named ASTs. The semantic VM, however, executes a loaded `:yin.code/*` image; it receives named AST input only through an explicit lowering composition (`docs/design/yin.vm.semantic.md:331-352`).

- **Remote De Bruijn VM:** speculative and incomplete. A future implementation would still need a compiler/loader, primitive and free-name resolution, effect and continuation semantics, distribution, versioning, and source/provenance policy. The projection is non-bijective and cannot reconstruct the named AST or its source intent.

- **Unison claims:** the discussion gives no citations. Treat these as **UNVERIFIED** unless externally sourced:
  - Unison’s exact storage schema and whether it is “strictly” a blob store.
  - Whether names are “entirely” a detached UI dictionary.
  - Whether the nameless AST is the only executable representation.
  - Whether arbitrary structural queries require full deserialization.
  - The breadth of “instant refactoring” and dependency-conflict elimination.
  - The claim that Unison lacks indexes for dependents or type-oriented search.

## 3. Publication verdict

The appropriate classification is **B: publishable as a vision/design-intent post**, after a small structural status split.

Add a dated status block near the beginning:

- **Implemented today:** named AST datoms, D0–D6 projection, Merkle fingerprints, local DaoJing persistence/deduplication, named AST lowering and VM execution.
- **Designed but not integrated:** relational indexing/querying of projected records and diagnostic joins.
- **Speculative/future:** `linearize-de-bruijn`, a projected-form VM, remote execution, global distribution, and dependency-by-hash.
- **External claims:** Unison comparisons require citations or explicit `UNVERIFIED` labels.

Then classify:

- Sections 1–3: architectural comparison, with current yin.vm execution wording corrected.
- Section 4: designed/future capability, not present functionality.
- Section 5: split between implemented named lowering and speculative projected execution.
- Conclusion: retain local deduplication; move global distribution and dependency-by-hash to future work.

It is not publishable as a fully factual comparison without those labels.

## 4. Context-file risk

Do not leave `docs/de-bruijn.md` unlabelled while presenting it as agent context. Its current speculative claims can propagate implementation errors. Correct the factual storage/execution statements and add an explicit status header; keeping it outside the authoritative `docs/design/` set is appropriate.

Exact header lines:

```text
> STATUS: SPECULATIVE DISCUSSION SUMMARY — NOT AN ARCHITECTURE CONTRACT
>
> As of 2026-09-21, this file mixes implemented behavior, design intent, and
> speculative future work. Treat the design document and merged source code as
> authoritative.
>
> Implemented: named AST datoms, D0–D6 De Bruijn projection, Merkle fingerprints,
> local DaoJing persistence/deduplication, named lowering, and VM execution.
>
> Designed but not implemented: DaoSpace indexing/querying of projected records,
> persistent diagnostic joins, and a De Bruijn-specific linearizer or VM.
>
> External Unison claims are UNVERIFIED unless accompanied by citations.
```

The file may remain untracked or move into an explicitly labelled discussion/archive area, but it should not remain an unlabeled context authority.
