Created-GMT: 2026-11-12 03:25:32 GMT
Created-Local: 2026-11-12 10:25:32 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: unavailable in this environment (original review reference: 01a1cd5a-78dc-7fc1-9f1b-9aa4b77e6e18)
Role: Architect, read-only step-7 re-check
Reviewed-Base: 4939a3ae
Reviewed-Tip: 4f262ffa
Verdict: FIX-LIST — NOT MERGE-READY; P1 CLOSED, P2 PARTIALLY REPAIRED

# PHP r4 step-7 re-check

The exported transitive mint is closed. The three reported malformed-payload cases are
repaired, and legitimate local representations retain identity. However, P2 remains open:
the new predicate validates a different representation branch from the one subsequently
consumed by instance-of, and cell authenticity alone does not validate a cell's contents.
Two bounded firsthand diagnostics still produce operational failures during reception.

Only this findings artifact was written. Source, tests, fixtures, git history, and runtime
configuration were left unchanged.

## Item 1 — P1: CLOSED

The receiver is now `php.rt/receive-condition`
(src/cljc/yang/php/antlr/prelude.cljc:2860), with all three call-helper references updated
at :2891, :2894, and :4906. No remaining `php/receive-condition` definition or alias exists
in the PHP implementation.

The actual export rule takes exactly namespace `php`
(src/cljc/yang/core/prelude.cljc:71; PHP prelude :6130).
The strip rule leaves `php.rt/*` qualified in definition keys and references
(core/prelude.cljc:20 and :29), preserving the existing bundled/linked private-name
convention. Firsthand namespace inspection printed `:receiver-exported false`.

Manual inspection of the condition-to-mint path found only the private receiver,
raise-foreign, raise-protocol, and mint-retained, plus the three public call helpers whose
receiver argument is the exceptional continuation passed to yang.call invocation.
There is no other public condition-accepting intermediary or export alias. This closes
the specific transitive mint to the Python/JS adoption standard.

The extended stripped-name assertion at
test/yang/call/php_exception_test.clj:376 detects the old export directly.
The new one-hop audit (:382) is a useful regression for this exact bypass; it is not a
general transitive/dataflow proof. Its whitelist is justified by the current callback
wiring, which was inspected manually. Its restriction to core-definitions and a fixed
minter set should not be described as an exhaustive future export-graph verifier.

I ran the adoption, reachability, and retention-surface pins firsthand:
**3 tests / 11 passing assertions / 0 failures / 0 errors.**

### P3 follow-up — make the negative executed pin assert the actual outcome

The new executed test at php_exception_test.clj:407 exercises the old exported name,
but its assertion at :442 accepts every value except a map with a truthy
`:forged-forwarded`. An unrelated host exception, nil, or malformed observation passes
too. Thus the assertion itself does not establish the reported specific
“Unable to resolve symbol: php/receive-condition” outcome.

Tighten it to accept the expected resolution refusal, or an explicitly specified successful
observation proving no forgery, while rejecting unrelated failures. This is a nonblocking
test-quality follow-up for P1 because the export absence, static pins, and current code path
independently establish its closure. The engineer's red-first execution remains reported
evidence; I did not rerun that linked test.

## Item 2 — P2: PARTIALLY REPAIRED; remains a P2 blocker

The predicate itself is total for the tested inputs, but the full reception/classification
path remains unsafe. The `cell/authentic?` gate at prelude.cljc:2856 closes the original
non-reference and forged-reference cases. The string check at :2858 closes the original
typed-map `:class 42` case. Neither check covers both operations actually performed later.

### Counterexample A: typed-map validation bypasses handle authentication

Payload:

```clojure
{:php/type :obj :class "Exception" :php/obj 42}
```

The typed-map branch at :2857 accepts it because its top-level class is a string.
But `php/instance-of` selects its representation using the non-nil `:php/obj` key
(:4586), then dereferences 42 at :4588. The checked top-level class is never used.

Firsthand result through a synthetic callee's supplied raise exit and `php/call`:

```
:mixed-tags :failure Dead or forged cell reference
{:reason :dead-or-forged-reference, :effect :cell/get,
 :kind :cell-ref, :id nil}
```

This payload is plain foreign data, without any requested VM reference operation.
Reception introduces the invalid cell read. It must instead become a ForeignException
retaining the original complete condition and payload.

### Counterexample B: authentic foreign cell with invalid class state

Payload construction in guest computation:

```clojure
(assoc {} :php/obj (cell/new {:class 42}))
```

The handle branch at :2856 accepts the live, authentic cell. It never validates the class
inside that cell. Instance-of reads 42 at :4588, and `php/class-is?` passes it to
`php/lc` at :4573, causing the string primitive to refuse.

Firsthand result through the same boundary path:

