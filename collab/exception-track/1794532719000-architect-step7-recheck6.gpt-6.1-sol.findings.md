Created-GMT: 2026-11-13 01:24:41 GMT
Created-Local: 2026-11-13 08:24:41 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: codex (gpt-6.1-sol)
Session-ID: unavailable in this environment
Role: Architect, step-7 read-only re-check round 6
Reviewed-Base: 4a246994a05c722b6f901d4f74811ec795e731cb
Reviewed-Tip: 2da48669e3c14d87143787502d17cd191848cc95
Verdict: RESIDUALS — NOT MERGE-READY

# Step-7 re-check round 6

The native-endpoint repair works for all four requested counts, through TWO parsed PHP
receptions with the first result live. The float-count disposition and the two count tests'
assertions are accepted. However, the doubling probe creates a new admission endpoint:
an admitted count immediately below that endpoint becomes ForeignException on its next
ordinary reception. This is the same identity defect as re-check 5, at a different threshold.
One P2 residual remains.

Source, tests, fixtures, configuration and git history were reviewed without modification.
Only this requested findings artifact was written.

## P2 — doubling creates another endpoint that ordinary acquisition crosses

Location: src/cljc/yang/php/antlr/prelude.cljc:2956-2958, specifically the doubling predicate
at :2958. Consumer: php.rt/receive-condition at :2995-2996.
Coverage gap: test/yang/call/php_exception_test.clj:628-631 and :711 cover native endpoints
and an already-over-limit count, but not the new admission boundary.

Let B be the composition's ::integer/max-bits, and T = 2^(B-1). For exact integer counts,
the doubling test admits |rc| < T. It therefore admits rc = T-1:

- rc+1 = T is an integer under the module limit (B bits).
- rc+rc = 2^B-2 is also an integer under that limit (B bits).
- Reception and ordinary PHP ownership produce rc = T.
- At the next reception, rc+1 still succeeds, and rc-1 still succeeds.
- But rc+rc = 2^B now returns ::integer/bit-limit, so obj-val? rejects the same local
  Throwable and receive-condition wraps it as ForeignException.

The failed doubling is a computation introduced solely by classification. No ownership
operation in this reproduction needs that doubled value. This is not genuinely unsupported
count state: the runtime just produced it from an admitted count, and both ownership
operations still work.

### Firsthand reproduction at the reviewed tip

The actual PHP harness installs B=100000. Construct this payload in the guest:

```clojure
(assoc {} :php/obj
       (cell/new
         (assoc {:class "Exception"}
                :rc (integer/sub (integer/shift-left 1 99999) 1))))
```

Every operation constructing that count is an admitted integer-module operation and stays
inside its configured limit. No raw-host arithmetic or over-limit intermediate is needed.

Supply a thrown condition containing the payload through a callable's actual
:yang.call/raise exit. Invoke this parsed/lowered source receiver twice, retaining the
first returned value in a guest binding until after the second call:

```php
function receive($f) {
    try { return $f(); }
    catch (Throwable $e) { return $e; }
}
```

No count edits, guest numeric operations, or condition changes occur between receptions.
Observed through current worktree source, production host registry and the JVM AST walker,
with normal completion and process exit 0:

| Initial count | Initially admitted | First identity / class | Admitted after first return | Second identity / class |
| --- | --- | --- | --- | --- |
| 1 | true | true / Exception | true | true / Exception |
| 9223372036854775806 | true | true / Exception | true | true / Exception |
| 9223372036854775807 | true | true / Exception | true | true / Exception |
| -9223372036854775808 | true | true / Exception | true | true / Exception |
| 9007199254740992 | true | true / Exception | true | true / Exception |
| 2^99999-1 | true | true / Exception | **false** | **false / ForeignException** |
| 2^99999 | false | false / ForeignException | false | false / ForeignException |
| 2^100000 | false | false / ForeignException | false | false / ForeignException |

