Created-GMT: 2026-11-11 17:15:00 GMT
Created-Local: 2026-11-12 00:15:00 +07 (+0700)
Coding-Agent: codex
Session-ID: 6f29f35c-a6ef-4582-95d1-4a6551be8178
# Task: step-7 implementation review — the VM provenance primitive (ff8b980a)
Role: Reviewer (independent implementation review; sol designed it in the consult, deepseek-v4-pro implemented it, you review — the design is NOT re-litigated, the code is)
Implementers:
- Model: gpt-6-astra (codex) | Assigned: 2026-11-12 00:15 +0700 | Status: active | Rationale: fresh on the implementation; the design consult (sol) and the implementer (deepseek) are both independent of you.

## The work under review

Branch `exception-vm-provenance` @ **`ff8b980a`** (pushed), one commit on master-base `adb6af80`.
Diff: `git diff adb6af80..ff8b980a` — wait, exact: `git diff adb6af80..ff8b980a`. Files:
`src/cljc/yin/vm/engine.cljc`, `src/cljc/yin/vm/module.cljc`, `src/cljc/yin/vm/macro.cljc`,
`src/cljc/yin/vm.cljc`, `src/cljc/yin/vm/docs/state.md`, `test/yin/vm/provenance_test.clj` (new).
Nothing else — verify scope first.

## The design it implements (already ruled — do not re-litigate)

The consult of record: `collab/exception-track/1794451000000-consult-vm-provenance-design.gpt-6.1-sol.findings.md`
(APPROVED WITH AMENDMENTS — read it fully). Your job is to verify the IMPLEMENTATION matches the
ruled design, on the actual diff:

1. **`:provenance` heap sibling**: optional, private, opaque to the VM; `heap-write` preserves it
   while updating `:value`/`:seal`; NO `cell/set!` refusal (the consult explicitly declined it).
2. **The doors**: `cell/dsg__new-provenance` `[value provenance]` (atomic mint: fresh id + seal +
   value + provenance) and `cell/dsg__provenance` `[candidate]` (authenticate BEFORE consulting;
   nil otherwise; never fatal, never mutating). Module profiles `[2]`/`[1]`.
3. **GC tracing (the subtle amendment)**: `:provenance` traced as data from live entries, pinned
   entries, AND allocation-triggered collection roots (`count-allocation`). Verify all three sites
   in the diff; verify no entry is pinned/global-rooted merely for carrying provenance.
4. **Macro closure (the landing requirement)**: source AND macro-admission reject guest-created
   executable references, bindings, and definition targets of the two doors — exercised through
   the ACTUAL macro/REPL expansion path (returning a door as a value for indirect application),
   not just raw-source spelling.
5. **Docs**: `state.md:119` heap-entry shape updated.
6. **Test quality**: are the GC probes real (ordinary/pinned/allocation-triggered + eventual
   reclamation), or shallow? Is the macro-door test exercising expansion or just spelling?

## Also verify

- Scope: exactly the six files above.
- kondo claim: 0 errors (one pre-existing warning in `macro/construct` — confirm pre-existing).
- The implementer's claim that no lane reconstructs heap entries (so shared engine changes cover
  all lanes) — spot-check the walker/semantic/de Bruijn dispatch.

## Deliverable

Findings with severity + file:line; explicit verdict per numbered item above; final line:
**APPROVE / APPROVE WITH FINDINGS / REQUEST CHANGES**. Read-only; no suites (the orchestrator's
shiva run covers them — `xtrack-vmprov` in flight). Begin with Completed-GMT/Completed-Local.
