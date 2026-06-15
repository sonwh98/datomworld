# DaoSpace Geometry for LLM Training and Inference

> The non-commutative geometry of DaoSpace (spectral triple (A, H, D)) lives on
> the **data graph** — the graph of relationships between datoms (shared entities,
> provenance, temporal proximity). This document explores how that geometry
> could inform LLM training and inference.
>
> **Important scope note.** The DaoSpace geometry operates on the data graph
> (relationships between datoms). Transformer mechanics operate on the
> **representation space** (learned weights acting on token embeddings). These
> are different mathematical objects. The analogies between them below are
> suggestive but are **not identities** — bridging data-graph structure to
> representation-space behavior is an open hypothesis, not a theorem. Sections
> are labeled by confidence level.
>
> **Operator convention.** Throughout this document:
> - **L** = the graph Laplacian (second-order, positive semidefinite). Used for
>   spectral gap, eigenvectors, spectral clustering.
> - **D** = a Dirac operator (first-order, self-adjoint) with D^2 = L. Required
>   for the Connes distance formula and the spectral triple (A, H, D). On a
>   graph, D is the off-diagonal operator on ell-squared(V) + ell-squared(E):
>   D = [[0, B], [B^T, 0]], where B is the |V|x|E| signed incidence matrix
>   (B(v,e) = +/-1 if v is an endpoint of e). Then D^2 = [[BB^T, 0], [0, B^TB]]
>   = [[L, 0], [0, L_edge]], so D^2 restricted to the vertex sector is L.
>   The commutator ||[D, f]|| is the Lipschitz seminorm (max edge-difference of
>   f), and the Connes distance recovers graph geodesic distance.
>
> Earlier drafts conflated D and L. They are not interchangeable: the Connes
> formula with L instead of D does not give shortest-path distance.

---

# Part I: Computable on the Data Graph (Established Mathematics)

These sections use standard spectral graph theory applied to the DaoSpace data
graph. The mathematics is real, established, and implementable today.

**Related work.** The individual techniques in Part I — graph Laplacian
eigendecomposition for clustering (Ng, Jordan, Weiss 2001; Belkin, Niyogi
2003), spectral data selection, graph-based retrieval (Microsoft GraphRAG) — are
established. The contribution of this document is not the techniques themselves
but the proposal to use the **DaoSpace data graph with a chosen relational
structure as the single unifying object** underneath all of them, so that data
curation, retrieval, and (speculatively) training signals all derive from one
operator.

---

## 1. Spectral Data Curation for Training

The graph Laplacian L of the training data relationship graph (documents
connected by shared entities, topics, provenance) has eigenvalues
0 = lambda_1 <= lambda_2 <= ... The spectral gap lambda_2 (Fiedler value)
measures connectivity:

- **Small lambda_2** (nearly disconnected data): the training corpus has clusters
  with weak semantic bridges.
- **Large lambda_2** (well-connected data): smooth semantic manifold.

The eigenvectors of L embed documents into R^k — this is **spectral embedding**,
the same mathematics as Laplacian eigenmaps, diffusion maps, and spectral
clustering (Ng, Jordan, Weiss 2001; Belkin, Niyogi 2003). Clustering in this
embedding reveals:

- Redundant documents (same cluster, deduplicate)
- Bridge documents (connect otherwise-separated clusters, prioritize for
  curriculum)
- Outliers (isolated nodes, may be noise)

**Concrete pipeline:**

1. Represent training documents as datoms in a DaoSpace
2. Build the relationship graph (shared entities, citations, lexical overlap)
3. Compute L = Deg - A_graph (graph Laplacian: Deg = degree matrix, A_graph = adjacency)
4. Use lambda_2 as a data connectivity metric before training starts
5. Use eigenvectors for spectral clustering; if desired, order curriculum from
   central clusters outward (though curriculum-learning efficacy is itself
   empirically contested — treat as a heuristic, not settled practice)

**Hypothesis (unproven):** small lambda_2 correlates with harder training and
worse generalization, because the model must bridge large semantic gaps. This is
plausible but not yet validated — label it as a research hypothesis, not a known
result.

**Compute cost:** eigendecomposition of an n x n graph Laplacian is O(n^3) for
the full spectrum, but the first k eigenvectors (all you need for clustering /
embedding) can be approximated via Lanczos iteration at O(k * nnz) cost, where
nnz = edge count (bounded average degree assumed; iteration count depends on
spectral gaps). For a million-document sparse corpus, this is tractable on a
single machine.

