Created-GMT: 2026-09-09 14:38:16 GMT
Created-Local: 2026-09-09 21:38:16 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 7762fd3e-1c6b-4e95-9e2d-2032a2209d5f (resumed)
# Task: revise the dao.space.schema v2 migration plan — round 2
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-09 21:38:16 +0700 | Status: active | Rationale: author of the r1 plan; resumed in its own session

Both reviews are in. **Revise your plan in place** at
`collab/1788962937302-architect-space-schema-v2-plan.claude-fable-5-1.findings.md`
(rewrite the file; do not append a changelog). Write no other file.

- Routine, `gpt-6-astra`: `collab/1788963872892-review-space-schema-v2-plan.gpt-6-astra.findings.md`
- Adversarial, `deepseek-v4-pro`: `collab/1788963872892-adversarial-space-schema-v2-plan.deepseek-v4-pro.findings.md`

They ran in parallel without coordinating and **converged on the same P1**,
by different routes. I verified every finding below against the tree myself
before sending them to you; treat them as established, not as claims.

## Blocking

1. **D4's completeness policy is unenforceable as written.** `query/snapshot`
   (`query.cljc:293`) mints a *fresh* `:dao.stream/oldest` cursor, so a ring
   buffer that overflowed **before** the call answers `:blocked`/`:ended`
   over its retained suffix — never `:gap`. V13(a) therefore cannot be
   written as specified, **and the silent degradation D4 exists to prevent
   stays open**: `schema/current` would accept a suffix that lost the
   bootstrap vocabulary and fall back to pass-through with card-one collapse
   disabled. `dao.stream.md:496-503` states this directly ("Retained history
   is not complete history, and no anchor can make it so… A consumer that
   requires complete history gets it from the transport's declared retention,
   never from an anchor"), and `query_test`'s `snapshot-of-a-gap-is-data`
   (`:359`) carries a comment saying exactly why it needs a scripted reader
   rather than a ring buffer.

   This is the plan's central open question now, and it is yours to settle,
   not to paper over. The contract offers two instruments — a
   **complete-retention transport** declared at creation (`dao.stream.memory-log`,
   `ba90b3a`) and a **kept origin cursor** minted before the first append —
   and `query/snapshot` provides neither. Decide what `schema/current`
   actually guarantees and what it demands of its caller. Rejecting a
   reported `:gap`/`:defect` is still right; it just detects observed read
   failure, not prior prefix loss, and the plan must say so in those words.
   If the honest answer is that schema cannot detect earlier eviction and
   states the limit instead, say that — a documented limit beats a guarantee
   that does not hold.

2. **D1's ordering claim is false.** `tx/transact!`
   (`transactor.cljc:241-243`) throws on empty `tx-data` **before** taking
   the lock and before its closed check. "Exactly as `tx/transact!` does" is
   wrong, and `closed-check-precedes-argument-validation` would pin behavior
   the inner value does not have. Adopt it as a schema-specific precedence
   rule with its own reason, or align with the transactor — and if you would
   change the transactor, name that as added scope rather than retiring T20
   as though the orderings already matched. "Every argument defect still
   throws" needs the qualifier "when the wrapper is open."

## Must fix before an implementer is briefed

3. **V11's property leaves the suite.** "`schema/current` never closes a
   borrowed source" is deleted, and V14 does not pin it: every V14 assertion
   still holds if `schema/current` closed the opened index early, because
   `close-published!` is idempotent (`query.cljc:261-268`) and the returned
   fact-relation is store-independent. The `RecordingStream` close-count atom
   was the only seam that could observe it, and D9 deletes it. Of the seven
   deletions this is the one property with no surviving pin. Either keep an
   ownership counter (a `reify`d v2 handle in `schema_test`, not a revived
   fixture namespace) or drop the claim and mark V14 for what it proves.
   Same shape, lower severity: `close-is-idempotent-and-closes-the-inner-value`
   asserts nothing that would fail if `schema/close!` never called
   `tx/close!`. Assert on the inner value directly.

4. **The arithmetic does not close.** Phase 1 deletes exactly two
   `ds/`-bearing lines from `schema.cljc` — the protocol declaration at
   `:946` and `ds/closed?` at `:1079` — because the `deftype`'s method names
   are unqualified. So **16 → 14**, not 11. The test file's 68 → 6 is right,
   but the require and require-macros lines contain no `ds/` and are not
   count contributors. T19's pin needs **both** `:1062` and `:1071` edited,
   not `:1071` alone (I checked; `:1062` asserts `(:result …)`). Two of the
   24 receipt lines vanish with the deleted race test, leaving 22 rewritten.
   And the deletion count is five deftests plus one rename plus one removed
   assertion, not seven. Recompute every number in both Prove lists from
   your own build/delete lists; an implementer self-checks against these.

5. **Phase 2's closure grep is impossible.** `grep -n "dao.stream\|dao.jing"`
   over `schema.cljc` can never return nothing: `:dao.stream/outcome`,
   `:dao.stream/ok` and `:dao.stream/closed` remain as keywords in the
   receipt vocabulary. Target require forms, and keep the `ds/` zero check.

6. **Phase 2's `finally` scoping won't compile** in W38/W39/W41 as written:
   `opened` is bound in an inner `let` and is out of scope in the existing
   outer `finally`. Specify the nesting.

## Confirmed sound by both reviewers — do not relitigate

**D7** (the guard and lock deletion): deepseek verified there is no
use-after-close — `tx/close!` sets only its own flag, the pool is
caller-owned — and astra agreed the behavior change is deliberate and
recorded. **The D10 receipt collapse**: no caller-visible dependency on
`{:result :ok …}` beyond the assertion lines. **T19 survives the
`deftype`→map rewrite**: the install stays atomic under the same lock.
**D5's deleted properties** survive on the query/index side; astra asks you
to cite `index_test`'s `published-index-constructor-validates-its-arguments`
(`:592`) as the pin rather than the constructor's implementation.

## Output

Rewrite the plan file in place, keeping its structure. Keep decision numbering
stable so the review record still reads against it; where a decision changes,
say what it now rules and why, not what it used to say. If a review finding is
wrong, say so with your reason — you corrected my brief on five points and
were right on all five; the same standard applies here. Start the file with
the same Completed-* / Coding-Agent / Session-ID header block, updated.
