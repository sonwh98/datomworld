Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: Architect ruling — eval-bridge source-byte domain + the class-collision oracle record

Role: Architect (read-only design consult; you own the eval-bridge design)

The implementation stopped twice under the stop rule. Two questions need your ruling before part 2 dispatches. Branch eval-bridge @ 80646de2 (the engineer's gap record: collab/yang-php/1794605391000-engineer-eval-bridge.gpt-6.1-terra.findings.md — read first; oracle probes: collab/yang-php/1794574000000-era-oracle-eval-probes.txt — the shiva php:8.3.35 records).

Q1 — source-byte domain: the design mandates byte carriers with byte-relative spans in eval requests; the existing parser boundary (cst.cljc parse-unit) is Unicode-based (code-point offsets, UTF-8 derived line starts). The engineer laid out the options (strict reject / replacement decode / byte-preserving parser path) and asked for the supported domain + representation ruling, with oracle fixtures for multibyte + invalid sequences.

Q2 — eval semantics pinned by oracle (records above): class collision = UNCATCHABLE compile fatal; defined classes/functions persist; mode-exit works; caller vars visible; syntax errors = ParseError. The design's "pin fatal timing and catchability" is now pinned — confirm the engine's eval-unit must reproduce EXACTLY these (uncatchable collision fatal — meaning the eval-unit emission cannot route the collision through a catchable path).

Rule with: (a) the admitted source-byte domain (valid multibyte UTF-8 only? arbitrary bytes with an explicit classified refusal? byte-preserving parser path feasibility given cst.cljc's Unicode offsets) and its representation at the parser boundary; (b) the fixture list the oracle must pin for the byte domain; (c) confirmation/amendment of the eval-semantics set above for the engine's eval-unit; (d) any amendment to your design doc that this ruling makes.
Deliverable: collab/yang-php/<ts>-architect-bytedomain-ruling.findings.md. Read-only consult.
