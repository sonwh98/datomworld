Created-GMT: 2026-11-23 15:04:50 GMT
Created-Local: 2026-11-23 22:04:50 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 0e1a01f2-ffe7-428a-b1a3-3ec59f0616c7
Task: Step-7 review - yang.preludes P4a (ts descriptor + frontend contract + composition-swap fixture)
Role: Reviewer (independent, read-only)
Implementers:
- Reviewing-Model: glm-5.3 (glm-5.4 pool returned 529 overload; fallback per assignment)
- Engineer: gpt-6.2-sol (codex) | Round: 1795440552499 | Diff: uncommitted working tree on feat/p4-ts-clojure-core, base 0c36043d

Scope reviewed: git diff (10 files, +1664/-364) plus whole-file reads of every new file
(contract.cljc, profiles.cljc, clj_runtime.cljc, frontend.cljc, both test files, the packets
EDN) and the P3 template (yang/python/antlr contract+profiles+clj_runtime,
yang.core.semantics - unchanged by this diff, i.e. reused, not forked).

Focus 1 - 6.4 authority in the ts path: VERIFIED

- Validation is reused, not forked: yang.typescript.antlr.profiles/admit
  (src/cljc/yang/typescript/antlr/profiles.cljc:124) calls semantics/validate-descriptor;
  yang.core.semantics is untouched by the diff. The ts-specific authority data (Hook ABI
  arities, per-hook role grants, entry-lowering) lives in the frontend contract exactly per
  the 6.2 ownership boundary.
- Address-bound role key: contract/frontend-contract builds :roles as
  [module-address, qualified-export, exact-role] triples (contract.cljc:214-236); hooks keyed
  by hook keyword, [:literal :int], [:projection alias kind]; authorize!
  (semantics.cljc:174) fails closed on any triple not granted. Same-arity wrong-role is
  exercised both ways:
  - ts: clj.ts/sub substituted for :op/add (same arity 2, same module address) gives
    :defect :export-role - test/yang/typescript/antlr/descriptor_profile_test.cljc:108-118;
    also at the lowering entry: lower.cljc re-verifies via profiles/verify-descriptor! to
    :export-role (test lines 128-129). This is exactly the 6.4 item-4 gate (substituting
    subtraction for addition within the same module must fail).
  - py swap fixture negative path: identity redirected to clj.py/ex-info without the extra
    grant gives :export-role with the exact role record in the evidence
    (test/yang/python/antlr/descriptor_profile_test.cljc:395-398).
- Hook ABI completeness verified mechanically: all 58 js/ symbols hardcoded in the base
  lowerer map 1:1 onto contract/legacy-hooks roles with matching arities (including
  js/make-object 0-or-1 call shapes, recorded via :call-shapes set 0 and 1 per the 6.4
  item-3 frozen-php pattern); zero hardcoded js applications remain in lower.cljc.
  js/string->bigint is carried as the legacy :int :overflow-op and arity-checked via the
  contract :literals {:int {:arity 1}}.

Focus 2 - Legacy ts-v1 preservation: VERIFIED (hunk-by-hunk read + orchestrator 736/736)

I read every lower.cljc hunk. Every change on the legacy path is a pure descriptor read:
- All literal emissions are byte-equivalent (:float :float64-raw -> cbor/float64; legacy
  :str/:str-units/:hole/:none/:unbound/:nil/:undefined tagged or raw values identical to the
  old constants). The bigint-literal rewrite preserves the native digit-count cutoff exactly:
  within-cutoff values can have at most 52 bits (radix 10 -> 15 digits ~50 bits, 16 -> 13 =
  2^52-1, 8 -> 17 = 2^51-1, 2 -> 52), so the <= 53 test always takes the raw path;
  over-cutoff runs force bits = 54 so the overflow operand js/string->bigint fires exactly as
  before, even when leading zeros make the magnitude small (lower.cljc:521-528). No changed
  emission order, no moved refusals, no changed diagnostics on the legacy path:
  require-capability! degrades to the identical refuse! with :T4/:T7/:T5 for legacy, and every
  capability legacy enables (bitwise, typeof, in, classes, enums, subscript/attribute/delete,
  scope/global, arguments/receiver, array-holes, object-accessors, spread) is :supported in
  legacy-capabilities.
