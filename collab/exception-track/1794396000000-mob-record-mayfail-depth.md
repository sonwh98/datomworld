Created-GMT: 2026-11-11 12:00:00 GMT
Created-Local: 2026-11-11 19:00:00 +07 (+0700)
Status: MOB RECORD — 2/2 CORRECT WITH AMENDMENTS (astra + deepseek-flash, independent); steward
synthesis follows. Supersedes the steward's framing in this thread where amended.

# MOD RECORD — the may-fail chain depth claim

Brief: `1794395000000-architect-mod-mayfail-depth.prompt.md`. Verdicts:
`…gpt-6-astra.stdout.log`, `…deepseek-flash.stdout.log`.

## Where both architects agree (the corrected claim of record)

1. **Bottom line holds**: the continuation encoding's non-tail try bodies make may-fail recursion
   grow linearly; this is currently unmeasured for py/ts/php at the kit level; the yang.clojure
   port inherits it; a measured uniform probe is warranted.
2. **The outcome model is ALSO non-tail through nested `try`** — both architects found this
   independently. Its constant depth covers only HANDLER-FREE tail chains (chains whose
   intermediate calls don't open a try). So "may-fail chains" was over-broad in the steward's
   framing: the distinguishing property is handler-free tail propagation, not may-fail per se.
3. **"Every published entry" is currently false for PHP**: its function/Closure entries tail-apply
   `:code` with no bridge — a known R1 implementation gap, not a safe elision. The claim becomes
   true for php when its round lands the bridge. (Growth in php today comes from return escapes.)
4. **Native-runtime parity is the wrong justification**: CPython 3.11+/V8/Zend use exception
   tables (zero-cost — entering a try adds no frame; astra measured Python/Node with-try vs
   without: no additional logical frames). Deepseek adds the design-level point: yin.vm
   deliberately makes continuations heap data with no overflow, so the encoding is honest to the
   DESIGN, not to the source runtimes.
5. **The uniform probe**: assert ONLY the constant case (loop/recur and handler-free chains:
   `D(16)=D(64)=D(256)`); measure and report the linear case (depth increments at 8/32/128 across
   three categories: outer-catch calls, cross-unit published calls, nested handlers) with an
   upper-bound ceiling — NEVER contractually require growth ("a lower-bound assertion requiring
   growth would reject a correct optimization"). Also: python already has a loop-safepoint depth
   pin (safepoint_test.cljc:420) — the "no probe exists" claim is narrowed to handler-chain probes.
6. **Neither architect would override the owner's veto on architectural authority.** Both treat
   the LaTTe depth measurement as the evidence gate before the port merges.

## The amendments that change the port brief

1. **The regression is BROADER and SHARPER than "handler scopes"**: deepseek — entry bridges AND
   return escapes make **every value-returning recursion across published entries** linear; loops
   are constant only because the loop self-call sits outside the try body. Astra: "depth
   proportional to simultaneously active scopes/bridges, not completed try executions."
2. **The genuinely first-order workload class** (both): search/backtracking engines — long
   deterministic tail-call stretches between choice points where failure is normal control flow.
   `loop`/`recur` restructuring is NOT a local substitution for mutual/supplied functions.
3. **The remedy without the VM seam** (deepseek): **preserve an intra-unit private tail lane in
   the port** — intra-unit calls that don't cross a published boundary keep direct tail
   application; only published/cross-unit entries take the bridge frame. No VM change; consistent
   with the declined seam.
4. **The owner-visibility threshold** (deepseek): any LaTTe corpus workload whose max continuation
   depth grows >10× pre-port, or exceeds 10,000 frames post-port, must be shown to the owner
   before the port merges. Astra's version: outcome baseline completes within an agreed resource
   budget where the port does not, AND extensibility prevents a local rewrite — with the actual
   call path, sizes, peak depth, memory, runtime recorded on both implementations.
5. **The LaTTe harness lacks depth collection** (astra, verified at latte_oracle_test.clj:239) —
   a depth-collecting harness is a PREREQUISITE of the port's acceptance, not a re-run.
6. **Acceptance tree must be identified-clean** — the voided contaminated result cannot establish
   anything (astra reiterated against the acceptance report's correction).

## Steward synthesis

The port proceeds under the owner's clarified veto, with these amendments as binding acceptance
criteria: (i) LaTTe depth harness built FIRST, baselines recorded pre-port; (ii) intra-unit
private tail lane preserved in the port design (no VM change); (iii) uniform probe asserts the
constant case, measures the linear case; (iv) the deepseek thresholds escalate to the owner before
merge; (v) PHP's missing bridge noted — the "every published entry" property of the unified
encoding becomes true when the php round lands, completing the uniformity the owner asked for.
