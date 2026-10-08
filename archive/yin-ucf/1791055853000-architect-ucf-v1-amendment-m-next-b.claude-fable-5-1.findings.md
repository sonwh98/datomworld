Completed-GMT: 2026-10-03 19:43:54 GMT
Completed-Local: 2026-10-04 02:43:54 +07 (+0700)
Coding-Agent: claude (fable-5-1)
Session-ID: a22aafbc-6499-4a8f-be89-f6ebe80ae496

# Findings: M-next B, the UCF version-1 amendment for fenced custody

Role: Architect. Design and documentation only. No source, no tests, no
git writes. Two documents edited; nothing else touched.

## Verdict

The amendment is published as UCF r5 and is ready for the second
architect's adversarial review. It is NOT ready for implementation until
Q1 (below) is confirmed: the proposal in linker 14.2.2 could not work as
written for any retained write, and I resolved that with a rule the owner
or second architect has not yet seen.

## 1. Edits made

### docs/design/yin.vm.universal-continuation-format.md (+596, -0)

All edits are additions. No existing sentence was changed or removed, and
no section was renumbered.

| Where | What |
|---|---|
| Header status | One r5 paragraph: what the amendment adds, and that it lands no code and closes no blocker. |
| 7.2.1 (new, end of 7.2) | The handoff body as wire grammar (tag, kind, frames, parked, stores, cells, requires, code, installs); shape rules; the two `:yin.k/version` keys; the version gate; version 0 = fork only; version 1 custody header (`:yin.k/policy`, `:yin.k/occurrence`, `:yin.k/arbitration`, `:yin.k/origin`, `:yin.k/next-op-seq`); "no body carries an epoch". |
| 7.4.3 (appended) | Explicit park as the no-wait shape; `:park` and `:call-effect` are not wire reasons; what "name-only install" means and why it is never exported; the complete install shape (pending + `:yin.k/installs` entry: phase, parent, response, whole child body); `:yin.k/op-id` on `:put`, `:ffi-request`, `:link-request` and its carry/retry rules; required keys per pending variant as landed. |
| 7.7.5 (one sentence) | Pointer to 7.7.8 for the envelope grammar (which adds `:yin.k/epoch`). |
| 7.7.8 (new, end of 7.7) | Exact integers and the 2^52-1 bound; sequence state (assign, retain, carry, restore, regrant, exhaust); the `:yin.k/fenced-v1` envelope and what is inside vs outside program data; intent; the grant epoch binding fact `:yin.k/custody :yin.k/bound`, its validity, how a reader learns the epoch, the counter, restart, exhaustion; the admission check order. |
| 7.9 (appended) | Version-1 refusals mapped onto the existing closed `:yin.k/status` set (no new status); the closed `:yin.k/admission` dispatch key with its five values and the keys each carries; what the driver does with each. |
| 7.11.1 (new block at end) | Ten numbered clauses, each with fixtures, expected data outcome, and the stage (C, D, or E) that owes the evidence; an explicit statement that no existing row is changed and that the M4 kept-cursor evidence is not moved; what the amendment does not claim. |

### docs/design/yin.vm.linker.dht.md (+34, -27)

| Where | What |
|---|---|
| 14.2.2 | The "proposed UCF amendment" code block is replaced by pointers to UCF 7.2.1, 7.4.3, 7.7.8, 7.9, plus the two points the UCF text settled (id scope, incarnation echo). The runtime and ledger contract before and after it is unchanged. |
| 14.2.3 (last paragraph) | One clause: UCF 7.9 publishes the outcome grammar. |
| 14.3 item 2 | Marked published 2026-10-04 as r5; says both 7.4.3 shapes were absent and are amended; a document change that closes no gate. |
| 14.3 closing paragraph | Status line (A landed 80b59233, B published, C to E remain, no UCF closure claimed); note that the version raised is the body's top-level key. |

### Format check

- Every added line in both files is ASCII and at most 80 columns
  (checked on `git diff -U0` added lines with awk and grep).
