Created-GMT: 2026-11-14 02:10 GMT
Created-Local: 2026-11-14 09:10 +07
Dispatched-By: master-green orchestrator seat (interactive; owner-authorized dispatch policy)
Destination: codex exec -m gpt-6.1-terra on jupiter (POOL 2, -c sandbox_mode="danger-full-access")
Task-ID: 1794612600000-engineer-admission-inheritance-fix

# ENGINEER: Family A — `yin.vm.linker.admission-test` broken by the decoupling landing

## Read first

1. `collab/orchestration/1794539700000-orchestrator-relay-decoupling-landing-debts-and-gate-census.md`
   — the triage of record (Families A/B/C). You own Family A; B/C are claimed by the refactor
   seat on branch `fix/master-gate-and-dht-tests` — DO NOT touch their surfaces.
2. `docs/agents/build-n-test.md` — focus rule, merge bar (2026-11-07), remote-lane discipline.
3. `git show c0216b9f` (step 2: `src/cljc/yin/vm/linker/interpreter.cljc` −729 lines) and
   `git show 6445f37a` (step 4 shell wiring) — the suspects.

## Failure evidence (reproduced, jupiter, `a3f7ab8d` and later tips)

`clojure -M:test -n yin.vm.linker.admission-test` → **184 failures / 7 errors**. Key body:
`an-install-child-inherits-the-admission-of-a-preinstalled-entry-test`
(test/yin/vm/linker/admission_test.cljc:728-729): `(:yin.admit/address recorded)` is NIL and
`(:yin.admit/id recorded)` is nil where a child install must inherit the preinstalled
admission record. Nightly lane adds: `tick-rollbacks-carry-the-entire-linker-successor-test`
×42, `a-trusted-import-records-its-admission-on-every-vm-test` ×24,
`one-tick-hands-external-and-snapshot-progress-to-the-vm-test` ×21,
`trust-commands-configure-the-live-linker-test` ×16,
`an-install-child-inherits-the-admission-of-a-preinstalled-entry-test` ×16,
`abandon-retires-nested-install-requests-from-a-nonzero-base-test` ×11.

The deftests are old and unchanged in the window; the src that changed under them is the step-2
interpreter rewrite. First DISCRIMINATE (cheap, scoped, on a jupiter scratch clone): run the
namespace at `4d0d3e70` (pre-rewrite, expect green) then at `c0216b9f` and `6445f37a` (expect
red from the first rewrite commit that breaks it). That attribution decides where the fix goes.

## Mission

Fix the admission recording/inheritance path so the namespace is green, WITHOUT weakening the
admission contract and WITHOUT touching: `test/yin/repl/dht_test.cljc`,
`test/dao/host/gate_test.cljc`, `docs/design/host-boundary/*`, or anything the refactor seat's
branch owns. If the correct fix requires changes to `yin.shell.*` composition (their new code),
that is in-bounds for THIS family — but keep the diff minimal and note the overlap in your
findings so the seats can coordinate the landing order (land yours first or theirs first, not
interleaved).

## Procedure

- Worktree from current origin/master on jupiter (`~/ci/datomworld-adm` scratch clone or a
  `git worktree add` from an existing clone). ssh to shiva works from here if you need node
  legs.
- Discriminate first (above), then fix, then verify scoped:
  - JVM: `clojure -M:test -n yin.vm.linker.admission-test` → 0F/0E.
  - Node (shiva, own clone): rebuild bundle, `node target/node-tests.js
    --test=yin.vm.linker.admission-test` → 0F/0E. If the ns is JVM-only in the bundle, say so
    with evidence instead of forcing it.
  - Neighbors unchanged-evidence: `clojure -M:test -n yin.vm.linker.publish-address-test` (or
    the closest linker ns your fix touches) — one adjacent ns, green.
  - Foundation precondition: `bb test:sub:clj dao.stream` green.
- Push branch `admission-inheritance-fix`. Never merge to master. Never rsync.

## Deliverables

- `collab/exception-track/1794612600000-engineer-admission-inheritance-fix.codex-gpt-6.1-terra.findings.md`
  (write it in the repo worktree; the orchestrator pulls it): discriminator results per sha,
  root cause paragraph, diff summary, every verification command with rc + Ran lines, branch
  head sha.
- If the mechanism turns out architectural (admission recording cannot survive the decoupled
  composition as designed), STOP at attribution and write that — it routes to the ruling lane.
