Created-GMT: 2026-11-12 12:52:10 GMT
Created-Local: 2026-11-12 19:52:10 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: codex (gpt-6.1-sol)
Session-ID: unavailable in this environment
Role: Architect, read-only step-7 re-check round 3
Reviewed-Base: c91f452b1390d76f9257532cdc8a8811d0799c58
Reviewed-Tip: b5da2eb8354c2468dbf08d439b79932dde50dc52
Verdict: RESIDUALS — NOT MERGE-READY

# PHP r6 step-7 re-check round 3

The competing-representation residual is closed. The missing/non-number count cases
are closed, but the required completion was safety through ownership acquisition:
a number predicate does not establish that the actual increment is safe. An authentic
cell with a string Throwable class and a maximum native integer count passes admission
and still produces an operational arithmetic failure during addref. P2 remains open.

Only this requested findings artifact was written. Source, tests, fixtures, runtime
configuration, and git history were left unchanged.

## Blocking P2 residual — admitted numeric count still fails ownership acquisition

Location: src/cljc/yang/php/antlr/prelude.cljc:2877.
Consumer: php.rt/receive-condition at :2905-2906, php/addref at :1841.
Supporting definitions: src/cljc/yin/vm/data.cljc:418-424 and
src/cljc/yin/vm.cljc:404.

This is legal guest computation, with no competing marker:

```clojure
(assoc {} :php/obj
       (cell/new
         (assoc (assoc {} :class "Exception")
                :rc (integer/parse "9223372036854775807"))))
```

integer/parse produces the native Long maximum on the JVM. data/number? accepts it:
that predicate accepts host numbers and exact-integer carriers, without an increment
range check. The reference is authentic, the class is a string, and the initialized
Exception class is a Throwable. released? answers false because :released? is absent.
The selected cell is correctly the SAME object cell throughout, but addref executes
the raw host operation (+ rc 1). The VM's + primitive is clojure.core/+, which throws
on native long overflow.

Firsthand bounded AST-walker boundary diagnostic on b5da2eb8:

```
:max-long-count :admission
{:guard true, :slot-kind :obj-handle, :released false}

:max-long-count :failure long overflow
ex-data: nil
stack:
java.lang.Math.addExact
clojure.lang.Numbers.add
clojure.lang.Numbers$LongOps.add
clojure.lang.Numbers.add
clojure.core$_PLUS_.invokeStatic
```

The synthetic callable's :yang.call/entry delivered the complete thrown condition to
its supplied raise exit. php/call performed reception under php/try. The callable
requested no arithmetic or invalid cell operation; reception introduced the failing
addition. No local ForeignException or retained condition was delivered. I reproduced
the overflow in two separate bounded subprocesses; legitimate local controls completed.

This is the same P2 valid-state obligation as the missing-count case, not a demand
to catch an operational failure deliberately requested by foreign code. It is also
not merely an invalid count that happens to be harmless: it demonstrably escapes the
required boundary behavior on an existing supported host.

Required completion: establish that the admitted count is safe for the actual ownership
operation, including its numeric carrier and range on supported hosts, or change that
operation to safely support the admitted count domain. Invalid local state must take
the retaining ForeignException path before an unsafe increment. Do not add a broad
host catch. Add this exact guest-constructed numeric-count regression, asserting local
ForeignException, full condition retention with evidence, and original payload identity.
Keep the competing-marker, missing/non-number count, legitimate map-error, genuine
handle, and local identity controls.

The broader data/number? domain also includes JS/Dart exact-integer carriers. Admission
must be matched to the arithmetic actually executed on each host; the JVM reproduction
above alone establishes the blocker. No Node/Dart reproduction is claimed here.

## Exhaustive representation and immediate-consumer audit

- slot-kind (:1750-1766) has scalar sentinel/boolean exits, then tests non-nil :php/ref,
  :php/arr, and :php/obj in that order. Its only remaining selector is :php/type :array,
  choosing :plain rather than :scalar when all markers are nil.
- The new guard (:2867-2879) rejects EVERY non-nil :php/ref or :php/arr, including false,
  zero, forged references, or genuine competing references. Thus neither representation
  can reach local ownership acquisition by validating :php/obj or top-level :class.
  Nil marker entries are harmless: both admission and slot-kind ignore them.
- With no competing marker, cell-of (:1769-1776) selects exactly the authenticated
  :php/obj, or nil for an admitted marker-free map error. It has no other representation
  key. No unchecked alternate cell selector remains on this path.
- The handle class is read from that same authenticated cell, and string validation
  precedes instance-of/class-is?/lc. :php/type and php/kind's string/type/closure branches
  can make a candidate fail Throwable classification; they do not introduce another
  unchecked ownership reference.
- released? (:1829-1833) calls cell-of again, reads :released? from the same cell, and
  compares it with true. No further state schema is required for that comparison.
  It is safe after admission; released? itself was NOT changed into a globally total
  function for arbitrary direct callers with forged markers.
