Created-GMT: 2026-11-07 12:11:51 GMT
Created-Local: 2026-11-07 19:11:51 +07
Coding-Agent: qwen
Session-ID: b0bcaae8-80a2-4444-b36b-43ebe7d18f0b

# Task: implement byte-sized index page cuts (owner directive; supersedes the repair-slot sweep)

Role: Engineer (implementation + verification)

Implementers:
- Model: qwen3.8-max | Assigned: 2026-11-07 19:11:51 +07 | Status: active | Rationale: continuation of the master-green engineering lane; the owner redirected the repair-slot sweep to this as the actual fix

## Governing directive (READ FIRST, in full)

collab/dao-space/1794056541000-orchestrator-byte-sized-index-pages.directive.md
— the owner's reframe: "this problem should be reframed in terms of byte size
not in terms of datom count" (2026-11-07). The Architect adjudication it
schedules is REPLACED BY review-after-implementation on this round (owner
redirect, same conversation): implement now on the branch, independent review
before merge per workflow step 7.

## What to implement

In src/cljc/dao/space/index.cljc (`from-sequential` and the publish path):

1. **Byte-budgeted leaf cut.** Accumulate sorted datoms into a leaf; before
   appending the next datom, encode (canonical CBOR, `jing/cbor/encode`) the
   candidate leaf; if adding the datom would push the leaf's encoded size past
   a page budget, cut first. Budget: a new `dao.space.index` constant,
   default = 49152 bytes (48 KiB = 75% of dao.jing.dht's 65536
   `max-message-bytes`, headroom for envelope/framing), overridable per
   publish-opts for tests. Key count is emergent; no count cap is imposed
   (a single datom larger than the budget still forms a one-datom leaf — the
   store-level oversize refusal remains the last resort, unchanged).
2. **Byte-budgeted branch cut** with the same budget over child-address
   entries (each entry's bytes = its canonical address encoding).
3. **Manifest compatibility.** `:branching-factor` in the manifest becomes the
   EMERGENT count of the root's children if branch-cut was byte-driven — check
   the conforming-manifest validator (index.cljc:287-296) and every
   `:branching-factor` consumer (yin/repl/index.cljc:368-371 compares it) and
   reconcile: either keep the field as the realized branch count (preferred —
   no schema change) or remove the comparison; do not invent a schema change.
4. **Revert the workaround that this replaces**, on its own commit:
   `git revert cb2cbe11` (the `:repair-slots 48` commit) — with byte-sized
   pages the 8× address amplification collapses and the slot change's node
   breakage is moot; verify rather than assume (the dart/node matrix below
   decides; if evidence shows 48 slots is still needed with byte-cut pages,
   do not revert and report why).
   Also retire the `:publish-opts {:branching-factor 64}` override in
   src/cljc/yin/repl/dht.cljc (fix 5's per-store constant): pages now self-size.
   Keep fix 5's INTENT (pages within the message bound) — enforced now by the
   builder. Fix 5's commit itself stays (history is not rewritten; this is a
   forward change on the branch).
5. **Pins:** dao.space index shape tests and any test pinning `:branching-factor`
   512/64 get updated per measure-then-pin (run them, report the new emergent
   counts, update the pins to the new realized values with a comment linking
   this directive). NEVER weaken an invariant to fit a byte budget.

## Where

Work in the git worktree /tmp/nrf-branch-wt (branch nightly-reds-fix @
cb2cbe11 + untracked scratch). Files in scope: src/cljc/dao/space/index.cljc,
src/cljc/yin/repl/dht.cljc, tests under test/dao/space/ and test/yin/repl/,
plus any test pinning page shape. Nothing else; no commits; no pushes; do not
touch collab/ or other seats' untracked files.

## Verification (the full matrix, per the scoped ruling)

- dart (laptop, owner-authorized): cd /tmp/nrf-branch-wt && eval "$(mise env -s bash)" && DATOM_SLOW_TESTS=1 bb src/dev/cljd_agg.clj --only yin.repl.dht-test  → all green
- dart: same, --only dao.space.query.datomic-test or the dao.space index namespace(s) covering the builder (find via grep) → all green
- node (shiva): cd ~/ci/datomworld-nightly-node; fetch origin nightly-reds-fix IS NOT ENOUGH — your diff is uncommitted, so apply it: git diff in /tmp/nrf-branch-wt > /tmp/bytecut.patch, scp to shiva, git apply in the clone (it is at cb2cbe11), then eval "$(mise env -s bash)" && clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge "{:autorun false}" && DATOM_SLOW_TESTS=1 node target/node-tests.js --test=yin.repl.dht-test/rounds-larger-than-the-pending-write-bound-are-acknowledged-never-busy,yin.repl.dht-test/with-peers-that-never-accept-twenty-rounds-report-and-the-ticker-idles,yin.repl.main-test/the-interactive-composition-holds-every-standard-language → all green
- JVM single namespaces are the QA seat's surface — do NOT run the JVM dht namespace; report the change so the QA seat re-verifies.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report: the cut algorithm as implemented (where the budget check sits), the
emergent page counts observed for the REPL test tree, every pin you changed and
why, the revert decision with evidence, git diff --stat, and BOTH verification
outputs verbatim (dart + node). Do not claim runs that did not occur.
