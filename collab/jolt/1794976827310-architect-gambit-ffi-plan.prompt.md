Created-GMT: 2026-11-18 04:40:22 GMT
Created-Local: 2026-11-18 11:40:22 ICT (+07)
Coding-Agent: codex
Session-ID: pending (provider-generated; capture thread.started.thread_id)
# Task: design plan — FFI support for the Gambit Scheme target on jolt

Role: Architect
Implementers:
- Model: gpt-6-astra | Assigned: 2026-11-18 11:40:22 +07 | Status: active | Rationale: hardest design problem in the Gambit port; architecture ruling reserved to Architect per team.md

## Deliverable

A DESIGN PLAN (not an implementation): the staged path to make jolt's FFI capability tier work on the Gambit target, replacing today's intentional degradation. Write it to
`collab/jolt/1794976827310-architect-gambit-ffi-plan.gpt-6-astra.findings.md`
(in the repo checkout you are given). End with `DONE` on its own line.

## Context

- datom.world hosts yin.repl on jolt, a Clojure-to-Scheme compiler/runtime. The pinned
  jolt clone (materialized under build/tools/jolt by `bb bootstrap:jolt`) has a full
  Gambit host adapter: `host/gambit/` (~11k lines) + the porting contract
  `host/scheme-adapter/{CONTRACT.txt,TARGET-CONTRACT.md,chez.ss,guile.ss}`.
- On the Gambit target, ffi / native compilation / fasl / subprocess intentionally RAISE
  "unsupported on the gambit target"; the G1 gate `make gambitcheck`
  (host/gambit/gambitcheck.ss, rows ~430-465) ASSERTS those raises. Gambit 4.9.8 is
  provisioned via mise (asdf plugin builds from source) and the non-FFI gates
  (gambitcheck/gambitkernel/gambiteval) pass on macOS arm64 and Linux x86_64.
- The FFI consumers on the Chez host: jolt-crypto (libcrypto bindings, native/jolt-crypto,
  J1b qualification protocol) and the ANTLR C++ runtime wrapper (native/jolt-file,
  J4d/J5 probes). Chez spells blocking-call GC-safety `__collect_safe`.
- Pinned semantics that govern ANY FFI implementation (TARGET-CONTRACT.md):
  sa-foreign-procedure-native-error must return the C result AND the calling thread's
  errno atomically at the call boundary; sa-foreign-procedure-blocking must not stop
  other threads' GC; callables entering from C; dlopen via sa-load-shared-object.
  An absent capability raises; it never fakes.

## Questions the plan must answer

1. Feasibility ruling: can Gambit 4.9.8's FFI (c-declare/c-define, release/claim
   discipline, ___ATOMIC etc.) actually satisfy BOTH pinned rows (atomic errno at the
   boundary; blocking calls that don't stop other threads' GC)? Name the exact Gambit
   mechanisms and any row that CANNOT be satisfied on 4.9.8 (and the fallback: degrade
   that sub-capability explicitly instead of the whole tier?).
2. Design: where the sa-foreign-* syntaxes expand for Gambit (target-prims entry in
   jolt-core/jolt/backend_scheme.clj; adapter-side wrapper strategy for errno capture —
   e.g. per-call-shape C shims vs generic), and what the callable direction looks like
   (Scheme callbacks from C threads: thread-claim rules).
3. Gate strategy: how gambitcheck's "must raise" rows flip to "must behave like Chez"
   WITHOUT losing the refusal protection for not-yet-implemented capabilities; which
   new make targets (gambitffi* equivalents of zlib-native-smoke, ffi-load-failure-smoke,
   errno-check, error-kinds-check, static-native-smoke, ffi-duplicate-symbol-smoke);
   and the corpus bar (J1b libcrypto qualification + J4d/J5 probe families passing
   identically on Gambit).
4. Delivery mechanics: this touches the PINNED jolt clone — changes there go through
   declared patches (jolt-toolchain.edn :patches) and the upgrade gate. Plan the slice
   boundaries so each lands as a declared patch with its own gate: P1 adapter FFI tier +
   flip gambitcheck rows + new gates; P2 jolt-crypto on Gambit (J1b requalification);
   P3 ANTLR C++ wrapper on Gambit. For each: acceptance criteria, risks, and what
   stays Chez-only if it must.
5. Effort estimate per slice (relative to the ~11k-line Gambit adapter as unit) and the
   go/no-go criteria after P1.

## Method

You are READ-ONLY. Read the source in the checkout you are in (the jolt clone is at
build/tools/jolt — host/gambit/, host/scheme-adapter/, host/chez/*.ss FFI usage,
Makefile gate targets, host/chez/errno-check.sh etc.), plus native/jolt-crypto and
native/jolt-file in the repo. You may run read-only shell (grep, make -n, reading
Makefile targets) but do NOT run builds, gates, or tests, and do NOT modify anything.
Gambit 4.9.8 docs may be consulted from memory; do not fetch the network.

Begin the final response exactly with:
Completed-GMT: <actual GMT>
Completed-Local: <actual local>
Coding-Agent: codex
Session-ID: <your thread id>
