Created-GMT: 2026-11-10 17:33 GMT
Created-Local: 2026-11-11 00:33 +0700
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: architect adjudication #2 — the wire shape of a value-path refusal (ruling site #7)
Role: Architect (Lead System Architect lane, per the owner's routing ruling)
Implementers:
- Model: codex (astra) | Assigned: 2026-11-11 00:33 +0700 | Status: active | Rationale: owner directive — codex is available again and takes architect work; this is the semantics call the prior ruling under-specified.

This is a NARROW adjudication continuing
`collab/exception-track/1794320926000-architect-dsg-condition-r4-coupling.gemini-4-argon-high.findings.md`
(the argon ruling — read section 4 first). Answer ONE question, then give the fix
instruction. Do not re-adjudicate what argon already ruled.

Worktree: `/Users/sto/workspace/datomworld-r3-orch`, branch `exception-r3-impl`
@ `98519957` + uncommitted follow-up work (two more fix attempts sit in the tree;
read the diff, not just the commit).

## The state (verified by the orchestrator, not claimed by a delegate)

Argon's ruling: `:dsg__condition` is load-bearing (retained-condition provenance);
stamp `dsg__call_token`, read by token equality, `remark-tails` in compile-program.
Sites #1–#6 and the two readers are FIXED and verified (kit rows
`:clojure/protocol-*` green; `:clojure/shape-*` green). One site remains:

**Site #7 — `invoke-value`'s `:else`** (clojure.cljc ~2799, the guest collection
library's host invoker): a scalar callee, a raw unpublished closure, or a forged
shape reaches the library value path and must refuse `not-callable`.

Argon's instruction: "trigger a genuine not-callable refusal that stamps
`dsg__call_token` via compiler-generated code (or invoke `(f)` / a non-callable
refusal helper lowered by the compiler)". TWO implementations were attempted; both
fail differently, and the failures prove the instruction under-specified the site:

- **Attempt A** (`:else (f)` — guest apply): re-enters the call-site dispatcher →
  infinite recursion → `Guest instruction budget exhausted` (6 errors). Killed.
- **Attempt B** (`:else (throw (yang.call/condition :not-callable {...}))` — bare
  host throw): the condition map carries NO token (a host exception cannot carry a
  guest closure value). The thrown value crosses back into the guest and the
  nearest guest boundary — following §8.10.7, since the map lacks the token —
  wraps it: the observable result is
  `{:reason :yang.call/thrown, :value {reason :not-callable, data {...}}}`
  instead of the raw refusal `{reason :not-callable, data {...}}` the five
  pre-existing tests pin (`test-value-invocation-refusals-are-terminating` :2599/
  :2619/:2635 ×2 lanes, `test-intra-unit-call-lowering-guards-and-budget` :2736/
  :2766 ×2 lanes). Verified: 10 failures / 0 errors, all this one signature.

## THE QUESTION

**On the library value path (entry-absent callee refused by `invoke-value`'s
`:else`), what is the correct observable wire outcome?**

**(A) The raw refusal `{reason :not-callable, data {...}}` is the contract** — the
value path never had a raise exit, refusal-shape is part of the path's own
contract, and the compiler must deliver the token to the library some other way
(e.g. the library's `yang.call/condition` helper is invoked through a
compiler-provided adapter that stamps the token host-side; or the refusal is
lowered INTO the guest program rather than thrown from the host library). Under
(A): name the exact mechanism and the fix.

**(B) The `:thrown`-wrapped shape is the contract** — the value path is a host
boundary, a thrown condition-map without token is by construction a thrown VALUE,
and §8.10.7's wrap is correct; the five pre-existing tests' expectations are the
stale artifact and get updated to the wrapped shape (keeping their termination
assertions intact). Under (B): say explicitly why this does not violate R5
(the wrap is produced by the compiler's own boundary, not by source-writable
attributes) and why it does not break the R3 contract (a refusal is not a
"retained condition forwarded unchanged" — it is a new condition; R3 governs
forwarding, not first-construction).

Weigh especially: consistency with the already-green `:clojure/protocol-*` rows
(those refusals DO arrive as raw conditions — but they travel through
`yang.call/invoke`'s eligibility `yang.call/transfer` with a real raise exit,
which the value path lacks); the §8.10.7 rule; R5; and which choice makes R4's
token-channel story cleanest.

## Deliverable

One ruling: (A) or (B), with the reasoning above made explicit, plus the minimal
fix instruction (sites, mechanism, and what the five tests' expectations become).
Do NOT implement. If you find a third option better than both, rule it and say why.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
