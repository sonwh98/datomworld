Created-GMT: 2026-11-10 23:27:47 GMT
Created-Local: 2026-11-11 06:27:47 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: 01a1c79a-399d-72e1-84c0-19a5a05f0563 (resumed thread; verdict: FIX-LIST round 2)
# Findings: JS R1+R3 fix-round confirmation

I’ll check the fix and probe coverage against the original R5 requirement, then rule on the seal-extraction residual. I’ll keep this read-only and check whether closing it actually requires an ambient registry.
The fix closes minting from scratch and copying a wrapper’s contents. P2 and the standing scope rulings were honored. But a wrapper-holder can still overwrite the companion’s condition directly; no seal extraction is needed for that attack. I’m ruling that this remains inside R5, and will specify a narrowly scoped private identity store as an acceptable repair direction.
Completed-GMT: 2026-11-10 23:27:47 GMT
Completed-Local: 2026-11-11 06:27:47 Asia/Ho_Chi_Minh

Reviewed `ce99e65e` read-only against `c0114a42`. No files edited or gates rerun.

**P1 — NOT CLOSED; remains P1.** The fix blocks minting from scratch and copying complete wrapper contents. However, [prelude.cljc:1684](/Users/sto/workspace/datomworld-jsr13/src/cljc/yang/typescript/antlr/prelude.cljc:1684) trusts mutable companion contents after checking the wrapper back-reference and seal. A holder can replace the condition while preserving both checks:

```clojure
(let [p (get (cell/get wrapper) :yang.call/retained-provenance)]
  (cell/set! p
    (assoc (cell/get p) :yang.call/condition forged-condition)))
```

Rethrowing that same wrapper then forwards `forged-condition`. This attack requires neither extracting the seal nor minting another wrapper. Seal extraction additionally permits manufacturing new accepted wrappers.

The new probes cover the two attacks they assert, but do not cover these remaining paths. Their attack construction uses synthetic guest UAST around real JS source; describing it as parsed Clojure-source coverage overstates the evidence.

**P2 — CLOSED.** [prelude.cljc:1586](/Users/sto/workspace/datomworld-jsr13/src/cljc/yang/typescript/antlr/prelude.cljc:1586) now correctly distinguishes restoration ordering from the pending suspension/lifetime proof.

The new retention comment at [prelude.cljc:1660](/Users/sto/workspace/datomworld-jsr13/src/cljc/yang/typescript/antlr/prelude.cljc:1660), however, incorrectly says admitted source cannot obtain the seal. Following a returned wrapper’s cell references exposes it. Correct that claim with the P1 repair.

**Standing rulings — honored.**

- Missing/invalid raise exits remain operational failures; no guest fallback was added.
- `js/construct-with-nt` remains outside the claimed ordinary-call bridge.
- The accepted `globalThis.saved` donor tweak is unchanged.
- Suspension, recursion, and completed-call exit lifetime remain open acceptance obligations.

**Residual ruling — (b): inside R5; not an accepted design limit.**

Possessing a genuine wrapper grants the ability to retain and rethrow that wrapper. It must not grant authority to replace its retained condition or authenticate unrelated wrappers. Receiving and storing wrappers is an expressly required use case, so possession cannot exclude this attack from the threat model.

Reading (a) would also reverse the original review’s boundary: admitted raw cell operations were precisely why source-writable metadata could not establish provenance. Adding another readable, writable cell does not change that authority boundary. The engineer’s impossibility claim is not established; it establishes the limitation of this particular companion-cell representation.

**Accepted mechanism direction:** I authorize a narrow exception to the registry prohibition for an explicit, prelude-private, task-owned identity association:

- Key it by the genuine wrapper reference; retain the original complete condition as authoritative data.
- Keep its storage root inside opaque prelude closure state, unreachable through wrapper cells, source properties, or exported registration/replacement operations.
- Authenticate by private identity membership. Wrapper-supplied metadata must not establish or replace membership.
- Preserve associations across completed calls and catches, with an explicit retention/reclamation policy. Process-global and cross-task registries remain prohibited.

Record this as a narrow amendment to the no-registry constraint, not a claim that the current implementation satisfies it.

Before sign-off, add probes proving that companion mutation cannot substitute the original condition, and that a new wrapper stamped with an extracted genuine seal remains ordinary thrown data. Preserve the existing genuine-wrapper lifetime and forgery regressions.

**Final verdict: FIX-LIST.** P2 and the standing rulings are satisfied; R5 provenance integrity still blocks sign-off.
