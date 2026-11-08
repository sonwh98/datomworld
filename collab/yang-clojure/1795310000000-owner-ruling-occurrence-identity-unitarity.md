Created-Local: 2026-11-08 2x:xx +0700
Coding-Agent: interactive (ZCode, yang.clojure orchestrator seat)
Status: DRAFT — pending owner sign-off (ruling text from the owner's words in session; filed verbatim-in-substance below)

# OWNER RULING OF RECORD — occurrence identity under unitarity (resolves the codex cc1 gate reject)

## The owner's principle (quoted-in-substance)

The compilation pipeline is transformation of values over dao.stream, so unitarity applies: **information is never lost**. The mapping is **bijective** — two things may compile to the same thing and should have the same content hash, but because of unitarity, **the complete history of the transformation is intact**. Two things may compile to the same thing and have the same hash **but have different history that can be recovered**.

## Rulings derived (U1–U3)

**U1 — an occurrence is not its content address.** The occurrence identity is **(content address, origin)** — the address as reached through a specific transformation event. "Two occurrences of identical content" is well-formed: they differ in history, not content. The per-occurrence origins in the call-site side table, keyed on `[origin root-address path]`, ARE the query that recovers the distinction (design §2.1/§2.3). Codex's P1-a objection ("identical generated/input applications share an address, so provenance cannot be classified by content") is ANSWERED: provenance is not classified by content — it is read from the retained history: the expansion-event record behind a `[:expansion record-address]` origin, the source position behind a `[:source …]` origin. Never minted, never guessed from content.

**U2 — admission identity is history-recoverable, declared by the composition.** If the composition's history contains two admission events, bijectivity demands both are recoverable — collapsing them into one deterministic token loses information and violates unitarity. If the history contains one event, minting a fresh token fabricates history — the violation in the other direction. Only the composition knows which its history contains, so the composition DECLARES the distinction when it exists (the caller-owned admission scope / explicit `:yin.link.publish/admission` token), and the deterministic content-derived default is CORRECT precisely because re-publication of identical content with no declared distinction IS one event. Codex's P1-b objection is ANSWERED on these grounds: the requirement is not "every admission mints a fresh token" (that fabricates history) but "every DISTINCT event is representable and recoverable" (the scoped/explicit mechanism provides it; the composition supplies it when its history contains two events).

**U3 — the general principle.** Identity is never minted from structure OR from process. Occurrence distinctness is either (i) READ from retained history (per-occurrence origins, expansion events), or (ii) DECLARED by the party whose history it is (composition-supplied admission identities). Provenance remains recoverable by address or query over datoms — axiom 2 holds in both channels.

## Consequences

- The cc1 branch state (d91489f7) STANDS as the implementation of U1/U2 — no further design-level code change is required to answer the codex reject; the objection is resolved on the record by this ruling.
- The landing sequence proceeds: astra re-check against this ruling → codex re-gate → merge to master → the exception track's R3 dispatch → adoption rounds 2–4 + D1.
- Addendum A's occurrence contract is unaffected in shape: K = [origin root-address path], `:yang/call-site 1`, the occurrence/content boundary — all unchanged. U1 clarifies WHAT an origin identifies (an event in the retained history), not how it attaches.

— drafted by the yang.clojure orchestrator seat from the owner's ruling in session

## Sign-off (owner, 2026-11-09 01:5x +07)

The owner reviewed U1–U3 and signed off verbatim: "sign-off". Status:
**RULING OF RECORD** — the codex cc1 gate reject (P1-a/P1-b) is answered on
the record; the landing sequence proceeds (astra re-check → codex re-gate →
merge). Signed off by the owner in session to the master-green orchestrator
seat, witnessed and recorded here.