- `yin.vm.linker.dht.md`: 0 lines over 80 columns, whole file.
- `yin.vm.universal-continuation-format.md`: 111 lines over 80 columns
  and 323 non-ASCII lines, ALL pre-existing (the count of long lines is
  111 before and after). The brief says both documents follow the
  80-column ASCII convention; the UCF document does not. I did not
  rewrite that text (out of scope, and it would bury the diff).

## 2. Reconciliation list

Where 14.2.2's proposal and the existing UCF text (or the landed wire)
disagreed or overlapped, and what I did.

R1. Where `:yin.k/next-op-seq` lives. 14.2.2 said "in the continuation's
`:yin.k/scheduler`"; UCF 7.6.3 also names a `:yin.k/scheduler` map. The
landed handoff body has no such map: `:yin.k/id-counter` and
`:yin.k/parked` are top-level. Resolved: top-level, beside
`:yin.k/id-counter` (7.2.1). Reason: smallest change to the landed wire;
a scheduler map holding one key beside flattened siblings is worse.

R2. Two keys named `:yin.k/version`. UCF 7.2/7.3.3 put the "envelope
version" inside `:yin.k/contract`. The landed body has a top-level
`:yin.k/version` AND the one inside the stamp, and `resume-task` compares
the stamp whole. 14.3 says code stamps do not change. Resolved: the
amendment raises the top-level body key only; the stamp stays
`{:yin.code/contract "v3" :yin.k/version 0}` (7.2.1, "Two version keys").
See Q4.

R3. UCF 7.2 envelope vs the landed handoff body. 7.2 shows
`:yin.k/type :yin.k/continuation`, one `:yin.k/frame`, `:yin.k/id`,
`:yin.k/values`, `:yin.k/scheduler`, `:yin.k/carried`. The landed wire is
`:yin.k/handoff true`, ordered `:yin.k/frames`, no id, `:yin.k/code`.
`yin.vm.ucf-revisions.md` section 6 item 1 requires the amendment to
publish the body as UCF grammar. Resolved: 7.2.1 publishes it and states
it is the wire contract where the sketch differs. 7.2 itself is untouched.

R4. The envelope's keys. UCF 7.7.5 lists envelope, incarnation, op-id,
value. 14.2.2 adds `:yin.k/epoch`. Resolved: 7.7.8 publishes five keys;
7.7.5 gets one pointer sentence. No contradiction: 7.7.5's form is elided.

R5. Admission outcomes "inside the 7.9 algebra" (brief) vs "not new UCF
statuses" (14.2.3). Resolved: 7.9 gains a second closed dispatch key,
`:yin.k/admission`; the `:yin.k/status` set gains nothing. Version-1
refusals reuse existing statuses with new kinds.

R6. Unnamed carried data. 14.2.3 says what each outcome carries but names
no keys. I named them: `:yin.k/effect-result`, `:yin.k/observed-epoch`,
`:yin.k/observed-lease`, `:yin.k/closed`, `:yin.k/recorded-intent`,
`:yin.k/observed-intent`, and `:yin.k/arbitration` for suspended. I did
not reuse `:yin.k/result`: it already means a successor address (7.7.2)
and a halt value (body), and a third meaning for one attribute is a
problem the moment outcomes are datoms.

R7. The recorded result vs 7.9's "never nest a stream outcome under a
private key". The recorded result of an append IS a DaoStream outcome
map, and it sits under `:yin.k/effect-result`. I kept it nested, because
merging its keys into the admission map would be "a DaoStream outcome map
gaining keys", which 14.2.2 forbids. 7.9's rule is about lift/lower
statuses; the text says the nested map is unchanged.

R8. The grant binding vs 7.7.2 "both custody facts are evidence, not
authority". The binding is a third `:yin.k/custody` fact
(`:yin.k/bound`), grantor-authored, and it IS authority. 7.7.2's "both"
still refers to offer and resumed; 7.7.8 says the binding differs and why.
It uses `:dao.lease/lease` and `:dao.lease/holder` as keys on a non-lease
fact; the `:yin.k/resumed` fact already does that. No lease key is added.

