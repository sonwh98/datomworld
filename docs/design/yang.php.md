# yang.php: the PHP frontend

Status: Designed 2026-10-17. Slices P1 and P2 (section 15) are implemented on
the `yang.php.antlr` route (P2: arrays, key coercion, `foreach` by value, the
core array builtins and sorting, oracle-verified against `php-8.3.35`, section
17.3); later slices are unstarted, and the legacy `yang/php.cljc` frontend is
still the installed one. Design by Fable (Lead
System Architect seat); adversarial review by codex (gpt-6.1-sol) over a
nine-round design loop that ended READY with sign-off granted. Section 20
("Design provenance") cites the loop.

Parent design: [`yang.antlr.md`](yang.antlr.md). This document makes concrete
its section 8.6 (Object-oriented and web: PHP, mixed text and ordered maps),
its section 8.11 for PHP arrays, and Phase 3. Companion:
[`yang.typescript.md`](yang.typescript.md), whose section 2 (prerequisites
binding both frontends) and section 17 (the `yang.core` lifts) this document
depends on and does not repeat. Once accepted, this document governs the
detail; `yang.antlr.md` section 8.6 remains the paradigm summary.

Request records, bound surfaces and the refusal of ambient resolution follow
the doctrine of ADR 0004 ("external effects are interpreted stream deposits"),
which is Proposed and not yet committed; `yang.typescript.md` section 2.4
names what its rejection would reopen.

## 1. Scope

A new ANTLR frontend replaces the current PHP compiler frontend (section 5).
It compiles PHP 8.3 source, including
mixed HTML text, to the Universal AST, and runs one request as one task
reading an explicit request record. The frontend id is `:yang/php` either
way — the one-frontend-per-language scheme (2026-10-18; the id was
`:yang.php/legacy` before the legacy suffix was retired) leaves no room
for implementation-suffixed ids, so the ANTLR frontend lands as a new
revision of `:yang/php`, its implementation named by the manifest's
revision and profile keys; the final naming at acceptance is the owner's.

The design claims a specified fragment, not Zend fidelity: the transition
table of section 4 and the behaviour of the slices of section 15, each pinned
by oracle fixtures against the pinned PHP CLI, with every Zend behaviour
outside it listed as a named corner (section 17). The `yang.antlr.md` Phase 3
exit criteria are direct fixtures:

1. mixed text and code produce output in statement order;
2. arrays keep insertion order and PHP's alias and copy semantics;
3. superglobals are built from an explicit request input;
4. type declarations are not silently ignored (including through references);
5. two requests share nothing.

Like every frontend: no new AST tag, no engine change, no codec change.

## 2. Prerequisites

Everything in `yang.typescript.md` section 2 binds this frontend: defects D1
to D7, the shared rulings (own null sentinel, Jing numeric kinds, JVM-only
parsing with three-host execution, the linked-profile matrix of bundled on
four evaluators and linked on three until L-b), and the ADR 0004 dependency.
PHP uses the lifted namespaces of `yang.typescript.md` section 17 (L1 to L4
land first) and adds nothing to them (section 16). Two PHP-specific
dependencies: statement-level ticks wait on safepoint slice 3 (section 12),
and optional absent includes wait on the known-gaps ledger item for
publication-time-unavailable imports (section 11.3).

## 3. The paradigm specification

### 3.1 Reference runtime

PHP 8.3 CLI at a pinned patch release (owner decision B8.1), with `php.ini`
pinning `precision=14`, `serialize_precision=-1`, `error_reporting=E_ALL` and
`display_errors=stderr`. The grammar's feature set (enums, `match`,
`readonly`, named arguments, attributes) is PHP 8.1 and later, so 8.3 is the
natural pin. PHP 8.4 features (property hooks, asymmetric visibility) are not
parsed (corner C10).

### 3.2 Arrays and keys

A PHP array is a value: assignment and parameter passing copy it, and a write
never affects another holder. Its representation is chosen by what it contains
(section 4.2): an array that transitively holds no reference, no
array-instance handle and no object handle is a **plain** persistent ordered
map stored directly in its holder, copied in O(1), with mutation as get, pure
update and `cell/set!`. Any other array is a counted **instance** cell whose
duplication follows the engine (section 4).

- **Key coercion** at every write and read: an integer stays; a
  decimal-integer string in canonical form (`"8"` yes; `"08"`, `"8.0"`, `" 8"`
  no) becomes that integer; a float truncates toward zero (with the PHP 8.1
  deprecation emitted as a diagnostic record); `true` and `false` become 1 and
  0; `null` becomes `""`; any other string stays a string.
- `next-index` is one more than the largest integer key ever used and never
  decreases on unset; negative keys follow PHP 8.3's rule (next is max + 1).
  `$a[] = v` appends at `next-index`. The one decrement is `array_pop`, which
  lowers it when the popped key was `next-index - 1`.
- Iteration follows the key order (`php/array-keys`; the prelude's array
  operations are the only code that reads `:index`, `:order` or `:next-index`).
  Unset splices the order. Nested arrays are nested
  values: `$a['x']['y'] = 1` is one path update.
- Large arrays cost one path copy per write; a packed-vector fast path is an
  attached optimizer's business, not the lowering's.

#### 3.2.1 Reads, `isset`, `empty`, `unset` and builtins (P2)

- **Reads.** A missing key warns (2) `Undefined array key "x"` or
  `Undefined array key 5` and answers null; an offset read on null, bool, int
  or float warns `Trying to access array offset on <type|true|false>`; a
  string container answers the one byte at the offset (negative counts from the
  end), out of range warns `Uninitialized string offset N` and answers `""`.
  `$a[]` in read position is a compile error `Cannot use [] for reading`.
- **`isset`/`empty` on a string container** coerce the offset, then test the
  range: int itself; `true`, `false`, null as `1`, `0`, `0` silently; a float
  by the float-key seam (`php/float-key`, precision deprecation when
  fractional, no "String offset cast" warning, which belongs to reads); an
  integer-numeric string as that int; anything else is not an offset (`isset`
  false, `empty` true).
- **`unset` on a non-array**: a missing key or null at any level is a no-op;
  a string terminal is `Error "Cannot unset string offsets"`; int, float and
  `true` are `Error "Cannot unset offset in a non-array variable"`; `false`
  emits Deprecated (8192) `Automatic conversion of false to array is
  deprecated` and stays `false`.
- **Builtin protocol.** One table (`yang.php.antlr.builtins/supported`) feeds
  the prelude registration and the lowering's by-reference positions. For a
  call with `:by-ref` positions the lowering (1) binds each by-ref location's
  keys at that argument's place in left-to-right order; (2) evaluates every
  other argument left to right into temporaries (a non-location at a by-ref
  position warns 8 `Only variables should be passed by reference`); (3)
  immediately before the call, reads each by-ref location through its bound
  keys and builds the argument vector; (4) calls; (5) writes each out back
  through the same keys, in position order.
- **`foreach` by value** iterates a snapshot (`php/foreach-array`, then
  `php/array-keys` and `php/array-get`), so writes, `unset`, appends and
  `sort` of the source in the body never change the iteration (the by-value
  half of C15). A non-array warns and iterates nothing.
- **Sorting** is one stable merge sort in the prelude (no host sort), with
  flags `SORT_REGULAR`, `SORT_NUMERIC`, `SORT_STRING`; `r*` variants flip the
  sign without reversing equal elements. A **bool** comparator is retried
  swapped and emits Deprecated 8192 `usort(): Returning bool from comparison
  function is deprecated, return an integer less than, equal to, or greater
  than zero` once per call. Parity claim: `usort` with a bool comparator
  `return $a > $b;` over `[3, 1, 2]` matches the CLI's order and emits the
  deprecation exactly once per call. Stability is not parity for a comparator
  that disagrees with itself (corner G-sort-inconsistent-cmp, section 17.2).

### 3.3 Scope

- PHP has function scope only, with no block scope and no implicit closure
  capture, except arrow `fn`, which captures by value every enclosing variable
  it mentions, at creation.
- **Locals are cells** allocated at function entry with `:php/undef`; reading
  one emits `Warning: Undefined variable` and yields null. Every function body
  that declares locals or parameters releases them on exit (row 16).
- `global $x` binds the local to the `$GLOBALS` entry by reference (row 17),
  creating it as null if absent.
- `static $x = <expr>;` is a cell owned by the function object, allocated at
  function creation with an uninitialized sentinel. Its initializer is an
  arbitrary expression evaluated **when execution first reaches the
  declaration** (PHP 8.3) and filled once.
- **The dynamic-scope profile**: a function whose body contains
  `include`/`require`, `$$name`, `extract`, `compact`, `get_defined_vars`,
  `eval`, or one-argument `parse_str` keeps its locals in a heap symbol-table
  dict (the precedent is Python's module namespace, owner decision 1 of
  `yang.antlr.md` 8.5.1). Scope analysis decides per function, and the
  lowering targets `php/local-get`/`php/local-set` on the dict. All other
  functions use cells (owner decision B8.3). The symbol table holds reference
  markers like any location.
- **The global scope** is always the `$GLOBALS` dict: a heap dict,
  insertion-ordered, with the superglobals pre-seeded from the request record.
  `$GLOBALS` is read-only as of PHP 8.1 except through its elements (corner
  C13).

### 3.4 Superglobals and the request context

- A PHP run is one task consuming one **request record** from a
  composition-supplied stream: `{:php.request/method :uri :query :headers
  :cookies :body :server :env :files}`. `php/init!` builds `$_GET`, `$_POST`,
  `$_COOKIE`, `$_SERVER`, `$_ENV`, `$_FILES`, `$_REQUEST` (ordered per a
  pinned `request_order`) and, for CLI, `$argv` and `$argc`. `$_SESSION` is a
  bound surface (a session-store effect), absent by default. Nothing reads
  ambient process or HTTP state.
- **Output** (`echo`, `print`, inline HTML, `printf`) appends to an output
  cell with the output-buffering stack; `ob_start` and `ob_get_clean` are pure
  prelude over that stack. The top level flushes ordered `:php.response/body`
  records on the response stream. `header()`, `http_response_code` and
  `setcookie` produce `:php.response/header` records, refused after the first
  body flush with PHP's warning text.
- `exit` and `die` are an escape to the task root, followed by the shutdown
  sequence (row 22).
- Cross-request leakage is impossible by construction: one task per request
  and no store writes for guest state.

### 3.5 Coercion, comparison and arithmetic

- **Loose equality** is the PHP 8 comparison table, pinned as a fixture matrix
  over every pair of `null`, `true`, `false`, `0`, `1`, `-1`, `"0"`, `""`,
  `"1"`, `"01"`, `"1e1"`, `"10"`, `"abc"`, `" 1"`, `"1 "`, `[]`, `[0]`, `0.0`,
  `NAN`, `INF`, an object, and a `Stringable`. For example `0 == ""`, `0 ==
  []` and `"" == []` are false in PHP 8, while `0 == "0"`, `"" == null`, `[]
  == false`, `"1" == "01"`, `"10" == "1e1"` and `100 == "1e2"` are true.
  Number versus numeric string compares numerically; number versus non-numeric
  string compares as strings, so `0 == "a"` is false. Arrays compare by key
  and value; objects by property, or by identity for `===`.
- `<=>` uses the same ordering; `===` requires identical type and value,
  arrays element-wise, objects the same handle.
- **Numeric strings** (`is_numeric`) allow leading and trailing whitespace
  (8.0) and exponent forms. Leading-numeric strings in arithmetic warn (`"5
  apples" + 1` warns and gives 6); non-numeric ones throw `TypeError`. String
  increment is supported (`"a"++` is `"b"`, `"Az"++` is `"Ba"`).
- **Integer overflow converts to float**: every integer operator goes through
  the exact integer module and demotes to float64 when the exact result leaves
  `[-2^63, 2^63-1]` (`PHP_INT_MAX + 1` is a float). This is the one place C3's
  promotion-before-overflow is used to detect rather than to promote.
- `/` yields an int only when exact, else a float. `%` is int-only:
  `PHP_INT_MIN % -1` is `0`, and `% 0` throws `DivisionByZeroError`.
  `intdiv(PHP_INT_MIN, -1)` throws `ArithmeticError`, and `intdiv(x, 0)`
  throws `DivisionByZeroError`. `fmod` never throws. `**` is an int when exact
  and in range, else a float, via the pinned `fpow`.
- **Float to string**: `echo` uses `precision=14` (`%.14G`, so `echo 0.1+0.2`
  prints `0.3`); `var_dump`, `var_export` and `json_encode` use
  `serialize_precision=-1` (the shortest round trip, `integer/float-digits`).
  Fourteen-significant-digit rounding needs exact decimal rounding of a
  binary64: a prelude function over `integer`'s exact decomposition, with no
  new kernel (an `integer` version bump only if measured too slow).

### 3.6 Null

`:php/null` is the only null. `??` and `?->` test it; `isset` is "bound and
not null"; `empty` is falsiness; `unset` unbinds.

### 3.7 Classes, interfaces, traits and enums

- A class is a cell `{:php/type :class :name :parent :interfaces #{}
  :constants :static-props (cells) :props-decl :methods {name {:code
  :visibility :static? :abstract? :final? :declaring-class}} :abstract?
  :final? :readonly?}`. Instances are object cells (section 10.1).
- **Traits** are copied into the class descriptor at declaration time (class
  methods over trait methods over inherited ones; `insteadof`/`as` resolution;
  abstract and static trait members; trait properties merged with the
  compatibility check), which is PHP's own copy semantics and needs no runtime
  trait object.
- **Interfaces** are dispatch contracts: `instanceof` walks `:parent` and
  `:interfaces` transitively; interfaces carry constants.
- **Visibility** is checked at run time against the calling class context (a
  `:class-ctx` the lowering threads into methods), as PHP does; `protected`
  uses the inheritance relation.
- **Magic methods**: `__construct`; `__destruct` for acyclic garbage (section
  4, rows 48 to 51; cyclic garbage is corner C1); `__get`, `__set`, `__isset`,
  `__unset`; `__call`, `__callStatic`; `__toString` (`Stringable`);
  `__invoke`; `__clone` (row 52). `__serialize` is deferred (corner C18).
- **Declarations are enforced**: typed properties, parameter types, nullable
  and union types, and return types are checked at call or write time under
  the coercion rules, with `TypeError` under `declare(strict_types=1)` (a
  per-unit lowering option, pinned in the packet, default 0: coercive).
  Readonly properties follow rows 66 to 68.
- **Enums** (`enum X: string { case A = 'a'; }`) are singleton object cells
  created at class evaluation, with `cases()`, `from`, `tryFrom` and interface
  implementation. `match` uses strict comparison and throws
  `UnhandledMatchError`.
- First-class callable syntax `f(...)`; named arguments (rows 57, 63 to 65);
  variadics and defaults; `static`, `self`, `parent` and late static binding;
  closures as `Closure` objects with `bind`, `bindTo`, `call` and
  `fromCallable`; callables as strings and `[obj, 'm']` arrays resolved
  through the function and class tables. Attributes are parsed and stored as
  data on the descriptor (for `ReflectionAttribute` later), with no runtime
  effect.

### 3.8 Functions and the tables

The function table and the class table are heap dicts in `php.rt/*`, populated
as declarations execute: top-level unconditional declarations are hoisted to
the start of the unit as PHP does, and conditional ones execute in place. A
call to an undefined function raises `Error("Call to undefined function f()")`
at run time, before any argument runs (row 53). In addition the lowering emits
a warning-severity diagnostic `:yang.php/unknown-builtin` for a name in the
pinned PHP builtin catalogue (a checked-in list) that the prelude does not
supply, so unsupported stdlib fails honestly before the program runs (law 12).

### 3.9 Exceptions, warnings and `@`

- `Throwable` with the `Exception` and `Error` hierarchies (`TypeError`,
  `ValueError`, `ArgumentCountError`, `ArithmeticError`,
  `DivisionByZeroError`, `UnhandledMatchError`, `JsonException`, the SPL
  exceptions); `try`/`catch` (multi-type `|`, optional variable)/`finally`;
  `throw` as an expression (8.0).
- **Warnings, notices and deprecations are not exceptions**: they are
  `:php.diag/warning` records appended to a guest-diagnostics stream the
  composition supplies (deterministic, since they derive from program state;
  owner decision B8.4), observable by `set_error_handler`, a guest callable in
  a cell invoked at the emission point by prelude code. `ErrorException`
  conversion is then user code.
- **`@`** lowers the expression's diagnostics with `error_reporting()`
  temporarily reduced to the PHP 8 mask (`E_ERROR | E_PARSE | E_CORE_ERROR |
  E_COMPILE_ERROR | E_USER_ERROR | E_RECOVERABLE_ERROR`). A handler installed
  by `set_error_handler` is **still invoked** and observes the reduced value;
  only the default output is silenced.
