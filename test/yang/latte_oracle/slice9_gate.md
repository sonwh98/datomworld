# Slice 9 kernel differential gate: final fixture closure

The final gate on `yang-hardening2` closes all 20 residual arities through the
primary consumers in [`fixture_closure.edn`](fixture_closure.edn). Each primary
consumer must pass independently on upstream A, adapted Clojure B, C-walker,
and C-semantic, have a concrete expected result, and contribute a direct or
named-integration path to its exact admitted arity. The gate still ends with
the assertion `(= :pass (:gate-status evidence))`.

The 106-fixture corpus reports 102 passing agreements, two recorded deviations,
and two recorded upstream failures, with no mismatches or unrecorded blockers.
It adds 49 fixtures, including entry calls, installation followed by
use, validation/refusal cases, display labels, empty-input cases, and
lookup/removal behavior. No upstream or `src/cljc/` source changes are made.
No new exclusion is needed: the admission denominator remains 155 bindings
(146 admitted, 9 excluded), and 175 candidate function/arities (165 admitted,
10 excluded). All 165 admitted arities are exercised: 39 directly and 126
through named integrations, with zero unexercised entries. The manifest
dispositions are unchanged.

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

Passing direct calls contribute only their original function and argument
count. Passing named integrations contribute their declared source dependency
closures under the existing acceptance rule; this is not dynamic branch or
arity tracing. (Provenance: this named-integration coverage basis is computed
by this round's counting change — it is not a pre-existing measure.) The three new named integrations are `:unfold-install-use`,
`:opacity-install-use`, and `:fundamental-install-use`. Their context adapter
identities are recorded explicitly alongside the existing five integrations.
All final residuals have an individually asserted primary path.

Only `:pass` fixture rows contribute exercise evidence. Neither recorded
upstream failures nor recorded deviations contribute. `slice9_unexercised.edn`
is empty, and any future admitted arity without a passing direct or named
integration path blocks the gate.

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

The oracle suite passes 25 tests and 1,147 assertions with zero failures or
errors. The four regression suites pass 122 tests and 1,338 assertions with
zero failures or errors. Kondo reports zero errors and warnings; cljstyle and
`git diff --check` pass. The checked-in command outputs and `slice9_verification.edn` record the final
oracle, four regression suites, kondo, formatting, and whitespace results.
`slice9_evidence.edn` is explicitly regenerated by
`yang.latte-oracle-test/write-kernel-gate-evidence!`; normal tests do not write
it. It retains the pinned upstream revision, compiler digest, runtime,
instruction budget, per-fixture outcomes, dependency graph, admission
inventory, exercise paths, recorded boundaries, and recorded upstream failures.

Execution scope is both C evaluators on the JVM with 1,000,000 instructions
per lane. This fixture-only round does not add Node/Dart host verification.
