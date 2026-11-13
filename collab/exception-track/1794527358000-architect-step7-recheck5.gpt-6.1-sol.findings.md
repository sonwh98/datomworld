Created-GMT: 2026-11-12 23:57:01 GMT
Created-Local: 2026-11-13 06:57:01 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: codex (gpt-6.1-sol)
Session-ID: unavailable in this environment
Role: Architect, read-only step-7 re-check round 5
Reviewed-Base: 265c4a4590259f2ea592cb81cd6991aa7b8afde8
Reviewed-Tip: 59329a054a66041cb2c5816aae0e97dc65e2b542
Verdict: RESIDUALS — NOT MERGE-READY

# Step-7 re-check round 5

The exact-integer repair closes both concrete count-domain defects from re-check 4:
ordinary acquisition no longer uses native overflowing/mixed-carrier arithmetic, and a
computed Ratio is rejected before integer/compare. However, I rule AGAINST the restored
endpoint disposition. One P2 residual remains: a value admitted and returned as the
original local Exception becomes ForeignException on its next ordinary reception because
the first reception moved its count onto the excluded maximum. This was reproduced
through parsed/lowered PHP source at the reviewed tip.

The source and tests were reviewed read-only. Only this requested findings artifact was
written; no source, tests, fixtures, configuration, caches, or git history were modified.

## P2 residual — ordinary ownership changes turn a local exception into a foreign one

Location: src/cljc/yang/php/antlr/prelude.cljc:2937-2939.
Consumer: php.rt/receive-condition at :2975-2977.
Coverage gap: test/yang/call/php_exception_test.clj:627-644 exercises only one reception.

The test's own near-max payload is sufficient:

```clojure
(assoc {} :php/obj
       (cell/new (assoc {:class "Exception"}
                       :rc (integer/parse "9223372036854775806"))))
```

Supply its thrown condition through a callable's actual :yang.call/raise exit, and call
this ordinary PHP receiver TWICE with the same callable/condition:

```php
function receive($f) {
    try { return $f(); }
    catch (Throwable $e) { return $e; }
}
```

Keep the first returned value live while making the second call. No count edits, numeric
operations, or malformed-condition tricks occur between the two calls. Firsthand results
from current-tip source, production host registry and JVM AST walker:

| Initial count | First result identical | Count after first return | Second result identical | Second class |
| --- | --- | --- | --- | --- |
| 1 | true | 2 | true | Exception |
| 9223372036854775806 | true | 9223372036854775807 | **false** | **ForeignException** |
| 9007199254740992 | true | 9007199254740993 | true | Exception |

The failing case completed normally at the VM level; this is a semantic identity failure,
not another host overflow. Its count after the second call remains 9223372036854775807.
A separate parserless two-reception probe gave the same classification failure.

The first call proves exactly why the engineer's claim that the endpoint is "not a count
php's machinery could have produced" is false: php's own repaired ownership operations
just produced it. The value is still throwable by php/throw-value, which checks the
Throwable hierarchy and uses the repaired exact addref. Only boundary reception changes
its class/identity. An ordinary catch retaining one more reference cannot make an otherwise
unchanged local exception foreign.

### Endpoint ruling

Section 8.10.7 rule 1 and the owner ruling v2 R2 require already-local exceptions to retain
ordinary catching and identity. Rule 3 applies to an OTHERWISE-foreign payload; it does
not authorize reclassifying this runtime-produced state just because its private reference
count reached the source-language integer endpoint.

The accepted Python implementation at prelude.cljc:1067-1079 recognizes its own exception
representation/hierarchy and raises that value unchanged. It has no PHP reference-count
analogue that would justify a signed-64-bit cutoff on PHP's now-exact ownership arithmetic.
PHP still needs representation and operability checks; that does not make every previous
count cutoff a semantic invariant after its original arithmetic reason has been removed.

