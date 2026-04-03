---
name: Problem domain vocabulary for naming
description: Vocabulary from biology, economics, physics, and philosophy to guide naming and enable stigmergic emergence
type: project
---

# VOCABULARY: NAMING FROM PROBLEM DOMAIN

Datom.World's problem domain is at the intersection of biology, economics, physics, and philosophy.

The goal is for **stigmergy to emerge from sharing of data**: agents coordinate indirectly through environmental modification (shared streams), not through top-down commands or explicit messaging.

Think ant colonies: individual ants don't know the colony's plan, but pheromone trails on the ground coordinate behavior. That's stigmergic coordination emerging from simple data sharing.

## Vocabulary by Domain

### Biology

Concepts that describe self-organizing systems, adaptation, and emergent behavior:

- **Cell/Organism**: A bounded, autonomous agent with internal state and a membrane (interface) separating it from the environment.
- **Metabolism**: The transformation of resources into work and new resources. Data as fuel, computation as transformation.
- **Adaptation**: Response to environmental change. Code that evolves fitness through feedback.
- **Evolution**: Change over time guided by selection pressure.
- **Reproduction**: Replication of structure. Code as DNA—immutable, heritable, mobile.
- **Symbiosis**: Mutual benefit between independent agents. Agents that enhance each other's fitness through cooperation.
- **Feedback**: Closed loops of cause and effect. Causality made explicit.
- **Homeostasis**: Self-regulation. Maintaining stability within bounds despite external change.
- **Genome/Phenotype**: Genotype (code/structure) vs. phenotype (expression/behavior). AST (genotype) vs. execution (phenotype).
- **Mutation**: Random change. Errors that sometimes improve fitness.
- **Niche**: The role an organism plays in its ecosystem. What a module does in the system.
- **Predator/Prey**: Asymmetric relationships. Data flows from producer to consumer.
- **Pheromone**: Chemical signals that coordinate behavior without centralized control. Streams as coordination medium.

### Economics

Concepts that describe incentives, exchange, and resource flow:

- **Exchange**: Trade of value. Data as tradeable commodity.
- **Ledger/Settlement**: Recording of transactions. Datoms as immutable records of exchange.
- **Incentive**: What drives an agent to act. What makes a piece of code useful.
- **Scarcity**: Limited resources. Computational limits, bandwidth, attention.
- **Value**: Worth in exchange. How useful data is to an interpreter.
- **Arbitrage**: Exploiting differences in value across contexts. Reinterpreting data for new purposes.
- **Market**: A place where exchange happens. Streams as markets for data.
- **Capital**: Accumulated resources that enable future work. Code, cached indexes, compiled bytecode.
- **Debt**: Obligations. Dependencies between modules.
- **Currency**: Medium of exchange. Datoms as universal medium.
- **Dividend**: Reward for holding capital. Benefit of maintaining shared infrastructure.
- **Speculation**: Predicting future value. Compiling ahead vs. interpreting on-demand.

### Physics

Concepts that describe energy, flow, and equilibrium:

- **Entropy**: Measure of disorder. System entropy vs. module entropy.
- **Gradient**: Direction of change. Potential difference that drives flow.
- **Field**: Distributed influence. Shared state as field, not point.
- **Wave/Particle**: Dual nature. Datoms as both particles (discrete facts) and waves (distributed patterns).
- **Resonance**: Amplification through feedback. Ideas that propagate through the system.
- **Equilibrium**: Balance point. System state where no agent has incentive to change.
- **Potential**: Capacity to do work. Computation waiting to happen.
- **Flux**: Flow of something through space. Data flow through the system.
- **Frequency**: Rate of oscillation. Transaction frequency, message rate.
- **Interference**: Waves meeting and combining. Agents coordinating through shared data.
- **Propagation**: Spread of influence. Ideas moving through the network.

### Philosophy

Concepts that describe causality, emergence, and meaning:

- **Causality**: Explicit connection between cause and effect. No hidden mechanisms.
- **Emergence**: Complex behavior arising from simple rules and local interactions.
- **Intention**: Purpose or goal. What an agent is trying to achieve.
- **Autonomy**: Self-governance. Agents make local decisions without central authority.
- **Agency**: Capacity to act. Agents as first-class, mobile computation.
- **Ethics**: Values and principles. What makes a system trustworthy.
- **Interpretation**: Extracting meaning from data. Same data, different observers, different truths.
- **Perspective**: Viewpoint from which meaning is constructed. Locality of interpretation.
- **Truth/Falsity**: Correspondence to reality. Datoms as facts about what happened.
- **Knowledge**: Justified true belief. Queries as ways of knowing.
- **Ontology**: What exists. What entities and relationships the system recognizes.

## Naming Guidelines

### Do

- **Use domain vocabulary**: Names that reflect biological adaptation, economic exchange, physical flow, philosophical emergence.
- **Choose names that describe the function from the problem perspective**: What role does this play in the ecosystem?
- **Use metaphors that illuminate**: A name that makes the purpose obvious without documentation.
- **Consider the namespace as a niche**: What is this module's ecological role?

### Don't

- **Use solution domain terms**: Avoid `processor`, `handler`, `manager`, `service`, `util`, `helper`, `core`, `common`
- **Use generic names**: Avoid `data`, `info`, `thing`, `state`
- **Use implementation detail as the name**: The name should not reveal how, only why and what
- **Hide domain intent**: The name should make the problem domain visible

### Examples

**Poor (solution domain):**
- `stream-processor` — What does it do for the system?
- `event-handler` — Implementation detail, not purpose
- `core` — Meaningless; everything thinks it's core
- `utils` — Garden of miscellaneous functions with no coherent purpose

**Good (problem domain):**
- `pheromone-trail` — A stream that coordinates agent behavior
- `metabolic-exchanger` — Transforms input resources into output products
- `symbiont` — An agent that benefits from the ecosystem and contributes back
- `mutation-space` — Possible variations that evolution can explore
- `settlement-ledger` — Record of exchanges between agents
- `resonance-amplifier` — Mechanisms that strengthen coordination signals

## Stigmergy Through Data Sharing

Remember: the goal is for coordination to emerge from **shared data**, not from centralized commands.

- **No orchestrator**: No module that "knows" the overall plan
- **No callbacks**: No hidden control flow
- **Shared streams**: Data as the sole coordination medium
- **Interpreted locally**: Each agent extracts meaning for its own purposes
- **Feedback loops**: Effects become visible as new data, feeding back into decisions

When naming, ask: **"Does this name make it obvious how this module contributes to stigmergic coordination?"**

If the name is opaque or generic, it probably doesn't.
