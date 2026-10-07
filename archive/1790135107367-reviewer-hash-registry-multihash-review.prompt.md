Created-GMT: 2026-09-22 21:25:07 GMT
Created-Local: 2026-09-23 04:25:07 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: 95116c74-2f68-4276-99a8-56291df8587e (resume of your prior hash-registry-plan review session)
# Task: reviewer-hash-registry-multihash-review — review the reworked committed doc
Role: Independent Reviewer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-23 04:25:07 +07 | Status: active | Rationale: same reviewer, resumed; this is a materially reworked document, not merely a fold-in of your prior P2/P3s

Work in /Users/sto/workspace/datomworld (your launch directory; branch
master). READ-ONLY, STATIC REVIEW. Do not edit any file, do not run
`git add`/`commit`/`push`. Do not run the test suite -- there is no
implementation yet.

## Context

You previously reviewed gpt-5.6-sol's plan text (verdict: READY WITH
CHANGES, two P2s, six P3s -- your report at
collab/1790110150738-reviewer-hash-registry-plan.glm-5.3.stdout.log).
That version was written to docs/design/dao.jing.hash-registry.md and
committed as 5de9e7b1, framing SHA-256 as a "legacy spelling... retained
permanently for existing content" and building a two-phase
mixed-address-storage migration around it.

The owner then rejected that framing entirely: "there's no need to
support legacy but support multihash like IPFS. sha-256 is supported but
it is not a legacy support." This project has an established rule (see
[[project_no_backward_compat_needed]]-equivalent reasoning, already
applied identically to docs/design/dao.jing.cbor.md's own "Addressing and
clean break" section) that datom.world has no deployed stores and
therefore no backward-compatibility burden anywhere.

gpt-5.6-sol was resumed twice (once with a wrong "drop SHA-256 entirely"
instruction that was caught and corrected before it ran, then with the
corrected instruction) and produced a materially reworked document,
committed as `67585e52` ("docs(dao.jing): rework hash addressing as
permanent multi-algorithm, not legacy compat"). Read that commit in full
(`git show 67585e52`) -- this is a rewrite, not a patch: BLAKE3 and
SHA-256 are now both permanent first-class registry members (SHA-256 is
NOT deprecated, transitional, or legacy -- it's an equal, ordinary,
explicitly-selectable algorithm), there is no migration phase (the
former H2 "mixed-address storage" phase is gone entirely), and the
rollout collapsed from six phases (H0-H5) to three (H0-H3).

## What to check

1. **Does the document actually achieve genuine multihash-style
   multi-algorithm support, not a relabeled legacy framing?** Read the
   "Decision," "Address format," and "Clean introduction" sections
   closely. Is there any place SHA-256 is still treated as special,
   older, or lesser than BLAKE3 in a way that contradicts "SHA-256 is not
   deprecated, transitional, or a compatibility fallback"? Is the short
   `:segment/sha256-<hex>` spelling justified as "simply this registry's
   canonical spelling for print-v1/sha256," or does that justification
   secretly still smell like legacy-alias reasoning?
2. **Is dropping the migration phase actually sound**, or does removing
   it silently break something your original review's P2-1 finding
   depended on? Your original P2-1 was about `dht.cljc:229` and
   `btree/storage.cljc:186,286` needing to mint fetched content under the
   SOURCE address's algorithm, not the default -- verify the new
   "Address-directed mint sites" section still requires this (it should,
   since ongoing multi-algorithm operation needs it regardless of
   migration, but confirm the reasoning given in the new doc is
   "ordinary multihash correctness" and not accidentally re-introduced
   as migration-only advice that got missed when H2 was deleted).
3. **Re-verify your original call-site audit claims still hold against
   this rewritten text**: the primitive-profile mislabel fix, the
   DaoSpace bare `:schema-hash` fix (now "replace outright... no fallback
   reader" -- confirm this is consistent with dropping migration, i.e.
   confirm there really are no existing checkpoint candidates that would
   need a fallback, or flag if that claim needs verification you can
   actually perform), and the ~18 validation sites list.
4. **The collapsed H0-H3 phases**: are completion criteria for each
   phase still concrete and testable after the rewrite? Does H1 (now
   "registry and whole-system cutover," combining what was previously
   spread across H1-H3) attempt too much in one phase -- host primitives,
   ALL backend/consumer call sites, AND the default flip, all at once --
   in a way that makes it hard to land incrementally, or is a single
   cutover phase actually correct given there's no migration to stage
   through?
5. **yin.vm H/R disposition**: does the new document's framing ("neither
   compatibility support nor an application of DaoJing's multihash
   registry... independent contract boundaries that happen to specify
   SHA-256") hold up, or does it contradict itself anywhere relative to
   the "SHA-256 is a first-class multihash registry member" framing
   (i.e., is H/R's SHA-256 clearly enough distinguished from the
   registry's SHA-256, given both are "just SHA-256" at the byte level)?
6. **Scope and consistency**: does the "Explicit non-goals" list actually
   match this rewritten document's own phases? Does anything in
   "Sequencing with canonical CBOR" still carry stale assumptions from
   the deleted migration framing?
7. Anything else -- a new inconsistency introduced by the rewrite itself,
   a place two sections of the reworked document now disagree with each
   other, a completion criterion that references something the rewrite
   deleted.

## Verdict

READY / READY WITH CHANGES / NOT READY, findings as P1/P2/P3 with
concrete failure scenarios or file:line/section references, not vague
concerns. State plainly whether this reworked document is sound as
committed or needs further correction.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: <your session id>
Then the verdict and findings. Facts only, each claim checked against the
actual committed document or the actual codebase, not assumed.
