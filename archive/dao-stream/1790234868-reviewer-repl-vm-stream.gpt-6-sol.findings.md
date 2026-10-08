Completed-GMT: 2026-09-24 07:36:58 GMT
Completed-Local: 2026-09-24 14:36:58 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a0d255-1830-7f30-ab85-0840da6aed72

# Findings: Adversarial Code Review — yin.repl universal dao.stream boundary & 4-VM wiring

Role: Adversarial Code Reviewer and Security Auditor
Model: gpt-6-sol

Verification basis: orchestrator-run suites (not rerun):
- clj-M:kondo: 0 errors, 0 warnings
- cljstyle: clean
- JVM 48 tests / 288 assertions, 0 failures
- CLJS/Node 1900 tests / 47221 assertions, 0 failures
- Dart/CLJD 1862 tests, 0 failures

---

## Actionable Findings

P1 | src/cljc/yin/repl/core.cljc:328 | A gap consumes one of the 4,096 drain
reads. After 4,096 prints and one result fill the 4,096-element ring, the drain
reads the gap and 4,095 prints, leaving the successfully appended result unread.
`finalize-eval` then reports result loss. | Give gap recovery a separate bounded
retry allowance, or continue draining before declaring a missing result. Add a
full-ring boundary test.

P1 | src/cljc/yin/repl/core.cljc:595 | `finalize-eval` accepts the newest
*observed* `:repl/result` without identifying its evaluation. `create-state`
accepts a supplied output stream; if it contains an earlier result and the
current result append is refused, the shell reports that earlier value and
records it as `*1`. | Put a round ID on result tokens and accept only the
current round's token; treat unmatched tokens and append failure as loss. Test a
preloaded, then closed, output stream.

P2 | src/cljc/yin/repl/core.cljc:96 | Both de Bruijn loaders retain every prior
image and recompute H or R over the entire concatenated image on each input
(register path: line 108). Repeated inputs with no surviving closures therefore
grow retained code linearly and total hashing work quadratically. | Retain prior
code only while reachable by live closures, using stable image references so a
new input hashes only its new image.

---

## Passing Areas

- Four VM paths (:ast-walker, :semantic, :stack, :register) and PC relocation
  are consistent with the inspected opcode tables. No wiring defect found.
- `docs/design/yin.repl.md` does not exist; the similarly named
  `yin-repl-design.md` is already labelled historical. No doc-sync action
  required from reviewer.

---

## Orchestrator addendum (2026-09-24 14:42 +0700)

P2 | src/cljc/yin/vm/docs/yin.repl.md:55-59 | Doc states `:ast-walker` is the
only evaluator and asking for another is an error. Branch now wires all four
VMs (:ast-walker, :semantic, :stack, :register). Also missing: description of
result delivery via dao.stream output medium. Reviewer missed this file
(searched docs/design/ only). Escalated to engineer for doc-sync update.
