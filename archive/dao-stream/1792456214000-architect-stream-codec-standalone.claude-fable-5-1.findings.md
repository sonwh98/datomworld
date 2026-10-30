Created-GMT: 2026-10-20 00:30:14 GMT
Created-Local: 2026-10-20 07:30:14 +07
Coding-Agent: claude
Session-ID: 8c79f881-a441-4e88-9098-a7d3b7a75d13
# Findings: the codec design made a standalone track
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-20 07:30:14 +07 | Status: done | Rationale: correction round; the owner separated the tracks

Edited in place: `docs/design/dao.stream.codec.md` and `docs/design/yin.repl-jolt-implementation.md`. No src edits, nothing staged.

## Codec document

`grep -ci jolt docs/design/dao.stream.codec.md` = **0** (was 9).

- Status: no supersession of any other document; an independent track runnable from this document alone.
- §1 and §6.3: the "host without a Transit implementation" example is generic (none of the three lanes today; a future host lane is its own track depending on C0-C4 only).
- §6.4: the CBOR recommendation is justified from the repo alone (implementation sizes 212/306 vs 571 lines, bytes carrier, superset domain, no compatibility constraint).
- §7.1: C0 and C2 acceptance made self-contained instead of citing an external review; the slices depend on nothing outside the document.
- §7.2: 22 namespaces by grep; the brief's 20 noted as superseded.
- New §10 "Open owner decisions": OQ1 default profile (recommend CBOR; Transit explicit until recorded), OQ2 the 571-line cljd Transit port, OQ3 move or keep the `:ws/*` keys.
- New §11 "For a successor orchestrator": decided vs open, start with C0, gate ladder from `docs/agents/build-n-test.md` (G0 `bb test:sub:clj dao.stream`, G1 `bb test:sub dao.stream`, G2 `bb test:changed`, G3 `bb test`, slow tier once after C4), and the review rule: Claude-authored, so a non-Claude architect signs off.

## Jolt document

The codec design is no longer restated. It appears as one dependency statement in §9.4 ("J6 needs the codec track to have landed C0-C4; jolt requires nothing else of it") plus jolt-only consequences (jolt passes `dao.stream.cbor/profile`, the `.jolt` CBOR wire writer, the `:jolt` arm of the shared number predicate, Transit tests excluded on jolt). §4.2, §4.4, §9.6, the slice table (an "External dependency" row), §10.2, §12, §13.3 (now empty), ruling 1 (withdrawn, history kept), the third change note and the appendices are reduced accordingly. A4, A5 and Autobahn corrections are untouched.

## Not verified

Nothing was run. Table widths rechecked at 170 columns; no em dashes in the codec document.
