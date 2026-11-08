Created-Local: 2026-11-07 0x:xx +0700
Coding-Agent: codex (gpt-6.1-sol, architect step-7 + escalation rulings on Round B2)

I’ll read the architecture, relay logs, and branch diff, then rule on the three escalations and give a step-7 verdict with file and line references.

---

I’m using the code-review-pr skill. The relay confirms that occurrence-key equality belongs to each frontend’s own adoption gate; I’ll keep that scope separate from this lifecycle review.

---

Two issues are confirmed in the PHP 8.3 source: `$_REQUEST` overwrites matching integer keys rather than appending, and invalid `trigger_error` levels throw `ValueError`. The new `E_USER_ERROR` path also appears to terminate even when a handler consumes it. I’m checking that path and the initialization boundary before setting the verdict.

---

Both profiles should accept request input: linked modules publish immutable code and specifications, then create runtime state in the importing task. I also found that B2 embeds the request as a lowering-time literal and still collects responses and diagnostics in vectors. That removes the test rewrite, but leaves the stream-lifecycle finding open.

The oracle runner also needs work before it can gate these fixtures: it doesn’t enumerate B2, rejects nested request values, and constructs `$_REQUEST` with `array_merge`.

---

**Step-7: DEFECT at `03465799`.** Landing authority is withheld: the three escalations have rulings below, but additional correctness and acceptance gaps also need fixes on this branch.

Read-only review of all six changed files, including `lower.cljc`. No files changed or suites run. Diff analysis and `git diff --check` completed; shiva execution remains outstanding.

The escalation rulings:

1. **Init-time `raise` must terminate initialization and preserve the Throwable.** The ordinary `php/throw-error` path should produce the existing `:php/uncaught {:class … :message …}` result, rendered as `PHP Fatal error:  Uncaught …`. It must never become an array key or another successful value. Initialization timing alone does not turn a guest Throwable into an operational failure; operational failures retain their separate classification. This follows the project’s [exception semantics](/Users/sto/workspace/datomworld-b2/docs/design/yang.php.md:319).

   Move request binding at [prelude.cljc:5293](/Users/sto/workspace/datomworld-b2/src/cljc/yang/php/antlr/prelude.cljc:5293) inside the root protected region, after terminal escapes and the root exception handler exist, before invoking the body. The empty-stack branch at [prelude.cljc:196](/Users/sto/workspace/datomworld-b2/src/cljc/yang/php/antlr/prelude.cljc:196) must fail loudly as an invariant violation rather than return `e`. Simply calling the current `root-fatal` there would recurse through its unset-escape fallback. Add an initialization-failure regression asserting the exception snapshot, no body execution, and no Error-valued data.