```
:authentic-cell-bad-class :failure data primitive refused
#:yin.vm.data{:op str->code-points, :reason :wrong-type,
              :arg 0, :expected :string}
```

Authenticity proves the reference is live and issued by this task; it does not prove that
its value is a valid PHP object state. Creating this ordinary cell is legal guest
computation. The failure is again introduced by PHP reception, not by a caller-requested
invalid VM operation.

### Required repair and regression coverage

Use the same representation discriminator in the safe reception check and the later
object/class operations. A non-nil handle marker must not enter a map-only validation
branch and later be dereferenced unchecked. For a handle, authenticate before reading,
then safely validate the state/class actually consumed by the local object machinery.
Keep the legitimate map-error path when no handle is present.

Add both counterexamples to the boundary regressions, asserting a local ForeignException,
the complete retained condition, original reason/evidence, and original payload identity.
Preserve real handles, real map errors, and unchanged local-Throwable identity.
Do not catch host implementation/custody failures broadly or relabel them after the fact.

The new three-shape regression at php_exception_test.clj:447 is meaningful but does not
exercise mixed representation tags or an authentic cell with foreign contents.

### Verified controls and preserved behavior

The bounded diagnostics used the same synthetic entry/raise-exit shape as the kit and
delivered through `php/call`, then caught with `php/try`. This was parserless JVM
AST-walker execution, not a linked/parsed-source acceptance run.

| Payload | Firsthand result |
| --- | --- |
| `{:php/obj 42}` | ForeignException; complete condition retained |
| `{:php/obj {:type :cell-ref :id 99999 :seal "forged"}}` | ForeignException; complete condition retained |
| `{:php/type :obj :class 42 :props {}}` | ForeignException; complete condition retained |
| Mixed-tags map above | Operational cell/get refusal |
| Authentic cell with class 42 above | Operational string-primitive refusal |
| `(php/make-error-map "Exception" "map")` | Exception; received object identical to payload |
| `(php/make-error "Exception" "handle")` | Exception; received object identical to payload |

No broad host catch was introduced by r4. The correct preservation of these legitimate
representations is accepted; safe handling of arbitrary otherwise-foreign data is not
yet complete.

## Item 3 — Engineer process provenance: ACCEPTABLE, with evidence limits

The missing findings artifact was disclosed, and the r4 brief itself restated both blockers,
their exact paths, required repair, no-broad-catch constraint, and red-first requirements.
Independent reproduction from that brief is acceptable provenance for the repair.
The absent copy does not invalidate the reported red-first tests or require repeating
already established work merely for file-distribution reasons.

The original findings file is now present in this worktree. Its P2 required repair included
“safe validation of the state used for local class lookup”; the r4 brief also included that
requirement. The residual above is therefore incomplete implementation of the existing
fix-list, not a newly imposed architectural requirement.

The engineer's chronology/red-first runs are reported in its artifact and summarized stdout;
I did not independently reconstruct their execution order from this single commit.
My acceptance is of the disclosed workflow and the independently inspectable/reproduced
behavior, not certification of unseen raw gate transcripts.

## Item 4 — Scope and assertions: ACCEPT

The diff contains exactly four files: the PHP prelude, its boundary-test namespace, the r4
brief, and the r4 engineer findings. Runtime edits are confined to obj-val?, the receiver
name/comments, and the three receiver references. No other frontend, shared kit, lifecycle,
ledger, provenance primitive, or entry-bridge implementation was edited.

The test diff adds 121 lines and removes no existing assertions. The existing adoption pin
is strengthened from four to five assertions. No existing pin was weakened.
The new negative-test weakness noted above is a new coverage limitation, not a weakening
of an old assertion. `git diff --check 4939a3ae..4f262ffa` passed.

The engineer's report still says “the other 12 fixtures green” for a 53-assertion PHP batch.
The prior review's correction stands: 13 fixtures total, three lane-blocked, **10 green**.
This record correction does not change the disclosed nine batch failures.

## Verification and disposition

Firsthand verification: complete fix diff, receiver references and export semantics,
three static pins (3/11/0F/0E), bounded reception diagnostics and legitimate-identity
controls above, and clean diff whitespace. Diagnostics used existing local jars/JDK with
a 60-second subprocess bound. No full gate suite, linked regression, contract test,
slow e2e corpus, or remote acceptance lane was run by this reviewer.

The prior lifecycle instrument preference, 18 disclosed lane-blocked assertions, dao.jing
stall disclosure, suspension/context-lifetime obligations, and remote/foundation merge
gates remain steward-owned carry-overs; r4 neither repairs nor changes their disposition.

**FIX-LIST — NOT MERGE-READY. P1 is CLOSED. P2 remains open for the two reproduced
classification failures.** Repair the representation discriminator/state validation,
add the missing regression cases, and re-run the focused pins. P3 test hardening is a
separate nonblocking follow-up.