- js/run-main [body] (1-operand) is FROZEN: entry-lowering pins :call-shapes {1} and
  :params [body] for ts-v1 and the emitted operand count is asserted by test
  (descriptor_profile_test.cljc:130-139). Bare compiler calls keep the native pin:
  lower-module-body and lower-unit are hardcoded to legacy-descriptor, and lower-packet /
  lower-units fall back to it when :descriptor is absent (lower.cljc:2481-2485, 2574-2581) -
  the oracle suites path is not captured by the default flip. program-medium :lower now
  forwards (select-keys state [:prelude :descriptor]), which is {} for existing stage callers.
- context adds one extra gen (:globals) before :this for both profiles; generated names are
  canonicalized (names/canonicalize) before any tree escapes, so the counter shift is
  unobservable - corroborated by the 736/736 byte-identical parity evidence.

Focus 3 - clojure-core-v1 ts profile: VERIFIED

- clj.ts/run-main [body-fn ambient-resolver] (contract entry-lowering; adapter at
  clj_runtime.cljc:207-214) - installs the cold-error policy handler (clj.ts/_policy), inits
  clj, clojure.core and clj.ts, restores the rt-ctx in a finally.
- Zero provenance doors: no dsg__ names, no provenance ops, no .rt/.b/.impl/safepoint
  references in the adapter (grep-clean); the internal clj.ts.rt/exports cell follows the
  clj.py.rt convention, is excluded from public-names by the core/exports namespace filter,
  and a descriptor pointing at it is refused (:reserved-door, tested at test line 114).
- Number -> boxed float64 (:float :float64-raw), BigInt -> exact integers via
  :raw-or-call / clj.ts/int-lit (radix-aware, limit-checked through yrt.int/check-limit).
  The expression 1n == 1 is false by contract (exact vs float64 boxes) and pinned by
  goldens/tests.
- Capability refusals fail closed BEFORE emission: rule-level constructs are refused in a
  whole-tree pre-scan in context (lower.cljc:2358-2366) before scope analysis; site-level
  gates (check-member-write!, lower-new, lower-typeof, bitwise ops, spreads, holes, this,
  defaults/varargs/destructuring, typed catch, try/throw, import/export) all run ahead of the
  corresponding emit. capabilities-fail-before-emission-test covers 18 distinct capability
  keys x both prelude modes, asserting status/reason/capability/semantics-id, non-empty
  construct, and node-id evidence into :yang.cst/nodes, plus the transform-level
  diagnostics-port path (no program emitted).
- Projection aliases are ts-idiomatic camelCase (assocIn, getIn, isEmpty, isNil, isMap,
  isVector, isSet, exInfo) and are legal per the grammar - exercised both as free identifiers
  through the ambient resolver and via import {inc as plus, assocIn} from clojure.core, with
  lexical shadowing (a local let inc wins) in the collections fixture.

Focus 4 - DEFAULT flip: VERIFIED at three layers

- Catalog: ts frontend install installs both revisions and sets an id-to-revision
  :yang.frontend/preferred map; yang.frontend/select now accepts map-or-vector preference
  (backward compatible - the python vector form still works and survives the ts install
  vector-to-map conversion). Default id-only select -> clojure-core-v1; explicit ts-v1 pin and
  an assoc of :yang.frontend/preferred both select legacy; the binding captured BEFORE the
  preferred flip still lowers and runs clojure (in-flight snapshot retained) -
  default-selection-and-in-flight-snapshot-test.
- REPL: frontends/create-state with lang :yang/typescript evaluates 1n == 1; to displayed
  false with derivation revision clojure-core-v1; lang [:yang/typescript ts-v1] displays
  true (clj-gated test). yin.repl.frontends installs ts on :clj only (the parser is
  :yang.grammar/targets {java}, src/clj/.../cst.clj), leaving cljs/cljd/jolt untouched.
- Oracle path: bare lower-packet / lower-module-body / lower-unit calls retain the frozen
  ts-v1 descriptor (Focus 2), so the selector change cannot capture the oracles.

Focus 5 - Composition-swap fixture (owner directive): VERIFIED

ambient-composition-swap-test (py descriptor-profile test) redirects the identity projection
to clj.py/ex-info in a modified descriptor, admits it against the contract extended with
exactly one new [address, clj.py/ex-info, [:projection identity :cell-accessor]] grant, and
proves:
- positive: the swapped descriptor validates, lowers, and EXECUTES end-to-end -
  identity(swapped, 17) returns {:yin.k/message swapped :yin.k/data 17} through the REPL
  catalog (:last-value) and through run-program on both prelude modes x all three engines;
