Completed-GMT: 2026-09-21 19:34:12 GMT
Completed-Local: 2026-09-22 02:34:12 +07 (Indochina Time)
Coding-Agent: cmd
Session-ID: pending

VERDICT: SOUND WITH CHANGES

The design meets the owner's invariant in the push direction (a sender transmits
the image plus hash over a `dao.stream`; any host validates and runs it, B5),
and no datom.world invariant is violated. The pull direction (B knows only H),
the benchmark "gate" contradiction, and one compliance-table citation need
fixes before B0 sign-off is fully honest.

---

## FINDINGS

### P1 — none.

No mechanism fails the owner's invariant or a datom.world non-negotiable.

### P2-1 — B3 completion contradicts §1 and D7 on benchmarks
Section: §6 B3 vs §1 "Benefit and exit criterion" vs §8 D7.
Quoted: B3 says "Completion requires pure-program parity using B0's normalizer
and fresh initial environments, plus the section 1 benchmark gate for
throughput, allocation, image size, and load time." But §1 says "The report is
informational, not an acceptance condition," and D7 says "B3 reports benchmark
numbers only."
Failing scenario: an implementer finishing B3 treats a slower frame VM as a
phase failure and blocks B4, even though the owner ruled (D7) that numbers are
informational and coexistence is the end state. Two acceptance criteria for the
same phase disagree.
Smallest fix: in B3, replace "plus the section 1 benchmark gate" with "plus the
informational section 1 benchmark report (not an acceptance condition)".

### P2-2 — Compliance table cites a future mechanism for invariant I
Section: §1 compliance table, row "I: share executable code over dao.stream".
Quoted: "Canonical image hash, verified loading, stream request and response
(§2, §7.2)."
Problem: "stream request and response" is §7.2, which §7.2 itself declares "a
later epic, not B-phase VM machinery," and §8 DEFERRED lists "B6 request
schema, contract stamps, SCC manifest mechanics." Within B0–B5 the only
realized sharing mechanism is B5's transfer test: "An image sent over a
`dao.stream` from one host lane must load, validate, and execute on another."
The table claims present compliance on the strength of unbuilt machinery.
Failing scenario: the owner reads the table, believes hash-request linking
ships with the B phases, and plans a remote-code demo after B5 that cannot run.
Smallest fix: change the row's mechanism to "Canonical image hash, verified
loading, and stream transfer (B5); hash request and response arrive with B6
(§7.2)."

### P2-3 — Obtaining the image from H alone is unspecified in every phase
Section: §2 and §7.2.
Quoted: §2 "persistent publication and discovery are outside B0 through B6";
§7.2 B6 "Completion requires hash-address loading."
Problem: if B knows only H, no phase tells it where the bytes come from. B6
requires hash-address loading while §2 excludes publication and discovery
through B6, so even B6's source of bytes is unresolved; §8 defers the request
schema that would resolve it. These two sentences pull in opposite directions.
Failing scenario: host B receives `:call-hash H` in a linked image, parks, and
emits a request; nothing in the design says which stream or store the response
is read from, so B6 cannot start without a new decision.
Smallest fix: one sentence in §7.2: "Within B0–B5, images travel by sender
push over the stream; hash-addressed pull requires B6's request/response
streams, whose schema is deferred (§8)." Optionally relax §2 to "outside B0
through B5."

### P3-1 — Free names on the receiving host have no defined load-time outcome
Section: §1, §4.
Quoted: "Free names still resolve by name until the stream linker in §7.2
supplies dependency closure."
Problem: the image hash excludes the free environment, so the same H can run
on host A and hit an unbound name on host B. `:load-free` operands are hashed
and enumerable, yet the B1 validator checks scope only; resolvability of free
names is neither checked at load nor declared a runtime error contract.
Smallest fix: state in §2 or §3 that a free name unresolvable in the receiver's
`resolve-var` order is a qualified runtime error outcome (or add an optional
load-time resolvability check to B1).