---

## 2. Spectral RAG (Retrieval via Graph Distance)

Current RAG uses neural embeddings for retrieval. An alternative: use
**graph geodesic or diffusion distance** on the DaoSpace relationship graph
directly. Two facts are "close" if they are connected by short paths through the
entity/attribute/provenance graph.

The simplest metric is shortest-path distance on the relationship graph. The
NCG generalization is the **Connes spectral distance**:

```
d(e1, e2) = sup { |f(e1) - f(e2)| : f in A, ||[D, f]|| <= 1 }
```

where D must be a **first-order Dirac operator** (D^2 = L). With this choice,
||[D, f]|| is the Lipschitz seminorm (max edge-difference of f), and the Connes
distance recovers graph geodesic distance. Using L (the Laplacian) instead of D
in this formula does **not** give shortest-path distance — the first-order
property of D is essential.

**For RAG inference:**

1. The query defines a subgraph of interest
2. Retrieval = finding nearest neighbors by graph distance
3. Multi-hop relationships are naturally incorporated (not just lexical
   similarity)

**Where this works cleanly:** structured knowledge domains where a real
relationship graph already exists (codebases, legal records, medical knowledge
graphs). In these domains the graph structure carries more signal than surface
text similarity.

**Where this needs help:** for unstructured text, you still need something to
**build the edges** (lexical overlap, co-occurrence statistics, or neural
embeddings). The graph distance replaces the embedding model only after the graph
exists. For pure text corpora, the savings over neural RAG may be marginal.

---

## 3. The Design Decision: What Graph?

The central design decision is the **relationship graph** itself — what edges
connect datoms, and with what weights. Given the graph, the Laplacian L is
determined, and a Dirac operator D (with D^2 = L) can be constructed (e.g., the
off-diagonal operator built from the signed incidence matrix; see the operator
convention above).

For LLM training data, the natural graph choices:

| Graph type | Edge: "these datoms are related because..." | L captures | Good for |
|---|---|---|---|
| Entity co-occurrence | They share entities | Factual structure | Factual knowledge |
| Provenance | They are linked by citation / derivation | Reasoning chains | Reasoning, source-tracking |
| Temporal | They are temporally proximate | Temporal structure | Temporal reasoning |
| Composite | Weighted union of the above | Combined structure | General |

A composite graph L = alpha*L_entity + beta*L_provenance + gamma*L_temporal is
itself a valid graph Laplacian (a nonnegative sum of graph Laplacians is the
Laplacian of the weighted union graph: zero row-sums and nonpositive off-diagonals
are preserved),
and its Dirac operator D (with D^2 = L, constructed from the combined incidence
matrix via the off-diagonal block form) can be derived.
The weights (alpha, beta, gamma) are a design lens: they control what kind of
relational structure the geometry captures. This is the same open question left
by the other DaoSpace design documents, restated concretely.

---

# Part II: Speculative — Data-Graph Structure Applied to Training/Inference

> The following sections propose ways that data-graph spectral structure could
> inform LLM architecture and training. These are **research hypotheses**, not
> established results. Each draws an analogy between a transformer mechanism
> and a data-graph geometric concept. The analogies are suggestive but
> unproven — bridging data-graph structure to representation-space behavior
> requires validation.

---

## 4. Attention and Measurement: An Analogy

The attention operation:

```
Attention(Q, K, V) = softmax(QK^T / sqrt(d)) * V
```

produces a row-stochastic matrix (each row is nonnegative and sums to 1). Each
row is a **probability distribution over positions** — a soft, query-dependent
selection. This is analogous to a classical measurement outcome distribution:
the query "measures" the key-value store and the attention weights are the
resulting probability over outcomes.

**What is true:** each row of the attention matrix is a convex combination weight
vector. The output is a weighted average of value vectors — a soft,
query-dependent averaging over a subspace.

**What is not true:** the attention matrix is not a density matrix (it is not
symmetric, not positive semidefinite, and its trace is not 1). It is not a
projection operator (attention is not idempotent: applying it twice does not
yield the same result). Standard attention is a **stochastic (Markovian) matrix**,
not a quantum one.