For the failing T-1 row, the count after the first return has 100000 magnitude bits,
and BOTH integer/add(count,1) and integer/sub(count,1) return exact integers.
The second result retains the complete original condition, including
{:complete [:evidence]}, and the original payload. Retention works; classification
and identity are wrong.

An independent integer-module diagnostic at the profile floor B=4096 likewise observed:

```clojure
{:before-double-integer true
 :after-double :yin.vm.integer/bit-limit
 :after-increment-integer true
 :after-decrement-integer true}
```

### Ruling on probe soundness

The probe is sound as an immediate arithmetic safety margin: for B>=4096, passing doubling
places |rc| strictly below T, so subsequent individual +1 and -1 operations have ample room
before the ACTUAL arithmetic limit 2^B-1. I found no exact-integer carrier bypass that passes
both probes and immediately installs ::bit-limit during an ordinary acquisition/release.
The preserved exact domain predicate still excludes ratios and float content before
arithmetic, and both probes are pure.

That does NOT make the admission policy stable under ordinary ownership. The comments'
huge number of acquisitions concerns reaching the arithmetic limit, while reaching the
predicate's own exclusion needs ONE acquisition from the legally constructed T-1 count.
Thus "crossing the limit unreachable" does not establish local identity, and "No count the
repaired arithmetic itself produces is ever refused" is false. Nor is "every still-operable
count is admitted" true: T is directly constructible by integer/shift-left and supports
both ownership operations, but is rejected.

Required completion:

- Preserve the native-endpoint repair, integer/integer?, and exact integer/add/sub.
- Repair identity-stable reception across ordinary ownership changes, including T-1 to T.
  Address the ownership limit policy consistently; merely moving a finite admission cutoff
  or probing one extra acquisition reproduces the same issue at the new cutoff.
- Add this T-1 two-reception case to the source regression, asserting original identity and
  Exception class on both results with the first result live.
- Keep the truly over-limit full-retention control, and correct the stronger lifetime and
  operability claims in the source comments and engineering rationale.
- Do not apply a bare deletion that lets ::bit-limit become stored :rc, and do not introduce
  a broad host catch.

This follows the same §8.10.7 rule 1 / owner v2 R2 ruling as re-check 5. The counterexample
is an actual runtime-produced state from a count this implementation itself admitted,
not a demand to recognize arbitrary corrupted state.

## Ruling 2 — float-count choice accepted

data/float64 1 preserves float content: a JVM/Dart double and a Float64 carrier on JS.
dao.host.integer/exact-integer? rejects those representations on all three hosts.
That is the appropriate portable foreign-count pin.

data/float-value 1 deliberately unwraps to a host arithmetic number: a Double on JVM,
a double on Dart, but a bare Number on JS. The JS exact-integer predicate admits safe
integral Numbers and cannot distinguish that value from integer 1. Omitting a uniform
foreign-disposition assertion for this host-divergent operation is accepted. This removes
no existing committed pin: float-count is additive in this range. The JVM-only diagnostic
in re-check 5 did not establish a portable rejection requirement for float-value.

Sources inspected: yin/vm/data.cljc:431-453, dao/jing/cbor.cljc:212-248,
dao/host/integer.cljc:66-82.

## Ruling 3 — count-test assertions accepted, boundary coverage incomplete

php-count-acquisition-test (:611-685):

- Four counts, six boolean observations per count, 24 expected keys per backend.
- Each shape uses the SAME injected callable and condition twice. The first value is
  referenced in the final observation after the second reception, so it remains live.
- Both return routes, both original handle identities, and both exact Exception classes
  are asserted. The PHP-side get_class comparison distinguishes Exception from a generated
  ForeignException before rendering; equality compares the original cell-bearing handle.
- The class-observation calls use ordinary PHP argument acquisition/release. Nothing in
  the observation manually resets the count between receptions.
- The new class checks strengthen the old route/identity checks. A local delivery does not
  retain a foreign condition, so absence of a condition-retention comparison on these
  LOCAL cases is appropriate.
- Missing coverage is the probe's own endpoint above, not an incorrect expected value.

