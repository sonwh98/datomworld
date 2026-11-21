Created-GMT: 2026-11-18 16:14:01 GMT
Created-Local: 2026-11-18 23:14:01 +0700
Coding-Agent: glm
Session-ID: 3b22ab98-3808-492f-99b7-216dd4cf8345

# Task: fix/constructed-collection-order r1 — implement the ruling's canonical map/set operand ordering

Role: Compiler Engineer (compiler provenance)

## Authority (read first, in this order)

1. **The RULING (binding, normative)**: `/Users/sto/workspace/datomworld/collab/yang-clojure/1795017757000-architect-cc4-dart-map-order-ruling.gpt-6-astra.findings.md` — RULING 1 (compiled-content-address pair/element ordering at `compile-constructed-collection`), RULING 2 (semantics + scope + what must NOT be sorted), RULING 3 (bounds table + required tests 1-7 + regression gate + re-pinning protocol), RULING 4 (manifest revision `c1-2`, profile `:yang.clojure/constructed-collections-v1`, legacy load).
2. The defect evidence: worktree `collab/yang-clojure/1795017484194-engineer-cc4-r2-findings.md`, `test/resources/yang/clojure/cc4-replay-dart-divergences.edn`, `1795017275897-cc4-r2-evidence/`.
3. The touched code: `src/cljc/yang/clojure.cljc` (`compile-constructed-collection` ~:302-323, `arity-error-form` ~:1087 — a covered producer, not a second authority), `src/cljc/yin/repl/frontends.cljc` (:43-54, manifest only), `src/cljc/dao/jing.cljc` (:174-207, canonical-compare — READ ONLY).

## Where you work

Worktree: `/Users/sto/workspace/datomworld-fixorder`, branch `fix/constructed-collection-order` @ `706d44f9` (contains cc4's strengthened battery + cohort). Work ONLY there. TDD: the ruling's required tests are RED first. Commit NOTHING — the orchestrator commits after review. ANTLR: `export PATH="$HOME/.local/share/mise/shims:$PATH"`, `bb gen:antlr` (cache hit expected).

## The work (ruling RULING 3 governs; summarize, don't paraphrase)

1. Implement the RULING 1 emitter normalization (map pairs sorted by ascending `jing/canonical-compare` of `[key-root value-root]` where roots are the existing `vm/ast->semantic-bytecode` projection; set elements by their element root; vector branch untouched; seed first; no dedup; compute each child once; private helper allowed in the same file).
2. Manifest: advance `clojure-manifest` revision `c1-1` → `c1-2`, profile `:yang.clojure/constructed-collections-v1` (RULING 4; frontends.cljc only, lines ~43-54).
3. Write the required test families 1-7 from RULING 3 in `test/yang/clojure_collection_order_test.cljc` (new, portable) plus focused additions to `clojure_callsite_test.cljc`; keep the eight-member cohort; RED before the fix.
4. **Re-pin**: after four-host agreement, record ONE post-fix reference bundle (the ruling's re-pinning protocol: re-record the complete cohort incl. unchanged members; archive the old active fixtures under an explicit pre-fix name; publish a before/after ledger keyed by compiler revision under collab/).
5. Regression gate: run the ruling's four command forms for BOTH `yang.clojure-callsite-test` and the new namespace, plus affected compiler/manifest tests and kondo. Jolt note: the J2 manifest refusal for the FOUR unrelated namespaces (php-parser, code-entry, machine-data, provenance tests) is a J2-owner dependency — record the refusal honestly; do NOT add exclusions or bypasses. Add only your changed/new namespaces to the J2 manifest (permitted).
6. Deliverable: `collab/yang-clojure/<new-timestamp>-engineer-fix-constructed-collection-order-r1.glm-5.3.findings.md` (worktree mirror + say-so), including exact commands, counts, toolchain pins, the before/after digest ledger, and any dependency discovered OUTSIDE the bounds table (report it, do not fix it).

## Bounds

Edit ONLY the ruling's RULING 3 bounds table. Forbidden: Jing/CBOR, VM grammar/evaluators, macro.cljc, semantic lowering, encoder/loader, core prelude, dht.cljc, slice9_site_tables.edn — report dependencies, never fix incidentally. Do not repair by changing hashing, C1 validation, origins, or the oracle. The r2 divergence evidence stays immutable.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
