Created-GMT: 2026-09-21 19:16:21 GMT
Created-Local: 2026-09-22 02:16:21 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your decisions turn)
# Task: fold in the deepseek and qwen reviews; make the stream linker a committed phase
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 02:16:21 +07 | Status: active | Rationale: you authored the design; two independent reviewers from other families (deepseek-v4-pro, qwen3.8-max) returned SOUND WITH CHANGES and converge on one gap against the owner's invariant. One batched turn (GPT weekly budget about 20 percent; resets 2026-09-23 20:44 +07); after it the orchestrator verifies by grep and commits, then fable-5-1 does an architectural check after 04:00.

Work in /Users/sto/workspace/datomworld. You MAY edit exactly ONE file:
`docs/design/yin.vm.debruijn-vm.md`. BE ECONOMICAL: read the two review files and
the design only. Give the complete answer now; do not wait for approval and do not
promise one.

## Inputs

- collab/1790017709976-reviewer-debruijn-vm-decisions.deepseek-v4-pro.findings.md
  (2 P1, 4 P2, 2 P3)
- collab/1790017709976-reviewer-debruijn-vm-decisions.qwen3.8-max.findings.md
  (0 P1, 3 P2, 7 P3)
Verify each against the document before accepting. The owner's invariant, verbatim:
"I want de Bruijn projection so that a yin.vm can easily share code over dao.stream
linker." Both reviewers found no datom.world invariant violated. They CONVERGE on:
the design promises sharing over the linker but delivers only sender-push in B0-B5;
"obtain the image when only H is known" (the pull path) has no owning phase; and the
compliance table overclaims invariant I by citing the deferred section 7.2.

## Decisions (rule ADOPT / MODIFY / REJECT; the orchestrator proposes these)

- **D8 the minimal stream linker is a COMMITTED phase, not optional.** Replace the
  deferred/optional B6 with: **B6 (committed): fetch-by-H for closed images over
  dao.stream** and **B7 (later): dependency closure for free names**. B6: host B
  parks on `:call-hash H` (or an explicit fetch), emits a REQUEST value carrying H on
  a stream, the response arrives on a stream as either the image bytes or a qualified
  absence/unsupported outcome, and B verifies the received bytes by recomputing the
  image hash (D9) and only then loads. The source of bytes is whatever
  observer/peer the composition wires to the response stream; discovery and
  persistent publication stay outside B6 (relax section 2's "outside B0 through B6"
  to "outside B0 through B7", or state precisely what B6 does need). No loader is
  invoked and no callback is retained. B7: name environment as a value or stream,
  strongly-connected-component identity, hash-of-unit closure. A closed image is
  one with NO `:load-free` operands (derivable by scanning the hashed operands; no
  new field). Give B6 and B7 phase boxes in section 6 (file box, must-not-change,
  completion criteria, lanes), with B6 acceptance: host A publishes an image and a
  different-runtime host B (e.g. Dart) that knows only H obtains it over a stream,
  verifies it against H, and executes it with the same normalized results.
- **D9 pin the H function.** H is computed by ONE function defined in B1
  (`image-hash` over descriptor hash plus the canonical positional vector with the
  executable scalar encoding, using `jing/sha256`). Jing's `segment-key` address is
  only the storage address of the same bytes and is NOT H; drop or correct the
  sentence "This is the same canonical form used by `load-image` and
  `jing/segment-key`" and change section 7.2's "verifies their content address
  through `yin.vm.content`" to verification by recomputing `image-hash`
  (`yin.vm.content` stores and fetches bytes). Reconcile "independent of that
  address" so nothing contradicts it.
- **D10 derive, don't persist for scalar classes.** Delete "The image records the
  scalar classes it uses". The classes an image uses are DERIVED from its hashed
  `:const` operand tags; a host lacking a class refuses at decode with a qualified
  `:unsupported-value` outcome before execution. Also state that a producer can
  derive "common-domain safe" by scanning tags before transmission.
- **D11 free names.** State in section 1 (as a boundary of invariant I, not just
  an implication) that until B7 an image is safely shareable only if closed, or its
  free names are bound identically on the receiver; a free name unresolvable in
  the receiver's `resolve-var` order is a qualified runtime error outcome; a B6
  receiver MAY refuse a non-closed image; a closed image is derivable from its
  operands.

## Fold in (the reviewers' remaining findings)

- **B3 vs D7** (qwen P2-1): B3 still says "plus the section 1 benchmark gate";
  change to "the informational section 1 benchmark report (not an acceptance
  condition)".
- **Compliance table** (both): change the invariant-I row to what B0-B5 deliver
  (canonical image hash, verified loading, stream transfer in B5) and what B6
  delivers (hash request and response); change the "Everything is a continuation"
  row to scope it: parked state is explicit data within a VM, cross-host
  continuation transport is deferred (UCF); align the misaligned table cell
  ("Interpretation semantics"); keep the honest strained rows.
- **D4** (both): cite where the named-VM environment leak is established (the
  semantic VM `:return`/`:park` writes of the callee's merged env into `:env`, and
  that `vm-load-program` does not reset it while only `vm-eval` restores the initial
  env; use opus's cited locations in
  collab/1790014395419-reviewer-debruijn-vm-design.claude-opus-5.findings.md, verify
  them), and list "a named-VM environment-leak fix design" in DEFERRED as owning D4's
  release condition.
- **B2 lift equation** (qwen P3-3): equality with `canonical-vector(lower x)` holds
  when the side table carries the ORIGINAL binder names; with synthesized names only
  alpha-equivalence holds; state both.
- **B1/B2 validators** (deepseek P3): add a criterion that every image B2 emits is
  accepted by B1's validator and that hand-built out-of-range images are rejected by
  both, so the two scope walks cannot diverge.
- **Naming** (qwen P3-7): use "de Bruijn VM" consistently (B3 heading and section 1),
  not "frame VM".
- **Section 6 opening sentence** must cover B0-B7 after the change.

## Do

1. Make the edits; keep the document's conventions (numbered sections, ASCII box
   tables, no em dashes, 80 columns, status "design; not implemented", no
   operational routing text). Update section 8: DECIDED gains D8-D11 with rationale
   and the invariant each serves; DEFERRED keeps only what is truly undecidable
   (B6 request/response schema details, contract stamps, B7 mechanics, retry and
   timeout event vocabulary).
2. **Your sign-off as author**: READY or NOT READY to commit and begin B0.
3. Report briefly: dispositions of D8-D11 and each folded finding (ADOPT / MODIFY /
   REJECT with reason), sections changed, and anything that still needs the owner.

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a
then: the sign-off line, the D8-D11 dispositions, the folded findings' dispositions,
sections changed, and anything still needing the owner.
