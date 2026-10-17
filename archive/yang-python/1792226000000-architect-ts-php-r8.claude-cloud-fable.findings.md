Completed-GMT: 2026-10-17 08:43:04 GMT
Completed-Local: 2026-10-17 15:43:04 +0700

# Revised designs, round 8: four claimed-row corrections against PHP 8.3 ground truth

Role: Lead System Architect. Findings only; nothing edited. This round answers the round-7 codex review (`collab/1792224000000-reviewer-ts-php-r7.gpt-6.1-sol.findings.md`): four P1 defects inside claimed rows, all accepted. The orchestrator's first-hand PHP 8.3 runs (php:8.3-cli, shiva) are the ground truth for three of them and are cited below as fixtures F1 to F3 of the new `oracle-2026-10-17` fixture group; the fourth (constructors) follows from the `NEW` handler and gets its own oracle fixtures to run before landing. The blocking rule of round 7 §5 applies: these are corrections of normative rows, confirmed by oracle, not reclassifications. Everything previously confirmed closed is unchanged.

## 1. Iterator copy lineage (P1-1, oracle F1)

**Ground truth F1**: `$a=[10,20,30]; foreach ($a as &$v) { echo "$v,"; if first { $b=$a; $a[2]=99; } }` prints `10,20,99,`.

Reading it against the handlers: `FE_RESET_RW` made `$a` a reference holding table h with the iterator registered on h. `$b = $a` reads through the reference and `ADDREF`s h (rc 2). `$a[2] = 99` is a write fetch through the reference: rc 2 → `SEPARATE`: `zend_array_dup` produces h′ for the reference, and the duplication **carries the iterator position**: Zend copies the source's internal pointer and iterator bookkeeping into the copy so that `zend_hash_iterator_pos_ex`, finding the loop's table changed from h to h′, resumes at the duplicated position rather than at the table start. The write lands in h′[2]. The next fetch is on h′ at the carried position: `20`, then `99`. `$b` keeps h: `[10,20,30]`.

Round 7's row 54 ("duplication does not carry iterators") is withdrawn. Corrected rows:

| # | Construct (handler) | Rule |
|---|---|---|
| 60 | `DUP(h)` of a table with registered iterators (`zend_array_dup` with iterator tracking) | `h′` is created as before (round 5's `DUP`), **and** for every iterator `it` registered on h a **lineage record** is appended to `it`: `it.copies += {h′ → it.pos}`, the position at duplication time. h′ itself registers no iterators (the iterator stays on h until a fetch finds the loop reference holding another table). `:ptr` (the internal pointer) is copied from h to h′ unchanged |
| 25 (revised) | `FE_FETCH_RW` migration branch (`zend_hash_iterator_pos_ex`, table mismatch) | let `h_now` be the instance the loop reference holds. **Lineage hit**: `h_now ∈ it.copies` (directly, or through a chain of lineage records: a copy of a copy made while no fetch intervened inherits the recorded position): if `rc(h_now) > 1` → `SEPARATE` the loop reference's value first (rows 60 and 25 compose: the new copy inherits the record); unregister from the old table, register on the resulting table, `it.pos := recorded position`; drop the lineage records; fetch at `it.pos`. **Unrelated table** (no lineage): round 7's rule, separate when shared and `it.pos := ptr(h_now)`. **Same table**: no separation, fetch at `it.pos` |
| 54 (revised) | separation performed by another holder (`$b = $a; $b[0] = 9` inside the loop) | `$b`'s write separates `$b`'s copy; the loop reference still holds the iterator's table; no migration; the lineage record created by that `DUP` is inert and is dropped when the iterator is freed or migrates elsewhere |
| 61 | lineage expiry | a lineage record is consulted only at the first fetch after it was created; any fetch on the iterator's current table drops all records (Zend's position is re-derived from the live table on every fetch, so a stale copy made several iterations ago is "unrelated" by then and gets the `ptr` rule); `FE_FREE` drops them with the iterator |

