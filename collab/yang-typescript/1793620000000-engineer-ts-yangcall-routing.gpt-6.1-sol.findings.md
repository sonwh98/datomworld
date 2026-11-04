Created-GMT: 2026-11-01 20:27:53 GMT
Coding-Agent: codex (gpt-6.1-sol)
Role: engineer
Worktree: /Users/sto/workspace/datomworld-ts-t3
Branch/base: yang-ts-t3 @ bce0e454

# TS S11 — source call routing findings

Status: TS-scoped implementation and source fixtures committed as cccebf3e (feat(yang.typescript): exercise real-source yang.call routing); acceptance BLOCKED by PHP callback resolution. No push. PHP files and shared cross_lang_test.clj remain untouched. A scope question requesting authorization for the PHP dependency is pending.

## Baseline finding

The requested TS eligibility/invocation routing and unconditional linked requires were already present at bce0e454: js/callable? delegates to yang.call/eligible?; js/call delegates to yang.call/invoke-with; lower-packet and lower-units both require yang.call before js. The peer note's line coordinates predate the T3 class lowerer. Python reference efc99cc4 was read with git show (this TS worktree predates Python S3).

## Prepared changes

- TS prelude: transfer :yang.call/thrown through js/throw with the original payload. Before the change, a new identity regression failed on all four VMs because the router replaced the payload with TypeError. JS native entries retain this/class/new.target behavior; constructors retain their existing private path.
- TS lowerer: consolidate both source entry wrappers into with-runtime, preserving the unconditional yang.call-before-js sequence. A portable test covers single-unit, multi-unit, and empty multi-unit programs.
- Real-source tests: separately lower PHP source; publish an ordinary module exporting its unchanged body and the PHP runtime; run the donor; resolve functions with the published php/resolve-fn export; project those same cells with js/define-global!. TS source body is lowered unchanged. Bundled runs use the same namespace projection. No AST walks, call-node rewriting, foreign :code extraction, replacement function cells, or language-pair call wrappers.
- Direct source proof: PHP function defaults, positional arguments, returned/stored function identity, and a member call with a JS receiver. All four bundled VMs and three linked VMs pass.
- Higher-order source candidate: TS closure with captured state passes into PHP remember(), is stored globally, returned, and recovered; PHP callback() then invokes the stored value. Identity and callback counts are asserted in TS source. This test is deliberately not weakened or skipped: it exposes the prerequisite below.
- Portable CST fixtures include the original source strings. JVM parser checks prove each recorded CST form equals the parser output. Node/Dart can lower those same source-derived forms without a parser.
- Re-pin the TS prelude and float-program addresses for the changed call router.

## Acceptance blocker

src/cljc/yang/php/antlr/prelude.cljc:2495, php/callable-of, calls php/kind before common entry discovery. A stored TS function is its original task cell, not a PHP Closure wrapper; php/kind attempts data/float-value on that cell and produces an operational :wrong-type refusal. The full new source suite reports 2 tests / 22 assertions / 0 failures / 7 errors: all seven errors belong to source-foreign-store-and-callback-test (four bundled VMs, three linked VMs). The direct-call test has no failure/error.

PHP routing/admission must recognize common entries before its local kind conversion and preserve its local string/Closure resolution. PHP's published runtime currently has no yang.call dependency, so a proper helper-based fix may also need the PHP module's shared-runtime dependency wiring. That work is outside the named TS implementation files; no test-local workaround has been inserted. Full §8.10 conformance is not claimed.

## Verification

Pending final targeted JVM/Node results; see /tmp/ts-s11-verify-summary.txt, /tmp/ts-s11-source-current.log, /tmp/ts-s11-node.log and /tmp/ts-s11-kondo.log. Final outcomes will be appended here before handoff.

## Preserved state

All pre-existing untracked collab artifacts and test/yang/typescript/antlr/class_units_test.cljc were left untouched. No VM opcode, grammar, or ABI changes. No push. This flat collab artifact is intentionally uncommitted in MAIN, per the task and worktree hook.

## Final verification and handoff

