Created-GMT: 2026-11-11 02:15:00 GMT
Created-Local: 2026-11-11 09:15:00 +07 (+0700)
Coding-Agent: deepseek
Session-ID: e33b854a-394d-4c93-bfb8-1d6ad921b0fe
# Task: engineer — R3 protocol rows: the public entry DOUBLE-WRAPS a retained condition
Role: Engineer
Implementers:
- Model: pending lane | Assigned: 2026-11-11 09:15 +0700 | Status: active | Rationale: pinpoint diagnosis is done by the orchestrator; this is a small, surgical change on one path.

## The diagnosis (verified by reading the code, not inferred)

At `exception-r3-impl` @ `fbd8da0f`, `src/cljc/yang/clojure.cljc`:

`condition->outcome` (:1629-1649) is correct: a qualified protocol condition becomes
`(outcome-node error-tag (dsg__condition_wrapper condition condition))` — visible = the WHOLE
condition, so `dsg__condition_retained` later returns `[whole-condition whole-condition]`.

`call-entry`'s `entry-body` (:1384-1414) then does:

```clojure
(let [retained (dsg__condition_retained err-val)]
  (if (= retained nil)
    (raise (yang.call/thrown err-val))       ; ordinary: correct
    (raise (data/nth retained 1))))          ; <-- THE BUG
```

`raise` is `(fn [condition] (assoc (escape (assoc {} :clj/escape-token ... :clj/outcome
(condition->outcome condition))) :tail? true))` (:1650-1657). So the retained condition is fed
BACK through `condition->outcome`, which sees a qualified `:yang.call/not-callable` reason and
WRAPS IT AGAIN. The escape carries `[:yin.k/error <wrapper-of-wrapper>]`; the kit reads it as
`:reason :yang.call/thrown, :condition-identical false`.

That is precisely the observed shiva failure:
expected `{:reason :yang.call/not-callable, :condition-identical true}`
actual   `{:reason :yang.call/thrown, :condition-identical false}`

## The fix

The retained condition must reach the escape exit **without re-classification**. Two acceptable
shapes — pick the one that preserves the invariant that a wrapper is never nested:

(a) an escape node that delivers an already-converted outcome: `(escape (assoc {} :clj/escape-token
    dsg__call_token :clj/outcome [error-tag <condition>]))` — i.e. the retained condition inserted
    verbatim as the outcome's error slot, no `condition_wrapper`, no `yang.call/thrown`; or
(b) gate `condition->outcome` so an ALREADY-QUALIFIED condition is passed through as
    `[error-tag condition]` rather than re-wrapped.

Constraint from R5: a guest-thrown map that merely LOOKS like a condition must still be treated as
data. Only a value decoded by `dsg__condition_retained` (the heap-marked retained cell) may bypass
re-classification. Do not make the bypass reachable from a raw guest value.

## Tests

Qwen wrote two pins before its session died; they are in /tmp/qwen-pins.patch (apply with
`git apply`, then finish the missing helpers `run-public-entry-program` / `marker-operand` /
`marker-raise` — they must drive the PUBLIC entry (`yang.call/invoke` with a raise exit), not an
intra-unit call). The pins are correct in intent: qualified reason + identity across the public
entry, for the fresh and the saved variants.

## Verification

`clojure -M:test -n yang.clojure-test` + `clj -M:kondo --lint` on your files. **NEVER run
yang.call.exception-contract-test locally** (suite-scale, shiva-only, owner ruling 2026-11-04).
Commit + push `exception-r3-impl`.

End with: **PROTOCOL PATH FIXED** or **STILL FAILING: <what>**.
