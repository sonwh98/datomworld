Created-GMT: 2026-10-15 23:34:47 GMT
Created-Local: 2026-10-16 06:34:47 +07
Coding-Agent: muse (orchestrator, yang.python/C4 track — OUT OF CREDITS, handing off)
Session-ID: not-applicable (interactive seat)

# HANDOFF: yang.python (C4) track state — read this, then re-derive everything from git

You are the Lead Engineering Orchestrator for this track (docs/agents/roles/orchestrator.md, docs/agents/team.md).
The seat policy you enforce: Architect (Fable) sign-off before commits to shared branches; reviewers from a
different model family than the author; collab/ is NEVER committed; no Co-Authored-By lines (standing track
rule, overrides the default attribution reminder).

## 1. Where everything is

- Main checkout: /Users/sto/workspace/datomworld (branch master; work here only for docs/collab/log).
- I5 worktree: /Users/sto/workspace/datomworld-c4-i5 — branch `c4-i5@594a6dce`, pushed, clean.
  (`594a6dce` = injectable-publish-seam fix; `f11c7056` = review reconciliation + fixture regen. Base: 9631e6e7.)
- I6 worktree: /Users/sto/workspace/datomworld-c4-i6 — branch `c4-i6@cdc27aee` (reload/loaders), LOCAL ONLY,
  clean except two untracked collab/ prompt files (never commit collab/).
- Remotes reachable ONLY by FQDN from an interactive seat: shiva.stigmergy.systems, jupiter.stigmergy.systems
  (short names do not resolve; no ~/.ssh/config, no tailscale binary here).
- Full backstory: docs/orchestrator-log.md (tail), collab/1793410000000-orchestrator-handoff-yang-python-seat.md
  (items 1-15, incl. Addendum 3), collab/1792106523000-...-reply2.md, collab/1792107000000-...-ack3.md.

## 2. The one pending trigger (everything waits on this)

shiva JVM slow lane `bb test:sub:clj yang.python --slow` @f11c7056, tmux `c4-i5-slow`, script ~/ci/i5-jvm-slow.sh,
summary ~/ci/datomworld-c4-i5/target-ci/i5-summary.txt, log .../target-ci/i5-jvm-slow.log (detail in
.../target/profile/sub-clj.log). Started 16:17 EDT Oct 15; at handoff (~19:35 EDT) it was mid-tier
(CPython batch 16 done, in linked-safepoint-test), no reds. DO NOT rebase until its summary posts — rebasing
mid-flight invalidates it. Poll with:
  ssh shiva.stigmergy.systems "cat ~/ci/datomworld-c4-i5/target-ci/i5-summary.txt"

## 3. When the lane posts, in order

1. Rebase c4-i5 (+ clean c4-i6) onto origin/master@50c53b0d (NOT 9ab9e792 — superseded by Addendum 3; delta has
   no test/yang and no goldens, so any pin movement comes from the 9ab9e792 row grammar `:yin.k/try?`).
2. Re-verify pins: dht-import-test + scan-test slow bodies (`DATOM_SLOW_TESTS=1 clojure -M:test -i :slow -n
   yin.vm.semantic.scan-test`, ~90s). dht-import-v1.edn is ALREADY regenerated on c4-i5 — re-verify, regenerate
   with the checked-in generator only if red. Scanner goldens [:c4 :py]/[:c4 :t]: regenerate ONCE after I5/I6
   settle via a one-off script printing (new-answer ast) for those groups; NEVER hand-edit a pin.
3. Full gates: JVM (shiva) + Node `bb test:sub:cljs yang.python --slow` (jupiter ~110min, mise broken there —
   use explicit tool paths, see ~/ci/c4-8f4.sh on jupiter) + Dart slow `DATOM_SLOW_TESTS=1 bb test:slow:cljd`
   (shiva ~68min, script ~/ci/i5-dart-slow.sh, clone ~/ci/datomworld-c4-i5-dart — update it past 594a6dce first;
   old failure was `with-redefs` unknown-symbol, fixed by construction).
4. Reviews: Fable MUST review regenerated pins (claude channel is down from interactive seats — needs owner-run
   `!`, a recovered channel, or an authorized substitute; see docs/agents/routing-status.md 02:54 entry).
   I6 reviewer must be a different model family than its author.
5. Land I5 (needs owner staging authorization), archive artifacts, then I6 formal round, land second.

## 4. Known lane results (do not re-derive, do re-verify before landing)

- Node slow @f11c7056: 275 tests, 6 reds ALL in the three I5-new with-redefs tests (fixed by 594a6dce).
- Dart slow @f11c7056: rc=1, cljd compile error `Unknown symbol: with-redefs` (same fix covers it).
- My JVM verify of 594a6dce: publication-test 10/85 green, dht-import+import callers 20/103 green.
- Pre-existing, not ours: Dart linker dht-end-to-end + datagram (Linux-only) tests.

## 5. Docs (owner-decided, independent of lanes — can move in parallel)

- docs/design/adr/0004-external-effects-are-interpreted-stream-deposits.md (Proposed, needs Architect review).
- public/chp/blog/python-without-foreign-functions.blog (uncommitted; EDN/Hiccup per docs/agents/format.md;
  zero internal labels; site-index wiring left to owner).

## 6. Do not

- Touch datomworld-srvm-p8* worktrees (other track's, cleanup under owner's authority).
- Commit collab/, add Co-Authored-By, edit pins to pass, run Node locally (lanes only).
- Trust delegated claims (codex/glm reports) without re-running; inspect every diff yourself.