### P3-2 — "The image records the scalar classes it uses" vs derive-don't-persist
Section: §2.
Quoted: "The image records the scalar classes it uses. A receiving host that
lacks a class refuses the image with a qualified `unsupported-value` outcome
before execution."
Problem: the class set is derivable from the hashed `:const` operands. If the
"record" is a separate field, it is redundant persisted structure outside or
inside the hash, and the design does not say which.
Smallest fix: "The scalar classes an image uses are derived from its hashed
constant operands; a receiving host that lacks a class refuses..."

### P3-3 — B2 lift equation overstates the synthesized-name case
Section: §6 B2.
Quoted: "lift(adapt(lower x), side-table) = canonical-vector(lower x)" and two
sentences later "With synthesized names, the lifted result is alpha-equivalent
to `lower x`."
Problem: the equality holds only when the side table carries original binder
names; with synthesized names only alpha-equivalence holds, as the same
section admits. The equation as written is false for the synthesized case.
Smallest fix: "= canonical-vector(lower x) when the side table carries the
original binder names; alpha-equivalence otherwise."

### P3-4 — B6 has no §6 phase box
Section: §6 header vs §7.2.
Quoted: §6 "Each phase has a file box, a must-not-change list, completion
criteria, and JVM, Node/CLJS, ClojureDart, kondo, and cljstyle verification,"
yet §6 defines B0–B5 only; B6's completion criteria live in §7.2.
Smallest fix: add a stub B6 box in §6 pointing at §7.2, or say the §6 sentence
covers B0–B5.

### P3-5 — D4's leak fix has no owner
Section: §8 D4, §1, §5, B4.
Quoted: "treat it as a separate named-VM defect outside these phases; retain
the stated fixture restriction until fixed."
Problem: no phase or referenced design owns the fix, so the park/resume
fixture restriction may persist indefinitely and B4/B5 parity stays narrowed
without a tracked path to widening it.
Smallest fix: add to DEFERRED: "a named-VM environment-leak fix design owns
D4's release condition."

### P3-6 — Form: one misaligned table row
Section: §1 compliance table.
Quoted: "| Interpretation semantics|" — the cell is 25 characters against a
26-character column; the border does not line up.
Smallest fix: pad to "Interpretation semantics |".

### P3-7 — Terminology drift: "frame VM"
Section: §1, §6 B3.
Quoted: "the owner may instead make the frame VM the default" and "B3: frame
VM kernel," while the rest of the document says "sibling VM" / "de Bruijn VM"
/ "executable VM."
Smallest fix: pick one name (de Bruijn VM) and use it in §1 and the B3
heading.

---

## ANSWERS

### 1. Invariant I, end to end (host A JVM lowers, host B Dart runs)

1. **A lowers.** A holds named `:yin/*` datoms plus the optional projection
   fingerprint. The B2 adapter runs `yin.vm.linearize/lower`, rewrites each
   `:var` to `:load-bound [depth pos]` or `:load-free name`, and replaces
   closure parameter vectors with arity plus body refs. Scope is validated
   against the frame-arity vector. Covered by §3/B2. No gap.
2. **A hashes.** B1 computes H over the canonical positional instruction
   vector: pc-indexed tuples, refs resolved to pcs, descriptor hash, exact
   scalar bytes; binder names and provenance stay in the side table outside H.
   Alpha-equivalent programs on A and elsewhere yield the same H. Covered by
   §2. No gap for the common scalar domain.
3. **A publishes over the stream.** Within B0–B5 this is sender push: the
   image bytes plus claimed hash travel as plain stream data. B5's completion
   requires exactly this cross-lane transfer. Covered, but see P2-3: if B only
   knows H, no phase supplies the bytes.
4. **B verifies.** B recomputes the hash from the received canonical vector
   (same canonical form as `load-image` / `jing/segment-key`, §2), refuses on
   mismatch, and the B1 validator re-walks every body's arity and
   enclosing-body chain, rejecting hand-built `[:load-bound [5 0]]` images
   (§3). Covered; this is the design's strongest part.
