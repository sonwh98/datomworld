Created-GMT: 2026-11-10 22:05:51 GMT
Created-Local: 2026-11-11 05:05:51 +0700 (+0700)
Completed-GMT: 2026-11-10 22:13:50 GMT
Completed-Local: 2026-11-11 05:13:50 +0700 (+0700)
Coding-Agent: agy
Session-ID: 1fb0ddec-06b6-4256-a343-5707fbae90b3

# Task: Architect review — the P5b2 oracle promotion (commit aefac78d)

Role: Architect (read-only review, step 7)

Implementers:
- Model: gemini-4-argon-high (agy) | Assigned: 2026-11-11 05:05:51 +0700 | Status: active | Rationale: the owner-reserved architecture-review lane; codex sol was quota-walled earlier this session

## Summary Verdict

**STEP-7 SIGN-OFF: `MERGE-READY` at `feat/p5b2-oracle-promotion @ aefac78d` (parent `498cba4c`).**

All three changed files ([`prelude.cljc`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc), [`oracle.clj`](file:///Users/sto/workspace/datomworld-p5rec/src/dev/yang/php/oracle.clj), [`programs_p5b2.cljc`](file:///Users/sto/workspace/datomworld-p5rec/test/yang/php/antlr/programs_p5b2.cljc)) preserve the foundational invariants (`docs/design/datom.world.md`), respect the exception-track surface registry (`collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md`), and close the P5b2 oracle promotion gate cleanly (subject to the running shiva merge gate `dao.stream` tri-lane + focused completing green).

---

## Rulings on the Architectural Questions

### 1. Region Check Against the Exception-Track Surface Registry — `CONFIRMED DISJOINT`

- **Evidence:**
  - The exception-track ruling of record ([`1794960000000-owner-ruling-exception-uniformity-v2.md:38-68`](file:///Users/sto/workspace/datomworld-p5rec/collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md#L38-L68)) and peer relay ([`1794970001000-to-tsphp-your-js-and-php-rounds.md:13`](file:///Users/sto/workspace/datomworld-p5rec/collab/exception-track/1794970001000-to-tsphp-your-js-and-php-rounds.md#L13)) cede the PHP boundary entry/exit/reception/rethrow surfaces (at baseline `e9a0b430` lines `:194-197`, `:2264`, `:2384`, `:4085`, `:4217` — corresponding on current HEAD to `php/raise` at [`prelude.cljc:206-219`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L206-L219), `php/make-fn` at [`prelude.cljc:2679-2703`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L2679-L2703), `php/call` / `php/call-with-binding-profile` / `php/call-named` at [`prelude.cljc:2799-2817`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L2799-L2817) and [`prelude.cljc:4740-4748`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L4740-L4748), `php/throw-value` at [`prelude.cljc:4512-4516`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L4512-L4516), and `php/make-closure` at [`prelude.cljc:4644-4655`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L4644-L4655)).
  - Commit `aefac78d` touches only `php/run-main`'s post-shutdown exit-status derivation at [`prelude.cljc:5636-5638`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5636-L5638). There is zero syntactic or semantic overlap with the ceded regions.

---

### 2. Completeness of the Uncaught-Throwable `255` Exit-Status Fix — `CONFIRMED COMPLETE FOR ALL NIL-STATUS PATHS` (1 non-blocking edge note)

- **Trace of all termination paths in [`php/run-main`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5513-L5645) and [`php/handle-uncaught`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5501-L5512):**
  1. **Uncaught throwable from request binding or `body` (no user `set_exception_handler`):**
     - [`prelude.cljc:5590-5597`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5590-L5597) catches the guest throwable as `exc0` and returns `(php/pair :normal exc0)` at [`prelude.cljc:5602`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5602).
     - `ex` ([`prelude.cljc:5606`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5606)) is `:php/null`; `(php/handle-uncaught exc0)` ([`prelude.cljc:5507-5508`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5507-L5508), [`5608`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5608)) returns `exc1 = exc0` unchanged when `h = :php/null`.
     - After shutdown (`s` at [`prelude.cljc:5609-5618`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5609-L5618)), `e` at [`prelude.cljc:5635`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5635) is `exc1` (`!= :php/null`), and [`prelude.cljc:5638`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5638) (`st (if (= st :php/null) (if (= e :php/null) st 255) st)`) promotes `st` from `:php/null` to `255`, associating `:php/status 255` at [`prelude.cljc:5640`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5640).
  2. **`handle-uncaught` when the user exception handler throws (or is not callable):**
     - In [`php/handle-uncaught`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5509-L5512), `(php/try (fn [] (do (php/call-value h (php/vconj [] exc)) :php/null)) (fn [e] e) (fn [] :php/null))` clears `php.rt/exception-handler` and catches any throwable `e` raised inside `h` (or raised by `php/call-value` if `h` is invalid) via `(fn [e] e)` at [`prelude.cljc:5511`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5511), returning it as `exc1` at [`prelude.cljc:5608`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5608).
     - `e` ([`prelude.cljc:5635`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5635)) is `exc1` (`!= :php/null`), so [`prelude.cljc:5638`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5638) sets `st = 255`.
     - Conversely, when `h` handles the exception and returns normally without throwing, `php/try`'s `orelse` thunk at [`prelude.cljc:5512`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5512) returns `:php/null` (`exc1 = :php/null`), leaving `st = :php/null` (normal exit). This matches PHP 8.3 (`Zend/zend_execute_API.c`: `zend_exception_error(EG(exception), E_ERROR)` is skipped when `EG(user_exception_handler)` succeeds without leaving a new `EG(exception)`).
     - If `h` calls `exit(n)` ([`php/root-exit`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L791-L801)) or `trigger_error(..., E_USER_ERROR)` ([`php/guest-fatal`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L819-L843)), `php.rt/in-shutdown` is still `false` and `php.rt/exit-escape` still holds `eesc` from [`prelude.cljc:5589`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5589) (with `php.rt/exception-handler` already cleared at [`5509`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5509)), so `eesc` re-enters `v` with `:php/terminal :exit` or `:guest-fatal`, runs `s` (`php/shutdown!`), and exits with `n` or `255`.
  3. **Uncaught throwable escaping from `php/shutdown!` (shutdown callback or destructor):**
     - Whether raised by a `register_shutdown_function` callback ([`php/run-shutdown-fns` at `prelude.cljc:4249-4256`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L4249-L4256)) or by an object destructor (`php/run-destructor` -> `php/pending-add!` at [`prelude.cljc:4200-4206`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L4200-L4206), raised by `php/flush-pending!` at [`prelude.cljc:4309,4314`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L4309)), the `php/try` in `s` ([`prelude.cljc:5613-5615`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5613-L5615)) catches it as `exc2` and returns `(php/pair :normal exc2)` at [`prelude.cljc:5618`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5618).
     - [`prelude.cljc:5634`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5634) extracts `exc2 = (get s 1)`; [`prelude.cljc:5635`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5635) sets `e = (if (= exc1 :php/null) exc2 exc1)` (`!= :php/null`); and when `st` is `:php/null`, [`prelude.cljc:5638`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5638) sets `st = 255`.
  4. **Guest fatal (`E_USER_ERROR` via `php/guest-fatal`):**
     - [`php/guest-fatal`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L829-L843) writes `255` into `php.rt/exit-status` at [`prelude.cljc:830`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L830) and is also backed by the explicit `:guest-fatal` status guard at [`prelude.cljc:5629-5633`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5629-L5633).
  5. **Non-blocking edge observation (`exit(0)` in `body` followed by an uncaught throwable in `php/shutdown!`):**
     - If `body` calls `exit(0)` (setting `php.rt/exit-status` to `0`, so `ex = 0` at [`prelude.cljc:5606`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5606) and `st = 0` at [`prelude.cljc:5625`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5625)) and a shutdown callback or destructor subsequently throws an uncaught exception (`exc2 != :php/null` at [`prelude.cljc:5634`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5634)), `st` at [`prelude.cljc:5638`](file:///Users/sto/workspace/datomworld-p5rec/src/cljc/yang/php/antlr/prelude.cljc#L5638) is already `0` (`!= :php/null`), so `:php/status` remains `0` while `:php/uncaught` is populated. In PHP 8.3 (`main/main.c:php_request_shutdown` -> `zend_exception_error(..., E_ERROR)`), an uncaught exception during shutdown sets `EG(exit_status) = 255`, overriding an earlier `exit(0)` (whereas an `exit(0)` called *during* shutdown after a body throw overrides the body throw's `255`). This does not reach `:php/status nil` and affects no P5/P5b2 fixture; if a future fixture exercises `exit(0)` + shutdown uncaught throw, `exc2 != :php/null` (and `(:guest-fatal)` in `s`) can take precedence over `ex`.

---

### 3. Principledness of the `512`-vs-`2` Exemption in `oracle/errno-match?` — `CONFIRMED PRINCIPLED`

- **Evidence:** [`oracle.clj:65-75`](file:///Users/sto/workspace/datomworld-p5rec/src/dev/yang/php/oracle.clj#L65-L75), [`oracle.clj:43-63`](file:///Users/sto/workspace/datomworld-p5rec/src/dev/yang/php/oracle.clj#L43-L63), [`oracle.clj:146-151`](file:///Users/sto/workspace/datomworld-p5rec/src/dev/yang/php/oracle.clj#L146-L151).
- **Ruling:**
  - In PHP 8.3 (`main/main.c:php_error_cb`), standard stdout error formatting collapses engine and user error levels into the same label prefix: `E_WARNING` (`2`) and `E_USER_WARNING` (`512`) both print `Warning: ...`; `E_NOTICE` (`8`) and `E_USER_NOTICE` (`1024`) both print `Notice: ...`; `E_DEPRECATED` (`8192`) and `E_USER_DEPRECATED` (`16384`) both print `Deprecated: ...` (`E_USER_ERROR` `256` prints `Fatal error: ...` and is already parsed into `[256 fatal]` at [`oracle.clj:156`](file:///Users/sto/workspace/datomworld-p5rec/src/dev/yang/php/oracle.clj#L156)).
  - Because the information loss happens in PHP's CLI text formatter (`parse-stdout` at [`oracle.clj:128-151`](file:///Users/sto/workspace/datomworld-p5rec/src/dev/yang/php/oracle.clj#L128-L151)), [`errno-match?`](file:///Users/sto/workspace/datomworld-p5rec/src/dev/yang/php/oracle.clj#L65-L75) rightly permits the projection `512 -> 2` (and `1024 -> 8`) **directionally** (`e` is the fixture's semantic constant, `actual-errno` is the class-derived constant) and **only** when `(class-derived? a)` is true (`:yang.php.oracle/class-derived` metadata attached exclusively by `parse-stdout`).
  - **Should it be generalized, and would generalizing weaken the check?**
    - An **unconstrained** generalization (e.g., ignoring `errno` whenever `class-derived?` is true, or making the match symmetric) **would** weaken the check by allowing cross-severity mismatches (`Warning` vs `Notice` vs `Deprecated`).
    - The closed projection of the non-fatal `E_USER_*` levels to their `errno-of` class buckets (`{1024 8, 512 2}`, plus `16384 -> 8192` if an `E_USER_DEPRECATED` default-sink fixture is ever added) does **not** weaken the check: it relaxes only the single `E_USER_*` vs `E_*` bit within the same severity class that PHP's stdout formatter erases. Keeping the explicit pair clauses in [`oracle.clj:74-75`](file:///Users/sto/workspace/datomworld-p5rec/src/dev/yang/php/oracle.clj#L74-L75) for the two active user diagnostic classes (`1024 -> 8` and `512 -> 2`) is exact and minimal.

---

### 4. Keeping Engine Truth `512` in the Fixture vs Adopting `2` — `CONFIRMED RIGHT CALL`

- **Evidence:** [`programs_p5b2.cljc:159-170`](file:///Users/sto/workspace/datomworld-p5rec/test/yang/php/antlr/programs_p5b2.cljc#L159-L170), [`p5b2_lifecycle_test.clj:21-33`](file:///Users/sto/workspace/datomworld-p5rec/test/yang/php/antlr/p5b2_lifecycle_test.clj#L21-L33), [`oracle.clj:447`](file:///Users/sto/workspace/datomworld-p5rec/src/dev/yang/php/oracle.clj#L447).
- **Ruling:**
  - Keeping `:diag [[512 "w1"] [512 "w3"]]` in `:handler-mask-saved-and-restored` ([`programs_p5b2.cljc:169`](file:///Users/sto/workspace/datomworld-p5rec/test/yang/php/antlr/programs_p5b2.cljc#L169)) is the right call and directly upholds **Axiom 2 ("Interpretation Creates Semantics — one truth, many perspectives")** and **Unitarity ("Do not lose information")** (`docs/design/datom.world.md:17-20, 32`).
  - Within `:handler-mask-saved-and-restored` itself, `trigger_error('w2', E_USER_WARNING)` delivers `$n = 512` to handler `A`, printing `"B1024A512"`. The true semantic errno of `trigger_error('w1', E_USER_WARNING)` and `trigger_error('w3', E_USER_WARNING)` is `512`, which the engine emits onto `diag-stream` (`{:php.diag/warning true, :errno 512, :message "w1"}`) and which [`p5b2-lifecycle-fixtures-test`](file:///Users/sto/workspace/datomworld-p5rec/test/yang/php/antlr/p5b2_lifecycle_test.clj#L26) asserts directly via `(= (vec diag) (diagnostics r))`.
  - Replacing `512` with `2` in the fixture would corrupt the canonical fixture table to match a lossy regex projection of CLI stdout and would either break the engine's structured diagnostic test or force the test harness to discard the user-vs-engine bit.

---

## Minor Cosmetic Observation (Non-Blocking)

- `low` | [`test/yang/php/antlr/programs_p5b2.cljc:3-4`](file:///Users/sto/workspace/datomworld-p5rec/test/yang/php/antlr/programs_p5b2.cljc#L3-L4) | Namespace docstring left a trailing `"Every"` at the end of line 3 when replacing `"Every fixture stays :oracle :pending..."` with `"The oracle column records the shiva php:8.3 docker pass (2026-11-11)."` | Cosmetic only; does not block merge.