Commit: `cccebf3e81d6cb7c64de4d7c083c57911d385502` — `feat(yang.typescript): exercise real-source yang.call routing`. No push or merge. Tracked worktree is clean; only the original unrelated untracked files remain. The commit deliberately retains the red higher-order acceptance fixture and explains its PHP dependency in the message. This is NOT an S11-ready or full-conformance sign-off.

Targeted JVM namespaces ran serially, one JVM per namespace, using `clojure -J-Xss4m -M:test -n <namespace>` (including slow bodies):

| Namespace under yang.typescript.antlr | Tests | Assertions | Failures / errors |
| --- | ---: | ---: | --- |
| source-call-parser-test | 1 | 4 | 0 / 0 |
| lower-portable-test | 10 | 46 | 0 / 0 |
| prelude-module-test | 3 | 8 | 0 / 0 |
| prelude-parity-test | 9 | 1372 | 0 / 0 |
| class-prelude-test | 11 | 308 | 0 / 0 |
| linked-prelude-test | 10 | 62 | 0 / 0 |
| float-address-test | 5 | 20 | 0 / 0 |
| lower-test | 18 | 731 | 0 / 0 |
| Total above | 67 | 2551 | 0 / 0 |
| source-call-test (new acceptance fixture) | 2 | 22 | 0 / 7 |

Node, serialized after the JVM runs, with `DATOM_SLOW_TESTS=1`, `clojure -J-Xss4m -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge <ns-regexp>`:

- Selected existing/updated portable suites (lower-portable, prelude-module, prelude-parity, class-prelude, float-address, linked-prelude): **48 tests / 1816 assertions / 0 failures / 0 errors**, compile warnings 0. CLI exit 0, 738.83 seconds.
- Separate source-call namespace build (which also runs its parity dependency): **11 tests / 1394 assertions / 0 failures / 7 errors**, compile warnings 0, 636.84 seconds. All seven errors are the same PHP callback resolver failure. The parity dependency accounts for 9 tests / 1372 assertions. IMPORTANT: shadow's CLI returned exit 0 despite the seven reported test errors; this is a RED source acceptance result, not a passing gate.
- No Node failure/error belongs to source-calls-php-function-test. Its four bundled and three linked runs all pass, as on the JVM.
- `clojure -M:kondo --lint` all eight touched files: **0 errors / 0 warnings**. `cljstyle check` on all eight: exit 0. Staged diff whitespace check passed; the commit hook also formatted all eight files. No tracked changes remain after the commit.

Actual source-proof host/VM matrix (A = AST walker, S = semantic, K = stack, R = register):

| Host / source leg | Bundled A / S / K / R | Linked S / K / R | Linked A |
| --- | --- | --- | --- |
| JVM TS -> PHP direct | pass / pass / pass / pass | pass / pass / pass | existing profile refusal, not run |
| Node TS -> PHP direct | pass / pass / pass / pass | pass / pass / pass | existing profile refusal, not run |
| JVM PHP invokes received TS callback | PHP resolver error on all four | PHP resolver error on all three | not run |
| Node PHP invokes received TS callback | PHP resolver error on all four | PHP resolver error on all three | not run |
| Dart source fixtures | not run | not run | not run |

The direct proof admits ordinary PHP function cells and round-trips function identity with float64 positional/default values. A JS member call supplies a receiver that the PHP entry ignores under its own semantics. The higher-order candidate passes a captured TS arrow into PHP storage and attempts a callback without replacement cells; it remains blocked before shared invocation. No reference-binding or named-argument extension is claimed by these fixtures. Python directions and the shared cross_lang_test.clj rewrite cleanup remain outside this change. The new thrown-value test is a synthetic convention-mechanics regression, not real-source foreign-exception conformance evidence.

All unaffected requested TS gates are green on the tested JVM/Node lanes. The remaining acceptance failure requires PHP resolver/admission work (and potentially shared-runtime module wiring), for which the scope decision remains pending. PHP and the shared cross-language test file were not edited. Findings are intentionally uncommitted in MAIN's flat collab directory.
