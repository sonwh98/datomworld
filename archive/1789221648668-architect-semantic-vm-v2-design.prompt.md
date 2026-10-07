Created-GMT: 2026-09-12 14:00:48 GMT
Created-Local: 2026-09-12 21:00:48 +0700 (+07)
Coding-Agent: claude
Session-ID: 8dbd68cd-b008-4da3-a9e9-545cf94603e8

# Task: Architectural Design for Linear Executable Datom Semantic VM on DaoStream v2
Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-12 21:00:48 +0700 | Status: active | Rationale: Lead System Architect per team.md; primary owner of architectural invariants, subsystem boundaries, stream contracts, and VM lineage.

**Architecture and Design Task. Plan and specify the return of `yin.vm.semantic` on `dao.stream`.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`.
Write your complete architectural specification and findings to:
`collab/1789221648668-architect-semantic-vm-v2-design.claude-fable-5-1.findings.md`.
Change no other repository files.

---

## 1. The Problem & Context

The user has specified:
> "walking the ast is slow. the purpose of the semantic vm is to have a linear executable datom so that its fast. can you bring back the semantic.cljc but implement it with the dao.stream design? yin.vm implementations are interpreters of dao.stream. you should delegate the design of the new semantic.cljc to the @[docs/agents/roles/architect.md]"

### Background:
1. **The Historical Semantic VM (`yin.vm.semantic`)**:
   - The historical v1 `semantic.cljc` (deleted in `d8b27a5`) interpreted datom graphs by dynamic entity ID lookups across datom sets (`[e a v t m]`), scanning attributes dynamically and walking graph nodes with explicit continuation stacks.
   - It was deleted during the v1 VM cleanup because all v1 VM backends were entangled with v1 `dao.stream` and the obsolete macro engine.
   - However, the foundational vision of the Universal AST and datom computing was "RISC for semantics": low-level, flat, explicit datoms that express high-level semantics.
2. **The Performance Bottleneck in `ast-walker`**:
   - `yin.vm.ast-walker` evaluates programs by recursively traversing nested Clojure AST maps (or constructing continuation frames for operands, operator, application, conditionals, etc.).
   - Tree walking incurs heavy pointer chasing, structural map destructuring, and persistent allocation per evaluation step.
3. **The Core Thesis of the New Semantic VM**:
   - Programs are lowered into a **linear stream of executable datoms** (sequential datom triples/5-tuples).
   - The Semantic VM is an **interpreter of `dao.stream`** that consumes this linear stream sequentially (similar to an instruction stream), achieving execution speed and cache friendliness while preserving the full introspectability, content-addressability, and provenance of datoms.
   - Crucially, this interpreter must be built from the ground up on `dao.stream` design principles and the foundational axioms of `datom.world`.

---

## 2. Read First

- `docs/design/datom.world.md` — Foundational axioms (Everything is a Stream; Interpretation Creates Semantics; Code and State are Datoms; Everything is a Continuation) and the 6 non-negotiable invariants.
- `docs/design/dao.stream.md` — The DaoStream v2 specification: non-blocking `next`, total outcome maps (`ok`, `end`, `gap`, `closed`, `blocked`, `full`), opaque cursors, host-supplied stream constructors (`:make-stream`), and stream-based FFI.
- `docs/design/yin.vm.streams-all-the-way-down.md` — VM streams, interpreters, and host boundaries.
- `docs/design/yin.vm.divergence-register.md` — The current v2 VM conventions and invariants.
- `docs/cesk-space-optimization.md` — Section 6 ("Endgame: projection, not interpretation ... executes compiled code fast and emits the vocabulary as a datom stream").
- `src/cljc/yin/vm.cljc` — The `IVM` protocol (`step`, `run`, `eval`, `reset`, `halted?`, `blocked?`, `value`).
- `src/cljc/yin/vm/engine.cljc` — Shared execution machinery, variable resolution, and outcome handling.
- `src/cljc/yin/vm/ffi.cljc` — The explicit host-side `dao.stream.apply` bridge.
- `src/cljc/yin/vm/stream_observer.cljc` — Observation of incoming program streams.
- Historical `semantic.cljc` (accessible via `git show d8b27a5~1:src/cljc/yin/vm/semantic.cljc`) and `public/chp/blog/universal-ast-vs-assembly.blog` / `semantic-bytecode-benchmarks.blog` for architectural lineage.

---

## 3. Required Deliverable & Architectural Evaluation

Produce a comprehensive, rigorous architectural design document for the new `yin.vm.semantic` on `dao.stream`. Address the following sections with exact technical specifications:

### §1. Foundational Axioms & Invariant Compliance
- How does the linear executable datom stream honor:
  1. *Everything is a Stream*: Code is a linear stream of datoms; execution is stream consumption.
  2. *Interpretation Creates Semantics*: The datoms are syntax; the semantic VM is an explicit interpreter.
  3. *Code and State are Datoms*: Both the executable instruction datoms and execution state/traces are representable as datoms.
  4. *Everything is a Continuation*: Pausing, serialization, and stream travel.
- Strict adherence to the 6 Non-Negotiable Invariants (no hidden globals, no implicit control flow, no callbacks, no shared mutable state, no layer collapsing, no assumed graphs).

### §2. The Linear Executable Datom Specification
- Define the schema and structure of the executable datoms:
  - What is the tuple format? (e.g. `[entity attribute value]` or 5-tuple `[e a v t m]`).
  - How is linearity / sequential order represented? (e.g., sequential stream offsets, monotonic entity ordering, explicit `:code/op`, `:code/arg`, `:code/target`, `:code/next`).
  - What are the core semantic primitives? (`:literal`, `:load-var`, `:bind-var`, `:lambda-def`, `:call`, `:jump`, `:jump-if`, `:return`, `:stream-op`, `:ffi-call`).
  - How do constants and literals live in the stream?

### §3. The Semantic VM as a DaoStream v2 Interpreter
- The interpreter architecture:
  - How does the VM consume the stream? (Stream handle, opaque cursor, step loop).
  - How are control flow and branching handled over a stream?
    - Can a stream seek, or is branching modeled via cursor jump, indexed block streams, or a segmented ringbuffer? Reconcile with DaoStream v2's rule that cursors are opaque and streams are append-only.
  - How does stepping interact with `dao.stream/next` outcomes (`ok`, `blocked`, `end`, `gap`, `closed`)?
  - How does FFI and external effects interface via `dao.stream.apply` (`put-request!`, `put-response!`, `dispatch-request`)?
  - How does program observation interface with `yin.vm.stream-observer`?

### §4. CESK Machine Semantics on Linear Datoms
- Formulate the $\langle C, E, S, K \rangle$ transition function:
  - **Control ($C$)**: Current stream cursor / instruction datom.
  - **Environment ($E$)**: Lexical scope bindings (stack frames or activation records).
  - **Store ($S$)**: Mutable cells / stream handles / external resources.
  - **Continuation ($K$)**: Return stack / continuation frames when function calls occur.
- Provide concrete state transition equations: $\langle C, E, S, K \rangle \longrightarrow \langle C', E', S', K' \rangle$ for the primary instructions.

### §5. Lowering / Compilation Pipeline (Universal AST $\to$ Linear Datoms)
- How does source code (Clojure, Python, PHP via Yang, or Universal AST) compile / linearize into the executable datom stream?
- Is compilation ahead-of-time, just-in-time, or streaming?
- How are nested AST trees flattened into a topological or linear execution sequence?

### §6. Performance Architecture: Why & How It Outperforms AST-Walking
- Provide a rigorous performance comparison:
  - Traversal mechanics: Flat array/buffer iteration vs. recursive pointer chasing and tree destructuring.
  - Memory & Allocation: Hot-loop registers (`ctrl-tag`, `ctrl-data`) vs persistent map allocations.
  - Cache locality: Sequential stream blocks vs fragmented heap AST nodes.
  - Comparison with traditional bytecode: How does linear datom execution achieve near-bytecode speeds while preserving queryability and semantic transparency?

### §7. System Integration & Coexistence with `yin.vm`
- Implementation of the `IVM` protocol (`yin.vm`).
- Relationship with `yin.vm.ast-walker`: Do they coexist as alternative evaluators selected by `:evaluator-type` (e.g. `:ast-walker` vs `:semantic`), or does `:semantic` become the default high-performance engine?
- Impact on `yin.repl`, postgraphics, and CLI runners.
- Cross-platform parity across Clojure (JVM), ClojureScript (Node/Browser), and ClojureDart (Flutter).

### §8. Phased Implementation Roadmap
- Concrete phases (Phase 0: Spec & Lowering Contract, Phase 1: Core Linear Datom Stream Interpreter, Phase 2: Lowering Compiler, Phase 3: FFI & Stream Integration, Phase 4: Benchmarking & Parity Suite).
- Explicit deliverables, target files (`src/cljc/yin/vm/semantic.cljc`, `src/cljc/yang/linearize.cljc`, etc.), test requirements, and acceptance criteria.

---

## 4. Output Formatting Rules

Begin your output in `collab/1789221648668-architect-semantic-vm-v2-design.claude-fable-5-1.findings.md` exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 8dbd68cd-b008-4da3-a9e9-545cf94603e8
```
Follow with the structured architectural design, technical invariants, and implementation plan.
