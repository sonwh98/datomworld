Created-GMT: 2026-09-09 16:55:41 GMT
Created-Local: 2026-09-09 23:55:41 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: b71838c3-58ae-434b-bbcd-73418c7af768
# Task: dao.space.schema Phase 1 — the write side on dao.stream
Role: Storage & Indexing Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-09 23:55:41 +0700 | Status: active | Rationale: Storage & Indexing primary per team.md; implemented dao.space.query, the transactor and index phases of this same sweep

**Implementation task with write authority**, bounded to the files under
*Ownership*. Repository `/Users/sto/workspace/datomworld`, branch
`dao.stream-redesign-v2`, clean at `4b9f0e7` apart from
`docs/orchestrator-log.md` and the new plan (both mine, untouchable by you).

## Your specification

**`docs/design/dao.space.schema.implementation-plan.md` §4 (Phase 1).**
It was written by the Architect, revised through three rounds against a
routine review (`gpt-6-astra`) and an adversarial review (`deepseek-v4-pro`),
both of which cleared it, and every measurement in it I verified myself
against the tree. **§4's Build / Delete / Tests / Prove lists are exact.**
Read §2.3 and §2.4 (the invariants) and D1, D2, D7, D8 before touching code.

Phase 2 is **not yours**. Do not touch `current`, `interpret-view`, either
`defopen`, `PublishedSchemaRows`, `published`, or `schema_fixtures`. The
`[dao.stream :as ds]` require and `:require-macros` **stay** this phase.

## The goal this serves

Every consumer moves off v1 `dao.stream`, after which v2 takes the name.
`dao.space.schema` is the last `dao.space.*` namespace on v1;
`query`, `index` and `transactor` are already done.

## What Phase 1 is, in one paragraph

`SchemaWrapper` is a `deftype` whose only protocol is `ds/IDaoStreamBound`,
implemented so `ds/close!` and `ds/closed?` dispatch to it. It becomes a
plain map with named operations, exactly as `dao.space.transactor` did.
`transact!` stops re-wrapping the inner receipt into `{:result :ok :t :datoms}`
and returns it unchanged — that is the D10 collapse, and it is a deletion, not
a translation. `closed?` does not survive; its flag does, because
`transact!` needs it to answer `closed`. `publish!` loses its closed guard
and its lock.

## The three things most at risk, named so you check them

1. **T19** (§2.4) — `transact!` plans the complete next state before one
   inner append and installs it only when the receipt is `ok`. This is the
   invariant the `deftype`→map rewrite most endangers. Its pin is
   `failed-inner-append-leaves-wrapper-state-unchanged`, and per the plan it
   should pass with **only** `:1062` and `:1071` changed. If you need to
   change anything else in that test, stop and say so in your report —
   it means the rewrite moved semantics.
2. **The closed-precedence rule** (D1, L10) — empty `tx-data` throws
   **above** the lock; everything else answers `closed` first. This matches
   `tx/transact!` (`transactor.cljc:241-243`) exactly, and the new test
   asserts the wrapper and its inner value answer the same two calls the
   same two ways. Do not invent a schema-specific order.
3. **`close!` actually closing the inner value** — the new idempotence test
   pins it by asserting `(tx/transact! (:inner w) …)` answers `closed`,
   because an assertion on the wrapper alone would pass even if
   `tx/close!` were never called. Keep that direct assertion.

## Ownership

Write only:
- `src/cljc/dao/space/schema.cljc` — §4 Build and Delete
- `test/dao/space/schema_test.cljc` — §4 Tests
- `docs/design/dao.space.schema.md` — the in-phase edits §4 names (§3.1's
  ownership paragraph, the D10 paragraph's deletion, D7's sentence, §7's
  usage block)
- `docs/design/dao.space.transactor.md` — T20's retirement line and the
  deletion of its schema open item, exactly as §4 specifies

Touch nothing else. Not `transactor.cljc`, not `query.cljc`, not
`index.cljc`, not the plan, not the orchestrator log.

## Verification you must run and report

- `clojure -M:test` (or `bb test:clj`), `bb test:cljs`, `bb test:cljd` —
  full and unfiltered. Report **assertion counts**, not adjectives. Confirm
  `Testing dao.space.schema-test` appears in the Node output; its absence
  means the namespace did not run.
- `clj -M:cljs -m shadow.cljs.devtools.cli compile demo`.
- The two residue greps from §4 Prove, with their expected values:
  `grep -c "ds/" src/cljc/dao/space/schema.cljc` → **14**;
  `grep -c "ds/" test/dao/space/schema_test.cljc` → **6**.
  Deftest count 70 → **72**. If a number lands elsewhere, report the actual
  number and why — do not adjust the code to hit a target.
- `clj -M:kondo --lint src/cljc/dao/space/schema.cljc`.

Traps this repo has hit before: `#?(:clj ...)` alone does **not** exclude
code from the cljd build — use `#?(:cljd nil :clj ...)` with `:cljd` first;
a `deftype`'s private mutable field is a cljd trap, which is part of why
D8 makes the wrapper a map (`@(:state w)`, no reader conditional).

## Report

Write to `collab/1788972941480-storage-space-schema-phase1.glm-5.3.findings.md`,
starting with Completed-GMT, Completed-Local, Coding-Agent: glm,
Session-ID: b71838c3-58ae-434b-bbcd-73418c7af768. State what you built and deleted, the exact commands with
outcomes and counts, any invariant you could not honor and why, and anything
left owing. **Do not stage or commit** — the orchestrator commits. If your
budget runs out mid-phase, leave the tree readable and say precisely what
remains rather than leaving a half-applied edit.
