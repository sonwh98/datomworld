Created-GMT: 2026-11-18 18:08:33 GMT (delta-review dispatch)
Created-Local: 2026-11-19 01:08:33 +07 (Asia/Ho_Chi_Minh)

# Task: yang-dedup-round2-batch1-step7-review

Role: Reviewer (step-7, your round-1 thread continues)

You approved round 1 (all five extractions, landed as 1604aabe) and PROOF-FAILED the stale
round-2 residual (correctly — it was already landed). Now the REAL round-2 batch 1 is
implemented, per the plan you have not seen: argon's round-2 audit found 7 new clusters;
argon judged malleability conformance (ALIGNS WITH AMENDMENTS); astra reviewed dual-role
(architect + vm-engineer, DISPATCH WITH AMENDMENTS, no P0/P1). Batch 1 = C1 + C3 + the
atomic C2+prelude-admission-split unit. Both reviewer findings docs are in the worktree the
implementer used (collab/exception-track/1795020669393-…findings.md,
1795022027297-…findings.md, 1795022967614-…findings.md) — read them for the invariants.

## The implementation under review

Full patch (18 files incl. NEW yang/core/prelude_admission.cljc): piped after this prompt.
Key moves:
- C1: read-at/absent?/missing → siteset-store (5 publics; load-tree signature REPLACED
  with [handle root]); site-set-of/publish! → siteset-publication (5 publics; medium is a
  selection input, NOT a record field; :tree never serialized). The ::absent sentinel
  comparison moved WITH the reader. Consumers drop the yin.vm.encoder require.
- C2+split atomic: admission trio + private refusal → NEW yang.core.prelude-admission
  (3 publics, requires only yin.vm + yin.vm.module); core.module delegates declare-free-name
  to prelude-admission/host-declaration but keeps the covered-module check BEFORE delegation
  and the throw LOCAL with refusal-key as a VALUE; free-primitives iterates
  prelude/free-name-vec; core.module drops yin.vm/vm.module requires (out: 4 final);
  core.prelude drops vm requires (11 publics + 2 private); 6 caller-less publics across the
  codebase demoted to defn-; 8 consumers gained the prelude-admission require.
- C3: 6 missed call sites wired (module-tree ×3, build-vector/args-vector, app* alias,
  definitions->uast ×2 incl. core/call — linear tree + tail marking PRESERVED, astra's
  "do not substitute the balanced emitter" honored).

## Implementer's evidence

- Baseline (pre-change) vs post-change, fixed keys: "BYTE-IDENTICAL: linked prelude row
  bytes and addresses; site-set bytes and address; site record map and address.
  IDENTICAL: undeclared-name refusal ex-data; eval rejection envelope."
- Gates: focused 308/3507/0; separate 4-backend prelude coverage 222/2300/0; php include
  quick 10/46/0; diff --check clean.
- My (orchestrator) independent verification: public counts exact (store 5, publication 5,
  admission 3, prelude 11+2priv, module 8); core.module requires = clojure.string,
  core.prelude, core.prelude-admission, core.uast (matches astra's table); the one yin.vm
  mention in module.cljc is a docstring; my gate subset 163/1589/0 incl. yang.clojure-test.

## Your task

Review the patch: (1) fidelity of each shared body vs the deleted per-target bodies
(especially the C1 read-at unification — python's nil-vs-absent cond branches were unified
onto the php semantics; check the report's claim that the absent result is the same); (2)
the admission delegation preserves refusal ex-data EXACTLY (refusal-key as value, covered
check before delegation, throw local); (3) prelude-admission requires ONLY
yin.vm/yin.vm.module and nothing else leaked; (4) scope discipline — nothing beyond
C1/C2/C3 + the 6 demotions; (5) wrapper thinness. Read-only; final response is the
deliverable.

Begin exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

End with exactly one line:
APPROVED | CHANGES STILL REQUIRED — <why> | PROOF FAILS — <why>
