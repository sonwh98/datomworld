Completed-GMT: 2026-09-21 15:33:42 GMT  
Completed-Local: 2026-09-21 22:33:42 +07 (Indochina Time)  
Coding-Agent: codex  
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a  

PUBLISHABLE AFTER THE FIXES BELOW

## P1 — Factual or materially misleading claims about yin.vm

- **“Both systems rely heavily on De Bruijn indexing.”**  
  The projection uses De Bruijn indices, but the semantic VM does not. The named AST is lowered to `:yin.code/*` instructions and the semantic machine executes the loaded image (`docs/design/yin.vm.debruijn-projection.md:202-217`; `docs/design/yin.vm.semantic.md:331-352`).  
  **Replacement:** “Both systems use nameless or canonical representations for selected identity purposes; yin.vm keeps named execution input and applies De Bruijn projection during compilation.”

- **“This is what the semantic VM (the ast-walker) executes.”**  
  This conflates two evaluators. `yin.vm.ast-walker` evaluates named ASTs, while the semantic VM executes linearized code; the default evaluator is semantic, with the walker separately selectable (`docs/design/yin.vm.semantic.md:333-349`, `:795-801`).  
  **Replacement:** “The named AST remains the source representation: the ast-walker can evaluate it directly, while the semantic VM executes a separately linearized `:yin.code/*` image.”

- **“When a developer renames a variable … the projection … outputs the exact same fingerprint.”**  
  This is true for bound local variables only. Free occurrences preserve their symbols exactly (`docs/design/yin.vm.debruijn-projection.md:98-104`), and the test matrix explicitly requires changed free names to change fingerprints. Renaming a global or module reference therefore changes callers’ fingerprints; yin.vm has no dependency-by-hash indirection.  
  **Replacement:** “Renaming bound local variables preserves the fingerprint; renaming free or global references changes it because those names remain part of the projection.”

- **“Both the Named AST and the De Bruijn projection are stored natively as decomposed relational facts … [and] you can write native Datalog queries.”**  
  The named form is datomic data, but D5 persists the projection as a DaoJing content-addressed envelope containing projected datoms (`src/cljc/yin/vm/pipeline.cljc`; `docs/design/yin.vm.debruijn-projection.md:261-265`). No current source integrates `:yin.debruijn/*` records with `dao.space.query/q`.  
  **Replacement:** “The named AST remains suitable for relational indexing; the current projection is persisted as a DaoJing envelope containing projected datoms. Datalog querying of projected records would require a separate indexing integration.”

- **“This level of purely structural querying is impossible when your code is trapped in a serialized blob.”**  
  This overstates the current contrast: the projected envelope is presently blob/content-store material, and no projected Datalog query exists in the repository.  
  **Replacement:** “A relational projection can support direct structural queries once indexed; a serialized envelope requires an explicit decode or indexing step first.”

- **“yin.vm captures the global distribution and deduplication benefits of Unison.”**  
  D0–D6 implement local alpha-canonical projection, Merkle fingerprints, hash-consing, and DaoJing write-idempotence. They do not implement global distribution, dependency-by-hash references, DHT replication, or cross-node code sharing (`docs/orchestrator-log.md` final de Bruijn entries; `docs/design/yin.vm.debruijn-projection.md:261-272`).  
  **Replacement:** “yin.vm currently provides local alpha-equivalence identity and content-addressed deduplication; global distribution and dependency-by-hash remain future composition work.”

- **“stack traces and debuggers get real developer intent”**  
  Source provenance exists through `:yin.code/source` and `:yin.code/derived-from`, but trace emission and reverse rendering are described as optional or future query work (`docs/design/yin.vm.semantic.md:646-649`; `:444-459`).  
  **Replacement:** “The named AST and `:yin.code/source` provenance preserve the information needed for source rendering and future debugging tools; they do not by themselves provide a complete debugger or stack-trace facility.”

## P2 — Unison claims requiring qualification or external verification

- **“Unison uses SQLite strictly as a Key-Value Blob Store.”**  
  This is misleading. Unison’s codebase has indexes and derived structures for names, dependents, and type-oriented search. The repository cannot serve as evidence for Unison internals, so the precise implementation claim is **UNVERIFIED** here.  
  **Replacement:** “Unison stores content-addressed definitions in a codebase database and builds additional indexes for names and codebase queries.”

- **“To query the codebase … you must fetch blobs, deserialize them, and traverse programmatically.”**  
  **UNVERIFIED and too categorical.** Unison supports codebase-level searches and indexes; arbitrary structural queries may require decoding, but “must” and the implied absence of indexes are not supportable.  
  **Replacement:** “Some structural analyses require loading and interpreting serialized definitions, while the codebase also maintains specialized indexes for common searches.”

- **“The AST structurally forgets why a variable exists.”**  
  **Partially true but overstated; externally UNVERIFIED.** A nameless core representation omits source names, but that does not establish that the surrounding codebase loses all semantic or provenance information.  
  **Replacement:** “A nameless core representation does not itself preserve source-level binder names, so human-facing names and provenance must be maintained separately.”

- **“It relies entirely on a fragile UI trick where the text editor looks up a hash in a side-dictionary.”**  
  **UNVERIFIED and unfairly framed.** The repository provides no basis for this characterization, and “entirely,” “fragile,” and “UI trick” are editorial judgments.  
  **Replacement:** “Human-readable names are maintained as metadata and codebase indexes around the nameless identity representation.”

- **“Purely mathematical, collision-free graphs.”**  
  Cryptographic hashes are collision-resistant, not mathematically collision-free.  
  **Replacement:** “canonical, content-addressed graphs with cryptographically collision-resistant identities.”

- **“Instant refactoring” means a rename never changes the hash.**  
  **UNVERIFIED without an external Unison citation and too broad as written.** Whether a rename preserves identity depends on whether it is a local binder rename, a global name change, or a type/API change.  
  **Replacement:** “Unison’s hash-oriented representation can make some name changes—especially reference renames—independent of definition identity, subject to its dependency and type-checking rules.”

## P3 — Framing and editorial wording

- **Title: “How yin.vm Solves Unison’s Semantic Dilemma.”**  
  This presupposes a judgment and presents a comparison as a solved contest.  
  **Replacement:** “Named Source, Canonical Identity: yin.vm and Unison’s Different Trade-offs.”

- **“The Named Universal AST: This is the unassailable source of truth.”**  
  The design does make the named form canonical, stored, queryable, and renderable, but “unassailable” is rhetorical.  
  **Replacement:** “The Named Universal AST is the canonical stored, queryable, and renderable source form.”

- The `.blog` artifact is structurally consistent with the project’s EDN/Hiccup format. No format defect requires correction.

## What the post gets right

Keep these points:

- Named and projected forms intentionally coexist; the projection is derived data.
- Bound-variable alpha-equivalence is a legitimate identity use case.
- The named representation preserves source names and supports rendering/query-oriented consumers.
- The projection’s Merkle fingerprint and hash-consing provide local deduplication.
- yin.vm’s “Interpretation Creates Semantics” principle is an actual foundational axiom (`docs/design/datom.world.md:14-19`).
- The distinction between human-facing representation and canonical identity is a valuable comparison theme.

A fair comparison should add a dedicated section on references and distribution: yin.vm preserves free references by name, while the projection does not rewrite them to dependency hashes; current implementation is local content-addressed storage and deduplication, not global code distribution or cross-node dependency sharing.
