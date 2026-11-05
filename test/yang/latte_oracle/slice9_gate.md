# Slice 9 kernel differential gate: execution evidence correction

## Attribution section — version 1 (attribution-as-query ruling of record)

This section versions the executed-arity attribution contract per the signed
ruling `1794600000000-architect-attribution-as-query-ruling-of-record.md`
(v4) and its implementation round. The gate's credit rule is now a query
over caller-site-tagged execution records, not name+arity string matching.

- **Records.** Every instrumented arity body appends
  `[guest-function arity caller-site]` to the fixture's guest stream at body
  entry. The caller site id is assigned at APPLICATION EXPRESSIONS in the
  compiled AST, post-macroexpansion (the corpus runs under macros; source
  tagging would miss expanded sites), by a harness-owned instrumentation
  pass (`kernel-stamp-sites`) that threads each stamped call's argument
  vector through a guest site cell so the callee's first body form reads
  the applying site. This is instantiation #1 of the site contract,
  harness-level per Addendum A; yin.vm and yang.clojure are untouched. The
  walker/semantic ingresses ingest the stamped program unchanged.
- **Site tables.** Mechanically derived from each fixture's compiled AST
  (`R7`): every stamped site's callee, per-argument shapes
  (`:ambient-literal`, `:literal`, `:variable`, `:computed`,
  `:dynamic-callee`), enclosing definition, and self-delegation mark —
  recorded in the versioned [`slice9_site_tables.edn`](slice9_site_tables.edn)
  (`:version 1`) with the per-fixture canonical-source-hash of the inlined
  adaptation (R2b case 3's referent: a trace read against a differing hash
  refuses).
- **The query.** An upstream function-arity is credited when both C lanes
  record (a) the arity-preserving guest definition's body entry at the same
  arity through any real site (the degenerate 1:1 path), or (b) a body
  entry of a derived correspondence carrier — the guest/context callable
  and arity the derived correspondence names (`:attribution
  :ambient-correspondence` in the evidence) — entered through a
  SITE-LINKED record: the site table's static callee at that site is the
  carrier itself, and the site is not a self-delegation. The derived
  correspondence has three kinds. An `:ambient-delegation` path (R3's
  disjointness pair, `registered-definition?` /1) additionally requires
  the site's compiled context argument to have resolved to the
  adapter-supplied ambient literal. `:context-shift` paths — the retired
  offset table's scope (R6): the kernel-first context callable that
  replaced the upstream env parameter at the same arity or was added at
  one fewer argument (unregister-unparser!/1, unparse/1,
  register-unparser!/2 and /3, install-unfold-implicit!/1,
  install-fundamental-unparsers!/0, install-opacity-function/1,
  register-definition /2 and /3, local-definitions /1, …) — and
  `:context-copy` paths (the verbatim integration copies is-opaque?,
  unparse-term-once/rec) thread the adapted state explicitly, so their
  witness is the site-linked body entry of the carrier, whatever context
  value the site threads; the ambient literal never borrows across kinds.
  The retired offset table `kernel-context-extra-arguments` (six
  hand-declared +1 shifts) is replaced by that derived correspondence;
  its full coverage list is recorded in
  [`attribution_inventory.md`](attribution_inventory.md).
- **Refusals.** The seven-case battery
  (`test-attribution-battery`) pins: explicit `/2` supplying an empty
  environment never credits `/1` (disjointness by site identity and
  compiled argument shape, never value equality); unexecuted ambient sites
  borrow nothing; mismatched canonical-source-hash refuses; a missing lane
  refuses; a site declared ambient whose compiled argument is not the
  ambient literal refuses (compile-time AST verification); indirect-origin
  entries (`:dynamic` sites, the `:gate-site/none` entry site) refuse;
  variable context arguments are `/2`-only. A declared site id that never
  appears in any trace is a RECORDED DIAGNOSTIC
  (`:attribution :stale-site-diagnostics`), never silent non-credit. The
  `/3` self-delegation keeps its own unmapped site id: no credit, the
  originating call is the credited event.
- **H3-6 prep (the audit's query shape).** The independent audit
  re-derives the site tables from the same compiled ASTs, re-computes each
  fixture's canonical-source-hash, and re-runs the credit query over the
  two raw C-lane traces joined to the tables and the derived ambient
  correspondence; closure requires the reconstructed credit set to equal
  the gate's, with every withdrawn or gained witness explained.

The remainder of this document is the prior gate record, unchanged.

---

The gate on the hardening line is **blocked**. The architect denied the prior
165/165 closure claim because membership in a static dependency closure did
not prove execution of every arity. That claim is withdrawn. The gate retains
its final assertion `(= :pass (:gate-status evidence))`; missing execution
evidence must fail that assertion.

The 106-fixture corpus has **100 passing agreements, two recorded deviations
(`elab-after-qed`, `check-after-qed`), two blocked fixtures, and two recorded
upstream failures.** The four narrowly recorded exception dispositions remain
declared departures, not agreements. No upstream or `src/cljc/` source changes
are made by H3-0. Admission is unchanged: 155 bindings (146 admitted, 9
excluded) and 175 candidate function/arities (165 admitted, 10 excluded).

Recorded executions establish **107/165** admitted arities: 39 directly
and 68 through named integrations. The other **58**, including `parse/1`,
remain uncredited. The declared departures are carried by the versioned
[`slice9_declarations.edn`](slice9_declarations.edn) (provenance `ea18c8e4^`),
revalidated on every refresh by the representation-aware classifier: the
guest `:meta` value may be a pre-adoption raw closure or the admitted §8.10
descriptor; structurally invalid descriptors and any payload, message, script
or call mismatch refuse (descriptor authenticity itself is the compiler's
admission witness upstream of the outcome). The corrected counts and remaining arities
are recorded in [`slice9_evidence.edn`](slice9_evidence.edn) and
[`slice9_unexercised.edn`](slice9_unexercised.edn). The residual consumers in
[`fixture_closure.edn`](fixture_closure.edn) retain their concrete expected
outcomes; they do not establish closure of the full inventory, and their
count (20) predates the re-derivation — the live unexercised set is the
58-name list.

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

An independent audit re-derives the credited arities and fixture paths from
the two raw C traces. Kondo reports zero errors and warnings. The full oracle
runs 26 tests and 1,697 assertions with exactly one failure and no errors:
its retained `(= :pass (:gate-status evidence))` closure assertion. The
unexercised arities — **58** under the current accounting, including
`parse/1` — therefore continue to block acceptance. Verification results are
recorded in [`slice9_verification.edn`](slice9_verification.edn), with command
outputs beside it.
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
