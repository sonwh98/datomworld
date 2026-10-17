Completed-GMT: 2026-10-17 07:10:57 GMT
Completed-Local: 2026-10-17 14:10:57 +0700

# Architect designs: yang.typescript and yang.php

Role: Lead System Architect. Findings only; no file was edited. Both designs judge committed master at `7c143c01`. Evidence: `yang.antlr.md` whole, `datom.world.md`, ADR 0004, `yin.vm.typecheck.md` §0 to §3, the Python frontend (`lower.cljc`, `prelude.cljc`, `scope`, `uast`, `render`, `safepoint`, `publication`, `repl`, `parser.clj`), the neutral seams (`yang.frontend`, `yang.stage`, `yang.tails`, `yang.antlr.packet`, `yang.safepoint`, `yang.antlr.cst`, `yin.repl.frontends`), the legacy `yang/php.cljc` and its three consumers, `known-gaps.md`, `routing-status.md`, the orchestrator-log tail, and the upstream grammars-v4 directories for `javascript/typescript`, `javascript/javascript` and `php`.

## 0. Findings that bind both designs

### 0.1 Architectural defects in the current docs and code (not gaps)

| # | Defect | Where | Consequence for a second frontend |
|---|---|---|---|
| D1 | `yang.antlr.packet/malformed!` throws a `:yang.python.antlr/malformed-cst` diagnostic from a namespace documented as language-agnostic | `src/cljc/yang/antlr/packet.cljc:11`; §8.5.6 "Open" already records it | A TS or PHP unit with a bad packet reports a Python-named code. Fix before the second user: `validate!` takes the diagnostic namespace (or emits `:yang.cst/malformed`). |
| D2 | `yin.repl.frontends/create-state` merges `yang.python.antlr.repl/primitives` into every REPL state regardless of which frontend is selected | `src/cljc/yin/repl/frontends.cljc` `create-state` | The composition seam for a frontend's host primitives is a Python special case. The binding map must carry `:primitives`, and the REPL merges the selected binding's. Ruling 16's "no edit to yin.repl for a second language" is otherwise false. |
| D3 | The installed-binding shape actually landed in F2 (`{:parse :lower :lower-session :display :probe :input}`) is undocumented; §3.2 still says of its protocol "none of it exists in the repository today", and §2.1 still describes the REPL's closed `case` | §3.2, §2.1, `yin.repl.frontends` docstring | Both designs below target the landed binding shape, not §3.2's protocol. The doc needs a dated amendment stating that F1 and F2 landed and that §3.2 is the long-form contract the binding map abbreviates. |
| D4 | §8.5.2 says the stage machinery "now under `yang/python/antlr/stage.cljc` moves to a language-neutral namespace before the second user" | §8.5.2 | Already done (`yang.stage`, `yang.safepoint`). Stale text; amend. |
| D5 | §8.3 lists PHP only under "object-oriented, class-based"; PHP's dominant paradigm (value arrays, reference slots, request scope) has no row; §8.6 defers to §8.11 which has the mapping | §8.3 | Row finalized in design B §10. |
| D6 | §8.5 leaves open "whether other languages' module-level variables stay in the store or move to heap namespaces" | §8.5 owner decision 1 | Answered per language here: TS module scope is static and lexical (cells); TS script-goal globals are properties of a heap global object; PHP global scope is the heap `$GLOBALS` dict. Neither uses `yin/def` for guest variables. |
| D7 | The Python prelude and `safepoint.cljc` duplicate the emitter machinery (define, strip, placeholder, wide application, exports, free-name scan, module-spec resolution) nearly verbatim | `prelude.cljc:3483-3726`, `safepoint.cljc` | A third copy for `js` and a fourth for `php` is the wrong move. Lift first (design A §11, lift L1). |

### 0.2 Gaps and deferred work (correctly open, not defects)

L-a (requirement discovery replacing `admit`), L-b (walker sibling reads), L-e (closure-origin check, P1 in the ledger), the DHT 64 KiB cap, safepoint slices 3 and 4, C3's remaining fixtures. Both designs depend on the linked prelude pattern (P2/P3/I1 to I6) which is landed, and on nothing in those open items except where stated.

### 0.3 Shared rulings

- **No new AST tag, no engine change, no codec change** for either language. Everything below is lowering arms, prelude code over `cell`/`data`/`integer`/`stream/poll!`, and composition.
- **Each language's null is its own tagged sentinel** (§8.11). JS has two. PHP has one. None is host `nil`; none is `:py/None`.
- **Numbers**: Jing's existing kind distinction (exact integer vs float64 content) is reused as the guest's numeric kind. The float64 carrier is mandatory on the JavaScript host for every float-kind value (8.5.5). No language tags floats a second time the way Python's `{:py/float}` does, because neither needs Python's type-vs-kind split; the kind is the type.
- **Portability**: the ANTLR parser is JVM-only for both (upstream base classes exist for Java; see §A5, §B3). Node and Dart consume CST packets and rows, as Python does today. Every slice runs its prelude-level and packet-level tests on all four evaluators on JVM, Node and Dart; source-level tests on the JVM. The cljd traps already recorded (reader-conditional ordering, `for` over chunked seqs, multi-key `assoc` on nil, unary minus) apply to the new preludes' Clojure helpers.

---

# A. yang.typescript

## A1. The superset posture

**Grammar**: grammars-v4 `javascript/typescript` (`TypeScriptLexer.g4`, `TypeScriptParser.g4`, MIT), one grammar package. It parses plain JavaScript. The frontend id is `:yang.typescript/antlr`, language `:typescript`; there is no separate `:yang.javascript/antlr`. A dialect option selects the surface:

```clojure
{:yang.ts/dialect  :ts | :js          ; default :ts
 :yang.ts/goal     :module | :script  ; default :module
 :yang.ts/types    :erase | :claims   ; default :erase
 :yang.ts/checker  <content address of a checker descriptor> | nil
 :yang.ts/strict   {:strictNullChecks bool, :noImplicitAny bool, ...}}
```

Rulings:

1. **Runtime semantics are ECMAScript's**, pinned edition ES2022 (reference runtime: a pinned Node LTS, §A9). The type layer never changes a runtime result. This is stated as an invariant with a test: for every program in the corpus, stripping every type-only construct yields the same canonical rows and the same root address (§A3 erasure law).
2. **`:js` dialect refuses type syntax** with `:yang.ts/type-syntax-in-js` (not silently erased). The superset claim is not exact: `<T>x` casts, `a<b>(c)` generic calls and `enum`/`namespace` are TS-only. A JS file that happens to parse under the TS grammar but would differ under a JS grammar is caught by this refusal, since every such difference lives in a type-only or TS-runtime subtree.
3. **What the frontend does with types**: parse, and by default erase. Erasure is not deletion of information: under `:yang.ts/types :claims` the frontend additionally mints declared-type claim rows on the `claims` medium of `yin.vm.typecheck.md` §2.2 (`[occurrence :yin.sig/type <type-value> t m]`, `m` = "yang.typescript, declared"). It never checks. A **checking profile** is a composition that attaches the typecheck observer with a checker, in `report` or `gate` topology (§3 there). The checker is a parameter (decision 8 there); the TS frontend ships none in Phase 1. `:yang.ts/strict` flags are checker parameters recorded as profile data in the options schema so that a composition pins them; they have no meaning to lowering. This is consistent with §8.9: "TypeScript checking is a plugin-owned stage".
4. **A type error is a verdict row**, `:yin.typecheck/outcome :refused` with `:defects [{:rule :path}]`, never a compiler diagnostic from the frontend. The frontend's own diagnostics (§A9) are syntax, unsupported, and the `:js` refusal above. "Checked" certainty is minted only by an `:accepted` verdict. So "what a type error IS in the diagnostics model (§10)": it is not in §10's model at all; it lives on `typecheck-events`, and the §10 ledger may index it like any other datom.

## A2. null and undefined; strictNullChecks; the carrier; property keys

- **Two guests**: `:js/undefined` and `:js/null`, distinct tagged sentinels. `undefined` is the value of a missing property, a missing argument, an uninitialized `var`/`let` after TDZ exit, a function with no `return`. `null` is only the literal and what the prelude returns where the spec says null (`Object.getPrototypeOf(Object.prototype)`, regex misses). `==` makes them equal to each other and to nothing else; `===` distinguishes; `typeof null` is `"object"`, `typeof undefined` is `"undefined"`. Neither is host `nil`: the Python experience (`:py/None`) and §8.11 forbid a shared nil, and `cell/new :py/unbound` shows why a lowering needs its own sentinel for TDZ: `:js/tdz` is a third, non-guest-visible sentinel (a read raises `ReferenceError`).
- **strictNullChecks** is a checker flag. It changes which claims a checker refuses; it changes no row and no runtime behaviour. Under `:erase` it is inert profile data.
- **The float64 carrier**: a JS Number is binary64. Every Number value is Jing float64 content: on JVM and Dart a host double, on the JavaScript host the `dao.jing/float64` carrier, built at the producer exactly as 8.5.5 mandates. A literal `1` lowers to `{:type :literal :value (cbor/float64 1.0)}`, so `1` and `1.0` have one address (they are one JS value) on every host. Arithmetic in the prelude is `(data/float64 (op (data/float-value a) (data/float-value b)))`, the Python seam reused with no yield or safepoint inside it. `-0` keeps its sign (tag 27 preserves it); every NaN is Jing's one NaN, which is spec-conformant for JS since JS cannot observe NaN payloads except through typed arrays (deferred).
- **BigInt** is the untagged exact integer (C3 carriers: native within range, `BigInt`/`clojure.lang.BigInt` beyond). Because every Number is float64 content, an exact integer is unambiguously a BigInt; `typeof` is Jing kind. Mixed `1n + 1` is a `TypeError` in the prelude, as in JS. Literal `1n` is a bare integer literal within 2^53 and `(integer/parse "<hex>" 16)` beyond, exactly Python's literal rule. Numeric-key canonicalization of 8.5.4 does not apply (JS has no numeric dict keys; see next item).
- **Property keys**: ToPropertyKey. A number key is converted to its Number::toString (`o[1]`, `o["1"]`, `o[1.0]` one key; `o[-0]` is `"0"`; `o[NaN]` is `"NaN"`), a symbol stays a symbol (cell ref), everything else ToString. Own-key order is the spec's: integer-index keys ascending, then string keys in insertion order, then symbols in insertion order. The ordered-map helper (index map plus order vectors, lifted from `py/dict-*`) is therefore built with a pluggable key function and an integer-index partition. `Map`/`Set` use SameValueZero on the raw value (float64 content and refs), no coercion. Host maps are never iterated (§8.11).

