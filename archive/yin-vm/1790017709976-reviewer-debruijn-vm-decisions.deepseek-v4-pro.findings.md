[claude-code:unrecognized_model] {"model":"deepseek-v4-pro","query_source":"sdk"}
We're changing auto mode to no longer charge for classifier requests in Claude Code. However, this session isn't eligible because your requests go through api.deepseek.com, which isn't compatible with this update. Nothing breaks: auto mode keeps working, and its classifier requests are billed as before. To fix it and access the new version of auto mode, ask your gateway to implement: https://code.claude.com/docs/en/auto-mode-classifier-billing
[claude-code:unrecognized_model] {"model":"deepseek-flash","query_source":"auto_mode"}
Completed-GMT: 2026-09-21 19:12:07 GMT
Completed-Local: 2026-09-22 02:12:07 +07 (Indochina Time)
Coding-Agent: deepseek
Session-ID: pending

VERDICT: SOUND WITH CHANGES

The design is coherent and, for closed programs, correct in its core claim: a named-datom-derived image with binder names outside the hash is alpha-invariant and content-addressed. But the *linker* — the very mechanism the owner named in their invariant ("so that a yin.vm can easily share code over dao.stream linker") — is deferred to B6, and two of the mechanism's load-bearing pieces (dependency closure for free names, and the hash-verification primitive) are left unresolved inside the phases that ARE scoped. The compliance table overclaims invariant I as satisfied by §2/§7.2. Findings below.

---

## FINDINGS

### P1 — The compliance table claims invariant I is satisfied by §2/§7.2, but the linker is a deferred non-B-phase epic

**Section:** §1 compliance table, rows "I: share executable code over dao.stream".

**Quoted sentence:** "| I: share executable code | Canonical image hash, verified loading, | / | over dao.stream | stream request and response (§2, §7.2). |"

**Failing scenario:** Host A lowers a program and publishes the image under hash H. Host B knows only H. The design offers no mechanism in B0–B5 for B to obtain the image: §7.2 is introduced as "a later epic, not B-phase VM machinery", and §8 lists "B6 request schema, contract stamps, SCC manifest mechanics" under DEFERRED. B5's only cross-host criterion is "An image sent over a `dao.stream` from one host lane must load, validate, and execute on another" — a *direct* transfer, not a fetch-by-H. So after B5 there is still no path from "B knows H" to "B runs the image". The owner's invariant names the *linker* specifically; the design delivers portable, hash-verifiable bytes and a VM, not a linker. The compliance table's "stream request and response (§2, §7.2)" cites a future section as if it were the existing mechanism.

**Smallest fix:** Either (a) mark the "I: share executable code" row as a target reached only at B6 and move it out of the "satisfied" compliance table into the deferred section, or (b) state explicitly in §1 that B0–B5 establish *portability + content identity* while the linker is B6, and rename the row accordingly.

### P1 — The verification primitive that lets B "verify it against its hash" is not pinned to the image-hash definition

**Section:** §2 vs §7.2 (and §1 "Benefit and exit criterion").

**Quoted sentences:** §2: "The code-image identity is the hash of the canonical positional instruction vector … This is the same canonical form used by `load-image` and `jing/segment-key`." vs §2 (earlier): "It uses `jing/sha256` and a separate executable scalar encoding: distinct tags …" and §1: "the image hash uses its own canonical encoding and is independent of that address."

**Failing scenario:** B receives an image and a claimed H, and per §7.2 "verifies their content address through `yin.vm.content`". `yin.vm.content/fetch-vector` verifies with `(= address (jing/segment-key v))` (content.cljc:137). But `jing/segment-key` hashes a `:yin.code/*`-shaped vector under Jing's scalar tags, whereas H is defined over a *different* instruction shape (`:var`→`:load-bound`/`:load-free`, `:closure`→arity+body) with a *separate* scalar tag table that is "deliberately distinct from `yin.vm.debruijn/encode-value`". Either (a) the de Bruijn image is materialized under the new `:yin.debruijn.code/*` descriptor so `jing/segment-key` is dimension-aware and *does* yield H — in which case "independent of that [Jing] address" and "separate executable scalar encoding" must be reconciled with it, and the descriptor must carry the distinct tags — or (b) H is computed by a separate function, in which case §7.2's "verifies … through `yin.vm.content`" names the wrong primitive. As written, the walk-through's "verify against hash" step has no specified function that computes H. This is the single step that would make shared code silently *unverifiable*.

