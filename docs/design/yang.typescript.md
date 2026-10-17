# yang.typescript: the TypeScript and JavaScript frontend

Status: Designed 2026-10-17. Implementation pending: no part of this frontend
is built, and every slice in section 16 is unstarted. Design by Fable (Lead
System Architect seat); adversarial review by codex (gpt-6.1-sol) over a
nine-round design loop that ended READY with sign-off granted. Section 21
("Design provenance") cites the loop.

Parent design: [`yang.antlr.md`](yang.antlr.md). This document makes concrete
its section 8.7 (Prototype: JavaScript, lexical environments and jobs) and the
JavaScript half of its Phase 1. Companion: [`yang.php.md`](yang.php.md), which
shares the prerequisites of section 2 and the `yang.core` lifts of section 17.
Once accepted, this document governs the detail; `yang.antlr.md` section 8.7
remains the paradigm summary.

Effect bindings follow the doctrine of ADR 0004 ("external effects are
interpreted stream deposits"), which is Proposed and not yet committed;
section 2.4 names what a rejection of that ADR would reopen.

## 1. Scope

JavaScript is unparked as TypeScript: one frontend, one grammar package, one
frontend id `:yang.typescript/antlr` (language `:typescript`), with a dialect
option that selects plain JavaScript. There is no separate
`:yang.javascript/antlr`. The frontend compiles ECMAScript 2022 modules (and,
with the limits of section 3.7, scripts) to the Universal AST. The TypeScript
type layer is parsed and erased, optionally minted as claim rows for the
typecheck observer of [`yin.vm.typecheck.md`](yin.vm.typecheck.md), and never
changes a runtime result.

## 2. Prerequisites binding both frontends

This section is shared with `yang.php.md`, which references it rather than
repeating it.

### 2.1 Defects to fix before a second frontend

These are architectural defects in the current docs and code, not open gaps.
Each is closed by a lift of section 17 or by a dated amendment of
`yang.antlr.md`.

+----+------------------------------------+------------------------------------+
| #  | Defect                             | Closure                            |
+====+====================================+====================================+
| D1 | `yang.antlr.packet/malformed!`     | `validate!` takes the caller's     |
|    | throws a                           | diagnostic namespace (or emits     |
|    | `:yang.python.antlr/malformed-cst` | `:yang.cst/malformed`); lift L2    |
|    | diagnostic from a namespace        |                                    |
|    | documented as language-agnostic    |                                    |
|    | (`yang.antlr.md` 8.5.6 records it  |                                    |
|    | as open), so a TS or PHP unit with |                                    |
|    | a bad packet would report a        |                                    |
|    | Python-named code                  |                                    |
+----+------------------------------------+------------------------------------+
| D2 | `yin.repl.frontends/create-state`  | the binding map carries            |
|    | merges `primitives` from           | `:primitives` and the REPL merges  |
|    | `yang.python.antlr.repl` into      | the selected binding's; lift L4    |
|    | every REPL state regardless of the |                                    |
|    | selected frontend                  |                                    |
+----+------------------------------------+------------------------------------+
| D3 | the installed-binding shape landed | dated amendment: F1 and F2 landed; |
|    | in F2 (`{:parse :lower             | 3.2 is the long-form contract the  |
|    | :lower-session :render :probe      | binding map abbreviates            |
|    | :input}`) is undocumented;         |                                    |
|    | `yang.antlr.md` 3.2 still says     |                                    |
|    | none of its protocol exists, and   |                                    |
|    | 2.1 still describes the closed     |                                    |
|    | `case`                             |                                    |
+----+------------------------------------+------------------------------------+
| D4 | `yang.antlr.md` 8.5.2 says the     | already done (`yang.stage`,        |
|    | stage machinery moves to a neutral | `yang.safepoint`); stale text to   |
|    | namespace "before the second user" | amend                              |
+----+------------------------------------+------------------------------------+
| D5 | `yang.antlr.md` 8.3 lists PHP only | the rows of `yang.php.md` section  |
|    | under class-based OO; its dominant | 10                                 |
|    | paradigm has no row                |                                    |
+----+------------------------------------+------------------------------------+
| D6 | `yang.antlr.md` 8.5 leaves open    | answered per language: TS module   |
|    | whether other languages'           | scope is static and lexical        |
|    | module-level variables stay in the | (cells); script-goal globals per   |
|    | store                              | section 3.7; PHP's global scope is |
|    |                                    | the heap `$GLOBALS` dict. Neither  |
|    |                                    | uses `yin/def` for guest variables |
+----+------------------------------------+------------------------------------+
| D7 | the Python prelude and             | lift L1 before a third and fourth  |
|    | `safepoint.cljc` duplicate the     | copy                               |
|    | emitter machinery (define, strip,  |                                    |
|    | placeholder, wide application,     |                                    |
|    | exports, module-spec resolution,   |                                    |
|    | `admit`); `free-names` and         |                                    |
|    | `defined-keys` are already shared  |                                    |
|    | through `prelude/*`                |                                    |
+----+------------------------------------+------------------------------------+

### 2.2 Shared rulings

- **No new AST tag, no engine change, no codec change** for either language.
  Everything is lowering arms, prelude code over `cell`, `data`, `integer` and
  `stream/poll!`, and composition.
- **Each language's null is its own tagged sentinel** (`yang.antlr.md` 8.11).
  JavaScript has two, PHP has one. None is host `nil`; none is `:py/None`.
- **Numbers**: Jing's existing kind distinction (exact integer versus float64
  content) is reused as the guest's numeric kind. The float64 carrier is
  mandatory on the JavaScript host for every float-kind value (`yang.antlr.md`
  8.5.5). Neither language tags floats a second time the way Python's
  `{:py/float}` does: neither needs Python's type-versus-kind split.
- **Portability**: the ANTLR parser is JVM-only for both. Node and Dart
  consume CST packets and rows, as Python does today. Prelude-level and
  packet-level tests run on every evaluator on JVM, Node and Dart;
  source-level tests run on the JVM. The recorded ClojureDart traps
  (reader-conditional ordering, `for` over chunked seqs, multi-key `assoc` on
  nil, unary minus) apply to the new preludes' Clojure helpers.
- **Linked-profile matrix**: linked execution runs on the semantic, stack and
  register VMs; the AST walker under the linked profile waits on L-b, exactly
  as the Python linked harness states. Every slice's matrix is therefore:
  bundled profile on four evaluators, linked profile on three until L-b lands,
  after which the matrix widens with no slice change. This is an explicit
  prerequisite, not a nondependency.
- **Dependencies on open work**: both designs depend on the landed linked
  prelude pattern (P2, P3, I1 to I6). They do not depend on L-a, L-e, the DHT
  64 KiB cap or C3's remaining fixtures. They depend on L-b only through the
  linked walker lane above, and PHP's statement ticks depend on safepoint
  slice 3 (`yang.php.md` section 12).

### 2.3 Lifecycle design rules

Three TS mechanisms (the ES module record of section 11, the binder table of
section 12, the evidence record of section 12.7) obey two design rules.
**Identity is minted at declaration**: the declaring operation allocates the
cell or datom and fixes every coordinate that names the thing; no executing
step re-mints. **Commitment is per group and atomic**: a member of a strongly
connected group commits only when the whole group does, and a failure in an
unfinished group fails every unfinished member with the same cause. The
mechanisms share these rules, not a namespace: no `yang.core.lifecycle`
abstraction is proposed. The PHP lifecycle follows the engine's own operations
instead (`yang.php.md` section 4).

### 2.4 Dependency on ADR 0004

ADR 0004 is Proposed and untracked on the reviewed tree. Both designs apply
its doctrine. The rulings that rest on it are named so that a rejection of the
ADR shows what it reopens: request records and response records (PHP), bound
effect surfaces with declaration units (both), timers and host completions as
stream events (TS), and the refusal of ambient filesystem and package
resolution (both). Nothing else depends on it.

## 3. Paradigm specification

### 3.1 The compilation profile

The profile is data, because "TypeScript semantics" without emit flags is not
a semantics:

```clojure
{:yang.ts/dialect  :ts | :js              ; default :ts
 :yang.ts/goal     :module | :script      ; default :module
 :yang.ts/types    :erase | :claims       ; default :erase
 :yang.ts/compiler {:typescript "5.6.3"   ; reference compiler, owner A12.2
                    :target :es2022
                    :module :esnext
                    :isolatedModules true
                    :verbatimModuleSyntax true
                    :preserveConstEnums true
                    :useDefineForClassFields true
                    :experimentalDecorators false}
 :yang.ts/checker  <checker descriptor address> | nil
 :yang.ts/strict   {:strictNullChecks bool, :noImplicitAny bool}}
                                          ; checker parameters only
```

Rulings:

1. **Runtime semantics are ECMAScript's**, pinned at edition ES2022; the
   reference runtime is a pinned Node LTS (exact version: owner decision
   A12.2). The type layer never changes a runtime result; the erasure law of
   section 4.6 is its test.
2. **The compiler flags are profile data.** The reference compiler is
   consulted only for the purposes listed in section 15, never for runtime
   behaviour.
3. **The `:js` dialect refuses type syntax** with
   `:yang.ts/type-syntax-in-js`; it does not silently erase it. The superset
   claim is not exact, and `:js` safety comes from that refusal plus the
   generic-call disambiguation of section 5.3 (`a<b>(c)` is a call with type
   arguments in `:ts` and the relational `(a < b) > (c)` in `:js`).
4. **Strict mode is always on** for modules. Script-goal sloppy semantics are
   out of scope; `with`, implicit globals and duplicate parameters are refused
   (`:js/sloppy`).

### 3.2 undefined, null and the TDZ

- **Two guest sentinels**: `:js/undefined` and `:js/null`, distinct.
  `undefined` is the value of a missing property, a missing argument, an
  uninitialized `var`, and a function with no `return`. `null` is only the
  literal and what the prelude returns where the specification says null
  (`Object.getPrototypeOf(Object.prototype)`, regex misses once RegExp
  exists).
- `==` makes the two equal to each other and to nothing else; `===`
  distinguishes them; `typeof null` is `"object"` and `typeof undefined` is
  `"undefined"`.
