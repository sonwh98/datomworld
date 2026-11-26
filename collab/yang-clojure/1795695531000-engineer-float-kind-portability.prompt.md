Created-GMT: 2026-11-26 12:18:51 GMT
Created-Local: 2026-11-26 19:18:51 +0700
Coding-Agent: glm
Session-ID: 519cc353-4ebf-4ee9-a727-f6a51287dd1a

# Task: fix/float-kind-portability r1 — TDD: lexical preservation of Clojure float literals, per architect ruling 1795693415000

Role: Compiler Engineer (numeric semantics)

Implementers:
- Status-Event: 2026-11-26 12:58 GMT | Model: glm-5.3 (session 519cc353, darkstar) | Status: superseded | Rationale: owner-directed migration to a remote host (laptop load); in-flight state committed 09401506 before stop

## TDD IS THE REQUIRED METHOD (owner instruction, binding)

Write the FAILING tests first — RULING 5's seven acceptance gates in test form — run them RED, record the red evidence, THEN implement. Every acceptance gate lands as a test before the code that makes it pass. The red run is itself deliverable evidence (attach logs). Do not implement first and backfill tests.

## Authority (read first)

1. **The ruling (binding, normative)**: /Users/sto/workspace/datomworld/collab/yang-clojure/1795693415000-architect-float-kind-portability-ruling.gpt-6-astra.findings.md — RULING 1 (impact), RULING 2 (option b: frontend-owned lexical preservation; carrier admission; execution reboxing), RULING 3 (revision bump, migration, legacy custody), RULING 4 (honest portability statement + doc placements), RULING 5 (the complete fix-round contract: file boundaries + seven acceptance gates + re-pin protocol). Read ALL of it.
2. Your prior probes (from the ruling's Verification section): yang.clojure/read-forms returns Double for 1.0/0.0/1e0 on JVM (kind intact there); cljs.tools.reader AND cljs.reader return the same bare Number for 1/1.0/1e0 (kind lost). Start from that.
3. The precedent to generalize: yang.typescript lower.cljc:429-440 (token → (cbor/float64 v)); python/php equivalents.

## Where you work

Worktree: /Users/sto/workspace/datomworld-floatkind, branch fix/float-kind-portability @ origin/master (7f4898f4). Work ONLY there. Commit NOTHING — the orchestrator commits after review. Toolchain: node via /Users/sto/.nvm/versions/node/v26.1.0/bin on PATH; mise shims for bb/clojure; `bb gen:antlr` first (cache hit expected).

## The work, in TDD order

1. **RED — write the seven acceptance gates as portable tests** (new namespace per RULING 5's test boundaries, e.g. test/yang/clojure_float_kind_test.cljc + the corpus/semantic-oracle extensions it specifies). Gate 1 first (real identical source strings on JVM and Node through the frontend source entry + REPL/catalog route: 0.0, 1.0, -1.0, 1e0, 1.5, -0.0, integral floats at/beyond 2^53, small positive float rounding to zero; integer controls). Run RED on JVM + Node, save logs to collab/yang-clojure/<new-ts>-floatkind-evidence/.
2. **The shared factory FIRST**: a shared numeric-token classifier + carrier constructor under the yang.core shared layer (one implementation; per-language lexeme dialect tables) — per the owner's direction this is SHARED code, not a fourth private copy; ts/php/python adapters move to it (their existing correct boxing is a control — do not rewrite beyond the adapter call).
3. **The Clojure ingestion stage** (the hard piece — the ruling REQUIRES a lexical-coverage demonstration BEFORE selecting the minimal mechanism): demonstrate coverage of comments/strings/chars/quote/metadata/dispatch/discarded forms/reader conditionals + numeric tokens with the retained-kind mechanism, THEN implement. No regex-over-source, no postwalk of read Numbers, no print-round-trip, no process-wide reader monkeypatch. Scope per RULING 5's file boundaries (src/cljc/yang/clojure.cljc + a focused module under src/cljc/yang/clojure/; repl.cljc/frontends.cljc routing; the audit set).
4. **Carrier admission + execution reboxing** per RULING 2 (forms compiler admits float64 carriers incl. nested/quoted; the portable profile routes floating ops carrier-aware with results reboxed; do not overhaul legacy arithmetic).
5. **GREEN**: all seven gates green on JVM + Node (laptop single-namespace runs are G0-legal); then Dart + Jolt legs per the gate 6 cross-host matrix (targeted selectors; jupiter scratch clone for Jolt if needed, per-run log dirs). Record exact commands/counts per host.
6. **Migration per RULING 3**: new Clojure frontend revision + lowering/support profile (retaining the constructed-collection ordering contract in the identity), legacy custody tests (archive pre-fix JS integer-bearing + JVM float-bearing bytes, load/execute under the new system without recompilation), doc placements per RULING 4.
7. **Re-pin** per RULING 5's protocol — only after the gates explain the change: complete corpus key/C1/provenance references, pre-fix archives preserved, historical records untouched.

## Bounds

RULING 5's file boundaries exactly. Outside them (codec, VM equality, upstream reader): report as dependencies requiring separate architectural decision — do NOT fix incidentally. No unrelated arithmetic redesign, no float-key policy expansion, no new persistent annotation, no global reader monkeypatch. kondo 0 errors on touched files.

## Deliverable

collab/yang-clojure/<new-ts>-engineer-float-kind-portability-r1.glm-5.3.findings.md (worktree mirror + say-so): the RED evidence, the lexical-coverage demonstration, per-gate green evidence with commands/counts, the shared-factory design, the re-pin ledger. Do not end the turn with runnable work remaining. Commit NOTHING.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
