Created-GMT: 2026-11-07 03:30:00 GMT
Created-Local: 2026-11-07 10:30:00 +07 (+0700)
Status: OWNER-APPROVED 2026-11-07 — "enforce everywhere consistently. this should be a separate track."

# RULING OF RECORD — the exception track: §8.10.7 enforced uniformly in all four frontends

## The decision

Exception handling is enforced uniformly across yang.clojure, yang.typescript (js),
yang.php, and yang.python. Every escaping source exception — from any frontend, through
any boundary — compiles down to THE SAME continuation: the caller's supplied raise exit
receiving the §8.10.7 condition

    {:yang.call/reason :yang.call/thrown, :yang.call/value original-thrown-value}

and every receiving runtime implements the §8.10.7 reception rules (1)–(3) as written.
This is a SEPARATE TRACK: it does not ride any frontend's ongoing slice; it has its own
folder (`collab/exception-track/`), its own rounds, and its own acceptance gate. The four
frontend seats cede these exception-fix surfaces to the track's rounds; the track files
per-frontend results back into each seat's records.

## The uniformity requirements (each is testable)

**R1 — the callee bridge (js, php are non-compliant today).** Every frontend's published
function entry (and closure/higher-order entry) installs the exception bridge: it reads
the supplied `:yang.call/raise` and delivers every escaping source exception AND argument
binding failure to it as the §8.10.7 condition. No frontend may let an escaping exception
follow the host engine's own handler stack (js today, prelude.cljc:1581), and no frontend
may return an uncaught error as an ordinary value (php today, prelude.cljc:190/2384).

**R2 — foreign reception (python, php are non-compliant today).** On receiving a thrown
condition: a payload valid in the receiving runtime keeps that runtime's ordinary
catching and identity behavior; a runtime permitting arbitrary thrown values (js) may
deliver the payload raw; a runtime requiring exception objects (python, php) represents a
foreign payload with its own generated `ForeignException`, retaining the complete
condition and original payload — catch-all catches it, typed catches match the LOCAL
hierarchy, never guessed foreign class names. Raising the raw envelope (php:2384) or the
raw payload (python:1034) is non-compliant.

**R3 — protocol failures retain the condition.** `:yang.call/not-callable`,
`:invalid-invocation`, `:unsupported-binding` map to a documented local exception form
retaining the complete original condition, so an unchanged rethrow can forward it. A fresh
local error containing only rendered text (js:1627, python:1046) is non-compliant.

**R4 — clojure unifies.** The internal outcome-token channel
(clojure.cljc:1110/1486) may remain an optimization ONLY with a proof of equivalence at
every boundary; absent that proof it unifies onto the raise-exit path. Clojure's `try`
rejection of `finally` and its single-catch restriction are source-semantics decisions
THIS RULING DOES NOT CHANGE — R4 is about the continuation, not clojure's catch surface.

**R5 — no new shape-routing.** Nothing in the track may route on the condition map's
shape beyond the §8.10.7 contract; a lookalike map stays ordinary data (the cell-ref
ruling's rule, restated). No synthesized engine-effect exception values.

## What the audit establishes (the baseline)

The gpt-6.1-sol audit (collab/yang-python/1794900000000, verdict DIVERGENT, HEAD
e9a0b430) is the track's baseline evidence: python hooks the raise exit (R1 compliant);
js and php lack the bridge (R1); python and php lack ForeignException (R2); js and python
lose protocol conditions (R3); clojure has the private channel (R4). Zero cross-language
§8.10.7 legs exist in the conformance kit today.

## Acceptance gate

The conformance kit (`test/yang/call/conformance.clj`) grows exception legs — the
§8.10.11 exception row as acceptance: binding failures, arbitrary foreign thrown data,
catch-all/typed catches, unchanged rethrow, finally replacement, protocol-error rethrow,
condition-shaped thrown maps staying data, A→B→A propagation with identity and cleanup
order, and the stored-ForeignException rethrown-from-another-activation case. The track
is DONE when every admitted pair passes those legs with real source on both sides, and
the divergences R1–R4 are each closed by a focused test pinning the compliant behavior.

## Sequencing

Architect review of this ruling, then an engineer mob review, then per-frontend fix
rounds (smallest first: R3 js/python, R1 php, R2 python/php, R4 clojure), the conformance
legs landing alongside the fixes they gate. The known-gaps row for this track is filed
from the audit's divergence list.

— filed by the yang.python orchestrator seat on the owner's instruction