Trace of F1: iteration 1 fetches h[0] (`10`), `$b = $a`: rc(h) 2; `$a[2] = 99`: `SEPARATE` → `DUP(h)` → h′, lineage `{h′ → 0}`; write h′[2]; iteration 2 fetch: loop reference holds h′, lineage hit, rc(h′) 1, `it.pos := 0`, then advance to the next existing key → h′[1] (`20`); iteration 3 → h′[2] (`99`); `$b` reads h (`10,20,30`). Output `10,20,99,` ✓. (The recorded position is the position of the element being processed; the fetch after migration advances from it, exactly as a same-table fetch would.)

Fixtures: **F1** verbatim (`oracle-2026-10-17/F1`); round 7's `$b = $a; $b[0] = 9` (loop unaffected, `$b` sees 9); migration to an unrelated table (round 6's reviewer program, `$b[0]` is 2); a copy made before the loop then assigned back mid-loop (unrelated: restarts at the table's `:ptr`, 0); a chain copy (`$b = $a; $a[2] = 99; $c = $a; $a = $c;` within one iteration: lineage through the chain, position carried); `current($a)` inside a by-reference loop after the separation (oracle-pinned, probe 1); php-src `tests/lang/foreachLoop.0{1..4}*.phpt` by-reference series already selected.

## 2. `new` on a class without a constructor (P1-2): `NEW`, `DO_FCALL` dummy frame

Zend's `NEW`: resolve the class (`Class "X" not found` `Error` before anything), refuse non-instantiable kinds (`Cannot instantiate abstract class C` / `interface` / `trait` / `enum C`), create the object, then look up the constructor through `get_constructor`: a **missing constructor is not an error**. With no constructor and no arguments the call is skipped; with arguments Zend pushes a dummy frame so the argument expressions are still evaluated (side effects happen) and their values are released. An inaccessible constructor is `Error: Call to private C::__construct() from global scope` (or `protected`, or `from scope D`), raised by `get_constructor` at `NEW`, before arguments.

| # | Construct (handler) | Rule |
|---|---|---|
| 53 (revised) | `new C(args)` begins (`NEW`) | (1) resolve `C` (`Error` if not found, no arguments evaluated); (2) refuse abstract classes, interfaces, traits and enums (`Error`, no arguments evaluated); (3) create the object (rc 1, held by the pending call as `:this`, `destructed? false`, constructor not yet run); (4) constructor lookup: **present and accessible** → pending call targets it; **present but inaccessible** from the calling scope → `Error: Call to <visibility> C::__construct() from <scope>`, the object is released without `__destruct` (ctor-failed); **absent** → the pending call targets a **dummy callee**: arguments are still evaluated and acquired (rows 33 to 35, 57) in order and released at row 58 instead of being bound; `new C` without parentheses behaves as zero arguments |
| 58 (revised) | call entry for a dummy callee | release `:args` in order; the result of the `new` expression is the object, an owned temporary of the caller |
| 62 | argument evaluation throws during `new` (`zend_object_store_ctor_failed`) | the pending call's cleanup (row 39) releases the half-built object with the ctor-failed mark, so `__destruct` is **not** called; properties with defaults are released |
| 53 (unchanged part) | function and method calls | callee resolution before arguments stands (undefined function/method, non-callable, `__call` fallback) |

Fixtures (oracle-run before landing, `oracle-2026-10-17/C1..C4`): `class C {} new C();` (ok); `new C(side())` where `side()` echoes (echo appears, object created); `abstract class A {} new A(side())` (`Error`, no echo); `class P { private function __construct(){} } new P(side())` from global scope (`Error`, no echo); `class D { function __destruct(){ echo "d"; } function __construct($x){} } new D(throwing())` (no `d`); php-src `Zend/tests/new_*.phpt`, `tests/classes/ctor_*`, `constructor_*` filtered.

## 3. Named-argument errors at the send (P1-3, oracle F2)

**Ground truth F2**: `f(bad: 1, x: side())` with `side()` echoing `late` raises `Error: Unknown named parameter $bad` immediately; `late` is never printed.

So the name is resolved at **its own send** (`zend_handle_named_arg` inside `SEND_*_EX`), for user and internal functions alike, as soon as the callee is known, which it is from `INIT` (row 53). Round 7's "at entry after all arguments" is withdrawn.

| # | Construct (handler) | Rule |
|---|---|---|
| 57 (revised) | named argument `name: expr` evaluated (`SEND_VAL_EX`/`SEND_VAR_EX` with a name, `zend_handle_named_arg`) | **before** evaluating `expr`, resolve `name` against the callee's parameter list: **unknown** and the callee is not variadic → `Error: Unknown named parameter $name` now; pending-call cleanup (row 39) releases arguments already sent; `expr` never runs. **Unknown and variadic** → the argument will be collected into the variadic array under the string key; `expr` is evaluated and acquired. **Already supplied** (positionally, or by an earlier named argument) → `Error: Named parameter $name overwrites previous argument` now, before `expr`. Otherwise `expr` is evaluated and acquired immediately (rows 33 to 35) into the slot the name designates (gaps between filled slots are marked undefined until entry) |
| 63 | call entry with named arguments (`DO_FCALL` with `ZEND_CALL_MAY_HAVE_UNDEF`) | undefined slots receive the parameter's default (evaluated in the callee); a required parameter left undefined is `ArgumentCountError: f(): Argument #n ($p) not passed`; the variadic array is built from trailing positionals followed by string-keyed unknown named arguments in send order |
| 64 | positional argument after a named one | compile-time `Error: Cannot use positional argument after named argument` (a frontend `:yang.php/compile-error` diagnostic) |
| 65 | spread with string keys `f(...['x' => 1])` | each string key is treated as a named argument under row 57 at the spread's evaluation (`SEND_UNPACK` with keys), including the two `Error`s; integer keys are positional; mixing after string keys is `Error: Cannot use positional argument after named argument` at run time |

Trace of F2: row 53 resolves `f`; first send `bad:` → unknown, `f` not variadic → `Error` before `1` or `side()` is evaluated; no `late`. ✓

Fixtures: **F2** verbatim (`oracle-2026-10-17/F2`); `f(1, x: side())` with one parameter `$x` (overwrite `Error`, no echo); `g(...$args)` variadic with `g(bad: side())` (echo, `$args['bad']`); `f(x: 1, 2)` (compile error diagnostic); `h(...['x' => 1, 'y' => 2])` and `h(...['x' => 1, 3])` (run-time `Error`); php-src `Zend/tests/named_params/*.phpt` (the whole directory, filtered to the supported surface; `unknown_named_param.phpt`, `overwrite_*.phpt`, `undef_*` are the direct oracles).

## 4. Readonly reinitialization versus initialization after unset (P1-4, oracle F3)

**Ground truth F3**: `function __clone() { unset($this->x); $this->x = 2; }` on `public readonly int $x` initialized to 1 yields `2`, no error.

Two states, not one: `initialized?` (the property slot holds a value, versus uninitialized) and the clone-time flag `reinitable` (`IS_PROP_REINITABLE`). The write handler's readonly branch: **uninitialized** readonly → ordinary **initialization**, permitted from the declaring class scope regardless of any flag (`Cannot initialize readonly property C::$x from scope …` otherwise); **initialized** readonly → permitted only if `reinitable` is set (then cleared), else `Error: Cannot modify readonly property C::$x`. `unset` of a readonly property: permitted from the declaring scope if the property is uninitialized or `reinitable` (clearing it), making it uninitialized; otherwise `Error: Cannot unset readonly property C::$x`.

| # | Construct (handler) | Rule |
|---|---|---|
| 52 (revised, readonly part) | `__clone` entry/exit | at entry every readonly property of the clone gets `reinitable`; at exit (normal or exception) all flags clear |
| 66 | write to a readonly property (`zend_std_write_property`, readonly branch) | **uninitialized**: initialization from the declaring scope, allowed; flag untouched (it clears only on a reinitializing write, see next); from another scope: `Error: Cannot initialize readonly property C::$x from scope S` (or `global scope`). **initialized, `reinitable`**: allowed, value replaced under the type rules (rows 42-style verification), flag cleared. **initialized, not `reinitable`**: `Error: Cannot modify readonly property C::$x` |
| 67 | `unset` of a readonly property (`zend_std_unset_property`, readonly branch) | **uninitialized**: allowed from the declaring scope (no-op on the value; the flag, if set, is cleared); **initialized, `reinitable`**: allowed, becomes uninitialized, flag cleared; **initialized, not `reinitable`**: `Error: Cannot unset readonly property C::$x`; from outside the declaring scope in any state: the corresponding scope `Error` |
| 68 | increment/compound ops and references on readonly | `$this->x++` on initialized readonly is a modification under row 66 (allowed once with the flag); `=&` on readonly remains the `Error` of row 41 in every state, flag or not |

Trace of F3: entry sets `reinitable` on `x`; `unset($this->x)`: initialized and `reinitable` → uninitialized, flag cleared; `$this->x = 2`: uninitialized, declaring scope → initialization, allowed; echo `2`. ✓ Two overwrites: first clears the flag, second is `Cannot modify readonly property`. ✓ (round 7's defect corrected.)

Fixtures: **F3** verbatim (`oracle-2026-10-17/F3`); two overwrites in `__clone` (`Error` on the second); `unset` twice in `__clone` (second: uninitialized and flag cleared → allowed no-op, per the row; **oracle-pinned before landing**, probe 4); write after `__clone` returned (`Error`); initialization of a never-initialized readonly property from outside the class (`Error … from global scope`); a child class's `__clone` modifying the parent's readonly property (8.3: `Error`, declaring scope only; oracle-pinned); php-src `Zend/tests/readonly_props/readonly_clone_*.phpt`, `unset_*.phpt`, `initialization_scope_*.phpt`.

## 5. Row and fixture bookkeeping

New fixture group `oracle-2026-10-17` holding F1, F2, F3 as first-hand oracle outputs (program text and PHP 8.3 output recorded in the fixture file with the run's provenance: image `php:8.3-cli`, host shiva, date), alongside the per-row programs that the P3/P4 engineer runs against the same image before landing. Rows added or revised this round: 25, 52, 53, 54, 57, 58, 60 to 68. The corner table (B.2) is unchanged: none of the four findings was a corner; all were defects in claimed rows, corrected under the round-7 blocking rule.

Slice deltas: **P3** gains rows 60, 61, 25/54 revised, 57, 63 to 65, and the pending-call dummy callee of row 53/58; **P4** gains rows 62, 66 to 68 and the readonly state pair. Owner decisions unchanged.

## What round 8's reviewer should probe

1. **`current($a)` inside a by-reference loop after a separation** (whether the internal pointer is independent of the iterator, as rows 55 and 60 state): a one-line oracle run settles it; if `current` tracks the loop, `:ptr` must be written by `FE_FETCH_RW`, which changes row 55's plain-array pointer story but not F1.
2. **Lineage expiry (row 61)**: whether Zend's bookkeeping carries a position across *several* iterations before the loop reference adopts the copy (my rule expires records at the next fetch); an oracle program that copies at iteration 1 and assigns the copy back at iteration 3 decides it.
3. **Dummy-callee argument evaluation for `new C(args)` without a constructor**: confirm Zend evaluates the arguments (the `C2` fixture) rather than skipping them; the handler's `ZEND_CALL_DUMMY` path says it does.
4. **`unset` twice in `__clone`** and the child-scope cases of rows 66 and 67, which I pinned from the 8.3 write/unset handlers but which are exactly the kind of row the blocking rule wants oracle-confirmed before landing.
5. **Named spread with string keys after integer keys** (row 65): the exact text and whether it is raised at the spread or at entry.
