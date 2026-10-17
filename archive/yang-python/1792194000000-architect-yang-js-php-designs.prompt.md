Created-GMT: 2026-10-16 19:30:00 GMT
Created-Local: 2026-10-17 02:30:00 +07 (+0700)
Coding-Agent: claude-cloud
Session-ID: 14d68625-1020-4b19-8eac-fd2c5d0457b6

# Task: Architect designs — yang.javascript and yang.php frontends

Role: Lead System Architect

Deliver TWO complete frontend designs in your response (findings only — do not edit any file):
1. **yang.javascript** — the prototype/dynamic pilot (Phase 1's second half).
2. **yang.php** — the web/associative-array pilot (Phase 3), including the migration of the legacy
   handwritten `yang/php.cljc`.

Main tree (read-only): /Users/sto/workspace/datomworld at master. Both designs target the LANDED
architecture; judge committed state.

READ FIRST:
- docs/design/yang.antlr.md — wholly: §3 (the frontend SPI and the F1 catalog), §4 (the ANTLR host
  boundary), §5 (ingestion), §8 (the paradigm table rows: prototype/dynamic and PHP's ordered-map/
  value-copy/reference semantics), §9 (stdlib/FFI), §11 (determinism), 8.5.1-8.5.6 (all landed Python-era
  rulings you extend), §12 (the roadmap: JavaScript is Phase 1's second pilot; PHP is Phase 3).
- docs/design/datom.world.md (the six invariants).
- The Python frontend as the template: src/cljc/yang/python/antlr/{lower,prelude,scope,uast,render}.cljc
  and src/clj/yang/python/antlr/parser.clj — structure, sizes, and which parts proved language-specific.
- The already-neutral seams a JS/PHP frontend reuses: yang.frontend (F1 catalog), yang.stage, yang.tails,
  yang/antlr/packet.cljc, yin.vm.semantic + backends, yin.vm.linker (+ publish, admission), the statement-
  time import machinery (I4-a), SCC publication (I5), reload (I6), the float64 carrier (8.5.5).
- The recorded JS constraints: Number within ±(2^53-1)/native BigInt; the float64 carrier mandatory for
  integral doubles on JS (8.5.5); jobs run to completion — :loop/:call safepoints for interruption only,
  never thread switches (8.5.2); "JavaScript calls are not automatically Yin macros" (§9).
- The legacy PHP: src/cljc/yang/php.cljc (handwritten tokenizer/parser, limited lowering) and the audit
  notes in yang.antlr.md (replacing its parsing does not repair its semantic limitations).
- The known-gaps ledger and routing-status.md (fable context: your own prior rulings).

EACH DESIGN MUST COVER:
1. The paradigm specification — the semantic rulings that have no Python precedent:
   JS: prototype-chain resolution on the cell substrate (the link slot + chain-walking lookup), `this`
   binding rules, `==`/`===` and coercion tables, truthiness, var/let/const scoping and hoisting, classes
   as prototype sugar, ES module shape over the store model vs the landed import path, async/event-loop
   posture (jobs run to completion; async/await deferred or ruled), generators (map onto the landed C2
   continuation machinery), optional chaining/spread/destructuring/labels.
   PHP: ordered-array semantics (value/copy, reference semantics via &), the dict/array duality on the
   cell substrate, `global`/superglobals, mixed HTML/code mode and the request-context story over the
   stream model (per ADR 0004's doctrine), include/require over the linker, the legacy php.cljc migration
   (replace vs wrap), classes/traits on the landed class machinery, PHP's coercion table.
2. The grammar route: which vendored ANTLR grammars (the official JavaScript and PHP grammars), the parser
   worker shape per §4, CST node inventory highlights.
3. CST->UAST shape and the scope-analysis design per language.
4. The lowering-arms inventory (the big surface — enumerate the expression/statement groups and note
   which lower to shared machinery vs language-specific arms).
5. The prelude inventory per language (builtins, exceptions, classes, the paradigm runtime).
6. Value encoding and the paradigm-table rows finalized (the number model per language: JS doubles+BigInt;
   PHP int/float and the string-numeric coercion story).
7. The import/module story per language over the landed linker (ES modules; include/require).
8. Safepoint story per language (JS per 8.5.2; PHP's ticks/pcntl per the C4 safepoint design's Q4).
9. Diagnostics and the differential-corpus plan (pinned runtimes: node; php).
10. Slice sequencing with acceptance-test contracts per slice, following the C2/C3 style.
11. The yang.core lift: exactly which seams the second/third frontend pulls from yang.python into shared
    namespaces (publication core, packet harness, prelude mechanisms) — enumerate namespaces and functions.
12. An "Owner decisions" section per design: every open decision with your recommendation.

Distinguish architectural defects in the current docs from gaps and deferred work. CLJ/CLJS/CLJD
portability throughout. End each design with its acceptance-matrix sketch.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
