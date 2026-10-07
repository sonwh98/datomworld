Created-GMT: 2026-10-01 17:10:00 GMT
Created-Local: 2026-10-02 00:10:00 +0700
Coding-Agent: glm-5.3 (CLI)
Session-ID: 4b857b1a-a63f-4d24-9408-db1bcda00bc4

# Task: Linker Hardening Stage 1 — the Kept-Cursor Proof

Role: VM Runtime Engineer

Implement Stage 1 of the post-M5 hardening design: section 14 of
/Users/sto/workspace/datomworld-linker-hardening/docs/design/yin.vm.linker.dht.md
(committed; read the whole section 14 first — the kept-cursor identity,
aliasing rules, safepoint behavior, the lift/lower steps, and the
nine-host-pair test matrix are fully specified there), per its stage
sequencing (stage 1 = the cursor proof).

Worktree: /Users/sto/workspace/datomworld-linker-hardening (branch
linker-hardening @ 111a9823; the ANTLR parser and the node reader are
already built for you).

Read first:
- docs/design/yin.vm.linker.dht.md section 14 (your contract) and
  section 7.11's kept-cursor acceptance row in
  docs/design/yin.vm.universal-continuation-format.md
- docs/design/yin.vm.ucf-revisions.md section 8 (the :reasons ruling)
- src/cljc/yin/vm/linker/dht.cljc, src/cljc/yin/repl/link.cljc,
  src/cljc/dao/space/dht.cljc (the landed machinery you extend)
- docs/design/datom.world.md (the invariants)

Deliverables:
- The stage-1 mechanism per section 14's design: kept-cursor identity
  and aliasing rules, the safepoint behavior at every declared park,
  and the lift/lower steps — implemented on the landed machinery.
- The nine-host-pair test matrix from section 14's test contracts, as
  tests in the file box's location.
- Whatever UCF amendment stage 1 requires, recorded per section 14's
  sequencing note (the version-1 amendment itself is stage 2 — stage 1
  only records what stage 2 must carry).

Constraints:
- Pure ASCII, <= 80 columns on every line you add or edit; no em
  dashes; cljstyle and kondo clean.
- Do NOT commit or stage; do NOT run git checkout/reset/stash; do NOT
  touch docs/design/yang.antlr.md, src/cljc/yang/python/, collab/, or
  docs/orchestrator-log.md (a peer orchestrator owns the first two; the
  log is the seat's).
- JVM tooling under mise (mise exec -- <cmd>). The lane runner is
  bb test:clj / bb test:cljs / bb test:cljd — bare clojure -M:test
  skips required build steps; if you use it, run
  bb build:yin-repl-node and clojure -M:antlr-gen first (both are
  already done for you).
- Verify: all three lanes green, solo and sequential; report exact
  counts (this tree's baseline: JVM 2,808 tests / 225,640 assertions /
  0 failures; Node 2,656 / 91,265 / 0; CLJD 2,568 passed).
- If section 14's stage-1 design is ambiguous or conflicts with the
  landed machinery, STOP and report BLOCKED with the exact conflict.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