R9. The binding vs derive-don't-persist. The epoch looks derivable as a
count of `:dao.lease/lapsed` facts. It is not, for a reader: `:lapsed`
does not cross to a remote holder (`dao.lease.md`, Carriage), and the
count does not survive a ledger restart. 7.7.8 says so. Kept as 14.2.2
proposed.

R10. 7.7.7 "a grantor that lost its ledger reclaims and re-grants" vs
14.2.2 "loss of recoverable epoch/dedup state requires fail-stop".
Resolved in 7.7.8 without editing 7.7.7: reclaim-and-regrant presumes the
epoch survived; if it did not, no grant.

R11. UCF 7.2 says a result carries `:yin.k/occurrence` "the occurrence
that halted"; 7.7.6 says a result carries `:yin.k/origin`. Resolved for
version 1: a halted body carries `:yin.k/origin` only. A result is not a
lease subject.

R12. 7.4.1's reason enum lists `:park` and `:call-effect`; the owner
ruling (ucf-revisions section 8) and the landed code admit neither.
Resolved in 7.4.3's amendment text; the 7.4.1 code comment is left as is
and declared superseded (it is a pre-existing over-wide non-ASCII block).

R13. 7.4.3's examples vs the landed pending keys. `:ffi` on the wire has
no request/response descriptors; `:ffi-request` uses
`:yin.k/request-envelope` and `:yin.k/response-cell`, not
`:yin.k/request-args` and `:yin.k/cell`. Resolved: a required-keys list
appended to 7.4.3, taken from `validate-pending`, declared the wire
contract where the examples differ.

R14. 7.5.4 calls the non-portable kind set closed; the landed handoff
uses seven kinds not in it. Resolved: 7.2.1 lists them and the two new
ones as additions. 7.5.4 itself is untouched.

R15. Install `:waiters`. `yin.vm.linker.md` 7.3 holds a waiters list; the
landed body carries none. Resolved as derive-don't-persist: the waiters
are the frames whose `:install` pending names the child.

R16. Sequence overflow. 14.2.2 says "overflow suspends admission and
export". An admission outcome must carry an op id, and at holder-side
exhaustion no id exists. Resolved: holder-side exhaustion is not an
admission outcome; the writer assigns and appends nothing, and lift
refuses `:op-seq-exhausted`. Authority-side epoch exhaustion is
`:suspended`. The largest assignable sequence is 2^52-2 so the exhausted
counter (2^52-1) is itself representable.

## 3. Open questions, each with a recommendation

Q1 (blocking; second architect and owner). Which occurrence does a
carried op id name, and what does the consumer check it against?
A body is minted at the park that ends a run, so every pending id in a
body was assigned under a PREDECESSOR occurrence, and 14.2.3 step 6
closes that predecessor before the successor can be granted. 14.2.3 step
4 checks "open O". Read literally, every retried retained write is
refused as stale. My resolution (7.7.8 Admission, steps 3 and 4): tenure
is checked on the envelope's lease binding; the id's occurrence must be
the bound occurrence or an ancestor, with ancestry derived by query over
the authority's closed-with-successor records; and a successor must name
the same arbitration medium. The ancestry guard exists so a holder cannot
write dedup records into an unrelated occurrence's id space.
Recommendation: accept. Alternative: key ids by a chain-root id instead
of the per-park occurrence, which removes the walk but changes 7.7.5's
published `{:yin.k/occurrence O :yin.k/seq n}` meaning.

Q2 (owner). What does the authority do after `:intent-conflict`?
The holder fail-stops and publishes no successor. If the authority then
reclaims and regrants the same checkpoint, the replay conflicts again,
forever. Recommendation: the occurrence stays open and ungranted until a
governance act; do not auto-regrant after a recorded conflict. I wrote no
rule for this.

Q3 (owner). Where is consumer enrollment declared?
14.2.1 requires "declared consumer enrollment" but no document says
where. Both ends must agree on whether a stream is fenced, and 7.4.3 now
refuses a mismatch. Recommendation: a fact on the arbitration medium
keyed by stream identity, read by both ends; no new body field.