php-count-limit-test (:688-741):

- Its actual construction uses raw host + on TWO BigInt values seeded by
  integer/shift-left: 2^99999 + 2^99999 = 2^100000. It does not use raw host *.
  Both seeds fit the harness's explicit 100000-bit limit; their sum exceeds it.
  This avoids the Long-overflow problem previously identified for unseeded raw arithmetic.
- The conformance registry uses the TypeScript linked harness's integer limits, explicitly
  100000 bits. The count's lane-specific width is documented.
- The test asserts a returned wrapper, PHP ForeignException classification, complete
  retained-condition equality including evidence, and original retained-payload identity
  through a later activation. All three backend keys are required.
- These expectations are correct. The test exercises the first increment probe's rejection;
  it cannot establish the doubling predicate's stability or necessity.
- My parsed-source over-limit control independently observed ForeignException and full
  condition/payload retention. The linked three-backend test results are supplied gate
  evidence, not a rerun claimed here.

The 2da48669 repair adds exactly the missing closer ending php-count-acquisition-test.
A Clojure reader check at the tip confirms both count tests are separate top-level deftests.

## Ruling 4 — pins and scope

Exactly three files change in 4a246994..2da48669: the PHP prelude, PHP exception test, and
r11 engineering findings. The only executable prelude change in this range is the count
admission expression; the addref hunk changes comments. addref/delref arithmetic bodies,
integer module, lowerer, finalize!, host-names, PHP exports, aliases, receiver/mint privacy
and reserved provenance doors are unchanged.

The two endpoint payload constructions move from foreign-disposition coverage to LOCAL
coverage under the explicit re-check-5 ruling; they are not deleted. Near-max and 2^53
cases remain and gain the second reception and class assertions. The eleven-shape foreign
matrix retains malformed, competing-marker, bad-class, absent/non-number and ratio cases,
and adds float content. Its four full-retention observations remain (44 keys/backend).
Other fixtures, shared kit expectations, nil pins and directed legs are unchanged.
No unrelated pin is weakened or scope widened.

## Evidence and gate disposition

Firsthand this round:

- Full requested diff, architecture rules, §8.10.7, owner ruling v2, re-check 5 and r11
  rationale inspected, plus integer, host-carrier, data/float, ownership and lowering paths.
- Parsed/lowered source diagnostic above: eight count controls, normal VM completion,
  process exit 0. No source parsing diagnostics.
- Independent profile-floor integer diagnostic: headroom transition reproduced at B=4096.
- Static retention-surface/adoption/mint-reachability pins: 3 tests, 14 passes, 0F/0E.
- Clojure reader verifies separate top-level count tests.
- git diff --check 4a246994..2da48669 passed; tracked working-tree status stayed clean.

Diagnostics used current worktree source/test roots first and only existing cached jar
dependencies and generated parser class directories from the phpexc checkout. The PHP
manifest.edn and PhpParser.g4 were checked byte-identical to that checkout. No builds,
dependency resolution, parser generation or test-cache writes were performed. Shell
launchers emitted read-only temporary-cache warnings, but the reported JVM diagnostics
completed successfully. A preliminary diagnostic launch used a nonexistent Python path;
the successful results above come from the corrected invocation.

The dispatch's focused jupiter tip evidence (15 selected pins, three disclosed lane-block
failures only; family 58 tests/725 assertions green; kondo/cljstyle clean) is accepted AS
SUPPLIED. Those tests do not include T-1. I did not rerun the linked batches, full directed
matrix, Node/Dart boundary harnesses, contract acceptance kit or foundation gate.
The three :php/local-reception :payload-identical lane-blocked failures retain their prior
disposition. Existing nil coverage notes, context/suspension lifetime obligations and
steward-owned remote/foundation gates remain as previously recorded.

**RESIDUALS — NOT MERGE-READY.** The requested native-endpoint scenarios are repaired.
The new doubling endpoint must also preserve local class and identity after ordinary
acquisition before this slice closes.
