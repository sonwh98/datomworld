Created-GMT: 2026-10-26 15:10:00 GMT
Created-Local: 2026-10-26 22:10:00 +07

# Task: J5 — the local REPL acceptance gate (standard frontends, `(lang :yang/python)`, probe parity)

Role: Engineer (jolt lane). Your session built J4a–J4d and closed J4; J5 is
the local-REPL milestone gate, and the lane spec says it is *necessary but
not sufficient* for J5 acceptance: "The selected in-process C++ parser,
standard catalog, completeness-probe" (§10.1 J5 row) — meaning: the C++
ANTLR parser (J0c, landed probe-only), the standard frontend catalog
(yin.repl.frontends — currently requires yang.python.antlr.*, which needs
the JVM on jolt), and the completeness probe sequences equal the JVM's.

## The gate (spec §7.8 J5 + §10.1 J5 row, verbatim)

"§7.8 J5 gate. Standard catalog loads and `(lang :yang/python)` works;
frontend_catalog/python_antlr/main suites; synchronous probe sequences
equal the JVM's; local-build-only packaging. **Local REPL milestone**,
native C++ parser library disclosed; no process crash isolation claimed."

The §7.8 comparison contract: packet and completeness parity (strict packet
equality; E1 — ok packets equal in full, error packets equal apart from
errors, with the first error's kind, span, line and column equal and a
non-empty message), the fixed input set (arithmetic, nested indentation/
dedent, empty input/EOF, non-ASCII and astral characters, malformed syntax,
incomplete multiline input), the completeness-probe sequences equal the
JVM's, and two-worker/cache isolation.

## Scope

1. **Verify the J0c probe gates** on master at this head (the §7.8 minimum
   baseline is landed): the pinned Python grammar generated with Cpp, the
   wrapper and runtime built and loaded, the fixed parse set marshalled
   through the jolt adapter, the shared exporter run, canonical CST/
   diagnostic packet equality with the JVM/Node/Dart oracle for identical
   input, exception/lifetime tests, helper provenance, generated digests.
   J2b(a) closed at zero pins — the oracle_parity harness is the comparison
   instrument.
2. **The standard catalog on jolt**: yin.repl.frontends loads (its ANTLR
   requires are C++-parser-backed now), the standard catalog composes, and
   `(lang :yang/python)` selects the C++ parser backend.
3. **The suites**: frontend_catalog, python_antlr, main — green on jolt at
   master's counts.
4. **The acceptance run**: the standard catalog + `(lang :yang/python)` +
   the multiline/completeness probe sequences equal the JVM's, end to end
   in the jolt REPL, no hand-built catalog.
5. **The J5 declaration**: "Local REPL milestone" reached, native C++
   parser library disclosed, no process crash isolation claimed — the
   wording verbatim in the findings.

## Constraints

- Branch jolt-j5 off master (71fd56f8 or later); commits in repo style;
  merging is the orchestrator's.
- Remote verification per build-n-test (shiva + jupiter for the
  two-platform requirement; the safepoint tmux session on jupiter is
  FINISHED — its slot is free).
- The oracle comparison instrument is the landed oracle_parity_test
  (zero pins, JVM canonical per the owner's adjudication).
- known-gaps/spec §10.1 updates land as the same acceptance change.
- Do NOT claim J6/J7 networking — those are separate slices with their own
  preconditions.

Deliverable: branch + findings to
collab/1793071000000-engineer-j5-acceptance.claude-opus-5-5.findings.md
(the §7.8 parity evidence, the standard-catalog and (lang :yang/python)
acceptance run, per-suite results both platforms, the J5 declaration, any
diagnosed-not-fixed remainder). Begin now.
