# DaoSpace: From Discrete to Continuous

> The discrete set of datoms carries real geometric structure via the matrix
> mechanics → wave mechanics correspondence (Born-Jordan-von Neumann 1925-1927),
> the historical prototype of Connes' non-commutative geometry. What needs no
> extra structure — derivable directly from the algebra of relationships on the
> discrete set the system already has — is the **algebra, its spectral
> decomposition, and the graph (geodesic) metric**. The fuller "continuous space"
> apparatus (manifolds, PDEs, heat kernel, spectral dimension) is a continuum-limit
> idealization that requires additional structure (a Dirichlet form, or finite
> truncation / an unbounded Dirac operator for compact resolvent); see the caveats
> below. With those distinctions, the gauge/bundle/curvature language developed
> here (grounding the gauge/base framing of `docs/agents/datom-spec.md`, the
> current home `docs/design/dao.space.md` designates) is a correct description of
> the metric and spectral structure, not mere metaphor.

---

## The Original Equivalence

**Matrix mechanics** (Heisenberg/Born/Jordan): states are vectors in
ell-squared (countable basis), observables are infinite Hermitian matrices,
dynamics is i-hbar dA/dt = [A, H]. Everything is algebraic: commutators, matrix
multiplication, traces. The underlying space is discrete.

**Wave mechanics** (Schrodinger): states are functions psi(x) in L2(R^n),
observables are differential operators, dynamics is i-hbar d-psi/dt = H-psi.
Everything is analytic: PDEs, calculus, manifolds. The underlying space is
continuous.

**The equivalence** has three mathematical pillars:

### 1. Hilbert space unification

Both pictures live on the same separable Hilbert space H. The discrete (matrix)
and continuous (wave) bases are related by a unitary transformation — a change
of basis.

### 2. Spectral theorem

Every self-adjoint operator A on H has a spectral resolution
A = integral of lambda dE(lambda). In the matrix picture, this is
diagonalization. In the wave picture, this is solving the eigenvalue PDE. The
spectral data (eigenvalues, spectral measure) is basis-independent.

### 3. Gelfand-Naimark duality

Every commutative C*-algebra is C0(X) for a unique locally compact Hausdorff
space X. So: given a commutative subalgebra of operators (a set of simultaneously
diagonalizable observables), there exists a classical space X on which those
operators become multiplication. The matrix algebra "contains" all possible
classical spaces as its commutative subalgebras.

The deep content: **the algebra comes first; the space is derived from it.** A
non-commutative algebra encodes a "quantum space" that has no single classical
(point-set) manifestation, but each commutative subalgebra provides one classical
shadow.

---

## DaoSpace as Matrix Mechanics

(Here "DaoSpace" means the whole coordination construct — the datom set `E` plus the
interpreter algebra over it. The storage boundary `dao.jing` is the `E` row below; the
interpreter rows belong to the embedded query library, `dao.space.query`, not to storage.)

The mapping is direct:

| Matrix mechanics | DaoSpace |
|---|---|
| Countable basis of H | The countable set E of datoms |
| Hilbert space H = ell-squared | H = ell-squared(E) — square-summable functions on datoms |
| Algebra of observables (matrices) | C*(G) — the C*-algebra of the gauge groupoid |
| Commutative subalgebra (simultaneously diagonalizable observables) | An interpreter's equivalence relation (constant on equivalence-classes) |
| Non-commuting observables (general; not the canonical [Q, P] = i-hbar-I specifically) | Non-commutativity of different interpreters (two equivalence relations that cannot be simultaneously applied) |
| Energy eigenbasis | The "natural" basis induced by a chosen interpreter's quotient |
| State vector psi | An agent's attention/belief vector over the tuple space |

### The gauge groupoid G

- **Objects**: datoms E
- **Arrows**: (e1 -> e2) when pi(e1) = pi(e2) — i.e., e1 and e2 are
  gauge-equivalent representations of the same invariant fact (same content
  hash, different local coordinates)

The groupoid algebra C_c(G) with convolution:

```
(f * g)(e1, e3) = sum over e2 of f(e1, e2) * g(e2, e3)
```

completed to a C*-algebra A. This is the algebra of gauge-covariant operators on
H = ell-squared(E). Elements of A encode relational structure: "how strongly are
these datoms related through shared content?"

---

## Constructing the Wave Mechanics (the Continuous Picture)

This is where Gelfand-Naimark does the heavy lifting.

