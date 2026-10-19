Created-GMT: 2026-10-18 18:57:00 GMT
Created-Local: 2026-10-19 01:57:00 +07

Your fidelity review (collab/1792349357932-...) returned FAIL with three findings.
The orchestrator applied the focused corrections directly (no delegate, no rewrite):

- F1: docs/design/yang.wasm.md §4 items 2–4 no longer restate the control-block
  offsets, status-number assignments, or TLV/NaN bytes; they now state the
  compiler's obligations by reference to yin.vm.engine.md §E.3.1–§E.3.3. The
  module-import bindings item (1) and the register-snapshot/compiled-vs-service
  item (5) are unchanged. The register ten-region execution-memory table in Y §2.2
  was left alone, per your correction.
- F2: docs/design/yin.vm.debruijn.register.md §10.4 keeps the corpus paths,
  literal-golden meaning, binding lists, parked targets, fixture groups, and the
  raw observable-field list; the projection/admission/execution/comparison
  procedure sentences are replaced by a reference to engine §E.5.1.
  docs/design/yin.vm.engine.md §E.5.1 was generalized symmetrically: the two
  concrete corpus paragraphs are replaced by the generic per-fixture procedure
  (project/derive/instantiate/execute/compare, execute-every-arm, no-silent-skip,
  B0-insufficient + guest raw fields) referring to register.md §10.4 for the
  concrete corpora, bindings, and compared fields. Every acceptance obligation
  from the removed sentences is retained in one of the two homes.
- F3: the :return row's parenthetical now cites r5 §3.2 (Y:326).

Recheck ONLY these focused edits and the link/coverage invariants, per your own
disposition: (a) confirm the three corrections match your required corrections;
(b) confirm no acceptance obligation was dropped in the F2 move — walk each removed
sentence to its surviving home; (c) confirm the single-home rule now holds (ABI
tables once in E; corpus procedure once in E; register facts once in R §10.4);
(d) links still resolve; rust-kernel.md untouched.

Update or append to your findings file
(collab/1792349357932-architect-wasm-spec-tree-fidelity.gpt-6-astra.findings.md)
with a "Recheck round 2" section ending PASS or FAIL. Read-only except that file.