**Smallest fix:** Add one sentence to §2 stating unambiguously which function computes H for verification (e.g. "H = `jing/segment-key` under the `:yin.debruijn.code/*` descriptor, whose canonical scalar tags are the table above"), and align §7.2's "verifies through `yin.vm.content`" with it, or drop the "same canonical form used by `jing/segment-key`" clause.

### P2 — Free names resolve by name against the receiver's local environment; dependency closure is B6

**Section:** §1 ("Free names still resolve by name until the stream linker in §7.2 supplies dependency closure"), §4 (`:load-free`), §7.2.

**Quoted sentence:** §4: "`:load-free` uses the existing `resolve-var` order with `free-env`, store, primitives, and module registry."

**Failing scenario:** A's program is `(my-lib/inc x)` where `my-lib/inc` is a free name. The image hashes `:load-free` `my-lib/inc` as a *symbol*, not a binding. On B, `my-lib/inc` resolves against *B's* free-env/store/primitives/module registry. If B lacks it or binds a different value, the same H runs with different semantics (or a `resolve-var` miss). Only *closed* programs — or programs whose free names both hosts coincidentally bind identically — actually share correctly until B6's "dependency closure" exists. The owner's "share code" invariant is only met for the closed fragment.

**Smallest fix:** State the limitation in §1 as an explicit boundary: "Until B6, a shared image is semantically portable only for closed programs (no free names) or free names already bound identically on the receiver; `:load-free` operands are names, not bindings." This is currently implied but never asserted as a boundary of invariant I.

### P2 — "The image records the scalar classes it uses" is under-specified against a "no header" hash

**Section:** §2.

**Quoted sentences:** "The image records the scalar classes it uses. A receiving host that lacks a class refuses the image with a qualified `unsupported-value` outcome before execution" — but also "The code-image identity is the hash of the canonical positional instruction vector: pc-indexed tuples, refs resolved to pcs, **no header**, descriptor hash, and exact scalar bytes."

**Failing scenario:** B1's completion requires "a ratio fixture that is either encoded or refused with `:unsupported-value`" and "distinct hashes for `1`/`1.0`, ratios, and chars on JVM and Dart". But the design never says *where* the "scalar classes used" list lives. If it is inside the hash, then the same program encodes a `char` on JVM but "characters are host characters where available and strings otherwise" (§2), so the recorded class set differs by host and the image bytes — hence H — differ by host, contradicting "identical bytes across hosts for the common scalar domain". If it is outside the hash (like provenance), then "records" is diagnostic and the receiving host's "lacks a class" check can't be verified against H. The refusal mechanism is asserted but not structurally pinned.

**Smallest fix:** Specify where the class-usage record lives (a hash-included class-list slot, or a hash-excluded side field), and state how "host lacks a class" is detected *before* execution given that the bytes themselves only carry per-value tags.

### P2 — Compliance table's "Everything is a continuation" row contradicts the deferred UCF

**Section:** §1 compliance table vs §4.

**Quoted sentences:** Table: "| Everything is a | Parked machine state is explicit data and | / continuation | resumes through stream-carried events (§4, §7.2). |" vs §4: "UCF remains proposed and deferred, so heterogeneous continuation transport is not yet promised."

**Failing scenario:** Axiom 4's core is that a continuation can "pause, travel across streams, and resume anywhere". §4 explicitly defers exactly that property (UCF / heterogeneous transport). Within B0–B5, park/resume is explicit data *within one VM instance*, but a continuation cannot travel across a host boundary to a different runtime — which is what the "share code over stream" scenario would need for a parked program. The table presents axiom 4 as satisfied; the text says it is not.

**Smallest fix:** Amend the row to "within a VM" scope, e.g. "Parked machine state is explicit data; cross-host continuation transport is deferred (UCF, §4)", so the table stops overclaiming.

### P2 — D4 treats the named-VM environment leak as fact without a citation

**Section:** §1, §4, §5, B4, B5, D4.

**Quoted sentence:** "The named VM's environment leak is a separate defect and is not silently included in this contract."

**Failing scenario:** B4/B5 correctness rests on this leak existing: cross-program park/resume tests are "restricted to free names supplied by the initial environment or store until the named-VM leak is fixed". If the leak does not actually exist (or is fixed during these phases), the restriction silently over-restricts or the "named-path refusal" path in B5 never fires and is untested. The design never names where the leak is established (a test, an issue, a source location), so a future reader cannot tell whether the restriction is still needed.