- **`:js/tdz`** is a third sentinel, never guest-visible: the content of a
  `let`, `const` or `class` cell before its declaration runs. Reading it
  raises `ReferenceError`. This is the lesson of Python's `cell/new
  :py/unbound`: a lowering needs its own sentinel for unbound state.

### 3.3 Numbers

- **A Number is binary64.** Every Number value is Jing float64 content: a host
  double on JVM and Dart, the `dao.jing/float64` carrier on the JavaScript
  host, built at the producer as `yang.antlr.md` 8.5.5 mandates. The literal
  `1` lowers to `{:type :literal :value (cbor/float64 1.0)}`, so `1` and `1.0`
  have one address on every host (they are one JS value).
- Arithmetic in the prelude is `(data/float64 (op (data/float-value a)
  (data/float-value b)))`, Python's float seam reused, with no yield or
  safepoint inside it. `-0` keeps its sign (tag 27 preserves it). Every NaN is
  Jing's one NaN, which is conformant because JS observes NaN payloads only
  through typed arrays (deferred).
- **BigInt** is the untagged exact integer (C3 carriers: native within range,
  `BigInt`/`clojure.lang.BigInt` beyond). Because every Number is float64
  content, an exact integer is unambiguously a BigInt, and `typeof` is the
  Jing kind. Mixed `1n + 1` is a `TypeError` in the prelude, as in JS. A `1n`
  literal is a bare integer literal within 2^53 and `(integer/parse "<hex>"
  16)` beyond, Python's literal rule. The numeric-key canonicalization of
  8.5.4 does not apply: JS has no numeric dict keys (section 3.5).

### 3.4 Strings: one value, one representation, by normalization

Jing refuses unpaired UTF-16 surrogates (`cbor.cljc` `check-text`), so a host
string cannot carry every JS string value. The ruling:

- A JS string whose code-unit sequence is well-formed UTF-16 is `{:js/str s}`
  with `s` a host string.
- A JS string with any unpaired surrogate is `{:js/units [u0 u1 ...]}`, a
  vector of code units (integers 0 to 65535).
- **Normalization is an invariant** enforced by the prelude's string
  constructors: a well-formed result is always the `:js/str` form and an
  ill-formed one is always the `:js/units` form. Every operator that produces
  a string goes through `js/mk-str`, which checks well-formedness and picks
  the form. Concatenating the two halves of a surrogate pair therefore yields
  a `:js/str` again.
- `length`, indexing and `charCodeAt` are code units, which is what all three
  hosts' native strings already are; the `data` module's code-point functions
  serve `codePointAt` and `for-of`.
- Property keys use the normalized value, so `o["\uD800"]` is a `:js/units`
  key: plain data, legal in Jing, ordered by the ordered map's string
  partition.
- Both forms are plain persistent data, so literals, publication, snapshots
  and continuations carry them unchanged. Addresses are a function of the
  normalized form only.
- **`data` module additions** (one version bump; Python unaffected):
  `str-unit-length`, `str-unit-at`, `str-units` (string to code-unit vector),
  `units->str` (vector to host string, or a refusal datum when ill-formed),
  `well-formed-utf16?`. Code-unit slicing is `(data/str-units s)`, then
  `subvec`, then `js/mk-str`.

Every string-producing path (template literals, `String.fromCharCode`,
`JSON.parse` of a lone-surrogate escape, `split("")`, `replace`) goes through
`js/mk-str`; the T1 and T2 tests of section 16 pin this.

### 3.5 Property keys and own-key order

- **ToPropertyKey**: a number key converts to its Number::toString (`o[1]`,
  `o["1"]` and `o[1.0]` are one key; `o[-0]` is `"0"`; `o[NaN]` is `"NaN"`), a
  symbol stays a symbol (a cell ref), and everything else goes through
  ToString.
- **Own-key order** is the specification's: integer-index keys ascending, then
  string keys in insertion order, then symbols in insertion order. The
  ordered-map helper (index map plus order vectors, the pattern of Python's
  `py/dict-*`) is built with a pluggable key function and an integer-index
  partition.
- `Map` and `Set` use SameValueZero on the raw value (float64 content and
  refs), with no coercion. Host maps are never iterated (`yang.antlr.md`
  8.11).

### 3.6 Objects, functions and `this`

What the prototype runtime adds over Python's class model: property
descriptors, accessor properties, the prototype chain instead of a `:base`
chain, `this` as an explicit argument to every call, functions as cells
(functions are objects), and ordering rules on own keys. A class is a
constructor function cell plus a prototype cell; the VM has no class concept
(`yang.antlr.md` 9.4).

### 3.7 Script-goal globals

Under `:yang.ts/goal :script`, `var` and function declarations are properties
of the heap global object; top-level `let`, `const` and `class` live in a
separate global declarative environment (cells with TDZ) that shadows the
global object and is not reachable by property access. The REPL's persistent
session keeps both, as the ES analogue of Python's persistent `__main__`.

### 3.8 for-in enumeration

The specification's `EnumerateObjectProperties` informative algorithm, pinned:
walk the object, then its prototype chain; for each object, snapshot its own
string keys in own-key order; yield a key if it is enumerable, not already
visited (shadowing), and still present when visited (deleted-before-visit keys
are skipped). Keys added during enumeration are not visited, which is V8's
behaviour within the specification's latitude and a recorded choice.

### 3.9 Jobs run to completion

There is no thread switch at a safepoint, ever. A job runs to completion; the
scheduler runs the next microtask only when the current job's continuation
returns to the queue loop. Section 13 gives the job interpreter.

## 4. The type layer

### 4.1 Three profiles: erase, claims, checking

- **`:erase`** (default): every type-only construct is parsed and produces no
  UAST node and no side-table entry.
- **`:claims`**: erasure as above, and the frontend additionally mints
  declared-type claim rows on the `claims` medium of `yin.vm.typecheck.md`
  section 2.2 (`[occurrence :yin.sig/type <type-value> t m]`, `m` =
  "yang.typescript, declared"). The frontend never checks. Section 12 gives
  the claim coordinates.
- **Checking** is not a frontend mode: it is a composition that attaches the
  typecheck observer with a checker in `report` or `gate` topology (section 3
  of the typecheck design). The checker is a parameter (decision 8 there); the
  TS frontend ships none in Phase 1. This is `yang.antlr.md` 8.9's "TypeScript
  checking is a plugin-owned stage".

**A type error is a verdict row**, `:yin.typecheck/outcome :refused` with
`:defects [{:rule :path}]`, never a frontend diagnostic. It lives on
`typecheck-events`, outside the diagnostics model of `yang.antlr.md` section
10, which may index it like any other datom. "Checked" certainty is minted
only by an `:accepted` verdict.

### 4.2 null and undefined under strictNullChecks

`:yang.ts/strict` flags, `strictNullChecks` among them, are checker parameters
recorded as profile data so that a composition pins them. They change which
claims a checker refuses; they change no row and no runtime behaviour. Under
`:erase` they are inert.

### 4.3 The type surface per slice

What is checked, erased or refused: nothing is checked by the frontend;
everything type-only is erased (and claimed under `:claims`); decorators and
JSX are refused; enums, namespaces and parameter properties are runtime
constructs with their own arms. The table is the target surface; the T0 parse
fixtures (section 5.2) are the claim, and a construct whose fixture does not
parse at T0 exit moves to refused.

+---------------------------------+---------------------------------+----------+
| Construct                       | Treatment                       | Slice    |
+=================================+=================================+==========+
| Variable, parameter, return and | erased; claims under `:claims`  | T8       |
| property annotations; `as`,     |                                 |          |
| `satisfies`, non-null `!`,      |                                 |          |
| `<T>x` cast; definite           |                                 |          |
| assignment `!`                  |                                 |          |
+---------------------------------+---------------------------------+----------+
| `interface`, `type` alias,      | erased, no rows; claims under   | T8       |
| `declare` (var, function,       | `:claims`                       |          |
| class, module), `abstract`      |                                 |          |
| members                         |                                 |          |
+---------------------------------+---------------------------------+----------+
| Generics: type parameters,      | erased                          | T8       |
| arguments, constraints,         |                                 |          |
| defaults                        |                                 |          |
+---------------------------------+---------------------------------+----------+
| Unions, intersections, literal  | erased; utility types are       | T8       |
| types, conditional types with   | ordinary aliases in             |          |
| `infer`, mapped types,          | `js-lib.d.ts`, nothing special  |          |
| template-literal types,         | in the frontend                 |          |
| `typeof` type queries, indexed  |                                 |          |
| access, utility types           |                                 |          |
+---------------------------------+---------------------------------+----------+
| `public`, `private`,            | compile-time only, erased;      | T8       |
| `protected`, `readonly`,        | `#private` fields and the class |          |
| `override` modifiers; `abstract | itself are runtime (T3)         |          |
| class`                          |                                 |          |
+---------------------------------+---------------------------------+----------+
| `import type`, `export type`,   | erased; no linker edge          | T8       |
| inline `type` specifiers        |                                 |          |
+---------------------------------+---------------------------------+----------+
| Parameter properties            | runtime: lowered to the field   | T9       |
| (`constructor(private x:        | assignment the specification    |          |
| number)`)                       | implies                         |          |
+---------------------------------+---------------------------------+----------+
| `enum` and local `const enum`   | runtime: the object the         | T9       |
|                                 | reference compiler emits        |          |
|                                 | (forward and, for numeric       |          |
|                                 | members, reverse mapping);      |          |
|                                 | computed initializers evaluated |          |
|                                 | at run time in order; member    |          |
|                                 | references are property reads,  |          |
|                                 | never inlined literals          |          |
+---------------------------------+---------------------------------+----------+
| Ambient `const enum` in runtime | refused                         | T9       |
| position                        | `:yang.ts/ambient-const-enum`   |          |
+---------------------------------+---------------------------------+----------+
| `namespace`/`module` blocks     | runtime: an IIFE over an object | T9       |
|                                 | cell merging declarations;      |          |
|                                 | instantiated namespaces only    |          |
+---------------------------------+---------------------------------+----------+
| Narrowing, control-flow         | checker concern; no lowering    | checker  |
| analysis                        | effect                          |          |
+---------------------------------+---------------------------------+----------+
| Declaration files `.d.ts`       | a unit lowering to zero program | T10      |
|                                 | rows and a claims artifact only |          |
|                                 | (section 12.5)                  |          |
+---------------------------------+---------------------------------+----------+
| Decorators (legacy and TC39)    | refused `:yang.ts/unsupported   | deferred |
|                                 | :decorators`; deferred, never   |          |
|                                 | "automatically a Yin macro"     |          |
|                                 | (`yang.antlr.md` 7.2)           |          |
+---------------------------------+---------------------------------+----------+
| JSX                             | refused; a later dialect option | deferred |
+---------------------------------+---------------------------------+----------+

### 4.4 Emit-dependent constructs

- **Type-only imports and exports must be marked** (`import type`, `export
  type`, or inline `type` specifiers), which is what `verbatimModuleSyntax`
  demands of the reference compiler. An unmarked `import {X}` is a runtime
  import edge with a runtime binding; if the exporter has no runtime export
  `X`, that is the link error of section 11.3, not an elision. Import elision
  does not exist in this frontend, so the erasure law is not conditional on a
  whole-program type analysis.
- **`const enum`**: a local `const enum` lowers exactly as `enum`, an object,
  which is the reference compiler's own behaviour under `preserveConstEnums`
  with `isolatedModules`. An ambient `const enum` (from `declare const enum`
  or any declaration unit) referenced in runtime position is refused, which
  `isolatedModules` also makes an error. Consequently no lowering ever needs a
  declaration unit's values, and declaration units stay optional for
  execution.

