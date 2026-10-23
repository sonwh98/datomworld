Created-GMT: 2026-10-20 17:55:00 GMT
Created-Local: 2026-10-21 00:55:00 +07
Coding-Agent: claude
Model: claude-fable-5-1
Session-ID: 36134bec-9cbd-4852-b78c-4901af66fd02
Task: host-boundary design r6, three low defects D1-D3 from cmd's confirmation check
Role: Architect
Deliverable: docs/design/dao.stream.host-boundary.md edited in place (r6); no src edits, nothing staged.
Labels: V verified by reading. No lane run. Nothing else of substance changed.

# Rationale

D1: section 8's subsections were 8.2, 8.3, 8.1 in file order. Renumbered in
place (list 1 is now §8.1, the host-library gate §8.2, codec disjointness
§8.3) and every cross-reference remapped by a placeholder substitution, so
no stale number survives. D2: HD4, §10 and §14 still described H2's deletion
gate as a grep; reworded to the closure tool reading `ns` forms, matching
the H2 cell. D3: the library mode of `dao.host.gate-test` now lands with H2,
not H0, because H0 is a freeze with no source change and the mode has no
consumer before H2's deletion; H2's deliverables name it, and its acceptance
gains two negative tests (an executable `BigInteger` reference in a fixture
closure file fails; the same name only in a docstring and a keyword passes).
H0 stays the freeze only.

# Disposition

- D1: fixed; headings at lines 745, 804, 824 renumbered; references at lines 60, 111, 404, 513, 666, 669, 694, 720, 724, 729, 763, 802, 930, 1083, 1109, 1130, 1160 remapped.
- D2: fixed; HD4 (line 977), §10 (line 950), §14 (lines 1085-1087, 1111).
- D3: fixed, option "lands with H2"; §8.2 opening (lines 809-815), H2 cell (lines 720-729).
