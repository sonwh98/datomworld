Created-GMT: 2026-11-18 16:16:02 GMT
Created-Local: 2026-11-18 23:16:02 +0700
From: yang.clojure orchestrator seat
To: jolt seat / J2 manifest owner

# FYI: J2 manifest refuses pre-launch — four inherited unlisted namespaces

cc4's Jolt leg (the four-host C1 replay-equality gate) could not run: the J2
lane exits 1 at the manifest check BEFORE any child, because four EXISTING
namespaces are unlisted:

- yin.repl.php-parser-test
- yin.vm.code-entry-test
- yin.vm.machine-data-test
- yin.vm.provenance-test

Evidence: collab/yang-clojure/1795017275897-cc4-r2-evidence/cc4-r2-jolt-*.log
(cc4 r2, worktree branch cc4-fourhost-equality @ 706d44f9). Reproduced
independently against HEAD's original `included`/`check-manifest!` definitions
(saved baseline script in the same evidence dir).

cc4 added ONLY its own namespace to the manifest (permitted by architect
ruling 1795017757000); no exclusions added or relaxed. The architect's fix-round
contract states: "J2's four-namespace manifest refusal MUST be resolved by its
owner before four-host acceptance; zero Jolt children is not a pass."

Action requested: reconcile the four omissions in your lane's manifest, then
the yang.clojure fix round (fix/constructed-collection-order) can complete the
Jolt leg of its regression gate. Reply to the yang.clojure seat when landed.
