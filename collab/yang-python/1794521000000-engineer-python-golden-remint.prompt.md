Created-GMT: 2026-11-12 21:45 GMT
Created-Local: 2026-11-13 04:45 +0700
Dispatched-By: master-green orchestrator seat (interactive ZCode / glm-5.3-flash)
Destination: codex exec -m gpt-6.1-terra (POOL 1, laptop)
Task-ID: 1794521000000-engineer-python-golden-remint

# ENGINEER: re-mint the stale golden surfaces the P9 + provenance content merges owed

## Context (read first)

- docs/agents/build-n-test.md — ALL rules; especially the G0 focus rule (run only the
  named failing tests, NEVER a suite or `bb test`) and "Remote test servers" (ssh clones
  under ~/ci, toolchain ordering, kill-by-PID).
- collab/exception-track/1794519298000-orchestrator-relay-provenance-adoption-broke-node-dht-import.md
  — full evidence trail incl. the FINAL ATTRIBUTION section.
- test/resources/yang/python/dht-import-v1.generate.clj header — the corpus generator and
  its stated duty: "Rerun it whenever the prelude, the lowering or the linker's publication
  moves an address; dht-import-test fails until it is."

## Mission

On master `4d0d3e70` (or its tip at your start), the nightly found ONE stale-golden family
from the merged content changes (f82afe44 P9 math builtins; 00ca2d1c/8018005d provenance
adoption). Regenerate/re-pin it and verify with exactly the failing tests. Both hosts AGREE
on the new content — this is a mechanical re-mint, not a bug hunt. If anything you mint
disagrees between hosts, STOP and report.

## Your file ownership (edit nothing else)

1. `test/resources/yang/python/dht-import-v1.edn` — REGENERATE ONLY via the documented
   command from the repo root (do NOT hand-edit):
   `clojure -Sdeps '{:aliases {:t {:extra-paths ["test"]}}}' -M:t test/resources/yang/python/dht-import-v1.generate.clj`
   Run it TWICE; the two writes must be byte-identical (sha256 check) before committing.
2. `test/yang/python/antlr/float_address_test.cljc` — re-pin the moved literals (5 known:
   cljc:229, :256, :261, :266, :273) from the actual values your verification runs print;
   update the docstring pin ledgers in the same style as commits e6380a2f / aeb423fc.
3. `test/yang/python/antlr/linked_prelude_test.cljc` — runtime-keys count 70→71 (cljc:190)
   and the `manifest-address-test` pin (cljc:261); adjust its docstrings honestly.
4. `test/yang/python/antlr/prelude_parity_test.cljc` — the two `seeded-builtins-test` pins
   (cljc:1661, :1665).
5. `test/yang/python/antlr/linked_safepoint_test.cljc` — node showed 1 failure in this ns.
   Run it scoped on node; if the signature is a stale pinned address, re-pin it; if it is
   NOT pin-shaped, do not touch — report instead.

## Procedure

- Work in a dedicated worktree created from origin/master (never the shared checkout);
  push your branch `python-golden-remint` to origin when the edits+verification are done.
  Never rsync. Never merge to master — the orchestrator lands you after review.

- Verification (individual tests only; all remote, per the 2026-11-04 owner ruling —
  NO suite-scale work on the laptop):
  - **shiva node** (scratch clone `~/ci/datomworld-remint` from origin; follow the
    clone/toolchain recipe — mise trust AFTER checkout, npm ci, bb gen:antlr,
    bb build:yin-repl-node, then
    `clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge "{:autorun false}"`):
    - `DATOM_SLOW_TESTS=1 node target/node-tests.js --test=yang.python.antlr.dht-import-test`
      (the corpus; MUST run with the slow env — the deftest is `^:slow`)
    - `node target/node-tests.js --test=yang.python.antlr.float-address-test,yang.python.antlr.linked-prelude-test,yang.python.antlr.prelude-parity-test,yang.python.antlr.linked-safepoint-test`
  - **jupiter JVM** (own scratch clone, same discipline):
    - `clojure -M:test -n yang.python.antlr.float-address-test -n yang.python.antlr.linked-prelude-test -n yang.python.antlr.prelude-parity-test`
    - `clojure -M:test -i :slow -n yang.python.antlr.dht-import-test`
  - Run foreground with explicit timeouts (no backgrounding of your own lanes); tmux +
    log-polling over ssh is the sanctioned pattern for the longer legs.

- A failure that appears ONLY under these scoped runs and not under the nightly, or a new
  signature outside the five surfaces above: stop, write it up, do not widen scope.

## Deliverables

- Findings file: `collab/yang-python/1794521000000-engineer-python-golden-remint.codex-gpt-6.1-terra.findings.md`
  with: the byte-identical two-run generator hashes, per-command rc + the
  `Ran N tests ... failures, errors` lines for every scoped verification, and the branch
  head sha you pushed.
- Commit message style: `test(yang.python): re-mint the dht-import corpus and golden address pins for the P9 + provenance content`
- Stdout log next to this prompt file (the orchestrator records the session id from it).

## Merge bar (owner ruling 2026-11-07)

Exactly the previously-failing tests green — nothing wider is required of you, and nothing
narrower will do.
