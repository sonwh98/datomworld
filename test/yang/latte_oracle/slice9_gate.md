# Slice 9 kernel differential gate: BLOCKED, verification complete

The close-out gate on `yang-hardening2`, based on `daef60da`, remains
**`:blocked`**. This is the terminal result of the gate-verification slice,
not a conformance certificate. No production source or underlying error
behavior changed.

Of 57 attempted fixtures, **53 pass, 2 are recorded deviations, 2 remain
blocked, 0 mismatch, 0 skipped**. Both after-qed rows explicitly retain their
failed comparison flags and all A/B/C outcomes. They are not counted as
agreement. Their `:recorded-deviation :evidence` pointers identify the exact
upstream host-value payload, and `:recorded-portability-boundaries` lists them
in the gate summary.

The admitted denominator is unchanged: 155 bindings (146 admitted, 9 excluded),
175 candidate function/arities (165 admitted, 10 excluded). **145/165 admitted
arities satisfy the plan: 22 direct and 123 through named integrations. The
remaining 20 are unexercised.**

## Acceptance counting

Slice-9 acceptance is “Every admitted function/arity exercised DIRECTLY OR
THROUGH A NAMED INTEGRATION FIXTURE.” The accepted named top-level closures are:

- `:unparser-guest-closure`
- `:implicit-typed-closure`
- `:implicit-throwing-closure`
- `:check-assume-have-discharge`
- `:parse-to-normalize-to-type`

Only passing fixtures contribute. Direct evidence retains the original call
and arity criterion. Integration evidence uses the fixture's transitive
`:included-functions` dependency closure and the explicit
`:integration-context-identities` mapping for existing context adapters.
Each admitted arity records `:exercise`, `:direct-fixture-ids`, and
`:integration-fixture-ids` under `:coverage :entries`. This is the requested
named-closure acceptance criterion; it does not claim dynamic branch coverage
or that every arity body ran. Historical slice fixtures outside these named
integrations do not silently contribute.

A residual entry has neither kind of fixture path and records its
`:missing-dependency`. The gate requires every fixture to be `:pass` or
`:recorded-deviation` and no residual unexercised entry. Execution blockers
remain blockers, never successful negative cases.

## Recorded portability boundaries

`:elab-after-qed` and `:check-after-qed` both reject proof steps after qed.
Upstream puts the host `clojure.core/meta` function in its exception data;
A/B evidence records `{:nonportable-host-value ...}`, while C retains a guest
closure. The disposition guard requires these exact fixture IDs, completed
executions, A/B equality, the expected upstream host function, matching error
message and remaining error data, and an actual guest closure in each C lane.
A different mismatch remains a mismatch. All original comparison code and
agreement flags remain intact.

## Residual obligations: exact arities and missing paths

The following 20 entries are also recorded in
[`slice9_unexercised.edn`](slice9_unexercised.edn) and individually appended to
[`known-gaps.md`](../../../docs/agents/known-gaps.md). The next coverage program
must add real direct fixtures or named integrations with these dependencies.

- `[latte-kernel.defenv/mkenv 1]`: The fixtures start from empty-env or an explicit kernel context; no named closure calls mkenv. Needs an environment-construction fixture entering mkenv.
- `[latte-kernel.norm/beta-red 1]`: normalize enters beta-delta-normalize, not the standalone beta-red wrapper. Needs a named normalization integration that calls beta-red.
- `[latte-kernel.norm/install-unfold-implicit! 1]`: Implicit fixtures supply the extension in the immutable context; no named closure invokes the upstream installer. Needs an explicit installation and subsequent normalization sequence.
- `[latte-kernel.norm/delta-normalize 3]`: normalize uses beta-delta-normalize and lower delta-step helpers, bypassing this public delta-only wrapper. Needs a delta-only normalization fixture.
- `[latte-kernel.norm/delta-normalize-local 3]`: No named closure enters the local-only delta wrapper. Needs a fixture that calls delta-normalize-local with local definitions and checks namespace isolation.
- `[latte-kernel.presyntax/exists-kw? 1]`: The pinned parser dispatch does not reference exists-kw?; naming an existential term cannot add this absent edge. Needs a direct predicate fixture or an integration explicitly calling it.
- `[latte-kernel.proof/show-deftype 1]`: Only diagnostic display paths call show-deftype; named proof elaboration does not select those excluded display functions. Needs a portable display-result fixture and explicit diagnostic dependencies.
- `[latte-kernel.proof/install-opacity-function 1]`: Proof fixtures inject opacity-policy in the context and do not invoke its installer. Needs an installation and subsequent opaque/transparent proof sequence.
- `[latte-kernel.syntax/appify 2]`: Named parser and proof closures build applications without calling appify. Needs a syntax-construction integration entering appify.
- `[latte-kernel.syntax/term? 1]`: The named closures use individual syntax predicates, not the public recursive term? validator. Needs a term-validation fixture entering term?.
- `[latte-kernel.syntax/bound-vars 1]`: Substitution and proof closures use free-vars/vars and do not call bound-vars. Needs a binder-analysis integration entering bound-vars.
- `[latte-kernel.typing/proper-type? 1]`: The proof chain calls type-of/type-check? directly, bypassing proper-type?. Needs a proper-type? fixture at the one-argument entry.
- `[latte-kernel.typing/proper-type? 2]`: No named closure calls proper-type?; its context-default wrapper is not reached from type-of. Needs a proper-type? fixture with an explicit local context.
- `[latte-kernel.typing/proper-type? 3]`: No named closure calls proper-type?; its body calls type-of but the reverse dependency does not exist. Needs a proper-type? fixture with explicit environment and context.
- `[latte-kernel.unparser/prod-impl-unparser 1]`: The unparser integration registers only its custom guest closure; the fundamental product unparser is not in that closure. Needs fundamental-unparser installation followed by product unparsing.
- `[latte-kernel.unparser/install-fundamental-unparsers! 0]`: The named unparser fixture uses an empty registry plus a custom registration; no fundamental installer is selected. Needs explicit fundamental-unparser installation and use.
- `[latte-kernel.utils/pair? 1]`: No selected named closure references pair?. Needs a utility predicate fixture or a named consumer that explicitly calls pair?.
- `[latte-kernel.utils/vectorn? 2]`: No selected named closure references vectorn?. Needs a vector-size validation fixture or a named consumer that calls vectorn?.
- `[latte-kernel.utils/safe-get 2]`: Named closures use ordinary get/fetch adapters rather than safe-get. Needs a safe lookup fixture covering success and missing-key rejection.
- `[latte-kernel.utils/vremove 2]`: The explicit unparser unregister adapter uses filter/remove directly and does not depend on upstream vremove. Needs a vector-removal fixture entering vremove.

