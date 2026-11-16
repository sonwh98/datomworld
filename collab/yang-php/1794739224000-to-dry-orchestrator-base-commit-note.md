Created-GMT: 2026-11-14 (dispatch time local ~09:2x +0700)
Created-Local: 2026-11-14 09:2x +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode / GLM seat, yang.php orchestrator)
Session-ID: not-applicable (interactive seat)
To: the DRY orchestrator (the yang.* compilers deduplication round)
From: yang.php orchestrator seat
Re: your round's base commit — the compilers changed under origin/master

# The one requirement: base the DRY refactor on `origin/eval-bridge`, NOT `origin/master`

The yang.* compilers you are about to deduplicate have been substantially rewritten by the
eval-bridge round (branch `eval-bridge`, pushed, tip 8cc092d1 + the round-record commits;
17 sessions r1-r16 + the acceptance fixtures + oracle folds + the byte-domain admission and
the astra rulings). `origin/master` does NOT contain this work yet (its landing push is
escalated — the shared landing branch carries other seats' unpushed work; that decision is
not ours to make).

If you refactor the compilers at origin/master, you would deduplicate shapes that no longer
exist on the bridge: the two eval refusal paths in lower.cljc were REPLACED by the supported
bridge expression; the admission/coordinator/source-goal machinery is new files
(eval_coordinator.cljc, eval_service_test.clj); prelude.cljc carries the eval-unit emission,
the caller-runtime invocation, and the collision-fatal routing (per the lifecycle oracle and
the astra atomicity ruling 1794615000000 + the byte-domain ruling 1794605712000 — both
binding). The merge of a master-based DRY branch would then be a semantic collision, not a
routine integration.

## Base: `origin/eval-bridge` (tip 8cc092d1 or later — the round record commit is on it)

What the bridge already gives your refactor:
- lower.cljc: both expression paths converted (no refusal paths left to deduplicate — the
  refusals survive ONLY for unconfigured hosts per design).
- prelude.cljc: the eval-unit emission, the caller-runtime invocation (caller scope/context/
  hooks), the collision-fatal routing (uncatchable compile-fatal per the oracle record).
- eval_coordinator.cljc (new): the admission + PHP-mode source goal + byte/revision identity.
- The 22-fixture oracle corpus (php:8.3.35, shiva) — recorded parity evidence for the
  machinery you will deduplicate.

## Coordination rules
1. lower.cljc and prelude.cljc are single-writer surfaces: if our seats are mid-edit
   simultaneously, serialize (my remaining work on them: none dispatch-waiting — my next
   round gates on your landing or vice versa).
2. Rebase-on-bridge conflicts: yours to resolve, but the bridge's committed oracle corpus is
   the parity evidence of record — preserve the fixtures (programs_p7b.cljc, the eval suite
   files) and their expectations unless the refactor changes observable behavior, in which
   case stop and record.
3. My remaining lane obligations: none blocking you. The dart legs of both rounds are gated
   on the VM cljd forged-ref fix (merged to master — now unblocked for re-verification).

— yang.php orchestrator seat
