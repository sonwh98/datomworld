Created-GMT: 2026-11-11 16:30:00 GMT
Completed-GMT: 2026-11-11 16:52:32 GMT
Completed-Local: 2026-11-11 23:52:33 +0700 (+0700)
Role: Engineer (VM round; branch owner: the continuation-semantics orchestrator)
Coding-Agent: deepseek

# Findings — the VM provenance primitive (sol consult APPROVED WITH AMENDMENTS)

Landed the sol-approved design on branch `exception-vm-provenance` (cut from
origin/master @ adb6af80), scoped strictly to the VM surface.

## What changed

- `yin.vm/module.cljc`: the `cell` module gains the two compiler-private doors
  `cell/dsg__new-provenance` (effect `:cell/new-provenance`, arity [2]) and
  `cell/dsg__provenance` (effect `:cell/provenance`, arity [1]), each an
  `:effectful` profile per module.cljc:388.
- `yin.vm/engine.cljc`: `heap-write` preserves `:provenance` while updating
  `:value`/`:seal`; the `:cell/new-provenance` arm allocates a fresh sealed
  entry with `:value`+`:seal`+`:provenance` atomically; the `:cell/provenance`
  arm authenticates before consulting the slot and returns nil otherwise
  (never fatal, never mutating). GC traces `:provenance` as data from live
  entries, from pinned entries, and as an extra root at allocation-triggered
  collection (`count-allocation` now takes `& roots`).
- `yin.vm.cljc`: `reserved-prefix` ("dsg__") and `reserved-prefix-name?`.
- `yin.vm/macro.cljc`: the expander prelude refuses a compiler-private name in
  `yin/variable`, `yin/lambda` (and `yin/make-lambda` via `lambda-packet`), and
  `yin/make-def` — a guest macro can no longer construct an executable
  reference, binder, or definition target for a door.
- `yin.vm/docs/state.md`: heap-entry shape (:119) now documents `:provenance`.

## Acceptance

1. Unit tests (provenance_test.cljc): mint/read, write-once, query-nil-for-
   unmarked, set!-preserves-provenance, content-copy-loses-provenance,
   ref-copy-retains-rethrow-authority — all green on the four VMs.
2. GC probes: provenance-only payload reachability across ordinary, pinned,
   and allocation-triggered collection, plus eventual reclamation when the
   wrapper drops — green.
3. Macro-door access + exported-helper bypass tests — green (the full macro
   invocation path, including a door returned as a variable for indirect
   application, is refused).
4. VM seam suites green: cell, heap-reclamation, module, effect-forgery,
   rule-r, parity, mint, de Bruijn stack + register, semantic.
5. `clj -M:kondo --lint` on the changed files: 0 errors. One pre-existing
   "Redundant let expression" warning remains in `yin.vm.macro/construct`
   (untouched by this round).
6. `yang.call.exception-contract-test` was NOT run locally (owner ruling).

SHA ff8b980a

**PROVENANCE PRIMITIVE LANDED**
