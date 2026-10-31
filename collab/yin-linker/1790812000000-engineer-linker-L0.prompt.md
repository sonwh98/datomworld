# Brief: linker over dao.jing.dht — slice L0

Role: Engineer (docs/agents/roles/).
- Worktree: /Users/sto/workspace/datomworld-linker-l0, branch linker-l0, from linker-dht a1f41db3, which holds the signed-off design.
- Do not stage or commit.

**Contract (binding):**
- docs/design/yin.vm.linker.dht.md: section 12, slice L0 (its files and every acceptance bullet), plus every section it cites.
- Owner decisions: collab/1790808000000-orchestrator-linker-over-dht-owner-decisions.md.
- Touch ONLY the files listed for L0 in section 12. If you need another file, stop and report why.

**Rules:**
- Write tests first and show each FAILS before its fix. Give at least one mutation per core property.
- Time advances by ticks in the core.
- CLJC portability:
  - :cljd goes FIRST in mixed reader conditionals.
  - No array-map. No cross-namespace #'private.
  - Anchor regexes by hand.
  - Refusal helpers return the error object.
- Crypto comes from host primitives or vetted libraries only; never hand-roll it.
- Owner invariants:
  - plain Clojure and yin.repl share one path via host functions;
  - dao.stream is P2P with no privileged node;
  - apply stays independent of rpc;
  - derive, don't persist;
  - dao.jing is passive.
- Run every check in the FOREGROUND and poll long lanes to their verdict before finishing:
  - kondo on the changed files
  - bb test:clj
  - bb test:cljs
  - bb build:yin-repl-peer
  - bb test:cljd (delete test/cljd-out first)
- If a sandbox blocks a lane, name the lane and the error. Never report an unseen result.

Report: collab/1790812000000-engineer-linker-L0.<model>.report.md, mapping each acceptance bullet to its evidence.
