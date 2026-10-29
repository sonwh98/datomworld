# Slice 9 kernel differential gate — BLOCKED

Hardening program 2 implementation round, based on `7dc18478`. This is an
implementation evidence report, not the final verification slice or a
conformance certificate. The close-out assertion remains red.

The baseline had 54 fixtures: **4 passing, 2 mismatches, 48 blocked**. The same
54 fixtures now have **50 passing, 2 mismatches, 2 blocked**. Three additional
fixtures exercise explicit fresh-supply rebuilding and the real proof dispatcher's
opacity transitions and refusal. The complete current result is **53 passing,
2 mismatches, 2 blocked, 57 attempted, 0 skipped**.

Every fixture has independent A, B, C-walker and C-semantic records in
[`slice9_evidence.edn`](slice9_evidence.edn). Each passing row additionally
records explicit A/B, A/C-walker, A/C-semantic and C/C agreement flags. No host
parser, normalizer, type checker, proof dispatcher or extension callback replaces
guest execution. The assume/have/discharge proof passes within the unchanged
1,000,000-instruction limit on both C lanes.

## Admission denominator

The regenerated manifest contains 155 bindings: **146 admitted, 9 excluded**.
Its 147 functions have 175 candidate function/arity entries: **165 admitted,
10 excluded**. Exclusions name clock access, host pprint/output capture, and
functions that directly use those diagnostics. Their guest entry points raise
an explicit nonportable-binding refusal if reached; excluding a diagnostic
binding does not exclude the proof dispatcher that contains a diagnostic arm.

The proven direct A/B/C execution count is **22/165 admitted arities**. The
remaining **143** admitted entries are individually listed under
`:coverage :unexercised`. Transitive execution coverage remains **unproven**;
source dependency membership never substitutes for execution. Fixture success
does not narrow the admission denominator. These obligations independently keep
the final gate open.

Regenerate admission decisions and the existing derived throw/case tables with:

```sh
clj -X:test:dev latte-oracle-tables/-main
```

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

## Remaining mismatches with minimal repros

Both mismatches arise from upstream placing the **host `clojure.core/meta`
function itself** in after-qed error data. A and B retain that nonportable host
value; C carries its guest closure. This difference is not accepted or erased.

```clojure
;; :elab-after-qed
(latte-kernel.proof/elab-proof env []
  '[[:qed (lambda [A :type] (lambda [x A] x)) {}]
    [:qed missing {}]])

;; :check-after-qed
(latte-kernel.proof/check-proof env [] 'late '✳
  '[[:qed (lambda [A :type] (lambda [x A] x)) {}]
    [:qed missing {}]])
```

The two remaining blockers are upstream failures, not accepted negative results:

```clojure
;; :typing-ascription — pinned upstream declares type-of-ascribe but never
;; defines it. A raises an unbound-function error; B/C refuse that exact branch.
(latte-kernel.typing/type-of-term env '[[A ✳] [x A]]
  '(:latte-kernel.syntax/ascribe x A))

;; :compile-malformed — A and B raise native scalar-sequence exceptions.
;; C returns a portable sequence refusal, which is not counted as agreement.
(latte-kernel.proof/compile-proof '[42])
```

## Verification

The required compiler, macro, stream-eval and store-write-audit regression suites
pass: **122 tests, 1,338 assertions, 0 failures, 0 errors**.

```sh
clj -M:test -n yang.clojure-test -n yin.vm.macro-test \
  -n yang.clojure.stream-eval-test -n yin.vm.store-write-audit-test
clj -M:test -n yang.latte-oracle-test
```

The oracle suite reports **23 tests, 997 assertions, 1 failure, 0 errors**.
It retains exactly the expected close-out gate failure; the other oracle tests pass. The manifest schema and gate assertions check dispositions,
the admitted/excluded partition, unique fixture execution, negative outcomes,
and agreement records. Clj-kondo reports **0 errors, 2 inherited warnings, no new
warnings**. Cljstyle and `git diff --check` pass.

The stream suite's generated-parser prerequisites were rebuilt locally from
copied immutable cached inputs, with the repository generator checking all
pinned input and generated-source digests. No other checkout's mutable build
outputs are shared. The acceptance above executes both C evaluators on the JVM;
it does not claim a new Node or Dart host verification run.

The pinned upstream revision is `86fca0d1d054af005cb37395eae04500d45e6279`.
The evidence records the base compiler/VM revision, exact compiler source SHA-256,
host versions, context/assembly adaptation version 2, unchanged instruction
budget, admission inventory, and dependency graph. Normal tests do not rewrite
checked-in evidence; explicit refresh uses
`yang.latte-oracle-test/write-kernel-gate-evidence!`.
