# Review of UCF Phase 1 delta (worktree ucf-phase1)

Reviewer: ZCode subagent, GLM-5.3-Flash (independent of claude-sonnet-5 author).
Artifacts: collab/1790268690622-reviewer-ucf-phase1.glm-flash.{prompt.md,findings.md}
Completed: 2026-09-25 ~00:55 +07. Read-only; every claim verified against the worktree.

Contract fidelity verified against the engine and design (UCF §7.1-7.4/§7.11,
yin.vm.semantic.md §2.4-2.7/§4): the 21-mnemonic transitions map matches the
actual engine (only :stream/put, :stream/next, :park, :ffi-call, effectful
:call block); all five stack-effect rows match §7.4.1; resume pc = pc+1 is
provably in-bounds; the dynamic half is never derived from code. The
semantic.cljc change (+6/-12) is the intended, documented fix for the
unsaturated local canonicalizer; the coupling is justified.

Findings (all P3, none blocking):

- P3 | ucf.cljc:171 (vs semantic.cljc:585-599) | Saturation fires on (nil? v)
  while the loader's decode saturates on truthiness: a batch with
  :yin.code/prefix false executes as "id" but canonicalization keeps false,
  which well-formed-vector? refuses — two identically-executing batches can
  diverge (address vs refusal). Contrived, fail-closed. | Use
  (or v (get saturation-defaults [mnem a])) mirroring decode, or narrow the
  docstring's equivalence law.
- P3 | ucf.cljc:77 | [:ffi-call :yin.code/argc] 0 is unreachable (well-formed?
  rejects nil argc first). | Drop or annotate as mirroring the dead decode
  default.
- P3 | ucf.cljc:265-273 | Static :reasons name frame reasons the reference
  machine never mints (only :next/:put are produced of the six-value enum).
  Harmless in Phase 1; a Phase 2 lift driver keying on the design enum will
  never match. | Mint design reasons in Phase 2 or document :reasons as the
  safepoint-kind label.
- P3 | ucf_test.cljc | Five behaviors unpinned: :reasons for :park/:ffi-call;
  FFI-retained dynamic row; canonicalize's non-UCF rethrow; load-image error
  precedence for well-formed-but-non-canonicalizable claimed-hash batches;
  multi-frame stack-bases ordering. | Add when next touched.
- P3 | ucf.cljc:81-90 | Third byte-identical private copy of index-batch
  (code.cljc:53, semantic.cljc:517). | Expose one copy in yin.vm.code later.
- P3 | provenance | The implementing session's final report log is absent
  from the worktree collab/ and the main-tree copy ends mid-stream ("Waiting
  on the Dart suite and the Node rerun") with no file list or test outcomes —
  the 18/109/0 claim rests on the orchestrator's own fresh runs (which
  confirmed it). | Attach the final report before merge or note the
  orchestrator evidence as the record.
- P3 | ucf.cljc:203-207 (inherited) | Metadata-bearing literal operands are =
  but address differently via dao.jing (jing.cljc:105), against §7.3.2's
  equal-literals-address-equally. UCF inherits dao.jing's transitional
  encoder. | Record in the divergence register, not in ucf.cljc.

Verdict: READY
