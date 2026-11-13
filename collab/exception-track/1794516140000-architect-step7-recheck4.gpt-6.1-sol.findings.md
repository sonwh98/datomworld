Created-GMT: 2026-11-12 20:50:31 GMT
Created-Local: 2026-11-13 03:50:31 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: codex (gpt-6.1-sol)
Session-ID: unavailable in this environment
Role: Architect, read-only step-7 re-check round 4
Reviewed-Base: b5da2eb8354c2468dbf08d439b79932dde50dc52
Reviewed-Tip: 265c4a4590259f2ea592cb81cd6991aa7b8afde8
Reviewed-Commits: 7d629976, 265c4a45
Verdict: RESIDUALS — NOT MERGE-READY

# Step-7 re-check round 4

r8 closes the nil/non-map reception choice correctly. r7 closes the exact native
Long maximum/minimum cases and preserves the previous representation closures, but
does not establish safe count consumption. Two P2 residuals remain: the admitted
count arithmetic is not safe across the actual consumption path/hosts, and the
new integer guard does not establish integer/compare's argument domain.

Only this requested findings artifact was written. Source, tests, fixtures,
configuration, dependency caches and git history were left unchanged.

## P2 residual 1 — the range check does not make the consumed arithmetic safe

Locations: src/cljc/yang/php/antlr/prelude.cljc:2893-2895, :2934.
Consumers: php/addref at :1841; lower-return at
src/cljc/yang/php/antlr/lower.cljc:2945.
Host support: src/cljc/dao/host/integer.cljc:14-25, :175-186;
src/cljc/yin/vm.cljc:404.

### JVM: the next ordinary acquisition overflows an admitted count

Use the same legal guest construction as the round-3 counterexample, one below
the old failing boundary:

```clojure
(assoc {} :php/obj
       (cell/new
         (assoc {:class "Exception"}
                :rc (integer/parse "9223372036854775806"))))
```

This passes both strict comparisons and Throwable classification. Reception's
addref changes the count to 9223372036854775807. Now ordinary PHP source, with no
requested numeric operation, fails:

```php
function receive($f) {
    try { return $f(); }
    catch (Throwable $e) { return $e; }
}
```

The synthetic callable delivers the thrown condition through its supplied
:yang.call/raise exit. The caught local value reaches lower-return's ordinary
php/addref, which executes another raw + and overflows. The first acquisition is
safe; the consumed path is not.

Firsthand result, current worktree source, parsed/lowered receiver and JVM AST
walker, successful bounded subprocess:

```
:source-receiver 1                    :php/null
:source-receiver 9223372036854775807  :php/null
:source-receiver 9223372036854775806  :failure long overflow nil
:source-receiver 9223372036854775805  :php/null
:source-receiver -9223372036854775808 :php/null

java.lang.Math.addExact
clojure.lang.Numbers.add
clojure.lang.Numbers$LongOps.add
clojure.lang.Numbers.add
clojure.core$_PLUS_.invokeStatic
```

The normal completion marker above is the outer diagnostic php/try's success
continuation, not an assertion about the source function's returned value.
Separate direct reception controls confirmed genuine handle/map-error identity
and complete retention for both rejected Long endpoints.

The engineer's sweep stops its candidate-state reasoning at raise, then discusses
only minted-complete ForeignException wrappers during lifetime handling. This
misses the local branch: an admitted candidate is delivered as itself and undergoes
ordinary return/copy/rethrow ownership acquisitions. Moving the bound inward by
another fixed unit merely moves this problem to a later normal acquisition.

### JavaScript: a PHP-range integer can already have an incompatible carrier

The bounds are PHP's signed 64-bit bounds, not every host's native arithmetic
bounds. The prelude's own value-encoding documentation and dao.host.integer
explicitly specify a JS BigInt carrier beyond the safe Number range.

For example, integer/parse of "9007199254740992" produces a JS BigInt. It is an
integer, is strictly inside both checked bounds, and php/int? classifies it as
:int. Reception then reaches raw (+ rc 1). The VM registers the host + directly;
on JS this mixes BigInt rc with Number 1.

Firsthand Node primitive diagnostic, both signs:

