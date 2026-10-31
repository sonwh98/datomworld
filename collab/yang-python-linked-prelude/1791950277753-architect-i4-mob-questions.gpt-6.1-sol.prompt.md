Created-GMT: 2026-10-14 03:58:14 GMT
Created-Local: 2026-10-14 10:58:14 +07
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: Architect Mob on Slice I4 Design Decisions (try-require & deferred dependencies)

Role: Lead System Architect

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-14 10:58:14 +07 | Status: active | Rationale: Independent architect perspective in mob review

Evaluate the three open architectural decision questions for Slice I4 (Catchable ImportError & try-require) in datom.world:

Context to read first:
- `docs/design/datom.world.md` (Axioms and Non-Negotiable Invariants)
- `collab/1792341000000-architect-i4-scoping.claude-fable-5-1.stdout.log` (Fable-5.1's I4 scoping design)
- `docs/design/yin.vm.linker.dht.md` (Sections 4.1-4.2, 7.3-7.5, 9)
- `docs/design/yin.vm.linker.md` (Sections 4.2-4.3, 7.2)
- `docs/design/yang.antlr.md` (Section 8.5.6)

The three open questions to evaluate and answer:

1. **Unpinned optional imports (Scoping design §2.5):**
   - Question: When a Python module contains an optional import `try: import x / except ImportError:` where `x` is absent everywhere at publication time (unpinnable because publisher has no address for it):
     - Option A: Land I4 strict (require all optional dependencies to be pinned at publication; absent from reader's environment answers `:absent`; ledger unpinned imports as a known gap in `docs/agents/known-gaps.md`).
     - Option B: Authorize a `nil` pin representation in `:yin.module/requires` now, modifying `pin-conflict` to ignore nil pins.
   - Mob Prompt: Give your evaluation, weighing hermetic build/content-addressing invariants vs Python idiom compatibility. Which option do you endorse and why?

2. **Primitive surface vs host module (Scoping design §1.1):**
   - Question: For the guest-facing refusal-as-data linking mechanism:
     - Option A: Bare VM primitive `try-require` emitting `:module/try-require {:module m}` (mirroring `:stream/poll!` vs `:stream/next!`), declared in `:yin.module/primitives`.
     - Option B: A dedicated `module` host module exporting `try-require`.
   - Mob Prompt: Evaluate language-blindness, composition overhead, effect profiling, and VM invariants. Which option do you endorse and why?

3. **`ModuleNotFoundError` scope (Scoping design §2.6):**
   - Question: In the Python runtime/prelude (`yang.python.antlr.prelude`):
     - Option A: Raise `ModuleNotFoundError` strictly for `:reason :absent` (reader cannot resolve the name), and general `ImportError` for policy/integrity refusals (e.g. `:yin.admit/untrusted-principal`, `:dependency-pin-mismatch`, `:require-cycle`).
     - Option B: Treat admission refusals (`:untrusted-principal`) as `ModuleNotFoundError` too.
   - Mob Prompt: Evaluate CPython 3.9+ semantics, security masking vs transparency, and fault isolation. Which option do you endorse and why?

Begin your response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: <captured thread-id>

Then provide your thorough analysis and explicit recommendation for each of the three questions.