### 4.5 The boundary story: types at the edges

- **The runtime has no types.** No row, cell content, prelude value or effect
  carries a TS type. A type annotation subtree produces no UAST node and no
  side-table entry: the claims medium is where types go, and a side table
  would make two homes.
- **The prelude's typed surface** is a claims unit, `js-lib.d.ts`: a
  declaration file compiled by the same frontend against the `js` prelude's
  manifest, whose claims name the prelude's export occurrences (section 12).
  It is published beside the prelude manifest as datoms, not inside it
  (annotating does not change identity: typecheck decision 2). A composition
  that runs a checker pins the claims unit beside the prelude address in its
  runtime profile; one that does not never fetches it.
- **Consumed modules**: a module's `.d.ts` is a declaration unit compiled
  against that module's manifest (section 12.5). Under `:claims` the frontend
  also emits claims on the importer's binding occurrences, so a checker sees
  both ends.
- **Host effects**: Yin-side type stubs for effect bindings (ADR 0004
  surfaces) are declaration units whose occurrences are the effect-binding
  module's binders. Host values crossing in are plain data classified into JS
  values by the binding's lowering; the checker sees only the declared claim
  on the binding. There is no runtime type test at the edge; a binding that
  wants a runtime guard writes one in JS.

### 4.6 The erasure law

For every T1 to T7 corpus program `P`, a generator produces `annotate(P)` by
inserting annotations and erased declarations **before** every loop, function,
class and generated-temporary site (compound assignment, destructuring,
optional chain, template literal) and between statements, plus `as`,
`satisfies` and `!` wrappers on operands. Then `rows(annotate(P)) = rows(P)`
and the roots are equal, on three hosts. A second law pins the canonical
renaming pass (section 6.3): it is idempotent and host-independent. For T9
constructs, which are not erasable, the comparison is against Node running the
reference compiler's emit under the pinned flags.

## 5. The grammar route and parser worker

### 5.1 The package

`antlr/typescript/manifest.edn`, in the shape of `antlr/python3/manifest.edn`:
repository `antlr/grammars-v4`, path `javascript/typescript`
(`TypeScriptLexer.g4`, `TypeScriptParser.g4`, MIT), an exact upstream revision
pinned at T0, BLAKE3 per file, `.g4` vendored verbatim with the README notice.
The Java helpers `Java/TypeScriptLexerBase.java` and
`Java/TypeScriptParserBase.java` are fetched by digest with the same
licence-provenance block as Python's two helpers; they carry no notice header,
so redistribution is blocked until that is resolved, as for Python. Generation
uses the pinned ANTLR 4.13.2, `-Dlanguage=Java -package
yang.typescript.antlr.gen -no-listener -no-visitor`, with digests pinned.

### 5.2 The grammar patch set `ts-g4-1` and the fixture gate

The upstream parser grammar has no conditional-type production, no mapped
types, no template-literal types, no `import type`/`export type`, no
`satisfies`, and indexed access `T[K]` only by accident of `ArrayPrimType`.
The package therefore ships the patch set `ts-g4-1` through the existing
exact-text `:patches` mechanism extended to `.g4` files: `import type`/`export
type` and inline `type` specifiers; `satisfies`; conditional types with
`infer`; mapped types with `as` clauses and `+/-readonly`, `+/-?`;
template-literal types; `typeof` type queries; indexed access as its own
production; the dialect predicate for generic calls. The set is ASCII,
versioned and re-tested on regeneration (`yang.antlr.md` 4.2).

**Acceptance is by fixture, not by inventory.** T0 pins one parse fixture per
advertised construct, among them `type X<T> = T extends string ? 1 : 2`,
`import type {X} from "x"`, `x satisfies Y`, `{ [K in keyof T]?: T[K] }`, a
template-literal type, `typeof v`, and `a<b>(c)` in both dialects. A construct
whose fixture the patched grammar does not parse at T0 exit moves from erased
to refused `:yang.ts/unsupported-type-syntax`, recorded in the support
profile.

### 5.3 Predicates and the dialect flag

The parser uses six base-class methods (`notLineTerminator`, `n`, `p`,
`notOpenBraceAndNotFunctionAndNotInterface`, `lineTerminatorAhead`,
`closeBrace`); the lexer base tracks template-string depth and the
regex-versus-division context. These are internal library execution
(`yang.antlr.md` 4.3), not callbacks. Upstream provides Java, C# and C++ bases
only, so the local parser is JVM-only and Node and Dart go through packets or
a configured service (`yang.antlr.md` 4.1).

`a<b>(c)` cannot be resolved by alternative order. The generated parser takes
a **dialect flag on the worker**, read by a semantic predicate in the pinned
parser base class: in `:ts` the parse is the reference compiler's (type
arguments when `<...>` is followed by `(`), in `:js` it is relational. This is
worker configuration, not a callback. The grammar README's two known
ambiguities (`primaryType` decision 11, `singleExpression` decision 236) are
handled by pinning the prediction mode and pinning the parse of the ambiguous
forms by fixture.

### 5.4 The worker and the REPL probe

`yang.antlr.cst/make-worker` with the profile `{:grammar-id :make-lexer
:make-parser :start (.program p)}`; nothing in `cst.clj` changes. ASI is the
grammar's (`eos` predicates), so a REPL completeness probe cannot use "error
at EOF": the probe reparses the snapshot with a trailing `\n;` sentinel and
answers `:incomplete` only when the error set moves to EOF; otherwise the user
submits explicitly (`yang.antlr.md` 5.3).

### 5.5 CST inventory highlights

From the pinned grammar: `program`, `sourceElements`, the `statement` family;
`variableStatement`/`variableDeclaration` with `bindingPattern`;
`functionDeclaration`, `arrowFunctionDeclaration`,
`generatorFunctionDeclaration`,
`classDeclaration`/`classTail`/`classElement`/`propertyMemberDeclaration`; the
`singleExpression` labelled alternatives (`MemberDotExpression`,
`OptionalChainExpression`, `TemplateStringExpression`, `YieldExpression`,
`AwaitExpression`, `ClassExpression`); the `importStatement`/`exportStatement`
families; and the **parse-only subtrees** `typeAnnotation`, `type_`,
`typeParameters`, `typeArguments`, `interfaceDeclaration`,
`typeAliasDeclaration`, `accessibilityModifier`, `abstractDeclaration`,
`decoratorList`, plus the productions `ts-g4-1` adds.

## 6. CST to UAST

### 6.1 Context and marks