5. **Missing scalar class on B.** "A receiving host that lacks a class refuses
   the image with a qualified `unsupported-value` outcome before execution;
   this is a host-boundary outcome, not a stream gap" (§2). Covered and
   consistent with datom.world's "a host with no implementation emits a
   qualified unsupported result." Residual: the class manifest's own status is
   ambiguous (P3-2), and refusal is terminal, so real sharing narrows to the
   common scalar domain with no negotiation path.
6. **Free names on B.** `:load-free` resolves in B's own `free-env`, store,
   primitives, and module registry via the existing `resolve-var` order (§4).
   Same H can behave differently or fail unbound on B; dependency closure is
   B6 (P3-1). Unstated assumption: B's initial environment supplies the same
   free names A's did; B5 fixtures must fix the name environment for the
   cross-lane test to mean anything.
7. **B runs.** The B3 frame VM executes with positional frames, closures
   capturing the persistent frame stack, nil-fill/drop-extras call convention.
   Results agree with A under the B0 normalizer. Covered.

Conclusion: the invariant holds for push-sharing of self-contained images in
the common scalar domain, tested by B5. The "obtain given only H" leg and the
free-name closure leg are honestly labeled future (§7.2, B6), but the §1
compliance table overstates their present status (P2-2).

### 2. datom.world invariants

- **No hidden global state**: clean. VM state is one explicit map (§4);
  free-env is "the VM's initial environment value, fixed for one VM instance";
  "No cache is specified by B0 through B5; any later cache is an explicit
  value keyed by the canonical image hash" (§2).
- **No implicit control flow**: clean. "Steps classify explicit data; missing
  code parks and emits a request."
- **No callbacks**: clean. "No loader or callback is invoked"; §7.2 "The
  request token, parked continuation, and response are data; no callback is
  retained." The B4 "frame-aware completion adapter" is a datom.world adapter
  (a map of transforms), not a callback holder.
- **No shared mutable state**: clean. Frames, stores, continuations are
  persistent transition data; the named `bind-params` "remains name-keyed and
  unchanged."
- **No layer collapse**: clean. Projection, lowerer, VM, linker are separate
  interpreters; the linker "is a process between streams."
- **No assumed graphs**: clean. Body ranges and refs are validated before
  execution (§3, two independent checks).
- **Host boundary is a stream boundary**: clean. Refusals are qualified stream
  outcomes; the linker's "physical placement is not a linker decision."