## Remaining execution blockers

- `:typing-ascription`: pinned upstream declares `type-of-ascribe` but never
  defines it. A raises an unbound-function error; B/C refuse that exact branch.
  A future program needs an upstream disposition or a versioned oracle
  revision with a real definition before it can establish agreement here.
- `:compile-malformed`: scalar proof input `[42]` produces native sequence
  exceptions in A/B and a portable sequence refusal in C. A future program
  needs an explicit error-boundary decision and fixture evidence. This slice
  neither changes nor accepts the differing outcomes.

## Closed dependencies and explicit conventions

- Guest implementations admit `butlast`, `last`, bare `nth`, `string?`, `update`,
  and callable collections; the compiler lowers `->` through ordinary control
  forms. Callable collections use the guest library, with ordinary closures
  taking the direct callable path.
- `kind/var?` explicitly admits the JVM Clojure Var predicate. Namespace lookup
  still requires a separately installed host capability.
- Metadata attachment uses a declared pure **`kind/with-meta` seam**. This is a
  departure from the requested guest-only implementation: metadata is carried
  on existing host symbols/collections, and the current `data` module exposes
  no operation that can attach it. Introducing a second tagged representation
  would change symbol identity and every collection boundary. No Yin source or
  ABI was changed; domain metadata is checked by the existing shape comparison.
- Fresh allocation consumes immutable `{:counter N}` data and returns
  `[symbol next-supply]`, constructing `G__<prefix><N>`. The original A call
  `(let [a (gensym "fresh") b (gensym "fresh")] (= a b))` returns false.
  B/C thread the supply explicitly through the corresponding two allocations,
  also returning false. Prefix-only ambient allocation is refused; no hidden
  mutable counter is installed. Numeric deterministic constructors remain
  compatible with slice 8. `rebuild-type` returns `[original-result next-context]`,
  and its proof caller binds that next context explicitly.
- The free-vars argument boundary extracts the original global/local definition
  pair from `:definitions` before entering the upstream body. Its original
  `(second def-env)` access is preserved; local capture now returns `#{x}` on A,
  B and both C lanes.
- The actual `typing/unfold-implicit` source runs through portable `try/catch`.
  Throws retain early-return outcomes; exception bindings carry error data;
  `.getMessage`, `ex-message`, and `ex-data` access that data. Both the typed and
  throwing guest extension fixtures pass. Unsupported host accessors such as
  `.getStackTrace` are refused at compile time and recorded in the evidence.
- The former opacity atom names the explicit `:opacity-policy` context field.
  Installation returns an updated context. The real upstream proof dispatcher
  threads that context through opaque/transparent arms; the two added opacity
  fixtures pass without substituting slice 8's restricted dispatcher.

Observer exceptions can attach an entire host VM session. Evidence records
whether that diagnostic snapshot was attached and retains its exception data
and causes, without recursively expanding the VM's duplicated closure
environments. It does not remove domain error payloads.

## Verification

The final `clj -M:test -n yang.latte-oracle-test` run reports **25 tests,
1,037 assertions, 1 failure, 0 errors**. The only failure is the unchanged
terminal `:pass` check; every other assertion passes. The four regression
suites pass **122 tests, 1,338 assertions, 0 failures, 0 errors**. Kondo on
the changed Clojure file reports **0 errors, 0 warnings**, including removal
of the two inherited test-file warnings. Cljstyle and `git diff --check` pass.
The known-gaps ledger appends 22 rows, **127 to 149**, without changing any
previous bytes: two recorded host-value deviations and 20 coverage obligations.

Verification results and complete command output are recorded in
`slice9_oracle_test.txt`, `slice9_regression_test.txt`, `slice9_kondo.txt`,
`slice9_evidence_refresh.txt`, and `slice9_verification.edn` beside this report.
The four required regression namespaces are `yang.clojure-test`,
`yin.vm.macro-test`, `yang.clojure.stream-eval-test`, and
`yin.vm.store-write-audit-test`.

The full differential evidence is
[`slice9_evidence.edn`](slice9_evidence.edn), explicitly regenerated with
`yang.latte-oracle-test/write-kernel-gate-evidence!`. Normal tests do not
rewrite evidence. It includes the unchanged upstream revision
`86fca0d1d054af005cb37395eae04500d45e6279`, compiler source digest, runtime,
admission inventory, per-fixture A/B/C records, dependency graph, exercise
provenance, residual reasons, and both recorded portability boundaries.
The instruction budget remains 1,000,000 per C lane. This is JVM execution of
both C evaluators; remote Node/Dart landing verification remains outstanding.