- `E_ERROR` fatals (undefined class, abstract instantiation) are `Error`
  throws. The uncaught-exception text is pinned to PHP's `PHP Fatal error:
  Uncaught ...` format without the file path.

### 3.10 Generators and iteration protocols

`yield`, `yield k => v`, `send`, `throw`, `getReturn` and `yield from` map
onto the landed C2 core (switch, yield-raw, exit, flag cells, a per-generator
handler stack); `Generator` implements `Iterator`. `foreach` drives arrays and
`Iterator`/`IteratorAggregate` objects; `ArrayAccess` and `Countable` are
supported. Destroying a suspended generator releases its CVs but does not run
its pending `finally` blocks (corner C8, closed by P4-c).

### 3.11 Mixed HTML mode

`inlineHtml` tokens lower to `php/echo` of the literal text in source order
between code blocks. `?>` swallows one following newline (the lexer's rule,
preserved through the span); the first `<?php` has no output before it unless
there is text. Output order is statement order: exit criterion 1 is a direct
fixture.

### 3.12 eval

`eval` is a new compilation request (`yang.antlr.md` 8.6) sharing the caller's
scope, so it forces the dynamic-scope profile on its function. It is refused
with `:yang.php/unsupported :eval` until its slice (P9).

## 4. The reference, array and object lifecycle

PHP's observable aliasing (references, copy-on-write separation that unwraps
unshared references, destructor timing) is defined by the Zend engine's
operations, so this model is derived from them, not from an abstraction. Each
row names the Zend handler it maps to; each row is normative and is confirmed
against the pinned PHP CLI before the slice that claims it lands (the blocking
rule, section 17.3).

### 4.1 What the engine does

- A **zval** is a typed slot in a CV, a hashtable bucket, a property table or
  a temporary. Refcounted kinds carry a header: strings, arrays
  (`zend_array`), objects, resources and references (`zend_reference
  {refcount, val}`).
- **Copying a zval** (`$b = $a`, `SEND_VAR`, `RETURN`, the `FE_RESET_R`
  temporary, array-literal elements) copies the slot and `GC_ADDREF`s the
  pointee: for an array, the **hashtable**, never its elements. Literal arrays
  are immutable and uncounted; the first write duplicates.
- **Writing into an array** goes through a write-address fetch (`FETCH_DIM_W`,
  `FETCH_OBJ_W`, `FETCH_DIM_RW`): the container is separated
  (`SEPARATE_ARRAY`, `zend_array_dup`) when its refcount is above 1, a missing
  key is auto-vivified, and the element's address is returned. `$a[k] = v`,
  `$r =& $a[k]`, a by-reference `f($a[k])`, `foreach ($a as &$v)` and `$a[] =
  v` all use this path, separating each level as reached.
- **`zend_array_dup`** copies buckets; a reference with refcount 1 whose value
  is not the array being duplicated is **unwrapped** in the copy (the copy
  holds the value, `ADDREF`ed); any other refcounted element is `ADDREF`ed;
  the source is unchanged; registered iterator positions are duplicated onto
  the copy.
- **`ASSIGN`** dereferences a reference destination, acquires the new value,
  stores it, then destroys the old one. **`ASSIGN_REF`** fetches the source
  for write, does nothing when source and destination are the same zval, wraps
  a non-reference source in place, `ADDREF`s the reference, stores it, then
  destroys the old destination content: acquisition precedes release.
- **`UNSET_CV`** `DELREF`s whatever the CV holds; unset fetches separate but
  never vivify. **Function exit** releases every CV, on normal return and on
  unwinding.
- **Objects** are handles: property writes do not separate; the object is
  refcounted and finalized at zero; cycles are reclaimed later by the cycle
  collector.

### 4.2 The substrate mapping

Every Zend refcounted kind whose count is **observable** becomes a heap cell
with an explicit `:rc`; kinds whose count is unobservable stay plain values.

+-------------------------+-------------------------+--------------------------+
| Zend kind               | Substrate               | Why                      |
+=========================+=========================+==========================+
| string                  | plain value             | immutable semantics;     |
|                         |                         | separation never         |
|                         |                         | observable               |
+-------------------------+-------------------------+--------------------------+
| array transitively      | plain persistent        | duplicating such a table |
| holding no reference,   | ordered map, stored     | is observable only       |
| instance handle or      | directly in its holder  | through unwrapping, and  |
| object handle           |                         | there is nothing inside  |
|                         |                         | to unwrap or release;    |
|                         |                         | copy-on-write is free    |
+-------------------------+-------------------------+--------------------------+
| any other array         | instance cell           | its duplication and      |
|                         | `{:php/type :arr :rc n  | destruction perform      |
|                         | :value <ordered map>    | observable `ADDREF`,     |
|                         | :deps #{keys}           | `DELREF` and unwrapping  |
|                         | :iterators #{it} :ptr   | on elements              |
|                         | i}`, held through       |                          |
|                         | `{:php/arr h}`          |                          |
+-------------------------+-------------------------+--------------------------+
| reference               | cell `{:php/type :ref   | exactly                  |
|                         | :rc n :value x :sources | `zend_reference`;        |
|                         | #{[o p]}}`, held        | `:sources` are the       |
|                         | through a marker        | typed-property type      |
|                         | `{:php/ref c}`          | sources (rows 41 to 47)  |
+-------------------------+-------------------------+--------------------------+
| object                  | cell `{:php/type :obj   | destruction releases     |
|                         | :rc n :class C :props p | properties and runs      |
|                         | :ord n :destructed? b}` | `__destruct`, which is   |
|                         |                         | observable               |
+-------------------------+-------------------------+--------------------------+
| iterator (by-reference  | cell `{:php/type :iter  | Zend's hash iterators    |
| `foreach`)              | :current h :tables {h   | and their copied         |
|                         | pos ...}}`; a position  | positions                |
|                         | is the next bucket to   |                          |
|                         | fetch                   |                          |
+-------------------------+-------------------------+--------------------------+
| activation temporaries  | a temp list cell        | Zend's live temporaries, |
|                         | `php.rt/tmps` per       | freed by `FREE` and on   |
|                         | activation, holding     | unwinding                |
|                         | counted owned           |                          |
|                         | temporaries and pending |                          |
|                         | calls                   |                          |
+-------------------------+-------------------------+--------------------------+

- `:rc` counts slots holding the handle or marker: variables, property slots,
  instance-array slots, iterator temporaries, argument slots, pending calls.
- A plain array is **upgraded** to an instance (rc 1, its parent slot takes
  the handle, the parent's `:deps` gains the key, recursively up the write
  path) the moment a marker, an instance handle or an object handle is stored
  into it. This is the only conversion, and it happens inside the
  write-address fetch; every store of a counted thing goes through `STORE`, so
  the invariant holds by construction.
- `:deps` is the set of keys whose slots hold counted content (usually small).
  `:ptr` is the internal pointer (section 4.4.3).
- Object state additionally tracks, per property, `initialized?` and the
  clone-time `reinitable` flag (rows 52, 66 to 68). `php.rt/finalizable` is an
  **uncounted** ordered registry of objects whose class defines `__destruct`
  and which have not been destructed; holding a cell ref there adds nothing to
  `:rc`.

### 4.3 Primitive procedures

One per Zend routine. Order inside `ASSIGN` and `ASSIGN_REF` is the engine's:
acquire the new content, store, then destroy the old. `MAKE_REF` on a slot
that already holds a marker reuses it, so markers never nest. `DUP` never
changes the source.

```text
ADDREF(x)   x a handle, marker or object: rc += 1; otherwise nothing
DELREF(x)   x a handle, marker or object: rc -= 1; at 0 DESTROY(x)

DESTROY(arr h)  for k in deps(h): DELREF(slot k); h is garbage
DESTROY(ref c)  DELREF(value(c)); c is garbage
DESTROY(obj o)  finalize, resurrect or FREE (section 4.4.6)

DUP(h)      h' := {rc 1, value(h), deps(h), ptr(h)}
            for k in deps(h), x := slot k:
              marker c, rc(c) = 1, value(c) not the handle h:
                slot k of h' := value(c); ADDREF(value(c));
                deps(h') keeps k only if that value is counted
              otherwise: ADDREF(x)
            for each iterator registered on h: row 60

SEPARATE(loc)   loc holds handle h, rc(h) > 1:
                  DELREF(h) (cannot reach 0); loc := DUP(h)
                loc holds a plain array: nothing (implicit COW)

FETCH_W(path)   from the variable or property, at each step:
                  marker       continue inside value(c)
                  handle       SEPARATE here; continue inside
                  plain array  continue (persistent update back up)
                  missing key  vivify null ([] if a dim follows)
                  scalar or null in dim position: per the oracle
                returns the terminal slot; every instance on the
                chain is now exclusively owned

FETCH_UNSET(path)  as FETCH_W, but never vivifies:
                  missing key or null  stop: the rest is a no-op
                  string     Error "Cannot unset string offsets"
                  int, float, bool
                             Error "Cannot unset offset in a
                             non-array variable"
                  object     ArrayAccess::offsetUnset, else Error
                             "Cannot use object of type X as array"

STORE(slot, v)  v counted and the container plain: upgrade the
                container chain to instances (4.2)
                slot := v; deps += k when v is counted
MAKE_REF(slot)  slot holds marker c: c
                else c := {rc 1, value <slot content moved>};
                slot := marker c; deps += k
ASSIGN(loc, v)  target := loc; target holds marker c: target := c
                garbage := content(target); ADDREF(v);
                STORE(target, v); DELREF(garbage)
ASSIGN_REF(dst, src)
                s := FETCH_W(src); d := FETCH_W(dst)
                if s is d: return
                c := MAKE_REF(s); ADDREF(c);
                garbage := content(d); d := marker c; DELREF(garbage)
RELEASE_CVS(frame)
                for every local and parameter slot:
                DELREF(content); undef
UNSET_DIM(container, k)
                delete the bucket if present; DELREF its content;
                deps -= k; iterators updated (row 26)
UNSET_OBJ(o, p) DELREF the property's content; a typed property
                becomes uninitialized (row 44)
```

### 4.4 The transition table

Rows are PHP constructs with the Zend handler they map to. Row numbers are
stable identifiers from the design loop and are cited by fixtures, so they are
not renumbered: rows 9, 12 and 20 were retired (replaced by rows 30, 24 to 27,
and 48 to 51), and 23 was never assigned. Where a row distinguishes the
content kind of the slot it acts on, the kinds are S (scalar, string or null),
P (plain array), H (instance handle), O (object handle) and R (reference
marker).

#### 4.4.1 Copy, assignment and references

+----+------------------------------------+------------------------------------+
| #  | Construct (handler)                | Rule                               |
+====+====================================+====================================+
| 1  | `$y = $x` (`ASSIGN` with a CV      | S: copy. P: share in O(1). H:      |
|    | source; also `SEND_VAR`, `RETURN`, | `ASSIGN(y, h)`, rc(h) + 1, `$y`'s  |
|    | an array-literal element, the      | old content `DELREF`ed. O: the     |
|    | `FE_RESET_R` temporary)            | same with o. R: the source is      |
|    |                                    | dereferenced first, then the row   |
|    |                                    | of `value(c)` applies. A borrowed  |
|    |                                    | source `ADDREF`s; an owned         |
|    |                                    | temporary moves (rows 32 to 38)    |
+----+------------------------------------+------------------------------------+
| 2  | `$x = v` where `$x` holds content  | S, P: replace (an old plain array  |
|    | (`ASSIGN`)                         | releases nothing). H: `DELREF(h)`  |
|    |                                    | after the store (`DESTROY` at 0    |
|    |                                    | releases its slots). O:            |
|    |                                    | `DELREF(o)`. R: write through the  |
|    |                                    | reference: `value(c)` replaced,    |
|    |                                    | the old value `DELREF`ed, `c`      |
|    |                                    | retained                           |
+----+------------------------------------+------------------------------------+
| 3  | `$x[k...] = v` (`FETCH_DIM_W`,     | S: per the oracle (string-offset   |
|    | then `ASSIGN`)                     | write or `Error`; corner C5). P:   |
|    |                                    | persistent update; upgrade the     |
|    |                                    | chain if `v` is counted. H:        |
|    |                                    | `SEPARATE` at every instance level |
|    |                                    | with rc > 1, then `ASSIGN` at the  |
|    |                                    | terminal slot. R: every marker on  |
|    |                                    | the path dereferenced; a terminal  |
|    |                                    | marker is written through          |
+----+------------------------------------+------------------------------------+
| 4  | `$y =& $x`, variable source        | S, P, H, O: wrap `$x` in a new     |
|    | (`ASSIGN_REF`)                     | reference (rc 1), then rc + 1;     |
|    |                                    | `$y`'s old content released after. |
|    |                                    | R: reuse `c`, rc(c) + 1; `$y`'s    |
|    |                                    | old content released after         |
+----+------------------------------------+------------------------------------+
| 5  | `$y =& $x[k...]`, `[&$x[k]]`,      | S: vivify, then row 4. P: the      |
|    | by-reference `f($x[k])`, a         | container chain is separated and   |
|    | by-reference `foreach` element     | upgraded, the slot wrapped, then   |
|    | (`FETCH_DIM_W`, `MAKE_REF`,        | row 4. H: the container separated  |
|    | `ASSIGN_REF`)                      | (rc > 1), the slot wrapped. R: the |
|    |                                    | marker reused                      |
+----+------------------------------------+------------------------------------+
| 6  | `$x =& $x`, or the same slot on    | no-op: the identity check precedes |
|    | both sides                         | any release                        |
+----+------------------------------------+------------------------------------+
| 7  | `$y =& $x` with the source inside  | acquisition first: the source is   |
|    | `$y`'s old content (`$a =& $a[0]`) | separated and wrapped, rc + 1;     |
|    |                                    | then `$a`'s old array is           |
|    |                                    | `DELREF`ed, destroyed at 0, which  |
|    |                                    | `DELREF`s its slot marker (rc back |
|    |                                    | to 1, held by `$a`)                |
+----+------------------------------------+------------------------------------+
| 8  | `unset($x)` (`UNSET_CV`)           | S, P: undef. H: `DELREF(h)`. O:    |
|    |                                    | `DELREF(o)`. R: `DELREF(c)` (its   |
|    |                                    | value destroyed at 0)              |
+----+------------------------------------+------------------------------------+
| 10 | read `$x[k...]` (`FETCH_DIM_R`)    | the value; markers dereferenced;   |
|    |                                    | no count changes                   |
+----+------------------------------------+------------------------------------+
| 17 | `global $x`, `static $x`           | row 4 with the symbol-table or     |
|    | (`BIND_GLOBAL`, `BIND_STATIC`)     | static-table slot as the source    |
|    |                                    | (vivified null); a `static`        |
|    |                                    | initializer runs when execution    |
|    |                                    | first reaches it (section 3.3)     |
+----+------------------------------------+------------------------------------+
| 18 | `$a[0] =& $a` (`ASSIGN_REF`, self) | row 5 with row 7's ordering; `DUP` |
|    |                                    | keeps a marker whose value is the  |
|    |                                    | table being duplicated (the        |
|    |                                    | self-reference exception)          |
+----+------------------------------------+------------------------------------+
| 19 | `$o->p = v`, `$o->p =& x`,         | the property slot as a location:   |
|    | `unset($o->p)` (`ASSIGN_OBJ`,      | rows 2, 4 and 31; no separation    |
|    | `ASSIGN_OBJ_REF`, `UNSET_OBJ`)     | (objects are handles); typed       |
|    |                                    | properties add rows 41 to 47;      |
|    |                                    | `clone` is row 52                  |
+----+------------------------------------+------------------------------------+
| 21 | builtins taking arrays (`array_*`, | arguments by rows 13 and 33 to 35; |
|    | `count`, ...)                      | results built by `STORE`, so a     |
|    |                                    | returned array holding counted     |
|    |                                    | things is an instance; the         |
|    |                                    | reference-preserving functions the |
|    |                                    | engine documents follow the        |
|    |                                    | oracle; each supported builtin     |
|    |                                    | declares its by-reference          |
|    |                                    | positions in the prelude table     |
|    |                                    | (corner C6)                        |
+----+------------------------------------+------------------------------------+

#### 4.4.2 Calls, arguments and temporaries

Zend distinguishes **borrowed** operands, which a copy `ADDREF`s, from **owned
temporaries** (`TMP_VAR`), which transfer by move and are released by `FREE`
when discarded or by live-temporary cleanup when an exception abandons a
frame. The lowering classifies each expression syntactically. Borrowed:
variable, property, dimension and static-property reads, constants, literal
scalars. Owned: a call result, `new`, `clone`, an array literal with any
non-literal element, `(array)` and `(object)` casts, a `yield` result, closure
creation, the value of an assignment expression (borrowed, then `ADDREF`ed),
and `match` or ternary results when the chosen arm is owned (registered at the
arm). String interpolation produces plain uncounted values.

A **pending call** is an owned record on the activation's temp list, created
when the call expression begins and consumed when the callee is entered.

+----+------------------------------------+------------------------------------+
| #  | Construct (handler)                | Rule                               |
+====+====================================+====================================+
| 13 | by-value parameter                 | row 33 or 34 at evaluation, moved  |
|    | (`SEND_VAR`/`SEND_VAL`, `RECV`)    | into the callee's slot at entry    |
|    |                                    | (row 58)                           |
+----+------------------------------------+------------------------------------+
| 14 | by-reference parameter             | row 35 at evaluation               |
|    | (`SEND_REF`, `RECV`)               |                                    |
+----+------------------------------------+------------------------------------+
| 15 | `return` (`RETURN`)                | row 36                             |
+----+------------------------------------+------------------------------------+
| 16 | function exit, normal or unwinding | `RELEASE_CVS`: the lowering wraps  |
|    | (`zend_free_compiled_variables`)   | every body that declares locals or |
|    |                                    | parameters in `php/try-finally`    |
|    |                                    | with `php/release-cvs`             |
+----+------------------------------------+------------------------------------+
| 32 | owned temporary produced           | `php/tmp! v`: a counted `v` is     |
|    |                                    | pushed on the activation's temp    |
|    |                                    | list and returned; uncounted       |
|    |                                    | values are not registered (the     |
|    |                                    | producing operation already holds  |
|    |                                    | the count)                         |
+----+------------------------------------+------------------------------------+
| 33 | argument from a borrowed operand   | immediately `ADDREF` and append to |
|    | (`SEND_VAR`)                       | the pending call's `:args`, before |
|    |                                    | the next argument expression runs  |
+----+------------------------------------+------------------------------------+
| 34 | argument from an owned temporary   | immediately move: unregister from  |
|    | (`SEND_VAL`)                       | the temp list and append to        |
|    |                                    | `:args`                            |
+----+------------------------------------+------------------------------------+
| 35 | by-reference argument (`SEND_REF`) | immediately rows 4 or 5 (write     |
|    |                                    | fetch, `MAKE_REF`, `ADDREF`) into  |
|    |                                    | `:args`; a non-variable operand    |
|    |                                    | for a by-reference parameter is    |
|    |                                    | `Notice: Only variables should be  |
|    |                                    | passed by reference` and a         |
|    |                                    | by-value send, decided now (the    |
|    |                                    | oracle pins the prefer-ref         |
|    |                                    | builtins)                          |
+----+------------------------------------+------------------------------------+
| 36 | return into the caller (`RETURN`)  | borrowed: `ADDREF`; owned: move;   |
|    |                                    | the value becomes the caller's     |
|    |                                    | owned temporary (`php/tmp!` at the |
|    |                                    | call site); then row 16 releases   |
|    |                                    | the callee's CVs (the moved        |
|    |                                    | parameters balance) and every      |
|    |                                    | temporary still on the callee's    |
|    |                                    | list                               |
+----+------------------------------------+------------------------------------+
| 37 | expression statement with a        | at statement end `php/release-tmps |
|    | discarded result (`FREE`)          | mark` releases every temporary     |
|    |                                    | registered since the statement's   |
|    |                                    | mark, in registration order; a     |
|    |                                    | discarded `new Foo()` is           |
|    |                                    | destroyed, and its destructor      |
|    |                                    | runs, before the next statement    |
+----+------------------------------------+------------------------------------+
| 38 | temporary consumed by `STORE` or   | owned: move (unregister, no        |
|    | `ASSIGN`                           | `ADDREF`); borrowed: `ADDREF`      |
+----+------------------------------------+------------------------------------+
| 39 | exception or escape leaving an     | the activation's `php/try-finally` |
|    | activation (`cleanup_live_vars`,   | runs `RELEASE_CVS` and releases    |
|    | `zend_vm_stack_free_args`)         | the whole temp list; a pending     |
|    |                                    | call releases `:this` and every    |
|    |                                    | `:args` entry in order and is      |
|    |                                    | dropped; nested pending calls (an  |
|    |                                    | argument that is itself a call)    |
|    |                                    | are released innermost first       |
+----+------------------------------------+------------------------------------+
| 40 | `php/release-tmps` within a        | the mark is per statement; a       |
|    | statement that spans a call        | temporary consumed by row 34 or 38 |
|    |                                    | is gone before the mark is         |
|    |                                    | released; one neither consumed nor |
|    |                                    | discarded (an owned comparison     |
|    |                                    | operand, a `??` left side) is      |
|    |                                    | released at statement end          |
+----+------------------------------------+------------------------------------+
| 53 | a call expression begins           | the callee is resolved first: an   |
|    | (`INIT_FCALL`,                     | undefined function, an undefined   |
|    | `INIT_FCALL_BY_NAME`,              | method after                       |
|    | `INIT_METHOD_CALL`,                | `__call`/`__callStatic` lookup, or |
|    | `INIT_STATIC_METHOD_CALL`, `NEW`)  | a non-callable value raises its    |
|    |                                    | `Error` before any argument runs.  |
|    |                                    | A method call evaluates its        |
|    |                                    | receiver now and `ADDREF`s it into |
|    |                                    | the pending call's `$this`. The    |
|    |                                    | pending call `{:callee :this :args |
|    |                                    | []}` is pushed on the temp list.   |
|    |                                    | For `new C(args)`: (1) resolve `C` |
|    |                                    | (`Error` if not found, no argument |
|    |                                    | evaluated); (2) refuse abstract    |
|    |                                    | classes, interfaces, traits and    |
|    |                                    | enums (`Error`, no argument        |
|    |                                    | evaluated); (3) create the object  |
|    |                                    | (rc 1, held by the pending call as |
|    |                                    | `:this`, not destructed,           |
|    |                                    | constructor not run); (4) look up  |
|    |                                    | the constructor. Present and       |
|    |                                    | accessible: the pending call       |
|    |                                    | targets it. Present but            |
|    |                                    | inaccessible from the calling      |
|    |                                    | scope: `Error: Call to             |
|    |                                    | <visibility> C::__construct() from |
|    |                                    | <scope>`, and the object is        |
|    |                                    | released without `__destruct`      |
|    |                                    | (ctor-failed). **Absent: not an    |
|    |                                    | error**; the pending call targets  |
|    |                                    | a dummy callee whose arguments are |
|    |                                    | still evaluated and acquired in    |
|    |                                    | order (rows 33 to 35, 57) and      |
|    |                                    | released at entry (row 58). `new   |
|    |                                    | C` without parentheses is zero     |
|    |                                    | arguments                          |
+----+------------------------------------+------------------------------------+
| 56 | spread argument `f(...$xs)`        | the iterable is evaluated and each |
|    | (`SEND_UNPACK`)                    | element appended as a borrowed     |
|    |                                    | send (`ADDREF` each); by-reference |
|    |                                    | parameters bind to the array's     |
|    |                                    | elements through row 5 when the    |
|    |                                    | operand is a variable array;       |
|    |                                    | string keys follow row 65          |
+----+------------------------------------+------------------------------------+
| 57 | named argument `name: expr`        | (1) `CHECK_FUNC_ARG` looks `name`  |
|    | (`CHECK_FUNC_ARG`,                 | up only to choose a read or a      |
|    | `SEND_VAL_EX`/`SEND_VAR_EX`,       | write fetch for `expr`; an unknown |
|    | `zend_handle_named_arg`)           | name is not an error here, and     |
|    |                                    | `expr` is fetched for read. (2)    |
|    |                                    | Evaluate `expr`; its side effects  |
|    |                                    | happen. (3) At the send, validate: |
|    |                                    | unknown and the callee not         |
|    |                                    | variadic gives `Error: Unknown     |
|    |                                    | named parameter $name`; already    |
|    |                                    | supplied (positionally or by an    |
|    |                                    | earlier name) gives `Error: Named  |
|    |                                    | parameter $name overwrites         |
|    |                                    | previous argument`; on either, the |
|    |                                    | just-evaluated value is released   |
|    |                                    | and pending-call cleanup (row 39)  |
|    |                                    | releases the arguments sent so     |
|    |                                    | far, and later arguments never     |
|    |                                    | run. (4) Otherwise acquire and     |
|    |                                    | place by name, with gaps marked    |
|    |                                    | undefined until entry; an unknown  |
|    |                                    | name on a variadic callee is       |
|    |                                    | collected under its string key     |
+----+------------------------------------+------------------------------------+
| 58 | call entry (`DO_FCALL`)            | the pending call is unregistered;  |
|    |                                    | `:args` move into the callee's     |
|    |                                    | parameter slots (defaults by row   |
|    |                                    | 63, variadics gathered into a      |
|    |                                    | fresh array by move); `:this`      |
|    |                                    | moves into the callee's `$this`;   |
|    |                                    | the callee's temp list and CVs     |
|    |                                    | belong to its activation. For a    |
|    |                                    | dummy callee (row 53) the `:args`  |
|    |                                    | are released in order and the      |
|    |                                    | result of `new` is the object, an  |
|    |                                    | owned temporary of the caller      |
+----+------------------------------------+------------------------------------+
| 62 | an argument throws during `new`    | pending-call cleanup (row 39)      |
|    | (`zend_object_store_ctor_failed`)  | releases the half-built object     |
|    |                                    | with the ctor-failed mark, so      |
|    |                                    | `__destruct` is not called;        |
|    |                                    | properties with defaults are       |
|    |                                    | released                           |
+----+------------------------------------+------------------------------------+
| 63 | entry with named arguments         | undefined slots receive the        |
|    | (`DO_FCALL` with                   | parameter's default, evaluated in  |
|    | `ZEND_CALL_MAY_HAVE_UNDEF`)        | the callee; a required parameter   |
|    |                                    | left undefined is                  |
|    |                                    | `ArgumentCountError: f(): Argument |
|    |                                    | #n ($p) not passed`; the variadic  |
|    |                                    | array holds the trailing           |
|    |                                    | positionals, then the string-keyed |
|    |                                    | unknown named arguments in send    |
|    |                                    | order                              |
+----+------------------------------------+------------------------------------+
| 64 | positional argument after a named  | compile-time `Error: Cannot use    |
|    | one                                | positional argument after named    |
|    |                                    | argument`, a                       |
|    |                                    | `:yang.php/compile-error`          |
|    |                                    | diagnostic                         |
+----+------------------------------------+------------------------------------+
| 65 | spread with string keys,           | each string key is a named         |
|    | `f(...['x' => 1])`                 | argument under row 57 step 3 at    |
|    |                                    | the spread's send; integer keys    |
|    |                                    | are positional; a positional after |
|    |                                    | string keys is the run-time        |
|    |                                    | `Error: Cannot use positional      |
|    |                                    | argument after named argument`     |
+----+------------------------------------+------------------------------------+

Cost: one vector push per counted owned temporary and one release per
statement that produced any; scalars, strings and plain arrays never touch the
list.

#### 4.4.3 Iteration

An instance carries `:iterators` (the iterator cells registered on it) and
`:ptr`, its internal pointer: an index into its order vector, default 0,
adjusted on deletes like iterator positions. An iterator holds per-table
records, `{:current h :tables {h pos, h' pos ...}}`, where a position is the
next bucket to fetch.

+----+------------------------------------+------------------------------------+
| #  | Construct (handler)                | Rule                               |
+====+====================================+====================================+
| 11 | `foreach ($x as $v)`               | row 28                             |
+----+------------------------------------+------------------------------------+
| 24 | by-reference `foreach` start       | `$x`'s location: `MAKE_REF` (rc 2: |
|    | (`FE_RESET_RW`)                    | the variable and the iterator      |
|    |                                    | temporary); the array inside the   |
|    |                                    | reference is upgraded to an        |
|    |                                    | instance if plain, then separated  |
|    |                                    | if rc > 1, exactly once; an        |
|    |                                    | iterator is registered on that     |
|    |                                    | instance (`current` = it, position |
|    |                                    | 0); the iterator temporary is an   |
|    |                                    | owned temporary of the activation  |
+----+------------------------------------+------------------------------------+
| 25 | element fetch; handlers            | let `h_now` be the instance the    |
|    | `FE_FETCH_RW`,                     | loop reference holds (a plain      |
|    | `zend_hash_iterator_pos_ex`,       | array there is upgraded first).    |
|    | `zend_hash_iterator_find_copy_pos` | **Same table** (`h_now` is         |
|    |                                    | `it.current`): fetch the bucket at |
|    |                                    | `pos(it, h_now)` without           |
|    |                                    | separation; if the slot is not a   |
|    |                                    | marker, `MAKE_REF` in place        |
|    |                                    | (visible to every holder of that   |
|    |                                    | table); `ASSIGN_REF` into `$v`     |
|    |                                    | (acquire before releasing `$v`'s   |
|    |                                    | old marker); the position becomes  |
|    |                                    | the next existing key. **Copy      |
|    |                                    | hit** (`h_now` in `it.tables`):    |
|    |                                    | adopt it **without separation**,   |
|    |                                    | even when shared; `it.current :=   |
|    |                                    | h_now`; the records on every other |
|    |                                    | table are deleted; then fetch as   |
|    |                                    | for the same table. **Unrelated**  |
|    |                                    | (`h_now` not in `it.tables`):      |
|    |                                    | `SEPARATE` the loop reference's    |
|    |                                    | value when `rc(h_now) > 1`         |
|    |                                    | (unwrapping rc-1 references in the |
|    |                                    | copy), register on the resulting   |
|    |                                    | table at its `:ptr`, delete the    |
|    |                                    | old records, fetch                 |
+----+------------------------------------+------------------------------------+
| 26 | mutation of an instance with       | positions are per table, and a     |
|    | registered iterators               | table's own mutations adjust only  |
|    | (`zend_hash_iterators_update`,     | its records. `unset` of a key      |
|    | `zend_hash_iterators_lower_pos`)   | before the position: position - 1; |
|    |                                    | of the key at the position: it now |
|    |                                    | names the next element; appended   |
|    |                                    | elements are visited. A rebuild by |
|    |                                    | a builtin (`sort`, `array_splice`, |
|    |                                    | `shuffle`, `array_unshift`) takes  |
|    |                                    | the array by reference, separates  |
|    |                                    | when shared, and keeps each        |
|    |                                    | registered iterator at the same    |
|    |                                    | ordinal position (not the same     |
|    |                                    | key); `sort`-family builtins reset |
|    |                                    | `:ptr` to 0. Whole-array           |
|    |                                    | assignment through the loop        |
|    |                                    | reference is not this row: it is   |
|    |                                    | row 25's migration                 |
+----+------------------------------------+------------------------------------+
| 27 | loop end or `break` (`FE_FREE`)    | the iterator is unregistered and   |
|    |                                    | all its records deleted; the array |
|    |                                    | reference is `DELREF`ed; `$x`      |
|    |                                    | stays a reference (a later `$y =&  |
|    |                                    | $x` reuses it); `$v` keeps the     |
|    |                                    | last marker                        |
+----+------------------------------------+------------------------------------+
| 28 | `foreach ($x as $v)`               | iterate an owned temporary holding |
|    | (`FE_RESET_R`)                     | the array (plain share, or         |
|    |                                    | instance `ADDREF`); body writes to |
|    |                                    | `$x` separate `$x`'s copy, so the  |
|    |                                    | loop sees the original; `FE_FREE`  |
|    |                                    | releases the temporary; no         |
|    |                                    | iterator is registered             |
+----+------------------------------------+------------------------------------+
| 29 | `foreach` over an object, by value | property iteration in declaration  |
|    | or by reference                    | order, filtered by visibility from |
|    | (`FE_RESET_R`/`FE_RESET_RW` on an  | the calling scope; by reference    |
|    | object)                            | makes property slots references    |
|    |                                    | (row 19 locations); `Iterator` and |
|    |                                    | `IteratorAggregate` dispatch per   |
|    |                                    | section 3.10                       |
+----+------------------------------------+------------------------------------+
| 54 | separation performed by another    | `$b`'s write separates `$b`'s      |
|    | holder (`$b = $a; $b[0] = 9`       | copy, which receives a record for  |
|    | inside the loop)                   | the iterator (row 60); the loop    |
|    |                                    | reference still holds the          |
|    |                                    | iterator's table, so nothing       |
|    |                                    | migrates; the record stays inert   |
|    |                                    | unless the loop reference later    |
|    |                                    | adopts that table                  |
+----+------------------------------------+------------------------------------+
| 55 | internal-pointer builtins          | operate on `:ptr` of the instance  |
|    | (`current`, `key`, `next`, `prev`, | the argument holds; `next`,        |
|    | `reset`, `end`)                    | `prev`, `end` and `reset` take the |
|    |                                    | array by reference (row 35) and    |
|    |                                    | separate when shared; a plain      |
|    |                                    | array's pointer is kept in its     |
|    |                                    | holder beside the value as         |
|    |                                    | `{:php/arr-ptr i}` metadata and    |
|    |                                    | promoted into the instance on      |
|    |                                    | upgrade; `array_key_first` is pure |
+----+------------------------------------+------------------------------------+
| 60 | `DUP(h)` with iterators registered | for every iterator registered on   |
|    | on h (`zend_array_dup` iterator    | h: register it on h' with `pos(it, |
|    | duplication)                       | h') := pos(it, h)`, the            |
|    |                                    | next-bucket position at            |
|    |                                    | duplication. `it.current` stays h. |
|    |                                    | A duplication of h' carries the    |
|    |                                    | records again, so chains           |
|    |                                    | propagate. `:ptr` is copied from h |
|    |                                    | to h'                              |
+----+------------------------------------+------------------------------------+
| 61 | record lifetime                    | records are deleted only on        |
|    |                                    | migration (the non-adopted ones)   |
|    |                                    | and on `FE_FREE` or destruction of |
|    |                                    | their table; a same-table fetch    |
|    |                                    | never expires them                 |
+----+------------------------------------+------------------------------------+