- **Derive, don't persist**: strained but honest. The diagnostic side table
  duplicates facts derivable from named datoms, but it is load-bearing for the
  lift morphism (B2's `lift(adapt(lower x), side-table)` equation), is outside
  code identity, and the compliance table declares the strain ("Binder names
  and provenance are side data and never executable identity"). The image-hash
  exclusion of the side table is the right call.
- **Loaded-image state / frame env / request emission**: all three declared as
  strains in the table, with B4's lift requirement preventing an
  under-approximated `:yin.k/requires` on exported continuations. Honest.

**Table dishonesty check**: one overstatement, the invariant-I row citing
§7.2's unbuilt request/response machinery (P2-2). No omissions of substance;
the table even lists its own strains, which is unusual and to the design's
credit.

### 3. Decisions D1–D7

- **D1** (normalizer): consistent with §1 ("defined and tested in B0, rather
  than assumed to be an existing repository utility") and B0. Serves parity,
  hence invariant I's cross-host agreement. No leftover text.
- **D2** (drop lossless DAG): consistent with §1, §7.3, and §3's
  derived-diagnostics language. No phase still references a DAG. Serves
  derive-don't-persist, indirectly invariant I by keeping one authority.
- **D3** (descriptor): consistent with §2's raw-Bytes slots, scalar domain,
  lift morphism, and refusal rule; B1 fixtures match ("a ratio fixture that is
  either encoded or refused"). Serves portability, hence invariant I.
- **D4** (leak is separate): consistent across §1, §5, B4, B5. But no phase
  owns the fix (P3-5); the owner may be surprised when park/resume parity is
  still restricted after B5.
- **D5** (H is the executable image hash, SCC = one unit, fingerprint never H):
  consistent with §2 ("it is not the executable cache key") and §7.2's SCC
  ordering. "Definition unit" granularity is undefined, but that is B6 scope
  and does not block B0. Serves invariant I directly.
- **D6** (linker principles): consistent with §7.2. "A value-held name
  environment" is explicit data inside the linker process, not global state.
  Serves invariant I, but is future work (see P2-2/P2-3).
- **D7** (coexistence): consistent with §1's exit criterion, except B3's
  "benchmark gate" wording contradicts it (P2-1).

Nothing in D1–D7 blocks B0; the claim "No owner decision blocks B0" is
accurate, since B0 needs only D1 and the frozen corpus.

### 4. Internal consistency and form

Form is clean: no line exceeds 80 columns (verified by regex), no em dashes
(verified by search), line 3 is exactly "Status: design; not implemented",
tables are ASCII, and there is no model-routing text. Source claims verified
against code: `resolve-name` is public (debruijn.cljc:606); `index-frame`,
`build-node`, `project-node` are private (436/636/726), matching §3's
"not reused"; `encode-value` exists (887); `vector-operand-table` is public
(code.cljc:194); `segment-scope` is public while `closure-ranges` and
`layout-conforms?` are private (completion.cljc:162/132/147), exactly as §3
states; `load-vector`, `:code-aliases`, `:yin.code/hash`, `jing/segment-key`,
`jing/sha256`, `:yin.k/requires` all verified; the projection's UTF-16
surrogate guard, `:yin/tail?` drop, binder-name exclusion, and numeric
canonicalization match §1–§2's characterizations.

Contradictions found: P2-1 (benchmark gate vs informational), P2-3
(publication "outside B0 through B6" vs B6 "hash-address loading"), P3-3
(lift equation vs synthesized names), P3-4 (B6 has no phase box), P3-6 (table
misalignment), P3-7 (frame-VM naming). No phase depends on the dropped
lossless DAG.

### 5. Top three risks to "share code over dao.stream"

1. **The pull path is vaporware-adjacent.** After B5, sharing works only when
   the sender pushes bytes. Hash-addressed retrieval, the request/response
   convention, publication, and discovery are all deferred (P2-2, P2-3), so
   the headline capability, "any yin.vm can obtain the code for H," has no
   owning phase with a schema.
2. **The common scalar domain is the real portability ceiling.** Ratio,
   bigint, and char images refuse on hosts lacking those classes, and refusal
   is terminal with no negotiation or downgrade. Cross-host sharing silently
   narrows to the least-common-denominator domain, and the design gives no
   producer-side signal that an image is common-domain-safe before transmission.
3. **Free names are outside the identity.** The same H can execute, error, or
   diverge on a receiver whose free-env, store, primitives, or modules differ;
   there is no load-time resolvability check and no declared outcome for an
   unbound free name (P3-1). Until B6 dependency closure, "shares one content
   address that any yin.vm can run" holds only under an unstated
   same-name-environment assumption.

---

## CHECKED AND FOUND CLEAN

- Form: 80-column limit, no em dashes, status line, ASCII tables, no
  model-routing text.
- All §2/§3/§7.2 source claims against debruijn.cljc, code.cljc,
  completion.cljc, content.cljc, semantic.cljc, engine.cljc, jing.cljc, and
  yin.vm.debruijn-projection.md (public/private visibility, helper names,
  attribute names, UTF-16 guard, tail-flag and binder-name handling).
- datom.world compliance: no hidden global state, no callbacks, no shared
  mutable state, no implicit control flow, no layer collapse, no assumed
  graphs, host boundary = stream boundary; the three declared strains
  (frame env, loaded-image state, diagnostics) are genuine strains, honestly
  labeled, with B4's lift requirement closing the `:yin.k/requires` hole.
- D1–D7 alignment between §8, the architect's rulings
  (collab/1790017487131, including the D4 MODIFY), and the body text.
- B0 startability: D1 plus the frozen parity corpus is sufficient; no deferred
  item gates it.
