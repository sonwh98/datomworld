Created-GMT: 2026-11-18 16:03:59 GMT
Created-Local: 2026-11-18 23:03:59 ICT (+07)
Coding-Agent: glm
Session-ID: zcode-subagent-p1b-inventory
# Task: P1b-prep — jolt FFI construction-site inventory + typed-shim registry

Role: Compiler & AST Engineer
Implementers:
- Model: glm-5.3-flash | Assigned: 2026-11-18 23:0x:xx +07 | Status: active | Rationale: bounded static-analysis slice on the flat lane

## Mission

Produce the FFI construction-site inventory and initial typed-shim registry that
astra's design (§2) requires BEFORE any adapter code is written. Pure static
analysis of the jolt fork source — you write NO adapter code, change NO behavior.

## Paths

- Work dir (yours): ~/workspace/jolt-ffi-inv (jolt fork worktree, branch
  gambit-ffi-inv @ a6852ef8). Commit ONLY the inventory artifact(s) you create
  under p1b-inv/ on that branch. Never push. Do NOT touch ~/workspace/jolt,
  ~/workspace/jolt-ffi-p1 (another agent owns it), or the datomworld checkout's
  tracked files.
- Design ruling (read first, §1-§2 especially):
  /Users/sto/workspace/datomworld/collab/jolt/1794976827310-architect-gambit-ffi-plan.gpt-6-astra.findings.md
- Contract context: /Users/sto/workspace/datomworld/build/tools/jolt
  (host/scheme-adapter/CONTRACT.txt + TARGET-CONTRACT.md; host/chez/*.ss for the
  Chez implementations being mirrored). Note astra's finding that the contract's
  "four syntaxes" wording is INCOMPLETE — inventory what exists, not what the doc
  promises.

## Deliverables (commit under p1b-inv/ on gambit-ffi-inv)

1. `p1b-inv/ffi-call-sites.md` — every site in the fork where a foreign call can be
   CONSTRUCTED (compile-time expansion and runtime construction):
   - the four sa-foreign-* syntaxes' expansion paths in the compiler
     (jolt-core/jolt/backend_scheme.clj — astra cited line ~2134 for scoped
     symbol emission; find all others), plus backend primitive tables
     (:chez target-prims entry ~line 297; what a :gambit entry must carry);
   - runtime-signature construction (host/chez/java/ffi.ss ~1294 varargs and
     everything like it), scoped-address calls, aggregate/ftype emission;
   - for EACH site: file:line, which capability tier it belongs to (ordinary /
     blocking / native-error / callable / scoped-address / varargs / aggregate),
     whether it emits Chez-specific forms directly (and what those forms are),
     and whether the Gambit path can route it through a typed shim or must
     deliberately raise (astra's rule: unregistered shapes raise).
2. `p1b-inv/shim-registry.edn` — the initial typed-shim registry: one entry per
   distinct ABI shape found in the consumer inventory (argument/result types,
   calling convention, variadic boundary, native-error variant or not, blocking
   or not), referencing the sites that produce it. Shapes that no current site or
   fixture produces are NOT registered (they raise by design).
3. `p1b-inv/CONSUMERS.md` — the native consumers' binding surfaces as the shim
   registry's seed inventory: jolt-crypto (all libcrypto bindings incl. the
   twenty audited entries + RAND/SHA-1 astra names), jolt-file (POSIX wrapper,
   J4a-J4d surfaces), jolt-parser (ANTLRA C ABI incl. jolt_parser_worker_free's
   blocking-mark question astra raised). One row per binding: symbol, ABI shape
   id (linking to shim-registry.edn), blocking?, native-error?, callable?
4. Findings file (NOT committed to the fork):
   /Users/sto/workspace/datomworld/collab/jolt/1795020000000-engineer-p1b-inventory.glm-5.3-flash.findings.md
   — standard header, summary of counts (sites, shapes, consumers), surprises
   (sites astra didn't name), and open questions for the P1b designer. End with
   DONE on its own line.

## Rules

- Static analysis only: grep/read/EDN-generation; NO builds, NO test runs, NO
  behavior changes. You may run the existing jolt test suite? NO — nothing.
- Evidence quality: every claim carries file:line. If a path is dynamic
  (runtime eval building foreign procedures), trace where its inputs come from
  and mark it DYNAMIC with the construction entry point.
- Cross-check astra's citations (backend_scheme.clj:2134, host/chez/java/ffi.ss:1294,
  scheme-adapter-runtime.ss:321-459 refusals, sa-gc-collect no-op ~81) and note
  any that don't hold on THIS fork SHA (the fork is ~920 commits past datomworld's
  pin — line numbers may have moved; find the real ones).
- Final response starts with Completed-GMT / Completed-Local / Coding-Agent /
  Session-ID and gives the headline counts + top surprise.
