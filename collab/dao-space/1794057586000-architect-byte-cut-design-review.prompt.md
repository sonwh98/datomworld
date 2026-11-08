Created-GMT: 2026-11-07 12:19:46 GMT
Created-Local: 2026-11-07 19:19:46 +07
Coding-Agent: muse
Session-ID: 11c6cdbb-cfab-4b62-abe0-071cbbe541df

# Task: Architect review — byte-sized index page cuts (adjudicate the framing BEFORE implementation)

Role: Architect (design review; READ-ONLY)

Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-07 19:19:46 +07 | Status: active | Rationale: deep design review per team.md's selection guide; read-only round

## Background (verified facts; do not re-derive)

Nightly 20261106-0944: 512-key index pages crossed the DHT message bound
(65536 bytes) for the REPL store's code-heavy datoms → send refused → ack
collapse. Fix 5 (branch nightly-reds-fix @ 4f772d61) works around it with
`:publish-opts {:branching-factor 64}` (8× page amplification), which spawned a
repair-queue cadence regression (dart ticker-idle red) and an engineer slot
sweep that the owner halted as "solving the wrong problem", reframing:
"This problem should be reframed in terms of byte size not in terms of datom
count" (owner directive, 2026-11-07). A proposed implementation brief exists.

## Review these two artifacts (in the repo root, read both fully)

- collab/dao-space/1794056541000-orchestrator-byte-sized-index-pages.directive.md
  (the owner directive + the design working-out)
- collab/dao-space/1794056711000-engineer-byte-sized-index-cut.prompt.md
  (the proposed implementation brief — review the DESIGN it encodes, not its prose)

## Ground truth to check them against (the code)

- src/cljc/dao/space/index.cljc — `from-sequential`, the cmp functions,
  `publish-index!` (:619 area), the conforming-manifest validator (:287-296),
  incremental `publish!` (:1172 area — does the INCREMENTAL path also cut
  pages? a byte-cut must apply to BOTH bulk and incremental, or the two paths
  produce differently-shaped trees from the same datoms, breaking determinism
  across publications)
- src/cljc/dao/jing/cbor.cljc — encode cost: the brief's algorithm ENCODES A
  CANDIDATE LEAF PER DATOM to test the budget (O(n²) encodes per page build);
  is there a cheaper sound approach (running size estimate, incremental CBOR
  length accounting, or encode-once-then-split) and what does the design need
  to say about it?
- src/cljc/yin/repl/index.cljc:363-371 and :407-410 — `:branching-factor`
  consumers (verified-candidate comparison, manifest hash input)
- src/cljc/yin/repl/dht.cljc — fix 5's override and its docstring claims
- src/cljc/dao/jing/dht.cljc:80 + docs/design/dao.jing.dht.md:805 — the bound
  and its "composition data, confirm or raise" status
- tests pinning page shape: grep test/dao/space and test/yin/repl for
  branching-factor / 512 / 64 pins

## Adjudicate (give a verdict on each)

1. Is byte-sized cutting the correct reframe at all — or is there a better
   axis (e.g. fixed max-message-bound-derived cut + oversize-value chunking at
   a different layer)? The owner's directive frames it as byte size; if you
   see a materially better framing, say so with reasoning, do not rubber-stamp.
2. Does the proposed algorithm (encode-candidate-per-datom) preserve the
   determinism invariant (page cut = pure function of the sorted datom stream)
   while remaining practical? If O(n²) encoding per page is unacceptable,
   specify the acceptable alternative design precisely.
3. Does the byte cut apply identically to the bulk and incremental publish
   paths? If not, what must the design add?
4. Manifest compatibility: is keeping `:branching-factor` as the realized
   emergent count sound, given yin/repl/index.cljc's verified-candidate
   comparison and the manifest-as-hash-input? Or does this comparison need to
   change, and how?
5. Is reverting cb2cbe11 (:repair-slots 48) correct once byte-cut lands, or is
   the cadence question genuinely still open?
6. Sequencing: implement now on nightly-reds-fix (per the owner redirect) vs
   the directive's original after-master-green sequencing — pick one and own
   it; state what risks your pick carries.
7. The 48 KiB budget number: confirm, correct, or demand a measurement first.

## Constraints

Read-only (you run with --disable-write): no edits, no commits, no test runs
beyond reading. You may read the worktree /tmp/nrf-branch-wt and the shiva/jupiter
clones read-only over ssh if a claim needs it. Deliver a verdict per point, an
overall disposition (APPROVED / APPROVED-WITH-CHANGES / REJECTED with the
required changes enumerated precisely enough for an engineer brief), and
anything the two artifacts missed entirely.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
