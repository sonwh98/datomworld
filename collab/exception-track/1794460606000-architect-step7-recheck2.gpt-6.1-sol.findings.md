Created-GMT: 2026-11-12 05:23:00 GMT
Created-Local: 2026-11-12 12:23:00 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: codex (gpt-6.1-sol)
Session-ID: unavailable in this environment
Role: Architect, read-only step-7 re-check round 2
Reviewed-Base: 4f262ffa
Reviewed-Tip: c91f452b1390d76f9257532cdc8a8811d0799c58
Verdict: RESIDUALS — NOT MERGE-READY; original two counterexamples CLOSED, P2 still open

# PHP r5 step-7 re-check round 2

The guard and instance-of now agree on which representation supplies the class. Both
previous counterexamples are repaired, their regressions construct the exact cases, and
P3 meets the requested tightening. However, reception does not stop at instance-of:
the immediately following php/addref still chooses a different representation and reads
additional unvalidated state. Two bounded firsthand diagnostics reproduce operational
failures instead of ForeignException retention. P2 therefore remains open.

Only this requested findings artifact was written. Source, tests, fixtures, configuration,
and git history were left unchanged.

## P2 residual 1 — a different representation still reaches addref (blocking)

Location: src/cljc/yang/php/antlr/prelude.cljc:2859-2864 and :2891-2892.
Follow-on consumer: php/slot-kind (:1760-1766), php/cell-of (:1769-1776),
php/released? (:1829-1833), php/addref (:1834-1841).

A minimal plain foreign payload is:

```clojure
{:php/type :obj :class "Exception" :php/ref 42}
```

The map branch of obj-val? accepts it, and instance-of reads the validated top-level
"Exception" class. Reception then executes (php/raise (php/addref v)).
addref calls cell-of, whose slot-kind gives :php/ref precedence over both :php/arr
and :php/obj. It selects 42, which neither the guard nor instance-of authenticated.
released? dereferences that selected value.

Firsthand result through a synthetic callee's supplied raise exit and php/call:

```
:map-mixed-ref :guard true
:map-mixed-ref :failure "Dead or forged cell reference"
{:reason :dead-or-forged-reference, :effect :cell/get,
 :kind :cell-ref, :id nil}
```

The handle branch has the same remaining mismatch. This legal guest construction:

```clojure
(assoc (php/make-error "Exception" "real") :php/ref 42)
```

passes the guard using the genuine :php/obj cell, passes Throwable classification using
that cell's class, then dereferences the unauthenticated :php/ref entry during addref.
I reproduced the same guard-true / dead-or-forged-reference outcome.

Thus the class-read discriminator is aligned, but the entire check/consumer path is
not single-sourced: it can validate an object/map representation and consume a reference
marker instead. The payload construction requests no invalid VM operation; PHP reception
introduces that operation. Under section 8.10.7 rule 3, these invalid local-object shapes
must retain the complete condition in ForeignException.

## P2 residual 2 — a string class is insufficient for the consumed handle state (blocking)

Location: src/cljc/yang/php/antlr/prelude.cljc:2863-2864, followed by :2892 and :1841.

Legal guest construction:

```clojure
(assoc {} :php/obj (cell/new {:class "Exception"}))
```

The live task-issued cell authenticates; its class is a string and resolves as Throwable.
But the handle is not valid PHP counted-object state: :rc is absent.
addref reads that unvalidated field and executes (+ nil 1).

Firsthand result through the same boundary path:

```
:string-class-no-rc :guard true
:string-class-no-rc :failure
"Cannot invoke \"Object.getClass()\" because \"x\" is null"
ex-data: nil
```

The captured stack begins in clojure.lang.Numbers.ops / Numbers.add /
clojure.core$_PLUS_, matching the addition in php/addref at :1841.
No ForeignException is delivered.

This is the same authenticity-versus-valid-state issue one operation later: the repair
validates the class consumed by instance-of, but reception also consumes :rc before
raising the accepted local object. It must reject invalid local state safely before
that operation, preserving the original condition/payload as foreign.

### Required completion

