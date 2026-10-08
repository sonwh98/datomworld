Created-GMT: 2026-09-22 21:07:32 GMT
Created-Local: 2026-09-23 04:07:32 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: 95116c74-2f68-4276-99a8-56291df8587e (resume of your prior hash-registry-plan review session)
# Task: reviewer-hash-registry-doc-confirm — confirm the committed doc closes your own findings
Role: Independent Reviewer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-23 04:07:32 +07 | Status: active | Rationale: same reviewer confirming its own findings closed, per the commit-then-review workflow; this is also the first review of the actual committed doc (the prior round reviewed gpt-5.6-sol's uncommitted plan text, not a file in the tree)

Work in /Users/sto/workspace/datomworld (your launch directory; branch
master). READ-ONLY, STATIC REVIEW. Do not edit any file, do not run
`git add`/`commit`/`push`. Do not run the test suite -- there is no
implementation yet, this is a design document.

## Context

Your prior review (collab/1790110150738-reviewer-hash-registry-plan.glm-5.3.stdout.log)
found: READY WITH CHANGES, no P1, two P2s, six P3s, against gpt-5.6-sol's
plan text captured in
collab/1790109451076-architect-hash-registry-plan.gpt-5.6-sol.stdout.log.
gpt-5.6-sol was then resumed to fold your findings in
(collab/1790110998998-architect-hash-registry-plan-fold-fixes.gpt-5.6-sol.stdout.log
has its full response). The orchestrator wrote the folded result verbatim
into docs/design/dao.jing.hash-registry.md and committed it as `5de9e7b1`
("docs(dao.jing): promote the hash-agile content-addressing design"). This
is now the actual, permanent artifact -- read it directly with
`git show 5de9e7b1` or by reading the file at its current path.

## What to check

1. **P2-1 (address-directed mint sites)**: does the committed doc's
   "Address-directed mint sites" section correctly name
   `dao.jing.dht/make-get`, `dao.data.btree.storage/hydrate!` (sync
   `pull!`), and `dao.data.btree.storage/hydrate-async` (`fetched!`) as
   mint sites requiring explicit-arity `materialize!` with the source
   address's encoding/algorithm -- not left in the plain
   `segment-matches?` validation list where your review found them
   misclassified?
2. **P2-2 (dao.jing.cbor acknowledgment)**: does the committed doc's
   "Sequencing with canonical CBOR" section acknowledge the existing
   `dao.jing.cbor` codec and frozen `cbor-v1` corpus, use it as the real
   H2/H4 second-profile test rather than a fabricated one (or explicitly
   justify not doing so), and does H5 state the future `cbor-v1` profile
   is expected to use that exact codec/corpus, with the print-v1-vs-cbor-v1
   value-domain caveat stated?
3. **The six P3s**: verify each is actually folded in --
   `validate-codec-round-trip!` correctly described as not an
   address/payload check; a regression-guard/lint item present in H0/H2;
   H2's completion criteria referencing the full site list (macro,
   semantic, completion, ledger, yin.vm row validation) not a subset;
   the portability spike's fixtures named as the artifact of record in
   H0/H1; `dimension-hash` correctly described as a `def`; the
   hyphen-in-encoding-id parsing caveat stated explicitly.
4. **Fresh holistic pass on the committed doc as the artifact of
   record**: now that this is permanent, checked-in text (not a
   transient report), read it end to end as if for the first time. Does
   anything read as inconsistent, unclear, or wrong now that it's
   assembled as a single document rather than scattered across your
   original review comments? Is the Status line ("design, reviewed; not
   implemented") accurate and does it match this project's convention
   for other docs/design/*.md files?
5. Anything else -- a new defect introduced by the folding process
   itself, a section that lost precision when transcribed from the raw
   codex report into the final document.

## Verdict

For each of your original two P2s and six P3s: CLOSED, PARTIALLY CLOSED,
or NOT CLOSED, with why. Then an overall verdict: READY / READY WITH
CHANGES / NOT READY for this committed document to stand as-is.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: <your session id>
Then the per-finding disposition and overall verdict. Facts only, each
claim checked against the actual committed file, not assumed.
