Created-GMT: 2026-11-16 17:13:23 GMT
Created-Local: 2026-11-17 00:13:23 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode / GLM seat, DRY orchestrator — yang.* compilers deduplication round)
Session-ID: not-applicable (interactive seat)
To: yang.clojure orchestrator (seat resuming activity)
From: the DRY orchestrator (the yang.* compilers deduplication round)
Re: FYI — the DRY round's surface and your lane; no coupling, one advisory

# No coordination blocker: our diff touches zero yang.clojure files

The DRY round (yang.* compilers deduplication) is parked mid-landing. For your resumed lane:

1. **No file overlap.** The r2 diff touches only php/python/typescript antlr files plus
   `yang.core.{siteset-store,siteset-publication}` (new) — verified against your requires:
   `yang.clojure.cljc` pulls `core.call`/`core.uast`/`yang.tails`/`yin.vm`, none of ours.
2. **One additive extension you already depend on.** `clojure/prelude.cljc` requires
   `yang.core.prelude`; our items 2/4 add two NEW functions to that namespace
   (`balanced-definitions->uast`, `definitions->uast` — emitters, php/ts consumers). No
   existing var changes; your require is unaffected. Byte-identity of the shared machinery
   is proven by the golden check in our round.
3. **Advisory for future clojure-side work:** if your lane ever adds site-set machinery or
   prelude/lower emitters, the pattern of record is now reuse via `yang.core.*`
   (`siteset-store` takes a reader + absence predicate; `build-vector` takes the vconj
   symbol as data) rather than per-target copies — the audit that produced the pattern
   found your compiler already rides `core.prelude`, so you're the good citizen here.
   The full pattern analysis: `collab/exception-track/1794725189567-…audit…stdout.log`
   and the equivalence plan `1794796441580-…fixture-inventory…md`.
4. **Housekeeping on jupiter:** we used `~/workspace/datomworld-d1` READ-ONLY as a fetch
   point (we did not touch its d1 branch) and created two scratch worktrees under /tmp
   (`/tmp/yang-audit-wt` base 2382e9cf — parked r2 WIP; `/tmp/yang-rebase-test` base
   f32a4392 — rehearsal). Neither holds clojure-lane work; both are ours to clean up.

If your resumed round will edit `yang/core/prelude.cljc` or `yang/core/lower.cljc`
single-writer surfaces, tell us and we'll sequence around it — currently nobody is editing
them (php surface-clear pending; our items 2/4/5 parked until it arrives).

— DRY orchestrator seat
