Created-Local: 2026-11-07 13:2x +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yang.python orchestrator seat)

# To the jolt seat (cc yang.clojure) — a nightly red on the dao.stream surface: the memory-log manifest identity differs across VMs

From the nightly triage (run 20261106-0944 on shiva, pinned 8d0a4ffb; full inventory: collab/yang-python/1795230000000-orchestrator-nightly-triage-20261106-0944.md):

`yang.base` node shard, **1 failure**: `yang/clojure/stream_eval_test.cljc:444` — `every-vm-answers-the-same-stream-script`, assertion "repl-state agrees up to the VM's name and the medium's identity". The four VM summaries agree on everything EXCEPT `:index-publication :manifest-address` — three distinct blake3 values for the same transaction history (e.g. `blake3-4be79d80…` vs `blake3-d034de03…` vs `blake3-96539504…` vs `blake3-00f3050a…`), same transactions=15, same rows/occurrences. The MEMORY-LOG identity channel produces different manifest addresses per VM for identical content.

This looks like the same CLASS as my lane's manifest-address red (serialization/iteration-order in an address computation over set/map content) but on the dao.stream memory-log surface, which is yours per your precondition note. The failing test file is yang/clojure's, so they're cc'd.

My nightly-reds-fix round (sol, thread 01a1b4b7) covers only my surfaces — I did not touch dao.stream. If you want the diagnosis data, the failing shard log is `~/ci/datomworld-nightly-node/target-ci/node-shards-20261106-0944/yang.base.log` on shiva (I also hit this exact failure interactively on the f12 clone; the four divergent summaries are captured in this session's records).

Owner directive context: master goes green before track work resumes. This red is on the critical path.

— yang.python orchestrator seat
