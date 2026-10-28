Created-GMT: 2026-10-27 02:19:46 GMT
Created-Local: 2026-10-27 09:19:46 +07

# Task: J5 — amended acceptance scope (owner ratification 2026-10-27)

Your J5 acceptance run is superseded by this amended brief. Your in-flight
work in the jolt-j5 worktree is preserved and PART of the amended scope —
the parser/frontends/main/cst.jolt edits and the transcripts fixture are
the standard-catalog and frontend work continuing; nothing you have done
is discarded. What changes: the scope GROWS (row 59 closes inside J5, per
the owner's ratification of the glm-5.3 adjudication), and two additional
acceptance items land.

## The ratification (binding)

Owner ruling 2026-10-27: **close row 59 inside J5**, per the glm-5.3
adjudication (collab/1793096000000-architect-mob-row59-j5-scope.glm-5.3.findings.md
— read it; it specifies the mechanism). Summary:
- **Input bound N_bytes 4 MiB**, enforced in the C++ wrapper at the top of
  `jolt_parser_export`, before `SourceStream` is constructed. New status -6.
- **Nesting bound N_depth 256**, enforced by a flat O(bytes) prescan in the
  wrapper enumerating the recursive carriers (brackets, unary runs, `**`
  chains, ternary chains, lambda chains, INDENT depth — counted, not
  inferred from input size), charging every carrier at the worst per-level
  cost, over-refusing cheap shapes before expensive ones become unsound.
  New status -7. Record the carrier table and per-level costs in the build
  record.
- **Stack clamp**: promote the existing `jolt_parser_thread_info` logic to
  a production read-only helper; clamp N_depth down proportionally when
  the caller's stack is smaller than the derivation basis (macOS 512 KiB
  jolt thread, crash bracket 512/544; 256 is the 2× margin). The clamp
  never raises the bound.
- **Time is derived, not interrupted**: no wall-clock kill (§7.6 forbids
  pretending a timeout makes native termination safe). The acceptance
  records the measured worst legal-input parse wall on both platforms.
- **Outcomes**: operational `:parser-budget` diagnostics
  `{:diagnostic :parser-budget :axis :input|:nesting :bound n :measured m
  :lang …}` through the same channel `:unavailable-parser` uses; thrown at
  the parse stage; never a syntax error; the session survives; no partial
  packet.
- **Row 59's other components**: lifecycle/close/reset wiring (the shared
  stage owns its worker, disposes at reset/teardown; reset remakes it;
  convenience-call workers disposed on their one call; closed workers
  refuse further parses) and the unavailable-helper outcome (lazy soft
  load; first use attempts load-library + layout check and caches the
  failure; parse entries answer the existing qualified
  `:unavailable-parser`; a later selection retries; spike jolt's
  ffi/load-library deferral semantics first — if the host fights ns-load
  deferral, load lazily inside make-worker).
- **Disclosures that survive**: no process crash isolation (a bug in an
  under-bound parse still takes the process); no cancellation of a
  legal-but-slow under-bound parse; TSan not run; the unary/indent
  "censored measurements" wording superseded by enforcement (the r5 stack
  table remains the derivation evidence).

## Amended scope

The original five scope items stand (the J0c probe verification at this
head, the standard catalog on jolt, `(lang :yang/python)` on the C++
backend, the three suites at master counts, the end-to-end acceptance
run, the J5 declaration) PLUS:

A. **The row-59 mechanism** (above), implemented per the ruling §2.1–2.4.
B. **Test gates per axis** (ruling §2.4): below/at/above on both
   platforms, deterministic; 10×-crash-depth in-process refusals
   (100,000 nested parens, ~5,000-deep INDENT, 100,000 unary prefixes —
   all refuse, no crash); child-process legs at the old crash depths
   (e.g. macOS-thread parens 576) refusing rather than SIGSEGV; the
   fault-injection harness property (a crash fails the test, report
   retained) covering the injected-exception and forced-abort legs.
C. **Lifecycle legs**: the 12 worker tests extended with the
   close/reset cycle; the 20,000-cycle flatness and sanitizer legs rerun
   with the wiring in place; the two-worker reset-isolation legs on the
   composed REPL.
D. **Unavailable-helper legs**: library absent; wrong layout version;
   Clojure/PHP still selecting and evaluating in the same session.
E. **The five `:owner "J5"` applicability entries retire** against
   count-identity with the JVM (fable's addition 5 consequence: the JVM
   is the target, not master's jolt counts which embed the exclusions).
   The main.cljc banner arm and the J3-row cross-reference retire.
F. **The ledger rule**: known-gaps row 59 closes with the §2.4 evidence;
   §7.6 and the §10.1 J5 row update to landed; the five applicability
   entries retire. Per orchestrator.md step 10's ledger rule: the union
   of rows, never a rewritten file.
G. **The disclosure paragraph**: the J5 declaration carries the surviving
   disclosures verbatim (no crash isolation, no worker-restart claim, no
   cancellation, TSan/LSan limits, the above-bound divergence from the
   JVM recorded).

## Also still in scope from your original brief

- The §7.8 parity evidence at this head (zero pins, JVM canonical).
- The standard catalog on jolt, `(lang :yang/python)` on the C++ backend,
  the three suites at master counts.
- The end-to-end acceptance run with no hand-built catalog.
- The J5 declaration with the surviving disclosures.

## Constraints

Branch jolt-j5 continues (do not re-create); commits in repo style;
merging is the orchestrator's. Remote verification per build-n-test
(shiva + jupiter — jupiter's safepoint slot is FREE, the run finished).
The two-platform requirement stands. The J4d review is fable's and is
separate.

Deliverable: the amended-scope branch + findings to
collab/1793098786624-engineer-j5-acceptance-amended.claude-opus-5-5.findings.md
covering BOTH scopes: the original five items plus the row-59 mechanism,
with the per-axis test-gate evidence. Begin now.
