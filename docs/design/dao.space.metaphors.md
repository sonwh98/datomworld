# DaoSpace: The Metaphors of Realization

To understand the relationship between discrete datoms, linear streams, and higher-dimensional world-building, we use two primary metaphors. These are not merely decorative; they are structured by the underlying mathematics of fiber bundles, gauge theory, and non-commutative geometry. They are intuition pumps, not identities: the formalism they map onto is partially realized and partly a continuum-limit idealization. See `docs/design/dao.space.discrete-to-continuous.md` (Stone-space and compact-resolvent caveats) and the "Implementation status" and "v1 Scope" sections of `docs/design/dao.space.md` before reading any image below as literal.

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
As the $d_5$ pulses hit the Interpreter (the lens), they are projected onto one focal plane. Note the direction: a lens *selects and focuses*, it does not add dimensions. Interpretation is **measurement** — projecting the pulses onto one commutative "view" (an equivalence-class slice), which is a *classical shadow* of the full stream, not a richer object than it. What the lens (the interpreter's embedded `dao.space.query`) produces is a reorganized, queryable image of the same facts:
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
**The Tree is datom.world.** The roots, trunk, and branches are the **DaoStreams** (`dao.stream`): the roots are the input boundaries that lift nutrients out of the soil ($d_1 \to d_5$), and the vessels carry datoms upward. This is transport, not storage: it conducts sap, it does not photosynthesize. Two vessel types:
*   **Xylem (Strict Streams)**: Heavily reinforced, parallel vessels transporting water up. These are the optimized, fixed-shape `:strict? true` streams.
*   **Phloem (Open Streams)**: Flexible vessels carrying complex, varied sugars. These are the `:strict? false` streams: still append-only, but unconstrained in shape, so the receiving "cells" (interpreters) decide how to read what arrives.

Where the sap settles and is held is the **heartwood**: `dao.jing`, the storage boundary. It is the durable, content-addressed repository the vessels feed; it holds datoms, it does not carry or interpret them.

### The Leaves: The Terminals (Interpreters)
The leaves are the **Interpreters**. They are the terminals where the nutrients finally arrive to be processed. Each leaf embeds the **query library (`dao.space.query`)** — its chloroplast. The chloroplast is what makes a leaf a tuple space (`dao.space`); it is *not* part of `dao.jing` (storage). The division of labor: `dao.stream` carries datoms, `dao.jing` holds them, and the leaf's chloroplast indexes and queries them into living views.

### The Sun: Agent Attention and Energy
The leaf is inert without **Sunlight**. The Sunlight represents **Agent Attention** or **CPU cycles**. It is the external energy required to trigger the transformation.

### Photosynthesis: The Act of Interpretation
In the leaf, the **Nutrient Pulses** (datoms) meet the **Sunlight** (energy). Through photosynthesis — the query library folding datoms into an index and answering — the leaf transforms these simple inputs into complex, higher-dimensional organic matter.

### The Wood and Fruit: Reified $d_{Higher}$ Structures
The output of the Tree is its own body and its products:
*   **Fruit**: A **Query Result** or a **Knowledge Graph**. It is a concentrated, high-value "package" of facts ready to be consumed by other agents.
*   **Wood**: The **Durable Schema** and **Provenance Logs** held in `dao.jing`. This is the stored work that gives the Tree its strength and allows it to grow taller and more complex over time.

**The Math**: This is **Spectral Geometry**. The "Metabolism" of the tree is the **Dirac Operator ($D$)** — first-order, with $D^2 = L$ the relationship-graph Laplacian — which encodes the relationships between nutrients (which datoms are "close") and thus how structure flows through the tree.

---

## Synthesis: The Living Flow

Both metaphors agree, and the three layers line up:
1.  **Identity** lives in the Soil/Floor ($d_1$) — the content-addressed base.
2.  **Transport and storage**: the vessels (`dao.stream`) carry $d_5$ datoms; the heartwood (`dao.jing`) holds them.
3.  **Coordination and Reality** live in the reified views the leaves photosynthesize — the tuple space (`dao.space`), a *family* of interpreter projections, not any single higher-dimensional object.

`dao.jing` is not a database you "query" — it is the heartwood, the storage substrate where datoms accrete. `dao.stream` is the vessels that carry them; `dao.space` is the metabolism of the leaves. The **system** is the **Metabolic Fibration**: streams lift raw bits into $d_5$, the heartwood holds them, and the leaves' chloroplasts (`dao.space.query`) project them into a living world of coexisting views.
