Created-GMT: 2026-10-16 19:40:00 GMT
Created-Local: 2026-10-17 02:40:00 +07 (+0700)
Coding-Agent: claude-cloud
Session-ID: 93cda4c1-ec00-4fd9-8fe6-1d63c63eac89

# Task: Architect designs — yang.typescript and yang.php frontends

Role: Lead System Architect

Deliver TWO complete frontend designs in your response (findings only — do not edit any file):
1. **yang.typescript** — the typed superset pilot (Phase 1's second half). TypeScript is a superset of
   JavaScript: one frontend covers both languages. The RUNTIME semantics are JavaScript's; the type layer
   is additive. The design must say precisely what that means.
2. **yang.php** — the web/associative-array pilot (Phase 3), including the migration of the legacy
   handwritten `yang/php.cljc`.

Main tree (read-only): /Users/sto/workspace/datomworld at master. Both designs target the LANDED
architecture; judge committed state.

READ FIRST:
- docs/design/yang.antlr.md — wholly: §3 (the frontend SPI and the F1 catalog), §4 (the ANTLR host
  boundary), §5 (ingestion), §8 (the paradigm table rows: prototype/dynamic and PHP's ordered-map/
  value-copy/reference semantics), §9 (stdlib/FFI), §11 (determinism), 8.5.1-8.5.6 (all landed Python-era
  rulings you extend), §12 (the roadmap: the typed pilot is Phase 1's second half; PHP is Phase 3).
- docs/design/datom.world.md (the six invariants).
- The Python frontend as the template: src/cljc/yang/python/antlr/{lower,prelude,scope,uast,render}.cljc
  and src/clj/yang/python/antlr/parser.clj — structure, sizes, and which parts proved language-specific.
- The already-neutral seams a TS/PHP frontend reuses: yang.frontend (F1 catalog), yang.stage, yang.tails,
  yang/antlr/packet.cljc, yin.vm.semantic + backends, yin.vm.linker (+ publish, admission), the statement-
  time import machinery (I4-a), SCC publication (I5), reload (I6), the float64 carrier (8.5.5).
- The recorded runtime constraints (TypeScript runs on JavaScript): Number within ±(2^53-1)/native BigInt;
  the float64 carrier mandatory for integral doubles on JS (8.5.5); jobs run to completion — :loop/:call
  safepoints for interruption only, never thread switches (8.5.2); "JavaScript calls are not automatically
  Yin macros" (§9).
- The legacy PHP: src/cljc/yang/php.cljc (handwritten tokenizer/parser, limited lowering) and the audit
  notes in yang.antlr.md (replacing its parsing does not repair its semantic limitations).
- The known-gaps ledger and routing-status.md (your own prior rulings; the C4 rulings 8.5.6).

THE YANG.TYPESCRIPT DESIGN MUST COVER (the JS runtime semantics are presupposed — prototype-chain
resolution on the cell substrate, `this` binding, `==`/`===` and coercion tables, truthiness,
var/let/const scoping and hoisting, classes as prototype sugar, generators on the landed continuation
machinery, jobs-run-to-completion safepoints, ES modules over the store model — RULE these explicitly AND
add the type layer):
1. The superset posture: the frontend's grammar is the TypeScript grammar (it accepts plain JavaScript);
   the canonical runtime semantics are JS's; the type layer is compile-time. State what the frontend DOES
   with types: parse and erase (default), versus a checking profile (diagnostics, not runtime semantics).
   Recommend the profile shape (strict flags as profile data, per the F1 manifest/options pattern).
2. null vs undefined on the yin value model (two distinct guests or one?), strictNullChecks as a profile
   decision, and the interaction with the float64 carrier and the dict-key rules.
3. The type-system surface to support per slice: annotations, interfaces, type aliases, generics,
   unions/intersections, enums (which have RUNTIME presence — rule their lowering), namespaces, decorators
   (deferred?), access modifiers (compile-time only), type narrowing, utility types, declaration files.
   What is checked vs erased vs refused per slice; what a type error IS in the diagnostics model (§10).
4. The boundary story: how yin values and host values type at the edges (the prelude's typed surface —
   .d.ts-style bindings for the prelude builtins? yin-side type stubs?); how TS types relate to the
   yin.vm value model (they must not: the runtime has no types — rule the erasure precisely).
5. The grammar route: the official TypeScript/JavaScript ANTLR grammar, the parser worker shape per §4,
   CST node inventory highlights (the type-annotation subtrees are parse-only).
6. The lowering-arms inventory: the JS expression/statement groups (arrow functions, destructuring,
   spread/rest, template literals, optional chaining, classes, generators on the landed continuation
   machinery, async/await posture) + the type-annotation stripping arms.
7. The prelude inventory: the JS builtins surface (Object/Array/JSON/Math), the typed bindings question,
   and what the prototype runtime adds.
8. The import/module story: ES modules over the landed linker (statement-time imports, SCC, reload);
   node_modules-style resolution REFUSED per the no-filesystem doctrine; the .d.ts story for consumed
   modules.
9. Safepoints (per 8.5.2: interruption only), diagnostics, and the differential-corpus plan (pinned
   node runtime; the TS compiler's own diagnostics as reference where behavior, not types, is tested).
10. The prototype/dynamic paradigm rows finalized + the JS/TS number-model row (doubles+BigInt; the
    float64 carrier mandatory).
11. Slice sequencing with acceptance-test contracts per slice (JS-core first, then type layers), and the
    yang.core lift enumeration (which seams the second frontend pulls from yang.python into shared
    namespaces — publication core, packet harness, prelude mechanisms; name namespaces and functions).

THE YANG.PHP DESIGN MUST COVER:
1. The paradigm specification: ordered-array semantics (value/copy, the int/string key duality and its
   coercions, insertion order), reference semantics via & on the cell substrate, `global`/superglobals,
   the coercion table (PHP's string-numeric comparisons), classes/interfaces/traits on the landed class
   machinery, the mixed HTML/code mode and the request-context story over the stream model (per ADR
   0004's doctrine), include/require over the linker, null semantics.
2. The legacy migration: replace vs wrap for src/cljc/yang/php.cljc (the audit says replacing its parsing
   does not repair its semantic limitations — rule what the new frontend repairs and what stays refused).
3. The grammar route (the official PHP ANTLR grammar), parser worker, CST->UAST, scope analysis (PHP's
   function scoping, superglobals, &).
4. The lowering-arms inventory and the prelude inventory (PHP's builtin surface is enormous — rule the
   supported core and the refusal posture).
5. Value encoding and the paradigm-table rows finalized (int/float, the array duality, null).
6. The import/module story (include/require over the linker), safepoints, diagnostics, and the
   differential-corpus plan (pinned php runtime).
7. Slice sequencing with acceptance-test contracts per slice, the yang.core lift enumeration, and an
   "Owner decisions" section per design.

Distinguish architectural defects in the current docs from gaps and deferred work. CLJ/CLJS/CLJD
portability throughout. End each design with its acceptance-matrix sketch.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
