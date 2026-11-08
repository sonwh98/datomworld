Created-GMT: 2026-11-07 08:15:04 GMT
Created-Local: 2026-11-07 15:15:04 +07
Coding-Agent: muse
Session-ID: f51cddf2-c4a1-4c52-a342-00593df61c4d

# Task: diagnose the cross-VM manifest-address divergence (stream_eval, master-green critical path)

Role: DaoStream and Distributed Protocol Engineer (DIAGNOSIS ONLY — no edits)

Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-07 15:15:04 +07 | Status: active | Rationale: deep cross-module invariant work (dao.stream memory-log × yin.repl index publication)

Context. Nightly 20261106-0944 red: `yang/clojure/stream_eval_test.cljc:444`
(deftest `every-vm-answers-the-same-stream-script`, ^:slow-gated), assertion
"repl-state agrees up to the VM's name and the medium's identity". The four
VM summaries agree on everything except `:index-publication :manifest-address`
— distinct blake3 values for the SAME 15-transaction history. Relay thread:
collab/jolt/1795240000000-yangpython-relay-stream-eval-red.md. This red is on
the master-green critical path per the owner directive (2026-11-07).

What is already known (verified by the orchestrator seat, do not re-derive):
- The address is minted by `dao.jing/segment-key` on the publication manifest
  (src/cljc/yin/repl/index.cljc:410, `jing/segment-key manifest`), i.e. blake3
  over the manifest's canonical CBOR bytes (src/cljc/dao/jing.cljc — canonical
  CBOR, map keys sorted by canonical bytes, sets sorted; see
  src/cljc/dao/jing/cbor.cljc map-bytes/set-bytes).
- So the CBOR layer is deterministic; the DIVERGENT INPUT is what each VM puts
  INTO the manifest. Something upstream differs per VM for identical logical
  content: a value that differs (seed, token, ordering-sensitive vector built
  from a hash-map iteration, a timestamp, a host-specific object), or map keys
  that differ.
- A same-CLASS fix exists on branch nightly-reds-fix commit 21350ec5 (stale
  golden + iteration-order checks in linked_prelude_test.cljc:229) — read it
  for the pattern, it is NOT the fix here.

Your task: DIAGNOSE, do not edit. Deliverables in your final response:
1. The exact locus of nondeterminism: the manifest field (or fields) whose
   bytes differ across VMs, traced to the code that produces it.
2. A reproduction: run the single failing test on the shiva scratch clone
   (NOT on this laptop — the owner rule forbids slow tests locally):
     ssh shiva.stigmergy.systems
     cd ~/ci/datomworld-nightly-node   # is at branch nightly-reds-fix @ 4f772d61
     eval "$(~/.local/bin/mise env -s bash)"
     DATOM_SLOW_TESTS=1 node target/node-tests.js --test=yang.clojure.stream-eval-test
   Capture the per-VM summaries (instrument by printing if needed — but do
   NOT commit anything; any scratch edit stays in that clone, never pushed).
   If you need a second VM comparison, the jupiter clone
   ~/ci/datomworld-nightly-node exists for cross-host runs.
3. A proposed minimal fix (file:line, description, why it preserves manifest
   identity semantics per yin.repl/index.cljc's docstrings), for the
   orchestrator to route to an implementation round. Do not implement it.

Constraints. Read-only on this laptop's working tree (you run with
--disable-write). Work only from the repo at /Users/sto/workspace/datomworld
(master@a31215d8) and the shiva clone. Do not touch collab/ files. Do not
run suite-scale tests anywhere; the single --test invocation above is the
authorized maximum. Do not weaken any test.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report: the diagnosis (deliverable 1), reproduction evidence with exact
commands and captured summaries (deliverable 2), and the proposed fix
(deliverable 3). Do not claim runs that did not occur.
