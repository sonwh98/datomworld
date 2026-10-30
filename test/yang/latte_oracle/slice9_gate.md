# Slice 9 kernel differential gate: execution evidence correction

The gate on `yang-hardening2` is **blocked**. The architect denied the prior
165/165 closure claim because membership in a static dependency closure did
not prove execution of every arity. That claim is withdrawn. The gate retains
its final assertion `(= :pass (:gate-status evidence))`; missing execution
evidence must fail that assertion.

The 106-fixture corpus still has 102 passing agreements, two recorded
deviations, and two recorded upstream failures. The four narrowly recorded
exception dispositions remain declared departures, not agreements. No
upstream or `src/cljc/` source changes are made. Admission is unchanged:
155 bindings (146 admitted, 9 excluded) and 175 candidate function/arities
(165 admitted, 10 excluded).

Recorded executions now establish **145/165** admitted arities: 39 directly
and 106 through named integrations. The other 20, including `parse/1`, remain
uncredited. All 20 unsupported credits from the prior 165/165 claim are
withdrawn. Both C lanes record eight `parse/2` calls and zero `parse/1` calls.
The corrected counts and remaining arities are recorded in
[`slice9_evidence.edn`](slice9_evidence.edn) and
[`slice9_unexercised.edn`](slice9_unexercised.edn). The 20 earlier residual
consumers in [`fixture_closure.edn`](fixture_closure.edn) retain their concrete
expected outcomes; they do not establish closure of the full inventory.

## Consumer evidence

- `mkenv` preserves a nonempty global environment and creates empty locals.
- `beta-red`, `delta-normalize`, and `delta-normalize-local` have explicit
  entry fixtures and missing-reference rejections. The local wrapper also
  exercises explicit local definitions and their precedence over an ambient
  namespace definition.
- `install-unfold-implicit!` installs into both isolated upstream atoms on A.
  B/C return an updated immutable context, used by both the delta wrapper and
  NBE. Both normalizers read the same installed extension. An installed
  refusing extension propagates its original error data.
- `install-opacity-function` starts from a refusing policy, explicitly installs
  the working policy, then enters the original proof dispatcher through
  opaque/transparent steps and qed. A duplicate opaque step rejects. A uses
  an isolated namespace and host-local Var policy; B/C thread immutable
  definition updates through the explicit context.
- `install-fundamental-unparsers!` starts from an empty registry, installs the
  fundamental product unparser, then unparses nested implications. Dependent
  products and atoms remain unchanged. Direct `prod-impl-unparser` fixtures
  also verify its `[term false]` refusal result.
- `exists-kw?`, `term?`, `pair?`, and `vectorn?` include false results.
  `bound-vars` distinguishes a bound occurrence from entirely free variables.
  These fixtures preserve upstream behavior, including its shallow term
  validator and the use of `vector` rather than `vector?` in utility predicates.
- `proper-type?` enters each arity separately. Unknown variables throw;
  a well-typed term whose inferred type is not a sort returns false.
- `show-deftype` checks definition, axiom, theorem, implicit, and unknown
  labels. It depends only on portable record-kind predicates and returns a
  string. Host pprint and stdout functions remain excluded.
- `safe-get` covers successful lookup, missing keys, stored false, and stored
  nil. The latter three all reject upstream. `vremove` removes every match,
  preserves a no-match vector, and propagates a predicate's domain exception.

`mkenv`, `appify`, and `bound-vars` have no domain rejection in the pinned
source; the fixtures preserve their constructor, empty-input, and empty-set
results instead of manufacturing refusals. A refused product rewrite is
identified by its explicit modification flag, not mistaken for an exception.

The pinned local delta wrapper has a narrower portability scope than its
docstring suggests: `delta-step` drops `local?` when calling `delta-reduction`
on a reference. Consequently upstream can resolve a global or host namespace
definition on that path. The global-result fixture preserves this behavior;
the namespace fixture supplies a local definition that shadows the ambient
one. The guest boundary still requires explicit definitions and has no host
namespace lookup capability. This round does not repair upstream or claim
agreement for ambient-only resolution.

## Acceptance counting

