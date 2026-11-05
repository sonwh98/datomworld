Created-Local: 2026-11-05 0x:xx +0700
Coding-Agent: codex (gpt-6.1-sol, architect step-7 retro review)
Session: read-only static review of the five session landings

P7-a, P5, and P9 need forward fixes. The cross-language composition is acceptable as partial evidence: **its “no call rewriting” claim is true**. The ledger needs reconciliation.

This was static reading only; I ran no suites and changed no files. Examples below are predictions from the committed code, checked against the design and PHP documentation/source.

**1. `6e587996` — P7-a budget: DEFECT**

- **P1 — Exhaustion can execute guest code and lose the fatal.** [safepoint.cljc:20](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/safepoint.cljc:20) calls `php/unwind-to`, which executes `:finally` thunks. Those include guest `finally` blocks and local-variable cleanup that can invoke destructors. A `finally { return 1; }` can escape before `php/raise` delivers the operational fatal. This violates §12’s non-catchable fatal and destructor-skipping contract. Forward fix: use a terminal root escape that guest handlers, returns, and finalizers cannot replace.

- **P1 — Exhaustion during shutdown takes the wrong result path.** [prelude.cljc:5092](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude.cljc:5092) recognizes an operational failure only in `exc0`. A shutdown callback exhausting its budget produces operational `exc2`, which goes through `snapshot-exc` as though it were a guest Throwable. Forward fix: preserve operational termination throughout body, exception-handler, and shutdown execution.

- **P2 — Budget input is unchecked.** [safepoint.cljc:41](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/safepoint.cljc:41) accepts arbitrary values; a negative counter decrements forever without reaching zero. Reject values outside the documented non-negative integer domain.

The default-unlimited behavior, per-run cell, hook-only charging, and exact fatal text follow the intended design. The deterministic count is an explicitly documented PHP departure. This patch does not establish the signal-delivery portion of P7-a.

**2. `453aa72e` — P5 request context: DEFECT**

