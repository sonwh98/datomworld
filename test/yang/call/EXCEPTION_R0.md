# Exception track Round 0

Branch: `exception-r0`. Base: `7ab37566`; authority: owner ruling v2,
`collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md`.
Session: `01a1b40e-37dc-7be1-8dd7-d0beac15afc5` (provider's `CODEX_THREAD_ID`).

Only `test/yang/call/**` and `test/yang/python/**` changed. These tests assert
the compliant contract and intentionally remain red where runtimes diverge.
No runtime, lowerer, parser, VM, or prelude repair belongs to this commit.

## Contract and composition

`exception-contract/fixtures` contains 47 provenance fixtures, each with an
`:id`, `:scenario`, `:gates`, `:frontends`, and compliant `:expected` value.
`pair-matrix` derives all twelve directed pairs from the admitted frontends:
90 authored scenarios, 46 Python-containing scenarios runnable in R0 and
44 scenarios explicitly pending the other frontend rounds. JavaScript has
no required-argument binding failure in this source surface; Clojure's
`finally` refusal is preserved rather than replaced with a passing example.
`pending-obligations` records the wider acceptance gate separately.
`ledger-seed` derives the obligations; `open-gates` consumes ID-to-status data
and leaves unreported obligations open.

Fresh named/closure throws, binding failures, all three qualified protocol
reasons, saved protocol rethrows, local/foreign reception, a stored foreign
wrapper rethrown by another activation, condition-shaped returned/thrown
maps, and source-writable forged retention attributes have boundary probes.
The malformed invocation constructors are boundary fixtures, **not** claimed
as source-pair evidence. Protocol conditions are produced by `yang.call`
validation and a real Python function's unsupported-profile rejection, then
delivered through an authentic test descriptor to parsed receiver source.

`exception-probes/run-fixture` runs an isolated fixture. `run-frontend` batches
the frontend's observations after one source unit, with separate public
activations. `exception-legs/run-leg` runs one source pair/scenario;
`run-pair` batches distinct named source probes into one parsed caller unit.
Batching only changes source assembly before parsing. It does not rewrite
call nodes or extract foreign code. Outer diagnostic runtime handlers mark
a bypassed boundary exit as `:unbridged`; they never turn it into a passing
transfer. A diagnostic guard uses its runtime's own restore path when it
regains control. Full handler/depth/frame snapshot comparisons remain for
the frontend rounds; a green return alone does not prove those invariants.

Identity assertions execute in the guest task, before rendering: full
condition equality (including qualified reason and every evidence field),
authenticated payload-reference equality, non-stacking, exactly one transfer,
and an unreachable after-call sentinel. Lifetime probes return from `retain`
before entering `later`. Source-pair unchanged rethrow additionally proves
that the catch ran once. A-to-B-to-A checks original identity and that the
receiving source's cleanup completed exactly once before the home catch;
Clojure uses its admitted catch cleanup, not `finally`.

## Kit API added to `yang.call.conformance`

- `(export-form frontend name)` reads a source-produced public export.
- `(compose-exception donor exports caller source [observation-form])`
  preserves the success composition and optionally appends a boundary probe.
- `(boundary-call-form callee positional named [options])` emits an abortive
  observer reporting `:route`, `:transfers`, `:after`, and either `:condition`
  or `:value`. Returned condition-shaped data stays a return.
- `(observation-map-form entries)` evaluates known boundary expression values
  with explicit `assoc` forms; UAST map literals themselves remain data.
- `(observation-vector-form expressions)` evaluates boundary arguments with
  explicit `conj` forms; UAST vector literals themselves remain data.
- `(guard-exceptions-form frontends form)` reports an escape bypassing the
  supplied exit as `{:route :unbridged :frontend ...}`.
- `(exception-outcome frontend root-value)` exposes common `:frontend`,
  `:out`, `:exception`, `:value`, and legacy `:rendered` fields. Exception
  snapshots remain data. This rendering is not identity evidence.
- `(exception-call donor exports caller)` returns those outcomes per VM.
- `(run-exception-program donor ast)` runs independent JVM VM jobs with
  separate registries, tasks, link pairs, and content-serving compositions;
  only the immutable published content is read in common. This avoids
  serial repetition of costly linked-image validation. Original host Exceptions
  remain host objects for test `:error` reporting, separate from guest results;
  all jobs settle. A failed batch falls back to isolated fixtures/legs so an
  operational defect cannot hide the remaining inventory.

The original `call`, `compose`, and successful-call renderers are unchanged.
All new namespaces are JVM `.clj`; no conditional top-level requires.

## Runtime causes to carry into the frontend rounds

Python R2: `src/cljc/yang/python/antlr/prelude.cljc:1045` raises an
otherwise-foreign payload raw. `:802` matches typed clauses through the
local hierarchy, so those raw values do not become local exceptions.
`:754` rejects explicit re-raising of a foreign non-exception. The generated
`ForeignException` and identity-owned lifetime association are absent.

Python R3: `prelude.cljc:1046` replaces every protocol condition with a
generic `TypeError`, dropping the qualified reason and complete evidence.
The public entry at `:912` always creates a fresh thrown condition. R2/R3
reception repair therefore also needs condition-preserving egress.

JS R1: `src/cljc/yang/typescript/antlr/prelude.cljc:1581` applies source code
without a per-activation raise bridge. R3: `:1634` creates text-only local
TypeErrors. PHP R1: `src/cljc/yang/php/antlr/prelude.cljc:2326` and `:4298`
do not bridge escaping function/Closure exceptions; `:194` returns on an
empty handler stack. PHP R2/R3: `:2452`, `:2460`, and `:4392` raise envelopes
(or replace not-callable with an Error); source rethrow at `:4158` requires
a local Throwable. These current-tree PHP lines differ from the older audit.
PHP's `:143` also attempts numeric classification of an otherwise-foreign
envelope while `:4147` class-checks it, producing a host `float-value`
wrong-type refusal. This is reported as an operational error, never turned
into a guest thrown condition by the harness.

Clojure R3: `src/cljc/yang/clojure.cljc:1306` retains protocol catch data,
but public escape at `:1088` unconditionally constructs a fresh thrown
condition. R4 remains the separate private/public equivalence decision.

## Coverage limits

Execution is JVM linked semantic/stack/register only. The shared linked
kit refuses the AST walker until L-b; that pre-existing refusal is not
re-executed here. No Node, Dart, slow-tagged tests, remote hosts, bundled
frontend walker execution, or unsupported linked execution was run. A small
JVM walker helper test checks evaluated observation maps and abortive capture
over bundled `yang.call`; it is not frontend conformance evidence.
Methods/callable objects, full suspension/recursion, bundled/linked parity,
task isolation, custody refusal regressions, and hostile foreign formatting/
class-name-forgery probes remain explicit obligations.
No frontend receives full conformance from a green subset of these probes.

## Results

The checked-in result ledger and final inventory below record the final JVM
run, including the intentional reds. Parser/lowering errors in fixture source
are harness defects, not acceptable runtime reds.

Green verification:

| Command (`clj -M:test`) | Tests | Assertions | Failures | Errors |
| --- | ---: | ---: | ---: | ---: |
| `-n yang.python.antlr.source-call-test` | 4 | 4 | 0 | 0 |
| `-n yang.call.conformance-test -n yang.call.exception-source-test` | 6 | 131 | 0 | 0 |

The latter is 3 kit tests / 18 assertions plus 3 source tests / 113
assertions. Source checks include every single-scenario source and all
twelve assembled pair units. The existing TS/PHP source-call tests are
slow-tagged and were not run under the task's no-slow-tests rule.


### Exact runtime verification counts

The complete run (`-n yang.call.exception-contract-test -n
yang.python.antlr.exception-call-test`) produced **10 tests / 279 assertions,
138 failures / 60 errors**. Its seven JavaScript-to-Python rows were setup
errors: the kit projected JavaScript's absent `needs_argument` export. The
landed `exception-legs/donor-exports` follows the actual donor capability.
Those seven obsolete rows are replaced by the corrected targeted run:

```
clj -M:test -n yang.python.antlr.exception-call-test \
  -v yang.python.antlr.exception-call-test/typescript-to-python-test
```

That run produced **1 test / 21 assertions, 21 failures / 0 errors**. No
other executed direction's source or projections changed. Final kit/source
verification was rerun after this correction and remained **6 / 131, green**.

The current, deduplicated runtime ledger therefore has **93 rows × 3 VMs =
279 assertions: 81 pass, 159 fail, 39 operational errors**. Its 47 provenance
rows are 11 green / 29 red / 7 error; its 46 Python-containing source-pair
rows are 16 green / 24 red / 6 error. All three VMs agree on every row's
status. With the 135 green regression/helper assertions, the final unique
coverage is **20 test vars / 414 assertions: 216 pass, 159 fail, 39 error**.
This aggregate combines the full run with the corrected direction; it is
not a claim that a single post-correction full-suite command was run.

`exception_r0_results.edn` records the expected values, actual observations,
gates, frontend sets, backend statuses, verification counts, and the
ID-to-status map. The rejected setup-error rows are absent from its current
inventory. Feed `:status-by-id` to `exception-contract/open-gates`; unreported
and pending obligations remain open.

### Fixture inventory

Each state below holds on semantic, stack, and register. **Error** means a
failing test with the original host refusal, not a guest thrown condition.
R5 throw/forgery reds in JS/PHP are blocked by R1's missing bridge.

| Fixture ID | Gates | Frontend | Current status |
| --- | --- | --- | --- |
| `:clojure/binding` | R1 | clojure | green |
| `:clojure/fresh-closure` | R1/R5 | clojure | green |
| `:clojure/fresh-named` | R1/R5 | clojure | green |
| `:clojure/protocol-invalid-invocation` | R3 | clojure | red |
| `:clojure/protocol-not-callable` | R3 | clojure | red |
| `:clojure/protocol-unsupported-binding` | R3 | clojure | red |
| `:clojure/saved-protocol-invalid-invocation` | R3/R5 | clojure | red |
| `:clojure/saved-protocol-not-callable` | R3/R5 | clojure | red |
| `:clojure/saved-protocol-unsupported-binding` | R3/R5 | clojure | red |
| `:clojure/shape-return` | R5 | clojure | green |
| `:clojure/shape-throw` | R5 | clojure | green |
| `:php/binding` | R1 | php | red |
| `:php/foreign-reception` | R2 | php | error |
| `:php/forged-retention` | R5 | php | red |
| `:php/fresh-closure` | R1/R5 | php | red |
| `:php/fresh-named` | R1/R5 | php | red |
| `:php/local-reception` | R2 | php | error |
| `:php/protocol-invalid-invocation` | R3 | php | error |
| `:php/protocol-not-callable` | R3 | php | red |
| `:php/protocol-unsupported-binding` | R3 | php | error |
| `:php/retained-foreign-lifetime` | R5/R2 | php | error |
| `:php/saved-protocol-invalid-invocation` | R3/R5 | php | error |
| `:php/saved-protocol-not-callable` | R3/R5 | php | red |
| `:php/saved-protocol-unsupported-binding` | R3/R5 | php | error |
| `:python/binding` | R1 | python | green |
| `:python/foreign-reception` | R2 | python | red |
| `:python/forged-retention` | R5 | python | green |
| `:python/fresh-closure` | R1/R5 | python | green |
| `:python/fresh-named` | R1/R5 | python | green |
| `:python/local-reception` | R2 | python | green |
| `:python/protocol-invalid-invocation` | R3 | python | red |
| `:python/protocol-not-callable` | R3 | python | red |
| `:python/protocol-unsupported-binding` | R3 | python | red |
| `:python/retained-foreign-lifetime` | R5/R2 | python | red |
| `:python/saved-protocol-invalid-invocation` | R3/R5 | python | red |
| `:python/saved-protocol-not-callable` | R3/R5 | python | red |
| `:python/saved-protocol-unsupported-binding` | R3/R5 | python | red |
| `:typescript/fresh-closure` | R1/R5 | typescript | red |
| `:typescript/fresh-named` | R1/R5 | typescript | red |
| `:typescript/protocol-invalid-invocation` | R3 | typescript | red |
| `:typescript/protocol-not-callable` | R3 | typescript | red |
| `:typescript/protocol-unsupported-binding` | R3 | typescript | red |
| `:typescript/saved-protocol-invalid-invocation` | R3/R5 | typescript | red |
| `:typescript/saved-protocol-not-callable` | R3/R5 | typescript | red |
| `:typescript/saved-protocol-unsupported-binding` | R3/R5 | typescript | red |
| `:typescript/shape-return` | R5 | typescript | green |
| `:typescript/shape-throw` | R5 | typescript | red |

### Python source-pair report

G = green; R = condition/transfer mismatch; E = original host operational
error; — = not applicable to the admitted source capability. Every cell
represents three VM assertions. JS's typed-catch row exercises its admitted
untyped catch; Clojure uses its single `Exception` catch and no `finally`.

| Scenario | Python→JS | JS→Python | Python→PHP | PHP→Python | Python→Clojure | Clojure→Python |
| --- | --- | --- | --- | --- | --- | --- |
| `binding` | G | — | E | R | G | G |
| `catch-all` | G | R | E | R | G | G |
| `finally-replacement` | R | R | R | R | — | G |
| `retained-lifetime` | R | R | E | R | G | R |
| `round-trip` | R | R | R | R | G | G |
| `success-raise-success` | G | R | E | R | G | G |
| `typed-catch` | G | R | E | R | G | R |
| `unchanged-rethrow` | R | R | E | R | G | R |

Clojure→Python's typed reception, stored-wrapper lifetime, and unchanged
rethrow are R2 reds: the otherwise-foreign cell is raised raw at Python
`prelude.cljc:1045`; the catch-entry counter prevents an uncaught raw escape
from falsely passing rethrow. Python's isolated protocol fixtures separately
pin the R3 TypeError loss at `:1046` and always-fresh egress at `:912`.

Python→JS's four reds and all JS→Python/ PHP→Python reds are missing-bridge
R1 failures (including cleanup bypass on A→B→A). Python→PHP's six E cases
are envelope class-checking failures at PHP `prelude.cljc:143` / `:4147`;
finally replacement and round trip instead expose the missing bridge.
Python→Clojure's seven greens close only these scenarios: Clojure R3 and R4
remain open. The remaining six directed pairs' 44 scenarios are authored,
parse/lower successfully, and stay pending their rounds.

### Acceptance-ledger seed

| Frontend | Runtime repairs still open | Full acceptance still open |
| --- | --- | --- |
| JavaScript (`:typescript`) | R1, R3 | R1, R2, R3, R5 |
| PHP | R1, R2, R3 | R1, R2, R3, R5 |
| Python | R2, R3, including egress | R1, R2, R3, R5 |
| Clojure | R3, then R4 | R1, R2, R3, R4, R5 |

Full acceptance includes the pending directions, capabilities, complete
context snapshots, formatting/forgery probes, and host/publication/custody
obligations above. No frontend is declared conformant by this Round 0 seed.
