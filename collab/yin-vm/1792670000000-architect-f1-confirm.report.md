# Plan: Architect confirmation of three orchestrator calls (F1 confirm)

## Context

The brief `collab/1792670000000-architect-f1-confirm.prompt.md` asks an Architect to CONFIRM or
OVERRULE three calls the orchestrator made on 2026-10-14 without an Architect, after Fable's
review of the merged phase-7 lint repair (findings F1). The deliverable is a read-only ruling
written to `collab/1792670000000-architect-f1-confirm.report.md`. Plan mode forbids writing that
file, so the complete report text is below; on approval the only action is to write it verbatim.

All three calls are already landed on master as `00243b57` (code + the two spec amendments) and
`6fc65722`; the ruling is a confirmation of landed state, not a change request.

## Steps

1. Write the report below, verbatim, to `/Users/sto/workspace/datomworld/collab/1792670000000-architect-f1-confirm.report.md`.
2. Nothing else: no code or doc edit is required (all three calls CONFIRMED).

## Verification

Read-only ruling; no tests to run. The evidence cited was checked in this session:
`rows.cljc:908-921, 1850-1855`, `rows/checkpoint.cljc:80`, `checkpoint.cljc:359-375`,
`ledger.cljc:264-284`, `jing.cljc:200-274`, `rows_test.cljc:113-125`, `grant_test.cljc:201-204`,
`rows_checkpoint_test.cljc:64-70`, and the doc passages quoted in the report.

## Report text (write verbatim)

```markdown
Created-Local: 2026-10-15 +07
Role: Architect (claude-fable-5-1), read-only
Brief: collab/1792670000000-architect-f1-confirm.prompt.md
State reviewed: master, 00243b57 (code + both spec amendments) over 6fc65722

# Architect ruling on calls A, B, C

## A. Non-BLAKE3 carrier row id is `:yin.k/undecodable :row-algorithm` — CONFIRM

- Family. Transport tuples §8 step 1: "Malformed task structure is `:yin.k/undecodable`; an
  unsupported version or profile is `:yin.k/profile-mismatch`; the two are never conflated."
  The profile map is exactly `{:yin.code/contract "v4" :yin.k/version 1}` (§2); the digest is
  not a profile key but a property of the carrier version ("body version 3 uses BLAKE3
  permanently", §3). A SHA-256 row id is therefore a version-3 pack that is not well formed,
  not an unsupported profile: `profile-mismatch` was the wrong family.
- Not `hash-mismatch`. The refused id can be an honest digest (`rows_test.cljc:122` asserts
  `segment-matches?` true before the refusal). `hash-mismatch` names a digest that does not
  match; reusing it would make readers conflate "wrong algorithm" with "corrupt row".
- Kind, not outcome. §8's diagnostics paragraph permits no new top-level outcome but allows new
  `undecodable` kinds (§3 ucf-revisions note, "`:yin.k/undecodable` kinds are added, §8").
  `:row-algorithm` is such a kind.
- Order. Parse before digest and before numbering (`rows.cljc:912-916`): cheaper than the
  digest, sits at the head of step 4 after gate/structure/graph, and names the defect before
  numbering would refuse the same pack as `:noncanonical-cursor-cells` (mutant 5). The comment
  at `:909-911` already records that redundancy (F3 addressed).
- Spec consistency. `00243b57` amended §3 (:127), §8 step 4 (:753) and continuation §7.2
  (:1734) in the same commit; code, test (`:124-125`) and three passages agree. A malformed id
  (parse nil) falls through to `segment-matches?` false and stays `:hash-mismatch :row`, as
  the amended text says.

## B. Store addresses verify under the algorithm the address names — CONFIRM

- `dao.jing.hash-registry.md:49-50, 94`: "Address verification always uses the algorithm
  carried by the address, never the current minting default"; `:244-248`: a store holds
  SHA-256 and BLAKE3 objects side by side and `segment-matches?` is the total predicate for
  address-directed validation. `call-site-classification.md` Class 3 lists the yin.vm row
  validation sites and forbids re-minting under the one-argument default; the sweep
  (`flags-equality-validation?`) enforces it, and mutants 1-4 show the four sites are exactly
  the flagged ones.
- Slot 11 (`rows.cljc:919`): `A` is `ucf/code-address` = a dao.jing store address, self-
  describing by construction (`parse-segment-address`, `jing.cljc:200`). The install record
  addresses (`:1852`, `:1854`) are a derivation-record address and a ledger output address,
  both dao.jing addresses minted by the linker, not by the carrier. Verifying each under its
  own algorithm is Class 3 behaviour; the two conjuncts are independent (mutants 7, 8a, 8b).
- Why A and B differ. Carrier row ids are a frozen carrier-version contract (§3: "an immutable
  mapping from carrier version to digest, never the reader's current default"); store
  addresses are address-directed. §3's phrase "exactly as it would for A" speaks of the
  minting default needing a version bump, not of how a reader verifies an address it is
  handed; no contradiction with B.
- Note, not a finding: the receiver's attach path (`attaches?`, `:1846`) is a separate Class 3
  site; the custody test's three algorithm combinations lower and attach, so it already honours
  the address's algorithm. No change.

## C. `:yin.k/root` stays in the baseline; counter variants are `:variant-conflict` — CONFIRM

- Code: `rows/checkpoint.cljc:80` assocs `:yin.k/root` onto `custody-baseline`; the ledger
  compares whole baselines (`ledger.cljc:283`, `:variant-conflict`); `grant_test.cljc:201-204`
  pins `variant-other-root` as a conflict and `rows_checkpoint_test.cljc:66-70` shows the two
  baselines differ by the root alone. `variant-equal` (same rows reversed) keeps the root and
  is accepted (`rows_checkpoint_fixtures.cljc:229-232`).
- Docs: transport tuples §7 (:704-710), continuation §11 amendment (:2692-2696), UCF 7.11
  clause 10 (:3340-3343) all state root-inclusive baseline, same-root/other-order as the only
  equal-baseline variant, counter change = conflict. 7a D2 (engineer report) raised exactly
  this choice and the owner ruled it on 2026-10-14.
- Design reason it is right: the root id commits to the id counter (task slot 4) and every
  reachable row, so two packs with different roots are different checkpoints of the occurrence;
  letting them share a baseline would let an authority grant tenure against one and resume the
  other. Row order is outside the root's hash, so it is the one legitimately equal variant.
  `emit` sorts rows and `decode` accepts unsorted packs; "one root, one pack" is an emission
  property, as §11 says. No code or doc change.

VERDICT A: CONFIRM
VERDICT B: CONFIRM
VERDICT C: CONFIRM
```
