# VM Runtime Engineer Brief: Linker Hardening Stage 1 P1 Fixes (Round 5)

## Architect Gate Review Findings (Codex gpt-6.1-sol)

Architect review gate returned **REQUEST CHANGES** with 4 blocking P1 items:

1. **P1 | handoff.cljc:849 | referenced-cells traversal**:
   `referenced-cells` omits wait-frame registers and retained request/link envelopes. A cursor reachable only through a blocked frame's environment, stack, or closure is minted during encoding, then rejected as an `:extra-cell`.
   **Fix**: Traverse every encoded root, including wait-frame registers (`(:yin.k/registers frame)`), keys, and all pending payloads/envelopes. Add test coverage for cells reachable exclusively through wait-frame registers and pending envelopes.

2. **P1 | handoff.cljc:1197 | stream markers across complete reachable graph**:
   Stream attachment collects only cell streams and selected pending markers. A stream reference carried solely in a store, closure, register, parked record, or halt result is never attached; decoding subsequently refuses it.
   **Fix**: Collect stream markers across the complete reachable graph (cells, pendings, registers, stores, parked records, closures, result), deduplicating by stream identity. Pass all collected markers to `attach-all!`.

3. **P1 | handoff.cljc:1063 | child install validation & refusal propagation**:
   `resume-installs` extracts `:vm` from child `resume-task` without checking success. A child refusal becomes `:vm nil` while parent returns `:status :ok`.
   **Fix**: Recursively validate child bodies with `validate-body` before attachment/restoration. In `resume-installs`, check child resumption outcome; if child returns a refusal or unsatisfied status, propagate the failure and abort parent restoration.

4. **P1 | handoff.cljc:807 | frame & register validation**:
   Register validation checks key presence and segment membership, but does not validate field types, resume-pc safepoint eligibility, or pending-kind compatibility. Lowering can accept a tampered frame at an arbitrary pc; a `:blocked` body can also omit all frames.
   **Fix**:
   - For `:blocked` kind, enforce non-empty `(:yin.k/frames body)`.
   - Validate field types: `(:yin.k/pc r)` non-negative integer within segment bounds, `(:yin.k/env r)` vector, `(:yin.k/stack r)` vector.
   - Validate resume-pc safepoint eligibility: pc must correspond to an admissible instruction/safepoint kind matching `(:yin.k/reason pending)` from `ucf/parking-kinds`.
   - Add unit test coverage in `test/yin/vm/ucf/handoff_test.cljc` for malformed frames, out-of-bounds pc, missing frames on `:blocked`, and invalid register types.

## Verification Constraints
- Pure ASCII, max 80 columns, no em dashes.
- `clj-kondo` 0/0, `cljstyle` clean.
- Run tests: `mise exec -- bb test:clj` and `mise exec -- bb test:cljs`.
- Keep changes focused strictly on `src/cljc/yin/vm/ucf/handoff.cljc` and `test/yin/vm/ucf/handoff_test.cljc`.
