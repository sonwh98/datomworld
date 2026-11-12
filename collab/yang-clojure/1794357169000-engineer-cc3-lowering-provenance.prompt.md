Created-GMT: 2026-11-11 06:12:49 GMT
Created-Local: 2026-11-11 13:12:49 +0700
Coding-Agent: muse
Session-ID: 32f932e8-e945-4e01-8a58-f044508a17e9

# Task: cc3 — lowering provenance & oracle C1 occurrence-key bridge (adoption round 3)

Role: VM Engineer (compiler provenance)

Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-11 13:12 +0700 | Status: active | Rationale: four clean rounds on the adjacent C1 surfaces (safepoint/encoder/publish/repl) in cc2; deepest existing knowledge of the occurrence machinery; flat lane

## Where you work

Worktree `/Users/sto/workspace/datomworld-cc3`, branch `cc3-lowering-provenance`, cut from origin/master `ced9f2c2` (cc2 landed at `ca10afa4`). TDD throughout: red first for every acceptance item. Commit NOTHING — the orchestrator commits after review.

## The goal (design authority)

cc1 landed static C1 occurrence keys K = [origin root-address path]. cc3 makes them survive LOWERING: every call instruction in semantic, stack, and register images must resolve back to the exact K of the application that emitted it, durably, and the LaTTe oracle's harness-sequential integer site IDs must be bound to real C1 keys. Authorities, in order:
- `collab/yang-clojure/1794670000000-architect-callsite-generalization-design.gpt-6-astra.findings.md` §2.4–2.5 (lowering/runtime), §4.2 (verification), §6.3 (bounds).
- `collab/yang-clojure/1794344088000-orchestrator-cc3-d1-mob-reconciliation.md` — the SIX BINDING CONDITIONS (3/3 architect mob): read it before anything.
- `collab/yang-clojure/1794400000000-orchestrator-handoff-yang-clojure-seat-final.md` §2 (the macro.cljc carrier invariants — you don't edit that file, but your emissions must respect the choke-point discipline).
- cc2's landed code (`git show ca10afa4`) — the C1 tables, correspondence, and claim-validation vocabulary you build on.

## The seven work items (each = acceptance criterion; the mob's conditions are quoted in [])

1. **Semantic row provenance** [C: origins resolved per-occurrence]: `yin/vm/semantic/linearize.cljc` `project-rows` already returns `:provenance [[pc [root path]]]` (~:188-214) — extend it so each call instruction's provenance carries the FULL `[origin root path]`, with origin resolved from the validated C1 side table for THAT occurrence (a tree with mixed `[:source ...]` and `[:expansion ...]` applications must resolve each to its own origin — never one origin stamped across the set). Resolve from the cc2-validated rows; an occurrence with no validated row yields NO provenance entry (absence, never invention). The caller supplies the segment coordinate (unchanged contract).
2. **Native image provenance** [C: derive.cljc is lowering, in-scope; evaluator runtime stays ceded]: `yin/vm/semantic/derive.cljc` — `stack-image` (~:103-119, emits a NEW instruction sequence) and `register-image` (~:168-184, preserves layout) must each reconstruct call-provenance `[segment-address pc origin root-address path]` for their call PCs, mapping back through their own PC derivations to the semantic Ks. Stack's side-table currently records only `:closure`/`:var` diagnostics — add `:call` provenance without disturbing the existing diagnostic shapes.
3. **Durable persistence** [C: provenance must become durable]: `yin/vm/linker/publish.cljc` `publish-closure!` (~:312) and `yin/repl.cljc` `packet->vector` (~:156-158) select `(:vector ...)` and discard provenance. Persist the provenance relation as a SIDE ARTIFACT outside the schema-1 manifest hash (the cc1 precedent at publish.cljc:352-367 — `:yin.module/call-sites` excluded, `:yang.call-site/manifest` linkage), retrievable alongside the images, only when the compiled packet claims C1.
4. **Datom lane decision** [C: bridge or explicit restriction]: `project-datoms` (~:283-302) returns `[pc entity]` with no K bridge. EITHER implement the entity→occurrence bridge (design §2.4: explicit entity-to-occurrence mapping with owning batch/root/path, refusing ambiguity) OR restrict cc3's claims to the row lane and RECORD the restriction prominently in your findings as D1's stated dependency. Do not half-do it: an undocumented silent gap is a failed round.
5. **Oracle binding** [C: retained-history binding + honest origins; coverage separation]: in `test/yang/latte_oracle_test.clj`, bind each oracle integer site to its post-expansion C1 key: compile each fixture through the C1 pipeline, extract occurrence keys, verify the 1:1 bijection between stamped guest call sites and post-expansion C1 guest-call occurrences AFTER ALL transformations (note `lower-unit`'s `publish-to-globals` rewrite generates new applications — they are C1 occurrences but NOT oracle sites; the bijection is oracle-site → its selected compiled occurrence, and the FULL C1 set is verified independently per the coverage-separation condition). Verify both C lanes (walker, semantic) resolve each executed call to the identical K. ORACLE CHRONOLOGY (astra's correction): `kernel-compile` compiles FIRST and stamps by insertion afterward — the binding must account for that transformation; a fixture id may supply a recorded admission coordinate per `yin/vm.cljc:938-958`'s origin validator shapes only; `[:oracle/fixture <id>]` alone is NOT a valid persisted origin.
6. **Coverage separation** [C: oracle set ≠ complete C1 set]: the verification must establish the oracle-site↔occurrence bijection AND independently verify the complete C1 occurrence set and all claimed instruction mappings, including uncredited helpers, duplicate-content occurrences, and transformation multiplicities. Rule-R applications (`yin/def`) carry C1 markers but lower to `:define` — no fictitious call events.
7. **Numeric preflight bounds BEFORE any tagged run** [C: design §6.3 — finite ceilings declared before tagged evidence]: before running the oracle C lanes with binding active, your findings must record the ACTUAL finite numbers: max rows/occurrences/applications/path-depth/path-bytes for the pinned corpus (derive from the compiled fixtures), the trace capacity and byte bound, and the instruction ceilings (the oracle's inspected baseline is 4,000,000 guest instructions and 1,000,000 trace records per C lane — RESTATE them and the occupancy argument for the new binding work; never silently raise fuel). A tagged run before the bounds record exists is a failed round.

## Bounded file ownership

MAY edit: `src/cljc/yin/vm/semantic/linearize.cljc`, `src/cljc/yin/vm/semantic/derive.cljc`, `src/cljc/yin/vm/linker/publish.cljc`, `src/cljc/yin/repl.cljc`, `src/cljc/yin/vm.cljc` (validators/enumerations ONLY — no semantic changes), `test/yang/clojure_callsite_test.cljc`, `test/yang/latte_oracle_test.clj`, `src/dev/latte_oracle_tables.clj`, and existing test files for the touched namespaces.
MUST NOT edit (STOP and record if a fix seems to need them): `src/cljc/yin/vm/macro.cljc`, `src/cljc/yin/vm/ast_walker.cljc`, `src/cljc/yin/vm/semantic.cljc` (the RUNTIME evaluator — the continuation-semantics seat's surface), `src/cljc/yang/clojure.cljc` (ceded region + one-writer), `src/cljc/yang/core/prelude.cljc`, `src/cljc/yin/vm/linker/dht.cljc` (cc3 carries its custody item — see below), `src/cljc/yin/repl/index.cljc`.
**The dht custody item from cc2**: `dht/publish-name!` (~:70) discards the publication result's `:call-sites` handle — it is on YOUR record to close or explicitly re-scope, but dht.cljc itself is forbidden: if the fix requires editing dht.cljc, implement the retained handle exposure on the publish side and record the one-line forwarding need as an owner-visible item.

## Invariants (unchanged, from the owner's ruling)

Origins never minted — read from retained admission/expansion/publication records only. Zero canonical change: static provenance must not alter canonical rows, root addresses, instruction vectors, or hashes for identical input (assert with the canonical-invariance pattern). Legacy unannotated packets remain fully loadable. Metadata never reaches CBOR/hashing.

## Verification (foreground, explicit timeouts, nothing backgrounded; suite-scale goes to shiva at landing — do NOT run it)

1. `clojure -M:test -n yang.clojure-callsite-test` — green with your new tests.
2. The semantic/derive/publish test namespaces you touched — find and run them; green.
3. The oracle: run ONLY the deftests your binding touches (the full oracle suite is a shiva-scale run): `clojure -M:test -n yang.latte-oracle-test -v <ns>/<var>` per test, or the narrowest selection that proves the binding + bijection + refusals. Report per-test outcomes.
4. `clj -M:kondo --lint` on every changed file — 0 errors (the encoder `load` warning is pre-existing/exempt).
5. The kit contract test is N/A on this laptop (three measured non-completions, logged) — do not run it; shiva covers it at landing.

## Deliverable

`/Users/sto/workspace/datomworld/collab/yang-clojure/1794357169000-engineer-cc3-lowering-provenance.muse-wonder-1.0.findings.md` (sandbox-denied? write the worktree mirror `collab/yang-clojure/` and say so verbatim). Header per protocol. Contents: per-item disposition with file:line; the PREFLIGHT BOUNDS record (item 7) as its own section; red-first evidence; verification table with counts; the datom-lane decision; unverified items. Commit nothing.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
