Created-GMT: 2026-10-31 14:25:46 GMT
Created-Local: 2026-10-31 21:25:55 +07
Coding-Agent: interactive
Session-ID: sess_b6001731-427a-4431-b74f-97f348558fc2

# Task: investigate and close the yang.python JVM nightly red (3 failures, cross-host confirmed)

Role: yang.python Orchestrator (peer seat)

Implementers:
- Model: <yang.python seat's current orchestrator model> | Assigned: 2026-10-31 21:25 +07 | Status: active | Rationale: the yang.python track owns the red shard

## Evidence (verified before this brief)

The first nightly run (new infra, stood up 2026-10-31 ~02:23 by another seat) ran the JVM
quick tier sharded at commit `c02c7c5fa` on BOTH hosts. Result: 13 of 14 shards green;
`yang.python` rc=1 on BOTH — same failures, so this is a master regression, not host flake.

- shiva: `~/ci/nightly-results/20261031-0223-summary.txt` (jvm-sharded lanes=14 red-lanes=1
  secs=1325; yang.python rc=1, 265 tests / 2,875 assertions). NOTE: shiva's nightly kept only
  per-lane summary lines, not per-shard stdout — repro from jupiter's artifacts (next).
- jupiter: `~/ci/datomworld-nightly/target-ci/shards/yang.python.log` — full per-shard stdout.
  Exactly 3 failures:

1. `ERROR in (an-unsupplied-import-is-refused-at-publication-test)`
   (`test/yang/python/antlr/dht_import_test.cljc:107`)
   expected: `{:status :refused, :reason :yang.python.antlr/unpublished-import, :module "k",
   :imported-by "t"}` from `publication/publish-units!`
   actual: `clojure.lang.ExceptionInfo: dao.jing reads only valid segment content addresses
   {:address :test/address}` at `dao.jing.cljc:445`, through
   `yang.python.antlr.publication/publish-units!` (publication.cljc:76).
   The test stubs a placeholder address `:test/address`; dao.jing now refuses it.

2. `FAIL in (module-uast-is-one-wide-application-test)` (`linked_prelude_test.cljc:103`)
3. `FAIL in (module-uast-is-one-wide-application-test)` (`linked_prelude_test.cljc:112`)

All three sit in the `yang.python.antlr.dht-import-test` and `linked-prelude` namespaces.

## What to do

1. Re-derive, do not trust: read `git log` around `c02c7c5fa` and the diff that introduced
   dao.jing's segment-address validation (the "hash-registry contract" work landed a lint and
   a stricter `dao.jing` read path — suspect, not conclusion). Confirm which commit made
   `dao.jing` refuse `:test/address`.
2. Adjudicate the defect's location:
   - If `publish-units!` should never pass an unhashable placeholder (the test fixture is
     stale — it predates the validation), fix the yang.python test fixture to use a real
     content address while still exercising the `:unpublished-import` refusal path.
   - If dao.jing's validation is wrong to fire on this shape, the defect is NOT yours: file
     it to the dao.jing owner seat with the evidence and keep this round scoped to what
     yang.python owns. Do not change dao.jing semantics from the yang.python seat.
   - For `module-uast-is-one-wide-application-test`: diagnose before touching; determine
     whether it is the same root cause (fixture addresses) or an independent break.
3. Fix in a yang.python worktree per the standard cycle: G0 iterate
   (`bb test:sub:clj yang.python`), G1 (`bb test:sub yang.python`), then
   `bb test:changed` tri-host. The regression is JVM-visible; still verify Node/Dart legs
   before landing because the same fixtures run there (the cljs tier showed a large
   `dht_import_test` failure cluster on shiva's 20261031-0223 run — likely the same root,
   confirm while you are in there).
4. Land through your normal gate + independent review; commit message names the fixture or
   code defect, whichever it is, and cites this brief.

## Constraints

- Scope: yang.python test/publication code and fixtures. dao.jing changes route to the
  dao.jing seat. No new public entry points.
- Routing (routing-status.md, 2026-10-31 relay): codex, glm, agy, deepseek, vibe available;
  claude/cmd unavailable; muse geo-blocked. Reviewer must be a different model family from
  the implementer.
- Collab filing (orchestrator.md step 11, 2026-10-31 ruling): your round's artifacts live
  flat in `collab/` while in flight; file them into `collab/yang-python/` when the round
  closes, checking existing subfolders first.

## Deliverables

- Findings: `collab/<timestamp>-orchestrator-yangpython-nightly-red.findings.md` — root cause
  per failure (or the filed handoff to dao.jing), the fix diff, verification counts
  (JVM/Node/Dart), and the review round reference.
- A one-line nightly hygiene recommendation back to the owner: shiva's nightly-full.sh
  discarded per-shard stdout (only summary lines kept) — the diagnosis above was only
  possible from jupiter's copy; both hosts should keep per-shard logs.