**Smallest fix:** Add a one-line pointer (test name or source location) establishing the leak, so D4's premise is verifiable and can be retired when fixed.

### P3 — `§` section sign is non-ASCII throughout prose

**Section:** whole document.

**Quoted sentence:** e.g. "stream request and response (§2, §7.2)".

**Failing scenario:** The form rule requires "ASCII tables" (satisfied — the compliance table is pure box-drawing) and "no em dashes" (satisfied — no `—` present; the only non-ASCII glyph is U+00A7 `§`). If a strict "no non-ASCII" lint is later applied to the prose, every `§` trips it.

**Smallest fix:** None required if `§` is an accepted project convention (the merged projection doc uses it identically); otherwise a mechanical `§`→`sec.`/`section` substitution.

### P3 — Two independent scope computations (B1 validator and B2 lowerer) with no stated cross-check

**Section:** §3.

**Quoted sentence:** "Scope validation is mandatory in both places where an image can enter: 1. B2 validates … 2. B1's image validator repeats the check by walking each body's declared arity and enclosing-body chain."

**Failing scenario:** B1 and B2 reconstruct scope by two different walks ("enclosing-body chain" vs "each body range is `[entry, first :return]`"). A divergence (e.g. an off-by-one in one walk) would let a shape-valid-but-wrong-scope image pass one check and fail the other, with no single authority. B2's own example `[:load-bound [5 0]]` rejected "before execution" is checked twice by code that is not asserted to agree.

**Smallest fix:** Add a B2 completion criterion: every image B2 emits must be accepted by B1's validator and vice versa, pinning the two walks to agree.

---

## ANSWERS

### 1. Invariant I — end-to-end walk

Host A lowers a program → runs `yin.vm.linearize/lower` (linearize.cljc:154) over the named `:yin/*` datoms, then the B2 adapter rewrites `:var`→`:load-bound`/`:load-free` and `:closure` params→arity (the existing `lower` does the flattening; B2 is a rewrite pass over its output). A hashes the canonical instruction vector to H and publishes the image. Covered by: §2, §3, B2. **Gap:** B2 is not yet implemented; the hash and descriptor are B1.

Host B receives it over a `dao.stream`. The stream is the boundary; effects/transfer are stream outcomes. Covered by §4, §7.2. **Gap:** the request/response convention is B6 (deferred).

B verifies it against its hash. §7.2 says "verifies their content address through `yin.vm.content`", which uses `jing/segment-key`. **Gap (P1):** H is defined over a *separate* scalar encoding that "is independent of that [Jing] address"; the function that actually computes H for verification is never named, and `jing/segment-key` over the de Bruijn vector would not equal H unless the new descriptor's tags are wired into Jing.

B runs it. The de Bruijn VM executes the positional image. **Gap:** free names (`:load-free`) resolve against B's own `free-env`/store/primitives/module registry (§4) — a *name*, not a binding — so a program with free names runs with B-local semantics until B6's dependency closure.

Unstated assumptions, concretely: (a) how B obtains the image when it only knows H — **not covered, B6**; (b) what free names resolve to on B — **B-local, B6**; (c) what B does if the image uses a scalar class B lacks — **covered** (§2 "refuses … `unsupported-value` before execution"), but the *detection* mechanism is unstated (see P2).

### 2. datom.world invariants

- **No hidden global state** — clean. VM state, free-env, registries are explicit values (§4).
- **No implicit control flow** — clean. "Steps classify explicit data; missing code parks and emits a request" (§7.2).
- **No callbacks** — clean. "No loader or callback is invoked" (§7.2).
- **No shared mutable state** — clean. "Frames, stores and continuations are persistent transition data" (§4).
- **Do not collapse interpretation and execution** — clean. Projection / lowering / VM / linker are "separate interpreters" (§1).
- **No assumed graphs** — clean. "Image refs and body ranges are validated before execution" (§3).
- **Derive, don't persist** — *strained, self-flagged*. The diagnostic side table carries binder names and provenance "outside the image hash" (§2). Binder names are already facts in the named datoms; copying them into a per-image side table is re-persisting them, not deriving them. The design is honest ("Strained: diagnostics — Binder names and provenance are side data and never executable identity"), and it does not create a second *authority* (they're outside identity), so I do not escalate this to P2 — but it is a genuine strain on the literal rule, not a derivation.
- **Host boundary is a stream boundary; an adapter takes no function to invoke** — clean. "no direct function-to-function call crosses the boundary" (§7.2).