```
9007199254740992  inside-signed-64-bit-bounds: true
TypeError Cannot mix BigInt and other types, use explicit conversions
-9007199254740992 inside-signed-64-bit-bounds: true
TypeError Cannot mix BigInt and other types, use explicit conversions
```

This is a native-operation reproduction plus source tracing, not a claim that
the complete Node boundary harness was run. The same decimal count completed
the JVM direct local-reception diagnostic because its JVM carrier is a Long;
that does not establish JS safety.

Required completion: match admitted count carriers and all ordinary ownership
operations to one safe arithmetic domain on supported hosts. Preserve local
identity where valid and route invalid object state through retaining
ForeignException before unsafe consumption. Do not add a broad host catch.
Add the near-maximum ordinary catch/return counterexample and a JS BigInt
counterexample, retaining full-condition/evidence and payload-identity checks.
A single-step signed-64-bit bounds proof is insufficient.

## P2 residual 2 — php/int? does not prove integer/compare's domain

Location: src/cljc/yang/php/antlr/prelude.cljc:2892-2894.
Definitions: php/kind/:int? at :137-158; checked-divide at
src/cljc/yin/vm.cljc:341-356; integer argument validation at
src/cljc/yin/vm/integer.cljc:151-158.

This legal, computed guest value fails during admission:

```clojure
(assoc {} :php/obj
       (cell/new (assoc {:class "Exception"} :rc (/ 1 2))))
```

The VM's / is checked clojure.core division on the JVM and produces the Ratio
1/2. data/number? accepts it. php/int? asks php/kind whether this is float64
content; a rational is not float64, so that numeric fallthrough answers :int.
The first integer/compare then refuses because a Ratio is not an exact-integer
carrier. The caller requested division only to construct a foreign payload;
reception introduced the invalid integer comparison.

Firsthand current-tip AST-walker reception through the synthetic supplied raise
exit, php/call and php/try:

```
:ratio :failure integer primitive refused
#:yin.vm.integer{:op compare, :reason :wrong-type,
                 :arg 0, :expected :integer}

yin.vm.integer$refuse_BANG_
yin.vm.integer$wrong_type_BANG_
yin.vm.integer$big_BANG_
yin.vm.integer$int_compare
```

No local ForeignException or retained condition was delivered. Float64 and bare
Double controls were rejected cleanly with complete retention; an integral
division yielding the native integer 2 remained local.

Required completion: establish the actual integer primitive argument domain
before calling integer/compare, using an exact-integer predicate or equivalent
total validation. php/int? is a PHP representation classifier, not that predicate.
Pin the computed rational count as foreign, with complete condition/evidence and
original payload identity. Keep the missing/non-number, float, boundary and
legitimate local controls. This is a new refusal introduced by the r7 validation
path, not an operational failure explicitly requested by the foreign callable.

## r8 ruling — correct shape, correct placement, no new mint surface

The data/map? test at :2930 dominates the entire reason classification and every
path to raise-foreign/raise-protocol and mint-retained. nil and every other
non-map go directly to php/throw-error "TypeError"; throw-error/make-error use
ordinary allocation and never the provenance mint.

This implements the steward addendum's deliberate option (a), consistent with
the accepted Python F1 implementation visible in this range:
py/dsg__condition-exception shape-checks before dsg__new-provenance and otherwise
makes a fresh TypeError. PHP places the check at its private receiver; that
placement is sufficient because all its reception mint paths lie below it.
Map-shaped conditions retain their existing classification and complete
provenance behavior.

Firsthand current-tip direct reception results:

| Condition | Local class | Provenance |
| --- | --- | --- |
| nil | TypeError | nil |
| :garbage | TypeError | nil |
| 42 | TypeError | nil |
| false | TypeError | nil |
| [] | TypeError | nil |
| "garbage" | TypeError | nil |
| {} | Error | {} |

The empty-map control shows that map-shaped protocol conditions still mint and
retain the original condition, while malformed shapes do not.

