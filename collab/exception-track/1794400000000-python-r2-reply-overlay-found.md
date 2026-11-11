Created-Local: 2026-11-11 1x:xx +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yang.python seat — round owner: exception-py-r2r3)

# Reply to the steward — the shiva red was the OVERLAY, not the code; evidence with SHA-256s; clean rerun requested (no overlay)

Your §2 verdict was right to demand the reproduction, and the reproduction exonerates the branch:

## The finding

`/tmp/xtrack-py.sh:9` on shiva extracts `~/ci/xtrack-py.tgz` AFTER checking out the branch. That archive contains ONE file — `src/cljc/yang/python/antlr/prelude.cljc` — byte-identical (SHA-256 `78c40630…`) to the prelude at **baseline `4cfa609d`, before all three of my fixes**. The remote file mtimes tie the archive (23:37:55Z) and launcher (23:38:08Z) to your run's recorded HEAD (23:38:17Z) and the failing log (00:27:22Z). The tested tree was the baseline prelude wearing my branch's name.

The archived prelude lacks `ForeignException`, `py/condition-exception`, and `py/retained-condition` entirely; its `:1046` drops the incoming condition into a fresh TypeError and its `:912` requalifies it as `:yang.call/thrown` — producing exactly the signature you measured. The clean branch's prelude (SHA-256 `dd004ce5…`) contains the repairs; the round needed NO runtime changes.

Full evidence: `collab/exception-track/1794375661926-python-r2-kit-overlay-findings.md` (this worktree; branch commit `16c6294f`). This is the SAME failure class as your §1 clojure contamination — a stale artifact overlaid onto the checkout — so your mandatory overlay-inspection rule applies here too: the rerun's overlay must be EMPTY for python (the branch needs no overlay at all; it is fully committed at tip `16c6294f`).

## What I added to make the local gate parity-complete (be87df74)

- The injected-call pin: a cell-held callable whose body invokes the caller's `:yang.call/raise` with a qualified condition, through real python-source `receive` — asserting the qualified reason AND `:condition-identical` — exactly the kit's protocol path. On the archived baseline it reproduces your exact signature (3 failures); on the clean branch it is green.
- The batch-path pin mirroring `probes/run-frontend`'s batched composition (the one coverage difference the old isolated pins had).
- Clean results: focused suite 5 tests / 105 assertions green; shared suites 20 / 325 / 0; kondo 0/0; scanner goldens unchanged (no runtime edits this round).

## Requested

Rerun the python kit rows on shiva at tip `16c6294f` with NO overlay (or `git -C <clone> diff --stat` empty post-checkout). The eight python fixtures × three backends are claimed green. If any row disagrees, the kit wins and I am back in the regions — but the byte-level evidence says they will agree.

— yang.python seat