Compliance-table dishonesty/omissions: two rows overclaim — "I: share executable code" (P1) and "Everything is a continuation" (P2). The "No hidden global state … registries" row cites §4, but §4's only registry mention is the `resolve-var` "module registry"; the word "registries" is loose but not dishonest.

### 3. Decisions D1–D7

- **D1 (normalizer)** — consistent with §1/B0. No leftover text.
- **D2 (drop lossless DAG)** — consistent; §7.3 makes it a non-goal and §1 says "No second lossless source view is built". Note §1 still describes the projection as "a separate identity and deduplication artifact", which is consistent.
- **D3 (descriptor)** — consistent in outline; the "scalar classes used / host refusal" mechanism is under-specified (P2). The "one lift morphism" is pinned by B2's `lift(adapt(lower x), side-table)` equation, good.
- **D4 (environment leak)** — consistent in text; premise unverified (P2).
- **D5 (hash identity)** — consistent: §2's image hash and §7.2's "`:call-hash H` … The projection fingerprint is never H" agree. The internal tension is *which function computes H* (P1), not the D5 principle itself.
- **D6 (linker principles)** — consistent as principles; entirely deferred (B6). Serves invariant I in principle but is not implemented.
- **D7 (end state)** — consistent with §1 "Benefit and exit criterion".

Under-specified enough to block B0: **D3's scalar-class record** and **the verification primitive** (both P1/P2) do not block B0's contract+normalizer work, but they are undefined enough that B1 (which owns the descriptor, encoder, and image hash) cannot be completed without deciding them — they should be decided before B1, not deferred to B6. The design says "No owner decision blocks B0"; that is true, but a decision blocks B1.

### 4. Internal consistency and form

- No line exceeds 80 columns (verified via `awk`).
- No em dashes (verified; the only non-ASCII glyph is `§`, see P3).
- Compliance table is ASCII box-drawing.
- Status line is "design; not implemented".
- No model-routing text.
- The §2 "same canonical form used by `jing/segment-key`" vs "separate executable scalar encoding … independent of that address" is the one real cross-section contradiction (P1).

### 5. Top three risks to "share code over dao.stream"

1. **The linker and dependency closure are B6.** The phases that are scoped deliver portable, content-addressed bytes, not the ability for B to fetch-and-run by H or to resolve A's free names to A's bindings. The core of the owner's invariant is a promise, not a phase.
2. **The verification primitive is unpinned.** H is defined over a "separate executable scalar encoding" that is "independent of that [Jing] address", while §7.2 verifies "through `yin.vm.content`" (= `jing/segment-key`). Until one function is named as the H-computer, cross-host "verify against hash" is undefined and content-addressing can silently diverge.
3. **Scalar-class/encoding ambiguity across hosts.** The "records the scalar classes it uses" + "no header" + "identical bytes across hosts for the common scalar domain" triplet is not reconciled; a host-dependent class set could leak into identity and break alpha-invariance/byte-identity, defeating the content address.

---

## What I checked and found clean

- Read `docs/design/yin.vm.debruijn-vm.md` in full, `docs/design/datom.world.md`, the architect findings (1790017487131), and the merged projection doc.
- Verified against source: `yin.vm.code/vector-operand-table` exists (code.cljc:194); `yin.vm.linearize/lower` emits `:var`, `:closure`(params+body), `:call` with `:yin.code/tail?` (linearize.cljc:84–100, 97–99); only `:application` carries `:yin.code/tail?` and `:dao.stream.apply/call` lowers to `:ffi-call` (linearize.cljc:110–116) — the design's tail-operand claim is accurate.
- Verified `yin.vm.debruijn/resolve-name` exists and implements rightmost-wins matching `yin.vm.engine/bind-params` (debruijn.cljc:606–626) — the design's reuse claim is accurate.
- Verified `yin.vm.content/fetch-vector` verifies via `(= address (jing/segment-key v))` (content.cljc:137) — the basis of the P1 verification-primitive finding.
- Form: no >80-col lines, no em dashes, ASCII tables, correct status line, no model-routing text.
- The architect's sign-off ("READY to commit … begin B0") is consistent with the document's D1–D7, but it does not address the two P1 gaps (linker deferral, verification primitive), which are the review's material concern.

Findings only; no file edited.