#### 4.4.4 Unset

+----+------------------------------------+------------------------------------+
| #  | Construct (handler)                | Rule                               |
+====+====================================+====================================+
| 30 | `unset($x[k...])`                  | `FETCH_UNSET` to the container,    |
|    | (`FETCH_DIM_UNSET`, `UNSET_DIM`)   | then `UNSET_DIM`;                  |
|    |                                    | `unset($a['missing']['child'])` on |
|    |                                    | `[]` leaves `[]` unchanged         |
+----+------------------------------------+------------------------------------+
| 31 | `unset($o->p[k])`, `unset($o->p)`  | the property slot as a container   |
|    | (`FETCH_OBJ_UNSET`, `UNSET_OBJ`)   | (no separation: objects are        |
|    |                                    | handles); `__unset` when the       |
|    |                                    | property is inaccessible or absent |
+----+------------------------------------+------------------------------------+

#### 4.4.5 Typed properties and references

A reference bound to a typed property carries **type sources**, and every
assignment through it is verified against all of them
(`zend_verify_ref_assignable_zval`).

+----+------------------------------------+------------------------------------+
| #  | Construct (handler)                | Rule                               |
+====+====================================+====================================+
| 41 | `$r =& $o->p`, `foreach ($o->p as  | **initialized**: `MAKE_REF` on the |
|    | &$v)`, by-reference `f($o->p)`,    | slot, `sources += [o p]`.          |
|    | `[&$o->p] = ...`, with `p` typed   | **Uninitialized and nullable**     |
|    | (`zend_fetch_property_address`,    | (`?T`, `T|null`, `mixed`): the     |
|    | `BP_VAR_W`)                        | slot is initialized to `null`,     |
|    |                                    | then as initialized.               |
|    |                                    | **Uninitialized and                |
|    |                                    | non-nullable**: `Error: Cannot     |
|    |                                    | access uninitialized non-nullable  |
|    |                                    | property C::$p by reference`;      |
|    |                                    | nothing changes. **Readonly**:     |
|    |                                    | `Error` in every state (`Cannot    |
|    |                                    | modify readonly property C::$p`    |
|    |                                    | when initialized, `Cannot          |
|    |                                    | indirectly modify readonly         |
|    |                                    | property C::$p` when               |
|    |                                    | uninitialized). An unset typed     |
|    |                                    | property with `__get`: the         |
|    |                                    | indirect-modification notice and a |
|    |                                    | reference to a temporary (corner   |
|    |                                    | C4)                                |
+----+------------------------------------+------------------------------------+
| 42 | `ASSIGN` through a reference with  | before mutation, verify `v`        |
|    | non-empty `:sources`               | against each source's declared     |
|    |                                    | type under the assigning unit's    |
|    |                                    | `strict_types`; coercion (int to   |
|    |                                    | float, numeric string to int or    |
|    |                                    | float, `Stringable` to string, int |
|    |                                    | to string in coercive mode) is     |
|    |                                    | applied once, and the coerced      |
|    |                                    | value must satisfy every source,   |
|    |                                    | else `TypeError` (for example      |
|    |                                    | "Cannot assign string to reference |
|    |                                    | held by property C::$x of type     |
|    |                                    | int") naming the first failing     |
|    |                                    | source; `null` needs a nullable    |
|    |                                    | type; then row 2 proceeds with the |
|    |                                    | coerced value                      |
+----+------------------------------------+------------------------------------+
| 43 | `$r++`, `$r .= ...`, `$r += ...`   | the result is verified as in row   |
|    | through a sourced reference        | 42; an `int` property incremented  |
|    | (`PRE_INC`, `ASSIGN_OP`)           | past `PHP_INT_MAX` is `TypeError:  |
|    |                                    | Cannot increment property C::$p of |
|    |                                    | type int past its maximal value`   |
+----+------------------------------------+------------------------------------+
| 44 | source removal                     | `unset($o->p)`: `sources -= [o p]` |
|    |                                    | and the property becomes           |
|    |                                    | uninitialized (`__get` re-enabled  |
|    |                                    | for it); `$o->p =& other`: the old |
|    |                                    | reference loses the source and the |
|    |                                    | new one gains it; object free      |
|    |                                    | (section 4.4.6): every property's  |
|    |                                    | reference loses `[o p]`;           |
|    |                                    | initializing a nullable property   |
|    |                                    | to `null` under row 41 adds the    |
|    |                                    | source like any initialized        |
|    |                                    | property                           |
+----+------------------------------------+------------------------------------+
| 45 | two sources of different types on  | the acquisition is checked: the    |
|    | one reference                      | current value must satisfy the new |
|    |                                    | source, else `TypeError` at `=&`;  |
|    |                                    | thereafter row 42 requires both,   |
|    |                                    | and a coercion that satisfies one  |
|    |                                    | and not the other fails            |
+----+------------------------------------+------------------------------------+
| 46 | typed static properties            | the same sources, keyed `[class    |
|    |                                    | prop]`                             |
+----+------------------------------------+------------------------------------+
| 47 | destructuring by reference into    | row 41 per element, then row 42 at |
|    | typed properties (`[&$o->p] =      | the assignment                     |
|    | $arr`)                             |                                    |
+----+------------------------------------+------------------------------------+
| 59 | read-write fetch on an             | `Error: Typed property C::$p must  |
|    | uninitialized typed property       | not be accessed before             |
|    | (`$o->p++`, `$o->p .= ...`;        | initialization`; `$o->p[] = ...`   |
|    | `BP_VAR_RW`)                       | on an uninitialized `array`,       |
|    |                                    | `?array` or `mixed` property       |
|    |                                    | auto-vivifies (Zend's write fetch  |
|    |                                    | for a dim write), so only          |
|    |                                    | read-write operators error         |
+----+------------------------------------+------------------------------------+

