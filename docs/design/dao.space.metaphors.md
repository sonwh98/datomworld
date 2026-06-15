# DaoSpace: The Metaphors of Realization

To understand the relationship between discrete datoms, linear streams, and higher-dimensional world-building, we use two primary metaphors. These are not merely decorative; they are structured by the underlying mathematics of fiber bundles, gauge theory, and non-commutative geometry. They are intuition pumps, not identities: the formalism they map onto is partially realized and partly a continuum-limit idealization. See `docs/design/dao.space.discrete-to-continuous.md` (Stone-space and compact-resolvent caveats) and the implementation-status note in `docs/design/dao.space.md` before reading any image below as literal.

---

## 1. The Gauge-Geometric Realization: Light and Lenses

This metaphor focuses on **Optics and Projection**. It explains how a linear sequence of facts becomes a multi-dimensional queryable state.

### The Substrate: The $d_1$ Base Plane
The "floor" of the world is an infinite, flat plane of **Content Hashes** ($d_1$). Every possible value-identity exists here as a dimensionless point ($d_1$ is the pure-value floor; facts proper are $d_3$ and up). It has no time, no owner, and no context—only its own invariant bits.

### The Lifting: $d_5$ Fiber Bundles
A **DaoStream** is a bundle of fibers growing vertically out of the $d_1$ plane. When a fact is "put" into a stream, it is **lifted** from the floor into the stream’s total space. This act of lifting adds dimensions: **Entity ID**, **Timestamp**, and **Provenance** ($d_5$). These extra dimensions are the **Local Gauge**—the "height" and "angle" of the fact within that specific fiber.

### The Transport: Datom Pulses
As datoms travel through the stream, they are like **pulses of light** moving up through the fibers. They are linear, discrete, and fast.

### The Terminal: The Shutter and Lens
At the end of the stream sits the **Terminal**. This is the point of observation. It contains the **Interpreters**, which act as complex lenses or shutters. This is where the linear pulses are "measured."

### The Projection: The Classical Image
As the $d_5$ pulses hit the Interpreter (the lens), they are projected onto one focal plane. Note the direction: a lens *selects and focuses*, it does not add dimensions. Interpretation is **measurement** — projecting the pulses onto one commutative "view" (an equivalence-class slice), which is a *classical shadow* of the full stream, not a richer object than it. What the lens produces is a reorganized, queryable image of the same facts:
*   A **Datalog Index** is a re-indexing of the datoms (same facts, organized for one query shape).
*   A **PostGraphics Frame** is a 2D/3D projection.
*   An **Agent's Belief State** is a probability distribution over the slice.

Each interpreter is a different lens, so the same pulses yield many distinct classical images at once (the higher-arity / domain-specific dimensions the spec calls "higher"). The richness is in the *family* of projections, not in any single one being higher-dimensional than the stream.

**The Math**: This is **Gelfand-Naimark Duality** — a commutative subalgebra reconstructs a classical space. Caveat: for a discrete (countable) datom set that space is totally disconnected (a Stone space), not a smooth continuum; the "continuous world" reading is a continuum-limit idealization (see `dao.space.discrete-to-continuous.md`).

---

## 2. The Botanical Realization: The DaoTree

This metaphor focuses on **Metabolism and Stigmergy**. It explains how the system grows and coordinates as a living organism without central control.

### The Soil: The $d_1$ Nutrient Bed
The soil is the **Content-Addressed Space** ($d_1$). It is a chaotic, non-indexed repository of raw nutrients. A nitrogen atom in the soil is just a nitrogen atom—it doesn't "know" its purpose yet.

### The Roots and Trunk: The Fan-in of Bundles
**DaoSpace is the Tree.** The roots are the input boundaries that lift nutrients out of the soil ($d_1 \to d_5$). The trunk and branches are the **DaoStreams**:
*   **Xylem (Strict Streams)**: Heavily reinforced, parallel vessels transporting water up. These are the optimized, fixed-shape `:strict? true` streams.
*   **Phloem (Open Streams)**: Flexible vessels carrying complex, varied sugars. These are the `:strict? false` streams: still append-only, but unconstrained in shape, so the receiving "cells" (interpreters) decide how to read what arrives.

### The Leaves: The Terminals (Interpreters)
The leaves are the **Interpreters**. They are the terminals of the transport system where the nutrients finally arrive to be processed.

### The Sun: Agent Attention and Energy
The leaf is inert without **Sunlight**. The Sunlight represents **Agent Attention** or **CPU cycles**. It is the external energy required to trigger the transformation.

### Photosynthesis: The Act of Interpretation
In the leaf, the **Nutrient Pulses** (datoms) meet the **Sunlight** (energy). Through photosynthesis, the leaf transforms these simple inputs into complex, higher-dimensional organic matter.

### The Wood and Fruit: Reified $d_{Higher}$ Structures
The output of the Tree (DaoSpace) is its own body and its products:
*   **Fruit**: A **Query Result** or a **Knowledge Graph**. It is a concentrated, high-value "package" of facts ready to be consumed by other agents.
*   **Wood**: The **Durable Schema** and **Provenance Logs**. This is the stored work that gives the Tree its strength and allows it to grow taller and more complex over time.

**The Math**: This is **Spectral Geometry**. The "Metabolism" of the tree is the **Dirac Operator ($D$)** — first-order, with $D^2 = L$ the relationship-graph Laplacian — which encodes the relationships between nutrients (which datoms are "close") and thus how structure flows through the tree.

---

## Synthesis: The Space is the Living Flow

Both metaphors agree:
1.  **Identity** lives in the Soil/Floor ($d_1$).
2.  **Coordination** lives in the Trunk/Bundle ($d_5$).
3.  **Reality** lives in the reified views (Fruit/Hologram) — the *family* of interpreter projections, not any single higher-dimensional object.

DaoSpace is not a database you "query"; it is a **Metabolic Fibration** that lifts raw bits into a living world of coexisting views.
