Created-GMT: 2026-11-09 19:41:50 GMT
Created-Local: 2026-11-10 02:41:50 +0700 (Asia/Ho_Chi_Minh)
Completed-GMT: 2026-11-09 19:50:00 GMT
Completed-Local: 2026-11-10 02:50:00 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: 81cb7e6c-3076-4de1-b2eb-56252f9a43e4

# Task: Architect confirm — Round B2-r4 (the three residuals, commit e9b4e31b)

Role: Architect (read-only review)

Implementers:
- Model: gemini-4-argon-high (agy) | Assigned: 2026-11-10 02:41:50 +0700 | Status: active | Rationale: codex gpt-6.1-sol hit its usage limit (resets 23:39); argon is the owner-reserved ARCHITECTURE-REVIEW lane per routing-status 2026-11-09 for the yang.php track

## Summary Verdict

**CONFIRMED — all three r3 residuals are fixed at `feat/p5b2-lifecycle @ e9b4e31b` (parent `d78028a2`).**
The `src/dev/yang/php/oracle_test.clj` collateral update is **faithful to the maintained fixture contract** (not test-fitting).
**`feat/p5b2-lifecycle @ e9b4e31b` is MERGE-READY** from the code/architecture standpoint (subject to the orchestrator's remote shiva/oracle acceptance gate if not yet recorded).

---

## Per-Residual Verdicts

### 1. Request-in-UAST — `confirmed-fixed`

- **Evidence:**
  - `test/yang/php/antlr/portable_harness.cljc:199-207` (`attach-request-stream`) appends the request record to a composition-created ring-buffer stream (`lifecycle-stream`) and attaches that stream handle to the VM via `engine/attach-resource`, returning `[request-ref vm]` (a sealed `:stream-ref`).
  - `test/yang/php/antlr/portable_harness.cljc:210-224` (`run-packet-raw`) and `test/yang/php/antlr/portable_harness.cljc:226-267` (`attach-lifecycle-streams`, `run-lifecycle-raw`) compose only `request-ref` (never the request record) into `lower-through-stage` (`test/yang/php/antlr/portable_harness.cljc:168-196`). The pre-execution `consume-one` helper is removed.
  - `src/cljc/yang/php/antlr/prelude.cljc:486-499` (`php/resolve-request`) and `src/cljc/yang/php/antlr/prelude.cljc:5591-5594` (`php/run-main`) resolve a `:stream-ref` operand at runtime inside the root protected region by polling one record from `(stream/cursor request)` (`:dao.stream/blocked` keeps `:php/null`), then passing the resolved record to `php/bind-request!`. Plain record operands on the stream-less harnesses (`e2e-harness/run-raw`, `linked-harness/run-fixture`) continue to bind directly.
  - `src/cljc/yang/php/antlr/lower.cljc:3747-3806` updates `option-keys` and `lower-program` docstrings/comments to match the sealed-reference composition split.
  - `test/yang/php/antlr/p5b2_lifecycle_test.clj:122-158` (`request-never-enters-compiled-uast-test`) verifies that the second operand of `php/run-main` in the lowered UAST is the opaque `:stream-ref`, that a full postwalk finds zero AST nodes carrying `:php.request/*` keys, and that `run-lifecycle-raw` still seeds `$_GET['v']` from the stream at runtime.

### 2. Cookie validation ordering — `confirmed-fixed`

- **Evidence:**
  - `src/cljc/yang/php/antlr/prelude.cljc:743-772` (`php/bi-setcookie`) now checks argument count first (`prelude.cljc:744-751`), throwing `ArgumentCountError` with PHP 8.3 `zend_API.c` / `head.c` messages when `n > 3` (array form) or `n > 7` (positional form).
  - Option validation and coercion (`php/cookie-options-array` at `prelude.cljc:752-753` / `php/cookie-options-loop` at `prelude.cljc:709-727`) run next, throwing `ValueError` on numeric or unknown option keys before checking `php/headers-refused?`.
  - `php/headers-refused?` is checked only at `prelude.cljc:770` after all argument-count and option validations have succeeded, returning `false` and emitting the `E_WARNING` (`errno 2`) header refusal diagnostic when output has already flushed.
  - `test/yang/php/antlr/p5b2_lifecycle_test.clj:161-181` (`setcookie-validates-before-headers-sent-test`) covers all three post-`echo` cases: excess positional arguments (`ArgumentCountError`), unknown option key (`ValueError`), and valid `setcookie` after output (`xbool(false)` + `[[2 "Cannot modify header information - headers already sent"]]`).

### 3. Oracle fatal diagnostic — `confirmed-fixed`

- **Evidence:**
  - `src/dev/yang/php/oracle.clj:149-155` (`parse-stdout`) appends `[256 fatal]` to `:diag` (`:diag (if fatal (conj @diags [256 fatal]) @diags)`) whenever a single-slot plain fatal is parsed, matching the engine's `E_USER_ERROR` diagnostic (`errno 256`) and the maintained expectations in `test/yang/php/antlr/programs_p5b2.cljc:119,126,134,146` (`:diag [[256 "boom"]] :fatal "boom" :status 255`).
  - `check-p5b2-fixture` (`src/dev/yang/php/oracle.clj:444-447`) and `print-fold` (`src/dev/yang/php/oracle.clj:468-478`) now agree with the maintained fixture shape on `:out`, `:diag`, `:uncaught`, `:fatal`, and `:status`.

---

## Verdict on `src/dev/yang/php/oracle_test.clj` Collateral — `faithful (confirmed)`

- **Assessment:** Faithful to the maintained fixture contract, **not** test-fitting.
- **Evidence:**
  1. `src/dev/yang/php/oracle_test.clj:16,19`: Adding `:fatal nil` to the expected maps in `uncaught-block-keeps-shutdown-output-test` reflects the `:fatal` key added to `parse-stdout`'s return map in commit `300dc2f3` (`oracle.clj:155`).
  2. `src/dev/yang/php/oracle_test.clj:33-34`: Replacing `(is (= {:class "Error", :message "boom"} (:uncaught r)))` with `(is (nil? (:uncaught r)))` and `(is (= "boom" (:fatal r)))` reflects the Architect-ruled single-slot guest-fatal representation implemented in commit `d78028a2` (`oracle.clj:139-141`), which explicitly retired the old `:uncaught {:class "Error" ...}` overloading for plain fatals.
  3. `src/dev/yang/php/oracle_test.clj:38-42`: The new assertion `(= [[256 "boom"]] (:diag r))` alongside `(= "boom" (:fatal r))` and `(nil? (:uncaught r))` directly pins Residual 3 and matches `test/yang/php/antlr/programs_p5b2.cljc:116-147`.

---

## Merge-Readiness

- **Code & Architecture:** **MERGE-READY (`feat/p5b2-lifecycle @ e9b4e31b`).**
  All foundational invariants, stream lifecycle boundaries, PHP 8.3 cookie validation ordering, and oracle parser contracts are satisfied. `git diff --check 8d0a4ffb..e9b4e31b` is clean.