#### 4.4.6 Objects: finalization, clone and readonly

Finalization is not freeing. On reaching zero, if the destructor has not run,
the object is marked destructed, pinned, its `__destruct` runs under the
exception-chaining rule, and it is unpinned; if its count is then above zero
it was **resurrected** and storage is kept; otherwise its properties are
released and it is garbage. The chaining rule is Zend's: an exception in
flight when a destructor runs is set aside; a destructor exception takes its
place with the set-aside one as `previous`; otherwise the set-aside exception
is restored.

```text
DESTROY(obj o)   [rc(o) reached 0]
  if not destructed?(o) and o's class defines __destruct:
      destructed?(o) := true; rc(o) += 1 (pin)
      remove o from finalizable
      run __destruct under the chaining rule
      rc(o) -= 1 (unpin, in a finally)
      if rc(o) > 0: return       (resurrected: storage kept)
  FREE(o): for each property slot: if a marker, drop [o p]
           from its sources; DELREF content; o is garbage
```

+----+------------------------------------+------------------------------------+
| #  | Construct (handler)                | Rule                               |
+====+====================================+====================================+
| 48 | last holder released               | `DESTROY` above                    |
+----+------------------------------------+------------------------------------+
| 49 | a destructor resurrects `$this`    | rc > 0 after unpin: kept,          |
|    | (stores it in `$GLOBALS`, a        | properties untouched, the          |
|    | static, or another live object)    | destructor never runs again; the   |
|    |                                    | later final release goes straight  |
|    |                                    | to `FREE`                          |
+----+------------------------------------+------------------------------------+
| 50 | a destructor throws                | the chaining rule; the releasing   |
|    |                                    | operation (statement-end release,  |
|    |                                    | `unset`, scope exit) completes its |
|    |                                    | remaining releases, then           |
|    |                                    | propagates                         |
+----+------------------------------------+------------------------------------+
| 51 | `exit()` inside a destructor;      | `exit` is the ordinary exit escape |
|    | destructing the pending exception  | and proceeds to shutdown;          |
|    |                                    | destructing the in-flight          |
|    |                                    | exception is `Fatal error: Attempt |
|    |                                    | to destruct pending exception`, an |
|    |                                    | `Error`-class halt with pinned     |
|    |                                    | text (corner C9)                   |
+----+------------------------------------+------------------------------------+
| 52 | `clone $o`                         | a fresh object cell (rc 1, new     |
|    | (`zend_objects_clone_members`)     | `:ord`, not destructed); each      |
|    |                                    | property copied with               |
|    |                                    | `zval_add_ref` semantics: a marker |
|    |                                    | whose reference has rc 1 is        |
|    |                                    | unwrapped into the clone (value    |
|    |                                    | copied, `ADDREF`), any other       |
|    |                                    | counted content `ADDREF`ed (shared |
|    |                                    | references stay shared; typed      |
|    |                                    | sources gain `[clone p]`). If      |
|    |                                    | `__clone` is defined it runs as a  |
|    |                                    | method call (rows 53, 58): at its  |
|    |                                    | entry every readonly property of   |
|    |                                    | the clone gets the `reinitable`    |
|    |                                    | flag, and at its exit, normal or   |
|    |                                    | by exception, every flag clears.   |
|    |                                    | Writes and unsets during it follow |
|    |                                    | rows 66 and 67                     |
+----+------------------------------------+------------------------------------+
| 66 | write to a readonly property       | **uninitialized**: initialization  |
|    | (`zend_std_write_property`,        | from the declaring scope is        |
|    | readonly branch)                   | allowed, and the `reinitable` flag |
|    |                                    | is cleared whether or not it was   |
|    |                                    | set; from another scope, `Error:   |
|    |                                    | Cannot initialize readonly         |
|    |                                    | property C::$x from scope S` (or   |
|    |                                    | `global scope`). **Initialized and |
|    |                                    | `reinitable`**: allowed, the value |
|    |                                    | replaced under the type rules, the |
|    |                                    | flag cleared. **Initialized, not   |
|    |                                    | `reinitable`**: `Error: Cannot     |
|    |                                    | modify readonly property C::$x`    |
+----+------------------------------------+------------------------------------+
| 67 | `unset` of a readonly property     | **uninitialized**: allowed from    |
|    | (`zend_std_unset_property`,        | the declaring scope (a no-op on    |
|    | readonly branch)                   | the value; a set flag is cleared). |
|    |                                    | **Initialized and `reinitable`**:  |
|    |                                    | allowed; the property becomes      |
|    |                                    | uninitialized and the flag clears. |
|    |                                    | **Initialized, not `reinitable`**: |
|    |                                    | `Error: Cannot unset readonly      |
|    |                                    | property C::$x`. From outside the  |
|    |                                    | declaring scope, in any state: the |
|    |                                    | scope `Error`                      |
+----+------------------------------------+------------------------------------+
| 68 | increments, compound operators and | `$this->x++` on an initialized     |
|    | references on readonly properties  | readonly property is a             |
|    |                                    | modification under row 66 (allowed |
|    |                                    | once with the flag); `=&` on a     |
|    |                                    | readonly property is row 41's      |
|    |                                    | `Error` in every state, flag or    |
|    |                                    | not                                |
+----+------------------------------------+------------------------------------+

#### 4.4.7 Script and request end

+----+------------------------------------+------------------------------------+
| #  | Construct (handler)                | Rule                               |
+====+====================================+====================================+
| 22 | script or request end              | (1) run                            |
|    | (`shutdown_executor`,              | `register_shutdown_function`       |
|    | `zend_call_destructors`)           | callbacks in registration order.   |
|    |                                    | (2) **Destructor phase**: repeat { |
|    |                                    | walk `$GLOBALS` in reverse         |
|    |                                    | insertion order; for each entry    |
|    |                                    | holding an object handle with rc   |
|    |                                    | 1, remove the entry and `DELREF`   |
|    |                                    | it, destroying it } until the      |
|    |                                    | entry count is stable; then call   |
|    |                                    | `__destruct` for every object      |
|    |                                    | remaining in `finalizable` in      |
|    |                                    | `:ord` order (pinned and unpinned  |
|    |                                    | as above, not freed yet); scalars, |
|    |                                    | strings and arrays in `$GLOBALS`   |
|    |                                    | are untouched here, so destructors |
|    |                                    | can read them. (3) Flush the       |
|    |                                    | output buffers to the response     |
|    |                                    | stream, nested unclosed buffers in |
|    |                                    | stack order. (4) Release           |
|    |                                    | `$GLOBALS` in reverse insertion    |
|    |                                    | order, then the static tables,     |
|    |                                    | then `FREE` every remaining object |
|    |                                    | without calling destructors. An    |
|    |                                    | uncaught `Throwable` runs this     |
|    |                                    | sequence after the error output;   |
|    |                                    | the VM's operational halts (budget |
|    |                                    | exhaustion, cancellation) skip the |
|    |                                    | destructor phase, as Zend skips it |
|    |                                    | on fatal errors                    |
+----+------------------------------------+------------------------------------+

### 4.5 Oracle facts and anchor programs

Three outputs were obtained first-hand from PHP 8.3 (image `php:8.3-cli`, host
shiva, 2026-10-17) and are the fixture group `oracle-2026-10-17`, recorded
with that provenance:

- **F1** (copy lineage, rows 25 and 60): `$a=[10,20,30]; foreach ($a as &$v) {
  echo "$v,"; if (first) { $b=$a; $a[2]=99; } }` prints `10,20,99,`.
- **F2** (named arguments, row 57): with `function f($x) {}` and a `side()`
  that echoes `late`, `f(bad: 1, x: side())` raises `Error: Unknown named
  parameter $bad`, and `late` is never printed. `f(bad: side())` runs `side()`
  and then raises the same `Error`.
- **F3** (readonly clone, rows 66 and 67): with `public readonly int $x`
  initialized to 1 in the constructor, `function __clone() { unset($this->x);
  $this->x = 2; }` yields `2` with no error.

Anchor programs that every revision of the table has been traced against
(expected PHP 8.3 output):

+--------------------------------------+---------------------------------------+
| Program                              | Output                                |
+======================================+=======================================+
| `$a=[1]; $r=&$a[0]; $b=$a; $b[0]=2;  | `2`                                   |
| echo $a[0];`                         |                                       |
+--------------------------------------+---------------------------------------+
| `$a=[1]; $r=&$a[0]; unset($r);       | `1`                                   |
| $b=$a; $b[0]=2; echo $a[0];`         |                                       |
+--------------------------------------+---------------------------------------+
| `$a=[1]; $r=&$a[0]; $b=$a;           | `1`                                   |
| unset($r); $b[0]=2; echo $a[0];`     |                                       |
+--------------------------------------+---------------------------------------+
| `$a=[1]; $r=&$a[0]; $b=$a;           | `2`                                   |
| unset($b[0]); $a[0]=2; echo $r;`     |                                       |
+--------------------------------------+---------------------------------------+
| `$b=&$a; $c=&$a;`                    | one reference, rc 3, no nesting       |
+--------------------------------------+---------------------------------------+
| `$a=[1,2]; $r=&$a[0]; $b=$a;         | `2`                                   |
| $s=&$b[1]; $s=9; echo $a[1];`        |                                       |
+--------------------------------------+---------------------------------------+
| `$a=[1]; $r=&$a[0]; $z=&$a;          | no-op, nothing destroyed              |
| unset($z); $a=&$a;`                  |                                       |
+--------------------------------------+---------------------------------------+
| `$a=[1]; $r=&$a[0]; $x=&$a; $a=0;    | `1`                                   |
| $b=[&$r]; unset($r); $c=$b; $c[0]=2; |                                       |
| echo $b[0];`                         |                                       |
+--------------------------------------+---------------------------------------+
| `class C {} new C();`                | ok: a missing constructor is not an   |
|                                      | error                                 |
+--------------------------------------+---------------------------------------+
| `class D { function __destruct(){    | no `d`                                |
| echo "d"; } function                 |                                       |
| __construct($x){} } new              |                                       |
| D(throwing());`                      |                                       |
+--------------------------------------+---------------------------------------+

