Created-GMT: 2026-11-14 (dispatch time)
Created-Local: 2026-11-14 ~10:5x +0700
Coding-Agent: codex
Session-ID: pending

# Task: Architect §8.1 amendment ruling — two new host arms from the eval-bridge round (P7-b/P7-a lineage follow-on)

Role: Architect (read-only design consult; the §8.1 rows and the host-boundary closure are the
design law you keep for this surface). Branch eval-bridge @ 2da48669+ (the eval-bridge series).

## The situation
The host-boundary gate (dao.host.gate-test) is green at origin/master's tip and red at the
reconciled branch tip (master + eval-bridge): the observed host arms now include TWO files the
§8.1 list-1 does not record, both introduced by the eval-bridge round:
1. src/cljc/yang/php/antlr/cst.cljc:43 — the PHP parse boundary now derives the byte line-starts
   table via text/utf8-encode (a dao.host.text host call), per the byte-domain ruling's §A.6
   (line starts are byte positions in B, derived at the parse boundary where the source is known).
2. src/cljc/yin/vm.cljc:945 — machine-data? now consults values/host-typed? within the
   visited-identity-caching validation added by the eval bridge's linker composition work
   (the machine-data validation caching + exact-integer big-carrier checks per the same round).
gate_test.clj is IDENTICAL between the green tip and the reconciled tip; the flip is the arms.

## Rule with
(a) the §8.1 category/class assignment for each of the two arms (the categories in
test/dao/host/gate_test.clj list-1-categories: migration-source/profile/runtime-prereq/
codec-track/dialect-arm/seam-driver/deferred-scope; classes per §3.1's table;
the eval compiler service is an external stream seam per the design — but you rule);
(b) whether either arm instead requires implementation changes (e.g., the line-starts derivation
relocated to a non-arm module, or the machine-data validation taking a different shape);
(c) the exact list-1 entry text for each file (the edn/test edit the orchestrator applies);
(d) whether the arms need oracle fixtures pinning their behavior.
Evidence to read: the reconciled tip's two arm sites (prelude/cst.cljc:43 region and
yin/vm.cljc:945 region), the byte-domain ruling (1794605712000), the eval-bridge design
(collab/orch-lane/1794400000000-...stdout.log), test/dao/host/gate_test.clj's list-1-categories
and the §8.1 rows, docs/design/host-boundary/closure-clj.edn (the closure classes each file carries).
Deliverable: collab/yang-php/<ts>-architect-s81-arms-amendment.findings.md. Read-only.
