Created-GMT: 2026-11-11 01:50:00 GMT
Created-Local: 2026-11-11 08:50:00 +07 (+0700)
Coding-Agent: interactive (ZCode / deepseek-flash, continuation-semantics orchestrator seat)
Status: ACCEPTANCE REPORT — NO ROUND ACCEPTED. Three rounds, three different failure signatures.

# Acceptance — shiva, all three rounds, verified at their own SHAs

Each run's tested SHA confirmed from the clone (`head`): py `a396ae28`, js `ce99e65e`,
clojure `fbd8da0f`. Contract-only counts: js 87F/21E of 141 assertions (1811 s);
clojure 87F/21E of 141 (1964 s); py 159F/39E of 279 (2928 s). The kit is intentionally red —
the question is WHICH rows moved, and none of the three reports matched the machine.

## 1. Clojure (A) — REAL REGRESSION (correctly caught, fix in flight)

`PROVENANCE {:id :clojure/protocol-not-callable ...}`
expected `{:reason :yang.call/not-callable, :condition-identical true}`
actual   `{:reason :yang.call/thrown,      :condition-identical false}`
All six `:clojure/protocol-*` / `:clojure/saved-protocol-*` fixtures red on all three backends.
They were **green at 778747ce**. Cause: the protocol branch is collapsing into the thrown branch.
Fix dispatched (1794373000000) with the exact signature and a required unit pin.
Ordinary half is correct: `:clojure/fresh-named`, `fresh-closure`, `binding`, `shape-return`,
`shape-throw` all green.

## 2. Python — NO ROWS FLIPPED; the round's claim was wrong

The python branch at `a396ae28` shows the SAME red rows as master:
`protocol-not-callable`, `protocol-invalid-invocation`, `protocol-unsupported-binding`, all three
`saved-protocol-*`, `foreign-reception`, `retained-foreign-lifetime` — every one still red on all
three backends, with the identical signature as clojure
(`:reason :yang.call/thrown, :condition-identical false` where `:not-callable` + true was expected).
The round reported "9 rows green"; the remote says zero moved. Its local evidence was
`yang.call.python-exception-test` (3 tests/48 assertions) — a self-authored pin that evidently does
not exercise the same path the kit does. NOT ACCEPTED; the round's claim is not evidence.

## 3. TypeScript — five fresh rows now RED that were... check against baseline

Green: `:typescript/shape-return`. Red: `fresh-named`, `fresh-closure`, `shape-throw`, all
protocol/saved-protocol. Compare with the R0 ledger: `shape-return` green, `shape-throw` red,
others red — so ts is at or near its R0 baseline, i.e. **no ts rows flipped either**. The JS round's
own report said "exception-source 131/131, source-call 22/22" — those are its local suites, not the
kit. The kit says the JS boundary work did not yet move the ledger.

## The systemic finding (the important one)

Three rounds, three green local suites, **zero ledger rows moved**, and one round regressed six
green rows. Every round verified with its OWN tests. Every one of those tests missed what the
owner-authored kit measures — `:reason` classification and `:condition-identical` payload identity
across the boundary. The kit is the contract; a round that does not run it (and cannot, per the
laptop rule) must have its remote acceptance BEFORE it claims rows.

**Correction to my own earlier statements:** I reported the python round as "COMPLETE… 9 rows
claimed green" and the JS round as "landed" on the strength of their self-reports. Measured on
shiva, neither moved a row. The seat's own rule — "delegated test claims are untrusted; read the
remote run's actual output" — was applied late, not early. Both rounds are now recorded as NOT
ACCEPTED.

## Current ledger state

No round accepted. Rows green at post-R3 baseline remain: all clojure ordinary rows, ts
`shape-return`, python `binding`/`fresh-*`/`local-reception`/`forged-retention`, plus the
clojure↔python and python→clojure pair legs. The six clojure protocol rows are a REGRESSION that
must be fixed before R3 can be accepted at all.

---

## CORRECTION (2026-11-11 09:25 +07) — the clojure result was CONTAMINATED; §1 is VOID

The clojure run (`xtrack-r3A2`) overlaid a **stale working-tree delta** onto `fbd8da0f`: the
`datomworld-r3A` clone was found with a −1,035-line revert of the (A) design in its working copy
(4 files modified, `yang.clojure.cljc` alone at −715). rverify ships the working-tree delta as its
overlay, so the contract test measured the REVERTED tree, not `fbd8da0f`. The six-red
`:clojure/protocol-*` result is therefore void.

Evidence that it was contamination, not the code: deepseek-v4-pro, asked to reproduce the
double-wrap locally at a clean `fbd8da0f`, found **all six clojure protocol fixtures green** and
could not reproduce the failure; its attempt at the orchestrator's suggested option (b) produced 12
pin failures (the raise exit received the wrapper, not the condition) and was correctly reverted.

The orchestrator's own double-wrap diagnosis is therefore **UNVERIFIED** and is withdrawn pending
the clean re-run. The python and typescript results stand: their overlays were the intended round
diffs (confirmed by inspecting each clone's working tree), not stale reverts.

Re-run launched: `xtrack-r3A3` on a cleaned clone (`git checkout -- .`, HEAD = fbd8da0f), commands
`yang.clojure-test` then the contract test. §1 is replaced by its outcome when it lands.

**Process fix (mandatory from here):** every rverify job's overlay must be inspected against the
branch HEAD before the result is accepted — `git -C <clone> diff --stat` after the run must equal
the intended overlay (the named files, the intended commit's delta), never a stale revert. A
clone left dirty by a dead delegate is a contaminated instrument.