### Step 1 — Choose an interpreter

An interpreter defines an equivalence relation ~ on E. Operators that are
constant on ~-classes form a **commutative subalgebra** A_~ inside A. This is a
complete set of commuting observables. (This presumes ~ is compatible with — i.e.
coarser than — the gauge equivalence pi that defines the groupoid algebra A, so
that A_~ genuinely sits inside A. Interpreter relations that cut across gauge
classes need separate treatment.)

### Step 2 — Extract the classical space

By Gelfand-Naimark, A_~ is isomorphic to C(X_~) where:

```
X_~ = Gelfand spectrum of A_~
    = { multiplicative linear functionals on A_~ }
```

X_~ is a compact Hausdorff space — the **classical configuration space** for
interpreter ~. It is the "wave mechanics" side: a genuine topological space
derived from the discrete datoms.

**Caveat (totally disconnected spectrum).** For a countable datom set E, this
spectrum is **totally disconnected** — a discrete set of equivalence classes, or
a Stone space (e.g. the Stone-Cech compactification beta-X) once the algebra is
completed in the sup norm. It is compact Hausdorff but it is **not a manifold**:
there is no connected continuum, so traditional calculus (PDEs, smooth charts)
does not literally apply. The "wave mechanics" picture (L2(X_~), differential
operators) is a **continuum limit / smoothing**, valid in the large-data limit or
once a Dirichlet form (energy / heat semigroup) is imposed to supply a
differential calculus. For small E it is an idealization, not an identity. What
*is* literal on the discrete object is the algebra, its spectral decomposition,
and the graph (geodesic) metric — see the Dirac operator below.

### Step 3 — The Fourier transform of the groupoid

The map from the discrete picture to the continuous picture:

```
F_~: ell-squared(E) -> L2(X_~)
```

This is constructed by decomposing vectors in H along the ~-equivalence classes
and mapping each class to a point of X_~. It is the analog of the Fourier
transform that maps ell-squared(Z) to L2(S1). Under F_~, the gauge-covariant
operators in A become multiplication operators on L2(X_~) — exactly as position
operators Q-hat become "multiply by q" in the wave picture. (F_~ is a genuine
*unitary* ell-squared(E) -> L2(X_~) when A_~ is maximal abelian with a cyclic
vector; otherwise the representation has multiplicity and L2(X_~) carries a
multiplicity bundle.)

### Step 4 — Different interpreters = different representations

Interpreter ~1 gives X_~1; interpreter ~2 gives X_~2. If ~1 and ~2 are
incompatible (their subalgebras don't commute), then X_~1 and X_~2 are related
by a **non-trivial unitary transformation** — a gauge transformation that cannot
be reduced to a coordinate change. This is the Heisenberg/Schrodinger
correspondence: position basis and momentum basis are related by Fourier
transform, not by a relabeling.

---

## The Dirac Operator and Spectral Triple

To get curvature, holonomy, and distance — the full geometric apparatus — define
a **spectral triple** (A, H, D):

**D (the Dirac operator)** is a first-order self-adjoint operator on H encoding
the relational graph of datoms. On a graph, the natural choice is the
off-diagonal operator on ell-squared(V) + ell-squared(E):
D = [[0, B], [B^T, 0]], where B is the |V|x|E| signed incidence matrix
(B(v,e) = +/-1 if vertex v is an endpoint of edge e). Then
D^2 = [[BB^T, 0], [0, B^TB]] = [[L, 0], [0, L_edge]], so D^2 restricted to the
vertex sector is L, the graph Laplacian (second-order, positive semidefinite).
More generally, D can incorporate weighted or multi-relational edge structure.
The key relationship is D^2 = L; D is first-order, L is second-order. The Connes
distance formula and curvature both require D (not L): the commutator ||[D, f]||
is the Lipschitz seminorm (max edge-difference of f), which gives graph geodesic
distance. Using L instead would give a second-order operator whose commutator
does not recover shortest-path distance.

**Caveat (compact resolvent).** A genuine spectral triple in Connes' sense
requires D to have **compact resolvent** (discrete spectrum, eigenvalues to
infinity). On an infinite E with bounded average degree the graph Laplacian L
(and the incidence Dirac D) are **bounded** operators, so their resolvent is not
compact. The consequences are scoped, not total:

- **Survives without compact resolvent:** the Connes **distance** formula. It only
  needs D self-adjoint with bounded `[D, f]`; the incidence Dirac recovers the
  graph geodesic metric even on infinite graphs.
- **Requires compact resolvent (so, fails here as stated):** the **heat-kernel
  expansion, spectral/metric dimension, and the "compact non-commutative
  manifold" reading.** To recover these, work with finite subgraphs (an inductive
  limit of finite truncations) or choose an **unbounded** D with discrete spectrum
  (e.g. an unbounded multiplier weighting). For any finite E the triple is
  trivially fine (finite-dimensional H).

