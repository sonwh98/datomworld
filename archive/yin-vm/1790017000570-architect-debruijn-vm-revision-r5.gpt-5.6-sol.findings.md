Completed-GMT: 2026-09-21 18:59:10 GMT  
Completed-Local: 2026-09-22 01:59:10 +07  
Coding-Agent: codex  
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a

SIGN-OFF: READY for owner decisions and commit as a design document.

Sections changed:

- §1: corrected artifact terminology, optional lossless view, benefit and exit
  criteria.
- §2: scalar coverage, descriptor/lift contract, canonical image identity.
- §3: diagnostic provenance rule.
- §4: optional macro metadata, continuation state and environment boundary.
- §6: B0/B1/B2/B3/B4 criteria and adapter location.
- §7: matrix wording.
- §7.1: upstream lambda-lifting/ANF wording and tail-flag obligation.
- §7.2: linker and image terminology.
- §7.3: optional lossless source view.
- §8: current owner-decision ranking.

Rejected items:

- No review finding was rejected outright.
- The proposed permanent replacement of the sibling VM with one shared
  instruction implementation was not adopted now. It would prematurely alter
  the reversible experimental architecture; the post-B3 end state remains an
  owner decision.
- The lossless DAG was not silently removed. It is now explicitly optional and
  outside B0-B5; retaining or dropping it remains an owner decision.

Remaining owner decisions, ranked by B0 impact:

1. Exact result, error, closure, continuation, stream, cursor, and store
   normalizer. This is the only direct B0 blocker.
2. Whether to retain the optional lossless source DAG; if retained, choose its
   stable replacement for emitter-local tempids and treatment of non-`:yin`
   attributes.
3. Approve the executable scalar domain, descriptor, and lift morphism.
4. Fix the named VM environment leak or retain the stated cross-program
   park/resume restriction.
5. Define what `:call-hash H` identifies and whether a descriptor or contract
   stamp is included.
6. Define linker authority, trust/provenance, SCC identity, and retry, timeout,
   and permanent-absence policy.
7. After B3, decide whether the frame VM becomes default; retiring the semantic
   VM requires a separate design.
