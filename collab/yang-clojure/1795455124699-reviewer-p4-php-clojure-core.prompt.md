Created-GMT: 2026-11-22 23:45:24 GMT
Created-Local: 2026-11-23 06:45:24 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: b7c4c972-ba72-4f8a-82bb-f4e4c04f50b4

# Task: Step-7 review — yang.preludes P4′-b (php descriptor + frontend contract + clj.php adapter)

Role: Reviewer (independent, read-only)

Implementers:
- Model: glm-5.3 | Assigned: 2026-11-23 06:45 +07 | Status: active | Rationale: independent family from the codex/gpt-6.2-sol implementer

## What to review

The UNCOMMITTED working-tree diff in `/Users/sto/workspace/dw-p4php` (branch `feat/p4-php-clojure-core`, base master `0c36043d`). Review the real diff (`git diff`; new files include the php profiles/contract/clj.php adapter namespaces and `test/yang/php/antlr/descriptor_profile_test.cljc` — read new files whole). Do NOT modify anything.

- Governing design: `/Users/sto/workspace/dw-p4php/docs/design/yang.preludes.md` r8 — §6.3/§6.4 (esp. item 6: the php entry-lowering contract — FROZEN native `php/run-main` 4-parameter lambda legitimately called 2-or-4 operands with the request stream in slot two, alongside the Clojure 2-arg shape; "Both PHP modes are gated, including observable stream output"), §9 row "P4′".
- Engineer brief: `/Users/sto/workspace/datomworld/collab/yang-clojure/1795440604494-engineer-p4-php-clojure-core.prompt.md`.
- Template: the landed P3′ python implementation (`src/cljc/yang/python/antlr/{contract,profiles,clj_runtime}.cljc`).

## Already verified by the orchestrator (do not re-run suites)

descriptor 6/320/0; lower-portable+prelude 41/1627/0; deps clean. Engineer-captured: profile suites 55/2537/0 (JVM), Node/Dart/Jolt lanes, legacy parity 1,325 fixtures zero differences (518 permutations + 271 PHPT), the 3 python pin errors (pre-existing red #6, reproduced at clean base). OPEN items the implementer declared: the wide JVM e2e run had no final counts; the optional full-permutation oracle file is absent from the checkout — weigh whether either blocks approval.

## Review focus (prioritized)

1. **The dual-shape entry contract:** the recorded permitted call shapes for the FROZEN `php/run-main` (2-or-4 operands, request stream slot two) vs the clj.php 2-arg shape — does `yang.core.semantics` validation accept both via the contract's recorded set and REJECT a 3-operand call? Is a fixture exercising the rejection?
2. **Legacy `php-v1` preservation:** descriptor pins today's `'php/*` hooks 1:1; the 1,325-fixture parity is the evidence — scrutinize the `yang.php.antlr.lower` hunks for anything beyond descriptor reads.
3. **clj.php adapter:** zero provenance doors inside it; `:fn/make` → `clj/make-callable`, `:fn/call` → `clj/invoke-callable`; run-main installs the cold-error policy handler; **observable stream output gated under the new profile exactly as native mode gates it**.
4. **DEFAULT flip:** default selector → clojure-core-v1; `php-v1` pinnable; in-flight snapshots retained; bare compiler calls retain the native pin.
5. **Descriptor honesty:** capabilities declared per the actual php lowering surface (no invented capabilities); `admit-key?` enforcement; reserved-door ban intact (no `dsg__*`/`*.impl/*` in the descriptor).
6. **Fences + portability:** no `yang/typescript/` changes; php prelude modules frozen (only the lowerer reads descriptors); no JVM-only reflection; canonical ordering; zero raw host doubles.

## Deliverable

Write findings to `/Users/sto/workspace/dw-p4php/collab/yang-clojure/1795455124699-reviewer-p4-php-clojure-core.glm.findings.md` with the artifact header. Verdict per finding CRITICAL/HIGH/MEDIUM/LOW/NOTE with file:line evidence, then ONE overall verdict: `VERDICT: APPROVED` / `APPROVED-WITH-FIXES` / `CHANGES-REQUIRED`. End your final message with the same verdict.