- addref (:1834-1841) returns the original value if cell-of is nil or released? is true.
  Otherwise it reads :rc, adds 1, associates the result into the same map state, and
  writes the same authenticated cell. A string :class already requires keyed state
  usable by these accesses; the remaining demonstrated unsafe operation is the numeric
  increment above. No payload method or guest callback intervenes between these reads.
- raise (:211-227) passes the admitted value to the existing handler/finally chain.
  It introduces no new payload representation discriminator. Arbitrary caller-installed
  handler behavior is not an admission operation.

Correction to the prompt, code comment (:2858-2859), and engineer explanation:
a marker-free map error with :php/type :obj has slot-kind :scalar, NOT :plain.
:plain is selected for :php/type :array. Both yield cell-of nil and no-op addref,
so this explanation error is not a runtime defect. The diagnostic confirmed :scalar.

## Exact repaired cases and regression restructure

The committed test has EIGHT payload shapes, THREE newly added by r6, and FOUR checks
per shape. The brief/report phrase "all four shapes" is not the actual payload count.

The three r6 additions at test/yang/call/php_exception_test.clj:485-489 are exact:

1. {:php/type :obj :class "Exception" :php/ref 42}.
2. Evaluated (assoc (php/make-error "Exception" "real") :php/ref 42), using a real handle.
3. Evaluated (assoc {} :php/obj (cell/new {:class "Exception"})), genuinely missing :rc.

The five prior payloads remain, including the mixed tags and authentic invalid-class
cell. observation-map-form constructs assoc expressions, so cell/new is evaluated
rather than embedded as inert data. No committed numeric-overflow or non-number-count
case exists; I checked the latter independently with :rc "bad".

The composed-program restructure preserves all four prior observations for every shape:
successful retain, local ForeignException, complete condition equality, and original
payload equality. The full condition includes :yang.call/thrown and extra evidence.
Each leg binds its later result BEFORE the next retain overwrites $saved; final map
assembly reads those saved results. Unique per-shape bindings and keys prevent aliasing
between observations. Exact expected-map equality requires all 32 values to be true,
and the new backend-set assertion additionally requires semantic, stack, and register.
Reducing the clojure.test assertion count does not weaken those comparisons.

One coverage improvement is advisable: also give an authentic invalid-class cell a
valid numeric :rc so that the handle-class check remains independently discriminating.
The preserved old {:class 42} cell now lacks :rc as well; count rejection can mask a
future regression in class validation. This is a coverage note, not an additional
demonstrated runtime defect in r6.

## Scope, surfaces, and firsthand verification

The runtime diff changes only obj-val? and its explanatory comment. No receiver alias,
new export, provenance door, transitive mint path, host catch, shared-kit change, or
fixture expectation weakening appears. The private receiver and mint remain private.
Existing P1/P3 closure and the earlier retention/clone/mutation dispositions stand.
The count-domain hole is a residual admission issue, not a newly opened mint surface.

Firsthand checks:

- Read the architecture rules, prior step-7/re-check/re-check2 findings, r6 engineer
  report, complete c91f452b..b5da2eb8 diff, and the representation/class/ownership paths.
  HEAD is exactly b5da2eb8; reviewed source/tests match that commit.
- Bounded parserless JVM AST-walker diagnostics through the synthetic callee's supplied
  raise exit, php/call, and php/try. Repaired reference-marker cases, missing count,
  non-number count, competing array markers (including false), mixed tags, and authentic
  invalid class all answered guard false and:
  {:foreign true, :identical false, :condition-identical true, :payload-identical true}.
- Genuine handle, legitimate map error, and a released handle control answered guard
  true and were delivered as the identical local payload without a foreign wrapper.
- Maximum native integer count answered guard true, then failed as documented above.
  Both diagnostic subprocesses completed within their 55-second bounds.
- Existing static retention-surface/adoption/mint-reachability pins:
  3 tests, 11 passing assertions, 0 failures, 0 errors.
- Attempted the committed unified php-handle-shaped-foreign-payload-test in that same
  subprocess after the static pins, with a 55-second total bound. No focused-test
  assertion result was obtained before timeout. This is neither a claimed green nor
  a diagnosed test failure; the engineer's completed linked runs remain reported
  evidence. No red-first execution chronology was independently reconstructed.
- Whole-range git diff --check passed. No contract test, full suite, remote lane,
  Node/Dart harness, or foundation merge gate was run.

Existing steward-owned lane/instrument reds, lifecycle and suspension/context-lifetime
obligations, full pair matrix, and remote/foundation gates retain their prior disposition.

**RESIDUALS — NOT MERGE-READY.** Representation selection is aligned, and the exact
missing-count case is repaired. Complete count admission against the actual ownership
arithmetic before claiming the whole reception path is safe.
