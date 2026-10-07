Created-GMT: 2026-10-05 21:37:52 GMT
Created-Local: 2026-10-06 04:37:52 +07
Coding-Agent: claude
Session-ID: f56f11bb-ea1a-422e-8dc5-dd66b6ff7938

# Task: architect-h2-contract (round 3: rule on two review findings)

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-06 04:26:40 +07 | Status: active | Rationale: same Architect, resumed; one finding changes a contract sentence it signed

Read-only, in /Users/sto/workspace/datomworld/.claude/worktrees/head-h2 (still
UNCOMMITTED). Do not edit. The independent reviewer's second pass
(`collab/1791236110071-reviewer-head-h2-r2.gpt-6.1-sol.findings.md`; untrusted,
check the citations) closed its first three findings and found two new P2s in
`src/cljc/dao/stream/remote.cljc`. Your precision edit ("until channel loss") is
applied in `docs/design/dao.stream.remote.md` 2.4.

## Finding 1 (about line 876): the unsendable memory is unbounded

`:unsendable` on the link keeps every distinct name the writer refused with
`invalid-value` or `closed`, the whole oversized name included, with no bound: the
reviewer retained 100 entries and about one million name characters by resolving
100 distinct rejected names. The reviewer's suggested fix: bound the cache's
cardinality and the retained key size, returning the terminal refusal without
caching beyond those bounds.

Rule, with the reasoning: choose ONE and give the exact contract sentence for 2.4
(the sentence you signed says a refused name "is answered that outcome again with
no further send, until channel loss"):

- (a) keep the memory but bound it (state the bounds as contract data or as an
  implementation limit; say what a `resolve` answers beyond the bound, and whether
  the name's bytes may be retained at all, for example a digest or nothing above a
  size);
- (b) drop the memory entirely: every `resolve` of such a name attempts the send
  and returns the writer's own refusal verbatim, with no id left outstanding and no
  state kept (a caller that loops pays one refused append per call; nothing grows).
  Check this against the original finding it was added for (a permanently
  unsendable name "returns retry forever, minting another id"): is it still closed
  if the refusal is returned as terminal data each time?
- (c) something smaller you can show sound.

Prefer the least state that keeps `resolve` total, bounded and honest. Say whether
an invariant decides it (no hidden state, derive rather than persist, bounded
complexity below `dao.stream`).

## Finding 2 (about line 513): a named error can consume an identity request's probe

The widened `well-formed-answer?` admits a named error with no identity into the
identity-request branch. A reflection whose identity is `nil` is accepted by
descriptor validation; an unsolicited `{id 0, name "unasked", error not-found}`
then compares the missing identity as `nil`, consumes its probe and marks it gone.
Before H2 that answer was dropped. The reviewer's fix: require the identity KEY to
be present before consuming an answer for an identity request; test an absent
identity separately from an explicit `nil`, for both named error kinds.

Confirm the fix is right and sufficient, and say whether a contract sentence in 2.1
or 2.4 must state it (give the text), and whether a `nil` identity should be legal
for a reflection at all (if that is a pre-existing gap outside H2, say so and do
not expand the slice).

Also: the reviewer notes the JVM real-socket test finds a free port and then binds
it (a find-then-bind race that could flake under concurrent allocation) and
suggests a bounded retry on address-in-use. Say whether H2 should carry that.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: f56f11bb-ea1a-422e-8dc5-dd66b6ff7938

Then, decision first for each: finding 1 (a, b or c, with the exact replacement
sentence for 2.4 and the tests it needs), finding 2, the test race.
