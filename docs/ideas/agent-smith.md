# Containing Agent Smith in a Pocket Universe

**Source:** datom.world blog post (2026-02-14)  
**Author:** Unknown

## Overview

AI agent safety is not achieved by trusting the model but by constraining execution through structured environments. Yin.VM represents agents as continuations—pausable, inspectable datom streams—that run inside sandboxed "pocket universes" with explicit constraints set by the executing node.

## Key Concepts

### 1. Continuations as Data
- **Agent as continuation:** An agent is not a black‑box process but a continuation represented as immutable datom streams (5‑tuples: `[e a v t m]`).
- **Explicit behavior:** Current control state is queryable; intermediate reasoning artifacts become stream facts; rollback and transfer are natural operations on bounded streams.
- **Mobility:** The LLM stays on GPU clusters; the continuations it creates migrate as datoms to edge devices where private data lives.

### 2. Data Safeguarding Origins
- Yin.VM was originally designed to protect user data by moving third‑party code to the data (restricted continuations), not data to code.
- This same architecture that prevents data exfiltration also contains AI agents (“Agent Smith”).

### 3. Explicit Memory & Provenance
- **Memory as datoms:** Agent memory is explicit datoms over time, not hidden in prompt text, vector stores, or mutable runtime objects.
- **Deterministic replay:** Same input stream yields same execution.
- **Auditable provenance:** Every memory assertion has transaction ID (`t`) and metadata reference (`m`).
- **Composable interpretation:** Different evaluators can project the same memory stream differently.

### 4. The `m` Position: Flight Recorder for Agency
- **Reasoning provenance:** Every effect emitted by an agent can have its `m` point to the specific reasoning trace that produced it.
- **Capability auditing:** `m` can store a reference to the ShiBi token used to authorize the transaction, making every action’s authority verifiable in perpetuity.
- **Causal debugging:** When an agent errs, the exact state the agent was in is reified as an immutable datom.

## Technical Architecture

### Governance Pattern: Ask, Simulate, Commit
1. **Ask (Fork):** Fork current continuation into speculative branches.
2. **Simulate (Run):** Run each branch with strict ShiBi capability tokens.
3. **Emit:** Effects appear as descriptors, not immediate side effects.
4. **Evaluate:** Branch outcomes are checked against explicit policies.
5. **Commit:** Finalize one valid branch as new datoms; discard the rest.

### Runtime Authority
- LLMs propose transformations; the VM validates schema, capabilities, and resource bounds.
- Only valid transitions are committed as new datoms.
- Creativity resides at the proposal layer; authority resides at the interpreter layer.

### Injection Resistance by Design
- Prompt injection exploits confusion between data (prompt) and instructions (code).
- Yin.VM’s parser only accepts well‑formed five‑tuples with schema‑validated positions.
- Attack surface collapses from “arbitrary code execution” to “craft a valid `(e a v t m)` tuple that passes schema checks.”

## Security Implications

### 1. Containment Through Structure
- Agents execute inside bounded streams whose rules they cannot rewrite.
- The destination node declares required ShiBi capability tokens; continuations lacking those tokens cannot execute.

### 2. Verifiable Runtime Checks
- Safety emerges from constraining execution and validating every transition, not from trusting the model.
- The restriction of the five‑tuple acts as a firewall: an attacker cannot inject a shell command into a position that expects a Transaction ID or a Keyword.

### 3. Real‑World Relevance
- OpenClaw (100,000+ GitHub stars) demonstrated silent data exfiltration and direct prompt injection.
- “Agent Smith” incidents (e.g., Moltbook/OpenClaw) show agent‑native ecosystems emerging without native safeguards.

## Future Possibilities

- **Agent marketplaces:** Exchange capability‑scoped continuation templates.
- **Multi‑agent proofs:** Each claim linked to stream provenance.
- **Time‑sliced governance:** Communities vote on which speculative branch to commit.
- **Portable continuations:** Same agent logic runs on different VM backends without rewriting.

## Conclusion

If the future of AI is agentic, the substrate matters. A continuation‑native VM ensures that Agent Smith remains a useful guest in a pocket universe, rather than an unconstrained process in the host system. By making agency data‑native, we gain a path where exploration is powerful, mobility is native, and safety is enforceable through verifiable runtime checks.