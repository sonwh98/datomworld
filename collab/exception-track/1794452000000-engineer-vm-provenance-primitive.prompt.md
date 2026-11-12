Created-GMT: 2026-11-11 16:30:00 GMT
Created-Local: 2026-11-12 00:30:00 +07 (+0700)
Coding-Agent: deepseek
Session-ID: b6862ea7-aae0-4c40-aa6d-6f0daeb1f49f
# Task: engineer — the VM provenance primitive (sol consult APPROVED WITH AMENDMENTS)
Role: Engineer (VM round; branch owner: the continuation-semantics orchestrator)
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-11-12 00:30 +0700 | Status: active | Rationale: knows the engine heap anatomy (did the seam removal, analyzed heap-write for the port); the consult's amendments map onto code you have already read.

## Read first

1. `collab/exception-track/1794451000000-consult-vm-provenance-design.gpt-6.1-sol.findings.md`
   — the ruling of record. Implement ITS design, not the earlier proposal: the field is
   **`:provenance`** (kernel-neutral), the doors are **`cell/dsg__new-provenance` `[value provenance]`**
   and **`cell/dsg__provenance` `[candidate]`**, and the five amendments are binding.

## The design (sol-approved, amended)

1. Heap entries gain an optional private sibling `:provenance` beside `:seal`/`:value`. The VM
   treats it as opaque data — no frontend dispatch, no reason matching, no rendering.
2. Effects `:cell/new-provenance` (fresh id + seal + value + provenance, atomic) and
   `:cell/provenance` (authenticate, then return the stored provenance; nil for anything else —
   never fatal, never mutating). Module profiles declare arities `[2]` / `[1]`
   (module.cljc:388 pattern).
3. `heap-write` PRESERVES `:provenance` while updating `:value`/`:seal`. `cell/set!` does NOT
   refuse — visible contents stay ordinarily mutable (the declined seam's refusal is not
   reimplemented; the condition no longer lives in `:value`, so the refusal's purpose is gone).
   No operation attaches/replaces/clears provenance on an existing entry — only the mint, at
   allocation.
4. **GC (sol amendment 3 — the subtle one)**: trace `:provenance` as data from every live entry
   (engine.cljc trace ~:2577), from pinned entries (~:2615), and hold value+provenance as extra
   roots at allocation-triggered collection (~:2659). Do NOT pin entries merely for carrying
   provenance. Payload cells referenced only by a retained condition stay reachable while the
   wrapper is; the whole cluster becomes collectible when the wrapper is.
5. **Macro + transitive-caller closure (sol amendment 4 — LANDING REQUIREMENT)**: source AND
   macro-admission must reject guest-created executable references/bindings/definition targets
   of the two doors — exercise the actual macro/REPL expansion path, including returning a door
   as a value for indirect application. A raw-source spelling test is INSUFFICIENT. Also:
   protected reserved names for any transitive mint helper (the `py/condition-exception`
   lesson — an exported helper accepting arbitrary condition data reopens the hole).
6. Update `src/cljc/yin/vm/docs/state.md` heap-entry shape (:119).

## Scope

Branch `exception-vm-provenance`, cut from current origin/master (verify with `git fetch &&
git log --oneline -1 origin/master` — master has moved; cut fresh). Files:
`src/cljc/yin/vm/engine.cljc`, `src/cljc/yin/vm/module.cljc`, `src/cljc/yin/vm/docs/state.md`,
and VM test namespaces (cell, heap-reclamation, module, effect-forgery suites + your new unit
tests). NOTHING else — python/php/clojure adoption is LATER rounds by their seats.

## Acceptance

1. Your unit tests: mint/write-once/read/query-nil-for-unmarked, set!-preserves-provenance,
   content-copy-loses-provenance, ref-copy-retains-rethrow-authority.
2. GC probes (sol amendment 3): provenance-only payload reachability across ordinary/pinned/
   allocation-triggered collection; eventual reclamation when the wrapper drops.
3. Macro-door access test + exported-helper bypass test (the landing requirement).
4. The VM seam suites green: cell, heap-reclamation, module, effect-forgery + all lane suites
   (semantic, de Bruijn stack+register, parity, rule-r, mint).
5. `clj -M:kondo --lint` on your files — 0/0.
6. **NEVER run `yang.call.exception-contract-test` locally** (shiva-only, owner ruling). The
   orchestrator runs kit + forgery-pins verification remotely.

Commit + push `exception-vm-provenance`. Foreground only; no background server tasks.

## Report

Begin with Completed-GMT/Completed-Local; end with the SHA and one line:
**PROVENANCE PRIMITIVE LANDED** or **BLOCKED: <what>**.
