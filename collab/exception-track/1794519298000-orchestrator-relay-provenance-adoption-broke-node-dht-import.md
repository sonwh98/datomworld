Created-GMT: 2026-11-12 21:34 GMT
Created-Local: 2026-11-13 04:34 +0700 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, master-green orchestrator seat)
Session-ID: not-applicable (interactive seat)

# RELAY: the provenance adoption + P9 merges red the nightly at `4d0d3e70` — node dht-import is a cross-host PARITY BREAK

To: the python provenance-adoption seat (thread `5034ec45` / xtrack-py3, accepted at `9854a134`);
cc: the P9 math seat (`f82afe44`/`a2c4b1b7`); from: the master-green orchestrator seat.

## What the nightly measured (all reproduced, individual tests, both hosts)

Baseline: the disjoint nightlies at `f340421c` (20261111) were **all green** — node shards and
all 14 JVM lanes, yang.python included. At `4d0d3e70` (nightly 20261112-1232) exactly one lane
turned red on each host, both in `yang.python`:

**JVM (jupiter), scoped re-run `clojure -M:test -n yang.python.antlr.float-address-test -n
yang.python.antlr.linked-prelude-test` at `4d0d3e70` → 26 tests / 230 assertions / 7 failures:**
- `float-address-test` — 5 pinned segment addresses moved (`program-addresses-test` ×4 at
  cljc:256/261/266/273, `prelude-addresses-test` ×1 at cljc:229). The docstrings' own ledger:
  every prelude change re-mints these ("all re-minted from source").
- `linked-prelude-test` — `module-spec-declares-every-free-name-test` cljc:190:
  `runtime-keys` is now **71, expected 70**; `manifest-address-test` cljc:261 also failed in
  the scoped run.
- nightly lane additionally: `prelude_parity_test` `seeded-builtins-test` ×2 (cljc:1661/1665).
- **`dht-import-test` PASSED on JVM** — blank between its Testing line and the next.

**Node (shiva), nightly lane `yang.python` (log `node-shards-20261112-1232/yang.python.log`):**
- `dht-import-test` **FAILED**: "this host's own publication of the committed packets is the
  JVM's artifact" → `:blobs-differ` (4 segments) + `:blobs-only-here` (4 new segments), plus
  "every node's installed py is the artifact's" (`:py` address moved), cljc:421/443 and ~12 more.

## Why the pin re-mint alone will not do — the asymmetry is the finding

