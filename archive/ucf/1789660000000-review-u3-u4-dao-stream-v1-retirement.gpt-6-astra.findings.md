Coding-Agent: codex
Model: gpt-6-astra

# Review: U3 and U4 — dao.postgraphics.terminal and dao.gui.event on v2

## Verdict

**NOT READY FOR ARCHITECT SIGN-OFF.** The `:error/kind` question alone
should block sign-off until the canonical vocabulary is documented and
implemented consistently. Also found a startup regression and an unmet
U4 acceptance criterion.

## Findings

1. **P1 — The Flutter dao.gui Prototype loses its initial sample frame.**
   `terminal.cljc:82` now binds at `:newest`. The picker calls `start!`
   before mounting the view; the view's `LayoutBuilder`
   (`dao_gui.cljd:353`) emits the sample before constructing the child
   terminal widget. That sample precedes cursor creation and is skipped —
   an empty initial surface until another frame is emitted, contrary to
   U3's own acceptance criterion. Preserve D4's `:newest` rule by
   arranging initial emission after binding, and verify first mount and
   reopening.

2. **P1 — Terminal stream failures emit signals outside the canonical
   protocol.** `terminal.cljc:142` uses raw stream outcomes as
   `:error/kind`, whereas `dao.postgraphics.md:307` exhaustively permits
   only three ordering-error keywords. Migrating DaoStream does not
   implicitly version the graphics protocol. **Recommendation:** document
   a terminal-owned extension such as `:dao.terminal/transport-error`,
   with the underlying stream outcome carried separately; explicitly
   define how `:dao.stream/end` maps into it. Also resolve the payload:
   the current call supplies a submission counter as `:frame-id`, but
   submission and presented-frame identities differ after rejection or
   skipping, and a transport failure may occur before any frame exists.
   Update the canonical spec, terminal documentation, implementation, and
   tests together.

3. **P1 — U4 still re-reads after terminal input outcomes.**
   `event.cljc:826` returns `:transport-error` without retaining a
   stopped/error state; advancing the returned binding calls `next`
   again. `bind_test.cljc:259` explicitly asserts that two advances
   produce two reads. This meets "one read per call" but not U4's own
   stated "transport-error ... does not re-read" criterion. Retain the
   terminal failure so subsequent advances return without reading, or get
   an explicit architectural revision assigning that responsibility to
   callers — the current test should not certify the opposite behavior.

4. **P2 — The documented origin guarantee exceeds what lazy cursor
   creation provides.** `event.cljc:783` follows D5 literally by minting
   on first `advance`. But creating a binding before input exists does
   NOT ensure origin observation: bind an empty capacity-one stream,
   append twice, then advance — minting `:oldest` observes the retained
   suffix without a gap. This is an ambiguity inherited from the plan,
   not a coding error. Document that callers must supply a cursor minted
   before production, or perform the first advance before production
   begins; add coverage for that boundary. (Retaining the minted cursor
   after a blocked first read is correctly implemented.)

## Confirmed clean (no findings)

- **Submission IDs**: terminal-local counter is appropriate; spec already
  describes terminal-local ingress numbering; no production consumer
  inspects the numeric value; a gap increments once per observed gap
  (must not be described as counting every evicted frame).
- **Option compatibility**: both widgets retain public `:signal-stream`,
  translate to internal `:signal-handle`; no incompatible caller found.
- **D4 waiter removal**: confirmed — no waiter registration, wake list,
  or producer-driven presentation remains; tickers drive bounded stepping
  with disposal/unmount cancellation.
- **D5 dispatch and parking**: outcome-map dispatch, pending-output
  ordering, `full` parking, dropped-output-with-diagnostic all match the
  disposition; recovery-cursor handling correct.
- **Artifact split**: structurally confirmed disjoint (frame constructor
  vs. runtime-input/signal/output/cursor/event-driver changes).
- **Scripted fixture**: genuinely exercises refusal-then-admission,
  ordered flushing, and input transport-error reporting; no v1
  destructive-take mechanism remains (its no-retry assertion needs the
  fix in finding 3).

## Verification approach

Static review and test inspection; accepted the orchestrator's reported
three-host suite results without rerunning them. No files edited.
**Manual Flutter/browser animation, pause, drag, and keyboard checks
remain open acceptance gates — neither the passing suites nor the
abandoned `flutter analyze` substitute for them.**
