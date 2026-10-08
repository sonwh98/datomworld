Created-GMT: 2026-10-06 18:10:00 GMT
Created-Local: 2026-10-06 01:10:00 +0700
Coding-Agent: glm (glm-5.3, plan review)

# Task: gate review, M-next D9 — the version-1 lift as a pure encode and the fixture regeneration (read-only; a verdict is the deliverable)
Role: Review (routine gate)

Review the uncommitted work in /Users/sto/workspace/datomworld-d9
(branch ucf-d9-v1-lift, based on master baa3796a with D8 landed). The
engineer's report:
/Users/sto/workspace/datomworld-d9/collab/1791223000000-compiler
-engineer-ucf-d9-v1-lift.findings.md (one overwritten report covering
both rounds: the opus round that hit its provider session limit
mid-edit, and the glm round that audited the partial diff and
completed it). Read it first, then `git -C
/Users/sto/workspace/datomworld-d9 diff --stat` and the diff itself.

The contract: D plan r3 section 1.8 and the D9 test contract
(1791194000000-architect-m-next-d-plan-r3.claude-fable-5-1
.findings.md, staged in that worktree's collab/), astra's finding 8
(lift purity), and — governing this slice — the architect's
header-channel ruling (1791231000000-architect-d9-header-ruling
.claude-fable-5-1.findings.md, staged): the header enters prepare as
one argument (nil = the v0 fork lift byte-for-byte; a header = v1
exclusive in dao.jing.cbor with :yin.k/exclusive added by the lift),
stored in the record as :header; prepare validates the argument's
shape and mints nothing; the lift answers :op-seq-exhausted,
:unprotected-pending (every retained put/ffi-request/link-request
entry with no op id whose target is in :yin.k/enrolled, root or
child, every export) and :yin.k/unsatisfied (an op id on an
unenrolled target); kind/header agreement at lift; the SELF-CHECK —
the lift runs checkpoint/inspect on its own bytes and address, then
the D7 reader's validate-body, before answering :ok; the served table
re-keyed by task path and resource id with the retained descriptor;
phase and parent from the machine's install entries, v1 emits both, a
transient phase refuses :incomplete-install; the fixture regeneration
commits (real lifts for bases, predicate-named mutations, hand-set op
ids via one helper commented for D11, addresses re-pinned once).

## What to attack

1. The self-check is load-bearing: does every lift path (root and
   children, both versions where applicable) actually run inspect +
   validate-body before :ok, and is any refusal returned as data with
   nothing published?
2. Purity: two encodes of one prepared record give equal bytes; the
   record round-trips the canonical codec and re-encodes identically;
   encode makes zero serve calls; the served table is keyed by task
   path + resource id and carries the descriptor.
3. The three data refusals, in the root and in a child; a transient
   install phase refuses; a first-export halt under a header is
   refused; the v0 fork path is byte-identical to before (a nil
   header gives today's bytes).
4. The WIDENED DIFF (rule on it explicitly): the ruling's permitted
   list was holder/export.cljc, handoff.cljc,
   checkpoint_fixtures.cljc, checkpoint-v1.txt, their tests, and a
   shared test-support namespace. The engineer also modified
   test/yin/vm/ucf/authority/inherited_test.cljc (74 failures + 4
   errors from the old fixture shapes), authority/front_test.cljc
   (2 failures), and test/resources/yin/vm/ucf/ledger-v1.txt (the C12
   cross-host ledger fixture, re-rendered as a derived pin). For each:
   is the adaptation a faithful consequence of the regeneration, or
   does it weaken a pinned contract? The ledger-v1.txt re-render
   deserves the hardest look — what changed in the bytes, is the
   digest line consistent with the new render, and does the
   three-lane run still prove cross-host byte-determinism?
5. The four cross-host fixes the lanes caught (float64 carriers
   v1-admitted/v0-refused on Node; a CLJD assoc-on-nil PersistentList
   breaking every v0 lift on Dart; array-map/canonical map iteration;
   Dart 1 = 1.0 num equality): are the fixes portable and
   version-scoped as claimed?
6. The engineer's own flagged items: the lift's hold refusals added
   at lift before lift-pending! (the D4/D5/D6 rulings require them);
   the prepare defect where the by-handle dedup dropped a second
   resource key; the D11 op-id obligation; the doc amendments still
   owed (UCF 7.7.4/7.8, linker-dht 14.2.2).

Verdict first: READY or NOT READY (with what must change), then
numbered findings with file:line evidence. Read-only: edit nothing,
run no suite.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