**When the quantum reading would apply:** a Hermitian (density-matrix-like)
operator requires a **symmetric kernel** — e.g. the self-attention special case
`Q = K`, giving `softmax(QQ^T/sqrt(d))` with a symmetric pre-softmax score (the
softmax normalization still breaks exact symmetry, but the underlying kernel is
symmetric). General `Q != K` attention is irreducibly asymmetric and classical.

**The analogy to DaoSpace interpreters:** an interpreter projects the stream onto
a commutative subalgebra, producing a classical view. Attention rows are
similarly "views" of the key-value store, weighted by query-key overlap. The
analogy suggests that multi-head attention provides multiple "views" of the same
input, like multiple interpreters slicing the same stream. But heads are learned
linear projections, not verified non-commuting subalgebras — the algebraic
structure is not checked.

### Transformer / spectral triple correspondence table (analogies, not identities)

| Transformer concept | Analogous to | Gap |
|---|---|---|
| Residual stream | Hilbert space H | Both are inner-product spaces, but of unrelated dimension and indexing: residual stream is R^d (model width, ~10^3), while H = ell-squared(E) is indexed by datoms (10^6+ or countably infinite). The gap widens under inspection. |
| Attention weights (per row) | Classical measurement distribution | Honest probability, not quantum density matrix |
| Multiple attention heads | Multiple interpreters | Heads are learned projections, not algebraic subalgebras |
| Layer norm + FFN | Suggested as analogous to D | FFN is a pointwise nonlinear MLP; D is a fixed self-adjoint operator on the data graph. These are different kinds of object. The correspondence is asserted, not derived. |
| Residual connection | Suggested as analogous to parallel transport | Residual is x + f(x); no connection is defined on the network. The analogy is decorative unless a connection is constructed. |
| Multi-layer composition | Suggested as analogous to holonomy | Layers are nonlinear, not unitary gauge transformations. Holonomy is not well-defined without a connection. |

**Design proposal (not a theorem):** if one were to **define** attention as a
function of L rather than as learned Q, K matrices — for example, using the
eigenvectors of L as attention bases — then attention would factorize along
independent spectral modes. This is a design proposal that assumes the conclusion
(redefining attention in terms of the data graph), not a derivation from existing
attention.

---

## 5. Training Dynamics: Graph Curvature as a Data-Structural Signal

The curvature F = [D, [D, .]] is a **fixed property of the data graph's Dirac
operator D**, not of the training process. It measures how non-commutative the
data-graph connection is. (Note: this uses the first-order Dirac D, not the
Laplacian L.)

**Hypothesis (unproven):** regions of the data graph with high curvature may
correlate with training instability (order-dependent learning, catastrophic
forgetting). The intuition is that high-curvature regions represent facts whose
relationships are "twisted" — learning one changes the optimal representation of
another.

**What would need to be shown:** that data-graph curvature predicts
representation-space training dynamics. This requires demonstrating that the
fixed data-graph operator D has predictive power over the learned weight
trajectory, which is an empirical question. As stated, the curvature is a
property of the data, not of training — the link to training stability is a
hypothesis.

---

## 6. Expert Decomposition via Eigenspaces (MoE)

The spectral decomposition of L (the graph Laplacian) gives orthogonal
eigenspaces. Grouping eigenvalues into bands produces a partition of the data
graph into orthogonal clusters.

**What is true:** eigenspaces of a self-adjoint operator are mutually orthogonal.
Spectral clustering on L produces data-structurally principled clusters.

**Category gap:** MoE experts are nonlinear sub-networks, not orthogonal linear
subspaces. Eigenspace orthogonality on the data graph does not transfer to
expert orthogonality in the representation space. "Optimal specialization by
construction" overclaims — optimality is task-dependent and depends on
representation-space behavior, not just data-graph structure.

**Usable as:** a routing prior (initialize expert assignment from spectral
clusters), not a replacement for learned routing.

---

## 7. Knowledge Distillation and Spectral Truncation

**Category note:** spectral truncation (keeping top-k eigenvectors of L)
compresses the **data graph's relational structure**, not the model's function.
Standard knowledge distillation compresses the model's input-output behavior.
These are different operations.

**Distillation temperature:** in standard knowledge distillation, temperature
controls the softmax sharpness of the teacher's output logits (softening the
distribution). This is unrelated to the number of spectral modes retained in a
data-graph truncation. The two "temperatures" share a word but are different
parameters.