Q4 (second architect). Keep two keys named `:yin.k/version` (R2)?
Recommendation: keep; it is the landed wire and the stamp is compared
whole. Renaming the body key would be cleaner and is a clean break we can
afford, but it is a wire change to landed M-next A code.

Q5 (owner). May a version-1 body be a fork?
I wrote version 1 as exclusive only and version 0 as fork only, following
14.3's wording. Recommendation: keep until a use for a fenced fork
appears.

Q6 (second architect). A defective envelope produces no outcome at all.
That covers a malformed envelope, a wrong attributed author, and an id
outside the chain. It mirrors `dao.lease.md` ("a fact about that author")
and prevents a forger from inducing a false `:stale` for the real holder.
The cost: the brief says every refusal is a data outcome, and here the
only data is a telemetry diagnostic. Recommendation: accept; a conforming
writer cannot produce these.

Q7 (second architect, for M-next C). How is "admitted in the same
transaction as the grant" shown to a remote reader? Recommendation: datom
`t` equality served through the remote reflection, specified in
`dao.space.transactor.md`. 7.7.8 states the requirement, not the
mechanism.

Q8 (second architect). Is a terminal failure a recorded result?
I wrote: the recorded result is the effect's terminal outcome, success or
refusal, and `full` is never recorded. So `:committed` can carry a
terminal stream refusal. Recommendation: accept; otherwise a replay
could turn a refusal into a success.

Q9 (owner, small). Occurrence id form. 7.2.1 requires only plain data,
non-nil, compared by canonical bytes. Recommendation: fix the form in
M-next C.

## 4. Observations on landed code (not changed, not reopened)

O1. Likely silent drop. `export-task` includes frames whenever the wait
set is nonempty, including for kind `:parked`; `resume-task` sets
`:wait-set []` for `:parked`. By reading the code, not by running it. If
a parked task can have other waiters, they are lost on resume. 7.2.1
states frames restore for `:parked` too, and 7.11.1 clause 5 owes a test.

O2. `validate-body` does not check that an `:install` pending's name has
an entry in `:yin.k/installs`; only the lift does. 7.4.3 now requires it
at decode. Owed by M-next D.

## 5. Not done, for the orchestrator

- `yin.vm.ucf-revisions.md` section 6 still lists this amendment as
  "named but not landed". The brief limits edits to two documents, so I
  left it. It needs a record once this is signed off.
- `handoff-version` in `handoff.cljc` is still 0, correctly: no code
  changed.
- No tests were run; nothing executable changed.

# Round r6: reconciliation of the second architect's review

Round-Completed-GMT: 2026-10-03 19:59:00 GMT
Round-Completed-Local: 2026-10-04 02:59:00 +07 (+0700)
Review reconciled:
collab/1791056670000-architect-ucf-v1-amendment-review.gpt-6-astra.final.md
(ACCEPT-WITH-CHANGES).

## Verdict

Every required change is applied. I declined none. Four are applied with
a change of my own, each marked below; three of those raise a new open
question. The amendment is ready to commit as published design. It is
not implemented, and M-next C must not start on the items in "New open
questions" without reading them.

## Diff after r6 (uncommitted)

- yin.vm.universal-continuation-format.md: +785, -7. The 7 removed
  lines are pre-existing text that item 6 required narrowing: 5 lines
  of 7.2 and 2 lines of 7.9. r5 had removed nothing.
- yin.vm.linker.dht.md: +78, -30.
- yin.vm.ucf-revisions.md: +45, -5 (section 6, first bullet).
- Checks: every added line is ASCII and at most 80 columns. The UCF
  document still has its 111 pre-existing over-wide lines; the other
  two documents have none. No section renumbered. No git writes. No
  source or test touched; nothing was run.

## Item by item

