Created-GMT: 2026-11-11 01:30:00 GMT
Created-Local: 2026-11-11 08:30:00 +07 (+0700)
Coding-Agent: interactive (ZCode / deepseek-flash, continuation-semantics orchestrator seat)
Owner decision requested: ONE question. Nothing merges until you answer or until the shiva
contract numbers independently prove the same thing.

# OWNER DECISION — may yang.clojure's fix add a VM-level retained-cell mark?

## How we got here (one paragraph)

Clojure R3 (protocol-condition retention through the public escape) took four engineer rounds and
three independent confirmations. Each round's tests were green; each reviewer found a real defect
the tests could not see: a token transplant (R5), then a payload-identity regression that turned a
Round-0 green cross-language row red, then content replay/mutation plus seven observation leaks.
A three-architect mob was convened on whether a wrapper can ever be observationally identical to
its payload: **2/3 ruled (A)** — deliver ordinary `:yang.call/thrown` payloads RAW (so the caught
value IS the payload and every leak closes by construction), and keep the wrapper ONLY for
qualified protocol conditions, bound to heap identity. muse-wonder (the dissenting (B) author)
implemented (A) and returned **"(A) DESIGN HOLDS"**; glm-5.3 (author of the (A) rationale)
confirmed it independently: **CONFIRMED**.

## What the fix does now (`fbd8da0f`, branch `exception-r3-impl`)

1. `src/cljc/yang/clojure.cljc` — ordinary payloads raw; wrapper for protocol conditions only;
   the per-call-site router and the witness closure DELETED. This half alone closes all seven of
   the reviewer's observation leaks and restores the Round-0 round-trip row.
2. `src/cljc/yin/vm/engine.cljc` + `module.cljc` (+82 lines) — an issuer-side `:retained` mark on a
   heap entry, written only by a compiler-internal `:cell/new-retained` effect published as
   `cell/dsg__new-retained` (a name guest source cannot spell); `:cell/set!` refuses marked cells;
   a pure `cell/retained?` query beside `cell/authentic?`.

## The decision

glm's adjudication, verbatim in substance: *the seam is SAFE (521 tests / 6,188 assertions green
across the VM seam and all lanes; no invariant breakage), but AUTHORIZATION NEEDS-OWNER — the
mob's (A) included this seam, while the track's written authorization named only the (B) fallback.*

**Question for the owner: may the cell module publish the issuer-side retained mint/query, and may
`:cell/set!` refuse marked cells — i.e. may R3 touch yin.vm at all?**

- **YES** → clojure R3 merges as `fbd8da0f` (pending the shiva contract numbers), retention is
  identity-bound and unforgeable by construction, and the `:retained` mark becomes a documented VM
  seam with a `state.md` heap-shape correction.
- **NO** → the raw-payload half still lands (it is separable: revert the two wrapper forms to the
  witness versions). Consequence: protocol-condition retention keeps the witness-but-not-identity
  binding, so the content-replay/mutation finding from round 3 stays OPEN and must either be
  accepted as a known limitation (with the `:forged-retention` row documenting it) or deferred to
  a later VM round.
- **DEFER** → I hold `fbd8da0f` unmerged, land the raw-payload half alone, and the VM seam becomes
  a separate proposal.

## Evidence trail

- Mob record: `1794369000000-mob-record-r3-retention-design.md` (2/3 (A), 1/3 (B), votes converge)
- Design: `1794366000000-architect-mob-r3-retention-design.glm-5.3.findings.md`
- Implementation verdict: `1794370000000-engineer-r3-glmsplit-implementation.muse-wonder-1.0.stdout.log`
  ("(A) DESIGN HOLDS", 103 tests/993 assertions)
- Independent confirmation: `1794371000000-reviewer-r3-A-design-confirm.glm-5.3.findings.md`
  (CONFIRMED; seam SAFE; authorization NEEDS-OWNER; separable)
- Foundational finding: `1794365000000-reviewer-r3-identity-confirm.gpt-6-astra.stdout.log`

---

## DECIDED: **NO** (owner, 2026-11-11 16:3x +07, in-session — verbatim: "NO")

yin.vm stays untouched. The `:retained` heap mark, `cell/dsg__new-retained` mint, `cell/retained?`
query, and the `:cell/set!` refusal on marked entries are NOT authorized.

### Consequences recorded

1. R3 lands as the **raw-payload half only**: ordinary `:yang.call/thrown` payloads delivered raw
   (closes all seven observation leaks; restores the round-trip row), protocol conditions retained
   by the **witness mechanism** (c9c5533c's complete-contents closure), NOT heap identity.
2. **Known limitation, documented here and in the ledger**: a guest that catches a protocol
   wrapper can mutate its contents via `cell/set!` and substitute a different condition; the
   boundary then forwards the substituted condition. Python/PHP do not have this hole (their
   `ForeignException` is a native object with private state). This is a deliberate, owner-accepted
   trade to keep yin.vm minimal.
3. **R4's equivalence proof gains the decisive scenario**: does the outcome channel deliver
   protocol-condition retention as securely as the continuation lane? The replay/mutation probes
   (astra's battery) join the §8.10.9 scenario set. **Failure there triggers the owner's
   pre-authorized (B) fallback** — under which the protocol carrier is expected to be a
   closure-based immutable form (no VM change needed either).
4. The rationale, owner's words in substance: adding VM lines to compensate for one frontend's
   encoding inconsistency cuts against yin.vm's minimal-kernel design; the inconsistency is
   handled at the layer that created it.

---

## FINAL DIRECTIVE (owner, 2026-11-11 17:0x +07, in-session) — SUPERSEDES the earlier reading of "NO"

Owner, verbatim in substance across three messages: the "NO" vetoed **the divergence itself** —
"yang.clojure exception should converge. it should not be the exception." The VM seam was declined
because kernel lines compensating for an encoding the owner does not want felt wrong — not because
the outcome encoding should be kept.

**Decision of record: (B) TRIGGERS NOW.** yang.clojure's exception lowering converges onto the
continuation encoding (the py/ts/php `%capture` raise-exit scheme). No VM change. R4 transforms
from an equivalence proof into **port verification**; the earlier "(A) now, (B) if R4 proof fails"
sequencing is superseded by this explicit directive.

### The decisive argument (accepted by the owner through this exchange)

Real Clojure — JVM, cljs, dart, jolt — uses dynamic unwinding with non-cooperating frames
(throw-through-HOF works, `finally` runs from arbitrary depth), exactly the python/ts/php
semantics class; `recur` exists because those hosts lack TCO. yang.clojure's outcome model was a
divergence from **real Clojure itself**, forcing the restricted dialect (one catch, no finally,
opt-in outcomes). The port moves yang.clojure toward real Clojure semantics while yin.vm's native
TCO + loop/recur (already compiling to true tail calls) preserves constant-depth iteration under
any encoding.

### Binding acceptance criteria (from the 2/2 depth mod, 1794396000000)

1. LaTTe depth harness built FIRST; pre-port baselines recorded (the harness collects no depth today).
2. The intra-unit private tail lane is PRESERVED in the port design (deepseek's remedy — no VM change).
3. Uniform four-frontend probe: assert constant case only (`D(16)=D(64)=D(256)`, loop/recur +
   handler-free chains); MEASURE the linear case at 8/32/128 × three categories; never require
   growth contractually.
4. Escalation thresholds to the owner BEFORE merge: any LaTTe workload >10× pre-port max depth or
   >10,000 frames post-port.
5. PHP's missing R1 bridge noted — "every published entry" becomes true for all four frontends
   when the php round lands; the uniformity this directive asks for is then complete.