### 4.6 Fixture derivation

Three sources, none intuition:

1. **php-src tests.** From the pinned php-src tag, every `.phpt` selected by
   checked-in predicates whose source uses only the supported surface (no
   `preg_`, no extensions, no `goto`), with its `--EXPECT--`/`--EXPECTF--` as
   the oracle (`%d`-style patterns normalized, object ids compared by order).
   The selection covers `&` and `unset` forms, `global` and `static`,
   `tests/lang/foreachLoop.*`, `passByReference_*`,
   `engine_assignExecutionOrder_*`, the `Zend/tests/bug*` reference
   regressions, `Zend/tests/list/`, `foreach_*`, `unset_*`,
   `temporary_cleaning_*`, `named_params/`, `new_*`, `tests/classes/ctor_*`,
   `constructor_*` and `destructor_*`, `objects_0*`,
   `type_declarations/typed_properties_*` (references, uninitialized,
   readonly), `readonly_props/` (clone, unset, initialization scope), `gc_*`
   where no cycle collection is required, and `ext/standard/tests/array/`
   `current`, `next`, `reset` and `end` variations. The selection script and
   the resulting list are checked in; the count is reported, not guessed.
2. **One program per table row** against each applicable content kind, written
   as the minimal program reaching the named handler, with the oracle from the
   CLI.
3. **The permutation corpus**: from `$a=[1,2]; $r=&$a[0];`, every sequence of
   length 2 to 5 over the alphabet `{C: $b=$a, U: unset($r), W: $b[0]=2, X:
   unset($b[0]), A: $a[0]=3, D: unset($b), N: $c=$b; $c[1]=9, B: $s=&$b[1];
   $s=9, Z: $z=&$a; unset($z), T: $a=0, L: $q=[&$r]; unset($r), G: f($a) (copy
   and write), E: $a=&$a}`, printing `var_dump($a,$b,$c,$q,$r,$s)`, plus the
   same alphabet over a nested path `$a['x'][0]`. The full corpus runs in the
   slow tier and a fixed sample in the quick tier.

Fixture groups: `ref-lifecycle-v1` (rows 1 to 22 and the permutation corpus),
`iteration-v1` (rows 24 to 29, 54, 55, 60, 61), `temps-v1` and `calls-v1`
(rows 32 to 40, 53, 56 to 58, 62 to 65), `objects-v1` (rows 41 to 52, 59, 66
to 68), and `oracle-2026-10-17`. Each corner's fixture (section 17.2) records
its expected departure as data, so the differential harness distinguishes a
known corner from a row defect automatically.

### 4.7 Oracle gates before landing

The final review's sign-off keeps the implementation oracle gates: each of
these rows was written from handler source and must be confirmed against the
pinned CLI before the slice claiming it lands (a mismatch is a row defect
under section 17.3).

- Row 25: `current($a)` inside a by-reference loop after a separation (the
  internal pointer is independent of the iterator); migration to a table whose
  pointer `end()` moved; migration back to a previously copied table (row 25
  deletes non-adopted records, so a return is unrelated).
- Row 26: repositioning for every rebuilding builtin and `:ptr` reset by the
  `sort` family.
- Row 55: the pointer copied with a plain value (`next($a); $b = $a;
  current($b)`).
- Row 41: the two readonly reference texts.
- Row 42: the coercion order with multiple sources and the `TypeError` texts
  for sources of two classes.
- Row 53: the four constructor fixtures (`oracle-2026-10-17/C1` to `C4`: no
  constructor with zero and with side-effecting arguments, abstract class,
  private constructor, ctor-failed destructor), named distinctly from the
  corners C1 to C23.
- Row 57: `f(bad: new D)` with an echoing destructor (constructed, destroyed
  during cleanup before the `Error` surfaces, order pinned); an unknown name
  for a by-reference-only callee (`function f(&$x)`, `f(bad: $u['k'])`: read
  fetch, no vivification).
- Row 65: the exact text and the position (spread or entry) of the
  positional-after-string-key `Error`.
- Rows 66 and 67: `unset` twice in `__clone`; the uninitialized-`unset`
  branch; a child class's `__clone` modifying the parent's readonly property
  (8.3: `Error`, declaring scope only).
- Row 22: termination of the repeat-until-stable walk when destructors create
  new globals.
- The renderer: `var_dump` prints `&` only for a slot whose reference is
  shared (rc above 1).

### 4.8 Cost

A cell per array that ever holds a counted thing (arrays of objects included),
`O(|deps|)` per separation, one `try-finally` frame per call of a function
with locals, and one temp-list push per counted owned temporary. This is the
engine's own bookkeeping, and the price of exactness for the specified
fragment (owner decision B8.8).

## 5. The legacy migration: replace, not wrap

Reading `yang/php.cljc` confirms the audit: it has no cells (assignment is
lexical rebinding, so `$x = 1; $f = function() use ($x) {...}; $x = 2` and
every loop-carried mutation are wrong except the single-threaded-variable
special case `for-threaded-var`), no arrays, no strings beyond literals, no
`echo` or output, no classes, references, null semantics or coercion, a
Z-combinator per function and per loop, type hints parsed and dropped, `-10;`
folded into a literal, and PHP `null` lowered to host `nil`. Its tokenizer has
no modes (no HTML, no heredoc, no `<?=`). Replacing only its parser would keep
every semantic defect. **Ruling: replace.** Nothing of the legacy lowering is
reused; the Z-combinator encoding is superseded by self-applied `:loop`-marked
lambdas with cells, which also makes safepoints and tail marks work.

Migration mechanics (the F2 pattern):

- The ANTLR frontend is installed in `yin.repl.frontends/standard` as a
  new revision of `:yang/php` (the compiler frontend keeps its revision
  during the migration window; `[id revision]` pins either), with no
  fallback.
- Every program in `test/yang/php_test.clj` is re-expressed in the new corpus,
  with expectations corrected where the legacy was wrong (`null` is
  `:php/null`, not `nil`).
- The browser demo `src/cljs/datomworld/demo/compilation_pipeline.cljs`
  compiles editable PHP synchronously in CLJS with the legacy frontend, while
  the local ANTLR parser is JVM-only until a Dart helper exists (amended
  2026-10-17: PHP has Java and JavaScript helpers upstream; per-target helper
  availability decides the host matrix, `yang.antlr.md` 4.2). The demo moves to **checked-in CST packets**
  for its sample programs (generated by a JVM script, the `dht-import-v1.edn`
  pattern), lowered and run parserless in the browser; editable PHP source in
  the demo is dropped, or routed to a configured parser service when one
  exists (owner decision B8.9). `src/dev/subsystems.edn` is a one-line
  retarget.
- The compiler revision of `:yang/php` retires at P8 (implementation
  deleted, no shim; the repository has no backward-compatibility
  obligation) once the demo retarget has landed and the corpus covers the
  compiler's programs (owner decision B8.6).

What stays refused after migration: `goto`; return by reference (corner C7);
`declare(encoding)`; `__halt_compiler`; `eval` until its slice; `$$` and
`extract` until the dynamic-scope slice; extension surfaces (`ext/*`: PDO,
curl, mbstring, gd, intl, openssl, sockets, `pcntl` beyond signals), each a
bound surface per ADR 0004; `preg_*` until a portable regex engine exists (a
PCRE-compatible engine is large and host engines diverge); `date`, `time`,
`microtime`, `rand`, `random_int` and `uniqid` exist only as effects with
explicit outcomes.

## 6. The grammar route and parser worker

- **Package**: `antlr/php/manifest.edn`, from grammars-v4 `php`
  (`PhpLexer.g4`, `PhpParser.g4`, MIT), vendored verbatim. The one Java
  helper, `Java/PhpLexerBase.java` (modes HTML, Script, CSS, PHP, Heredoc;
  `<?=`; heredoc and nowdoc end detection; string-interpolation lexing),
  carries an explicit MIT notice (Kochurkin and Marianne) and is **vendored**
  beside the `.g4` files with its notice; it does not share the TypeScript
  helpers' unresolved-notice situation.
- `PhpParser.g4` has no superClass and no predicates, so the parser half is
  action-free on every target and only the lexer base is per-target. Upstream
  has Java, C#, C++, JavaScript, Python3 and Antlr4ng ports of the base but no
  Dart, so the local parser is JVM-first; a Node local parser is a later claim
  via the upstream JavaScript base, never assumed (`yang.antlr.md` 4.2
  per-target helper versioning applies). The entry rule is `htmlDocument`. The
  grammar's non-standard `typeParameterListInBrackets` (generics) is refused
  as `:yang.php/not-php`.
- **Helper token rewrites**: the helper turns a `?>` close tag into a
  `SemiColon`-typed token (text `?>` kept, so the exporter sees real text and
  a non-synthetic span), and constructs or rewrites heredoc end tokens
  (`identifier + ";\n"` or `"\n;"`), which the exporter classifies as
  synthetic with a zero-width span. There is no helper patch for heredoc ends:
  the lowering derives heredoc and nowdoc content from the **spans of the body
  tokens**, never from an end token's text, and handles the close-tag
  `SemiColon` tokens by type. If a P0 fixture shows the lowering needs a
  rewritten token's source, the Python-style positioned-token patch is added
  to the manifest at P0, not later.
- **Worker**: `yang.antlr.cst/make-worker` unchanged, profile `{:grammar-id
  :make-lexer :make-parser :start (.htmlDocument p)}`. Mode-dependent tokens
  (interpolated string parts, heredoc bodies) export as ordinary tokens with
  real spans.
- **REPL completeness probe**: PHP has no ASI, but "error at EOF after a
  complete `<?php` prefix" is a reliable `:incomplete` signal only for
  unbalanced braces and parentheses, so the probe is brace balance plus a
  reparse, with an explicit-submit fallback.

## 7. CST to UAST and scope analysis

### 7.1 CST inventory highlights

`htmlDocument`, `inlineHtml`, `phpBlock`, `topStatement`,
`namespaceDeclaration`, `useDeclaration`, `functionDeclaration`,
`classDeclaration` (class, trait and interface via `classEntryType`),
`enumDeclaration`; statements `ifStatement`, `whileStatement`,
`doWhileStatement`, `forStatement`, `foreachStatement`, `switchStatement`,
`tryCatchFinally`, `globalStatement`, `staticVariableStatement`,
`echoStatement`, `inlineHtmlStatement`, `declareStatement`, `gotoStatement`,
`unsetStatement`, `returnStatement`, and `breakStatement`/`continueStatement`
with levels; the `expression` labelled alternatives (`AssignmentExpression`
including `= &`, `CoalesceExpression`, `SpaceshipExpression`,
`InstanceOfExpression`, `IncludeExpression`/`RequireExpression`,
`castOperation`, `newExpr`, `chain`/`memberAccess`,
`arrayCreation`/`arrayItem` with `&`, `lambdaFunctionExpr`/`LambdaFn`,
`matchExpr`, `yieldExpression`, `string`/`interpolatedStringPart`,
`attributes`); `formalParameter` with `&`; `typeHint` unions; `argumentName`.

### 7.2 Context, names and marks

The lowering context is `{:lower :scope :loop [{:brk :cont}] :ret :class-ctx
:static-ctx :gen :strict-types :unit}`, where `:loop` is a stack so that
`break 2` indexes it. The name supply is `%<prefix><cst-id>` (`%` cannot begin
a PHP identifier or variable). PHP has no erasure law, so the canonical
renaming pass of `yang.typescript.md` section 6.3 is not required; adopting it
is the same owner-scheduled choice as for Python. Marks: `:loop` on loop
lambdas, `:call` on function code lambdas, and `:line` on statement starts
under a ticks profile only (P7-b).

### 7.3 Scope analysis

`yang.php.antlr.scope`. Scopes are the unit (global) and each function,
method, closure and arrow function. Each records `{:kind :params :assigned
:statics :globals :uses :arrow-captures :dynamic? :this?}`, with by-reference
and variadic flags on parameters and `{:name :ref?}` per `use`. `$this` is a
parameter of methods. Arrow functions compute `:arrow-captures` as the free
variables of their body that the enclosing scope binds. `:dynamic?` follows
section 3.3. Superglobals resolve to `$GLOBALS` entries everywhere.

Name resolution for functions, classes and constants is compile-time:
`namespace` and `use` statements resolve every unqualified name to a fully
qualified name by PHP's rules. Functions and constants fall back to the global
namespace at run time, which the lowering encodes as a two-key lookup,
`php/fn-lookup "ns\\f" "f"`.

## 8. The lowering-arms inventory