The lowering context is Python's shape: `:lower` self-reference, `:loop {:brk
:cont}`, `:ret`, `:this`, `:gen`, `:async`, `:class`, `:scope`. Loop and
function lambdas carry `:yang/site` marks for safepoint insertion.

### 6.2 Consumed rules

The lowering's `consumed-rules` set names every type-only rule, so that
`default-arm` can never reach one: an unhandled type rule is a frontend defect
(`:yang.ts/unhandled-rule`), not an unsupported construct. `as`, `satisfies`,
`!` and casts lower to their operand.

### 6.3 Generated names and canonical renaming

The name supply is `%<prefix><cst-id>` (`%` cannot begin a JS identifier).
Those names are canonical row slots (`:lambda [:params :syms]`), so inserting
an erased annotation would shift preorder CST ids and move rows. The ruling is
**canonical renaming before projection**, as a shared pass:

- `yang.core.names/canonicalize` walks the finished UAST in a deterministic
  preorder over the row grammar's slots and renames every symbol whose name
  begins with `%` to `%<n>` by first-occurrence order. It is capture-safe
  because `%` names are never guest names and are already unique per lowering.
  `%_` (discard) and the reserved `yang.safepoint/_` are excluded.
- The pass is a pure function of the lowered tree, and the tree is a function
  of the runtime constructs alone once type-only subtrees are consumed; hence
  rows are a function of the runtime tree.
- The TS lowering keeps CST ids internally and applies the pass in
  `lower-packet`, `module-packet` and `module-spec`, before `mark-tails` and
  projection. Site marks (metadata) survive the rename, and the safepoint
  stage reconstructs from rows that already carry canonical names.
- Python is not changed: the pass is not applied to Python in L2. Adopting it
  there is an owner-scheduled golden-moving slice with no correctness need
  (owner decision A12.8).

## 7. Scope analysis

`yang.typescript.antlr.scope`. Hoisting is the whole job:

- Per function scope: `var` and function declarations are hoisted to the
  function top; their cells are allocated with `:js/undefined`, and functions
  are initialized first.
- Per block: `let`, `const` and `class` cells are allocated with `:js/tdz`.
- `for (let ...)` gets a fresh cell per iteration, closed over correctly.
- The `arguments` object exists only in non-arrow functions that mention it.
- `this` is lexical for arrows: a parameter threaded from the enclosing
  non-arrow function. In strict code a bare call's `this` is `undefined`.
- Derived constructors hold `this` in a TDZ cell until `super()` returns.
- Every binding is a cell (mob D5 holds: closures and continuations need it).
- Module scope is static and lexical; section 11 allocates module environments
  at link time.

## 8. The lowering-arms inventory

+---------------+------------------------------+-------------------------------+
| Group         | Arms                         | Lowering                      |
+===============+==============================+===============================+
| Expressions   | literals; template literals  | ordinary `:application` of    |
|               | (`js/to-string` per          | `js/*`                        |
|               | substitution; tagged         |                               |
|               | templates in T5);            |                               |
|               | identifiers; member and      |                               |
|               | index access; optional       |                               |
|               | chaining (`js/get` with a    |                               |
|               | short-circuit `if` on        |                               |
|               | nullish); `new`; calls       |                               |
|               | (`js/call f this args`);     |                               |
|               | spread in calls and arrays;  |                               |
|               | `typeof`, `void`, `delete`,  |                               |
|               | `in`, `instanceof`;          |                               |
|               | arithmetic, bitwise,         |                               |
|               | logical, `??`; comparison    |                               |
|               | (`js/loose-eq`,              |                               |
|               | `js/strict-eq`, `js/compare` |                               |
|               | with ToPrimitive);           |                               |
|               | assignment and compound      |                               |
|               | assignment (receiver and key |                               |
|               | evaluated once into          |                               |
|               | `%o`/`%k`); `++`/`--`;       |                               |
|               | comma; conditional; arrow    |                               |
|               | and function expressions;    |                               |
|               | class expressions; object    |                               |
|               | literals (shorthand,         |                               |
|               | computed keys, methods,      |                               |
|               | accessors, spread); array    |                               |
|               | literals with holes          |                               |
+---------------+------------------------------+-------------------------------+
| Destructuring | array and object patterns in | `js/iter-destructure`,        |
|               | declarations, parameters and | `js/get` chains               |
|               | assignment, with defaults    |                               |
|               | and rest                     |                               |
+---------------+------------------------------+-------------------------------+
| Statements    | `var`/`let`/`const`, `if`,   | loops: self-applied lambda    |
|               | `for`, `for-in` (section     | with a `:loop` site; escapes: |
|               | 3.8), `for-of` (iterator     | `js/call-ec` with             |
|               | protocol), `while`/`do`,     | `unwind-to`; `finally`:       |
|               | `switch` (strict-equality    | `js/try-finally`              |
|               | chain, fallthrough by        |                               |
|               | sequencing), labelled        |                               |
|               | `break`/`continue`,          |                               |
|               | `return`, `throw`,           |                               |
|               | `try`/`catch`/`finally`      |                               |
|               | (optional catch binding),    |                               |
|               | block, labelled, empty,      |                               |
|               | expression                   |                               |
+---------------+------------------------------+-------------------------------+
| Functions     | declarations, defaults,      | `js/make-function name code   |
|               | rest, `arguments`, `this`    | this-mode proto-slot`, a cell |
|               | modes, `new.target`          |                               |
+---------------+------------------------------+-------------------------------+
| Classes       | `class`, `extends`           | `js/make-class`: constructor  |
|               | (including `extends null`),  | function cell plus prototype  |
|               | `constructor`, `super()` and | cell; `super` before `this`   |
|               | `super.m`, methods, static   | via the TDZ cell              |
|               | members, accessors, fields   |                               |
|               | (public, `#private`,         |                               |
|               | static), static blocks       |                               |
+---------------+------------------------------+-------------------------------+
| Generators    | `function*`, `yield`,        | the landed continuation       |
|               | `yield*`                     | machinery:                    |
|               |                              | `js/make-generator`,          |
|               |                              | `js/gen-switch`,              |
|               |                              | `js/yield-raw`; iterator      |
|               |                              | objects                       |
|               |                              | (`next`/`return`/`throw`)     |
|               |                              | over the same cell as         |
|               |                              | `yang.antlr.md` 8.5.3, with   |
|               |                              | its flag-cell pattern and its |
|               |                              | accepted fixed retention      |
|               |                              | (ruling 7)                    |
+---------------+------------------------------+-------------------------------+
| Async         | `async function`, `await`,   | T6: `await` suspends through  |
|               | `for await`, async           | the generator core; the job   |
|               | generators                   | queue is prelude (section 13) |
+---------------+------------------------------+-------------------------------+
| Modules       | `import`/`export` forms,     | section 11                    |
|               | `import()` with a literal    |                               |
|               | specifier                    |                               |
+---------------+------------------------------+-------------------------------+
| Type layer    | every type-only rule in      | sections 4.3, 4.4             |
|               | `consumed-rules`;            |                               |
|               | `as`/`satisfies`/`!`/casts   |                               |
|               | return their operand;        |                               |
|               | `enum`, `namespace`,         |                               |
|               | parameter properties         |                               |
+---------------+------------------------------+-------------------------------+

Refused with stable `:yang.ts/unsupported` codes: `with`; `eval` and `new
Function` (a compilation request later, as for PHP `eval`); decorators; JSX;
`import.meta`; regex literals and `RegExp` in Phase 1 (a portable engine is a
large slice); tagged templates until T5; labelled `continue` across functions
(a syntax error); getters on `arguments`; Proxy and Reflect until after T7;
`Symbol.asyncIterator` until T6; typed arrays; `SharedArrayBuffer` (forbidden
by "no shared mutable state"); top-level `await` (`:top-level-await`, section
11.6).

## 9. The prelude inventory

Module name `js` (reserved, non-dotted, as `py` is); hook prelude `jssp`; a
single definition list with two emitters (lifted in L1). `js/init!` allocates
the intrinsics once per task (`%Object.prototype%`, `%Function.prototype%`,
`%Array.prototype%`, the error constructors, the well-known symbols, the
global object, the job-queue cells, the module registry `js.rt/modules` and
evaluation stack `js.rt/eval-stack`) and flips `js.rt/state` to `:js/ready`
last.

Python's 3,770-line prelude splits roughly into 1,000 lines of shareable
mechanism and 3,000 of semantics; a JS prelude is dominated by abstract
operations rather than numeric text.

+---------------------------+-----------------------------------+--------------+
| Area                      | Content                           | Est. lines   |
+===========================+===================================+==============+
| Abstract operations       | ToPrimitive (with                 | 450          |
|                           | `Symbol.toPrimitive` and the      |              |
|                           | `valueOf`/`toString` order),      |              |
|                           | ToNumber (string grammar),        |              |
|                           | ToString (Number::toString via    |              |
|                           | `integer/float-digits` and the    |              |
|                           | shortest round trip the integer   |              |
|                           | module already supplies),         |              |
|                           | ToBoolean, ToPropertyKey,         |              |
|                           | SameValue, SameValueZero,         |              |
|                           | IsStrictlyEqual, IsLooselyEqual,  |              |
|                           | ToInt32, ToUint32                 |              |
+---------------------------+-----------------------------------+--------------+
| Objects                   | `[[Get]]`, `[[Set]]`, `[[Has]]`,  | 500          |
|                           | `[[Delete]]`, `[[OwnKeys]]` over  |              |
|                           | descriptors and the prototype     |              |
|                           | chain; `Object.*` (keys, assign,  |              |
|                           | create, defineProperty, freeze,   |              |
|                           | getPrototypeOf, entries,          |              |
|                           | fromEntries)                      |              |
+---------------------------+-----------------------------------+--------------+
| Functions and classes     | call and construct, `this` modes, | 250          |
|                           | bound functions, `new.target`,    |              |
|                           | super dispatch, `instanceof`      |              |
|                           | (`Symbol.hasInstance` deferred)   |              |
+---------------------------+-----------------------------------+--------------+
| Arrays                    | methods on `items` with holes:    | 450          |
|                           | push, pop, shift, unshift, slice, |              |
|                           | splice, concat, indexOf,          |              |
|                           | includes, join, map, filter,      |              |
|                           | reduce, forEach, find, sort       |              |
|                           | (stable, comparator through       |              |
|                           | `js/call`), flat;                 |              |
|                           | `Array.isArray`, `Array.from`     |              |
+---------------------------+-----------------------------------+--------------+
| Strings                   | the code-unit surface of section  | 300          |
|                           | 3.4 and the `String.prototype`    |              |
|                           | core (slice, substring, indexOf,  |              |
|                           | split by string, trim, pad,       |              |
|                           | replace by string, ASCII case     |              |
|                           | mapping)                          |              |
+---------------------------+-----------------------------------+--------------+
| Iteration                 | iterator protocol, generators     | 250          |
|                           | (shared core), destructuring      |              |
|                           | helpers, `for-of`, `for-in`       |              |
+---------------------------+-----------------------------------+--------------+
| Errors and control        | `js/throw`, the handler stack     | 150          |
|                           | (shared pattern), try/finally,    |              |
|                           | completion through escapes; the   |              |
|                           | Error hierarchy                   |              |
+---------------------------+-----------------------------------+--------------+
| Numbers                   | `Number.*`; pure `Math.*` (abs,   | 250          |
|                           | floor, ceil, round, trunc, sign,  |              |
|                           | min, max, pow via `py/fpow`'s     |              |
|                           | pinned squaring rule, sqrt where  |              |
|                           | `data` supplies it); `parseInt`,  |              |
|                           | `parseFloat`; `Math.random` is an |              |
|                           | effect                            |              |
+---------------------------+-----------------------------------+--------------+
| JSON                      | `JSON.stringify` and `JSON.parse` | 300          |
|                           | (specification grammar, key       |              |
|                           | order, `toJSON`, well-formed      |              |
|                           | stringify of lone surrogates)     |              |
+---------------------------+-----------------------------------+--------------+
| Promises and jobs         | promise states, `then` chaining,  | 300          |
|                           | the microtask queue in a cell,    |              |
|                           | `queueMicrotask`; `async`/`await` |              |
|                           | over the generator core;          |              |
|                           | `setTimeout` as an effect through |              |
|                           | `stream/poll!` outcomes           |              |
+---------------------------+-----------------------------------+--------------+
| Modules                   | module records, `js/link-module`, | in the above |
|                           | `js/evaluate-module`, namespace   |              |
|                           | objects, the dynamic-import job   |              |
|                           | (section 11)                      |              |
+---------------------------+-----------------------------------+--------------+
| Map, Set, WeakMap, Symbol | ordered map with SameValueZero    | 150          |
|                           | keys; WeakMap as a Map            |              |
+---------------------------+-----------------------------------+--------------+
| console and boundary      | `console.log` appends snapshots   | 150          |
|                           | to an out cell; the host renderer |              |
|                           | formats them as Node's            |              |
|                           | `util.inspect` default does for   |              |
|                           | the corpus subset (a parity law,  |              |
|                           | as `float-repr` has)              |              |
+---------------------------+-----------------------------------+--------------+

The prelude carries no types; `js-lib.d.ts` is its claims unit (section 4.5).
Until the `yrt` shared prelude is scheduled (owner decision A12.5), the TS
prelude copies Python's mechanism patterns under `js/` with JS payloads (a
`catch` receives any value, not an exception instance).

## 10. Value encoding and paradigm-table rows

### 10.1 Value encoding (runtime profile data)

+------------------------------------+-----------------------------------------+
| JS type                            | Representation                          |
+====================================+=========================================+
| undefined, null                    | `:js/undefined`, `:js/null` (and the    |
|                                    | non-guest `:js/tdz`)                    |
+------------------------------------+-----------------------------------------+
| boolean                            | host boolean                            |
+------------------------------------+-----------------------------------------+
| number                             | float64 content (carrier on the         |
|                                    | JavaScript host)                        |
+------------------------------------+-----------------------------------------+
| bigint                             | exact integer (C3 carriers)             |
+------------------------------------+-----------------------------------------+
| string                             | `{:js/str s}` when well-formed UTF-16,  |
|                                    | else `{:js/units [u ...]}` (section     |
|                                    | 3.4)                                    |
+------------------------------------+-----------------------------------------+
| symbol                             | cell `{:js/type :symbol :description    |
|                                    | d}` (identity); well-known symbols      |
|                                    | pre-allocated by `init!`                |
+------------------------------------+-----------------------------------------+
| object                             | cell `{:js/type :object :proto          |
|                                    | ref-or-:js/null :props ordered-map      |
|                                    | :extensible? bool}`; a property slot is |
|                                    | a data descriptor `{:value :writable    |
|                                    | :enumerable :configurable}` or an       |
|                                    | accessor `{:get :set :enumerable        |
|                                    | :configurable}`                         |
+------------------------------------+-----------------------------------------+
| function                           | cell `{:js/type :function :code closure |
|                                    | :this-mode m :home h :fields f :props p |
|                                    | :proto q}`; `prototype` is an ordinary  |
|                                    | own property                            |
+------------------------------------+-----------------------------------------+
| array                              | cell `{:js/type :array :items vector    |
|                                    | :props p}`; `length` derived; holes are |
|                                    | `:js/hole`                              |
+------------------------------------+-----------------------------------------+
| class                              | a function cell plus a prototype cell;  |
|                                    | no class concept in the VM              |
+------------------------------------+-----------------------------------------+
| generator, promise, Map, Set, Date | cells with their own `:js/type`; `Date` |
|                                    | only via a time effect                  |
+------------------------------------+-----------------------------------------+
| module record, namespace object    | cells (section 11.2)                    |
+------------------------------------+-----------------------------------------+
| error                              | an ordinary object cell whose `:proto`  |
|                                    | is an error prototype; `stack` is       |
|                                    | derived at the boundary from position   |
|                                    | side tables, like Python's `tb_lineno`  |
+------------------------------------+-----------------------------------------+

### 10.2 Paradigm rows for `yang.antlr.md` 8.3

```text
+----------------------+--------------------------------------+-------------+
| Paradigm             | Universal AST target                 | Reference   |
+======================+======================================+=============+
| Prototype / dynamic  | Objects, functions, arrays and       | JavaScript, |
| (finalized)          | symbols are cells holding descriptor | TypeScript  |
|                      | maps linked by :proto refs; every    | (one        |
|                      | binding a cell (hoisting and TDZ     | frontend,   |
|                      | sentinels); `this` an explicit       | the type    |
|                      | argument; escapes and generators on  | layer       |
|                      | continuations; jobs run to           | erased or   |
|                      | completion over a prelude queue      | claimed)    |
|                      | reading completion streams; module   |             |
|                      | link and evaluation are prelude      |             |
|                      | records; type facts are claim rows   |             |
|                      | beside code, never rows              |             |
+----------------------+--------------------------------------+-------------+
| JS/TS number model   | Number = Jing float64 content on     |             |
| (new row)            | every host (carrier mandatory on JS, |             |
|                      | 8.5.5); BigInt = exact integer (C3   |             |
|                      | carriers); kind is typeof; one NaN;  |             |
|                      | -0 preserved; property keys are      |             |
|                      | strings or symbols by ToPropertyKey; |             |
|                      | Map/Set keys by SameValueZero        |             |
+----------------------+--------------------------------------+-------------+
```

## 11. The import and module story

ES modules only in Phase 1.

### 11.1 Names and compiled shape

A TS unit compiled as module `./a/b.ts` becomes linker module `jsm.a$b` (the
specifier mangled injectively, `$` as in `pym`). Its tree:

```clojure
(do (require 'js)
    (yin/def spec {:specifier "./a/b" :goal :module
                   :bindings {"x" :let, "f" :function,
                              "v" :var, "C" :class}
                   :imports  [{:from "./c" :import "y" :as "y"}
                              {:from "./d" :namespace "D"}]
                   :exports  {"x" "x", "default" "%default",
                              "y" {:re-export "./c" "y"}}
                   :star-exports ["./e"]})
    (yin/def init (fn [%env] <assign hoisted functions into %env>))
    (yin/def body (fn [%env] <lowered module body>)))
;; :yin.module/exports #{spec init body}
```

`spec` carries no binder paths; the binder table is a publication artifact
(section 12.3), so changing the export set does not move the module's code
root. The module store holds only `spec`, `init` and `body` (or bundle
exports), immutable after install, first link wins. Every lifecycle record
below is a heap cell declared and driven by prelude code in the importing task
(C4 ruling 1: install delivers code, instantiation is the task's own
evaluation). Two tasks importing one module have independent records, as
Python's module objects are per task.

### 11.2 The module record

A cell in `js.rt/modules`, keyed by resolved specifier, one registry per task:

```clojure
{:js/type :module :spec s
 :lc/status :unlinked | :linking | :linked
          | :evaluating | :committed | :failed
 :env {name cell}                ; declared at link
 :namespace ref | nil            ; declared at link
 :dfs-index n :dfs-ancestor n    ; during evaluation only
 :error e}                       ; :failed only
```

### 11.3 Link (declare)

`js/link-module`, over the static graph from `spec` (`:imports`,
`:star-exports`), depth first:

1. Deliver each module through the I4-a thunk and create its record in
   `:linking`. A delivery refusal is a link-time `SyntaxError`-class error
   before any body runs.
2. **Declare environments** for every module of the graph before resolving
   anything: one cell per top-level binding, `:js/tdz` for `let`, `const` and
   `class` and for function bindings until step 3, `:js/undefined` for `var`.
3. Run every module's `init`, filling hoisted function cells. Functions close
   over their own `%env`, so a cyclic call into a hoisted export of a module
   whose body has not run resolves.
4. Resolve imports: an import binding's env entry **is the exporter's cell**
   (a live binding by shared cell); `import * as ns` receives the exporter's
   namespace object, an exotic frozen object whose property reads go through
   the env cells; `export * from` resolves transitively with the
   specification's ambiguity rule. **A missing export is a link error raised
   here**, before any body runs. Records become `:linked`.

Link is idempotent: a record already `:linked` or beyond is skipped, and only
`:unlinked` records are touched. A link failure restores every record it
created to `:unlinked`; no partial environment survives, and a later correct
import links afresh.

### 11.4 Evaluate (commit per strongly connected component)

`js/evaluate-module` runs the specification's `InnerModuleEvaluation` with an
explicit stack in a cell (`js.rt/eval-stack`):

- Entering a `:linked` module: status `:evaluating`, assign `dfs-index` and
  `dfs-ancestor` from a counter, push.
- For each required module in order: if `:failed`, rethrow its `:error`; if
  `:committed`, continue; if `:evaluating` (a cycle), lower this module's
  `dfs-ancestor` to the minimum of both; otherwise recurse, then take the
  minimum of the ancestors.
- Run `body`. If it throws, every record on the stack from the thrower upward
  becomes `:failed` with **the same error object**, the stack is cut there,
  and the error propagates.
- After `body`, if `dfs-index` equals `dfs-ancestor`, the module is the
  component root: pop the stack down to and including it and mark every popped
  member `:committed`. Otherwise it stays on the stack and commits with its
  root.

TDZ reads during a cycle raise `ReferenceError`, which fails the component.
Environment cells, hoisted functions and namespace objects are committed at
link; `:committed` is reached only when the component root pops; promises
settle only at the end of a dynamic job.

### 11.5 The operation matrix

Columns are the status of the target record at the moment the operation
reaches it.

+--------------------------------------+---------------------------------------+
| Operation                            | Status and effect                     |
+======================================+=======================================+
| Static import, link phase            | `:unlinked`: deliver, create,         |
|                                      | allocate env, run `init`, resolve,    |
|                                      | recursively. `:linking`: a static     |
|                                      | cycle; use the already-allocated env, |
|                                      | no recursion. Any later status: skip  |
+--------------------------------------+---------------------------------------+
| Static import, evaluate phase        | `:linked`: recurse (push, index, run, |
|                                      | take the ancestor minimum).           |
|                                      | `:evaluating`: a cycle; lower the     |
|                                      | importer's ancestor, no recursion.    |
|                                      | `:committed`: continue. `:failed`:    |
|                                      | rethrow `:error`, failing the         |
|                                      | importer's component. `:unlinked`,    |
|                                      | `:linking`: impossible (link          |
|                                      | completed first)                      |
+--------------------------------------+---------------------------------------+
| Body throws                          | every record on the stack from the    |
|                                      | thrower upward becomes `:failed` with |
|                                      | this error; propagate                 |
+--------------------------------------+---------------------------------------+
| Body returns at the component root   | pop to and including self; every      |
|                                      | popped record becomes `:committed`    |
+--------------------------------------+---------------------------------------+
| Body returns below the root          | stays `:evaluating` on the stack      |
+--------------------------------------+---------------------------------------+
| `import(spec)` expression            | create a promise and enqueue a job,   |
|                                      | whatever the status; nothing links or |
|                                      | evaluates synchronously               |
+--------------------------------------+---------------------------------------+
| `import()` job                       | `:unlinked`: link (a link error       |
|                                      | rejects and restores records),        |
|                                      | evaluate as a new top-level DFS,      |
|                                      | settle. `:linked`: evaluate as a new  |
|                                      | top-level DFS, settle. `:committed`:  |
|                                      | resolve with the namespace.           |
|                                      | `:failed`: reject with `:error`.      |
|                                      | `:linking`, `:evaluating`: impossible |
|                                      | by the job rule (asserted)            |
+--------------------------------------+---------------------------------------+
| Two `import()` jobs of one module    | the first links and evaluates; the    |
|                                      | second settles from the final status; |
|                                      | each has its own promise, one         |
|                                      | namespace object, body run once       |
+--------------------------------------+---------------------------------------+
| Static import of a module evaluated  | link skips it; evaluation sees        |
| earlier by a job                     | `:committed` or `:failed`             |
+--------------------------------------+---------------------------------------+
| REPL `(reset)`                       | every record is dropped with the task |
+--------------------------------------+---------------------------------------+

### 11.6 Dynamic import, the job rule and top-level await

A dynamic `import(spec)` with a literal specifier performs no linking or
evaluation synchronously: it creates a promise and enqueues a **job** that
runs link and evaluate from `spec`'s record as a fresh top-level evaluation
and settles from the record's final status. A computed specifier is a
diagnostic (`import_module` ruling 10 reused). The scheduler drains jobs only
when the evaluation stack is empty; a host-effect park inside a body (possible
on this VM, not in the engine) leaves the body evaluating, so no job runs
meanwhile. Consequences: a module that calls `import("./self")` and then
throws `e` has status `:failed e` when the job runs, so the promise rejects
with that `e`; a module that imports itself and succeeds resolves to its own
namespace after its component commits.

**Top-level `await`** is refused in Phase 1 (`:yang.ts/unsupported
:top-level-await`). The asynchronous evaluation variant of the algorithm is a
later slice; with it refused, evaluation is synchronous and the algorithm of
section 11.4 is complete.

**Settlement ordering is narrowed.** The frontend claims, and T7 tests: the
import promise settles in a job that runs after the current synchronous
evaluation completes; settlement reflects the module's final status (the same
error object for failures); `.then` handlers on it run after all synchronous
code of the current job. The frontend does not claim Node's tick count
relative to user promise chains (Node's order comes from its host loading
hook, which the specification leaves to the host). The support profile records
"dynamic-import settlement tick count differs from Node" as a departure, with
a fixture asserting this frontend's order and documenting Node's.

### 11.7 Publication

Unchanged in mechanism: `jsm.<mangled>`, I5 SCC bundles through the lifted
publisher. An SCC bundle exports one `spec`/`init`/`body` triple per member,
and the alias modules re-export them. The link graph the prelude walks is the
spec graph, so bundling is invisible to instantiation. The publication SCC and
the evaluation SCC are computed from the same static edges and coincide for
static roots, but evaluation never assumes it: a dynamic root starts its own
DFS. Python's lazy bodies are not reused for TS instantiation; only the
publication mechanics are. Static imports are pinned in the manifest
`:requires` exactly as Python's; the I4-a statement-time delivery thunks are
not used to run static imports at statement time, because ES links the whole
graph before running any body.

### 11.8 Refused resolution

CommonJS `require`, `module.exports`, `node_modules` resolution, package.json
`exports` maps, URL specifiers and `import.meta.url` are refused: there is no
filesystem (`yang.antlr.md` 5.1, ADR 0004). A bare specifier is a name in the
publication unit set or a published module name, nothing else. Node and
browser APIs are effect packages (`yang.antlr.md` 8.7), bound per ADR 0004 one
slice at a time, each with a declaration unit.

### 11.9 The REPL binding

Installed like Python's F3: parse (JVM) plus `lower`/`lower-session`,
`:render` (the loop prints the value of the submission's last top-level
bare expression statement through it), and `:primitives` (for example
`js-report`) once D2 is fixed. The persistent session keeps the script-goal
global object and the global declarative environment (section 3.7).

## 12. Claims and declaration units

The claim entity is `[canonical-root path]` (`yin.vm.typecheck.md` section
2.2). This section fixes which root, which path, how a declaration unit finds
them, and how late claims are rechecked.

### 12.1 The owning root

The owning root of a binder is the root of the tree that textually contains
it, declared by the publisher. For a standalone module it is the module tree
root `A`. For an SCC member `m_i` it is the bundle root `A_scc`: its binders
sit under the `(yin/def 'm<i>-init ...)` and `(yin/def 'm<i>-body ...)`
definitions. For the `js` prelude it is the prelude module tree root. An alias
module owns no binders: its tree holds references to bundle exports, so no
path from an alias root reaches a binder.

### 12.2 The occurrence path vocabulary

A path is a vector of row-grammar slot steps as `yang.safepoint` walks them
(`pos` for a `:node` slot, `[pos i]` for a `:nodes` element), extended by one
terminal step for binders: `[:params i]` names the i-th parameter symbol of
the `:lambda` the path reaches, and `[:def-key]` names the literal key operand
of a `yin/def` application. Node occurrences stay bare paths. The typecheck
observer indexes claims by this reified pair; `[:params i]` and `[:def-key]`
are the two binder forms.

### 12.3 The binder table

A publication artifact, declared by the publisher beside the manifest as
datoms (like the site sets of C4 ruling 14), never a row and never in `spec`:

```clojure
[<manifest-address> :yang.ts/binders {"x" [A_owner path] ...}]
[<manifest-address> :yang.ts/owning-root A_owner]
[<manifest-address> :yang.ts/bundle <bundle-manifest-address>]  ; members
```

Only the publisher knows the bundle layout and member order, so it computes
the table; the I5 construction is deterministic, so the table is reproducible.
The lifted publisher (L3) gains a binder-table emission hook that the frontend
supplies; Python supplies none.

### 12.4 Annotation claims and the type environment

- Annotated bindings, parameters, return types (on the function's lambda
  occurrence) and class members (on the class's constructor-function binder,
  with a member map) get `[[A_owner path] :yin.sig/type <type-value> t m]`.
- **Type-only declarations** (interfaces, aliases, ambient declarations,
  generic parameters) have no runtime occurrence. They form the module's
  **type environment**, one claim on an occurrence that exists: `[[A_owner
  path-to-spec-def-key] :yin.sig/type-env {"Foo" <type-value> ...} t m]`, on
  the `[:def-key]` of the module's `spec` definition, or for an SCC member of
  its `m<i>-spec` definition. Imported type-only names are recorded in the
  environment as `{:import "./x" "T"}`. Resolving named-type references
  against it is the checker's job (typecheck decision 8: the type language is
  the checker's).

### 12.5 Declaration units

Compiling a `.d.ts` (a consumed module's declarations, `js-lib.d.ts`, an
effect binding's stubs) requires the target's manifest address as an explicit
pinned input of the compilation request: a `.d.ts` is compiled **against** a
module, never floating. The compiler reads the target's binder table and mints
each declaration's claim on `[A_owner path]`. The unit compiles to zero
program rows and a claims artifact:

```clojure
{:yang.ts.claims/target <manifest address>
 :root   A_owner
 :claims [[path attribute value] ...]}
```

### 12.6 The claims watermark

Every claims producer appends a unit's claims to the `claims` medium before
the program envelope of the packet that owns the binders, and that envelope
carries `:yang.claims/t`, the claims cursor after the last claim (typecheck
section 2.2 item 1 allows this through the batch envelope). For an SCC the
publisher appends the bundle's claims, then the bundle envelope with
`:yang.claims/t`, then the alias envelopes, which carry no claims and need no
watermark (their verdict is trivially `:accepted` or `:dynamic` per checker
parameter). Under `:erase` nothing is appended and the key is absent.

### 12.7 The evidence record and rechecks

The stream contract mints cursors only from `:dao.stream/oldest`,
`:dao.stream/newest` or a transport-specific anchor, forbids fabricated
positions and keeps cursors opaque. Replay therefore uses a **stream-minted
cursor saved before the first relevant append**, never a position. The
evidence record is a ledger datom in the composition that owns the `claims`
medium; it is not content-addressed, since a cursor is not content and is
valid only for its stream:

```clojure
[<root A> :yang.claims/evidence
 {:yang.claims/stream <logical-stream identity of the claims medium>
  :yang.claims/cursor <minted by (stream/cursor claims
                       :dao.stream/newest) before the first claim
                       about A was appended>
  :yang.claims/root   A}]
```

- **The appender protocol**, `yang.core.claims/append-claims!`, used by every
  claims producer (the TS frontend under `:claims`, a `.d.ts` compile, a
  future inference observer): before appending claims about `A`, look up `A`'s
  evidence record; if absent, mint the `newest` cursor, append the evidence
  datom, then append the claims; if present, append the claims. Minting
  precedes the first claim, so the cursor is at or before it. A crash between
  minting and the first claim leaves a harmless anchor. An initially
  unannotated module has no record at publication and acquires one when its
  first `.d.ts` arrives.
- **Racing appenders**: two appenders that both find no record both mint; the
  earlier evidence datom (ledger order) wins, and the later appender's claims
  still lie after the earlier cursor, so replay is complete either way.
- **Recheck**: a composition-level requester (not the frontend, not the
  observer) appends the byte-identical tree packet to `typecheck-in` with the
  envelope `{:yang.claims/evidence <the record> :yang.claims/t <current newest
  cursor>}`. The observer reads from the saved cursor to `t` on a fresh handle
  to the same stream, indexes rows for `A`, evaluates, cites the claim refs it
  used, frees the index, and discards the handle; its own cursor is untouched,
  so typecheck section 7's prohibitions hold. A `gap` on the saved cursor
  (eviction) yields `:refused :evidence-unavailable`, an honest verdict, never
  a silent accept. In `gate` the recheck packet is forwarded or withheld like
  any other (a verdict cannot un-run a program); in `report` the new verdict
  supersedes by time.
- **Cross-composition replay is not claimed**: another composition re-mints
  evidence by recompiling declaration units (claims are regenerable from
  source: derive, don't persist).

### 12.8 Identity

No claim, binder table or evidence record touches rows. Annotating moves no
address, and changing the export set does not move `A` either, because the
table lives beside the manifest.

## 13. Safepoints and the job interpreter

- **Safepoints** (`yang.antlr.md` 8.5.2): `:loop` and `:call` marks only, with
  hook prelude `jssp` providing `jssp/loop` and `jssp/call`, which poll a
  signal cursor through `stream/poll!` and throw a guest `js.rt/Interrupt`.
  The interrupt is not catchable by `catch` unless the profile says so; by
  default it terminates the job. JS has no `RecursionError`: a `RangeError:
  Maximum call stack size exceeded` is available as a profile-pinned depth
  through the same hooks when a composition wants Node parity (default off;
  continuations are heap data). `:line` marks are inserted for a debugger
  profile only.
- **The job interpreter** (`yang.antlr.md` 8.7): a microtask-queue cell and a
  macrotask-queue cell in the prelude. Host completions (timers, I/O outcomes)
  are events on composition-supplied streams read with `stream/poll!` between
  jobs; ordering is deterministic for a given event order. An empty queue
  finishes without parking. Observable ordering (`await` tick counts, `then`
  chains, `queueMicrotask` interleavings) is a named corpus group, subject to
  the dynamic-import narrowing of section 11.6.

## 14. Diagnostics

Compile-time diagnostics: `:yang.ts/syntax` (ANTLR, with the recovery-free
strict profile); `:yang.ts/unsupported {:construct}`;
`:yang.ts/unsupported-type-syntax` (constructs dropped at T0 exit);
`:yang.ts/type-syntax-in-js`; `:yang.ts/ambient-const-enum`;
`:yang.ts/unhandled-rule` (a frontend defect); `:yang.ts/early-error` for the
specification's early errors the grammar does not catch (duplicate `let`,
`const` without an initializer, `await` outside async, invalid assignment
targets, `super` outside methods). Link errors (missing or ambiguous exports,
refused delivery) are guest `SyntaxError`-class errors raised by the link
phase. Runtime errors are guest `Error` objects. Type errors are verdict rows
(section 4.1). Operational failures stay operational (`yang.antlr.md` section
11).

## 15. The differential-corpus plan

- **Behaviour oracle**: a pinned Node LTS (exact version in the support
  profile) runs each corpus program, and its stdout is the expectation, as
  CPython 3.9.6 is for Python. `console.log` formatting parity is a law over a
  fixture set, like `float-text-v1`.
- **The reference compiler** at the pinned version and flags is used in two
  ways only: for T9 constructs, its emitted JS is the semantic reference (then
  run on Node); and under `:claims` with a future checker, `tsc --noEmit`
  diagnostics are the reference for which programs a strict checker refuses.
  Never for behaviour otherwise.
- **Corpus groups**: coercion tables (every `==` pair of the specification
  table, ToNumber strings, ToPrimitive order); scoping (hoisting, TDZ,
  per-iteration `let`, closures in loops); `this` (bare calls, methods,
  arrows, bound functions, `call`/`apply`); prototypes (shadowing, `in`,
  `hasOwnProperty`, key order, `for-in`); classes (super chains, fields,
  statics, private); exceptions (`finally` ordering with `return`/`break`);
  generators (send, throw, return, `yield*`, early return running `finally`);
  async (tick ordering); modules (live bindings, cycles, TDZ across modules,
  component failure, dynamic import); numbers (`0.1+0.2`, `-0`, `2**53+1`,
  BigInt mixing errors, `toString` of every double in the float-digits
  fixture); strings (lone surrogates, code-unit operations, normalization);
  erasure (the law of section 4.6); enums and namespaces against the reference
  compiler's emit.
- **Recorded departures** are fixtures whose expected value is the recorded
  deviation, so the harness distinguishes a departure from a defect (section
  18).

## 16. Slice sequencing and acceptance contracts

Order: the lifts L1 to L4 land first (address-neutral for Python), then T0 to
T10. JS core first and type layers last, because the erasure law needs a
complete JS core to be meaningful. Every acceptance line runs bundled on four
evaluators and linked on three (section 2.2) on JVM, Node and Dart unless
noted; source-level parsing is JVM-only.

+-------+----------------------------------+-----------------------------------+
| Slice | Scope                            | Acceptance                        |
+=======+==================================+===================================+
| L1    | lift the emitter machinery       | Python `py` and `pysp` manifest   |
|       | (define, strip with a namespace  | goldens unchanged byte for byte;  |
|       | parameter, placeholder, wide     | `prelude_parity_test` green       |
|       | application, exports,            |                                   |
|       | module-spec resolution, `admit`) |                                   |
|       | to `yang.core.prelude`;          |                                   |
|       | `prelude.cljc` and               |                                   |
|       | `safepoint.cljc` call it         |                                   |
+-------+----------------------------------+-----------------------------------+
| L2    | lift `uast.cljc` and the         | every Python golden unchanged;    |
|       | lowering and stage helpers       | `packet.cljc` emits the caller's  |
|       | (section 17); fix D1; add        | namespace; renaming idempotent    |
|       | `yang.core.names/canonicalize`   | and host-independent              |
|       | (not applied to Python) and the  |                                   |
|       | five `data` string exports       |                                   |
+-------+----------------------------------+-----------------------------------+
| L3    | lift `publication.cljc` and the  | `publication_test` and            |
|       | module helpers; binder-table     | `dht_import_test` unchanged       |
|       | emission hook                    |                                   |
|       | (frontend-supplied, Python       |                                   |
|       | none); `append-claims!` in       |                                   |
|       | `yang.core.claims` with the      |                                   |
|       | evidence record                  |                                   |
+-------+----------------------------------+-----------------------------------+
| L4    | fix D2: binding `:primitives`,   | `repl_test` green; Python REPL    |
|       | the REPL merges the selected     | output unchanged; a second toy    |
|       | binding's                        | frontend with its own             |
|       |                                  | `:primitives`; `(lang)` installs  |
|       |                                  | the selected binding's primitives |
|       |                                  | and drops `:frontend-session`;    |
|       |                                  | `(reset)` isolates frontends      |
+-------+----------------------------------+-----------------------------------+
| T0    | grammar package, pinned upstream | spans are byte offsets;           |
|       | revision, `ts-g4-1`, dialect     | template-literal and              |
|       | predicate, worker profile, CST   | regex-context tokens export; the  |
|       | export, licence block            | two README ambiguities pinned by  |
|       |                                  | fixture; ASI error packets; one   |
|       |                                  | parse fixture per advertised type |
|       |                                  | construct; `a<b>(c)` in both      |
|       |                                  | dialects; the refusal list        |
|       |                                  | produced at exit                  |
+-------+----------------------------------+-----------------------------------+
| T1    | JS core: literals, operators,    | coercion, scoping and exceptions  |
|       | coercion,                        | groups; `console.log(0.1+0.2)`    |
|       | `var`/`let`/`const`/TDZ,         | prints `0.30000000000000004` on   |
|       | functions, closures,             | all hosts; float-address goldens  |
|       | `if`/loops/`switch`/labels,      | of the `js` prelude and a program |
|       | `try`/`catch`/`finally`, strict  | root agree on three hosts (the    |
|       | equality, the two-form strings,  | 8.5.5 gate); a `"\uD800"` literal |
|       | the `js` prelude skeleton with   | lowers, projects, round-trips     |
|       | `init!`, the `console.log`       | through rows on three hosts and   |
|       | boundary, bundled and linked     | reads back as `:js/units`;        |
|       | profiles                         | indexing and `.slice(0,1)` of a   |
|       |                                  | surrogate pair, `.charCodeAt`,    |
|       |                                  | `.length`, and concatenation      |
|       |                                  | re-forming the pair; a            |
|       |                                  | continuation holding a            |
|       |                                  | `:js/units` value lifts and       |
|       |                                  | lowers byte for byte              |
+-------+----------------------------------+-----------------------------------+
| T2    | objects, prototypes,             | prototypes and `this` groups;     |
|       | descriptors, `this`, arrays,     | key-order fixtures; `a === b` on  |
|       | `Object.*`, `for-in`, `delete`,  | cells is same-cell on every host; |
|       | accessors                        | the pinned `for-in` enumeration   |
|       |                                  | fixture (inherited keys,          |
|       |                                  | shadowing, deleted-before-visit); |
|       |                                  | a `:js/units` property key set,   |
|       |                                  | read and enumerated;              |
|       |                                  | `JSON.stringify` of a lone        |
|       |                                  | surrogate                         |
+-------+----------------------------------+-----------------------------------+
| T3    | classes, `extends`, `super`,     | classes group; a subclass of      |
|       | fields, `#private`, static,      | `Error` caught by `catch` with    |
|       | `instanceof`, the Error          | correct `instanceof` across       |
|       | hierarchy, `new.target`          | modules of one task               |
+-------+----------------------------------+-----------------------------------+
| T4    | destructuring, spread and rest,  | destructuring fixtures;           |
|       | default parameters, `arguments`, | evaluation-order fixtures         |
|       | template literals, optional      |                                   |
|       | chaining, `??`, single           |                                   |
|       | evaluation in compound           |                                   |
|       | assignment                       |                                   |
+-------+----------------------------------+-----------------------------------+
| T5    | generators and the iterator      | generators group; reachable-heap  |
|       | protocol, `for-of`, `yield*`,    | measurement after repeated        |
|       | tagged templates, Map, Set,      | suspension (ruling 7); Node       |
|       | Symbol                           | parity on `return()` running      |
|       |                                  | `finally`                         |
+-------+----------------------------------+-----------------------------------+
| T6    | promises, microtasks,            | async group tick-ordering         |
|       | `async`/`await`, async           | fixtures; an empty queue finishes |
|       | generators, the timer effect     | without parking                   |
|       | binding                          |                                   |
+-------+----------------------------------+-----------------------------------+
| T7    | ES modules: `jsm` emission,      | the modules group: cyclic         |
|       | module records, link and SCC     | hoisted-function call before the  |
|       | evaluation, live bindings,       | exporter's body (works); exported |
|       | dynamic `import()` as a job,     | `let` across a cycle              |
|       | top-level `await` refused,       | (`ReferenceError`, fails the      |
|       | publication, DHT import          | component); missing export (link  |
|       | parserless on Node and Dart      | error, no body side effect,       |
|       |                                  | records restored, a later correct |
|       |                                  | import links); a throwing body    |
|       |                                  | imported twice (same error        |
|       |                                  | object, body once); `a -> b -> a` |
|       |                                  | with `b` done and `a` throwing    |
|       |                                  | (both `:failed`, static and       |
|       |                                  | dynamic re-imports rethrow or     |
|       |                                  | reject with the same object, no   |
|       |                                  | re-run); `b` failing under `a`    |
|       |                                  | (`b` is `:failed`, not            |
|       |                                  | `:evaluating`); all members       |
|       |                                  | `:committed` after the root pops; |
|       |                                  | star re-export chain with a       |
|       |                                  | shadowing named export; live      |
|       |                                  | binding after `export let x;      |
|       |                                  | x++`; two `import()` of one       |
|       |                                  | module (one namespace, body       |
|       |                                  | once); `import("./self")` then    |
|       |                                  | throw (rejects with that error)   |
|       |                                  | and then success (resolves after  |
|       |                                  | commit); `import()` of a failed   |
|       |                                  | component's member; a dynamic     |
|       |                                  | graph with committed modules (no  |
|       |                                  | re-run, side-effect counter); an  |
|       |                                  | `import()` inside a body does not |
|       |                                  | run before the body returns; the  |
|       |                                  | settlement-order departure        |
|       |                                  | fixture; the top-level-`await`    |
|       |                                  | refusal; a JVM-published `jsm.m`  |
|       |                                  | imports on Node and Dart;         |
|       |                                  | refusals of `require` and bare    |
|       |                                  | `node_modules` specifiers         |
+-------+----------------------------------+-----------------------------------+
| T8    | type-erasure arms, the `:js`     | the erasure law of section 4.6    |
|       | refusal, `import type`,          | over every T1 to T7 program;      |
|       | `verbatimModuleSyntax` behaviour | renaming idempotence; an unmarked |
|       |                                  | type-only import is a link error; |
|       |                                  | `:js` refusal fixtures            |
+-------+----------------------------------+-----------------------------------+
| T9    | `enum` and local `const enum` as | output equals Node running the    |
|       | objects, `namespace`, parameter  | reference compiler's emit under   |
|       | properties                       | the pinned flags; the ambient     |
|       |                                  | `const enum` refusal fixture      |
+-------+----------------------------------+-----------------------------------+
| T10   | `:claims` emission, binder       | every binder-table entry resolves |
|       | tables, declaration units,       | into its owning root and          |
|       | `js-lib.d.ts`, evidence records, | `[:params i]` lands on the        |
|       | the typecheck observer in        | parameter whose symbol is the     |
|       | `report` topology with the T1    | canonicalized binder (a test      |
|       | claims-consistency checker       | walks the tree), including a      |
|       |                                  | two-member SCC resolving into     |
|       |                                  | `A_scc`; `js-lib.d.ts` claims     |
|       |                                  | resolve to the prelude's          |
|       |                                  | `[:def-key]` occurrences;         |
|       |                                  | annotating appends claims with    |
|       |                                  | the same roots and paths and      |
|       |                                  | moves no address; `export let x:  |
|       |                                  | number` plus a `.d.ts` claiming   |
|       |                                  | `x: string` is refused citing     |
|       |                                  | both claim refs; a free name with |
|       |                                  | no claim yields `:dynamic` both   |
|       |                                  | ways; `report` leaves evaluator   |
|       |                                  | output unchanged; the `claims-t`  |
|       |                                  | barrier delays evaluation of a    |
|       |                                  | packet whose claims are late;     |
|       |                                  | alias packets pass with no        |
|       |                                  | claims; evidence fixtures: (a) a  |
|       |                                  | claim-free module, then a late    |
|       |                                  | `.d.ts` mints the record and a    |
|       |                                  | recheck cites it, then a second   |
|       |                                  | conflicting `.d.ts` is refused    |
|       |                                  | citing both; (b) the four-step    |
|       |                                  | publish, accept, late `.d.ts`,    |
|       |                                  | refuse sequence on an annotated   |
|       |                                  | module; (c) eviction below the    |
|       |                                  | saved cursor yields               |
|       |                                  | `:evidence-unavailable`; (d) two  |
|       |                                  | racing appenders, earlier datom   |
|       |                                  | wins, replay complete             |
+-------+----------------------------------+-----------------------------------+

Argument-type checking (`f("x")` against `(n: number) => void`) and arity at
guest call sites are deferred to a checker slice outside both frontends: every
guest call lowers to `js/call f this args`, so no guest binder is the operator
of a UAST application and the T1 checker's arity rule has no meeting point.
The generic arity rule is exercised by a hand-built UAST fixture in
`typecheck_contract_test`, proving observer plumbing, not TS. The recommended
known-gaps ledger row is "guest-aware call meeting points for the checker",
owner seat typecheck.

Deferred after T10: decorators, JSX, RegExp, Proxy and Reflect, typed arrays,
Intl, `Date` beyond the time effect, top-level `await` and asynchronous module
evaluation, a real checker.

## 17. The yang.core lift enumeration

What TypeScript pulls from `yang.python` into shared namespaces (lifts L1 to
L4; PHP uses the same namespaces):

- `yang.core.prelude`: the emitter machinery (define, strip, placeholder, wide
  application, exports, module-spec resolution) and `admit` (L1).
- `yang.core.uast`: from `yang.python.antlr.uast`, renamed (L2).
- `yang.core.lower`: `gen`, `kids`, `rules`, `rule-kids`, `site`, `app*`,
  `contains-rule?`, and the diagnostic builders `unsupported!`, `syntax!`,
  `default-arm` with the namespace and refusal table as parameters (L2).
- `yang.core.stage`: `diagnostic`, `lower-transform`, `open-stage`,
  `step-stage`, parameterized by the ok-check, the lower function and the
  diagnostic namespace (L2).
- `yang.core.names`: `canonicalize` (L2, section 6.3).
- `yang.antlr.packet`: D1 fixed (L2).
- `yang.core.module`: `module-symbol` (prefix parameter), `require-node`,
  `module-tree`, `declare-free-name`, and the `module-spec` and
  `scc-module-specs` shapes (L3).
- `yang.core.publication`: `publication-order`, `publish-units!`, with
  `imported-names`, `module-spec` and `scc-module-specs` injected, plus the
  binder-table emission hook (L3).
- `yang.core.claims`: `append-claims!` and the evidence record (L3, exercised
  in T10).
- `yin.repl.frontends`: binding `:primitives` (L4).

**Not lifted now**: the prelude *definitions* (the escape machinery `ctx`,
`frame`, `raise`, `unwind-to`, `try`, `try-finally`, `call-ec`; the generator
switch core; the ordered dict; the float seam; the module runtime skeleton;
the safepoint poll and deliver). Moving those into a shared linked module
(`yrt`) is a prelude-profile bump that moves every Python golden and changes
`py`'s requirement pins. It is the right end state but an owner-scheduled
slice (A12.5), not a TS dependency; PHP would be its second consumer.

## 18. The tracked fragment

The `yang.typescript` support profile claims exactly the constructs of the
landed slices, each with its corpus group equal to the pinned Node (and, for
T9, to Node running the reference compiler's emit). The ECMAScript
specification is itself the normative algorithm text, so the design has no
analogue of PHP's opcode-derived transition table; by law 9 of `yang.antlr.md`
13.1, a corpus disagreement with the pinned Node outside the departure list
below is a defect of the frontend, not a departure. Everything outside the
claimed fragment is in one of three lists, each pinned by a fixture:

**Refused** (diagnostic, law 12): the refusals of section 8; ambient `const
enum`; type syntax in `:js`; type constructs dropped at T0 exit; top-level
`await`; CommonJS and ambient resolution (section 11.8).

**Recorded departures and choices** (the fixture's expected value is the
recorded deviation):

- dynamic-import settlement tick count relative to user promise chains
  (section 11.6);
- keys added during `for-in` enumeration are not visited (V8's choice within
  the specification's latitude);
- `console.log` formatting is Node's only over the corpus fixture set;
- `Error.prototype.stack` is derived from position side tables, not V8's text;
- no stack-depth `RangeError` by default (a profile-pinned depth is
  available);
- the safepoint interrupt `js.rt/Interrupt` is not catchable by default;
- `WeakMap` is a `Map` (no weak semantics);
- `Date`, `Math.random` and timers exist only as effects with explicit
  outcomes.

**Deferred** (unsupported until their slice): the deferred list of section 16,
`Symbol.hasInstance`, and NaN payload observation through typed arrays.

## 19. Owner decisions

From the converged text; the labels are the loop's (A12.n).

1. (A12.1) One frontend id with a dialect option (recommended) versus two ids
   sharing a grammar package.
2. (A12.2) The reference runtime: ES2022 and a pinned Node LTS (which exact
   version), and the reference compiler version (5.6.3 proposed) with the flag
   set of section 3.1 as profile data.
3. (A12.3) `:erase` by default and no checker in Phase 1 (recommended); the
   checker is a later, separately owned slice.
4. (A12.4) Decorators deferred (recommended) rather than refused permanently.
5. (A12.5) Whether the `yrt` shared prelude lift is scheduled before or after
   T10 (recommended: after, as its own profile bump).
6. (A12.6) The `data` module bump with five UTF-16 code-unit exports (Python
   unaffected).
7. (A12.7) The two-form string representation by normalization (recommended)
   versus code-unit vectors for every string (uniform, costly) versus refusing
   lone surrogates (a rejected semantic departure).
8. (A12.8) Canonical `%` renaming applied to TS from T1 and to Python in a
   later golden-moving slice (recommended) versus never for Python.
9. (A12.9) Ambient `const enum` refused (recommended) versus mandatory
   declaration inputs.
10. (A12.10) The grammar patch set with fixture-gated narrowing at T0
    (recommended) versus upstream verbatim with a narrower surface.
11. (A12.11) Binder tables as publication datoms beside the manifest
    (recommended) versus inside `spec` (withdrawn: cannot name bundle binders,
    moves `A`).
12. (A12.12) Top-level `await` refused in Phase 1 (recommended) versus the
    asynchronous evaluation variant in T7.
13. (A12.13) Dynamic import strictly as a job (recommended).
14. (A12.14) Rechecks by evidence: a saved stream-minted cursor in a
    composition ledger, cross-composition replay by regeneration
    (recommended), versus a persistent claims index inside the observer
    (forbidden by typecheck section 7) versus no rechecks (late `.d.ts` would
    be useless).
15. (A12.15) Narrowed dynamic-import ordering (recommended) versus a pinned
    four-job settlement chain.

## 20. Acceptance-matrix sketch

+-----------------------------+------------------------------------------------+
| Dimension                   | yang.typescript claims                         |
+=============================+================================================+
| Host                        | CLJ: parse, lower and run; CLJS and CLJD:      |
|                             | packets and rows, run                          |
+-----------------------------+------------------------------------------------+
| Evaluators                  | bundled profile on four; linked profile on     |
|                             | semantic, stack and register until L-b, then   |
|                             | four                                           |
+-----------------------------+------------------------------------------------+
| Parser deployment           | local worker (JVM); configured service;        |
|                             | parserless (rows)                              |
+-----------------------------+------------------------------------------------+
| Support claim               | syntax: the T0 fixture set over the TS grammar |
|                             | with `ts-g4-1`; semantics: the T1 to T9        |
|                             | groups; lowering: executable for the listed    |
|                             | arms; stdlib: the section 9 inventory;         |
|                             | departures and refusals: section 18            |
+-----------------------------+------------------------------------------------+
| Input shape                 | ASI edge cases, template nesting,              |
|                             | regex-versus-division context, deep expression |
|                             | nesting, Unicode identifiers and strings split |
|                             | across chunks, lone surrogates, large module   |
|                             | graphs with cycles                             |
+-----------------------------+------------------------------------------------+
| Laws (`yang.antlr.md` 13.1) | 2 chunk invariance (T0); 3 determinism (every  |
|                             | slice, three hosts); 4 round trips; 5          |
|                             | occurrence separation; 9 semantic preservation |
|                             | (Node parity); 12 honest unsupported (refusal  |
|                             | fixtures); plus the erasure law (T8), the      |
|                             | renaming law (L2, T8) and the claims-identity  |
|                             | law (T10)                                      |
+-----------------------------+------------------------------------------------+
| Fault                       | cancellation mid-parse replays the snapshot;   |
|                             | duplicate delivery of a module publication is  |
|                             | idempotent; a link failure leaves no partial   |
|                             | record; evidence eviction is an honest refusal |
+-----------------------------+------------------------------------------------+

## 21. Design provenance

This document consolidates a nine-round design loop held on 2026-10-17 between
the Architect seat (Fable) and an adversarial reviewer (codex, gpt-6.1-sol).
The round files live in `collab/` (never committed) and are cited by timestamp
prefix:

- Round 1 design `1792197000000-architect-yang-ts-php-designs` (both
  frontends, judged at master `7c143c01`). Review
  `1792200000000-reviewer-ts-php-designs`: **REQUEST CHANGES** with eight P1
  findings (JS strings versus Jing's surrogate refusal, generated names versus
  erasure identity, ambient `const enum`, claim occurrence resolution, ES
  module instantiation, PHP reference ownership, the verbatim grammar's
  surface, the linked-matrix and tick dependencies), five P2s and one P3.
- Round 2 `1792204000000-architect-ts-php-revision`: two-form strings,
  canonical renaming, the pinned compiler profile, `ts-g4-1`, link and
  evaluate operations, the first claims contract, the three-VM qualification.
- Round 3 `1792214000000-architect-ts-php-r3`: SCC-committing evaluation,
  owning roots and binder tables as publication datoms, the occurrence path
  vocabulary, T10 rescoped.
- Round 4 `1792217000000-architect-ts-php-r4`: the module operation matrix and
  the dynamic-import job rule, evidence replay, arity moved to the contract
  test.
- Round 5 `1792219000000-architect-ts-php-r5`: the evidence record on a saved
  stream-minted cursor, first-claim acquisition, narrowed import ordering.
- Round 6 `1792223000000-architect-ts-php-r6`: the evidence key renamed to the
  logical-stream identity `:yang.claims/stream`.
- Rounds 7 to 9 (`1792224000000`, `1792226000000`, `1792229000000`) changed
  only PHP rows; the TS text was confirmed closed.
- Intermediate reviews (`1792212000000` r2-fresh, `1792216000000`,
  `1792218000000`, `1792220000000`, `1792223000000`, `1792224000000`,
  `1792227000000`) each returned REQUEST CHANGES; every finding was accepted
  and answered in the next round.
- Final review `1792230000000-reviewer-ts-php-r9`: **READY (sign-off
  granted)**, with the stated implementation oracle gates still required.

Where rounds conflict, the latest ruling is the one stated here. Withdrawn
rulings, recorded so that readers of the round files are not misled: `const
enum` literal inlining (round 1); "own enumerable only" `for-in` (round 1);
refusal alone as the `:js` superset guarantee (round 1); `:binders` inside
`spec` (round 2); "late claims bind on the next packet" (round 3); synchronous
dynamic-import evaluation (round 3); `:yang.claims/from` positions (round 4);
the attachment-identity evidence key (round 5); the unconditional arity
fixture in T10 (round 3).
