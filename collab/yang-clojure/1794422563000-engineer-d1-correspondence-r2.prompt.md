Created-GMT: 2026-11-13 15:2x GMT
Created-Local: 2026-11-13 22:2x +0700
Coding-Agent: codex
Session-ID: (resume of thread 01a1d5bd-11ef-7513-9672-97c79d26f3bc — the D1 dispatch thread; worktree files are truth)

# Task: D1 continuation r2 — from the partial inventory to the durable record and verifier

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-13 22:2x +0700 | Status: active | Rationale: continuity — you authored the partial inventory and the structural checker in this thread

## State resume (by state)

Your partial findings: `collab/yang-clojure/1794670000001-engineer-d1-correspondence-partial.findings.md` (in the worktree `d1-correspondence`; also promoted to the canonical collab). On disk: `src/cljc/yang/correspondence.cljc` (structural checker, partial) + `test/yang/correspondence_test.cljc` (1 test, 9 assertions, green). The original brief: `/Users/sto/workspace/datomworld/collab/yang-clojure/1794422563000-engineer-d1-durable-correspondence.prompt.md` — re-read it; your own inventory refresh (in the partial findings) is the authoritative correction of its stale references, and your decisions stand: row-lane-only restriction retained, no bridge claimed, the checked-in v1 table is historical evidence.

## What remains (the bulk — work through it in order)

1. **The D1 record construction** (`:yang.correspondence/schema 1`, design §4.1): build the actual record for the pinned corpus from the LIVE compilation path (admission scope + ordinal retained per the rebind; `:admission-origin` coordinates from the current run, never the checked-in table's historical identities). Content-address via dao.jing; lossless round-trip + retrieval test; no self-address field; closed key set; empty vectors allowed only with the claimed coverage saying so.
2. **The recipe record** (`:yang.correspondence.recipe/schema 1`): closed, versioned; names the interpreter function, the exact reconstruction profile (frontend revision, grammar/options, expansion environment, recorded origins/attempts incl. the Phase 3r rebind mechanics, canonicalization, site-table derivation, lowering rules), input roles, and the oracle's exact schema identity.
3. **The companion file** `test/yang/latte_oracle/slice9_correspondence.edn`: the D1 record beside the v1 tables; a test pins that the v1 file's bytes and SHA (`e435ac4072ba0a3c278fbe91918d22184b43f0a8d6c1e397c09f68dd629beb75` per your inventory) are UNTOUCHED.
4. **The independent reconstruction verifier** (design §4.2's seven steps): fetch and schema-check; verify code images under their own contracts; reconstruct under the exact recipe; RE-DERIVE the oracle site declarations from the compiled AST argument expressions (never the producer's serialized classifications); reconstruct every claimed binding with exact coverage + multiplicities (the cc3 findings' coverage-separation discipline); execute the scoped attribution query over both C lanes; publish a separate verification result naming the verifier revision. Reject unknown evidence schema, failed reconstruction, missing retained admission — never `:trusted` as substitute. The verifier must not call `latte_oracle_tables.clj`'s derivations as its only check.
5. **The D1 bounded-run declaration** (cc3 v2-declaration format, `1794390804000-...declaration-v2.md`): finite ceilings for trace capacity/bytes, workload, per-phase time, guest fuel, memory — declared BEFORE the tagged verification run, from your own pilot measurements. No tagged run before the declaration exists.
6. **Inventory completion**: your partial inventory names what's left (artifact families, execution/derivation contracts, expansion ownership, discovery/retention owners, registry status) — complete it as the brief's work item 0 requires.

## Bounds/invariants/deliverable

Same as the original brief: the bounded file list (plus your inventory's corrections — file references read against current master, `yin.shell.query` etc.); forbidden set unchanged; origins never minted (the checked-in table's identities are historical); the verifier rejects, never substitutes. Verification: red-first per item; the structural checker's tests stay green; `clojure -M:test -n yang.correspondence-test -n yang.clojure-callsite-test` green; kondo 0 errors; the tagged verifier run ONLY after item 5's declaration. Deliverable: findings (worktree mirror + say-so), updating your partial findings by REFERENCE (new findings file `1794422563000-engineer-d1-correspondence-r2.gpt-6.1-terra.findings.md`); the partial file stays as the round-1 record. Commit NOTHING.

If the remaining scope exceeds this turn again: deliver the next coherent partial (record + recipe + companion file complete and tested is a natural checkpoint) with the same honesty standard, and say what remains.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
