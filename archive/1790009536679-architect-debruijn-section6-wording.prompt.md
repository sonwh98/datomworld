Created-GMT: 2026-09-21 16:52:16 GMT
Created-Local: 2026-09-21 23:52:16 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your status-line review turn)
# Task: architect wording — section 6 sentence about what the VM and linearize consume
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-21 23:52:16 +07 | Status: active | Rationale: the design's author owns its wording; you reported this sentence as stale (P2) in your previous turn

Read-only. Work in /Users/sto/workspace/datomworld (master; de Bruijn merged; the
status-line commit d06ebffc is in). Give the complete answer now; do not wait for
approval and do not promise one. Edit no file.

## The sentence you reported

docs/design/yin.vm.debruijn-projection.md §6 (after the status edit it sits at
about lines 221-222; the line numbers in your report were pre-edit). Current
paragraph:

  Named storage, renderers, Datalog queries, source maps, and diagnostics
  consume the named stream. Deduplication, cache lookup, and alpha-equivalence
  consume the projected segment/fingerprint. The semantic VM and
  `yin.vm.linearize` continue to consume the named AST. A waitset is optional
  only for a composition that owns several independent waiters; no lease fact,
  lease timer, callback, or scheduler is introduced here.

You reported: `yin.vm.linearize` consumes named AST datoms, but the semantic VM
executes a loaded `:yin.code/*` image, reached through an explicit composition
(docs/design/yin.vm.semantic.md:331-352).

## Facts the owner and I confirmed in the code (verify, do not trust)

- `yin.vm/ast->datoms` (and `ast->datoms-with-root`) takes an AST MAP and emits
  named AST datoms `[e a v t m]` (`:yin/*`); `datoms->ast` is its inverse.
- `yin.vm.linearize/lower` takes those AST DATOMS (its docstring: "Lower one AST
  program, as [e a v t m] datoms, to one code segment") and emits the linear
  executable `:yin.code/*` datoms. It reads the datom index directly and
  deliberately does not reconstruct maps, because each instruction names its AST
  entity in `:yin.code/source`. `lower-ast` is documented as `lower` composed
  with `ast->datoms`, a map-accepting convenience.
- The semantic VM executes the `:yin.code/*` image; `yin.vm.ast-walker`
  separately evaluates named AST maps directly.
- The owner corrected me on exactly this point: linearize does NOT take maps and
  produce datoms; `ast->datoms` does that, and linearize is the NEXT step.

## Answer

1. Give the exact replacement for the sentence "The semantic VM and
   `yin.vm.linearize` continue to consume the named AST." that is precise about
   the three steps (map to named datoms; named datoms to `:yin.code/*`;
   `:yin.code/*` executed), says the ast-walker path where relevant, and states
   that none of them consumes the projected form. My draft to react to (approve,
   fix, or discard):

     "The named AST reaches execution unchanged: `yin.vm/ast->datoms` emits the
     named AST datoms, `yin.vm.linearize` lowers them to the `:yin.code/*`
     image, and the semantic VM executes that image; the ast-walker evaluates
     the named AST directly. None of these consumes the projected form."

2. Watch one ambiguity: `yin.vm.linearize/lower-rows` and the code-as-tuples
   design speak of a "projected row set" (UCF section 7.3.2). That is a
   different "projection" from the de Bruijn one. If the sentence could be read
   as contradicting or confusing that, say so and word around it.
3. Line-wrap the replacement at 80 columns and keep the rest of the paragraph
   (waitset sentence) intact, so the owner can paste it in.
4. Also give exact replacement wording, with the same constraints, for the
   other two sentences you reported: section 7 D5 "Wire Yang's post-emission
   path before projected persistence" (delivered as the standalone
   `yin.vm.pipeline/persist-compiled!`, no Yang caller yet) and section 1's
   "All state is explicit in the forward-step state ... output cursor" (the fact
   index, scope stack and memo live inside the atomic per-frame projection).
   The owner has asked only for section 6 now; give 4 as optional, clearly
   separated, so nothing is applied unless the owner says so.

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a
then APPROVED or REPLACE WITH for item 1, then items 2-4. Edit no file.
