Created-GMT: 2026-11-27 02:19:17 GMT
Created-Local: 2026-11-27 09:19:17 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: ea2e3e4d-6aa4-424e-aac0-f7b0cccad9de

# Task: yang.preludes P4′-b fix r2 — reconcile the php branch with the post-green master (ts slice landed)

Role: Engineer

Implementers:
- Status-Event: 2026-11-23 21:30 +07 | Model: gemini-4-argon-high | Status: timed-out | Rationale: agy argon pool exhausted before the php slice started; conversation f44b412c errored with zero output
- Model: gpt-6.2-sol | Assigned: 2026-11-27 09:19 +07 | Status: active | Rationale: semantic reconciliation of your own php slice against the landed ts slice; resume-by-state

## Context (resume-by-state)

Your php implementation sits COMMITTED on `feat/p4-php-clojure-core` (tip 464ee737) in /Users/sto/workspace/dw-p4php (review-closed: glm-5.3 APPROVED-WITH-FIXES -> r2 CONFIRMED-CLOSED). Master advanced since your base 0c36043d — the qa engineer drove master GREEN (declared bab7640f) landing countersigned fixes, and the P4′-a ts slice merged (aa033daa). The ts slice touched the SAME catalog file you touch: src/cljc/yin/repl/frontends.cljc.

A previous reconciliation attempt CONFLICTED in one hunk of that file and was aborted (tree restored to your commit — clean). The conflict: in `standard`, your side installs php as dual revisions (`install-php php.contract/legacy-id` + `install-php php.contract/clojure-id` + preferred) while master's side installs python via `install-python ... (assoc :yang.frontend/preferred [:yang/python "clojure-core-v1"])` and adds the ts frontend (`ts.frontend/install ts.cst/grammar-id ts.cst/parse-source`). CRITICAL SUBTLETY: your side sets php preferred via `(assoc-in [:yang.frontend/preferred :yang/php] "clojure-core-v1")` (map form) while landed master uses `(assoc :yang.frontend/preferred [:yang/python "clojure-core-v1"])` (vector-pair form) — reconcile to ONE canonical shape (check `yang.frontend/select` and the landed ts/python tests for which shape is canonical; the ts descriptor tests use the vector-pair form).

## Task

1. In dw-p4php: `git merge origin/master` (aa033daa or later — fetch first) and resolve. The union must have ALL THREE language frontends dual-revision + default-flipped: python (master's form), ts (master's landed install), php (your install-php dual revisions + clojure-core-v1 preferred), with ONE canonical preferred shape throughout — adapt your php preferred to master's canonical form if they differ. The ts frontend install and the python install-python block come from master verbatim; the php dual-revision install comes from your branch.
2. If `install-php`'s signature differs from what the union needs, adapt the call sites, not the semantic.
3. Verify BOTH features plus ts on the union (all green):
   - `clj -M:test -n yang.php.antlr.descriptor-profile-test` (yours)
   - `clj -M:test -n yang.typescript.antlr.descriptor-profile-test -n yang.frontend-test` (ts, landed)
   - `clj -M:test -n yin.repl.python-antlr-test -n yin.repl.main-test` (catalog-level: all languages installed, defaults flipped, snapshots kept)
   - `clj -M:test -n yang.php.antlr.lower-portable-test -n yang.php.antlr.prelude-test -n yang.php.antlr.request-context-test`
   - legacy parity quick script zero differences; `clj ./bin/analyze-deps.clj` clean
4. Commit the merge on the branch (the one authorized commit; body noting the union of three dual-revision frontends).

## Deliverable

Final report: the resolution per hunk (what each side contributed, the canonical preferred shape chosen and why), verification matrix with counts.
