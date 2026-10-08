Created-GMT: 2026-10-01 16:45:00 GMT
Created-Local: 2026-10-01 23:45:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Design the Post-M5 Hardening Contracts (kept-cursor proof, ownership fencing)

Role: Lead System Architect

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-01 23:45:00 +0700 | Status:
  active | Rationale: the acceptance matrix marks these rows post-M5;
  M5 landed, so the hardening design is now in scope and is
  architecture authorship.

Background: the linker-over-DHT epic landed (master @ df7cf1f4, epic
confirm READY/GRANTED). The acceptance matrix in
docs/design/yin.vm.universal-continuation-format.md section 7.11 leaves
two hardening rows open:
1. Cross-host kept-cursor proof — the portable-encoding blocker row
   notes the kept-cursor/aliasing round trip "may require post-M5"
   verification on cross-host resume.
2. Full ownership fencing — the ownership row lands a basic exporting
   gate in M4, with custody transfer and effect fencing (authority
   epochs, stable operation ids) as post-M5.

Task: author the design contracts for both rows, as a new section (or
sections) in docs/design/yin.vm.linker.dht.md (the design doc they
harden). For each:
1. The acceptance invariant restated precisely, per host and per
   safepoint kind where relevant.
2. The mechanism design: what state the linker/runtime carries, what
   the wire format gains (if anything), the exact lift/lower steps.
3. The test contracts M-next implements (setup/action/assertion, per
   host), following the acceptance matrix style.
4. Migration/sequencing: what lands first, what depends on what, and
   any UCF amendment required.

Read first: the UCF doc sections 7.3-7.7 and 7.11 (the matrix),
docs/design/yin.vm.linker.dht.md (whole),
docs/design/yin.vm.ucf-revisions.md section 8 (the :reasons ruling),
src/cljc/yin/vm/linker/dht.cljc and yin/repl/link.cljc (the landed
machinery).

Write scope: docs/design/yin.vm.linker.dht.md ONLY. ASCII, <= 80
columns on added/edited lines.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Summarize both contracts and their sequencing.

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
