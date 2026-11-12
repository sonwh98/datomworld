Created-GMT: 2026-11-11 01:40:00 GMT
Created-Local: 2026-11-11 08:40:00 +07 (+0700)
Coding-Agent: muse
Session-ID: b810a3f2-b1a6-4421-ba96-19b174412bab
# Task: engineer — R3 (A) design regression: protocol conditions lose their reason and identity
Role: Engineer
Implementers:
- Status-Event: 2026-11-11 08:52 +0700 | Model: muse-wonder-1.0 | Status: failed | Rationale: muse quota exhausted (429, resets 10:50 local) after staging a destructive partial revert (preserved: 1794376000000-muse-aborted-staged-revert.patch; worktree reset to fbd8da0f).
- Model: qwen3.8-max | Assigned: 2026-11-11 09:00 +0700 | Status: active | Rationale: remaining on-roster lane with clojure capability that has not authored this file; codex exhausted until 10:33, agy out, muse out.

## The failure (shiva, exact)

`xtrack-r3A2` at `fbd8da0f`, `clj -M:test -n yang.call.exception-contract-test`:

```
FAIL in (clojure-provenance-test) (exception_contract_test.clj:29)
:clojure/protocol-not-callable
expected: {:transferred true, :once true, :after false, :condition-identical true,
           :reason :yang.call/not-callable}
actual:   {:transferred true, :once true, :after false, :condition-identical false,
           :reason :yang.call/thrown}
```

Same for `:clojure/protocol-invalid-invocation`, `:clojure/protocol-unsupported-binding`, and the
three `:clojure/saved-protocol-*` variants — all three backends. These SIX fixtures (18 rows) were
`green` at `778747ce` and are now `red`. Nothing else about the round is in question: the ordinary
payload half is correct (the clojure↔python round-trip rows are green), and the (A) design is not
disputed — this is the protocol branch collapsing into the thrown branch.

## Diagnosis to confirm

The (A) design's commitment 2 says the wrapper survives for qualified protocol conditions
(`:yang.call/not-callable`, `:invalid-invocation`, `:unsupported-binding`). The observed behaviour
says the well-formed protocol condition arriving at the bridge is being wrapped as a FRESH
`:yang.call/thrown` instead of being retained behind the protocol wrapper — i.e. the bridge's
reason classification (`condition->outcome`'s protocol arms, the refusal arms) is either not
reached or classified before the protocol check. Find which, with file:line.

Likely suspects in your own diff (`git diff c9c5533c..fbd8da0f -- src/cljc/yang/clojure.cljc`):
the `condition->outcome` arms, `call-entry`, the refusal constructors, and `strip-top-outcome`'s
protocol path. Check whether `dsg__cell-retained?` (the `:cell/retained?` query) is consulted at
the point that decides "wrap as protocol" vs "pass raw", and whether a well-formed incoming
`{:yang.call/reason :yang.call/not-callable ...}` map is being classified as a plain map payload.

## What must hold after the fix

- `:clojure/protocol-*` and `:clojure/saved-protocol-*`: `:reason` = the qualified reason,
  `:condition-identical` = true, on all three backends.
- `:clojure/fresh-named`, `fresh-closure`, `binding`, `shape-return`, `shape-throw`: stay green.
- The ordinary payload half is untouched (round-trip, `(= e orig)`, all observation probes still
  green).
- All of astra's probes (your `test-exception-r3-astra-probes`) still green.

## Verification — READ THIS TWICE

- **Run the KIT**: `clojure -M:test -n yang.call.exception-contract-test` **is suite-scale and
  MUST NOT run locally** (owner ruling 2026-11-04; one round already lost four hours to this).
  Instead: `clojure -M:test -n yang.clojure-test` (focused) + `clj -M:kondo --lint`. If you
  believe you need a contract check to confirm, say so in the report and the orchestrator runs it
  on shiva — do not run it yourself.
- **Write a unit-level pin** that reproduces the failure locally: a protocol condition
  (`{:yang.call/reason :yang.call/not-callable, :yang.call/data {...}}`) crossing a call site must
  come out with `:reason :not-callable` and condition-identical payload. That is the test your
  round was missing; the kit found it and your tests must now cover it.
- Commit and push `exception-r3-impl` (your earlier push failed from the sandbox; I will re-push
  from the credentialed shell if needed — report the SHA).

## Report

Begin with Completed-GMT/Completed-Local; end with the commit SHA and one line:
**KIT PARITY HOLDS** (protocol reason + identity, per your new pin) or **STILL FAILING: <what>**.