I therefore rule the other way on the handover question: the signed-64-bit endpoints,
by themselves, are not a reason to wrap an otherwise admitted Throwable under the repaired
arithmetic. The positive maximum counterexample alone is enough to reject the restored
policy. The minimum-only exclusion likewise lacks the old decrement-overflow justification;
any separately proposed count invariant must be justified consistently, not inferred from
the old host carrier limits.

Required completion:

- Preserve exact integer/add and integer/sub and the exact-integer admission predicate.
- Make reception preserve local type/identity across ordinary acquisitions and later
  receptions, including the runtime-produced endpoint and beyond-native results.
- Extend the near-max source pin to two calls while the first result remains live, with
  original identity and original Exception class on BOTH results.
- Keep endpoint coverage, but revise its asserted disposition under this ruling instead
  of deleting the cases. Keep malformed, competing-marker, ratio, missing/non-number and
  legitimate-local controls with complete-condition/evidence/payload checks for foreign
  cases.

This revises the old endpoint disposition explicitly in response to the requested ruling;
it is not permission to silently invert or remove unrelated pins.

### Do not apply the documented bare revert without a limit-aware replacement

The engineer is correct about the separate integer-module limit hazard: integer/integer?
recognizes carriers without checking ::integer/max-bits; integer/add and integer/sub can
return ::bit-limit. Installing that keyword as :rc would make the next integer operation
refuse. Merely deleting both bounds and accepting EVERY exact integer is not a complete fix.

That hazard does not justify the particular strict 64-bit boundary: MAX, MAX+1 and MIN are
well inside the profile's minimum 4096-bit arithmetic limit. Count validation/limit handling
must distinguish genuinely unsupported state from the local results ordinary ownership
now produces. No broad host catch is authorized.