## A3. The type-system surface per slice

| Construct | Treatment | Slice |
|---|---|---|
| Variable, parameter, return and property annotations; `as`, `satisfies`, non-null `!`, `<T>x` cast; definite assignment `!` | Erased. Claims under `:claims`. | T8 |
| `interface`, `type` alias, `declare` (var/function/class/module), `abstract` members | Erased; emit no rows. Claims under `:claims`. | T8 |
| Generics (type parameters, arguments, constraints, defaults) | Erased. | T8 |
| Unions, intersections, literal types, conditional/mapped/indexed types, utility types (`Partial`, `Record` ...) | Erased. Utility types are ordinary aliases in `lib.d.ts`, a claims unit (§A4); nothing special in the frontend. | T8 |
| `public`/`private`/`protected`/`readonly`/`override` modifiers | Compile-time only; erased. `#private` fields are JS runtime (T3), not these. | T8 |
| Parameter properties (`constructor(private x: number)`) | Runtime presence: lowered to the field assignment the spec implies. | T9 |
| `enum` | Runtime presence: lowered to the object JS emits (forward and, for numeric members, reverse mapping; `const enum` inlined as literals, which is TS's own rule). Members with computed non-literal initializers evaluated at run time in order. | T9 |
| `namespace`/`module` blocks | Runtime presence: IIFE over an object cell, merging declarations; instantiated namespaces only. | T9 |
| Type narrowing, control-flow analysis | Checker concern; no lowering effect. | checker |
| Declaration files `.d.ts` | A source unit lowering to **zero program rows and only claims** (§A4). | T10 |
| Decorators (legacy and TC39 stage 3) | **Refused** `:yang.ts/unsupported :decorators` in Phase 1. TS has two incompatible semantics behind flags; metadata reflection is a separate surface. Deferred, and never "automatically a Yin macro" (§7.2). | deferred |
| `import type`, `export type`, type-only re-exports | Erased; no linker edge. | T8 |
| JSX | Refused (dialect option later). | deferred |
| `abstract class` | Erased modifier; the class is runtime (T3). | T8 |

What is **checked vs erased vs refused**: nothing is checked by the frontend; everything type-only is erased (and claimed under `:claims`); decorators and JSX are refused; enums, namespaces and parameter properties are runtime constructs with their own arms. **The erasure law**: for every T8 and T9 corpus program, `strip-types(P)` and `P` give byte-identical rows and root; for T9 constructs the comparison is against the JS the TS compiler emits at the pinned version, since those are not erasable.

## A4. The boundary story: types at the edges

- **The runtime has no types.** No row, cell content, prelude value or effect carries a TS type. Erasure is precise: a type annotation subtree in the CST produces no UAST node, no side-table entry (no "type in the side table" as §7.3 permits for spans, because the claims medium is where types go, and a side table would make two homes).
- **The prelude's typed surface** is a claims unit: `js-lib.d.ts`, a declaration file compiled by the same frontend into claim rows whose occurrences are the prelude module's export binders (`jsm` root address, path to each `yin/def` of `Object`, `Array`, `JSON`, `Math` ...). It is published beside the `js` prelude manifest as datoms, not inside it (annotating does not change identity, typecheck decision 2). A composition that runs a checker pins the claims unit address beside the prelude address in the runtime profile; one that does not never fetches it.
- **Consumed modules**: a module's `.d.ts` is a claims unit pinned to that module's manifest address (§A8). Yin-side type stubs for host effects (ADR 0004 surfaces) are the same thing: a declaration unit whose occurrences are the effect-binding module's binders.
- **Host values crossing in** (effect outcomes) are plain data classified into JS values by the binding's lowering; the checker sees only the declared claim on the binding. There is no runtime type test at the edge, because "the runtime has no types"; a binding that wants a runtime guard writes one in JS.

## A5. The grammar route and parser worker

- **Package**: `antlr/typescript/manifest.edn` in the shape of `antlr/python3/manifest.edn`: repo `antlr/grammars-v4`, path `javascript/typescript`, pinned revision and BLAKE3 per file, `.g4` vendored verbatim (MIT with the README notice), Java helpers `Java/TypeScriptLexerBase.java` and `Java/TypeScriptParserBase.java` fetched by digest with the same licence-provenance block as Python's two helpers (the same unresolved-notice situation; redistribution blocked until resolved). Generated with the pinned ANTLR 4.13.2, `-Dlanguage=Java -package yang.typescript.antlr.gen -no-listener -no-visitor`, digests pinned.
- **Predicates**: the parser uses six base-class methods (`notLineTerminator`, `n`, `p`, `notOpenBraceAndNotFunctionAndNotInterface`, `lineTerminatorAhead`, `closeBrace`); the lexer base tracks template-string depth and regex-vs-division context. These are internal library execution (§4.3), not callbacks. Upstream provides Java, C# and C++ targets only (no JavaScript or Dart port of the bases), so the local parser is JVM-only and Node/Dart go through packets or a configured service (§4.1 row 2 and 3). The README records two known ambiguities (`primaryType` decision 11, `singleExpression` decision 236): the worker pins the prediction mode and the differential corpus pins the parse of the ambiguous forms.
- **Worker**: `yang.antlr.cst/make-worker` with a profile `{:grammar-id :make-lexer :make-parser :start (.program p)}`; nothing in `cst.clj` changes. ASI is the grammar's (`eos` predicates), so a REPL completeness probe cannot use "error at EOF"; the probe is a second parse of the snapshot with a trailing `\n;` sentinel, answering `:incomplete` only when the error set moves to EOF, else explicit submit (§5.3).
- **CST inventory highlights** (rule names from the pinned grammar): `program`, `sourceElements`, `statement` family; `variableStatement`/`variableDeclaration` with `bindingPattern`; `functionDeclaration`, `arrowFunctionDeclaration`, `generatorFunctionDeclaration`, `classDeclaration`/`classTail`/`classElement`/`propertyMemberDeclaration`; `singleExpression` labelled alternatives (`MemberDotExpression`, `OptionalChainExpression`, `TemplateStringExpression`, `YieldExpression`, `AwaitExpression`, `ClassExpression`); `importStatement`/`exportStatement` families; and the **parse-only subtrees**: `typeAnnotation`, `type_`, `typeParameters`, `typeArguments`, `interfaceDeclaration`, `typeAliasDeclaration`, `accessibilityModifier`, `abstractDeclaration`, `decoratorList`. The lowering's `consumed-rules` set names every type-only rule so that `default-arm` can never reach one (an unhandled type rule would be a defect, not an unsupported construct).

## A6. The lowering-arms inventory

Context map as Python's (`:lower` self-reference, `:loop {:brk :cont}`, `:ret`, `:this`, `:gen`, `:async`, `:class`, `:scope`), name supply `%<prefix><cst-id>` (`%` cannot begin a JS identifier either), `:yang/site` marks on loop and function lambdas.

**Scope analysis (`yang.typescript.antlr.scope`)**: hoisting is the whole job. Per function scope: `var` and function declarations hoisted to function top (cells allocated `:js/undefined`, functions initialized first); per block: `let`/`const`/`class` cells allocated `:js/tdz`; per-iteration copies for `for (let ...)` (a fresh cell per iteration, closed over correctly); `arguments` object only in non-arrow functions that mention it; `this` lexical for arrows (a parameter threaded from the enclosing non-arrow function); strict mode is always on (modules) and script-goal sloppy semantics are out of scope except where listed (`:js/sloppy` refused: `with`, implicit globals, duplicate params). Every binding is a cell (mob D5 holds: closures and continuations).

| Group | Arms | Lowering |
|---|---|---|
| Expressions | literals, template literals (`js/to-string` per substitution, tagged templates in T5), identifiers, member/index access, optional chaining (`js/get` with short-circuit through `if` on nullish), `new`, calls (`js/call f this args`), spread in calls and arrays, `typeof`/`void`/`delete`/`in`/`instanceof`, arithmetic/bitwise/logical/`??`, comparison (`js/loose-eq`, `js/strict-eq`, `js/compare` with ToPrimitive), assignment and compound assignment (receiver and key evaluated once, `%o`/`%k`), `++`/`--`, comma, conditional, arrow and function expressions, class expressions, object literals (shorthand, computed keys, methods, getters/setters, spread), array literals (holes), regex literals (host-effect-free: refused in Phase 1, see A7) | ordinary `:application` of `js/*` |
| Destructuring | array/object patterns in declarations, params, assignment, defaults, rest | `js/iter-destructure`, `js/get` chains |
| Statements | var/let/const, if, for, for-in (own enumerable string keys in spec order, snapshot at start), for-of (iterator protocol), while/do, switch (strict-eq chain with fallthrough via sequencing), break/continue (labelled), return, throw, try/catch/finally (optional catch binding), block, labelled, empty, expression | loops: self-applied lambda `:loop` site; escapes: `js/call-ec` with `unwind-to`; `finally`: `js/try-finally` |
| Functions | declarations, defaults, rest, `arguments`, `this` binding modes (strict `this` = undefined when called bare), `new.target` | `js/make-function name code this-mode proto-slot` (a cell: functions are objects) |
| Classes | `class`, `extends`, `constructor`, `super()` and `super.m`, methods, static, getters/setters, fields (public, `#private`, static), static blocks | `js/make-class`: constructor function cell plus prototype cell; `extends null`; derived constructors must call `super` before `this` (TDZ cell on `this`) |
| Generators | `function*`, `yield`, `yield*` | the landed continuation machinery: `js/make-generator`, `js/gen-switch`, `js/yield-raw`; iterator protocol objects (`next`/`return`/`throw`) over the same cell as 8.5.3; same flag-cell pattern, fixed retention (ruling 7) accepted |
| Async | `async function`, `await`, `for await`, async generators | T6: `await` is a suspension via the generator core; the job queue is prelude (A9) |
| Modules | `import`/`export` forms, `import()` with a literal specifier, `import.meta` (refused) | A8 |
| Type layer | every type-only rule in `consumed-rules`; `as`/`satisfies`/`!`/casts return their operand; `enum`, `namespace`, parameter properties as A3 | T8, T9 |

Refused with stable `:yang.ts/unsupported` codes: `with`, `eval`/`new Function` (a compilation request later, like PHP `eval`), decorators, JSX, `import.meta`, regex literals and `RegExp` (Phase 1), tagged templates until T5, labelled `continue` across functions (syntax error), getters on `arguments`, Proxy/Reflect (T7+), `Symbol.asyncIterator` until T6, typed arrays and `SharedArrayBuffer` (host memory model; the latter forbidden by "no shared mutable state").

## A7. The prelude inventory

Module name `js` (reserved, non-dotted, as `py`); hook prelude `jssp`; single definition list, two emitters (lifted, L1). `js/init!` allocates the intrinsics once per task: `%Object.prototype%`, `%Function.prototype%`, `%Array.prototype%`, the error constructors, the global object, the job queue cells, and flips `js.rt/state` to `:js/ready` last.

Value encoding (runtime profile data):

| JS type | Representation |
|---|---|
| undefined, null | `:js/undefined`, `:js/null` |
| boolean | host boolean |
| number | float64 content (carrier on JS host) |
| bigint | exact integer (C3 carriers) |
| string | `{:js/str s}` with UTF-16 code-unit semantics: `length`, indexing and `charCodeAt` are code units, which is what all three hosts' native strings already are; the `data` module's code-point functions serve `codePointAt`/`for-of`. A new pure `data` export pair, `str-unit-length`/`str-unit-at`, is required (today's exports are code-point indexed by design). |
| symbol | cell `{:js/type :symbol :description}` (identity) ; well-known symbols pre-allocated by `init!` |
| object | cell `{:js/type :object :proto ref|:js/null :props ordered-map :extensible? bool}`; a prop slot is a data descriptor `{:value :writable :enumerable :configurable}` or an accessor `{:get :set ...}` |
| function | cell `{:js/type :function :code closure :this-mode :home :fields ... :props :proto}`; `prototype` is an ordinary own prop |
| array | cell `{:js/type :array :items vector :props ...}` with `length` derived and holes as `:js/hole` |
| class | a function cell plus prototype cell; no class concept in the VM (§9.4) |
| generator, promise, Map, Set, Date | cells with their own `:js/type`; `Date` only via a time effect |
| error | ordinary object cell whose `:proto` is an error prototype; `stack` is derived at the boundary from position side tables like Python's `tb_lineno` (owner decision 2 of 8.5.1 extended) |

Prelude areas and estimates (Python's 3,770 lines split as roughly 1,000 shareable mechanism and 3,000 semantics; a JS prelude is dominated by abstract operations rather than numeric text):

| Area | Content | Est. lines |
|---|---|---|
| Abstract operations | ToPrimitive (with `Symbol.toPrimitive`, `valueOf`/`toString` order), ToNumber (string grammar), ToString (Number::toString via `integer/float-digits` and the shortest-round-trip rule, which the integer module already supplies), ToBoolean, ToPropertyKey, SameValue/SameValueZero/IsStrictlyEqual/IsLooselyEqual, ToInt32/ToUint32 | 450 |
| Objects | `[[Get]]`/`[[Set]]`/`[[Has]]`/`[[Delete]]`/`[[OwnKeys]]` over descriptors and the prototype chain; `Object.*` (keys, assign, create, defineProperty, freeze, getPrototypeOf, entries, fromEntries) | 500 |
| Functions and classes | call/construct, `this` modes, bound functions, `new.target`, super dispatch, `instanceof` (`Symbol.hasInstance` deferred) | 250 |
| Arrays | methods on `items` with holes: push/pop/shift/unshift/slice/splice/concat/indexOf/includes/join/map/filter/reduce/forEach/find/sort (stable, comparator through `js/call`)/flat; `Array.isArray`, `Array.from` | 450 |
| Strings | the code-unit surface, `String.prototype` core (slice/substring/indexOf/split by string/trim/pad/replace by string/toUpperCase ASCII) | 300 |
| Iteration | iterator protocol, generators (shared core), destructuring helpers, for-of/for-in | 250 |
| Errors and control | `js/throw`, handler stack (shared), try/finally, completion through escapes; Error hierarchy | 150 |
| Numbers | `Number.*`, `Math.*` (pure: abs/floor/ceil/round/trunc/sign/min/max/pow via `py/fpow`'s pinned squaring rule/sqrt where `data` supplies it; `Math.random` is an effect), `parseInt`/`parseFloat` | 250 |
| JSON | `JSON.stringify`/`parse` (spec grammar, key order, `toJSON`) | 300 |
| Promises and jobs | A+ states, `then` chaining, the microtask queue in a cell, `queueMicrotask`; `async`/`await` over the generator core; `setTimeout` is an effect through `stream/poll!` outcomes | 300 |
| Map, Set, WeakMap (as Map), Symbol | ordered-map with SameValueZero keys | 150 |
| console and boundary | `console.log` appends snapshots to an out cell, the host renderer formats them as Node's `util.inspect` default does for the corpus subset (parity law, as `float-repr` has) | 150 |

**The typed-bindings question**: the prelude carries no types; `js-lib.d.ts` is its claims unit (A4). **What the prototype runtime adds** over Python's class model: descriptors, accessor properties, the prototype chain instead of a `:base` chain, `this` as an explicit argument to every call, functions as cells, and ordering rules on own keys.

## A8. The import/module story

- **ES modules only** in Phase 1. A TS unit compiled as module `./a/b.ts` becomes linker module `jsm.a$b` (specifier mangled injectively, `$` as in `pym`); the compiled shape is Python's: `(do (require 'js) (yin/def spec {...}) (yin/def body (fn [%ns] ...)))`, exports `#{spec body}`.
- **Live bindings**: an exported binding is a cell; the module namespace object is a frozen heap object whose accessor properties read those cells. `import {x}` lowers reads of `x` to `cell/get` on the exporter's cell through the namespace (so `export let x; x++` is visible). Default exports and `export * from` follow.
- **Linking before evaluation**: static imports are hoisted and pinned in the manifest `:requires` exactly as Python's (I4-a delivery thunks at statement time are **not** used for static imports, because ES links the whole graph before running any body; `js/link-module` resolves every static dependency first, then runs bodies in the spec's depth-first post-order). Cycles are the spec's model: TDZ on not-yet-initialized bindings, `ReferenceError` on access. SCC publication (I5) is reused unchanged; the alias-module shape gives each member its own namespace object.
- **Dynamic `import("lit")`** uses the I4-a thunk path and returns a promise; a computed specifier is a diagnostic (`import_module` ruling 10 reused).
- **Refused**: CommonJS `require`, `module.exports`, `node_modules` resolution, package.json `exports` maps, URL specifiers, `import.meta.url` (no filesystem, §5.1 and ADR 0004). A bare specifier is a name in the publication unit set or a published module name; nothing else. Node and browser APIs are effect packages (§8.7), bound per ADR 0004 one slice at a time, each with a declaration unit.
- **`.d.ts` for consumed modules**: a declaration unit pinned to the consumed module's manifest address; under `:claims` the frontend emits claims against the importer's binding occurrences too, so a checker sees both ends.
- The REPL binding installs like Python's F3: parse (JVM) plus `lower`/`lower-session`, `:display :output`, `:primitives {js-report ...}` once D2 is fixed. Persistent `__main__` has an ES analogue: a persistent script-goal global object per session.

## A9. Safepoints, diagnostics, the differential corpus

- **Safepoints** (8.5.2): `:loop` and `:call` marks only, hook prelude `jssp` with `jssp/loop` and `jssp/call` polling a signal cursor through `stream/poll!` and throwing a guest `js.rt/Interrupt` (not catchable by `catch` unless the profile says so; default: terminates the job). **No thread switch at a safepoint, ever**: jobs run to completion; the scheduler runs the next microtask only when the current job's continuation returns to the queue loop. `:return` (depth) is not needed: JS has no `RecursionError`; `RangeError: Maximum call stack size exceeded` is a profile-pinned depth through the same `:call`/`:return` hooks if a composition wants Node parity (default off; continuations are heap data). `:line` marks are inserted for a debugger profile only.
- **The job interpreter** (§8.7): a microtask queue cell and a macrotask queue cell in the prelude; host completions (timers, I/O outcomes) are events on composition-supplied streams read with `stream/poll!` between jobs; the ordering is deterministic for a given event order. Observable ordering (`await` tick counts, `then` chains, `queueMicrotask` interleavings) is a named corpus group.
- **Diagnostics**: `:yang.ts/syntax` (ANTLR, with the recovery-free strict profile), `:yang.ts/unsupported {:construct}`, `:yang.ts/type-syntax-in-js`, `:yang.ts/unhandled-rule` (a frontend defect), `:yang.ts/early-error` for the spec's early errors the grammar does not catch (duplicate `let`, `const` without initializer, `await` outside async, invalid assignment targets, `super` outside methods). Runtime errors are guest `Error` objects. Operational failures stay operational (§11).
- **Differential corpus**: a pinned Node LTS (exact version pinned in the support profile) executes each corpus program and its stdout is the expectation, as CPython 3.9.6 is for Python; `console.log` formatting parity is a law over a fixture set like `float-text-v1`. The TS compiler at a pinned version is used in two ways only: (a) for T9 constructs, `tsc`'s emitted JS is the semantic reference (then run on Node); (b) under `:claims` with a future checker, `tsc --noEmit` diagnostics are the reference for which programs a strict checker refuses. Never for behaviour. Corpus groups: coercion tables (every `==` pair in the spec table, ToNumber strings, ToPrimitive order), scoping (hoisting, TDZ, per-iteration `let`, closures in loops), `this` (bare calls, methods, arrows, bound, `call`/`apply`), prototypes (shadowing, `in`, `hasOwnProperty`, key order), classes (super chains, fields, statics, private), exceptions (finally ordering with return/break), generators (send/throw/return, `yield*`, early return running `finally`), async (tick ordering), modules (live bindings, cycles, TDZ across modules), numbers (`0.1+0.2`, `-0`, `2**53+1`, BigInt mixing errors, `toString` of every double over the float-digits fixture), erasure (law A3), enums/namespaces vs `tsc` output.

## A10. Paradigm rows finalized

```text
+----------------------+--------------------------------------------------+--------------------+
| Paradigm             | Universal AST target                             | Reference          |
+======================+==================================================+====================+
| Prototype / dynamic  | Objects, functions, arrays and symbols are cells | JavaScript,        |
| (finalized)          | holding descriptor maps linked by :proto refs;   | TypeScript (the    |
|                      | every binding a cell (hoisting/TDZ sentinels);   | same frontend, the |
|                      | `this` an explicit argument; escapes and         | type layer erased  |
|                      | generators on continuations; jobs run to         | or claimed)        |
|                      | completion over a prelude queue reading          |                    |
|                      | completion streams; type facts are claim rows    |                    |
|                      | beside code, never rows                          |                    |
+----------------------+--------------------------------------------------+--------------------+
| JS/TS number model   | Number = Jing float64 content on every host      |                    |
| (new row)            | (carrier mandatory on JS, 8.5.5); BigInt = exact |                    |
|                      | integer (C3 carriers); kind is typeof; one NaN;  |                    |
|                      | -0 preserved; property keys are strings/symbols  |                    |
|                      | by ToPropertyKey; Map/Set keys SameValueZero     |                    |
+----------------------+--------------------------------------------------+--------------------+
```

## A11. Slice sequencing, acceptance contracts, and the yang.core lift

Order: the lifts L1 to L4 land first (they are address-neutral for Python); then T0 to T10. JS core first, type layers last, because the erasure law needs a complete JS core to be meaningful.

| Slice | Scope | Acceptance (all four VMs on JVM, Node, Dart unless noted) |
|---|---|---|
| L1 | Lift the emitter machinery (define, strip with a namespace parameter, placeholder, wide application, exports, `free-names`/`defined-keys`, module-spec resolution, `admit`) to `yang.core.prelude`; `prelude.cljc` and `safepoint.cljc` call it | Python `py` and `pysp` manifest goldens unchanged byte for byte; `prelude_parity_test` green |
| L2 | Lift `uast.cljc` to `yang.core.uast`; lift `gen`, `kids`, `rules`, `rule-kids`, `site`, `app*`, `contains-rule?`, `unsupported!`/`syntax!`/`default-arm` (namespace and refusal table as parameters), `diagnostic`, `lower-transform`, `open-stage`, `step-stage` to `yang.core.lower` and `yang.core.stage`; fix D1 in `yang.antlr.packet` | every Python golden unchanged; `packet.cljc` emits the caller's namespace |
| L3 | Lift `publication.cljc` to `yang.core.publication` with `imported-names`, `module-spec`, `scc-module-specs` injected; lift `module-symbol` (prefix parameter), `require-node`, `module-tree`, `declare-free-name` to `yang.core.module` | `publication_test`, `dht_import_test` unchanged |
| L4 | Fix D2: binding `:primitives`, REPL merges the selected binding's | `repl_test` green; Python REPL output unchanged |
| T0 | Grammar package, worker profile, CST export; `antlr/typescript/manifest.edn`; licence block | `cst_export_test` analogue: spans are byte offsets, template literal and regex-context tokens export, the two ambiguous decisions are pinned by fixture; error packets for ASI edge cases |
| T1 | JS core: literals, operators, coercion, var/let/const/TDZ, functions, closures, if/loops/switch/labels, try/catch/finally, strict equality, `js` prelude skeleton with `init!`, console.log boundary, bundled and linked profiles both | corpus groups coercion, scoping, exceptions; `console.log(0.1+0.2)` prints `0.30000000000000004` on all hosts; float-address goldens of the `js` prelude and a program root agree on three hosts (8.5.5 gate for `js`) |
| T2 | Objects, prototypes, descriptors, `this`, arrays, `Object.*`, for-in order, `delete`, getters/setters | prototypes and `this` groups; key-order fixtures; `a === b` on cells is same-cell on every host |
| T3 | Classes, `extends`, `super`, fields, `#private`, static, `instanceof`, Error hierarchy, `new.target` | classes group; a subclass of `Error` caught by `catch` with correct `instanceof` across modules of one task |
| T4 | Destructuring, spread/rest, default params, `arguments`, template literals, optional chaining, `??`, compound assignment single evaluation | destructuring fixtures; evaluation-order fixtures |
| T5 | Generators and the iterator protocol, for-of, `yield*`, tagged templates, Map/Set/Symbol | generators group; reachable-heap measurement after repeated suspension (ruling 7); Node parity on `return()` running `finally` |
| T6 | Promises, microtasks, async/await, async generators, timer effect binding | async group tick-ordering fixtures; `stream/poll!` empty queue finishes without parking |
| T7 | ES modules over the linker: `jsm` emission, live bindings, SCC, dynamic `import()`, publication, DHT import parserless on Node/Dart | modules group; a JVM-published `jsm.m` imports on Node and Dart; cycles with TDZ `ReferenceError`; refusals for `require` and bare node_modules specifiers |
| T8 | Type-erasure arms, `:js` dialect refusal, `import type` | **erasure law** over every T1 to T7 program annotated by a generator script: identical rows and root; `:js` refusal fixtures |
| T9 | Enums, namespaces, parameter properties | output equals Node running `tsc`'s emit at the pinned version |
| T10 | `:claims` emission, `.d.ts` units, `js-lib.d.ts`, the typecheck observer wired in `report` topology with a trivial claims-consistency checker (T1 of typecheck.md) | claim rows cite the right occurrences; annotating changes no address; a `report` composition emits verdicts and the evaluator output is unchanged |

Deferred after T10: decorators, JSX, RegExp (a pure engine is a large portable slice), Proxy/Reflect, typed arrays, Intl, Date beyond the time effect, a real checker.

**What TS pulls from yang.python into shared namespaces** (named): `yang.core.uast` (from `yang.python.antlr.uast`, rename), `yang.core.lower` (`gen`, `kids`, `rules`, `rule-kids`, `site`, `app*`, `contains-rule?`, diagnostic builders), `yang.core.stage` (`diagnostic`, `lower-transform`, `open-stage`, `step-stage`, parameterized by ok-check, lower fn, diagnostic namespace), `yang.core.module` (`module-symbol`, `require-node`, `module-tree`, `declare-free-name`, `module-spec`, `scc-module-specs` shape), `yang.core.publication` (`publication-order`, `publish-units!`), `yang.core.prelude` (emitter machinery and `admit`), `yang.antlr.packet` fixed. **Not lifted now**: the prelude *definitions* (escape machinery `ctx`/`frame`/`raise`/`unwind-to`/`try`/`try-finally`/`call-ec`, the generator switch core, the ordered dict, the float seam, the module runtime skeleton, the safepoint poll/deliver). Moving those into a shared linked module (`yrt`) is a prelude-profile bump that moves every Python golden and changes `py`'s requirement pins; it is the right end state but an owner-scheduled slice, not a TS dependency. TS copies the patterns under `js/` with JS payloads (a `catch` receives any value, not an exception instance).

## A12. Owner decisions (yang.typescript)

1. One frontend id with a dialect option (recommended) versus two ids sharing a grammar package.
2. ES2022 and a pinned Node LTS as the reference; which exact Node version.
3. `:erase` default and no checker in Phase 1 (recommended); the checker is a later, separately owned slice.
4. Decorators deferred (recommended) rather than refused permanently.
5. Whether the `yrt` shared prelude lift is scheduled before or after T10 (recommended: after, as its own profile bump).
6. The `data` module's two new UTF-16 code-unit exports (a `data` version bump; Python unaffected).

## A13. Acceptance-matrix sketch

```text
+----------------------+-------------------------------------------------------------------+
| Dimension            | yang.typescript claims                                            |
+======================+===================================================================+
| Host                 | CLJ: parse + lower + run; CLJS, CLJD: packets and rows, run       |
| Parser deployment    | local worker (JVM); configured service; parserless (rows)         |
| Support claim        | syntax: TS grammar ES2022 surface; semantics: the T1-T9 groups;   |
|                      | lowering: executable for the listed arms; stdlib: the A7 inventory|
| Input shape          | ASI edge cases, template nesting, regex-vs-division context,      |
|                      | deep expression nesting, Unicode identifiers and strings split    |
|                      | across chunks, large module graphs with cycles                    |
| Laws (13.1)          | 2 chunk invariance (T0), 3 determinism (every slice, three hosts),|
|                      | 4 round trips, 5 occurrence separation, 9 semantic preservation   |
|                      | (Node parity), 12 honest unsupported (refusal fixtures),          |
|                      | plus the erasure law (T8) and the claims-identity law (T10)       |
| Fault                | cancellation mid-parse replays the snapshot; duplicate delivery   |
|                      | of a module publication is idempotent                             |
+----------------------+-------------------------------------------------------------------+
```

---

# B. yang.php

## B1. The paradigm specification

Reference runtime: PHP 8.3 CLI at a pinned patch release (owner decision), `php.ini` pins `precision=14`, `serialize_precision=-1`, `error_reporting=E_ALL`, `display_errors=stderr`. The grammar's feature set (enums, `match`, `readonly`, named arguments, attributes) is PHP 8.1 and up, so 8.3 is the natural pin.

**Arrays** (§8.11 adopted and made precise): a PHP array is a plain persistent value `{:php/type :array :index {key slot} :order [key...] :next-index n}` stored directly in the variable's cell; assignment and parameter passing copy the value in O(1); mutation is get, pure update, `cell/set!`. Key coercion at every write and read: an integer stays; a decimal-integer string in canonical form (`"8"` yes, `"08"`, `"8.0"`, `" 8"` no) becomes that integer; a float truncates toward zero to an integer (with the PHP 8.1 deprecation emitted as a diagnostic record, see B6); `true`/`false` become 1/0; `null` becomes `""`; any other string stays a string. `next-index` is one more than the largest integer key ever used (never decreases on unset), and negative keys follow PHP 8.3's rule (next is `max+1`). Iteration is `:order`. `$a[] = v` appends at `next-index`. Nested arrays are nested values: `$a['x']['y'] = 1` is one path update. Unset splices `:order`. `count`, `array_keys`, `in_array` and the rest are prelude over this value. Large arrays cost one path copy per write; this is the accepted cost; a packed-vector fast path is an attached optimizer's business, not the lowering's.

**References**: a variable cell holds either a value or a marker `{:php/ref c}` pointing at a shared cell; reads dereference one level; `$b = &$a` moves `$a`'s content into a fresh shared cell and sets both variable cells to the marker; `unset($b)` removes only the binding. The marker may sit in an array slot and in an object property, survives array copy (PHP's real behaviour, including the famous reference-in-copied-array semantics), and `foreach ($a as &$v)` sets the slot marker per iteration and leaves the last one bound. `use (&$x)` captures the marker; `use ($x)` copies the value at closure creation. Function parameters `&$p` bind the caller's variable cell content to a marker. Return by reference is refused in Phase 3 (rare, and `&` returns need a cell per return site).

