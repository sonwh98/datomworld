Created-GMT: 2026-11-09 07:1x GMT (real header below)
Coding-Agent: agy
Session-ID: 19b7dadf-aaca-4f9d-8a8a-c5f3315a4281

# Task: fix astra's two P1 provenance defects on cc1 (request-changes verdict)

Role: Compiler/VM Engineer (implementation against a precise reviewer spec)

Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-09 | Status: active | Rationale: owner relay - muse available again; muse-wonder for the macro.cljc provenance implementation (agy 429, qwen out)
- EXECUTION CONSTRAINT: no ssh, no slow tests locally. If a RUN is needed, STOP
  and report the command for the orchestrator.

## Governing verdict (read fully)

collab/yang-clojure/1794202500000-architect-cc1-recheck-r2.findings.md
— REQUEST CHANGES on origin/callsite-contract-clojure @ fa70516f. Two P1 defects,
each with a reproducible source form and a "minimum correction" line:

1. Packet reassembly loses retained origins and fabricates construction
   provenance: (defmacro repack [x] (conj [] (first x) (first (rest x))))
   (repack (+ 1 2)) -> correspondence {[] :constructed} instead of
   {[] [:retained incoming-origin]}. Loci: macro.cljc:1208 (attach),
   macro.cljc:1232 (fallback fabricates), macro.cljc:1259 (walk), :1387 (table),
   :638 (child-constructor path treats missing child provenance as constructed).
2. Tracking metadata makes a packet literal unencodable:
   (defmacro packet-data [x] (yin/literal x)) (packet-data (+ 1 2)) -> the
   ::origin-of host function reaches [:literal v] (macro.cljc:667) and Jing
   meta-bytes/CBOR fails. Locus: macro.cljc:618 attach.
   Minimum correction: remove ONLY private tracking metadata, recursively, when
   values enter literal/data slots, before hashing and validation; preserve
   legitimate user metadata. Keeping tracking entirely outside the value is an
   alternative if it is cleaner — justify whichever you pick.

## Where

Fresh scratch worktree on the laptop from origin/callsite-contract-clojure
(fa70516f): git worktree add /tmp/cc1-p1 fa70516f --detach. Edit only
src/cljc/yin/vm/macro.cljc (+ its tests under test/ if new pins are needed; add
tests reproducing BOTH forms above — they must fail before, pass after). No commits.

## Verify (report commands; orchestrator runs them remotely)

- The two new pins + the existing callsite battery: the 3 namespaces
  (yang.clojure-callsite-test, yang.core.call-test, yin.vm.encoder-test) on
  node + JVM — the orchestrator will run these scoped.
- No invariant weakening: the repack case must record the incoming origin as
  :retained; the packet-data case must encode cleanly.

Begin the final response exactly with Completed-GMT / Completed-Local; include
git diff --stat and the exact commands to run.
