Created-GMT: 2026-10-05 08:58:04 GMT
Created-Local: 2026-10-05 15:58:04 +07
Coding-Agent: claude (fable-5-1, resume of session ff5b8c32-5cb0-4d81-9476-0a88c6109319)
Session-ID: ff5b8c32-5cb0-4d81-9476-0a88c6109319

# Task: author the M-next D plan (read-only; the plan is the deliverable)
Role: Architect

You authored the M-next C plan (r2). Master has since landed C6 to C11
(commits 9e53b93c, 9153b75e, d484080d, 919f7db7, 3bbb9856, 59479d9d,
9352001f, 51efd46c, 268c4f4b, 485fe1a1; doc amendments e748c4b2 and
8bae3f57) and C12, the stage-C gate, is landing today: the durability
predicate (`authority/durability`, `exclusive-capable?`), the crash-cut
matrix over 11 transitions x 3 cuts on memory plus a 4-row file subset,
and the cross-host ledger fixture (`test/resources/yin/vm/ucf/ledger-v1.txt`,
22 lines, pinned BLAKE3 projection digest). C12's gate review
(APPROVE-WITH-NITS, all findings doc-level, reconciled) and the
engineer's report are at
collab/1791124400000-compiler-engineer-ucf-c12-gate.findings.md and
collab/1791125400000-reviewer-c12-gate-gate.glm.findings.md. The C12 doc
sentences landed in UCF 7.7.7 (enrollment retry), 7.11.1 (stage-C
boundary), linker-dht 14.2.1 (the exclusive-capable? contract), and
dao.stream.journal.md (:persisted pin, cost paragraph). The C4 rulings'
deferred-to-document-text items did NOT land; they are yours to place.

Author the stage-D plan in the mold of your C plan: contract first, then
slices D1..Dn with files, test contracts, and order/concurrency, then the
boundary with E, then the risks, then the doc amendments implementation
will force.

## Scope D owns

From the C plan section 3 and linker-dht 14.3 item 4:

- sequence assignment, retained pending state, the fenced writer,
  version-1 restoration, input delivery discipline, outcome correlation
  and wait discharge, routing writes to enrolled streams;
- integrate the handoff driver and fenced writers with every pending
  path, child work, and the source exporting/abort discipline;
- wire `yin.repl.core`'s handoff composition as UCF 7.11 requires; keep
  plain functions usable without the REPL; reuse the landed staged
  module-load/link path; do not add a second loader or DHT step owner.

Carried over from the C4 inspector rulings (deferred to D): version-1
export with `jing/canonical-bytes` and a segment address; the version-0
codec and address mismatch as a third post-A defect with its own
version-0 test; regenerating the accepted fixtures through a real
export; install `:yin.k/phase` and `:yin.k/parent`, the
halted-forbids-frames rule, and the rest of clause 5; deterministic
payload encoding across snapshot variants; and the C4
deferred-to-document-text items (UCF 7.2.1 naming `jing/canonical-bytes`
and the segment-address form, the version gate's nested-body statuses,
7.7.8 *Snapshot variants* comparison on digests, 7.9 hash-mismatch over a
body's own address, linker-dht 14.3's third version-0 defect).

## UCF 7.11.1 evidence owed at D

Version-1 block clauses marked stage D: 1 (version gate), 2 (custody
header), 3 (sequence assignment and restoration), 4 (structural carried
ids, first exclusive export over an attempted write), 5 (explicit park
and install completeness, both versions, including the two version-0
defects fixed with version-0 tests), 6 (the writer half), 7 (the holder:
answers `:yin.k/not-holder`, releases on an invalid binding), 8 (the
driver half), 9 (sequence bounds). Clause 10 and the composition halves
of 3 and 8 stay with E. The M4 safepoint harness evidence (clause 5 of
the safepoint block; the lift driver consumes linker section 11 item
12's UCF table amendments before its round-trip tests run) is also owed.
Every 14.2.4 test contract not marked stage E is D's.

## Read

- docs/design/yin.vm.universal-continuation-format.md (as amended on
  master through C12: 7.2.1, 7.4.1-7.4.3, 7.7.1-7.7.8, 7.9, 7.11.1)
- docs/design/yin.vm.linker.dht.md (14.1 to 14.3; 14.2.2's input
  protocol, 14.2.3's exact steps, 14.2.4's test contracts)
- docs/design/yin.vm.linker.md (5.2, 9, 11 item 12)
- docs/design/yin.vm.ucf-revisions.md (section 6, what stays pending)
- src/cljc/yin/vm/ucf/handoff.cljc (the stage-1 version-0 driver:
  lift, lower, validate-body, resume-task)
- src/cljc/yin/vm/ucf/checkpoint.cljc and the landed authority
  namespaces under src/cljc/yin/vm/ucf/ (the C substrate D writes to)
- the C plan: collab/1791097400000-architect-linker-m-next-c-plan-r2
  .claude-fable-5-1.findings.md (section 3's D/E boundary)
- the C4 inspector rulings:
  collab/1791098900000-architect-c4-inspector-rulings
  .claude-fable-5-1.findings.md
- the C12 reports named above
- docs/design/yin.repl.md and the yin.repl entry points D must wire

## Constraints

- .cljc portable unless a host demands otherwise; three lanes for every
  slice; canonical byte fixtures over host-number semantics.
- The version-0 wire is frozen: version-0 defects are version-0 fixes
  with version-0 tests, never a grammar change.
- No second loader or DHT step owner; the composition may gate exclusive
  only when `authority/exclusive-capable?` says so.
- Keep plain functions usable without the REPL.
- Governance recovery (after quarantine, exhaustion, or lost authority
  state) stays outside the automatic protocol.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
