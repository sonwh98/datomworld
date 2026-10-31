# Architect: write the design doc for linker over dao.jing.dht (slice L-design)

Role: Lead System Architect. Write ONE design document; change no code.
- Target: docs/design/yin.vm.linker.dht.md (new). Add cross-references from yin.vm.linker.md, yin.repl.link-policy.md and dao.jing.dht.md, a few lines each.
- Work in a new worktree the orchestrator prepared: /Users/sto/workspace/datomworld-linker-dht, branch linker-dht, from master c66809fa.
- Do not stage or commit.

Consolidate these into one contract:
- Your lead design: collab/1790806000000-architect-linker-over-dht.claude-fable-5-1.findings.md
- gpt-6-sol's second opinion: collab/1790806000000-architect-linker-over-dht-second-opinion.gpt-6-sol.findings.md. Adopt its corrections:
  - an explicit module closure walker, with the outcomes missing / complete / invalid, covering all four formats and transitive requires;
  - re-check only on the relevant load's completion or failure;
  - canonical signed bytes, key encoding, the principal id and cross-host vectors specified before the adapters;
  - "a request budget is not a lease" stated;
  - the tightened acceptance per slice.
- Owner decisions: collab/1790808000000-orchestrator-linker-over-dht-owner-decisions.md. These are binding:
  - rows materialized every round;
  - a stable key loaded from a file, with loss and replacement behaviour defined;
  - Ed25519 on Dart now;
  - same-address consensus resolves.
- Owner invariants, from the brief collab/1790806000000-architect-linker-over-dht.prompt.md.

The doc must contain:
- the "today" facts;
- each ruling;
- data shapes: the envelope, the proof, signed bytes, the key file format, the load status and events, the failure vocabulary;
- the plain-Clojure API that yin.repl wraps through host functions;
- slices L0–L5 with files and acceptance;
- a deferrals section;
- a status line.

Keep it in the style of docs/design/dao.jing.dht.md (S0 contract).
Report briefly when it is done, with the path and a summary.
