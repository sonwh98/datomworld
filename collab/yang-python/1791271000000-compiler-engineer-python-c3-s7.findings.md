Created-GMT: 2026-10-07 10:30:00 GMT
Coding-Agent: claude (opus-5-5)
# Engineer report: Python C3 slice S7 (continuation run) -- SUPERSEDED

Superseded by collab/1791379500000-engineer-s7-signoff-pack.md, which
holds the final-tree gate counts and the ruling 14 mutation ledger.

## State found (previous runs killed, exit 137, no report)

Uncommitted tree on yang-python-c3-s7 (from master edaf571c). A first
continuation run had already deleted the scratch (tmp-s7/,
test/zz_s7_scratch_test.clj) and written a stub; it was killed too.
- src: `prelude.cljc` (py/repeat against data/max-items, round order,
  host-names + data/max-items, `admit`, docstring), `data.cljc`
  (two-arity register-data-module, max-items export, check-limits!).
- tests: fan-out of `{::data/max-items 1048576}` + `prelude/admit`;
  data_test two-arity; int_conv_test ledger rows; int_contract PM1/PM2;
  new c3_programs.cljc (10 packets), c3_gate_test.cljc,
  c3_gate_parser_test.clj, c3-corpus-v1.{txt,generate.py}; int-conv-v1
  generator + 11 rows.
- doc: yang.antlr.md 8.5 edits.

## Progress (this run)

- Gate rulings applied: no `:no-kw` (pow/round accept keywords in
  3.9.6); lint scope now the deftest docstring; c3 generator asserts
  non-blank stdout lines.
- Fixtures regenerated with `python3 -I` (3.9.6): c3-corpus-v1.txt
  and int-conv-v1.txt BYTE-IDENTICAL (sha256 1b430c4c... and
  5164c8fe... before and after).
- Next: focused JVM tests, mutations, goldens, Node/Dart slow sides.