1. Inherited-id scope and the successor chain. APPLIED-WITH-CHANGE.
   7.7.8 Admission step 4 now carries astra's rule verbatim in
   substance: an id assigned in the current occurrence names it; an
   inherited id must be among the retained pendings of the checkpoint
   granted to the holder, install children included, and its occurrence
   must be an ancestor through authoritative completion records;
   membership is derived from the accepted checkpoint, no ancestry or
   pending-id index is stored. A new paragraph "Completion and the
   successor chain" states the single acyclic chain (one successor per
   predecessor, one predecessor per successor, fresh occurrence,
   matching origin, unchanged arbitration identity, an orphan is not an
   edge). Occurrence-based ids are kept. My change: I added "if the
   accepted checkpoint cannot be read, the outcome is `:suspended`".
   Deriving membership means the admission resource must read the
   checkpoint body, and the rule needed a fail-closed answer for when
   it cannot. See N1.
2. One dedup namespace. APPLIED. 7.7.8 "One dedup namespace"; linker
   14.2.2's "An enrolled consumer owns durable records" now says the
   arbitration admission resource owns them, one namespace across
   targets, target identity in the intent.
3. Authenticated outcomes. APPLIED. 7.9: an outcome counts only when
   attributed to the enrolled consumer or admission authority for the
   target; correlation alone is insufficient.
4. Definitive failure vs uncertainty (Q8). APPLIED. 7.7.8 "Results and
   uncertainty": a terminal refusal is recorded only when the atomic
   boundary establishes it; an unknown-effect transport error is not a
   result, the id is retained and the write reconciled.
5. Epoch exhaustion precedence and export. APPLIED. 7.7.8 Exhaustion
   bullet uses astra's text: the bound is usable until a reclaim would
   increment it; that reclaim ends tenure and permanently exhausts the
   occurrence; admission then answers `:suspended` before tenure; no
   eligible exclusive successor. The contradictory "the lease check
   still refuses the old holder" sentence is gone. Linker 14.2.2's
   "overflow suspends admission/export" now distinguishes sequence
   from epoch exhaustion.
6. Outcome algebra. APPLIED. 7.9's opening is narrowed to lift and
   lower; admission outcomes are named the second disjoint family, not
   statuses and not `:yin.k/kind` values. 7.2's "never nests" sentence
   is scoped to direct lift/lower failures, and 7.9 says why a
   DaoStream map under `:yin.k/effect-result` is consistent. This is
   the one place existing sentences were rewritten.
7. Explicit park. APPLIED. 7.4.3: the explicitly parked activation
   waits on nothing; other carried frames are ordered waits.
8. Rollback and epoch rationale. APPLIED. 7.7.6 gains an r6 paragraph:
   a closed occurrence never regains tenure automatically; re-offer
   and compensation are governance. The "binding is not a cache"
   bullet is replaced by "Why a binding", which makes only the claim
   astra accepts: a remote holder cannot infer authoritative tenure
   from its partial view. I no longer claim lapse history fails to
   survive restart. I appended the paragraph rather than editing
   7.7.6's sentence, because that sentence is over-wide non-ASCII
   pre-existing text.
9. Version gate (Q4). APPLIED. Both `:yin.k/version` keys kept. 7.2.1
   now has astra's sentence on validating every nested body's version
   and structural role, the mixed-tree rule, the integer-kind check
   with Node named, a version-0-only reader refusing version 1, and a
   version-1 reader preserving version-0 fork semantics.
10. Install grammar. APPLIED-WITH-CHANGE. 7.2.1: the custody header is
    root-only; a child of any kind, halted included, carries no header
    key, the origin among them; validation is context-sensitive with
    the root's occurrence, origin, and counter passed down. 7.4.3:
    response verification (module identity, link id, image/manifest
    relation), restore-before-schedule with no rerun of initialization
    and no replayed link request. My change: I had to pick the allowed
    phases and the phase/kind pairing myself, since astra required
    them without naming them. I wrote `:running` or `:parked`, with
    `:parked` requiring a `:blocked` child and `:running` a `:halted`
    one. That is from reading `engine/advance-install`, not from a
    run. See N2. [Superseded in r7: the pairing was wrong and is
    removed from the documents; see Round r7, change 2.]
11. 7.11.1 stage ownership. APPLIED. All ten clauses follow astra's
    table: clause 3 split D/C/E, clause 4 split D/C, clause 5 covers
    both versions, clause 6 asserts the structured diagnostic, clause
    7 split C/D, clause 8 adds cross-target conflict and forged
    outcomes (and the unknown-effect case), clause 9 tests the bound
    while valid separately from terminal exhaustion, clause 10 adds
    the crash cuts. A closing sentence says fixtures prove grammar
    parity only.
