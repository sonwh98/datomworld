Created-GMT: 2026-10-05 09:45:00 GMT
Created-Local: 2026-10-05 16:45:00 +0700
Coding-Agent: claude (fable-5-1, resume of session ff5b8c32-5cb0-4d81-9476-0a88c6109319)
Session-ID: ff5b8c32-5cb0-4d81-9476-0a88c6109319

# Task: rule on D4's two stop conditions (read-only; rulings are the deliverable)
Role: Architect

The D4 engineer stopped before editing, on your r3 plan's own stop
conditions. Their report:
collab/1791194500000-vm-engineer-ucf-d4-engine-r1.findings.md (also in
the worktree datomworld-d4's collab/). Rule on both, and amend the D
plan (r4 delta only, in the same message) where the ruling changes it.

1. **The FFI and link request appends are not in engine.cljc.** The
   link append is `module/append-link-request` (module.cljc, reached
   through `require-handler`); the FFI append is inlined at the
   `:ffi-call` site of each kernel — semantic.cljc,
   debruijn/stack.cljc, debruijn/register.cljc, ast_walker.cljc
   (`park-and-call`). Gating them means touching module.cljc and the
   four kernels, wider than D4's diff. Options: widen D4's file list;
   or move the FFI/link appends to D5/D6 (and say which round and what
   the D4 zero-call test then covers). Reconcile with 1.2's claim that
   every observation path is gated and with the D4/D5/D6 zero-call
   lists — an ungated append is an unfenced effect.

2. **Held `:stream/poll` and `:stream/cursor` observations have no
   representation in the existing wait grammar.** `:stream/cursor` has
   no wait reason (handoff.cljc `observed-wire` would see no variant),
   and `:stream/poll` cannot reuse a `:next` entry (the sweep would
   resume it with a read value, not the held observation; the extra
   key it needs is unrepresented). r2/r3 forbid a new wire variant.
   The engineer offers: refuse poll and cursor under `:running`, or
   have the driver perform them with no parked record. Rule on the
   representation (machine value, not wire): what holds the
   observation, what the sweep must skip, how apply installs it, and
   what `observed-wire` does if such a task is exported anyway (r3's
   rule: export refuses any task holding one). Reconcile with residual
   1 (cursor sources carry the requested origin) and with 7.4.1/7.4.3's
   liftable-safepoint amendment.

Answer each with the exact machine-facing contract and the plan delta.
Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