**Where the idea has traction:** if the goal is to compress the **retrieval
index** (not the model), spectral truncation of the data graph is principled —
keep the top-k relational modes and discard the rest. This reduces index size
while preserving the most important relational structure.

---

## 8. KV-Cache Management via Spectral Weight

The spectral weight of a token (its coefficient in the eigenbasis of L) could
serve as an importance score for cache eviction.

**Object mismatch:** sections 1-7 build the data graph over corpus
datoms/documents. KV-cache eviction operates on a **different graph**: tokens
within a single inference context. How 128k context tokens form a graph (what are
the edges — attention weights? co-occurrence? syntactic dependencies?) is itself
an open problem, and that edge construction is a cost beyond the eigendecomposition.

**Compute cost concern:** even after the context graph is built, computing
spectral weight requires the eigendecomposition of that graph, which is O(n^3)
for n tokens in the context. For a 128k-token context, this is far more expensive than the LRU or sliding-window
heuristics it would replace. Any practical implementation would need an
approximation (e.g., pre-computed eigenvectors for common context patterns, or a
learned proxy for spectral weight).

Importance-based KV-cache eviction is active research (H2O, StreamingLLM).
Spectral weight is a candidate scorer, but it must beat existing heuristic
scorers on **cost-adjusted** terms to matter.

---

## 9. Long-Context Drift: A Real Phenomenon, Speculative Framing

Long-context drift (the model losing track of early context) is a real,
documented phenomenon. The geometric framing — accumulated gauge transformations
causing the representation to "wrap" — is **speculative**:

- Transformer layers are nonlinear, not unitary gauge transformations.
- The product U_L * ... * U_1 is not well-defined without specifying what U_i is
  (there is no connection on the network).
- "Holonomy approximately identity implies coherence" is backwards:
  representations are *supposed* to transform across layers. Returning to the
  starting point would mean the layers did nothing.

The underlying intuition (that accumulated transformations across many layers can
degrade information from early tokens) is valid. The holonomy framing does not
add operationalizable structure beyond what attention-score analysis already
provides.

---

## 10. Scaling Laws from Spectral Gaps (Conjecture)

The spectral gap lambda_2 of the data graph Laplacian could predict training
difficulty before any gradient steps:

- **Conjecture:** models trained on data with small lambda_2 will require more
  steps to converge and generalize worse (at the same data size).
- **Scaling law form:** the optimal model size for a given dataset may depend on
  lambda_2, not just token count.
- **Data mixing:** when combining datasets, the spectral gap of the union
  predicts whether the combination will help or hurt. Two datasets with a large
  inter-dataset spectral gap will produce a fragmented training manifold.

These are falsifiable predictions, not established results.

---

# Summary

## What is real and computable today

- **Spectral data curation** (section 1): graph Laplacian eigendecomposition for
  data quality assessment, clustering, deduplication, curriculum ordering.
  Standard spectral graph theory; O(k * nnz) for sparse graphs via Lanczos.
- **Graph-distance RAG** (section 2): retrieval via graph proximity for domains
  where a structured relationship graph exists. Needs edge construction for
  unstructured text.
- **The graph design question** (section 3): choosing the relational structure
  (and thus L, with D derived) the geometry captures. The central open design
  decision.

## What is a research hypothesis

- Attention / measurement analogy (section 4): suggestive but requires bridging
  data-graph and representation-space geometries.
- Graph curvature as training signal (section 5): data-graph property that may
  predict training dynamics; unproven.
- Spectral MoE routing prior (section 6): spectral clustering as expert
  initialization, not replacement for learned routing.
- Spectral index compression (section 7): compresses the retrieval index, not
  the model.
- Spectral KV-cache eviction (section 8): candidate scorer with O(n^3) cost
  barrier.
- Holonomy framing of long-context drift (section 9): real phenomenon,
  speculative geometric framing.
- Spectral-gap scaling laws (section 10): falsifiable conjecture.

## The central assumption

The DaoSpace spectral triple (A, H, D) lives on the data graph. Transformer
mechanics live on the representation space. The hypothesis underlying sections
4-10 is that data-graph structure is predictive of representation-space behavior.
This is plausible (the model must learn the data's relational structure) but not
proven. Validating it — showing that spectral properties of the training graph
predict training dynamics, model quality, or inference behavior — is the research
program this document proposes.