12. Q rulings in the text.
    - Q2 quarantine: APPLIED. 7.7.8 "Quarantine", 7.9, admission
      step 1.
    - Q3 enrollment: APPLIED. 7.7.8 "Enrollment"; 7.4.3 reads it.
    - Q4: APPLIED (item 9).
    - Q5: APPLIED. 7.2.1 states both halves.
    - Q6 diagnostic: APPLIED-WITH-CHANGE. Astra asked that the
      diagnostic contract be published before C, so I published one
      in 7.7.8 "Defective envelopes": dispatch key
      `:yin.k/diagnostic`, a closed `:yin.k/defect` set of four,
      target, resolved author, and the envelope's readable fields
      nested under `:yin.k/claimed`. The shape is mine. See N3.
      [The defect set is redefined in full in r7, change 3.]
    - Q7: APPLIED. 7.7.8 binding bullets: authenticated evidence of
      the common authority transaction, a transaction identity within
      the named authority's provenance domain; bare `t` equality is
      insufficient; C implements and proves it through a reflection.
    - Q8: APPLIED (item 4).
    - Q9: APPLIED. 7.2.1 lists the occurrence-id invariants and
      delegates the form to C.
13. The two version-0 defects. APPLIED. Linker 14.3 records both as
    post-A defect fixes with version-0 tests, optionally in D, and
    says they neither reopen M-next A's evidence nor reassign the M4
    gate. 7.4.3 and 7.11.1 clause 5 point there. Corrects my r5
    wording, which had filed them as owed by version 1.
14. Revisions record. APPLIED. `yin.vm.ucf-revisions.md` section 6:
    r5 and r6 with sections touched, the reconciliation, the review,
    carrier uncommitted, stamps unchanged, C to E pending, defects
    recorded separately, published design distinguished from
    implemented support.

## New open questions

N1 (second architect; affects C). Reading the accepted checkpoint at
admission. Item 1's membership rule makes the admission resource read
the granted checkpoint's body. Two things follow that neither of us
wrote down. First, I chose `:suspended` when it cannot be read; the
alternative is to treat the id as defective. Recommendation: keep
`:suspended`, since unreadable is not the holder's fault and must not
look like forgery. Second, one occurrence may be offered as several
snapshot variants. I assumed all variants of one park carry the same
pending ids, so any accepted variant decides membership.
Recommendation: C makes the authority refuse an offer whose variant
carries a different pending-id set for a known occurrence.

N2 (for D, verify against the engine). The phase/kind pairing in
7.4.3. If a `:running` child can be exported while blocked, my pairing
refuses a valid body. Recommendation: D checks `advance-install` and
`start-install` by test before coding the rule, and corrects the text
if the engine disagrees.

N3 (second architect). The diagnostic shape in 7.7.8. Open points:
whether the closed defect set of four is right, and where the
diagnostic stream is wired. I wrote "its diagnostic stream" and left
the wiring to the composition. Recommendation: accept the shape; C
names the stream.

Still open from r5 and not changed by the review: none. Q1 to Q9 are
all ruled, and governance recovery is explicitly outside the protocol.