**Scope**: PHP has function scope only, no block scope, no implicit closure capture (except arrow `fn`, which captures by value every enclosing variable it mentions, at creation). Locals are cells allocated at function entry with `:php/undef` (a read emits `Warning: Undefined variable` and yields null). `global $x` binds the local cell to a marker on the `$GLOBALS` entry (creating it as null if absent). `static $x = init;` is a cell owned by the function object, allocated once at function definition (constant expression initializer evaluated once). **Dynamic scope profile**: a function body that contains `include`/`require`, `$$name`, `extract`, `compact`, `get_defined_vars`, `eval` or `parse_str` with one argument has its locals in a heap symbol-table dict (the Python module-namespace precedent, owner decision 1 of 8.5.1); scope analysis decides per function, and the lowering targets `php/local-get`/`php/local-set` on the dict. All other functions use cells. The global scope is always the `$GLOBALS` dict (a heap dict, insertion-ordered, with the superglobals pre-seeded by the request record). `$GLOBALS` itself is read-only as of PHP 8.1 except through its elements, which fits.

**Superglobals and the request context** (ADR 0004 doctrine; §8.6): a PHP run is one task consuming one **request record** from a composition-supplied stream (`{:php.request/method :uri :query :headers :cookies :body :server :env :files}`), from which `php/init!` builds `$_GET`, `$_POST`, `$_COOKIE`, `$_SERVER`, `$_ENV`, `$_FILES`, `$_REQUEST` (ordered per `request_order` pin) and `$argv`/`$argc` for CLI; `$_SESSION` is a bound surface (session store effect), absent by default. Nothing reads ambient process or HTTP state. Output (`echo`, `print`, inline HTML, `printf`) appends to an output cell with the output-buffering stack (`ob_start`/`ob_get_clean` are pure prelude over that stack); the top level flushes as ordered `:php.response/body` records on the response stream; `header()`, `http_response_code`, `setcookie` are `:php.response/header` records (refused after the first body flush with the PHP warning text). `exit`/`die` is an escape to the task root, then flush. Cross-request state leakage is impossible by construction: one task per request and no store writes for guest state (Phase 3 exit criterion).