+------------+--------------------------------+--------------------------------+
| Group      | Arms                           | Lowering                       |
+============+================================+================================+
| Output     | `echo`, `print`, `inlineHtml`; | `php/echo` appends to the      |
|            | `printf`, `sprintf`,           | output-buffer stack            |
|            | `number_format` (prelude)      |                                |
+------------+--------------------------------+--------------------------------+
| Variables  | `$x`, `$this`,                 | `php/local-get` or `cell/get`  |
|            | `$GLOBALS[...]`, superglobals, | through the marker deref;      |
|            | `$$x` (dynamic profile, P9),   | writes through `php/assign`    |
|            | `list()`/`[]` destructuring    | (section 4)                    |
|            | with keys and references       |                                |
+------------+--------------------------------+--------------------------------+
| Operators  | arithmetic with overflow to    | `php/*` applications           |
|            | float; string `.` and `.=`;    |                                |
|            | `==`, `===`, `<=>`, `<` with   |                                |
|            | the juggling table; `&&`,      |                                |
|            | `and`; `??`, `??=`; ternary    |                                |
|            | and short ternary; bitwise     |                                |
|            | (int only; bytewise on         |                                |
|            | strings); casts; `instanceof`; |                                |
|            | `clone`; `new`; `@`; increment |                                |
|            | and decrement (string and null |                                |
|            | rules); `print` as an          |                                |
|            | expression                     |                                |
+------------+--------------------------------+--------------------------------+
| Arrays     | literals with keys, spread and | section 4 rows over plain      |
|            | references; `$a[k]`, `$a[]`,   | values and instances;          |
|            | nested writes, `unset($a[k])`, | `php/array-*`                  |
|            | `isset`, `empty`; `foreach` by |                                |
|            | value, by key, by reference,   |                                |
|            | with `list`                    |                                |
+------------+--------------------------------+--------------------------------+
| Strings    | single-quoted; double-quoted   | `php/str-concat` of parts,     |
|            | with interpolation (`$x`,      | each part through              |
|            | `{$x->y}`, `${x}`, `$a[k]`);   | `php/to-string`                |
|            | heredoc and nowdoc (the 7.3    |                                |
|            | indentation-removal rule);     |                                |
|            | escapes including `\u{...}` as |                                |
|            | UTF-8 bytes                    |                                |
+------------+--------------------------------+--------------------------------+
| Control    | `if`/`elseif`/`else` and the   | loops are self-applied `:loop` |
|            | alternative syntax; `while`,   | lambdas; multi-level escapes   |
|            | `do`, `for` with comma         | index the `:loop` stack        |
|            | expressions; `switch` (loose   |                                |
|            | `==`, fallthrough, `continue`  |                                |
|            | inside `switch` acting as      |                                |
|            | `break` with the 7.3 warning); |                                |
|            | `break n`/`continue n`;        |                                |
|            | `match`; `return`              |                                |
+------------+--------------------------------+--------------------------------+
| Functions  | declarations (hoisted or in    | `php/make-function name spec   |
|            | place), closures with `use`,   | code statics`; calls through   |
|            | arrow functions, defaults,     | pending calls (rows 53 to 65)  |
|            | by-reference parameters,       | to `php/call`; bodies wrapped  |
|            | variadics, named arguments,    | in `php/try-finally` with      |
|            | type declarations with         | `php/release-cvs`; statements  |
|            | coercion or `TypeError`,       | end with `php/release-tmps`    |
|            | return types, `static`,        |                                |
|            | `global`, first-class callable |                                |
|            | `f(...)`, callables            |                                |
+------------+--------------------------------+--------------------------------+
| Classes    | class, abstract, final,        | `php/declare-class` at the     |
|            | readonly; properties (typed,   | declaration point into the     |
|            | defaults, static); constants   | class table; traits copied     |
|            | (`final`, visibility);         | there                          |
|            | methods; constructors with     |                                |
|            | promotion;                     |                                |
|            | `static`/`self`/`parent`;      |                                |
|            | interfaces; traits (`use`,     |                                |
|            | `insteadof`, `as`); enums      |                                |
|            | (pure and backed); attributes  |                                |
|            | (data only); magic methods;    |                                |
|            | `__toString` in string         |                                |
|            | contexts                       |                                |
+------------+--------------------------------+--------------------------------+
| Exceptions | `try`/`catch`/`finally`,       | handler stack with             |
|            | multi-catch, `throw`           | `php/raise`; `finally` via     |
|            | expressions, rethrow           | `php/try-finally`              |
+------------+--------------------------------+--------------------------------+
| Generators | `yield`, `yield k => v`,       | the C2 core under `php/gen-*`  |
|            | `yield from`; `send`, `throw`, |                                |
|            | `getReturn`                    |                                |
+------------+--------------------------------+--------------------------------+
| Modules    | `include`, `require`, `_once`; | section 11; ticks per section  |
|            | `namespace`, `use`;            | 12                             |
|            | `declare(strict_types)`,       |                                |
|            | `declare(ticks)`               |                                |
+------------+--------------------------------+--------------------------------+
| Refused    | `goto`; return by reference;   | `:yang.php/unsupported         |
|            | `eval` until P9; `$$` outside  | {:construct}`                  |
|            | the dynamic-profile slice;     |                                |
|            | `__halt_compiler`;             |                                |
|            | `declare(encoding)`; nested    |                                |
|            | destructuring by reference     |                                |
|            | beyond one level (corner C12)  |                                |
+------------+--------------------------------+--------------------------------+

## 9. The prelude inventory

Module `php` (reserved), hook prelude `phpsp`. `php/init!` builds the class
table with the builtin classes and interfaces (`stdClass`, `Closure`,
`Generator`, `Traversable`, `Iterator`, `IteratorAggregate`, `ArrayAccess`,
`Countable`, `Stringable`, `JsonSerializable`, `UnitEnum`, `BackedEnum`, the
`Throwable` hierarchy; `ArrayObject` deferred), the function table with the
supported builtins and their by-reference positions, `$GLOBALS` from the
request record, the output-buffer stack, the error-handler cell, the
included-files dict, the `finalizable` registry and the shutdown-function
list, and flips `php.rt/state` to `:php/ready` last.

+--------------------------+--------------------------------------+------------+
| Area                     | Supported core (Phase 3)             | Est. lines |
+==========================+======================================+============+
| Type juggling            | `to-int`, `to-float`, `to-string`    | 700        |
|                          | (precision 14), `to-bool`,           |            |
|                          | `to-array`, numeric-string           |            |
|                          | classification, the `==`/`<=>`/`===` |            |
|                          | tables, `gettype`, `is_*`, `intval`, |            |
|                          | `floatval`, `strval`, `boolval`,     |            |
|                          | `settype`, and the `var_dump`,       |            |
|                          | `print_r` and `var_export` text      |            |
|                          | (serialize precision -1, the `#n`    |            |
|                          | object ids, indentation rules)       |            |
+--------------------------+--------------------------------------+------------+
| Arrays                   | `count`; `array_keys`, `_values`,    | 900        |
|                          | `_merge`, `_merge_recursive`,        |            |
|                          | `_combine`, `_flip`, `_slice`,       |            |
|                          | `_splice`, `_map`, `_filter`,        |            |
|                          | `_reduce`, `_walk`, `_search`,       |            |
|                          | `_key_exists`, `in_array`,           |            |
|                          | `_unique`, `_reverse`, `_sum`,       |            |
|                          | `_product`, `_fill`, `_fill_keys`,   |            |
|                          | `_pad`, `_chunk`, `_column`,         |            |
|                          | `_diff`/`_intersect` (with           |            |
|                          | `_key`/`_assoc`), `_push`, `_pop`,   |            |
|                          | `_shift`, `_unshift`, `_key_first`,  |            |
|                          | `_key_last`, `_is_list`; the sort    |            |
|                          | family (stable since 8.0, a flags    |            |
|                          | subset); `range`; `compact` and      |            |
|                          | `extract` (dynamic profile);         |            |
|                          | `implode`, `explode`, `str_split`;   |            |
|                          | `iterator_to_array`; `current`,      |            |
|                          | `key`, `next`, `reset`, `end`,       |            |
|                          | `prev` (row 55)                      |            |
+--------------------------+--------------------------------------+------------+
| Strings (byte strings)   | `strlen`, `substr`,                  | 900        |
|                          | `strpos`/`stripos`/`strrpos`,        |            |
|                          | `str_replace`/`str_ireplace`,        |            |
|                          | `str_contains`, `str_starts_with`,   |            |
|                          | `str_ends_with`,                     |            |
|                          | `trim`/`ltrim`/`rtrim`, ASCII        |            |
|                          | case functions (PHP 8 is             |            |
|                          | locale-insensitive), `str_repeat`,   |            |
|                          | `str_pad`, `strrev`, `strcmp`,       |            |
|                          | `strcasecmp`, `strncmp`, `strstr`,   |            |
|                          | `strrchr`, `substr_count`,           |            |
|                          | `wordwrap`, `nl2br`,                 |            |
|                          | `htmlspecialchars`, basic            |            |
|                          | `html_entity_decode`, `addslashes`,  |            |
|                          | `stripslashes`, `chunk_split`,       |            |
|                          | `sprintf`, `printf`, `vsprintf`,     |            |
|                          | `number_format`, `ord`, `chr`,       |            |
|                          | `bin2hex`,                           |            |
|                          | `hex2bin`, base conversions; `md5`,  |            |
|                          | `sha1`, `crc32`, `hash('sha256')`    |            |
|                          | only where pure kernels exist, else  |            |
|                          | refused; `json_encode`/`json_decode` |            |
|                          | (a flags subset,                     |            |
|                          | `JSON_THROW_ON_ERROR`, pretty        |            |
|                          | print); `serialize` deferred;        |            |
|                          | `preg_*` refused                     |            |
+--------------------------+--------------------------------------+------------+
| Math                     | `abs`, `ceil`, `floor`, `round`      | 250        |
|                          | (PHP's pre-rounding rule), `intdiv`, |            |
|                          | `fmod`, `pow`, `sqrt`, `min`, `max`, |            |
|                          | `array_sum`;                         |            |
|                          | `PHP_INT_MAX`/`MIN`/`SIZE`,          |            |
|                          | `PHP_FLOAT_*`, `M_PI`; `is_nan`,     |            |
|                          | `is_finite`, `is_infinite`; `rand`,  |            |
|                          | `mt_rand`, `random_int` as effects   |            |
+--------------------------+--------------------------------------+------------+
| Functions and classes    | call binding (positional, named,     | 700        |
|                          | variadic, by-reference, defaults,    |            |
|                          | coercion and `TypeError`,            |            |
|                          | `ArgumentCountError`) through        |            |
|                          | pending calls; `func_get_args`,      |            |
|                          | `call_user_func(_array)`,            |            |
|                          | `is_callable`, `function_exists`;    |            |
|                          | class-table operations, `get_class`, |            |
|                          | `get_parent_class`, `method_exists`, |            |
|                          | `property_exists`, `class_exists`,   |            |
|                          | `get_object_vars`, `spl_object_id`;  |            |
|                          | visibility checks; magic dispatch;   |            |
|                          | `Closure::bind`, `fromCallable` and  |            |
|                          | `call`; enum                         |            |
|                          | `cases`/`from`/`tryFrom`;            |            |
|                          | object `:rc`, finalization and       |            |
|                          | `clone` (section 4.4.6)              |            |
+--------------------------+--------------------------------------+------------+
| Control and errors       | the handler stack (the Python        | 350        |
|                          | pattern), `php/raise`, `finally`,    |            |
|                          | escapes with levels, `RELEASE_CVS`   |            |
|                          | and temp-list release; `Throwable`   |            |
|                          | classes with `getMessage`,           |            |
|                          | `getCode`, `getPrevious`, `getTrace` |            |
|                          | (empty), `getLine` (derived at the   |            |
|                          | boundary); `set_error_handler`,      |            |
|                          | `restore_error_handler`,             |            |
|                          | `set_exception_handler`,             |            |
|                          | `trigger_error`, `error_reporting`;  |            |
|                          | warning emission `php/warn`          |            |
+--------------------------+--------------------------------------+------------+
| Generators and iteration | the C2 core under `php/`, the        | 300        |
|                          | `Iterator` protocol, the `foreach`   |            |
|                          | driver with iterators (section       |            |
|                          | 4.4.3), `yield from` over arrays and |            |
|                          | generators                           |            |
+--------------------------+--------------------------------------+------------+
| Request and output       | `$_*` construction, `php/echo`, the  | 300        |
|                          | `ob_*` stack, `header`,              |            |
|                          | `headers_sent`,                      |            |
|                          | `http_response_code`, `setcookie`,   |            |
|                          | `exit`, `register_shutdown_function` |            |
|                          | and the shutdown sequence (row 22),  |            |
|                          | `php_sapi_name` (`"cli"` or          |            |
|                          | `"yin-http"`, pinned), `getenv` from |            |
|                          | the record only, `PHP_EOL`, a pinned |            |
|                          | `PHP_VERSION`, `error_log` as an     |            |
|                          | effect                               |            |
+--------------------------+--------------------------------------+------------+
| Module runtime           | `include`, `require`, `_once`, the   | 250        |
|                          | included-files dict, the autoload    |            |
|                          | class map, `define`, `constant`,     |            |
|                          | `defined`, namespace lookup          |            |
+--------------------------+--------------------------------------+------------+

Refusal posture: a builtin outside the supported core is
`:yang.php/unknown-builtin` at lowering (a warning) and `Error: Call to
undefined function` at run time; an extension surface is bound per ADR 0004 as
its own slice with an effect schema and an interpreter; nothing is stubbed to
return success. Until the `yrt` shared prelude is scheduled
(`yang.typescript.md` owner decision A12.5), PHP copies the escape machinery,
generator core, ordered map and module skeleton patterns under `php/`; it
would be `yrt`'s second consumer.

## 10. Value encoding and paradigm-table rows

### 10.1 Value encoding (runtime profile data)

+-----------------+------------------------------------------------------------+
| PHP type        | Representation                                             |
+=================+============================================================+
| int             | exact integer within `[-2^63, 2^63-1]` (C3 carriers; on    |
|                 | the JavaScript host a value past 2^53 is a `BigInt`        |
|                 | carrier); an operation's overflow demotes to float         |
+-----------------+------------------------------------------------------------+
| float           | Jing float64 content (carrier on the JavaScript host); one |
|                 | NaN; `-0.0` preserved; `INF`                               |
+-----------------+------------------------------------------------------------+
| string          | **byte string**: a host string whose every char is one     |
|                 | byte 0 to 255 (a latin-1 carrier of the bytes); source     |
|                 | literals contribute their UTF-8 bytes; `strlen` counts     |
|                 | bytes; output writes the bytes raw; `mb_*` is a separate   |
|                 | slice over this representation (owner decision B8.2)       |
+-----------------+------------------------------------------------------------+
| bool            | host boolean                                               |
+-----------------+------------------------------------------------------------+
| null            | `:php/null`                                                |
+-----------------+------------------------------------------------------------+
| array, plain    | `{:php/type :array :index {key slot} :order [key ...]      |
|                 | :next-index n}` stored directly in its holder (section     |
|                 | 4.2); `:next-index` is absent until the first integer key  |
|                 | is written                                                 |
+-----------------+------------------------------------------------------------+
| array, instance | cell `{:php/type :arr :rc n :value <plain map> :deps       |
|                 | #{keys} :iterators #{it} :ptr i}`, held through `{:php/arr |
|                 | h}`                                                        |
+-----------------+------------------------------------------------------------+
| reference       | cell `{:php/type :ref :rc n :value x :sources #{[o p]}}`,  |
|                 | held through the marker `{:php/ref c}` in a variable,      |
|                 | property or instance slot                                  |
+-----------------+------------------------------------------------------------+
| object          | cell `{:php/type :obj :rc n :class C :props ordered-map    |
|                 | :ord n :destructed? b}` with per-property `initialized?`   |
|                 | and `reinitable` state; `:ord` is the per-task creation    |
|                 | ordinal, also the `#n` of `var_dump` and `spl_object_id`   |
|                 | (never reused, corner C2); `clone` allocates a fresh cell  |
+-----------------+------------------------------------------------------------+
| closure         | an object cell of class `Closure` holding the code         |
|                 | closure, the bound `$this`, the scope class, and the       |
|                 | `use`d values or markers                                   |
+-----------------+------------------------------------------------------------+
| enum case       | a singleton object cell per case                           |
+-----------------+------------------------------------------------------------+
| iterator        | cell `{:php/type :iter :current h :tables {h pos}}`        |
|                 | (section 4.4.3)                                            |
+-----------------+------------------------------------------------------------+
| resource        | opaque handle data from a bound surface (`{:php/resource   |
|                 | kind id}`); none in Phase 3                                |
+-----------------+------------------------------------------------------------+
| callable        | a string, a `[class-or-obj, name]` array, or a `Closure`;  |
|                 | resolved at call                                           |
+-----------------+------------------------------------------------------------+

### 10.2 Paradigm rows for `yang.antlr.md` 8.3

```text
+--------------------------+-------------------------------------+-----------+
| Paradigm                 | Universal AST target                | Reference |
+==========================+=====================================+===========+
| Value-copy ordered maps  | Arrays are persistent ordered-map   | PHP       |
| with reference slots and | values (O(1) copy) until they hold  |           |
| request scope (new row,  | a counted thing, then counted       |           |
| finalizing D5)           | instance cells duplicated by the    |           |
|                          | engine's rule; references are       |           |
|                          | counted cells held by markers;      |           |
|                          | objects are counted handles with    |           |
|                          | destructors; locals are cells,      |           |
|                          | dynamic-scope functions a heap      |           |
|                          | symbol table; the global scope is   |           |
|                          | the $GLOBALS dict; a request is one |           |
|                          | task reading one request record and |           |
|                          | appending ordered output records;   |           |
|                          | warnings are diagnostic records,    |           |
|                          | fatals are throws                   |           |
+--------------------------+-------------------------------------+-----------+
| PHP number model         | int = exact integer bounded at 2^63 |           |
|                          | with overflow to float (detected by |           |
|                          | exact promotion, demoted by rule);  |           |
|                          | float = float64 content (carrier on |           |
|                          | JS); echo at precision 14, var_dump |           |
|                          | shortest; array keys coerce         |           |
|                          | int-like strings and floats to int  |           |
+--------------------------+-------------------------------------+-----------+
| Class-based OO (existing | Class descriptors in cells, traits  | PHP (with |
| row, PHP entry           | copied at declaration, interfaces   | Java,     |
| qualified)               | as dispatch contracts, runtime      | Python)   |
|                          | visibility against a class context  |           |
+--------------------------+-------------------------------------+-----------+
```

## 11. include and require over the linker

### 11.1 Units, names and inclusion

- A PHP file compiled as unit `src/lib/util.php` becomes linker module
  `phpm.src$lib$util` (injective mangling of the normalized relative path; `/`
  and `.` become `$`-escapes). Its shape is Python's: `(do (require 'php)
  (yin/def spec {:path "src/lib/util.php" :strict-types 0}) (yin/def body (fn
  [%scope %ctx] ...)))`.
- `include 'x.php'` with a **literal** path (after constant folding of
  `__DIR__ . '/x.php'`, which the lowering performs because `__DIR__` is the
  unit's declared path, not a filesystem fact) lowers to the I4-a
  statement-time delivery thunk. The body runs in the **including scope**: it
  receives the including scope's symbol table, which is why an including
  function is on the dynamic-scope profile (at top level it is `$GLOBALS`).
  The body's `return` value is the expression's value, else `1`. `_once`
  consults the included-files dict.
- Computed paths are a diagnostic (the literal-name restriction of ruling 10;
  a published literal path map can later interpret them). Conditional
  declarations inside included files execute in place, as in PHP.
- Cycles of `include` are a runtime matter (the included-files dict), not a
  linker cycle: static `include` edges are pinned in `:requires` only for
  delivery, and SCC publication handles mutual pins.
- **Namespaces** are compile-time; nothing links per namespace.

### 11.2 Autoload

`spl_autoload_register` with a guest callable is supported only when the
composition also publishes a literal **class map** (class name to unit) as
part of the unit set; the autoloader then runs on a class miss and may
`require` only literal paths in that map. Composer's generated class map is
admissible as data; its filesystem resolution is not (owner decision B8.7).

### 11.3 Missing includes: two stages, two meanings

1. **Publication**: every static `include`/`require` edge with a literal path
   must be pinned to a concrete address. A target absent from the unit set and
   from `published` is refused `:unpublished-import` by the lifted publisher
   (`yang.antlr.md` 8.5.6: "publication still requires a concrete address").
   There is **no** publication-time representation of an optional absent
   include; that is the known-gaps ledger's open item
   ("publication-time-unavailable optional imports cannot be represented
   without concrete segment pins"), not solved here (owner decision B8.10).
2. **Run time**: "missing" means the pinned content is unavailable or its
   delivery is refused (`:absent` with `:address`, a mismatch, an untrusted
   principal). `include` emits PHP's `Warning: include(...): Failed to open
   stream` record and yields `false`; `require` throws `Error` with PHP's
   fatal text.

## 12. Safepoints

- `:loop` and `:call` hooks (`phpsp`) serve signals: `pcntl_signal` handlers
  run at hooks via `stream/poll!` on a signal stream, and
  `pcntl_async_signals` is the default and only mode. A recursion-depth bound
  is available if a composition wants one (PHP has none; default off).
- `max_execution_time` and `set_time_limit` are wall-clock in PHP and so
  nondeterministic: the profile offers a deterministic **safepoint-count
  budget** instead, raising the fatal with PHP's text, a recorded departure
  (owner decision B8.5, corner C21).
- **Statement ticks** (`declare(ticks=N)`, `register_tick_function`) need
  `:line` sites before non-lambda statement nodes. `yang.safepoint` currently
  refuses a selected site that is not a `:lambda` (`site-not-lambda`), and
  `:line` insertion is safepoint slice 3, which Python tracing also needs.
  Ticks therefore **depend on the shared `:line` extension**: P7 splits into
  P7-a (signals, no dependency) and P7-b (ticks, behind safepoint slice 3).
  Neither L1 to L4 nor this frontend extends `yang.safepoint`; the extension
  is the safepoint seat's.
- No thread switches: PHP is single-threaded, and one request is one task.

## 13. Diagnostics

Compile time: `:yang.php/syntax`; `:yang.php/unsupported {:construct}`;
`:yang.php/not-php`; `:yang.php/unknown-builtin` (a warning);
`:yang.php/compile-error` for PHP's compile-time fatals (a function redeclared
in one unit, `break 0`, a duplicate parameter, `$this` reassignment, a
positional argument after a named one). Run time: guest warnings, notices and
deprecations as records on the guest-diagnostics stream; fatals as `Error`
throws; the uncaught-exception text pinned. Operational failures stay
operational (`yang.antlr.md` section 11).

## 14. The differential-corpus plan

- **Oracle**: the pinned PHP 8.3.x CLI with the pinned ini. The comparison
  covers stdout and the diagnostics stream, rendered in PHP's `Warning: ... in
  <unit> on line N` form with the path replaced by the unit name and the line
  derived from the side table (owner decision 2 of `yang.antlr.md` 8.5.1
  reused), against PHP's stderr with paths normalized.
- **Groups**: juggling (every pair of the comparison matrix, numeric strings,
  arithmetic warnings and `TypeError`); arrays (key coercion, order, next
  index after unset, copy-on-write observable through references, nested
  writes, sort stability); references and lifecycle (section 4.6's three
  sources); strings (bytes, interpolation forms, heredoc indentation,
  `precision=14` output, `var_dump` text); functions (named arguments,
  by-reference, defaults, variadics, coercion versus `strict_types`); classes
  (trait precedence, visibility, late static binding, magic methods, readonly,
  enums, `match`, destructors, `clone`); exceptions (`finally` ordering,
  multi-catch, the `Error` hierarchy); generators; request (superglobals from
  a fixture record, headers after output, `ob_*`); inclusion (`_once`, return
  values, scope sharing, the two missing-include stages); safepoints (tick
  counts, a signal at a hook).
- **Fixture groups** are those of section 4.6, plus a request-record fixture
  per corpus program. Corners are departure fixtures whose expected value is
  the recorded deviation.

## 15. Slice sequencing and acceptance contracts

PHP depends on L1 to L4 (`yang.typescript.md` section 16) landing first. Every
acceptance line runs bundled on four evaluators and linked on three until L-b,
on JVM, Node and Dart unless noted; source-level parsing is JVM-only. Every
claimed row's fixtures must equal the oracle before its slice lands (section
17.3).

+-------+----------------------------------+-----------------------------------+
| Slice | Scope                            | Acceptance                        |
+=======+==================================+===================================+
| P0    | grammar package                  | packets for mixed HTML and code   |
|       | `antlr/php/manifest.edn` with    | with byte spans; the `?>` newline |
|       | the vendored MIT helper, worker  | rule preserved; span fixtures for |
|       | profile, CST export of modes     | a heredoc with indentation        |
|       | (inline HTML, heredoc,           | removal and interpolation, a      |
|       | interpolation), licence block    | nowdoc, `?>` followed by a        |
|       |                                  | newline and by text, `<?=`, and a |
|       |                                  | close tag ending a statement      |
|       |                                  | without `;`, each asserting the   |
|       |                                  | exported spans cover exactly the  |
|       |                                  | source and synthetic tokens       |
|       |                                  | appear only where expected;       |
|       |                                  | generics refused                  |
+-------+----------------------------------+-----------------------------------+
| P1    | core: `echo`, inline HTML, the   | the mixed text and code           |
|       | output stack, scalars and the    | output-order fixture (exit        |
|       | juggling table, operators with   | criterion 1); `echo 0.1+0.2`      |
|       | overflow to float, byte strings, | prints `0.3` and                  |
|       | control flow, functions with     | `var_dump(0.1+0.2)` prints        |
|       | cells, the `php` prelude         | `float(0.30000000000000004)`;     |
|       | skeleton with `init!`, bundled   | `PHP_INT_MAX + 1` is a float on   |
|       | and linked profiles, the         | every host; float-address goldens |
|       | `var_dump` renderer              | of the `php` prelude agree on     |
|       |                                  | three hosts; `static` initializer |
|       |                                  | timing; the `%` and `intdiv` edge |
|       |                                  | fixtures; the comparison matrix;  |
|       |                                  | `@` with a call-counting handler  |
+-------+----------------------------------+-----------------------------------+
| P2    | arrays: the value, key coercion, | the key-coercion fixture table;   |
|       | nested writes, `foreach` by      | `$b = $a; $b[] = 1` leaves `$a`   |
|       | value, the core array builtins,  | unchanged; order after unset and  |
|       | sorting; the by-reference        | re-add; stable-sort parity (exit  |
|       | position table for every         | criterion 2)                      |
|       | supported builtin (C6);          |                                   |
|       | rebuild-during-iteration pins    |                                   |
|       | (C15)                            |                                   |
+-------+----------------------------------+-----------------------------------+
| P3    | references and the section 4     | `ref-lifecycle-v1`,               |
|       | model: rows 1 to 47 and 53 to 65 | `iteration-v1`, `temps-v1`,       |
|       | (plain and instance arrays,      | `calls-v1` and                    |
|       | markers, `RELEASE_CVS`,          | `oracle-2026-10-17` F1 and F2     |
|       | iterators with per-table         | equal to the oracle; the anchor   |
|       | records, unset fetches,          | programs of section 4.5; the      |
|       | temporaries and pending calls,   | php-src selection; the            |
|       | named arguments, typed-property  | permutation corpus (sample in the |
|       | references), `global`, `static`, | quick tier, full in the slow      |
|       | closures with `use` and `use     | tier); the `foreach` by-reference |
|       | (&)`, arrow functions,           | leftover binding and the          |
|       | destructuring, by-reference      | second-loop overwrite             |
|       | parameters                       |                                   |
+-------+----------------------------------+-----------------------------------+
| P4    | classes, interfaces, traits,     | classes and exceptions groups; a  |
|       | enums, `match`, exceptions,      | `TypeError` for `f("x")` with     |
|       | magic methods, visibility, typed | `int $x` under `strict_types=1`   |
|       | properties and declarations      | and coercion under 0 (exit        |
|       | (coercive and strict), named     | criterion 4); `instanceof` across |
|       | arguments; generators on the C2  | units of one task; `objects-v1`;  |
|       | core; object `:rc`, `__destruct` | the constructor fixtures; F3 and  |
|       | for acyclic graphs,              | the readonly fixtures; the        |
|       | resurrection, chaining, `clone`, | typed-reference `TypeError`       |
|       | readonly reinitialization in     | texts; the C1 departure fixture   |
|       | `__clone`, ctor-failed objects,  |                                   |
|       | the shutdown sequence (rows 22,  |                                   |
|       | 48 to 52, 62, 66 to 68)          |                                   |
+-------+----------------------------------+-----------------------------------+
| P4-b  | object dimension containers in   | closes corner C4                  |
|       | write fetches (`ArrayAccess` by  |                                   |
|       | reference, `&__get`, indirect    |                                   |
|       | modification)                    |                                   |
+-------+----------------------------------+-----------------------------------+
| P4-c  | generator finalization on the C2 | closes corner C8 and the          |
|       | core                             | `Generator` part of C19           |
+-------+----------------------------------+-----------------------------------+
| P5    | request context: `$_*` from the  | superglobals reproducible from a  |
|       | request record, `ob_*`, headers, | fixture record (exit criterion    |
|       | cookies and status as response   | 3); the headers-after-output      |
|       | records, `exit`, the error       | warning; two requests in two      |
|       | handler, the warnings stream     | tasks share nothing (exit         |
|       |                                  | criterion 5)                      |
+-------+----------------------------------+-----------------------------------+
| P6    | `include`/`require` over the     | the inclusion group; a            |
|       | linker, `_once`, namespaces and  | JVM-published `phpm.*` unit set   |
|       | `use`, constants, the autoload   | imports on Node and Dart with     |
|       | class map, publication and DHT   | equal output; the computed-path   |
|       | import parserless on Node and    | diagnostic; an unpublished target |
|       | Dart, the dynamic-scope profile  | refused at publication by name; a |
|       | for including functions          | pinned but unserved target:       |
|       |                                  | `include` warns and yields        |
|       |                                  | `false`, `require` throws `Error` |
+-------+----------------------------------+-----------------------------------+
| P7-a  | signals: `phpsp` hooks at        | a signal at a `:loop` hook runs   |
|       | `:loop` and `:call`,             | the handler; an empty signal      |
|       | `pcntl_signal`, the safepoint    | stream never parks; budget        |
|       | budget                           | exhaustion raises the pinned      |
|       |                                  | fatal text                        |
+-------+----------------------------------+-----------------------------------+
| P7-b  | ticks: `declare(ticks)`,         | tick counts equal PHP's for the   |
|       | `register_tick_function`; behind | statement fixture                 |
|       | safepoint slice 3                |                                   |
+-------+----------------------------------+-----------------------------------+
| P8    | legacy retirement: the legacy    | the REPL prints `1` for `<?php $x |
|       | test programs in the new corpus, | = 1; echo $x;`; every legacy test |
|       | the demo on checked-in CST       | program passes with corrected     |
|       | packets,                         | expectations; the demo runs its   |
|       | `src/dev/subsystems.edn`         | samples parserless in the         |
|       | retargeted, `yang/php.cljc`      | browser; no reference to the      |
|       | deleted, the compiler revision   | legacy `yang.php` namespace       |
|       | removed from the standard        | remains                           |
|       | catalog                          |                                   |
+-------+----------------------------------+-----------------------------------+
| P9    | builtin surface slices (strings  | each a corpus group with PHP      |
|       | II, arrays II, JSON, math II),   | parity; `eval` shares the         |
|       | `$$`, `extract`, `compact` and   | caller's symbol table; closes     |
|       | the dynamic scope, `eval` as a   | corners C5, C6 (prefer-ref), C11, |
|       | compilation request              | C12, C13                          |
+-------+----------------------------------+-----------------------------------+

Deferred: `preg_*` (a portable regex engine), `mb_*`, `serialize`, Reflection,
SPL data structures, `DateTime` beyond the time effect, sessions, PDO and
every `ext/*` surface (bound per ADR 0004), return by reference, `goto`, cycle
collection (corner C1).

## 16. The yang.core lift enumeration

Beyond L1 to L4 (`yang.typescript.md` section 17), PHP requires nothing new.
It exercises `yang.core.stage` (the ok-check on `:yang.cst/ok`),
`yang.core.lower` (the name supply and diagnostics with the `:yang.php`
namespace), `yang.core.module` (`module-symbol` with prefix `phpm`,
`declare-free-name`), `yang.core.publication` (with `php/imported-unit-names`
from the literal `include` edges and the class map; no binder-table hook), and
`yang.core.prelude` (the emitters, and `admit` with `host-names` adding the
byte-string `data` exports). An optional `:dynamic-scope` helper for the
symbol-table dict is the one candidate addition: Python's
`py/global-get`/`py/global-set` generalize to `yang.core`
`dict-scope-get`/`dict-scope-set` if lifted; otherwise PHP copies them.
`yang.core.names/canonicalize` and `yang.core.claims` are not used.

## 17. Support profile and known engine-fidelity corners

### 17.1 The support-profile statement

The `yang.php` runtime profile claims **exactly the rows of the transition
table** (section 4.4, as consolidated here) with their fixture sets, verified
against the pinned PHP 8.3 CLI on the three fixture sources, plus the slice
behaviour of section 15. Every Zend behaviour outside those rows is listed
below as a named corner with status **restricted** (behaves differently,
pinned by a departure fixture) or **unsupported** (refused with a diagnostic),
the deviation it implies, and the slice that would close it. The profile
claims nothing about a corner beyond what its fixture pins. This is the
project's standing pattern (C3's recorded departures, C2's finalization
restriction, 8.5.6's refusal postures, the known-gaps ledger): an honest
fragment with tracked gaps, not a claim of Zend fidelity.

None of the corners is required by the Phase 3 exit criteria (section 1) or by
the C4 import machinery; where a corner touched one of those it was moved into
the table during the loop (typed-reference sources, destructor timing of
discarded temporaries, unset without vivification). The corner table is the
known-gaps ledger's PHP section, row for row, owner seat `yang.php` (owner
decision B8.11).

### 17.2 Known engine-fidelity corners

+---------------------+-------------------------------------+------------------+
| Corner              | Engine behaviour and implied        | Status; closing  |
|                     | deviation                           | slice            |
+=====================+=====================================+==================+
| C1 cycle collector  | Engine: `gc_collect_cycles`         | restricted; a    |
|                     | reclaims reference cycles and runs  | cycle-collection |
|                     | their destructors at collection     | slice after the  |
|                     | time. Here: cyclic garbage lives    | reclamation work |
|                     | until shutdown and its destructors  | (B8.12)          |
|                     | run in the row-22 phase;            |                  |
|                     | `gc_collect_cycles()` returns 0;    |                  |
|                     | `gc_enable` and `gc_disable` are    |                  |
|                     | no-ops                              |                  |
+---------------------+-------------------------------------+------------------+
| C2 object id reuse  | Engine: `spl_object_id` and `#n`    | restricted; none |
|                     | ids are reused after free. Here:    | planned          |
|                     | ids are fresh ordinals, never       |                  |
|                     | reused                              |                  |
+---------------------+-------------------------------------+------------------+
| C3 weak references  | Engine: `WeakReference` and         | unsupported;     |
|                     | `WeakMap` observe collection. Here: | after C1         |
|                     | refused                             |                  |
+---------------------+-------------------------------------+------------------+
| C4 `ArrayAccess` by | Engine: `offsetGet` returning by    | restricted; P4-b |
| reference,          | reference; "Indirect modification   |                  |
| `&__get`, indirect  | of overloaded element/property"     |                  |
| modification        | notices. Here: results are values;  |                  |
|                     | writes to them are notices with no  |                  |
|                     | effect                              |                  |
+---------------------+-------------------------------------+------------------+
| C5 string offset    | Engine: `$s[i] = 'x'`, padding past | restricted; P9   |
| writes              | the end, negative offsets, the      | (strings II)     |
|                     | `$s[] =` `Error`, multi-byte        |                  |
|                     | warnings. Here: row 3 specifies     |                  |
|                     | only reads and single-byte in-range |                  |
|                     | writes                              |                  |
+---------------------+-------------------------------------+------------------+
| C6 internal         | Engine: `sort`, `array_push`,       | restricted; P2   |
| by-reference        | `preg_match`'s `&$m`, prefer-ref    | (core), P9       |
| parameters          | `array_multisort`, the "Only        | (prefer-ref)     |
|                     | variables should be passed by       |                  |
|                     | reference" notice. Here: each       |                  |
|                     | supported builtin declares its      |                  |
|                     | by-reference positions; the notice  |                  |
|                     | is emitted; prefer-ref builtins are |                  |
|                     | refused                             |                  |
+---------------------+-------------------------------------+------------------+
| C7 return by        | Engine: `function &f()`, `&__get`,  | unsupported; a   |
| reference           | `&offsetGet`. Here: refused         | slice after P9   |
+---------------------+-------------------------------------+------------------+
| C8 generator        | Engine: destroying a suspended      | restricted; P4-c |
| destruction         | generator runs its pending          |                  |
|                     | `finally` blocks and releases its   |                  |
|                     | frame. Here: its CVs are released   |                  |
|                     | (rows 16, 39) but pending `finally` |                  |
|                     | blocks do not run                   |                  |
+---------------------+-------------------------------------+------------------+
| C9                  | Engine: `Attempt to destruct        | restricted; none |
| pending-exception   | pending exception`. Here: an        | planned          |
| destruct fatal      | `Error`-class halt with pinned      |                  |
|                     | text, not a process fatal           |                  |
+---------------------+-------------------------------------+------------------+
| C10 property hooks, | Engine: PHP 8.4. Here: not parsed   | unsupported; a   |
| asymmetric          | (8.3 pinned)                        | grammar and      |
| visibility          |                                     | version bump     |
+---------------------+-------------------------------------+------------------+
| C11 dynamic-scope   | Engine: `extract(EXTR_REFS)`,       | unsupported      |
| references          | `compact` of references, `$$name =& | until P9         |
|                     | ...`, `get_defined_vars`. Here: the |                  |
|                     | symbol table holds markers like any |                  |
|                     | location; `EXTR_REFS` and `$$x =&`  |                  |
|                     | are refused                         |                  |
+---------------------+-------------------------------------+------------------+
| C12 nested          | Engine: `[[&$a]] = ...` and keyed   | restricted; P9   |
| destructuring by    | nested forms. Here: one level (row  |                  |
| reference           | 47); deeper refused                 |                  |
+---------------------+-------------------------------------+------------------+
| C13 `$GLOBALS`      | Engine: `$GLOBALS` is a read-only   | restricted; P9   |
| write semantics     | copy, element writes allowed,       |                  |
| (8.1)               | `$GLOBALS = ...` a compile error.   |                  |
|                     | Here: element writes supported;     |                  |
|                     | whole-array operations on           |                  |
|                     | `$GLOBALS` refused                  |                  |
+---------------------+-------------------------------------+------------------+
| G-str-offset-write  | Engine: `$s[0] = 'x'` writes one    | restricted; P9   |
| (C5, row 3)         | byte. Here: refused at run time     | (strings II)     |
|                     | with `Error "string offset writes   |                  |
|                     | are not supported yet"`; reads are  |                  |
|                     | in, and row 3's single-byte         |                  |
|                     | in-range writes move with this gap  |                  |
+---------------------+-------------------------------------+------------------+
| G-sort-flags        | Engine: `SORT_NATURAL`,             | restricted; P9   |
|                     | `SORT_FLAG_CASE`,                   | (arrays II)      |
|                     | `SORT_LOCALE_STRING`. Here:         |                  |
|                     | refused with `Error "sort flag N    |                  |
|                     | is not supported yet"`              |                  |
+---------------------+-------------------------------------+------------------+
| G-sort-inconsistent | Engine: `zend_sort` comparison      | restricted; none |
| -cmp                | sequence for a user comparator      | scheduled; P3    |
|                     | that is not antisymmetric after     | severity         |
|                     | the bool retry. Here: the stable    |                  |
|                     | merge sort's sequence; consistent   |                  |
|                     | and bool comparators and all        |                  |
|                     | built-in flags are unaffected       |                  |
+---------------------+-------------------------------------+------------------+
| C14 `ArrayObject`,  | Engine: SPL wrappers sharing        | unsupported; an  |
| `ArrayIterator`     | storage with an array by reference. | SPL slice        |
|                     | Here: deferred                      |                  |
+---------------------+-------------------------------------+------------------+
| C15 iterator        | Engine: the exact outcome of        | restricted; P2   |
| repositioning on    | `zend_hash_iterators_update` for    |                  |
| table rebuild       | every rebuilding builtin. Here: row |                  |
|                     | 26 pins `unset`, append, assignment |                  |
|                     | and `sort`; other rebuilding        |                  |
|                     | builtins during by-reference        |                  |
|                     | iteration are pinned individually   |                  |
|                     | in P2 or stay listed here           |                  |
+---------------------+-------------------------------------+------------------+
| C16 refcount dumps  | Engine: `debug_zval_refcount` and   | unsupported;     |
|                     | `debug_zval_dump` print refcount    | none             |
|                     | numbers. Here: refused (internal)   |                  |
+---------------------+-------------------------------------+------------------+
| C17 destructor      | Engine: fatal errors skip           | restricted; none |
| phase on engine     | destructors. Here: there are no     |                  |
| fatals              | engine fatals; operational halts    |                  |
|                     | skip the phase (row 22)             |                  |
+---------------------+-------------------------------------+------------------+
| C18 serialization   | Engine: `serialize` and             | unsupported; a   |
| of references       | `unserialize` preserving references | serialization    |
|                     | (`R:`, `r:`), `__sleep`,            | slice            |
|                     | `__wakeup`, `__serialize`. Here:    |                  |
|                     | deferred                            |                  |
+---------------------+-------------------------------------+------------------+
| C19 `clone` of      | Engine: engine-specific. Here:      | restricted; P4-c |
| objects holding     | `Closure` clone supported;          |                  |
| `Generator` or      | `Generator` clone is an `Error`, as |                  |
| `Closure` state     | in the engine; pinned by oracle     |                  |
+---------------------+-------------------------------------+------------------+
| C20 references to   | Engine: redeclared static           | restricted; P4   |
| static properties   | properties and reference identity   |                  |
| across inheritance  | across child classes. Here: row 46  |                  |
|                     | covers the declaring class;         |                  |
|                     | inheritance edge cases are pinned   |                  |
|                     | in P4                               |                  |
+---------------------+-------------------------------------+------------------+
| C21 memory and time | Engine: `memory_limit`,             | restricted; none |
| limits              | `max_execution_time`,               | (operational)    |
|                     | `memory_get_usage`. Here: the       |                  |
|                     | deterministic safepoint budget and  |                  |
|                     | no memory accounting;               |                  |
|                     | `memory_get_usage` returns a pinned |                  |
|                     | constant                            |                  |
+---------------------+-------------------------------------+------------------+
| C22 destructor      | Engine: destructor `echo` inside an | specified,       |
| output at shutdown  | unflushed `ob_start` at shutdown.   | listed for       |
| under output        | Here: row 22 flushes after          | visibility       |
| buffers             | destructors; nested unclosed        |                  |
|                     | buffers flush in stack order;       |                  |
|                     | pinned                              |                  |
+---------------------+-------------------------------------+------------------+
| C23 string          | Engine: unobservable. Here: no      | none             |
| interning and `===` | deviation                           |                  |
+---------------------+-------------------------------------+------------------+

### 17.3 Convergence and the blocking rule

**Claimed rows are normative, and a known-incorrect claimed row blocks.** A
row of the transition table is a statement of Zend's behaviour, fixed from the
handler source and **confirmed by the oracle before the slice that claims it
lands**. After landing, a disagreement between a row and the pinned PHP CLI is
a defect of the row, never of the fixture. A finding that a claimed row
contradicts Zend blocks landing of any slice whose support profile includes
that row until either the row is corrected (and its fixture regenerated from
the oracle), or the Architect explicitly moves the behaviour to section 17.2
as a restricted corner with a departure fixture whose expected value is the
recorded deviation, with the owner's acceptance when the corner touches a
Phase 3 exit criterion. Regenerating a fixture to match an incorrect row is
forbidden: oracle fixtures pin behaviour; they do not legitimize a
contradictory normative rule.

The landing gate is therefore: every claimed-row fixture equals the oracle;
every corner's departure fixture equals its recorded deviation; no fixture
failure is unclassified. A new finding lands in one of four bins, in blocking
order:

1. a defect in a claimed row (blocks);
2. a missing row required by an exit criterion or the C4 profile (blocks);
3. a reclassification request, row to corner (an Architect decision recorded
   in the ledger; blocks until decided);
4. a new corner (tracked, non-blocking).

This is the Q6 review rule applied to normative rows, and it becomes the
standing rule of the ledger's PHP section (owner decision B8.13).

## 18. Owner decisions

From the converged text; the labels are the loop's (B8.n).

1. (B8.1) Reference runtime PHP 8.3 at a pinned patch release (recommended)
   versus 8.2.
2. (B8.2) **Strings**: byte-faithful (recommended: PHP semantics are bytes,
   and `strlen` of a two-byte UTF-8 character is 2) versus code-point strings
   with a documented departure. Byte-faithful needs a `data` export for
   byte-string slicing and an output boundary writing raw bytes; code-point
   strings would make every `mb_*` function trivial but break `strlen`,
   `substr`, binary data and `md5` parity.
3. (B8.3) The dynamic-scope profile rule (recommended) versus dict scopes for
   every function (simpler, slower, and it loses the cell path).
4. (B8.4) Warnings as diagnostic-stream records (recommended) versus a guest
   `Warning` exception class; the record model matches ADR 0004 and keeps `@`
   cheap.
5. (B8.5) The deterministic safepoint budget as the `max_execution_time`
   stand-in (recommended) versus refusing the ini.
6. (B8.6) Replace and delete the compiler frontend at P8 (recommended) versus
   keeping the compiler revision of `:yang/php` installed until a later date.
7. (B8.7) The autoload class-map rule (recommended) versus refusing
   `spl_autoload_register` in Phase 3.
8. (B8.8, final) The exact engine model of section 4 for the specified
   fragment, with no profile switch. Withdrawn alternatives: holder counts
   over persistent values (rounds 2 and 3), instances on demand with an
   `:exact-refcount` profile (round 4).
9. (B8.9) The browser demo goes fixture-based (recommended) versus removing
   PHP from the demo.
10. (B8.10) Optional absent includes stay refused at publication until the
    ledger's open item is ruled (recommended).
11. (B8.11) Adopt the support-profile statement and the corner table of
    section 17 as the PHP section of the known-gaps ledger (recommended).
12. (B8.12) C1 cycle collection scheduled after the reclamation work rather
    than inside Phase 3 (recommended).
13. (B8.13) The blocking rule of section 17.3 binding for `yang.php` landings
    (recommended). An open sub-question from the loop: whether the owner's
    acceptance is required for every reclassification, rather than only for
    those touching exit criteria.

## 19. Acceptance-matrix sketch

+-----------------------------+------------------------------------------------+
| Dimension                   | yang.php claims                                |
+=============================+================================================+
| Host                        | CLJ: parse, lower and run; CLJS and CLJD:      |
|                             | packets and rows, run                          |
+-----------------------------+------------------------------------------------+
| Evaluators                  | bundled profile on four; linked profile on     |
|                             | semantic, stack and register until L-b, then   |
|                             | four                                           |
+-----------------------------+------------------------------------------------+
| Parser deployment           | local worker (JVM); configured service;        |
|                             | parserless (rows); a Node local parser is a    |
|                             | later claim via the upstream JavaScript lexer  |
|                             | base, never assumed                            |
+-----------------------------+------------------------------------------------+
| Support claim               | syntax: the PHP 8.3 surface of the pinned      |
|                             | grammar (generics refused); semantics: the     |
|                             | transition table and the P1 to P7 groups;      |
|                             | lowering: executable for the listed arms;      |
|                             | stdlib: the section 9 core, everything else    |
|                             | honestly refused; corners: section 17.2; ticks |
|                             | only after safepoint slice 3                   |
+-----------------------------+------------------------------------------------+
| Input shape                 | mixed PHP text (named by `yang.antlr.md`       |
|                             | 13.2), heredoc with indentation, long          |
|                             | interpolated strings, deep arrays, large class |
|                             | hierarchies with traits, many small included   |
|                             | units, a request-record fixture per corpus     |
|                             | program                                        |
+-----------------------------+------------------------------------------------+
| Laws (`yang.antlr.md` 13.1) | 2, 3, 4, 5, 7 (warnings are data, never        |
|                             | stderr), 9 (PHP CLI parity on stdout and the   |
|                             | rendered diagnostics), 10, 12; Phase 3 exit    |
|                             | criteria 1 to 5 are direct fixtures (P1, P2    |
|                             | and P3, P5, P4, P5)                            |
+-----------------------------+------------------------------------------------+
| Fault                       | a request task cancelled mid-output leaves a   |
|                             | complete response prefix and a cancellation    |
|                             | record, never a half-record; duplicate         |
|                             | delivery of a unit publication is idempotent;  |
|                             | operational halts skip the destructor phase    |
+-----------------------------+------------------------------------------------+

## 20. Design provenance

This document consolidates a nine-round design loop held on 2026-10-17 between
the Architect seat (Fable) and an adversarial reviewer (codex, gpt-6.1-sol).
The round files live in `collab/` (never committed) and are cited by timestamp
prefix:

- Round 1 design `1792197000000-architect-yang-ts-php-designs` (both
  frontends, judged at master `7c143c01`). Review
  `1792200000000-reviewer-ts-php-designs`: **REQUEST CHANGES** with eight P1
  findings (among them PHP reference ownership and the tick and linked-matrix
  dependencies), five P2s (among them PHP semantics against the 8.3 oracle,
  helper token rewrites, demo and REPL lifecycle, missing includes) and one
  P3.
- Round 2 `1792204000000-architect-ts-php-revision`: oracle corrections
  (`static` timing, `%`/`intdiv`, the comparison matrix, `@`), helper
  vendoring and spans, the P7 split, two-stage missing includes, the
  fixture-based demo.
- Rounds 3 and 4 (`1792214000000`, `1792217000000`): holder-count reference
  models, both superseded.
- Round 5 `1792219000000-architect-ts-php-r5`: the reference model re-derived
  from Zend's operations (zvals, `zend_array_dup`, `ASSIGN`, `ASSIGN_REF`, CV
  release), objects counted, the three fixture sources.
- Round 6 `1792223000000-architect-ts-php-r6`: iteration by reference, unset
  fetches, temporary ownership, typed-reference sources, finalization and
  shutdown, `clone`; the support-profile statement and the corner table C1 to
  C23.
- Round 7 `1792224000000-architect-ts-php-r7`: argument acquisition at
  evaluation (pending calls), uninitialized and readonly typed references,
  iterator migration and the internal pointer, readonly reinitialization in
  `__clone`, and the blocking rule.
- Round 8 `1792226000000-architect-ts-php-r8`: the PHP 8.3 oracle results F1
  to F3 and corrections to iterator copies, `new` without a constructor,
  named-argument timing and readonly state.
- Round 9 `1792229000000-architect-ts-php-r9` (the final consolidated text):
  the copy-position lifecycle (adoption without separation, records that
  survive same-table fetches), named-argument validation after the argument's
  own evaluation, and initialization clearing the clone flag.
- Intermediate reviews (`1792212000000` r2-fresh, `1792216000000`,
  `1792218000000`, `1792220000000`, `1792223000000`, `1792224000000`,
  `1792227000000`) each returned REQUEST CHANGES; every finding was accepted
  and answered in the next round.
- Final review `1792230000000-reviewer-ts-php-r9`: **READY (sign-off
  granted)**: "ready to land with its declared supported fragment and tracked
  corners. The stated implementation oracle gates remain required" (section
  4.7).

Where rounds conflict, the latest ruling is the one stated here. Withdrawn
rulings, recorded so that readers of the round files are not misled:
"`__destruct` never called" (round 1; now supported for acyclic garbage);
static initializers evaluated at definition (round 1); `PHP_INT_MIN % -1`
throwing (round 1); the `0 == "" == []` grouping (round 1); the `:refs` set on
array values (round 2); holder counts and demotion at write (rounds 2 and 3);
the `:exact-refcount` profile (round 4); acquisition of arguments at call
entry (round 6); iterator reset to position 0 on assignment (round 6);
"duplication does not carry iterators" (round 7); "a missing constructor is an
error" (round 7); named-argument errors after all arguments (round 7) and
before the argument's own evaluation (round 8); lineage records expiring at
the next fetch (round 8); initialization leaving the clone flag set (round 8).

### Consolidation note

The loop assigns typed-property reference rows (41 to 47, 59) to P3 (round 6)
while classes and typed properties are P4 scope; the converged text does not
order the two. Their fixtures need P4's class machinery, so the slice plan
must either run them at P4 or bring the minimal class surface into P3; this is
left to the Engineer's slice plan and does not change any row.