- provenance names the swapped addresses: derivation :yang.frontend/lowering-profile is the
  swapped descriptor fresh segment key, the clj.py pinned address appears in
  :yang.frontend/runtime-closure, and the ambient-resolver address differs from the unswapped
  bundled lowering resolver;
- negative: admitting the same swapped descriptor WITHOUT the grant fails closed
  (:export-role, target clj.py/ex-info, role [:projection identity :cell-accessor]);
- ZERO frontend changes for the swap: the fixture builds its catalog purely from
  frontend/install and frontend/select with existing machinery; no python src file is touched
  (the generic frontend.cljc preference-map change is the ts default-flip mechanism, not
  needed by the swap).

Focus 6 - Fences + portability: VERIFIED

No yang/php changes; no src/cljc/yang/python changes (the py test file addition is the
mandated fixture venue). New src namespaces are pure cljc with zero host interop or
reflection (grep-clean); data/nth discipline holds in all new vector reads (_int-mod,
_float-mod, for-in-next, ex-info); float literals are boxed (cbor/float64) with no raw host
doubles in guest UAST; canonical ordering is deterministic (runtime-profile requires and
ambient-resolver projections sorted by jing/canonical-compare; descriptor maps keyed
deterministically like the py template). Host gating is correct: the ts test file gates the
parser-dependent and REPL tests to :clj, matching the engineer-captured Node 175 / Dart 4 /
Jolt lanes; test/cljd-out dart output for descriptor-profile-test confirms the cljd compile.

---

Findings

F1 - MEDIUM - import binding collision with a function declaration escapes the duplicate check

- File: src/cljc/yang/typescript/antlr/lower.cljc:2331-2336 (ambient-imports)
- The duplicate-binding guard tests imports, the module-scope :lexical map and :vars, but not
  :functions (function declarations register in :functions, scope.cljc:286, not :lexical -
  classes DO register as :lexical :class and are caught). Under the default profile,
  source importing inc from clojure.core followed by a function declaration of the same name
  parses, passes the guard, and then the injected import const and the hoisted function name
  both land in binding-names tdz-names, so bind-cells emits a duplicate-param lambda
  (u/lam [inc inc] ...) with both cells initialized to the projected clojure.core/inc
  callable (the import wins; the function body is unreachable). JS modules make this an early
  SyntaxError; here it is silently accepted with surprising shadowing instead of a refusal.
- Failure scenario: default-profile source importing inc, then function inc(x) returning
  x + 100n, then calling inc(1n) evaluates 2n (the projected inc) with no diagnostic, where
  the guest language contract should refuse the duplicate declaration.
- Suggested fix: add a check of :functions module-scope membership to the or in the guard.

F2 - LOW - capability whitelist omits :single-assignment

- File: src/cljc/yang/typescript/antlr/lower.cljc:92 (require-capability!)
- The gate admits only :supported and :cell. yang.core.semantics (schema at
  semantics.cljc:76-77 and the required-hook check at semantics.cljc:240) treats
  :single-assignment as an enabled value, and D1 keeps it as a future restricted profile;
  the py lowerer refuses only on :refused. A ts descriptor with :mutation/locals
  :single-assignment would validate but then be refused at every let/assignment in this
  lowerer. Latent only (both builtin profiles use :cell, and verify-descriptor! currently
  rejects unknown ids), but the predicate should match the schema enabled set.

F3 - NOTE - nullish coalescing and optional chaining collapse null and undefined (both nil)

- contract.cljc clojure literals map both :nil and :undefined to raw nil, so a ?? b, a?.b and
  === cannot distinguish them (=== is clj.ts/equiv, the representation-sensitive data/equiv).
  Internally consistent and golden-pinned ([0.0] === [-0.0] true, [1n] === [1] false,
  1n == 1 false); recorded as declared contract per 6.1 truth-in-advertising.

F4 - NOTE - numeric adapters deliberately depart from JS where Clojure semantics differ

- clj.ts mod is floor-mod (-5n % 3n -> 1, Clojure mod, vs JS -2n); zero-base negative powers
  and integer division by zero raise zero-division policy errors instead of Infinity or a JS
  throw; non-integral float exponents raise :math/unsupported-exponent; 4.0 ** -1n uses
  fpow-half. All fail closed through the installed policy handler, goldens pin them
  (persistent-profile-goldens-test), and out-of-range float exponents are contained because
  float/floor-int answers ::out-of-range (a non-integer), routing to the same guest error.
  The ts _policy table is slightly coarser than the py one (unsupported-exponent covers the
  py overflow / pow-infinite / pow-fractional split; ts maps :math/underflow to :underflow
  where py maps it to :overflow - ts is the cleaner of the two).