- **P1 — `exit` uses exception unwinding instead of escaping directly to the task root.** [prelude.cljc:445](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude.cljc:445) calls `php/raise`. Consequently, `try { exit(7); } finally { echo 'F'; }` executes `F`, and a return from `finally` can cancel termination. Exit during shutdown also lacks proper root handling. PHP executes shutdown functions and destructors after exit, but skips `finally` blocks. Forward fix: install a distinct root exit continuation and preserve its status through shutdown. [PHP exit semantics](https://www.php.net/manual/en/function.exit.php)

- **P1 — Unclosed output buffers disappear.** [prelude.cljc:5082](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude.cljc:5082) reads only buffer zero; `php/shutdown!` never drains the remaining buffers. Thus `ob_start(); echo 'x';` loses `x`. This directly violates row 22 and C22, including destructor output inside buffers. Forward fix: flush nested buffers after the destructor phase, before releasing globals.

- **P2 — Request binding is not integrated into the specified stream lifecycle.** The request injection occurs through a test-local postwalk at [e2e_harness.clj:160](/Users/sto/workspace/datomworld/test/yang/php/antlr/e2e_harness.clj:160). Production initialization neither consumes a request nor seeds its superglobals; responses and diagnostics remain collected vectors rather than emissions on composition-supplied streams. The explicit binder is useful, but §3.4/§3.9 completion is not established. Forward fix: wire the actual request/response/diagnostics composition and exercise that path.

- **P2 — `$_REQUEST` uses `array_merge` semantics.** [prelude.cljc:359](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude.cljc:359) renumbers numeric keys and replaces nested arrays. PHP’s request merge preserves numeric keys and recursively merges nested arrays. GET-then-POST is a valid pinned order; its merge operation is wrong. Forward fix: implement the request-specific merge. [PHP 8.3 implementation](https://raw.githubusercontent.com/php/php-src/PHP-8.3/main/php_variables.c)

- **P2 — Accepted HTTP arguments lose semantics.** At [prelude.cljc:418](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude.cljc:418), setting `http_response_code` returns `true`, rather than the previous status. `header` ignores its status argument; cookie records discard expiration, path, domain, secure, HttpOnly, and SameSite options. Forward fix: retain the supplied information and correct return values, or explicitly refuse unsupported forms. [Status semantics](https://www.php.net/manual/en/function.http-response-code.php), [cookie arguments](https://www.php.net/manual/en/function.setcookie.php)

- **P1/P2 — Error triggering is incomplete.** [prelude.cljc:450](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude.cljc:450) treats every level as a warning and continues, including unhandled `E_USER_ERROR`; invalid levels are accepted. The handler stack also retains only callbacks, leaving the inherited omission of handler masks unresolved. Forward fix: implement fatal user errors, validate levels, and save/restore callback plus mask. [Trigger errors](https://www.php.net/manual/en/function.trigger-error.php), [handler masks](https://www.php.net/manual/en/function.set-error-handler.php)

The explicit request data and task-local cells support the isolation design; I found no newly introduced ambient process reads or shared guest state.

The committed `:output-buffer-flush` fixture has an incorrect empty diagnostic expectation: its final `ob_end_clean()` must emit `E_NOTICE` because no buffer remains. [PHP 8.3 output implementation](https://raw.githubusercontent.com/php/php-src/PHP-8.3/main/output.c)

**3. `439ff42a` — P9 builtin surfaces: DEFECT**

There are defects beyond the disclosed `eval` and JSON-object/Unicode deferrals.

- **P1 — Ordinary inputs can cause nontermination.** [prelude_p9.cljc:74](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude_p9.cljc:74) never advances for `str_ireplace('', 'x', 'a')`; [line 144](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude_p9.cljc:144) never progresses when padding is needed with an empty pad string; [line 358](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude_p9.cljc:358) loops forever for `range(1, 3, 0)`. These prelude loops have no source safepoints. Forward fix: handle empty searches and validate progress-making arguments before entering loops. PHP rejects zero range steps. [PHP range](https://www.php.net/manual/en/function.range.php)

- **P2 — String operations accept arguments they do not implement.** Examples: `str_replace` with an empty search returns an empty result; `str_pad('abcd', 2)` truncates; `strrpos` subtracts a positive offset from the backward starting position instead of enforcing the search’s lower bound. Character masks, replacement arrays/count output, and several optional arguments are silently ignored. See [prelude_p9.cljc:39](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude_p9.cljc:39), [line 84](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude_p9.cljc:84), and [line 145](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude_p9.cljc:145). Forward fix: implement each advertised signature or give a named refusal. [Padding semantics](https://www.php.net/manual/en/function.str-pad.php), [reverse-search offsets](https://www.php.net/manual/en/function.strrpos.php)

- **P2 — Array operations do not preserve their contracts.** [prelude_p9.cljc:329](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude_p9.cljc:329) renumbers keys for single-array `array_map` and ignores additional arrays. `array_unique` uses loose equality instead of its default string comparison; diff/intersect ignore additional arrays. Splice bounds are not normalized, and omitted replacement becomes a vector value rather than an empty PHP replacement array. Forward fix: reconcile keys, comparisons, variadic inputs, and splice normalization with PHP. [array_map](https://www.php.net/manual/en/function.array-map.php), [array_unique](https://www.php.net/manual/en/function.array-unique.php), [array_splice](https://www.php.net/manual/en/function.array-splice.php)

- **P1/P2 — JSON can corrupt data and accept invalid syntax.** [prelude_p9.cljc:391](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude_p9.cljc:391) replaces unhandled control bytes with `?`. The parser accepts trailing commas, arbitrary escaped characters, and unchecked object separators. Associativity and depth are ignored; encoding error behavior does not respect `JSON_THROW_ON_ERROR`. Forward fix: preserve input data through correct escaping, enforce grammar/depth, propagate parse failure, and implement flags or refuse unsupported forms. Returning arrays for objects is disclosed in findings, but is still an unfinished parity obligation. [JSON encoding](https://www.php.net/manual/en/function.json-encode.php), [decoding](https://www.php.net/manual/en/function.json-decode.php)

- **P2 — `extract`/`compact` semantics and constants are wrong.** [prelude_module.cljc:199](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude_module.cljc:199) omits the mandatory underscore between prefix and key; existence tests use `isset`, incorrectly treating null-valued variables as absent; most extraction modes fall through to overwrite. [builtins.cljc:166](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/builtins.cljc:166) misnumbers `EXTR_PREFIX_INVALID` and `EXTR_PREFIX_IF_EXISTS` and introduces nonexistent constants. `compact` drops null-valued variables and silently skips missing names. Forward fix: correct constants, use binding existence, implement modes, and emit required warnings. [extract](https://www.php.net/manual/en/function.extract.php), [compact](https://www.php.net/manual/en/function.compact.php)

- **P2 — New special cases bypass PHP name resolution.** [lower.cljc:1853](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/lower.cljc:1853) intercepts `extract`/`compact` by fallback name even when a namespaced user function was resolved. Forward fix: apply builtin lowering only when the call actually resolves to that builtin.

- **P2 — Math results fail outside the tiny fixtures.** [prelude_p9.cljc:539](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude_p9.cljc:539) lets `abs(PHP_INT_MIN)` escape the PHP integer range. `round` ignores precision/mode; `min([3,1,2])` returns the array; the fixed 40-step square-root iteration is grossly inaccurate for large/small magnitudes. Forward fix: implement PHP’s numeric contracts and exceptional-value handling. [round](https://www.php.net/manual/en/function.round.php), [min](https://www.php.net/manual/en/function.min.php), [sqrt](https://www.php.net/manual/en/function.sqrt.php)

Three committed fixtures specifically disagree with PHP 8.3:

| Fixture | Required correction |
|---|---|
| `programs_p9.cljc:28`, multi-byte string assignment | Keep `aXZ`, but record `E_WARNING`: “Only the first byte will be assigned to the string offset.” |
| `programs_p9.cljc:91`, prefix `'p_'` | PHP creates `$p__z`; reading `$p_z` warns. Expected output is `15`, not `156`. |
| `programs_p9.cljc:94`, missing compact variable | Keep `3,4`, but record the missing-variable warning. |

The first-byte value change is correct; suppressing its warning is not. [PHP 8.3 assignment implementation](https://raw.githubusercontent.com/php/php-src/PHP-8.3/Zend/zend_execute.c)

The named refusals for C6/C11/C12 and whole-`$GLOBALS` operations broadly follow the restriction table. They do not justify claiming the §15 P9 acceptance row—including caller-scope `eval`—complete.

**4. `716326aa` + `a7ea0796` — cross-language matrix: ACCEPT, with notes**

The committed helper satisfies the narrow composition requirement:

- It publishes real lowered donor bodies.
- It projects the donor’s callable value into the caller’s namespace.
- It neither replaces call nodes nor extracts foreign `:code`.
- Python’s injected namespace bindings are explicit composition, not language-pair call adapters.

See [conformance.clj:202](/Users/sto/workspace/datomworld/test/yang/call/conformance.clj:202).

The evidence is narrower than the recorded “four-frontends conformance closed” claim:

| Host | AST walker | Semantic | Stack | Register |
|---|---|---|---|---|
| JVM | Explicitly excluded | Covered | Covered | Covered |
| Node / Dart | No evidence in this unit | — | — | — |

The tests cover the six directed pairs among TS, PHP, and Python. [cross_lang_test.clj:102](/Users/sto/workspace/datomworld/test/yang/cross_lang_test.clj:102) is a **Clojure refusal assertion**, not an executed Clojure leg.

The matrix also does not establish all §8.10.11 gates: exception identity/rethrow, reference refusal and cleanup, suspension/context restoration, dynamic callability, custody, and the full binding/presence cases remain unproven here. Forward follow-up: record these limits explicitly and derive pair coverage from admitted profiles so new admissions cannot silently lack legs.

**5. Ledger closures and pin changes: DEFECT in reconciliation; dedupe itself ACCEPT**

- **Current master still contains the obsolete string-write refusal** at [known-gaps.md:107](/Users/sto/workspace/datomworld/docs/agents/known-gaps.md:107), despite P9 implementing in-range writes.
- `bab43c51` updates that row and the stale pins, but the supplied refs place it on `feat/p8-legacy-retirement`, not master. Its single-byte outputs (`cb`, `xbc`) and `$$a[0]` output `1,20` agree with PHP 8.3. The latter follows PHP 7+ left-to-right variable syntax. [PHP syntax ruling](https://wiki.php.net/rfc/uniform_variable_syntax)
- At `bab43c51:test/yang/php/antlr/programs.cljc:574`, the fixture source changes while retaining the old dated oracle label. Forward fix: record the new source or mark its provenance pending.
- The string-write closure should explicitly retain C5’s remaining restrictions and the missing multi-byte warning; it must not imply unrestricted C5 parity.
- `d01a7c80` removes three identical datagram rows and retains one closed row citing `a226423d`. That dedupe loses no distinct ledger information. It is separate from these PHP/TS closures.

The correct ledger outcome is a narrowly closed **in-range single-byte write gap**, with the defects and unfinished acceptance obligations above retained explicitly.