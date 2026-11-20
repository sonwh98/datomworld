Created-GMT: 2026-11-19 18:0x:xx GMT
Created-Local: 2026-11-20 01:0x:xx ICT (+07)
Coding-Agent: interactive (ZCode / glm-5.3-flash, Gambit host-lane orchestrator seat)

# Relay: jolt-toolchain.edn re-pin to the FFI lineage (owner-directed, cross-cutting)

To the yin.repl jolt orchestrator seat — per the addendum this is your file, so
this note files the change for your §10.1 bookkeeping before it reaches master.

Owner direction (2026-11-19): pin the FFI work. The change, prepared and verified:

- :sha b389f1a7 (branch assoc-deftype-refusal) -> dd605630 on fork branch
  gambit-ffi-p1b. Submodule pins UNCHANGED (all 7 verified identical).
- Prerequisite handled first: b389f1a7 is NOT an ancestor of the FFI lineage
  (fork main diverged ~920 commits; the assoc-deftype refusal block exists only
  at the old pin). The fix was carried onto the FFI lineage as commit dd605630
  (cherry-pick, conflict resolved per the current records-coll.ss structure;
  records mirror regenerated, both seeds byte-identical; unit row
  records-assoc-refusal at test/chez/unit.edn:2596 gate-holds the parity —
  [false "class user.AssocRefusal cannot be cast to class
  clojure.lang.Associative"], JVM-matched against Clojure 1.12.5/JDK 21;
  findings in the clone: p1b-stage1/P1b-assoc-parity.findings.md).
- Verified end-to-end on macOS: bb bootstrap:jolt against the new manifest
  (fresh clone at dd605630, 7 submodules, smoke green) and the FFI gate matrix
  on the newly pinned clone (gambitffi PASS — ABI 79/79, public 44/44, errno
  9/9, loader 14/14; provenance recorded per-install per the new fail-closed
  design). jolt CI on this lineage: all code jobs green (Linux shards +
  Android bionic; macOS/Windows jobs are upstream infra).
- Scope of the pin: the FFI-degraded tier per the owner's accepted boundary —
  blocking, foreign-thread callbacks, dynamic callables, AOT/FASL/subprocess
  keep message-carrying refusals (gambitcheck-held). The yin.repl-under-Gambit
  slice (gambit-ffi-p1c, in flight on the fork) advances the pin through the
  same gate when it closes.

Landing: after the Lane B tri-host gate completes (your track's slice, running
now), this seat lands Lane B, then commits the re-pin + this note as one small
batch, fetch+rebase first, then the wide-change tri-host gate covers both.

— the Gambit host-lane orchestrator seat
