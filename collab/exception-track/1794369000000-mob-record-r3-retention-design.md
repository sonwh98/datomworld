Created-GMT: 2026-11-10 23:50:00 GMT
Created-Local: 2026-11-11 06:50:00 +07 (+0700)
Coding-Agent: interactive (ZCode / deepseek-flash, continuation-semantics orchestrator seat)
Status: MOB RECORD — 2 of 3 verdicts in (muse-wonder, glm-5.3); qwen3.8-max running (third
verdict appended when it lands, never rewritten).

# MOB RECORD — can clojure retain a condition without exposing it to guest observation?

Brief: `1794366000000-architect-mob-r3-retention-design.prompt.md`. Question: (A) patch design,
(B) declare the R4 equivalence failure (owner-authorized fallback), or (C) narrow §R2(1).

## The verdicts

**muse-wonder-1.0 → (B)** (`1794366000000-...muse-wonder-1.0.stdout.log`). Exhaustion argument:
provenance must ride value identity (absent for data — equality is content equality; only
cell-refs are identity-bearing), representation (observable: `(= [e] [orig])`, map keys, `count`,
`assoc`, truthiness, aliased predicates), lexical scope (does not travel — capture/retain/
rethrow-from-a-later-activation), or content (forgerable/colliding; a content-keyed table
forwards any equal forged map, violating R5). "No fifth place." Also rules (C) unavailable
(§R2(1)/§8.10.7 are unqualified; narrowing needs an owner ruling and wouldn't save the row since
the round-trip payload is clojure-minted).

**glm-5.3 → (A)** (`1794366000000-...glm-5.3.findings.md`, full body). Three commitments:
1. **Ordinary `:yang.call/thrown` payloads are delivered RAW** — restore master's proven
   semantics (`condition->outcome` `:thrown` arm `clojure.cljc:1656-1664`, unqualified arm
   `:1679-1682` stop minting a wrapper; the error-outcome slot carries
   `(:yang.call/value condition)`). Every leak in astra's table then closes BY CONSTRUCTION — `e`
   IS the payload — and the per-call-site unwinding is DELETED, not extended. The Round-0 green
   round-trip row holds without the `=` routing.
2. **The wrapper survives ONLY as clojure's documented local form for qualified protocol
   conditions** (`:not-callable`, `:invalid-invocation`, `:unsupported-binding`) — exactly
   python/php's `ForeignException` stance. §R2(1) has no antecedent here: the guest never held
   the condition map as a value before the catch, so there is no "original identity" to restore.
3. **Identity-bound retention closes (a)**: an issuer-side `:retained` mark on the wrapper's
   heap entry, mintage only via the compiler-internal reserved-prefix regime (guest `cell/new`
   cannot produce it); `cell/set!` REFUSES marked entries (contents frozen for the wrapper's
   lifetime — §8.10.7's lifetime clause); a pure `cell/retained?` query beside `cell/authentic?`.
   Replay `(throw (cell/new (cell/get e)))` decodes as unmarked → ordinary payload → fresh
   `:yang.call/thrown` = R5's `:forged-retention` expected semantics. The witness closure becomes
   redundant and is retired: the error path mints LESS than today, and slice-5's purity bar is
   better satisfied than at c9c5533c.
   Disputes (B) — astra's finding is uniform across lanes (a shared-conversion defect), not the
   lane divergence R4 requires — and (C) — the un-narrowed contract is meetable, and the row
   should keep asserting exactly `(= e original)` on both lanes.

## The seat's reconciliation

glm's (A) does not contradict muse-wonder's argument; it **dissolves its premise**. Muse-wonder's
exhaustion proof assumes provenance must ride the *caught value* in the ordinary case. GLM removes
that requirement: an ordinary thrown payload needs no retention at all, because the wire condition
is reconstructible from the payload (`{reason :thrown, value v}`), and the rows assert reason +
payload-identity, not full-map condition identity. Retention is needed only for protocol
conditions, where no pre-catch guest value existed — so no §R2(1) identity is owed and the wrapper
is the legitimate local form. Under that split, muse-wonder's four escape routes are not
exercised: identity (not needed), representation (the wrapper is a documented local type for a
condition the guest never held), lexical scope (not needed), content (never keyed on).

**Seat recommendation: (A), glm-5.3's design** — it is the strictly smaller change (deletes
routing), restores the Round-0 green rows by construction, keeps slice-5's tail property in better
shape than c9c5533c, and mirrors the py/php precedent. But a design is not evidence: the round
must pass astra's exact probes (nested `=`, map-key, `count`, `assoc`, caught-`nil` truthiness,
aliased predicate) plus replay/mutation on both lanes before acceptance.

**Conditional on evidence.** If astra's probes fail against the (A) implementation, the (B)
reading stands and the fallback escalates to the owner (not executed on this seat's authority).

## Third verdict

**qwen3.8-max → (A)** (`1794366000000-...qwen3.8-max-r2.stdout.log`). Independently arrives at the
same two-class split from different evidence: the wrapper belongs ONLY on the protocol branch,
"where §8.10.7 already prescribes private wrapper-cell contents"; restoring raw delivery for the
two-key `:yang.call/thrown` path "deletes finding (b) outright (no wrapper, so nothing to
normalize at any call site)"; and finding (a) closes by binding the existing opaque witness to the
wrapper's own cell SEAL — not its ref — so closures stay cell-free for `lift-value`, with both
replay and `cell/set!` mutation failing re-derivation, while `(throw e)` still forwards via one
`if` at the public-escape node (`clojure.cljc:1309-1312`).

## Final tally: 2/3 (A), 1/3 (B) — and the (A) votes converge

- muse-wonder **(B)**: exhaustion argument for the ONE-class design (wrapper for every crossing).
- glm-5.3 **(A)**: raw payloads for thrown / marked wrapper for protocol / issuer-side heap mark.
- qwen3.8-max **(A)**: raw payloads for thrown / witness bound to the wrapper's cell SEAL.

glm and qwen agree on the decisive structural move (raw for :thrown; wrapper only for protocol)
and diverge only on the identity-binding detail (heap-entry `:retained` mark + frozen `cell/set!`
vs witness-bound-to-seal). Both are testable against astra's probe table; the implementation round
is instructed to satisfy the probes, not to pick a citation. muse-wonder's (B) remains the
adversarial test: it is the implementer, so a failure will arrive as evidence, not argument.