Make admission agree with the representation actually used throughout the local
reception path, including ownership acquisition. Ensure an admitted handle's state is
safe for the operations reception performs; do not authenticate one cell and then select
another by an unchecked competing marker. Keep legitimate map errors, real handles, and
unchanged local-Throwable identity. Add boundary regressions for the plain map with
:php/ref 42, the real object with a competing forged reference marker, and the authentic
string-class cell with missing/invalid count state. Assert local ForeignException,
complete condition retention including evidence, and original payload identity.

Do not repair this by broadly catching operational failures. These are residual
admission holes in the existing P2 rule-3 obligation, not evidence that r5 introduced a
new regression; the changed class-read predicate alone does not close the whole path.

## Original two counterexamples — CLOSED; exact regression coverage accepted

obj-val? at :2859 and instance-of at :4592 both test whether :php/obj is nil.
For the handle class read, authentication precedes cell/get and data/string? checks the
class obtained from that same cell. A non-nil marker cannot now pass only a top-level
typed-map class check and then be dereferenced unchecked by instance-of.

The new probes at test/yang/call/php_exception_test.clj:520 and :525 match exactly:

- Mixed tags: {:php/type :obj :class "Exception" :php/obj 42}.
- Authentic invalid class: a guest-evaluated cell/new {:class 42}, installed by
  observation-map-form, which emits assoc rather than leaving the cell expression as
  inert data inside a map literal.

The existing three shapes remain. Each probe compares the full four-key expected
observation, including local ForeignException, complete condition equality, and original
payload equality. The condition includes :yang.call/thrown and extra evidence, so
condition equality covers both reason and evidence.

Independent parserless boundary diagnostics on the reviewed tip produced, for BOTH
original counterexamples:

```
:guard false
:reception {:foreign true, :identical false,
            :condition-identical true, :payload-identical true}
```

Controls using php/make-error-map and php/make-error both passed admission and were
delivered as the identical local payload, without a foreign wrapper.

## P3 — CLOSED to the requested specification

resolution-refusal? at test/yang/call/php_exception_test.clj:407-414 requires a Throwable
with map ex-data naming exactly the symbol php/receive-condition. The assertion at
:456-457 permits only that result or a map containing an explicit false value for
:forged-forwarded. Missing keys, nil, unrelated exceptions, and malformed observations
no longer pass accidentally.

Firsthand helper checks accepted the expected symbol and rejected another symbol,
an unrelated Exception, nil, and an empty map. This meets the previous requested
negative-pin outcome; no further P3 residual is raised.

## Surface, scope, and evidence limits

No new export, receiver alias, privileged mint door, host catch, or shared-kit change
appears in the runtime/test diff. The private receiver remains private. The added
cell/get is guarded by the issuer's authenticity query. The residual is validation of
the rest of the already-existing consumer path.

Firsthand verification:

- Read the complete 4f262ffa..c91f452b diff, prior re-check, engineer report, and relevant
  class/ownership/reception code; HEAD is exactly c91f452b and reviewed source/tests have
  no working-tree differences from it.
- Existing adoption, mint-reachability, and retention-surface pins:
  3 tests / 11 passing assertions / 0 failures / 0 errors.
- Bounded JVM AST-walker diagnostics through a synthetic callee's supplied raise exit
  and php/call, caught by php/try: repaired original cases, both legitimate identity
  controls, and the residual cases above. Each diagnostic subprocess had a 45-second
  limit and completed.
- Attempted the two focused linked tests, php-handle-shaped-foreign-payload-test and
  php-exported-mint-forgery-test, in one subprocess with a 60-second bound. Only startup
  output was observed before timeout; no completed assertion result was obtained.
  This is not a claimed green or a diagnosed linked-test failure.
- Source/test diff whitespace check passed. The whole-range diff check reports one
  cosmetic extra blank line at EOF in the newly committed prior findings artifact
  (1794453820000); this is not a merge blocker.

The engineer's red-first chronology and linked/vector-VM green runs remain reported
evidence; I did not reconstruct their execution order. My residual reproductions are
parserless JVM diagnostics, not linked source acceptance on all hosts. No contract test,
full gate suite, remote lane, or Node/Dart harness was run.

The corrected 10/13 fixture tally is accepted. Existing steward-owned lane/instrument,
lifecycle, suspension/context-lifetime, and remote/foundation obligations retain their
prior disposition.

**RESIDUALS — NOT MERGE-READY.** The exact r4 counterexamples and P3 are closed.
Complete P2 across the ownership consumer before declaring the reception path safe.
