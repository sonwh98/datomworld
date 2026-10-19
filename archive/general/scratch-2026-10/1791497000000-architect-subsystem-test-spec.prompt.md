Created-GMT: 2026-10-09 05:01:00 GMT
Created-Local: 2026-10-09 12:01:00 ICT

# Task: Subsystem-First Tri-Host Test Architecture Specification & Migration Plan

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-09 12:01:00 ICT | Status: active | Rationale: Architectural specification and testing boundary decomposition across multi-host targets.

Context & Problem Statement:
Currently, test verification across branches and worktrees relies primarily on monolithic whole-repository runs:
`bb test:clj && npm ci && bb test:cljs && bb test:cljd`
which runs ~3,800+ tests on each host (JVM, Node, Dart), spanning all repo subsystems (Yin VM/UCF, Yang Python/PHP, DAO Stream/BTree/DHT, and DatomWorld Demo/Voxel).

When engineers work on scoped feature tracks (Track A: Yang Python, Track B: DAO Stream, Track C: Yin UCF), running the entire repo suite creates unnecessary CPU contention, latency, and feedback delays during local iteration.

We need an Architectural Specification and Execution Plan to rethink test execution to be **grouped by subsystem first**, while preserving our foundational multi-host invariants.

Read first:
- docs/design/datom.world.md
- docs/agents/roles/architect.md
- docs/agents/malleability.md
- bb.edn
- deps.edn
- src/dev/cljd_agg.clj
- src/dev/affected.clj

Core Architectural Questions to Answer in the Specification:
1. **Subsystem Taxonomy & Boundaries**:
   - What are the formal canonical subsystems (e.g. `yang.python`, `dao.stream`, `dao.data`, `yin.vm.ucf`, `demo`) and their corresponding source / test namespace boundaries?
   - How should cross-subsystem dependencies (e.g. `dao.stream` utilizing `yin.vm` or `dao.data`) be classified and tested?

2. **Subsystem-First Host Matrix**:
   - How should a subsystem test task be parameterized across hosts? (e.g., `bb test:subsystem <name>`, or `bb test:<subsystem>[:host]`)
   - How do we handle host-specific prerequisites per subsystem? (e.g., ANTLR generation `gen:python-antlr` for Yang Python; Node REPL build `build:yin-repl-node` or Dart peer build `build:yin-repl-peer` for DAO Stream / Yin REPL)?

3. **Invariants & Landing Gates**:
   - How does subsystem-scoped testing interact with our non-negotiable landing rules?
   - Exactly when is a subsystem-scoped test suite sufficient (e.g. worktree local development, PR review gates), versus when the full whole-repo tri-host suite (`bb test`) remains mandatory (e.g. final master merge gate)?

4. **Integration with `src/dev/affected.clj`**:
   - How does this proposed subsystem model complement or relate to the existing change-detection mechanism in `src/dev/affected.clj`?

Deliverable:
Produce a comprehensive architectural design artifact at:
`docs/design/test.subsystems.md`
and write your architect ruling / findings at:
`collab/1791497000000-architect-subsystem-test-spec.claude-fable-5-1.findings.md`

Do NOT implement any changes to `bb.edn`, code, or build scripts. This is strictly an architectural planning and design specification task.