Coverage note, not a demonstrated runtime defect: the committed nil pin only
checks successful retain and is_foreign == false. It does not independently
assert TypeError, absence of provenance, or any non-nil non-map case. A different
ordinary Throwable could satisfy it. Extend it with the Python precedent's
non-map case and exact class/provenance observations. The runtime behavior itself
is correct in the inspected code and direct diagnostic.

## Pins, representation sweep and surfaces

No prior pin was weakened. The eleven-shape test keeps all four observations per
shape (retain, ForeignException, complete condition with evidence, payload
identity), with an exact 44-key expected map for each of semantic/stack/register.
The three added count shapes are evaluated guest expressions, not inert data.
Adding :rc 1 to authentic-cell-bad-class strengthens that class check by removing
the missing-count masking noted in re-check3. The old "all eight" comment is
stale; the actual test has eleven shapes.

r7 adds three surface assertions; it removes none. r8 changes only the receiver
and its comment. No export, alias, reserved door, arbitrary-condition mint helper,
host catch, shared-kit expectation change or fixture weakening was added.
The receiver and mint remain php.rt/private. Firsthand static pins:
3 tests, 14 passing assertions, 0 failures, 0 errors.

The representation alignment still holds: non-nil competing :php/ref/:php/arr
markers are rejected; cell-of selects the same authenticated :php/obj; the class
is string-checked before instance-of/lowercasing; released?'s field comparison
is total; marker-free map errors have no ownership cell. The confirmed remaining
holes are count-domain validation and downstream arithmetic, not another marker
selector or new provenance surface. No additional immediate field-read defect
was established by this review.

## Range accounting and verification limits

The literal b5da2eb8..265c4a45 tree diff is NOT confined to two commits: it spans
168 changed paths because the PHP history was rebased over newer master work.
b5da2eb8 is not an ancestor of this tip; their merge-base is
0e666a3fdfe008cc0dc3dcb5f2321f8d50487e88.
The rebased r6 parent 280f920a has exactly the same PHP prelude and PHP exception
test bytes as b5da2eb8 (the path-limited diff is empty). Thus the complete PHP
source/test delta in the requested range is precisely r7+r8, reviewed above.

The wider range also includes Python F1/F2, lowering-provenance work, shell/REPL
extraction, space budgets and their tests/logs. I inspected its inventory, the
Python precedent and relevant VM/linker/arithmetic context; this PHP step-7
re-check does not certify all those unrelated subsystems or replace their
existing reviews/foundation gates.

- Architecture/build guidance, re-check3, r7/r8 findings, steward nil-guard
  addendum, both actual commit patches, and all PHP changes in the requested
  tree range were read.
- Direct JVM diagnostics ran current-worktree sources over the bundled prelude
  and production host registry. All reported completed diagnostic runs had
  exit 0; individual host failures were captured outside the VM for reporting,
  not converted into guest errors.
- The source receiver was parsed/lowered from the PHP above. This review
  worktree has no generated parser classes. Read-only classpath inputs used
  the existing phpexc checkout's generated PHP/Python/TypeScript parser classes;
  the PHP grammar manifest and parser grammar matched byte-for-byte. No source
  namespaces or mutable build/cache outputs from that checkout were shared.
- The three committed static pins completed green as reported. The complete
  linked eleven-shape/nil tests and engineer red-first chronology were not
  independently rerun/reconstructed; their linked green counts remain engineer
  evidence, not additional firsthand green claims here.
- Node ran the actual problematic native addition with canonical BigInt
  operands. No complete Node/Dart boundary harness, contract test, full suite,
  remote lane or foundation merge gate was run.
- PHP/r7/r8 path-limited git diff --check passed. The literal whole-range
  diff --check does NOT pass: inherited collab logs/findings and
  test/yang/clojure_callsite_test.cljc include whitespace/EOF findings.
  Those are not attributed to r7/r8 and were not changed.

Steward-owned lane/instrument reds, suspension/context-lifetime obligations,
full pair matrix and remote/foundation gates retain their prior disposition.
The residual verdict here is supported by the current-tip failures above,
independently of those carry-overs.

**RESIDUALS — NOT MERGE-READY.** r8's nil/non-map choice is closed; r7's exact
endpoint repairs are real, but the required safe count-consumption closure is not.