2. **Invalid `trigger_error` levels throw `ValueError` in PHP 8.3.** The warning-plus-`false` implementation is incorrect. The pinned message for this call is:

   ```text
   trigger_error(): Argument #2 ($error_level) must be one of E_USER_ERROR, E_USER_WARNING, E_USER_NOTICE, or E_USER_DEPRECATED
   ```

   Use `php/throw-error "ValueError"` at [prelude.cljc:631](/Users/sto/workspace/datomworld-b2/src/cljc/yang/php/antlr/prelude.cljc:631). It is catchable; uncaught, it uses the ordinary exception snapshot, with no `E_WARNING` diagnostic or false return. Update the [fixture](/Users/sto/workspace/datomworld-b2/test/yang/php/antlr/programs_p5b2.cljc:97) and the test’s unconditional nil-uncaught assertion. Add a caught case too. [PHP 8.3 implementation](https://raw.githubusercontent.com/php/php-src/PHP-8.3/Zend/zend_builtin_functions.c)

3. **`:linked` must accept requests too.** Request semantics belong to the PHP task, independent of prelude delivery. The [request model](/Users/sto/workspace/datomworld-b2/docs/design/yang.php.md:206), [§8.10.9 publication rule](/Users/sto/workspace/datomworld-b2/docs/design/yang.antlr.md:3943), and §8.11 require published code/specifications with task-local runtime state. They provide no bundled-only exception.

   Keep one runtime request entry point for both profiles at [lower.cljc:3790](/Users/sto/workspace/datomworld-b2/src/cljc/yang/php/antlr/lower.cljc:3790); instantiate request state in the importing task. Add linked request/isolation coverage through [linked_harness.cljc:127](/Users/sto/workspace/datomworld-b2/test/yang/php/antlr/linked_harness.cljc:127). Do not publish live request cells or per-request superglobals in module exports.

Additional step-7 findings:

- **P1 — Integer-key request merge is wrong.** [prelude.cljc:416](/Users/sto/workspace/datomworld-b2/src/cljc/yang/php/antlr/prelude.cljc:416) appends integer entries. PHP preserves their keys, overwrites colliding scalar values, and recurses when both colliding values are arrays—also for integer keys. Apply the same collision logic to both key kinds. The numeric fixture’s request should contain `ids[0]="30"` and `ids[1]="20"`, with no `ids[2]`; its current `102030post` expectation is wrong. The string-key nested case is correct. Add sparse integer keys and integer-key nested collisions. [PHP request merge](https://raw.githubusercontent.com/php/php-src/PHP-8.3/main/php_variables.c)

- **P1 — `E_USER_ERROR` terminates even when handled.** [prelude.cljc:627](/Users/sto/workspace/datomworld-b2/src/cljc/yang/php/antlr/prelude.cljc:627) ignores whether the handler consumed the error. A matching handler returning anything except `false` permits execution to continue; absent/excluded/false-returning handlers take the default fatal path. Make handling disposition explicit rather than infer it from diagnostic emission. [Zend handler dispatch](https://raw.githubusercontent.com/php/php-src/PHP-8.3/Zend/zend.c)

  The default fatal path also cannot reuse operational termination unchanged: [prelude.cljc:5325](/Users/sto/workspace/datomworld-b2/src/cljc/yang/php/antlr/prelude.cljc:5325) bypasses shutdown functions and buffer draining. PHP still executes registered shutdown functions and flushes buffers after this fatal, while marking existing objects destructed. Preserve a distinct guest-fatal reason and test shutdown output, buffered output, handled continuation, and false-returning fallback. [PHP fatal and shutdown implementation](https://raw.githubusercontent.com/php/php-src/PHP-8.3/main/main.c)

- **P2 — The stream-lifecycle finding remains open.** [lower.cljc:3791](/Users/sto/workspace/datomworld-b2/src/cljc/yang/php/antlr/lower.cljc:3791) embeds the request in compiled UAST; [prelude.cljc:456](/Users/sto/workspace/datomworld-b2/src/cljc/yang/php/antlr/prelude.cljc:456) still collects responses in a vector, as diagnostics do. Removing the postwalk establishes a production binder, but does not establish consumption/emission over composition-supplied request, response, and diagnostic streams. Wire and exercise that composition before declaring this P2 closed.

- **P2 — HTTP behavior still has gaps.** Returning the previous status is correct for the existing 200-initialized profile. However, [prelude.cljc:524](/Users/sto/workspace/datomworld-b2/src/cljc/yang/php/antlr/prelude.cljc:524) treats `http_response_code(0)` as a setter; PHP treats it as a getter. Also, [prelude.cljc:504](/Users/sto/workspace/datomworld-b2/src/cljc/yang/php/antlr/prelude.cljc:504) claims HTTP status-line parsing that the implementation lacks. Implement and test those cases. Retaining `:replace` as an appended command is appropriate; test repeated, case-insensitive header names to establish replacement behavior. [Response-code implementation](https://raw.githubusercontent.com/php/php-src/PHP-8.3/ext/standard/head.c), [header status handling](https://raw.githubusercontent.com/php/php-src/PHP-8.3/main/SAPI.c)

- **P2 — Cookie retention is useful, but the accepted signature is inaccurate.** [prelude.cljc:568](/Users/sto/workspace/datomworld-b2/src/cljc/yang/php/antlr/prelude.cljc:568) invents an eighth positional SameSite argument. PHP accepts at most seven positional arguments; SameSite belongs in the options array. Array option names are case-insensitive; unknown/numeric keys throw `ValueError`, and array form rejects extra positional arguments. Correct these newly interpreted forms. [PHP cookie parser](https://raw.githubusercontent.com/php/php-src/PHP-8.3/ext/standard/head.c)

The callback-and-mask save/restore implementation looks sound for ordinary nesting at [prelude.cljc:4623](/Users/sto/workspace/datomworld-b2/src/cljc/yang/php/antlr/prelude.cljc:4623). Its fixture only restores the default handler, though; add two distinct handlers/masks and restore the earlier pair.

The nine `:oracle :pending` fixtures are appropriately labelled provisional, but their passing assertions are not acceptance evidence yet:

- The [new test](/Users/sto/workspace/datomworld-b2/test/yang/php/antlr/p5b2_lifecycle_test.clj:16) runs only the AST walker.
- The [oracle runner](/Users/sto/workspace/datomworld-b2/src/dev/yang/php/oracle.clj:364) does not enumerate B2. Its request serializer rejects nested values, its prologue still uses `array_merge`, and its fatal parser does not preserve the new guest-fatal representation.
- The initial-status expectation needs an explicit HTTP scaffold/profile treatment: bare CLI starts without the assumed HTTP 200. Header/cookie response-record shapes additionally need composition-contract checks.

**Boundary discipline is confirmed:** no `yin.vm`, AST-tag, or codec changes. All corrections can remain in the PHP frontend, prelude, composition, and tests. Complete these fixes and run the relevant backend/e2e and corrected oracle gates before landing. The relayed occurrence-key equality requirement remains part of each frontend’s adoption acceptance; this B2 review does not discharge it.