F5 - NOTE - :control/for-in :supported narrows to plain maps at runtime

- clj.ts/for-in-start (clj_runtime.cljc:140-144) iterates only non-cell data maps; for-in
  over an array raises a guest :iteration-type error at run time rather than being refused at
  lowering. The lowering surface genuinely exists (hooks for-in-start / for-in-next), so the
  capability is not dishonest, but the narrowing is observable only at runtime. Same shape as
  the get-item runtime type errors; acceptable, recorded for the ledger.

F6 - NOTE - minor divergences from the P3 template worth recording

- ts profiles/admit hardcodes the admission limits (py takes a limits argument).
- The ts clojure catalog binding installs no :render (py installs display-clojure-value);
  the default display is used and the evidence test passes.
- capabilities-fail-before-emission-test does not cover the :modules/export refusal path
  (export statements under the default profile go through the context pre-scan and the
  lower-stmt capability branch, untested in the new suite).
- Cosmetic: misaligned indentation in the check-params do blocks (lower.cljc:696-711 region).

---

Verdict rationale

The core deliverables are correct and faithful to the r8 design: validation is reused rather
than forked; the authority model (address-bound exact-role grants, both entry shapes frozen,
UAST-derived arity/role/closure/reserved-door checks) is implemented and adversarially
tested; legacy ts-v1 is preserved byte-for-byte by construction and by the parity evidence;
the default flip is layered so the oracle path cannot be captured; the swap fixture proves
the descriptor-is-data story end-to-end with provenance naming the swapped addresses. All
findings above are edge-case hygiene (F1 is the only user-reachable one, on source that real
JS rejects anyway) and none blocks the slice acceptance gates.

VERDICT: APPROVED-WITH-FIXES


---

r2 confirmation (2026-11-23, glm-5.3) - fixes for F1 and F2 re-reviewed on the updated
uncommitted diff; delta confirmed to be exactly the two hunks plus one regression fixture
(lower.cljc 926 -> 927 changed lines, ts test 249 -> 266; orchestrator gates 10 tests /
257 assertions / 0 failures vs 9/255 before, native parity 736/736 unchanged, deps clean).

F1 - CONFIRMED-CLOSED (lower.cljc ambient-imports guard + test fixture at
descriptor_profile_test.cljc:184)

- The duplicate-binding guard now consults all four collision sources: prior imports
  (pre-existing), module-scope :lexical including classes (pre-existing), :vars
  (pre-existing), and :functions (new). The injected import const can no longer produce a
  duplicated tdz name, so the duplicate-param lambda and import-wins shadowing from r1 are
  unreachable.
- Rejection goes through scope/early-error!, answering the established early-error
  vocabulary of this lowerer (:yang.ts/diagnostic :yang.ts/early-error with :rule, :span,
  :name evidence) - the correct channel for a redeclaration, rather than a capability
  refusal.
- Order-independence verified: scope/analyze collects whole-module function declarations
  before ambient-imports reads the module scope, so the collision is caught whether the
  function declaration precedes or follows the import; nested (block-scoped) functions
  correctly do not collide.
- The regression fixture drives the exact r1 failure scenario (import inc from
  clojure.core, then function inc returning x + 100n, then a call) through the real parser
  and the DEFAULT profile selection, asserting :yang.cst/ok parse and the early-error
  refusal with :rule importAliasName and :name inc - fail-closed during lowering, before
  emission.
- Legacy path untouched: ambient-imports is legacy-skipped and legacy imports still refuse
  as :T7 module syntax - consistent with parity 736/736 unchanged.

F2 - CONFIRMED-CLOSED (lower.cljc:91)

- require-capability! now admits :single-assignment, exactly matching the enabled set the
  validator itself uses (semantics.cljc:240; schema at semantics.cljc:76-77). :refused and
  :ambient-only still refuse at the gates, which is required (ambient-only imports must not
  blanket-pass; the ambient path is handled separately in ambient-imports).
- No builtin profile uses :single-assignment, so builtin behavior is byte-unchanged -
  consistent with the unchanged 736/736 parity and the 10/257/0 gates.

VERDICT r2: CONFIRMED-CLOSED
