Created-GMT: 2026-10-14 04:03:00 GMT
Created-Local: 2026-10-14 11:03:00 +07
Coding-Agent: interactive (agy Lead Engineering Orchestrator)
Session-ID: not-applicable (interactive seat)

# Mob Consolidation: Architect Consensus on Slice I4 Design Decisions

Participants:
- Claude Fable 5.1 (`claude-fable-5-1`, design author) — Session `aa937a79-1f03-4d96-8086-95aabdc1158e`
  Artifact: `collab/1791950277753-architect-i4-mob-questions.claude-fable-5-1.findings.md`
- GPT-6.1-Sol (`codex`, independent architect reviewer) — Thread `01a13890-169e-78b3-bde4-87b67897514f`
  Artifact: `collab/1791950277753-architect-i4-mob-questions.gpt-6.1-sol.findings.md`

Verdict: **UNANIMOUS CONSENSUS ON OPTION A FOR ALL THREE QUESTIONS**, with key semantic sharpenings adopted.

---

## 1. Unpinned Optional Imports (Publication vs Resolution)

### Unanimous Ruling: Option A (Land I4 Strict, Ledger Unpinned Imports as Known Gap)

- **Axiomatic Grounding:** Under the foundational axiom that address $A$ is the only code identity, a dependency edge without an address is not a valid dependency edge. A `nil` pin would act as a wildcard, allowing unverified substitutions at runtime and reopening the exact integrity hole that Mob Q4 closed.
- **Contract & Schema Invariants:** Manifests require values under `:requires` to be segment addresses (`yin.vm.linker.cljc:847`). Adding `nil` would require a schema revision across manifests, walkers, and validators. In addition, step 5b of `yin.vm.linker.md` §4.2 prohibits discharging obligations against unpinned names.
- **Scope & Acceptance:** Strict I4 completely satisfies the acceptance line: an optional import must be published and pinned, and if the reader's environment cannot resolve it, `try-require` answers `:absent`, executing the `except` branch.
- **Consolidation Action:** 
  1. Maintain concrete pins for all declared dependencies in I4.
  2. Record publication-time-unavailable imports in `docs/agents/known-gaps.md` as an explicit known gap.
  3. Ensure publication error text clearly indicates the unit is unpinnable at publication time.

---

## 2. Primitive Surface vs Host Module

### Unanimous Ruling: Option A (Bare VM Primitive `try-require`)

- **Language-Blindness & Simplicity:** `require` is already a bare VM primitive (`yin.vm.cljc:410`). `try-require` is the identical linking mechanism, merely changing continuation outcome delivery (refusal as plain data rather than an uncatchable abort).
- **Composition Overhead:** A dedicated host module would require explicit wiring across every runtime host composition and lowering profile, whereas a bare primitive declared in `:yin.module/primitives` integrates directly with zero composition bloat.
- **Effect Profiling:** Declaring effect `:module/try-require` cleanly distinguishes code that inspects linking outcomes from ordinary code via the effect system.
- **Consolidation Action:**
  1. Add bare primitive `try-require` to `yin.vm/primitives` emitting `:module/try-require {:module m}`.
  2. Amend docs (`yang.antlr.md` §8.5.6, `yin.vm.linker.md` §7.2) to clarify that `module/try-require` refers to the effect kind/namespace, not a host module.

---

## 3. `ModuleNotFoundError` Scope

### Unanimous Ruling: Option A (Strict Semantic Distinction)

- **CPython Alignment & Transparency:** CPython 3.9+ strictly reserves `ModuleNotFoundError` for failure to locate a module (finder failure). An admission refusal (`:untrusted-principal`) or integrity mismatch (`:dependency-pin-mismatch`) means the module was located, but admission policy or integrity validation declined it. Policy refusals must not be masked as absence.
- **Fault Isolation:** Catching `except ModuleNotFoundError:` allows fallback when optional functionality is absent without silently concealing permission/trust misconfigurations or corrupted pins. Applications wishing to catch both can catch `except ImportError:`.
- **Crucial Sharpening (Both Architects):** Differentiate **name absence** from **content absence**:
  - Unresolved Name (`:reason :absent` with `:name` key): Raise `ModuleNotFoundError`.
  - Content Miss at Address (`:reason :absent` with `:address` key): Raise `ImportError` (module was located but content could not be fetched).
  - All other refusals (`:untrusted-principal`, `:dependency-pin-mismatch`, `:require-cycle`, etc.): Raise `ImportError` carrying the structured refusal map.

---

## Next Steps

With consensus established and verified, proceed with:
1. Dispatching Slice **L-d-1** (`try-require` primitive, handler, refusal-as-data) to Codex (`gpt-6.1-sol`) in a dedicated worktree (`../datomworld-ld1`).
2. Fable 5.1 review of L-d-1 upon completion.