**Coercion and comparison** (PHP 8 rules): `==` follows the PHP 8 table (number vs numeric-string compares numerically; number vs non-numeric string compares as strings, so `0 == "a"` is false; `null == false == "" == 0 == []` in their pairs; arrays compare by key/value with the same rules; objects by property or identity for `===`); `<=>` the same ordering; `===` identical type and value, arrays element-wise, objects same handle. Numeric-string detection (`is_numeric`) includes leading whitespace and trailing whitespace (8.0), exponent forms, and the "leading-numeric" `TypeError`/`Warning` distinctions for arithmetic (`"5 apples" + 1` warns and gives 6; `"apples" + 1` throws `TypeError` in PHP 8). String increment (`"a"++` is `"b"`, `"Az"++` is `"Ba"`) is in. Integer overflow converts to float (`PHP_INT_MAX + 1` is a float): every integer operator goes through the exact integer module and demotes to float64 when the exact result leaves `[-2^63, 2^63-1]`, which is the one place C3's "promotion before overflow" is used to *detect* rather than to promote. Division `/` yields an int only when exact, else float; `intdiv`, `%` (int only, `DivisionByZeroError`, `ArithmeticError` on `PHP_INT_MIN % -1`), `**` (int when exact and in range, else float, via the pinned `fpow`). Float to string uses `precision=14` (`%.14G`, so `echo 0.1+0.2` prints `0.3`); `var_dump`/`var_export`/`json_encode` use `serialize_precision=-1` (shortest round trip, `integer/float-digits`). The 14-significant-digit rounding needs exact decimal rounding of a binary64: a prelude function over `integer`'s exact decomposition (no new kernel; an `integer` version bump only if measured too slow).

