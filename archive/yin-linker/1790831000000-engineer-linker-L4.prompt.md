# Brief: linker over dao.jing.dht — slice L4

Role: Engineer (docs/agents/roles/).
- Worktree: /Users/sto/workspace/datomworld-linker-l2 (worktree reused), branch linker-l4, from linker-l3 bd9bfae7 (L0 + L1 + L2 + L3).
- Do not stage or commit.

**Contract (binding):**
- docs/design/yin.vm.linker.dht.md: section 12, slice L4 (its files and every acceptance bullet), plus every section it cites.
- Owner decisions: collab/1790808000000-orchestrator-linker-over-dht-owner-decisions.md.
- Touch ONLY the files listed for L4 in section 12. If you need another file, stop and report why.

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

Report: collab/1790812000000-engineer-linker-L4.<model>.report.md, mapping each acceptance bullet to its evidence.

**Note from prior slices:** the ClojureDart EDN reader throws on whitespace right before a closing ] or }. A test helper whose body ends in `is` inside `when` fails to compile on Dart; end such helpers with an explicit nil.
**L4 owner decisions in force:**
- rows every round;
- a stable key loaded from a file, with no silent minting;
- same-address consensus resolves;
- automatic repair.
**Footprint:** use the §5.2 computation L0 built (yin.vm.linker.publish/footprint). If deriving a module from indexed code needs anything unavailable, stop and report; do not invent an empty default.