The spectral triple gives:

### Spectral distance

Between datoms e1, e2:

```
d(e1, e2) = sup { |f(e1) - f(e2)| : f in A, ||[D, f]|| <= 1 }
```

This is a genuine metric on E derived from relational structure. Two datoms are
"close" if they are connected by many short paths in the relationship graph,
regardless of their entity IDs or timestamps. This is the computational analog of
the geodesic distance in the continuous picture.

### Curvature

Of a connection nabla = [D, .] on an A-module M:

```
F_nabla = nabla^2 = [D, [D, .]]
```

The discriminating property is **free (globally trivial)**, not merely projective.
Projective ⇔ locally free (Serre-Swan) guarantees that *a* connection exists (the
Grassmann connection ∇ = p∘d), but its curvature θ = p(dp)² is generally nonzero
— the canonical NCG line bundles (Bott projector on the torus/sphere) are
projective *with* curvature. Flatness needs the stronger free/trivial structure.

- For strict streams (`:strict? true`): constant fiber ⇒ M is **free (globally
  trivial)** ⇒ it admits the trivial connection ∇ = d ⇒ F = 0, a flat, locally
  trivial bundle. This matches the claim in `dao.space.v0.md` (superseded; its
  typed-stream material is preserved) that strict typing is the local-triviality
  condition.

- For open streams (`:strict? false`): variable fiber ⇒ M is **not free** (no
  global trivialization; in general not even projective) ⇒ no flat connection ⇒
  F ≠ 0. The curvature measures the **obstruction to finding a consistent global
  gauge** — i.e., you cannot assign entity IDs uniformly across all datoms because
  the fiber shape varies. This is computable and meaningful.

### Holonomy

Around a loop of gauge transformations (e1 → e2 → e3 → e1 through different
streams):

```
Hol(loop) = ordered product of edge parallel-transports around the loop
```

(On a discrete graph there is no integral: holonomy is the finite ordered product
of the per-edge parallel-transport maps, the discrete analog of the path-ordered
exponential.) Non-trivial holonomy means that going around the loop and returning
to the starting datom changes the local gauge — the same fact "looks different"
after a round trip through different streams. This is the analog of the Aharonov-Bohm
effect: the geometry is detected by parallel transport, not by local measurement.

### Descent: reassembling global views from local streams

The way a bundle is actually *built* — from local pieces plus gluing metadata that
must agree — is the precise mathematics of reassembling a consistent global view
(e.g. an ACID materialized view) from many independent streams. This is
**descent** (Grothendieck), whose classical shadow is the transition-function /
cocycle description of a bundle.

A bundle is rarely given globally. It is given by **local trivializations** over a
cover `{U_i}` (each patch looks like `U_i × F`), stitched on overlaps by
**transition functions** `g_ij : U_i ∩ U_j → G` (the structure group). Those
transition functions are the *reassembly metadata*, and they must satisfy the
**cocycle condition**:

```
g_ii = id,    g_ij g_jk = g_ik    on triple overlaps
```

The cocycle condition is the requirement that every local stitch agrees — the
geometric form of "every interpreter applies the same deterministic merge rule,
consistently." When it holds, the local pieces glue into one global object; when
it fails, they do not.

The correspondence to the stream picture (this is the **end-to-end argument** of
networking, stated geometrically — the substrate delivers ordered durable local
data; meaning is reassembled at the endpoint):

| Stream / data picture | Bundle / descent |
|---|---|
| per-stream local view | local trivialization `U_i × F` |
| reassembly metadata (tx-id, causal `m`-slot / version vector, merge rule) | transition functions `g_ij` |
| "all interpreters merge consistently" | cocycle condition `g_ij g_jk = g_ik` |
| a single global consistent (ACID) materialized view | a **global section** of the bundle |
| "no single global view exists; only local ones" | **obstruction** to a global section (nontrivial bundle) |
| the inequivalent ways to reassemble | Cech `H^1(X, G)`, the classification of bundles |