[N1, N2 and N3 were all ruled by astra's confirmation and are closed
in Round r7 below. N2's pairing no longer exists in any document.]

# Round r7: the second architect's confirmation of r6

Round-Completed-GMT: 2026-10-03 20:03:00 GMT
Round-Completed-Local: 2026-10-04 03:03:00 +07 (+0700)
Confirmation applied:
collab/1791057600000-architect-ucf-v1-amendment-r6-confirm.gpt-6-astra.final.md
(CONFIRM-WITH-CHANGES).

## Verdict

All four changes and the three N rulings are applied with astra's text,
adapted only to the surrounding prose. Nothing declined. No open
question remains from r5, r6, or r7. Ready to commit as published
design; implementation (C to E) is untouched and still owed.

## Changes applied

1. UCF 7.7.8, Admission step 4 (inherited-id scope). The final
   sentence is astra's replacement: for an inherited id, an accepted
   checkpoint that cannot be read and verified answers `:suspended`
   without changing tenure, quarantine, or dedup state; the checkpoint
   is selected by the authority's accepted record, never by an
   envelope-supplied location; current-occurrence ids need no lookup.
   The text also says the suspension comes after the tenure check, so
   a stale holder is still `:stale`, and that keeping the checkpoint
   readable is a composition durability duty. N1: a new paragraph
   "Snapshot variants" in 7.7.8 requires variants to preserve the
   operation baseline (root next-op-seq and the id-to-intent mapping,
   children included), has the authority refuse a conflicting
   variant, and suspends variant admission when the comparison is
   unavailable.
2. UCF 7.4.3, install entry phase. My r6 pairing is REMOVED: astra
   showed `advance-install` does not establish it, so I was wrong.
   The bullet is now astra's text: phase `:running` or `:parked`; the
   child independently satisfies the handoff grammar and quiescence
   with kind `:blocked`, `:parked`, or `:halted`; the phase records
   scheduler progress and does not substitute for validating the
   child's machine state; a runnable child refuses export; lower
   preserves both fields; other phases are not exportable. N2: the
   text says a `:running` phase with a blocked child is valid, that no
   stricter relation is introduced until a scheduler normalization
   invariant is specified and proved, and that D tests every
   combination, explicit park included.
3. UCF 7.7.8, "Defective envelopes". The defect bullet is astra's
   closed set with its full definitions (`:malformed` now covers
   unsupported dispatch or version and non-canonical intent;
   `:wrong-author` covers absent or invalid attribution). Added: the
   enrollment precision (enrollment belongs to the target boundary;
   writer authorization comes from attribution and binding; an
   unenrolled boundary cannot run the protocol or claim its
   guarantee); readers dispatch on the diagnostic key; the diagnostic
   adds nothing to lease facts or DaoStream outcome maps. N3: the
   composition supplies the diagnostic stream explicitly; failure or
   backpressure while publishing never permits the rejected effect or
   creates an admission outcome; publication failure is an explicit
   driver outcome.
4. UCF 7.7.8, "Results and uncertainty". "Nothing is recorded" is
   replaced by astra's text: unknown transport acceptance establishes
   neither commitment nor its absence; the writer retains the id and
   retries through the fenced boundary; the authority may already
   hold a committed result, which the retry must replay; an external
   effect that cannot be reconciled inside that boundary is outside
   the guarantee. Backpressure is kept distinct: there nothing was
   appended.

## Consistency sweep (grep over the three documents)

- UCF 7.11.1 clause 5: "a phase that contradicts the child's kind"
  removed; the clause now tests both phases against all three kinds,
  explicit park included.
- UCF 7.11.1 clause 6: adds absent attribution, non-canonical intent,
  and a failed diagnostic append.
- UCF 7.11.1 clause 8: "nothing recorded" removed; adds the transport
  cut before and after remote commit, the unreadable checkpoint, and
  the conflicting snapshot variant.
- UCF header: r7 status sentence.
- Linker 14.2.2 settled-points list: the "not recorded" bullet
  rewritten; a bullet added for variants and checkpoint suspension;
  14.3 item 2 mentions r7.
- Revisions section 6: r7 entry added; "r5 to r7 publish each item".
- This file: the r6 statements on the pairing, the defect set, and
  N1 to N3 are marked superseded or closed in place.
- No remaining match for the old pairing, the old defect wording, or
  "nothing is recorded" in the three documents.

## Diff after r7 (uncommitted)

- yin.vm.universal-continuation-format.md: +850, -7.
- yin.vm.linker.dht.md: +82, -30.
- yin.vm.ucf-revisions.md: +55, -5.
- Every added line ASCII and at most 80 columns (awk on the diff's
  added lines). UCF's 111 pre-existing over-wide lines unchanged; the
  other two documents have none. No renumbering, no git writes, no
  source or test touched, nothing run.