Every selected guest function body appends `[guest-function arity]` to an
explicit diagnostic stream at entry. Instrumentation covers all arities of
the functions supplied to each fixture, including higher-order calls through
registries and extension closures. Each C lane creates its own stream.
After the VM halts, the harness reads that stream through its own
cursor, preserving the fixture's original outcome and exception payload.
A/B remain uninstrumented. Raw ordered call records are retained under
`:c-walker :executed-calls` and `:c-semantic :executed-calls` for every completed
fixture. The original uninstrumented fixtures used 1,000,000 instructions per
lane. The instrumented fixtures have a 2,000,000 instruction bound because
body-entry stream writes add guest instructions. Reading the completed
diagnostic stream is harness work after halt. This is an instrumentation
budget change, not a new performance acceptance claim.

Passing direct fixtures contribute only their original function/argument
count, and only with matching body-entry records in both C lanes. Passing
named integrations contribute only exact arities recorded in both C lanes.
Static dependency membership contributes no credit. Full branch coverage is
not required. Context adapter identities and explicit extra context arguments
are recorded in the evidence; each observed guest arity is checked against
that mapping individually, never against all arities of its function.

Only `:pass` fixture rows contribute exercise evidence. Recorded upstream
failures and deviations do not contribute. Any admitted arity without a
passing direct or named integration execution path blocks the gate.

The accounting regression first failed against the old implementation: it
credited the deliberately uncalled arity and accepted missing C-lane traces.
It now requires both traces and leaves unused arities uncredited. A second
regression replaces the adapted `parse/1` body in memory with an unconditional
error, then runs `:parse-to-normalize-to-type`. A/B and both C lanes still
pass; both C traces record `parse/2` and no `parse/1`, and the counter leaves
`parse/1` uncredited. Every credited path is also checked against the recorded
executions of its named fixture. See
[`slice9_arity_accounting_test.txt`](slice9_arity_accounting_test.txt).

## Recorded portability boundaries

The two `:recorded-deviation` rows remain `:elab-after-qed` and
`:check-after-qed`. Upstream embeds `clojure.core/meta`, a host function, in
exception data; C carries a guest closure. The original outcomes, comparison
flags, exact payload guards, and evidence pointers remain intact. Both rows
are listed in `:recorded-portability-boundaries` in the summary. Neither is
reported as agreement.

## Recorded upstream failures

`:typing-ascription` still reaches the upstream declaration
`type-of-ascribe`, which has no definition. A raises its original unbound-fn
exception; B/C retain the explicit branch-local refusal.

`:compile-malformed` still passes scalar proof input `[42]`. A/B raise the
original native sequence exception; C retains its portable sequence refusal.

These two rows have the distinct disposition `:recorded-upstream-failure` and
are listed in `:recorded-upstream-failures`. Their original A/B/C records and
false agreement flags remain available. The disposition checks the exact
fixture ID, input call, exception class/message, B outcome, and both completed
C outcomes. Changed calls, IDs, or C payloads are tested to refuse the
disposition. Any other execution failure remains a blocker. No upstream fix,
accepted-negative claim, or equality projection is introduced for these rows.

## Verification

The accounting tests pass 2 tests and 22 assertions. An independent audit
re-derives all 145 retained arities and all 360 credited fixture paths from
the two raw C traces. The four regression suites pass 122 tests and 1,338
assertions. Kondo reports zero errors and warnings; cljstyle and
`git diff --check` pass. The full oracle runs 26 tests and 1,879 assertions
with exactly one failure and no errors: its retained
`(= :pass (:gate-status evidence))` closure assertion. The missing 20 arities
therefore continue to block acceptance. Verification results are recorded in
[`slice9_verification.edn`](slice9_verification.edn), with command outputs
beside it.
`yang.latte-oracle-test/write-kernel-gate-evidence!` explicitly regenerates
both execution evidence and the remaining-arity inventory; normal tests do
not write them. Evidence retains the pinned upstream revision, compiler
digest, runtime, instruction budget, per-fixture outcomes, diagnostic traces,
dependency graph, admission inventory, credited paths, and all four recorded
exception dispositions.

The append-only correction in [`known-gaps.md`](../../../docs/agents/known-gaps.md)
records that host Clojure turns a thrown string into `ClassCastException`,
caught by `catch Exception` but not `catch ExceptionInfo`. Portable `try` binds
the error payload without equivalent runtime class handling; this guest/host
gap remains declared.

Execution scope is both C evaluators on the JVM. This correction does not
claim renewed architect sign-off or Node/Dart verification, and the blocked
gate does not authorize program closure or merge.
