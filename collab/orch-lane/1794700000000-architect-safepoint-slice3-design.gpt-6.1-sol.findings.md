Created-Local: 2026-11-05 0x:xx +0700
Coding-Agent: codex (gpt-6.1-sol, architect design — safepoint slice 3)

I’ll read the architecture rules, section 12, and the current safepoint code, then develop a shared `:line` design for PHP ticks and Python tracing. I’ll keep this read-only and cite the relevant file locations.

---

The shared `:line` extension is already in this checkout, along with Python tracing hooks and tests. The PHP section still describes it as pending. I’ll distinguish the existing contract from the PHP integration that remains, including whether a shared “current line” API needs anything beyond the hook operand already implemented.

---

The main semantic issue is tick timing: the shared `:line` hook runs before its marked node, while Zend counts ticks after executing selected statements. A counter at every statement start would get callback order wrong. I’m checking how PHP lowering can preserve that timing while reusing the shared insertion primitive.

---

I’ve confirmed that include units need an explicit site-set handoff: canonical published rows carry no marks. I’ll make that a landing requirement for PHP, alongside a small profile option for hooks that run after normal statement completion. Python will keep its existing hook-before behavior, and neither path needs a VM semantic change.

---

The shared `:line` mechanism already exists in this checkout. The remaining design work is PHP integration and one timing extension: Python needs a hook **before** a statement; PHP ticks need a hook **after normal completion**. Both can use the same marks, literal-operand API, and derivation stage.

This is a proposed design, based on source inspection. No files were edited and no tests were run.

**1. Existing contract and proposed extension**

The PHP dependency statement is stale: [yang.php.md:1938](/Users/sto/workspace/datomworld/docs/design/yang.php.md:1938) says non-lambda sites are refused, but the implementation already defines `statement-kinds #{:line}` and sequences the hook before any marked node. See [safepoint.cljc:85](/Users/sto/workspace/datomworld/src/cljc/yang/safepoint.cljc:85) and [safepoint.cljc:172](/Users/sto/workspace/datomworld/src/cljc/yang/safepoint.cljc:172). Python tracing has bundled and linked profiles, and the design records its landing at `a822e152`: [yang.antlr.md:4645](/Users/sto/workspace/datomworld/docs/design/yang.antlr.md:4645).

No separate `docs/design/yang.python.md` is present. Python’s relevant specification is §8.5.2 of `yang.antlr.md`.

Retain the existing mark model:

- A `:line` mark belongs to the **occurrence of the lowered statement node**, including its statement cleanup.
- It does not belong to every expression, instruction, physical newline, or lambda.
- Separate statements on the same source line remain separate sites.
- A closure’s construction statement and statements inside its body have separate occurrences.
- Prelude code and generated implementation machinery remain unmarked.

This follows [yang.antlr.md:1596](/Users/sto/workspace/datomworld/docs/design/yang.antlr.md:1596). PHP’s existing temporary-cleanup boundary is particularly relevant: [lower.cljc:2279](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/lower.cljc:2279).

Add one **proposed**, language-neutral profile option:

```clojure
;; Existing behavior: before the marked node.
{:line {:hook 'py.sp/tline :operands true}}

;; Proposed behavior: after the marked node completes normally.
{:line {:hook 'php.sp/line :operands true :after true}}
```

The default remains hook-before. `:after true` is valid only for statement sites; invalid combinations should fail during derivation.

