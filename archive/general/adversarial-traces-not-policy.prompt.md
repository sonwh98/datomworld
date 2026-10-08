Created-GMT: 2026-09-07 12:23:54 GMT
Created-Local: 2026-09-07 19:23:54 +07 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 50d48a71-9ff9-44b7-8dc0-b334e5f42aac
# Task: review the traces-not-policy proposal, which amends an axiom
Role: Adversarial Code Reviewer and Security Auditor
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-07 19:23:54 +07 | Status: active | Rationale: resumed session; it established the cursor law this proposal builds on and reviewed the P0 commit the proposal would amend

Read the proposal in full:
`collab/architect-traces-not-policy.claude-fable-5-1.findings.md`.

**This one changes `datom.world.md`.** It proposes a seventh non-negotiable
invariant and a sentence in *Host Boundaries*, plus keyword-level changes in
five namespaces and a correction to `b2bf609`, the P0 commit you passed. An
axiom-level change deserves the hardest reading you have given anything today.

## How it arose

The user's framing, in their words: interpreters observing streams *is* the
core philosophy; interpreters do not differ in how they observe; errors are
"just data traces in an environment that can be consumed and interpreted with
different semantics — an error might trigger an email or an sms or a
firealarm". I measured that four of six interpreters route an unrecognized
outcome into a catch-all that classifies it as `transport-error`, and proposed
adding four declaration-driven tests. The user said the fix is structural, not
test-based. The architect agreed and went further: the core should *deposit a
trace and interpret nothing*, and the four tests become unnecessary because
the step is the only place a new contract outcome arrives.

## What to judge

1. **The two-case distinction, which is the proposal's load-bearing claim.**
   An unrecognized *operation answer* (contract broken, no successor) → trace
   and stop, the cursor law satisfied vacuously because no element was
   observed. An unrecognized *value* (conforming `ok`, payload the interpreter
   cannot use) → the effect classifies it, answers ok, and the cursor
   advances. Is that split real and exhaustive, or is there a third case it
   misses? Check it against `rpc/poll-read`'s decode path, which the proposal
   claims already does the second correctly.
2. **"The core deposits nothing itself."** The trace rides out in the return
   value and the composition deposits. Is that right, or does it just move the
   problem — every caller now holds a trace it may drop on the floor, with
   nothing requiring it to carry it anywhere?
3. **Is `transport-error` actually wrong today?** The proposal's central
   accusation is that folding an unrecognized answer into `transport-error`
   *invents a meaning* — the transport "did not fail to perform a read; it
   performed one and reported it in a vocabulary this interpreter does not
   speak." Is that distinction real in the contract's own terms
   (`dao.stream.md`, Reading), or is `transport-error` a legitimate catch-all
   for a non-conforming transport?
4. **The eight-valued status set.** The proposal argues against itself here:
   the step would have eight statuses against a seven-outcome contract, and
   `:unrecognized` "names the absence of a contract outcome, not a new one."
   Is that sound, or is the step now inventing vocabulary — the thing it
   accuses the catch-alls of?
5. **The diagnostics-vector claim.** It says `rpc`/`apply`'s `:diagnostics`
   vectors are ADR-0003's debt "in a worse shape" — a private ingress that is
   not even a stream, with no retention and one reader. Fair, or overstated?
6. **The proposed invariant**, in `datom.world.md`'s register:
   > Do not interpret what you cannot interpret: an interpreter that meets an
   > answer or a value outside its vocabulary leaves it in the medium as a
   > trace, exactly as it came, and takes no action on its behalf. What the
   > trace means belongs to whoever reads it.
   Does it belong beside the six? Is it non-negotiable, or is it a strong
   default with legitimate exceptions — and if the latter, it does not belong
   there. Does it contradict any existing invariant or axiom?
7. **The cost accounting.** Five namespaces, one keyword each; `observe_test`'s
   nine malformed cases change expected status; `rpc`/`apply` tests change one
   keyword; the REPL adapter remaps one reason. Is that complete, or does it
   miss a consumer? Does correcting `b2bf609` days after landing it create a
   problem the proposal does not name?

If the proposal is right, say so and say what should land first. If the
invariant is right but the implementation is not, separate them. If the whole
thing is over-reach dressed in the system's own vocabulary, say that plainly —
the user is entitled to that answer, and an axiom accepted on enthusiasm is
worse than no axiom.

Do not edit any file. You have no authority to run tests. Produce the complete
response in this run.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: 50d48a71-9ff9-44b7-8dc0-b334e5f42aac