Two details in the findings' supporting argument should also be corrected:
(1) raw clojure.core/* on two Long operands does not auto-promote past overflow; a BigInt
seed is needed for the proposed repeated-squaring construction; (2) an initial 63-bit
bound does not prove ALL subsequent ownership results stay 64-bit. Neither correction
negates the real ::bit-limit hazard, and neither is the blocking reproduction above.

## Ruling 1 — both re-check-4 count-domain counterexamples are repaired

php/addref (:1856-1857) and php/delref (:1869) now use the integer module. Its checked big
path promotes operands to the host big carrier, computes exactly, and demotes to the
canonical carrier; its fast path falls back when the safe native result range is exceeded.
The ordinary lower-return acquisition still calls php/addref (lower.cljc:2945), so the
repair applies after reception as required, including copying/rethrow operations using it.

The source receiver above now completes its FIRST near-max call with original identity;
the previous native-long overflow is closed. The 2^53 decimal also completes with original
identity through both calls on the JVM.

For JS, dao.host.integer/exact-integer? admits BigInt, promote keeps both arithmetic
operands in BigInt form, and demote produces the canonical Number/BigInt result. Thus the
count is no longer sent to raw BigInt-plus-Number arithmetic. integer/sub demotes zero to
the native zero, preserving the existing (= n 0) destruction test. This is verified by
reading the actual host/module implementations; no complete Node/Dart runtime green is
claimed by this review. The decimal in the JVM pin is a Long and alone would not prove JS.

At :2936 integer/integer? now precedes BOTH integer/compare calls. Its implementation
delegates directly to host/exact-integer?, so a computed JVM Ratio, float64 content, a
Double, nil, or a non-number never reaches those comparisons. The old representation
classifier php/int? and the broad data/number? check are gone from count admission.

Firsthand direct reception controls, including full evidence and payload identity:

| Count expression | ForeignException | Original local identity | Whole condition retained | Original payload retained |
| --- | --- | --- | --- | --- |
| (/ 1 2) | true | false | true | true |
| (data/float64 1) | true | false | true | true |
| (data/float-value 1) | true | false | true | true |
| (/ 6 3) | false | true | n/a | n/a |
| parse MAX | true | false | true | true |
| parse MIN | true | false | true | true |

The last two rows record CURRENT behavior, which the endpoint ruling above rejects;
the retention machinery itself works on those rejected paths. The computed-ratio
residual is CLOSED, not merely hidden behind another refusal.

## Ruling 3 — accept leaving finalize!'s raw unpin in this round

At prelude.cljc:4382 finalize! overwrites :rc with the literal native 1 BEFORE invoking
the destructor. The raw subtraction at :4386 therefore does not consume the large count
admitted from a boundary. Under ordinary PHP ownership, only acquisitions/releases made
during this destructor contribute to its unpin value.

I accept that scoped argument for the requested residual: the near-max and JS-BigInt
received-carrier counterexamples cannot flow into this subtraction. No conversion is
required for this review. This is not a proof against arbitrary guest cell/set! corruption
during a destructor, nor an unrestricted totality claim for an unbounded number of
acquisitions. Neither is the counterexample established here.

## Ruling 4 — pins, publication surface and scope

The requested range is one commit and exactly three changed files: the PHP prelude,
the PHP exception test, and the r10 findings. No other frontend, VM implementation,
lowerer, shared kit, fixture, ledger, or design file changes in this range.

No previous assertion or payload case is removed. The two original endpoint payload
forms remain, with their foreign-retention observations; the computed-ratio case and
the ordinary catch/return acquisition test are additive. The committed shape matrix is
now TWELVE cases with FOUR observations each (48 expected keys per backend), rather than
the supplied gate summary's 45-key description. I did not inspect that remote batch's
raw output and do not equate these cardinalities; the direct ratio result above is
firsthand evidence of the repaired ratio path.

Adding integer/integer? to host-names declares an already-existing integer module export,
not a new PHP publication door. Removing data/number? removes an unused declaration.
The PHP exports, aliases, reserved provenance doors, receiver/mint privacy and mint call
graph are unchanged. There is no broad host catch or arbitrary-condition mint helper.

addref/delref are outside the original receiver's textual region, but changing ownership
operations is expressly required by re-check 4's whole-consumption-path residual and
this dispatch. No unrelated ownership algorithm changed. The literal surface-registry
file 1794343000000 remains absent here, as disclosed in the original review; scope is
checked against the available binding briefs and the actual diff, not a nonexistent
registry artifact.

## Evidence and remaining gate disposition

Firsthand this round:

- Full requested source/test diff, re-check 4, r10 findings, governing architecture,
  §8.10.7, owner ruling v2, accepted Python reception and relevant integer/ownership/
  lowering implementations inspected.
- Bounded current-source JVM diagnostics completed with exit 0: direct count controls
  and the parsed/lowered receiver called twice with three count controls.
- Static surface/adoption/reachability pins: 3 tests, 14 passes, 0 failures, 0 errors.
- git diff --check 265c4a45..59329a05 passed; tracked working-tree status remained clean.

The source probe borrowed only existing generated parser class directories from the
phpexc checkout, plus cached dependency jars; current worktree source/test roots led
the classpath. PHP manifest.edn and PhpParser.g4 match that checkout byte-for-byte.
No builds, generated files, dependency resolution or cache writes were performed.
A preliminary source-probe invocation had a diagnostic-script read error; the
reported source table is from the corrected, successful bounded invocation, not that
attempt. The parserless identity failure was independently observed as well.

The orchestrator's tip gate evidence in the dispatch is accepted as supplied evidence:
focused count/nil/shape/directed/adoption/reachability batches, listed namespaces, kondo
and cljstyle. Those successes cover the existing assertions; a one-reception count pin
does not exercise the newly demonstrated second reception. I did not rerun the linked
batches, contract kit, Node/Dart boundary harness, full directed matrix or foundation gate.

The three disclosed :php/local-reception :payload-identical lane-blocked failures retain
their previous disposition; no changed shared fixture/expectation contributes to the
new failure. Existing nil-pin class/provenance coverage notes, suspension/context lifetime
and steward-owned remote/foundation obligations remain as previously recorded.

**RESIDUALS — NOT MERGE-READY.** The arithmetic and computed-ratio repairs stand.
Complete stable local identity at subsequent reception before this PHP step-7 slice closes.
