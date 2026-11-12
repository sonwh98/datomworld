Created-GMT: 2026-11-10 18:48 GMT
Created-Local: 2026-11-11 01:48 +0700
Coding-Agent: agy
Session-ID: pending (provider-generated)
# Task: architect ruling #3 — the `condition->outcome` payload shape and the kit regression
Role: Architect
Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-11 01:48 +0700 | Status: active | Rationale: owner directive — architect work goes to codex or argon; argon ruled the prior two questions in this arc and holds the context.

Continuation of the R3 adjudication chain (read both prior rulings first):
1. `collab/exception-track/1794320926000-architect-dsg-condition-r4-coupling.gemini-4-argon-high.findings.md` — your own ruling: token stamping, token equality, remark-tails.
2. `collab/exception-track/1794332020000-architect-value-path-refusal-shape.codex-astra.stdout.log` — codex's ruling: (B), the value-path `:thrown` wrap is the contract; five test expectations updated accordingly.

Worktree: `/Users/sto/workspace/datomworld-r3-orch`, branch `exception-r3-impl` @ `98519957`
+ uncommitted working-tree edits (the token fix as applied by a since-interrupted round,
plus my mechanical test updates). The tree currently: `yang.clojure-test` fully green
(937/0/0); kit acceptance REGRESSED.

## The measured regression (bisected, two runs, same machine, same command)

- **Commit-only** `98519957` (R3 with literal-`true` stamps, original payload shape):
  `clj -M:test -n yang.call.exception-contract-test` → **4 tests / 141 assertions,
  69 failures / 21 errors**. The Clojure protocol rows
  (`:clojure/protocol-not-callable` etc.) are **GREEN** — `:transferred true,
  :condition-identical true` on all three backends.
- **Working tree** (token stamps + token-equality readers + `remark-tails` + the
  kimi round's `condition->outcome` STRUCTURAL rewrite + my test edits): the same
  command → **69 failures / 54 errors**. The same Clojure protocol rows now fail with
  `data primitive refused: {:op nth, :reason :wrong-type, :arg 0, :expected :vector}` —
  guest code does `(data/nth outcome 0)` and receives a MAP where the old shape
  delivered a 2-vector.

## The structural delta (the only candidate cause; my test/comment edits cannot reach it)

The kimi round rewrote `condition->outcome` beyond the stamps. Before (98519957):
for a `:thrown` reason the outcome payload was `(dsg__get condition :yang.call/value)`
— the **unwrapped value**. After: ALL FOUR recognized reasons keep the **whole
condition map** as the payload (with the token stamped), plus a `u/let1 reason`
restructure. Diff sites: clojure.cljc:1530-1549 (condition->outcome), plus the
token stamps at 1567/1586/1636/2617-2624 and the `:else` restructure at 2799-2808
(codex-ruled, keep).

## THE QUESTION

**Which payload shape is correct — and what exactly must change?**

**(A) Restore 98519957's payload shape** (for `:thrown`, unwrap to `:value`; for
protocol reasons, keep the condition map), keeping ONLY the token stamps, the
token-equality readers, and `remark-tails` from the working tree. Argon's original
ruling authorized stamps + readers + remark-tails — it never authorized a payload
restructure. If (A): confirm the restore is complete and name anything in the
current tree that depends on the new shape (the five updated test expectations,
the codex-ruled value-path wrap — do they still hold under the old shape?).

**(B) The new whole-map shape is correct** and the downstream consumers (whatever
guest code does `(data/nth outcome 0)` on a may-fail outcome) must be updated to
the new shape. If (B): name every consumer site and the migration.

Weigh: which shape keeps `:condition-identical true` TRUE for the protocol rows
(the kit asserts `(= condition (get result :condition))` — identity of the ORIGINAL
condition map through the boundary); which keeps the codex value-path wrap working
without further edits; and which is the minimal deviation from the two rulings of
record.

## Deliverable

One ruling: (A) or (B), with the exact edit list (file:line) and the assertion that
the chosen shape satisfies BOTH prior rulings simultaneously. Do NOT implement.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