The reason is observable PHP behavior. Zend emits tick instructions following statement compilation, excludes particular statement categories, and suppresses adjacent duplicate tick instructions. Consequently, counting every statement entry is insufficient for PHP parity. PHP lowering must select the corresponding completion sites, including declaration and control-flow cases. [PHP 8.3 compiler](https://raw.githubusercontent.com/php/php-src/PHP-8.3/Zend/zend_compile.c)

For a marked node `S`, the two rewrites are conceptually:

```clojure
;; Before: existing.
(do (hook literal-operands...) S)

;; After: proposed. Preserve S's value mechanically.
(let [yang.safepoint/value S]
  (do (hook literal-operands...)
      yang.safepoint/value))
```

Both lower entirely to ordinary applications and lambdas. The after-hook runs once on normal completion, including completion after a suspension resumes. An escape bypassing completion skips it. It is not a `finally` hook.

Marks must not overwrite existing `:call` or `:loop` marks. PHP should mark the outer statement/cleanup node while preserving inner call sites and loop lambdas. The current representation holds one mark per path; adding multiple marks per occurrence is outside this slice. See [core/lower.cljc:76](/Users/sto/workspace/datomworld/src/cljc/yang/core/lower.cljc:76).

**2. Writer and reader API**

Use the existing metadata and literal-operand API, without introducing a VM “current line” register.

| Operation | Contract |
|---|---|
| Frontend writes a site | `(core/site [:line n ...literal-data] statement-node)` |
| Projection transports it | `[origin root path :yang/site mark]` |
| Stage reads sites | `(safepoint/sites metadata origin root)` |
| Composition selects behavior | Profile entry for `:line` |
| Runtime consumer reads the line | First literal argument passed to its hook |

These APIs already exist: [core/lower.cljc:76](/Users/sto/workspace/datomworld/src/cljc/yang/core/lower.cljc:76), [yin/vm.cljc:1217](/Users/sto/workspace/datomworld/src/cljc/yin/vm.cljc:1217), and [safepoint.cljc:96](/Users/sto/workspace/datomworld/src/cljc/yang/safepoint.cljc:96).

Recommended consumer shapes:

```clojure
;; Python: existing.
[:line n]
;; -> (py.sp/tline n)

;; PHP: proposed payload; generic insertion does not interpret the map.
[:line n {:unit unit-path :ticks N}]
;; -> (php.sp/line n {:unit unit-path :ticks N})
```

PHP receives the unit explicitly because a closure can execute after its defining include has returned. Reading only the currently including unit would misidentify its source.

The line-number conventions remain frontend-owned:

- PHP uses its packet’s physical-line side table; existing code returns `0` when unavailable: [lower.cljc:3298](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/lower.cljc:3298).
- Python currently uses logical lines counted from `NEWLINE` tokens. This slice preserves that documented limitation: [lower.cljc:198](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/lower.cljc:198).

The PHP tick consumer uses the supplied `N` and a request-owned counter. Increment at each selected completion; when the counter reaches that site’s threshold, reset it before dispatch. Counter state survives calls, includes, and generator switches. Zend likewise uses one execution counter with the threshold carried by each tick instruction. [PHP 8.3 tick handler](https://raw.githubusercontent.com/php/php-src/PHP-8.3/Zend/zend_vm_def.h)

Keep this state separate from:

- The execution-budget counter, currently named `php.sp/tick`: [PHP safepoint.cljc:23](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/safepoint.cljc:23).
- Escape-restored dynamic context.
- Source-location data.

PHP callback dispatch needs its own semantics. In particular, suppression is **per executing registered handler**, rather than Python’s global tracing-busy flag. Hook operands are not additional arguments to registered PHP callbacks. [PHP tick callback implementation](https://raw.githubusercontent.com/php/php-src/PHP-8.3/ext/standard/basic_functions.c)

Python already consumes the same primitive: `py.sp/tline` receives `n`, finds the running function’s frame, writes `f_lineno`, and invokes its local trace function. See [Python safepoint.cljc:265](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/safepoint.cljc:265). Its tracing state and frame restoration remain Python-owned.

Thus “current line” means the location supplied to the current hook. A consumer may retain that location in its own frame or context. There is no ambient cross-language mutable location.

**3. Mark lifecycle and linked retention**

Marks are static occurrence data. Executing a mark does not consume or remove it.

| Boundary | Required behavior |
|---|---|
| Include unit | Retain that unit’s own statement locations and lexical tick thresholds. Sharing caller variables does not share its source map. Repeated execution revisits its sites; `_once` skips the body when appropriate. |
| Eval unit, P9 | A new compilation produces its own source occurrence, site set, and derivation under the run’s selected profile. Caller scope sharing does not substitute caller locations. Until P9, preserve the existing unsupported result. |
| Generator suspension | Instrumented code and the pending continuation survive suspension. A before-hook is not replayed merely by resuming; a pending after-hook runs when its statement eventually completes normally. |
| Closure | Its body retains defining-unit locations and compile-time tick thresholds. Invocation uses the invoking request’s runtime tick state. Creating a closure does not execute its body’s sites. |
| Exception/escape | Static sites remain available. Frame/location restoration follows language context restoration; the request’s tick counter is not rolled back. |

Relevant existing boundaries are [yang.php.md:1875](/Users/sto/workspace/datomworld/docs/design/yang.php.md:1875), [yang.php.md:360](/Users/sto/workspace/datomworld/docs/design/yang.php.md:360), and [PHP prelude.cljc:4506](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude.cljc:4506). PHP already saves generator context at yield and restores the caller context: [prelude.cljc:4538](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude.cljc:4538).

This guarantees location retention, not complete CPython generator-tracing parity. Python’s existing call mark surrounds function invocation, including generator construction: [Python lower.cljc:1482](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/lower.cljc:1482). Generator trace-event policy needs separate acceptance before broader parity is claimed.

The linker retention contract is:

| Artifact | Retain |
|---|---|
| Canonical program `A` | Original rows, without line literals or mark fields |
| Published source association | Content-addressed occurrence site set, bound to the exact canonical root and frontend/profile revision |
| Derived executable `A′` | Selected hook applications and their literal operands |
| Derivation record | Input/output roots, complete hook profile—including `:after`—and selected-site-set address |
| Source archive | Original occurrence metadata and positions needed to recover provenance |

This matches the existing separation between canonical publication and derived execution: [yang.antlr.md:1613](/Users/sto/workspace/datomworld/docs/design/yang.antlr.md:1613). Derived envelopes intentionally contain empty side tables: [safepoint.cljc:234](/Users/sto/workspace/datomworld/src/cljc/yang/safepoint.cljc:234).

A published canonical tree alone cannot recover marks. Python addresses this through published site sets and link-time derivation: [yang.antlr.md:3040](/Users/sto/workspace/datomworld/docs/design/yang.antlr.md:3040). **PHP does not yet publish them**—its publication configuration explicitly sets `:site-set nil`: [publication.cljc:41](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/publication.cljc:41).

Therefore PHP’s linked ticks profile must:

- Publish and retrieve verified site sets for included guest code.
- Require the appropriate hook module and rebind dependencies to derived images.
- Refuse missing or invalid site metadata when ticks are required; it must not silently run that dependency without ticks.
- Pin the selected source/site association explicitly when multiple source occurrences share canonical rows.

For include cycles, instrument the SCC bundle and repin member aliases to that derived bundle. The existing publisher places marks on bundles, not aliases: [core/publication.cljc:147](/Users/sto/workspace/datomworld/src/cljc/yang/core/publication.cljc:147). Python currently documents an SCC admission limitation; copying that limitation would not establish PHP’s cyclic-include acceptance.

**Stripping rule:** omit `:line` from the profile to derive an executable without line hooks. Never delete inserted calls from an already derived image. A compact execution package may omit side tables after derivation, provided its pinned provenance remains retrievable. A package intended for later instrumentation must retain/retrieve its site set.

There is no automatic hook-prelude dead-code stripping promised here. Python’s current module emitter includes its definition list regardless of selected profile: [Python safepoint.cljc:425](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/safepoint.cljc:425).

**4. Cost and host deltas**

At an executed selected site, excluding consumer work:

| Placement | Added application occurrences | Added lambdas |
|---|---:|---:|
| Before | 2 | 1 |
| After, value preserving | 3 | 2 |
| `:line` omitted | 0 | 0 |

The existing before shape is visible at [safepoint.cljc:128](/Users/sto/workspace/datomworld/src/cljc/yang/safepoint.cljc:128). Runtime cell accesses, polling, frame updates, and guest calls are additional consumer costs; “one cell read per statement” is not a sufficient description of the current Python tracing hook.

Derivation traverses the program and recomputes tail marks; sorted site-set construction adds sorting work. Bundle growth includes wrappers, operands, changed ancestors, and tail-mark changes. Content addressing shares identical rows, so these occurrence counts are not byte estimates. Measure rows and serialized bytes in acceptance.

**No `yin.vm` semantic change is required.** No new opcode, effect, semantic-contract stamp, UCF field, or host location register is introduced.

Language instrumentation hooks are ordinary calls. They are distinct from the VM’s parking/migration safepoints, whose table concerns blocked operations, explicit parks, and halt: [yin.vm.semantic.md:655](/Users/sto/workspace/datomworld/docs/design/yin.vm.semantic.md:655).

The after-wrapper needs a distinct reserved result binder and tail recomputation. Its continuation uses existing machinery. Application placement alone adds no host effect capability.

**5. Acceptance and landing contract**

Use this PHP fixture to expose both count and callback ordering:

```php
<?php
function tick_handler() { echo "T"; }
register_tick_function('tick_handler');

declare(ticks=1) {
    echo "A";
    $x = 1;
    echo "B";
}

unregister_tick_function('tick_handler');
```

Proposed oracle assertions:

| Threshold | Output | Handler calls |
|---|---|---:|
| `1` | `ATTBT` | 3 |
| `2` | `ATB` | 1 |
| `0` | `AB` | 0 |

Record these against the project’s pinned PHP oracle before accepting them as goldens; the repository records PHP `8.3.35` oracle provenance at [programs.cljc:10](/Users/sto/workspace/datomworld/test/yang/php/antlr/programs.cljc:10).

PHP acceptance must additionally cover:

- Same-line statements and non-tickable expression subparts.
- Nested directives, threshold changes, and adjacent-tick suppression.
- Registration/unregistration inside ticked code and handler exceptions.
- Temporary destruction before the completion callback.
- Return, throw, break, continue, and `finally`.
- Included units with and without their own directives; `_once`; include cycles.
- Closures invoked after their defining include returns.
- Two generators interleaved across a yield, proving no replay and one shared request counter.
- Unchanged execution-budget charging: adding `:line` must not charge the `:loop`/`:call` budget.

For Python, retain the existing basic trace fixture and golden:

```text
call f 7
line f 8
call g 5
line g 6
return g 6
line f 9
return f 9
```

It already exists in bundled and linked tests: [safepoint_test.cljc:805](/Users/sto/workspace/datomworld/test/yang/python/antlr/safepoint_test.cljc:805), [linked_safepoint_test.cljc:401](/Users/sto/workspace/datomworld/test/yang/python/antlr/linked_safepoint_test.cljc:401). Preserve unwind restoration, callback suppression, newline handling, and unsupported-profile tests.

Do not enlarge Python’s parity claim: the documented slice excludes exception/C-call events, module-level events, and certain header events: [yang.antlr.md:1770](/Users/sto/workspace/datomworld/docs/design/yang.antlr.md:1770).

The **shared landing gate** is:

- Before and after placement work on non-lambda nodes, preserving values and evaluation order.
- Escapes skip after-hooks; suspension/resumption neither loses nor duplicates them.
- Empty selection remains identity; canonical input remains unchanged.
- Existing Python derivation goldens remain unchanged.
- Derivation is deterministic across JVM, Node, and Dart.
- All four evaluators pass applicable execution fixtures.
- Prelude exclusion, tail behavior, provenance joins, and fail-closed hook resolution hold.

Passing that gate unblocks PHP P7-b. P7-b itself remains incomplete until its tick semantics and linked-unit acceptance pass. Eval execution fixtures remain P9 work.

**6. Implementation slices, seats, and ordering**

| Order | Seat | Files and work |
|---|---|---|
| 1 | Architect + safepoint owner | Reconcile stale status in `docs/design/yang.php.md` §12 and `docs/design/yang.antlr.md` §8.5.2; specify `:after`, payloads, retention, and acceptance. |
| 2 | Shared safepoint compiler seat | `src/cljc/yang/safepoint.cljc`: add validated after-placement, value-preserving rewrite, and provenance-path handling. Reserve the result binder in `src/cljc/yang/core/names.cljc`. Add focused cases to `test/yang/safepoint_test.cljc`. |
| 3 | QA/verification seat | Run shared cross-host derivation checks and existing Python bundled/linked tracing regressions. Establish the shared landing gate. |
| 4 | PHP compiler/runtime seat | `src/cljc/yang/php/antlr/lower.cljc`: lexical directive tracking and completion-site selection. `safepoint.cljc`: distinct statement-tick hooks and bundled/linked emission. `prelude.cljc`: request-owned state and builtin wiring. Extend PHP safepoint fixtures. |
| 5 | Publication/linker integration seat | Enable the existing publication `:site-set` seam in PHP; add a PHP site-set adapter, verified link-time derivation, hook dependency pins, and SCC alias rebinding. Extend include and portable publication fixtures. |
| 6 | QA + independent reviewer | Record pinned PHP oracle results; verify consumer semantics, lifecycle, bundle sizes, and the required host/evaluator lanes. Review shared and PHP completion separately. |
| Later | P9 eval seat | Feed each admitted eval compilation through the same site/profiling contract and add eval-boundary fixtures. |

The shared insertion change belongs to the safepoint seat, as PHP explicitly requires at [yang.php.md:1944](/Users/sto/workspace/datomworld/docs/design/yang.php.md:1944). Python needs regression coverage for this work; its existing tracing implementation does not need to be rebuilt.