**Null**: `:php/null`, the only null; `??` and `?->` test it; `isset` is "bound and not null"; `empty` is falsiness; `unset` unbinds.

**Classes, interfaces, traits, enums**: a class is a cell `{:php/type :class :name :parent :interfaces #{} :constants :static-props (cells) :props-decl :methods {name {:code :visibility :static? :abstract? :final? :declaring-class}} :abstract? :final? :readonly?}`; instances are cells `{:php/type :object :class :props ordered-map :id n}` (the `#n` of `var_dump`/`spl_object_id` is a per-task counter, not the cell id, documented as task-local like Python's `id()`). Traits are copied into the class descriptor at class-declaration time (precedence: class methods over trait methods over inherited; `insteadof`/`as` resolution; abstract and static trait members; trait properties merged with the compatibility check), which is PHP's own copy semantics and needs no runtime trait object. Interfaces are dispatch contracts: `instanceof` walks `:parent` and `:interfaces` transitively; constants on interfaces. Visibility is checked at run time against the calling class context (a `:class-ctx` the lowering threads into methods), as PHP does; `protected` uses the inheritance relation. Magic methods `__construct`, `__destruct` (never called; the collector has no finalizers, the C2 ruling 6 precedent, a documented departure), `__get`/`__set`/`__isset`/`__unset`, `__call`/`__callStatic`, `__toString` (`Stringable`), `__invoke`, `__clone` (shallow copy of the props map into a fresh cell), `__serialize` deferred. Readonly properties, typed properties with coercion/`TypeError` per `declare(strict_types)` (a per-unit lowering option, pinned in the packet, default 0: coercive), enums (`enum X: string { case A = 'a'; }` are singleton object cells created by `init!`-time class evaluation, `cases()`, `from`/`tryFrom`, interface implementation), `match` (strict comparison, `UnhandledMatchError`), first-class callable syntax `f(...)`, named arguments (binding by parameter name, `ArgumentCountError`, unknown named `Error`), variadics and defaults, nullable and union types in declarations (checked at call time under the coercion rules; **type declarations are not silently ignored**, Phase 3 exit criterion), return type checks, `static`/`self`/`parent`, late static binding, closures as `Closure` objects with `bind`/`bindTo`/`call`/`fromCallable`, callables as strings and `[obj, 'm']` arrays resolved through the function and class tables.

**Functions and the tables**: the function table and the class table are heap dicts in `php.rt/*`, populated as declarations execute (top-level unconditional declarations are hoisted to the start of the unit as PHP does; conditional ones execute in place). A call to an undefined function raises `Error("Call to undefined function f()")` at run time, PHP's rule; in addition the lowering emits a *warning-severity* diagnostic `:yang.php/unknown-builtin` for a name in the pinned PHP builtin catalogue (a checked-in list) that the prelude does not supply, so unsupported stdlib fails honestly before the program runs (law 12).

**Exceptions and errors**: `Throwable` with `Exception` and `Error` hierarchies (`TypeError`, `ValueError`, `ArgumentCountError`, `ArithmeticError`, `DivisionByZeroError`, `UnhandledMatchError`, `JsonException`, the `Spl` exceptions); `try`/`catch` (multi-type `|`, optional variable)/`finally`, `throw` as an expression (8.0). Warnings, notices and deprecations are **not** exceptions: they are `:php.diag/warning` records appended to a guest-diagnostics stream the composition supplies (deterministic, since they derive from program state) and are observable by `set_error_handler` (a guest callable in a cell, invoked at the emission point by prelude code; `ErrorException` conversion is then user code). `E_ERROR` fatals (undefined class, abstract instantiation) are `Error` throws. The uncaught-exception output text is pinned to PHP's `PHP Fatal error: Uncaught ...` format without the file path.

**Generators**: PHP `yield`, `yield k => v`, `send`, `throw`, `getReturn`, `yield from` map onto the landed C2 core (switch, yield-raw, exit, flag cells, per-generator handler stack); `Generator` implements `Iterator`. `foreach` over `Iterator`/`IteratorAggregate` objects and arrays; `ArrayAccess`, `Countable`.

**Mixed HTML mode**: `inlineHtml` tokens lower to `php/echo` of the literal text in source order between code blocks; `?>` swallows one following newline (the lexer's rule, preserved through the span); the first `<?php` has no output before it unless there is text. Output order is the statement order: Phase 3 exit criterion 1 is a direct fixture.

**include/require** (B6) and **eval**: `eval` is a new compilation request (§8.6) sharing the caller's scope, so it forces the dynamic scope profile on its function; deferred to after P6, refused until then with `:yang.php/unsupported :eval`.

## B2. The legacy migration: replace, not wrap

The audit's judgement is confirmed by reading `yang/php.cljc`: it has no cells (assignment is lexical rebinding, so `$x = 1; $f = function() use ($x) {...}; $x = 2` and every loop-carried mutation are wrong except the single-threaded-variable special case `for-threaded-var`), no arrays, no strings beyond literals, no `echo` or output, no classes, references, null semantics or coercion, a Z-combinator per function and per loop, type hints parsed and dropped, `-10;` folded into a literal, and PHP `null` lowered to host `nil`. Its tokenizer has no modes (no HTML, no heredoc, no `<?=`). Replacing its parser with ANTLR would leave every one of those semantic defects. **Ruling: replace.** The new frontend repairs all of the above; nothing of the legacy lowering is reused (the `Z-combinator` encoding is superseded by self-applied `:loop`-marked lambdas with cells, which also makes safepoints and tail marks work).

Migration mechanics (the F2 pattern): `:yang.php/antlr` is installed beside `:yang.php/legacy` in `yin.repl.frontends/standard` with no fallback; `test/yang/php_test.clj`'s eleven tests are re-expressed as source programs in the new corpus (their expectations change where the legacy was wrong: `null` is `:php/null`, not `nil`); the demo `src/cljs/datomworld/demo/compilation_pipeline.cljs` and `src/dev/subsystems.edn` are the other two consumers to retarget; `:yang.php/legacy` retires (file deleted, no shim: the repo has no backward-compatibility obligation) at slice P8 when the new frontend's corpus covers the legacy test programs. **What stays refused** after migration: `goto`, return by reference, `declare(encoding)`, `__destruct`/`__halt_compiler`, `eval` until its slice, `$$` and `extract` until the dynamic-scope slice, extension surfaces (`ext/*`: PDO, curl, mbstring, gd, intl, openssl, sockets, pcntl beyond signals) each a bound surface per ADR 0004, `preg_*` until a portable regex engine exists (a PCRE-compatible engine is large and host regex engines diverge; refusing is honest), `date`/`time`/`microtime`/`rand`/`random_int`/`uniqid` as effects with explicit outcomes.

## B3. The grammar route, parser worker, CST to UAST, scope analysis

- **Package**: grammars-v4 `php` (`PhpLexer.g4`, `PhpParser.g4`, MIT), vendored verbatim; one Java helper `Java/PhpLexerBase.java` (modes HTML, Script, CSS, PHP, Heredoc; `<?=`, heredoc/nowdoc end detection, string interpolation lexing) fetched by digest with the same licence-provenance block; `PhpParser.g4` has **no superClass and no predicates**, so the parser half is action-free on every target and only the lexer base is per-target. Upstream has Java, C#, C++, JavaScript, Python3 and Antlr4ng ports of the base but no Dart, so the local parser is JVM-first; a Node local parser is plausible later via the upstream JavaScript base (the §4.2 per-target helper versioning applies). Entry rule `htmlDocument`. The grammar's non-standard `typeParameterListInBrackets` (generics) is refused as `:yang.php/not-php`.
- **Worker**: `yang.antlr.cst/make-worker` unchanged; profile `{:grammar-id :make-lexer :make-parser :start (.htmlDocument p)}`. Mode-dependent tokens (interpolated string parts, heredoc) export as ordinary tokens with real spans; the exporter's synthetic-token rule needs no change. The REPL completeness probe: PHP has no ASI; "error at EOF after a complete `<?php` prefix" is a reliable `:incomplete` signal only for unbalanced braces/parens, so the probe is the brace-balance-plus-reparse rule with an explicit submit fallback.
- **CST inventory highlights**: `htmlDocument`, `inlineHtml`, `phpBlock`, `topStatement`, `namespaceDeclaration`, `useDeclaration`, `functionDeclaration`, `classDeclaration` (class/trait/interface via `classEntryType`), `enumDeclaration`, statements (`ifStatement`, `whileStatement`, `doWhileStatement`, `forStatement`, `foreachStatement`, `switchStatement`, `tryCatchFinally`, `globalStatement`, `staticVariableStatement`, `echoStatement`, `inlineHtmlStatement`, `declareStatement`, `gotoStatement`, `unsetStatement`, `returnStatement`, `breakStatement`/`continueStatement` with levels), `expression` labelled alternatives (`AssignmentExpression` including `= &`, `CoalesceExpression`, `SpaceshipExpression`, `InstanceOfExpression`, `IncludeExpression`/`RequireExpression`, `castOperation`, `newExpr`, `chain`/`memberAccess`, `arrayCreation`/`arrayItem` with `&`, `lambdaFunctionExpr`/`LambdaFn`, `matchExpr`, `yieldExpression`, `string`/`interpolatedStringPart`, `attributes`), `formalParameter` with `&`, `typeHint` unions, `argumentName`.
- **Scope analysis (`yang.php.antlr.scope`)**: scopes are the unit (global), each function/method/closure/arrow; records `{:kind :params (with by-ref and variadic flags) :assigned :statics :globals :uses [{:name :ref?}] :arrow-captures :dynamic? :this?}`; `$this` is a parameter of methods; arrow functions compute `:arrow-captures` as the free variables of their body that the enclosing scope binds; `:dynamic?` per B1; superglobals resolve to `$GLOBALS` entries everywhere. Name resolution for functions, classes and constants is compile-time: `namespace` and `use` statements resolve every unqualified name to a fully qualified name per PHP's rules (functions and constants fall back to the global namespace at run time, which the lowering encodes as a two-key lookup `php/fn-lookup "ns\\f" "f"`).

## B4. The lowering-arms inventory and the prelude inventory

Context: `{:lower :scope :loop [{:brk :cont}] (a stack, for `break 2`) :ret :class-ctx :static-ctx :gen :strict-types :unit}`; name supply `%<prefix><cst-id>` (`%` cannot begin a PHP identifier or variable). Marks: `:loop` on loop lambdas, `:call` on function code lambdas, `:line` on statement starts (for ticks; inserted only under a ticks profile).

| Group | Arms | Lowering |
|---|---|---|
| Output | `echo`, `print`, `inlineHtml`, `printf`/`sprintf`/`number_format` (prelude) | `php/echo` appends to the output-buffer stack |
| Variables | `$x`, `$this`, `$GLOBALS[...]`, superglobals, `$$x` (dynamic profile), list()/[] destructuring with keys and refs | `php/local-get`/`cell/get` through the marker deref; writes `php/assign` (deref-aware) |
| Operators | arithmetic (overflow to float), string `.`, `.=`, comparison (`==`, `===`, `<=>`, `<` with the juggling table), logical (`&&`, `and` precedence difference is the grammar's), `??`, `??=`, ternary and short ternary, bitwise (int only; on strings bytewise), casts `(int)`/`(string)`/`(array)`/`(object)`/`(bool)`/`(float)`, `instanceof`, `clone`, `new`, error-suppression `@` (diagnostics muted for the expression), increment/decrement (string and null rules), `print` as expression | `php/*` applications |
| Arrays | literals with keys/spread/refs, `$a[k]`, `$a[]`, nested writes, `unset($a[k])`, `isset`/`empty`, `foreach` by value, by key, by reference, with `list` | path updates on the value in the cell; `php/array-*` |
| Strings | single-quoted, double-quoted with interpolation (`$x`, `{$x->y}`, `${x}`, `$a[k]`), heredoc/nowdoc (indentation removal rule of 7.3), escape sequences incl. `\u{...}` as UTF-8 bytes | `php/str-concat` of parts; each part `php/to-string` |
| Control | if/elseif/else, alternative syntax (`if: endif;`), while/do/for (comma expressions), switch (loose `==`, fallthrough, `continue` inside switch acts as `break` with the 7.3 warning), `break n`/`continue n`, `match`, `return`, `goto` refused | loops self-applied `:loop` lambdas; multi-level escapes index the `:loop` stack |
| Functions | declarations (hoisted or in place), closures with `use`, arrow fns, defaults, by-ref params, variadics, named args, nullable/union type declarations with coercion or `TypeError`, return types, `static` vars, `global`, first-class callable `f(...)`, callables | `php/make-function name spec code statics`; calls `php/call callee args named-args class-ctx` |
| Classes | class/abstract/final/readonly, properties (typed, defaults, static), constants (`final`, visibility), methods, constructors with promotion, `static`/`self`/`parent`, interfaces, traits (`use`, `insteadof`, `as`), enums (pure and backed), attributes (parsed, stored as data on the descriptor for `ReflectionAttribute` later, no runtime effect), magic methods, `__toString` in string contexts, `instanceof` | `php/declare-class` at the declaration point into the class table; traits copied there |
| Exceptions | try/catch/finally, multi-catch, throw expression, rethrow | handler stack with `php/raise`; `finally` via `php/try-finally` |
| Generators | yield, yield key => value, yield from, send/throw/getReturn | C2 core under `php/gen-*` |
| Modules | `include`/`require`/`_once`, `namespace`, `use`, `declare(strict_types)`, `declare(ticks)` | B6 |
| Refused | goto, return by reference, eval (until its slice), `$$` outside the dynamic profile slice, `__halt_compiler`, `declare(encoding)`, `list()` with references nested beyond one level in Phase 3 | `:yang.php/unsupported {:construct}` |

**Prelude inventory**, module `php` (reserved), hook prelude `phpsp`; `php/init!` builds the class table with the builtin classes and interfaces (`stdClass`, `Closure`, `Generator`, `ArrayObject` deferred, `Traversable`, `Iterator`, `IteratorAggregate`, `ArrayAccess`, `Countable`, `Stringable`, `JsonSerializable`, `UnitEnum`/`BackedEnum`, the `Throwable` hierarchy), the function table with the supported builtins, `$GLOBALS` from the request record, the output-buffer stack, the error-handler cell, the included-files dict, and flips `php.rt/state` to `:php/ready`.

| Area | Supported core (Phase 3) | Est. lines |
|---|---|---|
| Type juggling | `to-int`, `to-float`, `to-string` (precision 14), `to-bool`, `to-array`, numeric-string classification, `==`/`<=>`/`===` tables, `gettype`, `is_*`, `intval`/`floatval`/`strval`/`boolval`/`settype`, `var_dump`/`print_r`/`var_export` text (serialize_precision -1, the `#n` object ids, indentation rules) | 700 |
| Arrays | `count`, `array_keys/values/merge/merge_recursive/combine/flip/slice/splice/map/filter/reduce/walk/search/key_exists/in_array/unique/reverse/sum/product/fill/fill_keys/pad/chunk/column/diff/intersect(+_key/_assoc)/push/pop/shift/unshift/key_first/key_last/is_list`, `sort/rsort/usort/uasort/uksort/ksort/krsort/asort/arsort` (stable since 8.0, flags subset), `range`, `compact`/`extract` (dynamic profile), `list`, `implode`/`explode`/`str_split`, `array_key_exists`, `iterator_to_array`, `current/key/next/reset/end/prev` (the internal pointer is a per-array-value position in the cell content) | 900 |
| Strings (byte strings) | `strlen`, `substr`, `strpos/stripos/strrpos`, `str_replace/str_ireplace`, `str_contains/starts_with/ends_with`, `trim/ltrim/rtrim`, `strtolower/strtoupper/ucfirst/ucwords/lcfirst` (ASCII, PHP 8 locale-insensitive), `str_repeat/str_pad/strrev/strcmp/strcasecmp/strncmp/strstr/strrchr/substr_count/wordwrap/nl2br/htmlspecialchars/html_entity_decode (basic)/addslashes/stripslashes/chunk_split`, `sprintf/printf/vsprintf/number_format`, `ord/chr/bin2hex/hex2bin/dechex/hexdec/decbin/bindec/octdec/decoct/base_convert`, `md5/sha1/crc32/hash('sha256')` via `data`/BLAKE3-era pure kernels only if present, else refused, `json_encode/json_decode` (flags subset, `JSON_THROW_ON_ERROR`, pretty print), `serialize/unserialize` deferred, `preg_*` refused | 900 |
| Math | `abs/ceil/floor/round` (PHP's pre-rounding rule)/`intdiv/fmod/pow/sqrt/min/max/array_sum`, `PHP_INT_MAX/MIN/SIZE`, `PHP_FLOAT_*`, `M_PI`, `is_nan/is_finite/is_infinite`, `rand/mt_rand/random_int` as effects | 250 |
| Functions and classes | call binding (positional, named, variadic, by-ref, defaults, type coercion/`TypeError`, `ArgumentCountError`), `func_get_args`, `call_user_func(_array)`, `is_callable`, `function_exists`, class table ops, `get_class`, `get_parent_class`, `method_exists`, `property_exists`, `class_exists`, `get_object_vars`, `spl_object_id`, visibility checks, magic dispatch, `Closure::bind/fromCallable/call`, enums `cases/from/tryFrom` | 700 |
| Control and errors | handler stack (the Python pattern), `php/raise`, `finally`, escapes with levels, `Throwable` classes with `getMessage/getCode/getPrevious/getTrace (empty)/getLine (derived at the boundary)`, `set_error_handler/restore_error_handler/set_exception_handler/trigger_error/error_reporting`, warning emission `php/warn` | 350 |
| Generators and iteration | C2 core under `php/`, `Iterator` protocol, `foreach` driver, `yield from` over arrays and generators | 300 |
| Request and output | `$_*` construction, `php/echo`, `ob_*` stack, `header/headers_sent/http_response_code/setcookie`, `exit`, `php_sapi_name` ("cli" or "yin-http" pinned), `getenv` from the record only, `PHP_EOL`, `PHP_VERSION` pinned, `error_log` as an effect | 300 |
| Module runtime | `include/require/_once`, included-files dict, autoload class map, `define/constant/defined`, namespaces lookup | 250 |

Refusal posture: a builtin outside the supported core is `:yang.php/unknown-builtin` at lowering (warning) and `Error: Call to undefined function` at run time; an extension surface is bound per ADR 0004 as its own slice with an effect schema and an interpreter; nothing is stubbed to return success.

## B5. Value encoding and the paradigm rows finalized

| PHP type | Representation |
|---|---|
| int | exact integer within `[-2^63, 2^63-1]` (C3 carriers; on JS a value past 2^53 is a `BigInt` carrier); overflow of an operation demotes to float |
| float | Jing float64 content (carrier on JS); one NaN; `-0.0` preserved; `INF` |
| string | **byte string**: a host string whose every char is one byte 0..255 (latin-1 carrier of the bytes); source literals contribute their UTF-8 bytes; `strlen` is bytes; output writes the bytes raw; `mb_*` is a separate slice over this representation. Owner decision B8.2 states the alternative. |
| bool | host boolean |
| null | `:php/null` |
| array | persistent ordered-map value in the holder's cell or slot (B1) |
| object | cell ref (handle); `clone` allocates a fresh cell |
| reference | `{:php/ref c}` marker in a variable cell, array slot or property slot |
| closure | object cell of class `Closure` holding the code closure, bound `$this`, scope class and `use`d values or markers |
| enum case | singleton object cell per case |
| resource | opaque handle data from a bound surface (`{:php/resource kind id}`), none in Phase 3 |
| callable | string, `[class-or-obj, name]` array, or `Closure`; resolved at call |

```text
+----------------------------+--------------------------------------------------+------------+
| Paradigm                   | Universal AST target                             | Reference  |
+============================+==================================================+============+
| Value-copy ordered maps    | Arrays are persistent ordered-map values in      | PHP        |
| with reference slots and   | cells (O(1) copy); references are markers in     |            |
| request scope (new row,    | slots; objects are cell handles; locals are      |            |
| finalizing D5)             | cells, dynamic-scope functions a heap symbol     |            |
|                            | table; the global scope is the $GLOBALS dict; a  |            |
|                            | request is one task reading one request record   |            |
|                            | and appending ordered output records; warnings   |            |
|                            | are diagnostic records, fatals are throws        |            |
+----------------------------+--------------------------------------------------+------------+
| PHP number model           | int = exact integer bounded at 2^63 with overflow|            |
|                            | to float (detected by exact promotion, demoted   |            |
|                            | by rule); float = float64 content (carrier on    |            |
|                            | JS); echo at precision 14, var_dump shortest;    |            |
|                            | array keys coerce int-like strings and floats to |            |
|                            | int                                              |            |
+----------------------------+--------------------------------------------------+------------+
| Class-based OO (existing   | Class descriptors in cells, traits copied at     | PHP (with  |
| row, PHP entry qualified)  | declaration, interfaces as dispatch contracts,   | Java,      |
|                            | runtime visibility against a class context       | Python)    |
+----------------------------+--------------------------------------------------+------------+
```

## B6. include/require over the linker, safepoints, diagnostics, the corpus

- **Units and names**: a PHP file compiled as unit `src/lib/util.php` becomes linker module `phpm.src$lib$util` (injective mangling of the normalized relative path; `/` and `.` to `$`-escapes); shape as Python's: `(do (require 'php) (yin/def spec {:path "src/lib/util.php" :strict-types 0}) (yin/def body (fn [%scope %ctx] ...)))`. `include 'x.php'` with a **literal** path (after constant folding of `__DIR__ . '/x.php'`, which the lowering performs since `__DIR__` is the unit's declared path, not a filesystem fact) lowers to the I4-a statement-time delivery thunk; the body runs in the **including scope** (passing the including scope's symbol table, which is why an including function is on the dynamic profile; at top level it is `$GLOBALS`); the body's `return` value is the expression's value, else `1`. `_once` consults the included-files dict. A missing `include` is `Warning` plus `false`; a missing `require` is `Error` (PHP 8: `Error`, fatal). Computed paths are a diagnostic (ruling 10's literal-name restriction; a published literal path map can later interpret them). Conditional declarations inside included files execute in place, as PHP does. Cycles of `include` are a runtime matter (the included-files dict), not a linker cycle: static `include` edges are pinned in `:requires` only for delivery, and SCC publication handles mutual pins. **Autoload**: `spl_autoload_register` with a guest callable is supported only when the composition also publishes a literal **class map** (class name to unit) as part of the unit set; the autoloader then runs on a class miss and may `require` only literal paths in that map. Composer's generated class map is admissible as data; its filesystem resolution is not.
- **Namespaces** are compile-time; nothing links per namespace.
- **Safepoints**: `:loop` and `:call` for signals (`pcntl_signal` handlers run at hooks via `stream/poll!` on a signal stream, `pcntl_async_signals` is the default and only mode) and for recursion depth if a composition wants a bound (PHP has none; default off); `:line` under a ticks profile for `declare(ticks=N)` and `register_tick_function`. `max_execution_time` and `set_time_limit` are wall-clock in PHP, so nondeterministic: the profile offers a deterministic **safepoint-count budget** instead, raising the fatal with PHP's text; a recorded departure. No thread switches: PHP is single-threaded; one request is one task.
- **Diagnostics**: compile-time `:yang.php/syntax`, `:yang.php/unsupported`, `:yang.php/not-php`, `:yang.php/unknown-builtin` (warning), `:yang.php/compile-error` for PHP's compile-time fatals (redeclared function in one unit, `break 0`, duplicate parameter, `$this` reassignment); runtime guest warnings on the guest-diagnostics stream; fatals as `Error` throws; uncaught text pinned.
- **Differential corpus**: pinned PHP 8.3.x CLI with the pinned ini; stdout plus the diagnostics stream rendered in PHP's `Warning: ... in <unit> on line N` form with the path replaced by the unit name and the line derived from the side table (owner decision 2 of 8.5.1 reused) compared against PHP's stderr with paths normalized. Groups: juggling (every `==` pair of the PHP 8 table, numeric strings, arithmetic warnings/`TypeError`), arrays (key coercion, order, next index after unset, copy-on-write observable through references, nested writes, `foreach` by reference pitfalls, sort stability), references (`&` in all five positions, `global`, `static`), strings (bytes, interpolation forms, heredoc indentation, `precision=14` output, `var_dump` text), functions (named args, by-ref, defaults, variadics, type coercion vs `strict_types`), classes (traits precedence, visibility, static/late static binding, magic methods, readonly errors, enums, `match`), exceptions (finally ordering, multi-catch, `Error` hierarchy), generators, request (superglobals from a fixture record, headers after output warning, `ob_*`), inclusion (`_once`, return values, scope sharing, missing include warning vs require error), safepoints (ticks counting, signal at a hook).

## B7. Slice sequencing, acceptance contracts, and the yang.core lift

Depends on L1 to L4 (design A) landing first; PHP uses the same lifted namespaces and adds nothing to them except an optional `:dynamic-scope` helper for the symbol-table dict (which Python's module dict already is: `py/global-get`/`py/global-set` generalize to `yang.core`'s `dict-scope-get`/`-set` if lifted; otherwise copied).

| Slice | Scope | Acceptance (all four VMs on JVM, Node, Dart unless noted) |
|---|---|---|
| P0 | Grammar package `antlr/php/manifest.edn`, worker profile, CST export of modes (inline HTML, heredoc, interpolation), licence block | packets for mixed HTML/code with byte spans; `?>` newline rule preserved; heredoc tokens span their source; refusal of the generics syntax |
| P1 | Core: echo/inline HTML/output stack, scalars and the juggling table, operators with overflow to float, strings as byte strings, control flow, functions with cells, the `php` prelude skeleton with `init!`, bundled and linked profiles, boundary renderer (`var_dump` text) | mixed text/code output order fixture; `echo 0.1+0.2` prints `0.3`, `var_dump(0.1+0.2)` prints `float(0.30000000000000004)`; `PHP_INT_MAX + 1` is float on every host; float-address goldens for the `php` prelude agree on three hosts |
| P2 | Arrays: the value, key coercion, nested writes, foreach, the core array builtins, sorting | key coercion fixture table; `$b = $a; $b[] = 1` leaves `$a` unchanged; order after unset and re-add; stable sort parity |
| P3 | References, `global`, `static`, closures with `use`/`use (&)`, arrow fns, destructuring, by-ref params, foreach by reference | the five `&` positions; the reference-in-copied-array fixture; `foreach (&$v)` leftover binding |
| P4 | Classes, interfaces, traits, enums, `match`, exceptions, magic methods, visibility, typed properties and declarations (coercive and strict), named args; generators on the C2 core | classes and exceptions groups; a `TypeError` for `f("x")` with `int $x` under `strict_types=1` and coercion under 0; `instanceof` across units of one task |
| P5 | Request context: `$_*` from the request record, `ob_*`, headers/cookies/status as response records, `exit`, error handler, warnings stream | superglobals reproducible from a fixture record (exit criterion 3); headers-after-output warning; two requests in two tasks share nothing (exit criterion 5) |
| P6 | include/require over the linker, `_once`, namespaces/`use`, constants, the autoload class map, publication and DHT import parserless on Node/Dart, dynamic scope profile for including functions | inclusion group; a JVM-published `phpm.*` unit set imports on Node and Dart with equal output; computed path diagnostic; missing include warning vs require `Error` |
| P7 | Safepoints: `phpsp` hooks, `declare(ticks)`, `register_tick_function`, `pcntl_signal`, the safepoint budget | tick counts equal PHP's for the statement fixture; a signal at a `:loop` hook runs the handler; empty signal stream never parks |
| P8 | Legacy retirement: the `php_test.clj` programs in the new corpus, consumers retargeted, `yang/php.cljc` deleted, `:yang.php/legacy` removed from the standard catalog | REPL `<?php $x = 1; echo $x;` prints `1`; all prior PHP test programs pass with corrected expectations; no reference to `yang.php` remains |
| P9 | Builtin surface slices (strings II, arrays II, JSON, math II), `$$`/`extract`/`compact` dynamic scope, `eval` as a compilation request | each a corpus group with PHP parity; `eval` shares the caller's symbol table |

Deferred: `preg_*` (portable regex engine), `mb_*`, `serialize`, Reflection, SPL data structures, `DateTime` beyond the time effect, sessions, PDO and every `ext/*` surface (bound per ADR 0004), return by reference, `goto`, `__destruct`.

**yang.core lift enumeration for PHP** (beyond L1 to L4): nothing new is required; PHP exercises `yang.core.stage` (ok-check on `:yang.cst/ok`), `yang.core.lower` (name supply, diagnostics with `:yang.php` namespace), `yang.core.module` (`module-symbol` with prefix `phpm`, `declare-free-name`), `yang.core.publication` (with `php/imported-unit-names` from the literal `include` edges and the class map), `yang.core.prelude` (emitters, `admit` with `host-names` adding the byte-string `data` exports). If the owner schedules the `yrt` shared prelude (A12.5), PHP is the second consumer of its escape machinery, generator core, ordered map and module skeleton; until then PHP copies the patterns under `php/` as TS does under `js/`.

## B8. Owner decisions (yang.php)

1. Reference runtime PHP 8.3 at a pinned patch (recommended) versus 8.2.
2. **Strings**: byte-faithful (recommended: PHP semantics are bytes; `strlen("é")` is 2) versus code-point strings with a documented departure. Byte-faithful needs a `data` export for byte-string slicing and the output boundary writing raw bytes; code-point strings would make every `mb_*` function trivial but break `strlen`, `substr`, binary data and `md5` parity.
3. The dynamic-scope profile rule (recommended) versus dict scopes for every function (simpler, slower, and it loses the un-boxing path).
4. Warnings as diagnostic-stream records (recommended) versus a guest `Warning` exception class; the record model matches ADR 0004 and keeps `@` cheap.
5. The deterministic safepoint budget as the `max_execution_time` stand-in (recommended) versus refusing the ini.
6. Replace-and-delete the legacy at P8 (recommended) versus keeping `:yang.php/legacy` installed until a later date.
7. The autoload class-map rule (recommended) versus refusing `spl_autoload_register` entirely in Phase 3.

## B9. Acceptance-matrix sketch

```text
+----------------------+-------------------------------------------------------------------+
| Dimension            | yang.php claims                                                   |
+======================+===================================================================+
| Host                 | CLJ: parse + lower + run; CLJS, CLJD: packets and rows, run       |
| Parser deployment    | local worker (JVM); configured service; parserless (rows); a Node |
|                      | local parser is a later claim via the upstream JavaScript lexer   |
|                      | base, never assumed                                               |
| Support claim        | syntax: PHP 8.3 surface of the pinned grammar (generics refused); |
|                      | semantics: the P1-P7 groups; lowering: executable for the listed  |
|                      | arms; stdlib: the B4 core, everything else honestly refused       |
| Input shape          | mixed PHP text (13.2 names it), heredoc with indentation, long    |
|                      | interpolated strings, deep arrays, large class hierarchies with   |
|                      | traits, many small included units, a request record fixture per  |
|                      | corpus program                                                    |
| Laws (13.1)          | 2, 3, 4, 5, 7 (warnings are data, never stderr), 9 (PHP CLI       |
|                      | parity on stdout and the rendered diagnostics), 10, 12; Phase 3   |
|                      | exit criteria 1-5 are direct fixtures (P1, P2/P3, P5, P4, P5)     |
| Fault                | a request task cancelled mid-output leaves a complete response    |
|                      | prefix and a cancellation record, never a half-record; duplicate  |
|                      | delivery of a unit publication is idempotent                      |
+----------------------+-------------------------------------------------------------------+
```

---

## C. Cross-design notes for the orchestrator

- Both designs assume the four lifts L1 to L4 land first; they are small, address-neutral Clojure moves with Python goldens as the regression net, and they close D1, D2 and D7. Nothing else in Python changes for either language.
- The `yrt` shared linked prelude is the one lift that is *not* address-neutral; it is recorded as an owner-scheduled profile bump (A12.5) and neither design depends on it.
- Both frontends are JVM-parsed and three-host-executed, the Python posture; neither advertises offline parsing on Node or Dart.
- Documentation amendments the designs imply: §3.2/§2.1/§8.5.2 stale text (D3, D4); §8.3 new rows (A10, B5); §8.5's open module-variable question answered per language (D6); §8.6 and §8.7 superseded in detail by these designs once accepted; `yang.antlr.md`'s implementation-status paragraph to say JavaScript is unparked as TypeScript.
