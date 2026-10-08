Completed-GMT: 2026-10-02 15:38:58 GMT
Completed-Local: 2026-10-02 22:38:58 Asia/Ho_Chi_Minh

# Gate Review: Linker Hardening Stage 1 (M-next A: Kept Cursors)

Reviewer: Architect / Review Gate (Codex `gpt-6.1-sol`, thread `01a0fd43-6335-7a81-a229-73b728ba54d1`)
Status: **REQUEST CHANGES**

## Blocking Findings (4 P1 items)

- **P1 | [handoff.cljc:849](/Users/sto/workspace/datomworld-linker-hardening/src/cljc/yin/vm/ucf/handoff.cljc#L849)** | `referenced-cells` omits wait-frame registers and retained request/link envelopes. A cursor reachable only through a blocked frame's environment, stack, or closure is minted during encoding, then rejected as an `:extra-cell`. Traverse every encoded root, including keys and all pending payloads. Add coverage for cells reachable exclusively through these roots.

- **P1 | [handoff.cljc:1197](/Users/sto/workspace/datomworld-linker-hardening/src/cljc/yin/vm/ucf/handoff.cljc#L1197)** | Stream attachment collects only cell streams and selected pending markers. A stream reference carried solely in a store, closure, register, parked record, or halt result is never attached; decoding subsequently refuses it. Collect stream markers across the complete reachable graph, deduplicating by identity.

- **P1 | [handoff.cljc:1063](/Users/sto/workspace/datomworld-linker-hardening/src/cljc/yin/vm/ucf/handoff.cljc#L1063)** | `resume-installs` extracts `:vm` from the child's result without checking success. A child refusal, such as a missing route or incompatible version, becomes `:vm nil` while the parent returns `:status :ok`. Propagate child refusals and recursively validate child bodies before attachment or restoration.

- **P1 | [handoff.cljc:807](/Users/sto/workspace/datomworld-linker-hardening/src/cljc/yin/vm/ucf/handoff.cljc#L807)** | Register validation checks key presence and segment membership, but does not validate field types, resume-pc safepoint eligibility, or pending-kind compatibility. Lowering can accept a tampered frame at an arbitrary pc; a `:blocked` body can also omit all frames. Enforce these shape and semantic checks before restoration, with malformed-body coverage.

## Passing Properties

- Export rejects queued work without polling and checks observed wait reasons against static safepoint kinds.
- Encoding uses one cell map across task roots; lowering allocates fresh resources and re-seals decoded references.
- Isolated-store restoration uses `engine/store-put`; the audit allowlist documents that boundary.
- Explicit park has no phantom wait.
- I-5 records the static-kinds migration. Section 6 documents all six Stage 2 obligations and the required `:yin.k/version 1` amendment.

I accept the supplied JVM, Node, Dart, lint, style, ASCII, and column-width evidence without re-running it. No files were edited and no test suites were run. The operative §14.1 text is in `yin.vm.linker.dht.md`.

**REQUEST CHANGES — the four P1 items above block sign-off.**