**These are closely related obstructions to a consistent global view — though not
literally the same one.** Three distinct obstructions are in play, and it is worth
keeping them apart:

- **Cech `H^1(X, G)`** — whether the bundle *exists* at all (topological). On a
  discrete base this vanishes (`H^1 = 0`; every bundle is topologically trivial),
  so it is not the operative obstruction here.
- **Curvature `F`** — whether a connection on a given bundle is *flat* (geometric).
- **Holonomy** — whether a flat connection is *globally trivializable* (see below).

So on the discrete datom graph the live obstructions are geometric (`F`) plus
holonomy, not topological. With that distinction: when local streams admit one
coherent global gauge, a global section exists and a single consistent view
reassembles cleanly — the strict-stream / free-module / `F = 0` case. When they do
not, only local sections exist — the open-stream / non-free / `F ≠ 0` case, whose
curvature is the geometric "obstruction to a consistent global gauge." The
ACID-reassembly question and the curvature computation are two vocabularies for
the *same family* of obstructions, with curvature the operative member here.

**The synchronous-consensus residue is holonomy.** Even a flat bundle over a base
with loops can be reassembled locally and along any path, yet transport around a
loop returns a different gauge (`Hol ≠ id`). That path-dependence is exactly the
case local gluing cannot settle without an extra global choice — two uncoordinated
writers going "around the loop" and disagreeing. Detection and deterministic
resolution are descent (read-side, cohomological); *establishing* agreement before
acting is the consensus step that lives above it.

**This is what upgrades the open-stream fibered set to a sheaf.** `dao.space.v0.md`
(superseded) notes that an open-stream fibration is a *fibered set, not a sheaf*, because no
site or gluing was specified. The reassembly metadata is precisely that missing
data: the sheaf gluing axiom ("local sections agreeing on overlaps glue uniquely
to a global section") is reassembly when it succeeds, with the cocycle condition as
the gluing law. Supply the transition metadata and the fibered set becomes a
genuine sheaf.

**Honest boundary.** The bundle/descent picture is exact for the *static*
question: whether a consistent global view exists, and how many inequivalent ones
there are (sections, obstructions, `H^1`, holonomy). It does **not** model the
*dynamic, algorithmic* act of reaching agreement under concurrency — writers
racing in time to establish the cocycle. That needs a bundle over a causal /
temporal site (a sheaf over a poset of events, or a stack), and the synchronous
consensus step is an algorithmic fact outside the static classification — just as,
in the network stack, consensus sits above reliable delivery and is not a property
of the packets.

**Dynamic membership is the site-level structure.** The coordination medium is the whole
system: `dao.jing` (storage) whose **streams** join and leave at runtime, read by
**interpreters** that embed the query library (see `dao.jing.md`, *Intake*). So the
descent picture above describes only a **snapshot** — the sections being glued at one
instant. With membership changing, the gluing is
time-varying: the space is not a fixed bundle but a (pre)sheaf over a **site that
includes membership/time events**, where `attach`/`detach` are morphisms that grow
or shrink the cover. A single stream's geometry is unaffected (it is still a
section — a trivial bundle when strict, a fibered set when open); what becomes
time-indexed is the *assembly*. This is the concrete, operational form of the
boundary above: the static bundle is the per-instant descent; dynamic membership
is exactly the temporal/site (stack) structure that static descent does not
capture. On the read side it is the mirror image — an interpreter attaching or
detaching is choosing or dropping a re-fibering `π' : E → E/∼`, so the *set* of
fibrations over the total space is time-varying too.

---

## What This Buys You, Concretely

### 1. The "quantum measurement" metaphor becomes literal

`dao.space.v0.md` (superseded; "Interpreters and Equivalence-Class Slicing") says: "same data, different projections, simultaneously."
In the matrix-to-wave correspondence, this is exactly the Heisenberg picture:
the state |psi> in ell-squared(E) is fixed; different interpreters are different
"observables" that project |psi> onto different classical spaces X_~1, X_~2, ....
Incompatible interpreters (non-commuting subalgebras) cannot be simultaneously
measured — applying ~1 destroys information that ~2 would have provided. This is
genuine non-commutative uncertainty, not a metaphor.

### 2. Spectral clustering becomes a natural operation

The graph Laplacian L (where L = D^2) on ell-squared(E) has eigenvalues
0 = lambda_1 <= lambda_2 <= ... and eigenvectors v1, v2, .... These are the "normal modes" of
the tuple space:

- **lambda_2** (the spectral gap / Fiedler value) measures how well-connected
  the tuple space is. A small gap means the space is nearly disconnected — there
  are clusters of datoms that barely relate to each other.

- The first k eigenvectors embed E into R^k: e maps to (v2(e), ...,
  v_{k+1}(e)). This is the **wave function** view — each datom becomes a point in
  R^k, where standard geometry (distances, angles) applies. (This is a spectral
  *embedding* into Euclidean space — the honest, computable object. It does not
  make the datom set itself a manifold; see the totally-disconnected caveat
  above.)

- Clustering in this embedding = finding "communities" of related datoms. This
  is spectral clustering, and it is a direct application of the matrix-to-wave
  correspondence: the matrix (Laplacian) is diagonalized to produce waves
  (eigenvectors) that reveal continuous structure.

### 3. Entanglement between agents becomes measurable (speculative)

Two agents have a **joint state** |Psi> in ell-squared(E x E). Note: a bare
product |psi1> tensor |psi2> is by construction separable — its reduced density
matrix is pure and its entropy is always zero, so it can never exhibit
entanglement. Entanglement requires a **non-product** joint state, which arises
when the agents interact through shared datoms (their views become correlated
rather than independent).

For such a non-product |Psi>, the **entanglement entropy** — von Neumann entropy
of the reduced density matrix rho1 = Tr2(|Psi><Psi|) — measures how much
information about agent 2 is encoded in agent 1's local view. Zero entropy =
agents are independent (the state factored); positive entropy = coordination is
happening through the shared space. (Speculative: this presumes a model of how
shared datoms induce a non-product joint state, which is not specified here.)

### 4. The Wigner function gives a phase-space picture (speculative)

Between matrix and wave mechanics sits the **Wigner-Weyl formalism**: a quasi-
probability distribution W on phase space. **Caveat:** Wigner-Weyl presupposes a
genuine phase space — a pair of *canonically conjugate* variables with a
symplectic / Heisenberg (CCR) structure. "content-hash x gauge" is base x fiber,
not a demonstrated conjugate pair, so treating it as phase space is an assumption,
not an established fact. If such a conjugate structure were defined, W(content-hash,
gauge) would be a function on (invariant content x local coordinates) that
visualizes where the tuple space's "mass" concentrates, enabling:

- Optimization of gauge choices (minimize Wigner entropy → find the most
  "classical" interpreter)
- Detection of "interference" between different streams (negative regions of W)
- A 2D visualization of the entire tuple space

---

## The Correspondence Table

```
MATRIX MECHANICS              DAOSPACE (discrete)           WAVE MECHANICS
(you have this)               (you have this)               (you derive this)

ell-squared basis { |n> }     datoms E                      L2(X_~) basis
infinite matrices             gauge groupoid alg C*(G)      multiplication ops on X_~
commutator [A, B]             non-commuting interpreters    Poisson bracket {f, g}
spectral theorem              graph Laplacian eigendecomp   eigenvalue PDE D-psi = lambda-psi
canonical commutation         incompatible equiv relations  Fourier transform F_~
density matrix rho            agent belief vector           Wigner function W(b,g)
entanglement entropy          shared-datoms correlation     classical correlation
curvature F = nabla^2         gauge obstruction             holonomy != identity
```

The left column is what the system does today (discrete datoms, gauge
relabelings, interpreter equivalence classes). The right column is what the
matrix-to-wave correspondence derives from it (classical spaces, differential
operators, continuous geometry). The middle column is the translation.

---

## What Is Needed

**You do not need to add anything to the data model** to obtain the algebra, its
spectral decomposition, and the graph (geodesic) metric — these are latent in the
relational structure of the discrete datoms, extracted via Gelfand-Naimark + the
spectral theorem. For these, the gauge/bundle/curvature language here (grounding the
gauge/base framing of `docs/agents/datom-spec.md`, the current home, per
`docs/design/dao.space.md`) is a correct description, not metaphor, once the Dirac
operator is chosen. The
*fuller* continuous apparatus (manifold, heat kernel, spectral dimension) is the
continuum-limit idealization and does need extra structure (Dirichlet form, or
compact-resolvent fix), per the caveats above.

The one missing piece is the **choice of Dirac operator D**, which is a design
decision, not a mathematical gap. D encodes "what relationships matter" — and
different choices of D give different geometries, just as different Hamiltonians
give different physics. That choice is where the system designer's intent enters
the mathematics.
