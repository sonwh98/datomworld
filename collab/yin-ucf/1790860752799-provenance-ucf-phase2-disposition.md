# Provenance: disposition of the ucf-phase2 branch (linker M2 era)

Created: 2026-10-01 (provenance-research subagent, read-only investigation)
Question: what happened to branch `ucf-phase2` (linker epic M2 "four
format records" milestone, uncommitted in worktree
../datomworld-ucf-phase2 around 2026-09-25)?

## Answer in one line

The branch was NOT dropped or superseded: its uncommitted work was
committed, gated, merged into master on 2026-09-26 13:12:37 +0700
(merge b3d0b5a1), and the branch and worktree were deleted during
same-day epic cleanup. The M2 substance survives verbatim on master
and was extended by M4; the linker-over-DHT pass is an additive layer
on top of it, itself not yet landed.

## Timeline (all times +0700)

- 2026-09-25 12:45 M1 landed; worktree ../datomworld-ucf-phase2 created
  (branch ucf-phase2 @ 96657a4f); M2 dispatched to it.
  docs/orchestrator-log.md:7122-7160 (worktree created at :7132).
- 2026-09-25 16:10 / 17:16 / 17:53 M2 gate cycle: fix rounds 1-3 sit
  UNCOMMITTED in the ucf-phase2 worktree; cycle paused after round 3.
  docs/orchestrator-log.md:7163-7287 (paused at :7245-7249).
- 2026-09-26 06:09 M2 committed on ucf-phase2: 19719d66 "linker M2
  four format records and identity-directed fetch" (replaces :hash-fn
  with :identity-fn; AST, semantic, stack, register format records).
  Then 9779044c (M3 stepped core, 07:21) and cb96de0b (authority A1/A2);
  ucf-authority merged in at cfca34a5.
- 2026-09-26 13:12 branch merged to master: b3d0b5a1 "Merge branch
  'ucf-phase2'" (parents 71f3fb93 + cfca34a5; conflicts resolved in
  docs/design/yin.vm.semantic.md and yin.vm.universal-continuation-
  format.md). Reflog: `b3d0b5a1 HEAD@{2026-09-26 13:12:37 +0700}:
  commit (merge): Merge branch 'ucf-phase2'`.
- 2026-09-26 16:47-23:22 M4 slices (S1-S5, A3) and M5 merged (merges
  d64e2ec4, 9428c3d2, 28ff1a5b, 3e9a665c, 28229945, 78163e92,
  768e62c2, followups 3244f9ea; report commit 2f0cbcc4).
  docs/orchestrator-log.md:7304-7401.
- 2026-09-26 evening: worktree removed and branch deleted by the
  claude (Sonnet 5) seat; its report entry says "No feature branches
  or extra worktrees remain" (docs/orchestrator-log.md:7309-7310).
  Session transcript ~/.claude/projects/-Users-sto-workspace-
  datomworld/fa5bf27a-873e-4b49-b144-4020b87a8d0e.jsonl (session id
  recorded at log :7306) contains
  `worktree remove /Users/sto/workspace/datomworld-ucf-phase2`.
  refs/heads/ucf-phase2 no longer resolves and its per-branch reflog
  file is gone (normal after `git branch -d` of a merged branch).
- 2026-09-27 09:35 successor seat verified: "all epic worktrees are
  gone; the ucf-phase2 branch was fully merged"
  (docs/orchestrator-log.md:7505-7507).

## Ancestry verification (run 2026-10-01)

- `git merge-base --is-ancestor b3d0b5a1 master` -> true
- `git merge-base --is-ancestor 19719d66 master` -> true (M2)
- `git merge-base --is-ancestor 9779044c master` -> true (M3)
- `git merge-base --is-ancestor 19719d66 linker-l5` -> true

## Correction to the 2026-10-01 20:20 takeover note

The note (docs/orchestrator-log.md:8974-8979) says the branch "is not
an ancestor of master" and that the linker direction "was superseded
by the linker-over-DHT design pass". Both halves are incorrect:

1. The branch's tip IS an ancestor of master (via b3d0b5a1). The
   check fails only because the branch name no longer resolves
   (unknown revision), which reads as non-ancestry.
2. The DHT design does not supersede the M2 linker; it is subordinate
   to it (see below).

## Does the M2 substance survive? Yes, on master

src/cljc/yin/vm/linker.cljc (master, 2479 lines) carries all four M2
format records with :identity-fn / :identity-matches-fn:

- :yin.debruijn.code   (stack)     :654-669, identity = image-hash
- :yin.debruijn.register         :672-688, identity = register-hash
- :yin.semantic/code             :691-710, identity = jing/segment-key
  (:identity-fn at :703, :identity-matches-fn jing/segment-matches?
  at :704) - the segment-key identity lives at src/cljc/dao/jing.cljc:301
- :yin.ast/code                  :713-730, identity = jing/segment-key

Identity-directed verification dispatch: linker.cljc:1237-1250
((:identity-fn format) / (:identity-matches-fn format)), also :1956
and :2001. M4 later extended the record family (:yin.module/manifest
:877, :yin.ledger/record :894). The same records exist in the
linker-l5 tree (8 identity-fn hits; :yin.semantic/code at :703).

## Relation to the linker-over-DHT pass

docs/design/yin.vm.linker.dht.md exists only on branch linker-dht
(a1f41db3, 2026-10-01 10:05; not on master). Its header declares it
"Subordinate to datom.world.md, yin.vm.linker.md, dao.jing.dht.md,
yin.repl.link-policy.md, yin.repl.dao.space-index.md" (:19-24), and
section 1 keeps the M2 linker as the engine: "the linker resolves a
module name to a module manifest address through a name environment,
and links the manifest's image over a content pair". The L-slices are
additive: L2 (79c1e55d) adds src/cljc/yin/vm/linker/closure.cljc,
linker/dht.cljc and linker/publish.cljc; nothing deletes linker.cljc
or the format records. Per the 2026-10-01 stop state
(docs/orchestrator-log.md:8919-8955) the DHT epic is NOT landed:
design + L0-L4 committed on branches, L5 uncommitted (13 files) in
../datomworld-linker-l2 on branch linker-l5.

## Verdict

Disposition = LANDED. ucf-phase2 -> master via merge b3d0b5a1 on
2026-09-26; branch and worktree deleted same day after the merge
(standard post-epic cleanup, owner-ordered "commit it and push
everything"). The M2 format records and both identity families
(:identity-fn/:identity-matches-fn; :yin.semantic/code under
jing/segment-key) are live code on master today and are the substrate
the linker-over-DHT design builds on.