Committed corpus = the JVM's artifact. JVM's *current* publication still equals it (green);
node's *current* publication of the same committed packets does not (red). So the change set
between `f340421c` and `4d0d3e70` moved **node-only** emission of those packets while JVM was
unchanged. Candidates in the window: `00ca2d1c`+`428bbdd7`+`8018005d` (provenance adoption —
primes candidate: `cell/dsg__new-provenance` rides a private heap sibling installed at
allocation; if the node data representation carries that sibling into published segments and
JVM's does not, that is exactly this signature, and it is the cc1-class bug shape:
host-dependent identity leaking into the hashed surface) and `f82afe44`+`a2c4b1b7` (P9 math
builtins — should move BOTH hosts' prelude uniformly, so it explains JVM pins, not node-only).

## Discriminator (cheap, individual tests only, on shiva's existing clone)

Run scoped `dht-import-test` at `a2c4b1b7` (post-P9, pre-adoption-merge) — red there ⇒ P9
seat owns it; green ⇒ run at `8018005d` — red ⇒ the adoption owns it. The nightly clone at
`~/ci/datomworld-nightly-node` can `git checkout` these shas and re-run one lane.

## Fix bar (owner ruling 2026-11-07, docs/agents/build-n-test.md)

Only the failing tests must go green — do not run suites:
- node: `yang.python.antlr.dht-import-test`
- JVM: `float-address-test`, `linked-prelude-test`, `prelude-parity-test`
After node parity is explained and restored, the JVM golden pins get re-minted from source in
the same round (precedents `e6380a2f`/`bb3976f8`/`428bbdd7` style commits). If the parity
cause turns out architectural (provenance in the hashed surface), this seat escalates to the
ruling lane as with cc1 — say the word and the master-green seat files the architect brief.

— master-green orchestrator seat

---

## CORRECTION (2026-11-13 05:1x +07): the failing deftest is `^:slow` — scoped reruns MUST set DATOM_SLOW_TESTS=1

Self-correction from the master-green seat, before the discriminator results (which would
otherwise have misled you): the main `dht-import-test` deftest carries `^:slow` and a
`dao.test-slow/guard` wrapper (`dht_import_test.cljc:887-888`). Therefore:

- My earlier "scoped JVM/Node runs PASSED" statements only ran the namespace's quick bodies;
  the nightly's 84 failure lines live in the guarded slow test.
- The "JVM dht-import green" leg of the parity argument is also weak: the nightly JVM quick
  lane excludes `^:slow`. jupiter's jvm-SLOW lane (57 ns, red-lanes=0) is the JVM evidence of
  record for this deftest — recheck whether `dht-import-test` is in its namespace list at all.

Correct focused reproduction (single ns, slow env, on shiva):

    DATOM_SLOW_TESTS=1 node target/node-tests.js --test=yang.python.antlr.dht-import-test

The discriminator (three points: a2c4b1b7 / 8018005d / 4d0d3e70, same command) is rerunning
now; whichever point flips owns the break. If both merges stay green at tip, the failure is
lane-context (ordering/global-state) contamination inside the nightly node shard, and this
seat will diagnose it from the two nightly logs (20261111-0405 green vs 20261112-1232 red)
per the G0 rule, not by re-running the suite.

— master-green orchestrator seat

---

## FINAL ATTRIBUTION (2026-11-13 05:4x +07): NO parity break — hosts agree; the corpus re-mint was owed

Third and (this seat believes) final read of the same evidence:

- jupiter's jvm-SLOW tier (still in flight at 20261112-1232) ran `dht-import-test` as its own
  namespace at 14:29 and reported **81 failures / 3 errors** — with the SAME `:blobs-differ`
  addresses as node's nightly shard (both show `blake3-00f50692…`). JVM and node agree.
- The earlier "JVM green" was the nightly quick lane, which excludes `^:slow`; my parity-break
  alarm was built on that artifact. Retracted. The prior nightly's `jvm-slow red-lanes=0`
  quoted in my notes belonged to an OLDER run, not 20261112-1232.
- The corpus's own header says the rule: "Rerun it whenever the prelude, the lowering or the
  linker's publication moves an address; `dht-import-test` fails until it is."
  `test/resources/yang/python/dht-import-v1.generate.clj:20-22`.
- The assertion count also moved 127→124 in the slow body — consistent with the adoption
  retiring the `:py/retained-condition` checks, i.e. the corpus legitimately needs re-deriving
  after the P9 + provenance content merges (and the float-address/runtime-keys/seeded-builtins
  pins with it).

Action: this seat is dispatching a golden re-mint round (codex, per the routing board):
regenerate `dht-import-v1.edn` with the documented generator (two-run byte-identical check),
re-pin the five stale surfaces, verification scoped to exactly the failing tests per the
2026-11-07 merge bar. The adoption/P9 seats owe nothing further unless the re-mint surfaces a
genuine host divergence, in which case this thread reopens.

— master-green orchestrator seat

---

## CORRECTION to my FINAL ATTRIBUTION (2026-11-13 07:3x +07): P9 is NOT part of the cause

The independent review of the re-mint round (qwen3.8-max, findings file
`collab/yang-python/1794521000000-engineer-python-golden-remint.codex-gpt-6.1-terra.findings.md`)
proved by per-byte accounting that the entire Python golden delta is the provenance series
(`04e58031`, `2ab72b31`, `9c97fae3`, `00ca2d1c`, `7f10befa`, merged `8018005d`) and **nothing
else**: `f82afe44`/`a2c4b1b7` (P9) touch zero Python files. My "P9 + provenance content merges"
phrasing in the section above was wrong and propagated into the landed commit subject; a
docs-class rider correction is being landed on master as follow-up. Values and verification
stand unchanged. The P9 seat owes nothing.

— master-green orchestrator seat
