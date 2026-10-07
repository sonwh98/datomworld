# Architect: L5 sign-off and whole-epic review — linker over dao.jing.dht

Role: Architect (read-only).
- Worktree: /Users/sto/workspace/datomworld-linker-l2, branch linker-l5.
- The epic is every commit from master c66809fa to HEAD: `git log c66809fa..HEAD` and `git diff c66809fa`, plus L5's uncommitted change (`git diff`, `git status`):
  - docs a1f41db3
  - L0 e5392305, L1 3c76c063 (merged)
  - L2 79c1e55d, L3 bd9bfae7, L4 e5b856b9
- Contract: docs/design/yin.vm.linker.dht.md.
- Owner decisions: collab/1790808000000-orchestrator-linker-over-dht-owner-decisions.md.
- Owner invariants:
  - plain Clojure and yin.repl share one path via host functions;
  - dao.stream is P2P with no privileged node;
  - derive, don't persist;
  - dao.jing is passive;
  - code as datoms;
  - dao.stream.apply independent of rpc.

Part A: sign-off for L5 (uncommitted).
- Report: collab/1790837000000-engineer-linker-L5.claude-opus-5-5.report.md.
- Verify every §12 L5 bullet.
- RULE on the engineer's notes:
  - there is no Dart reader process; the Dart leg runs in process;
  - the §9 response-row conversion is private in yin.repl.link. Should it be public so plain Clojure gets the same shapes? (This bears on the owner invariant "same path".)
  - the process test runtime.

Part B: whole-epic review. L1, L2 and L3 were signed off by gemini-3.1-pro-high while you were capped; L0 by fable; L4 by you. Review the epic as one change for:
- cross-slice integration defects;
- contract drift;
- any acceptance property no test actually guards;
- bounds;
- security of the signed-name path:
  - the declared principals come only from composition;
  - forged, ambiguous and retracted envelopes;
  - sequence handling;
- portability traps;
- owner-invariant violations.
Look especially hard at the gemini-signed slices.

End with:
- a verdict for L5 (SIGN-OFF GRANTED or WITHHELD);
- a verdict for the EPIC as a whole, i.e. ready to land on master (GRANTED or WITHHELD);
- findings as a Severity | file:line | issue | fix | slice table;
- any owner questions.
