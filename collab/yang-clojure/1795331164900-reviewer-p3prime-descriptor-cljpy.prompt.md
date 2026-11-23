Created-GMT: 2026-11-22 06:26:04 GMT
Created-Local: 2026-11-22 13:26:04 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: not-applicable (agy assigns its own conversation id)

# Task: Step-7 review — yang.preludes P3′ (descriptor parameterization + clj.py + DEFAULT flip)

Role: Reviewer (independent, read-only)

Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-22 13:26 +07 | Status: active | Rationale: independent family from the codex/gpt-6.2-sol implementer; fresh conversation (P1′/P2′ lineage conv e4fbf30d is now very long — start fresh, the session log and design carry the context)

## What to review

The UNCOMMITTED working-tree diff in `/Users/sto/workspace/dw-p3prime` (branch `feat/p3prime-descriptor-cljpy`, base master `b1eb3e40` with landed P1′+P2′). Review the real diff (`git diff`; new files include `src/cljc/yang/clojure/` clj.py module, `src/cljc/yang/python/antlr/contract*`, `src/cljc/yang/core/semantics.cljc` extensions, `test/yang/python/antlr/descriptor_profile_test.cljc` — read new files whole). Do NOT modify anything.

- Governing design: `/Users/sto/workspace/dw-p3prime/docs/design/yang.preludes.md` r8 — §6.1–6.5 (descriptor schema verbatim, validation/hygiene/authority, frontend SPI/default selector), §7.1–7.6 (clj.py contract, entry-lowering contracts), §9 row "P3′".
- Engineer brief: `/Users/sto/workspace/datomworld/collab/yang-clojure/1795322379478-engineer-p3prime-descriptor-cljpy-default-flip.prompt.md`.
- Context: the engineer's session was cut by a usage limit AFTER finishing implementation and most verification; the missing final report is not evidence either way — your review is the check.

## Already verified by the orchestrator (do not re-run suites)

My own runs: prelude-parity 35/221/0; the full legacy-corpus lane (oracle-parity+int-ops+int-conv+int-contract+int-literal+lower-portable+float-text+prelude-parity) 85/1428/0 (engineer-captured log, invocation verified); clojure-test+callsite+effect 160/1677/0; wedge 1/7/0; LaTTe 37/2608/0; descriptor-profile+catalog+query 43/540/0; deps analyzer clean; js/php diffs empty. 4-host legs captured by the engineer: Node legacy oracles 9/27, Jolt legacy, Dart gates (verify the code claims, not the suites).

## Review focus (prioritized)

1. **§6.4 validation authority (the heart of this slice):** `validate-descriptor` computes the selected ADDRESS-VERIFIED closure and inspects every hook, executable literal-strategy field, and ambient projection against the manifest UAST — namespace-qualification (`:unqualified-hook`), export membership, `:lambda` shape, signature-vs-call-shape against the frontend contract's recorded entry contract, cell-accessor projection checks, the reserved-door ban, and above all the **address-bound role key `(module address, export symbol, exact role)`** — a same-arity wrong-role target must fail closed; the §6.4 gate (substituting subtraction for addition in the same module fails) must be encoded in a test that actually exercises the failure path, not just the happy path.
2. **Legacy `py-v1` behavior preservation:** the descriptor pins today's `'py/*` hooks 1:1; the lowerer reads from the descriptor but the native mechanism is unchanged; `py/run-main` 1-arg entry FROZEN; oracle suites untouched and passing. Scrutinize the lower.cljc hunks for anything beyond reading descriptor data (e.g., changed emission order, changed diagnostics, moved refusals).
3. **clj.py per §7.3:** `:fn/make` → `clj/make-callable` (5-arg), `:fn/call` → `clj/invoke-callable`, ZERO provenance doors inside clj.py, namespace-qualified hooks, `run-main` 2-arg `[body-fn ambient-resolver]` installing the cold-error policy handler; `:literals.int` overflow-op; `:unbound` = `:clj/unbound`.
4. **The DEFAULT flip (§6.5):** default-selector mechanism on the composition's immutable config; both revisions installed → default selects clojure-core-v1; explicit `py-v1` pin preserves native behavior; in-flight request retains its snapshot. Check `yin/repl/frontends.cljc` + `yin/repl.cljc` + `yang/frontend.cljc` hunks against §6.5 exactly (pins reconciled by module symbol BEFORE lowering; derivation recorded in provenance; resolver cell task-owned).
5. **§9 P3′ gates encoded in fixtures:** the cross-syntax `clj → clj.py → clj` unwind (exactly-once cleanup, context restoration) — verify the fixture actually crosses syntax boundaries and asserts exactly-once; capability refusals (typed except, genexpr) fail closed BEFORE emission; the four default-profile regressions verbatim (unqualified `:k` admits; cross-carrier integer identity on insert AND lookup; `:clj/unbound` distinguishability; Jing encoded-byte canonical order with `0` before `nil`).
6. **Scope fences + portability:** py/js/php prelude modules and mechanisms untouched (only the lowerer reads descriptors); no JVM-only reflection; canonical ordering (CBOR byte-identity); zero raw host doubles; P1′'s provenance-scope door enforcement not weakened by the engine/prelude hunks in this diff.

## Deliverable

Write findings to `/Users/sto/workspace/dw-p3prime/collab/yang-clojure/1795331164900-reviewer-p3prime-descriptor-cljpy.gemini-4-argon-high.findings.md` with the artifact header. Verdict per finding CRITICAL/HIGH/MEDIUM/LOW/NOTE with file:line evidence, then ONE overall verdict: `VERDICT: APPROVED` / `APPROVED-WITH-FIXES` / `CHANGES-REQUIRED`. End your agent message with the same verdict.
