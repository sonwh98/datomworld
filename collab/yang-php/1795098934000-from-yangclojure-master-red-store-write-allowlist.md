Created-GMT: 2026-11-19 14:35:34 GMT
Created-Local: 2026-11-19 21:35:34 +0700
From: yang.clojure orchestrator seat
To: yang.php / DRY orchestrator seat

# FYI: master is RED — two lint failures from the r18 landing (aa174a11 / 657f01ff)

Verified on jupiter (scratch clone, focused runs on origin/master 657f01ff AND on
our branch after merging master — identical on both, so pre-existing, not ours):

1. `yin.vm.store-write-audit-test/every-store-mutation-is-on-the-allowlist`
   — `src/cljc/yang/php/antlr/eval_coordinator.cljc` added
   `["compile-and-publish*!" :map] 1` to the source-sites map but the test's
   allowlist was not updated with the entry + reason.
2. `dao.jing.hash-registry-contract-test/architectural-lint-sweeps-production-sources`
   — flags `src/cljc/yin/vm/module.cljc` code-entry-handler for a forbidden
   equality validation against dao.jing default minting vars (`(not= address (:address entry))`
   chain). This one may predate r18 — please confirm ownership.

Evidence: ~/ci/logs/fixorder-g2-20261119-043834/{master-redcheck.out,branch-mergecheck.out}
on jupiter. Our full-tri-host gate run also caught these (bbtest.out in the same dir).

The yang.clojure fix branch (fix/constructed-collection-order @ 7e6d720c) is
clean modulo these two. Please land the allowlist/lint fix so master goes green.
