Created-GMT: 2026-09-25 13:20:00 GMT
Created-Local: 2026-09-25 20:20:00 +0700
Coding-Agent: claude
Session-ID: ae52a4a7-1fd1-48de-bc13-9f7a4a05f16a

# Task: make yin/def unshadowable by construction (design, read-only)

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-25 20:30 +0700 | Status: active | Rationale: owner directive "send the yin/def brief to fable"

Design how yin/def becomes impossible to shadow or redefine, and what that
does to the uncommitted M2 linker work. Read-only: do not edit files.

## Owner statements (verbatim quotes)

"what if we make that impossible?" (about a program overwriting what
yin/def means)
"then that answers the question def cannot be shadowed or redefined"
(after being shown that in Clojure def is a special form: a local named def
does not change (def x 5), (resolve 'def) is nil, and (def def 7) does not
disturb later defs)

## Orchestrator framing (my reading, not the owner's words; challenge it)

- Today yin/def is a primitive found by NAME. engine.cljc:54 (resolve-var)
  looks up env, then store, then primitives, then the module registry, so a
  lambda parameter, a store write to key yin/def, or an aliased call such as
  (setter 'yin/def 0) can change what a later (yin/def 'x 1) does. The M2
  linker recognizes (yin/def 'x 5) as a definition to discharge
  use-before-definition obligations, so shadowing makes that unsound.
- Codex gate rounds 1-4 each found a new shadowing route (visible rebind,
  direct :vm/store-put of the key, computed key, aliased primitive call). A
  fifth patch is another guess.
- My pushback (not a conclusion): it must hold in every engine that runs the
  program (semantic, register, stack, AST walker; CLJ/CLJS/CLJD; foreign
  engines under UCF), so it is a VM/UCF conformance rule, not a linker
  patch. It needs a decision on WHICH names (only yin/def, or every primitive
  the UCF profiles). The AST already has :vm/store-put with a direct :key slot
  that name resolution never touches: that is the shape a special form takes.
- I verified only engine.cljc resolve-var; I did NOT check whether the other
  VMs share it, or how the macro expander emits yin/def.

## Read first
- docs/design/datom.world.md
- src/cljc/yin/vm/engine.cljc (resolve-var at 54; handle-effect), src/cljc/yin/vm.cljc (:vm/store-put near 807), ast_walker.cljc (~232 evaluation order)
- docs/design/yin.vm.linker.md sections 4.1, 4.2 and the store-isolation clause near line 1352
- docs/design/yin.vm.universal-continuation-format.md (primitive profiles, ~597 and 7.5.2)
- the M2 gate cycle: collab/1790336700000-architect-linker-m2-fixes-r4-gate.gpt-6-sol.findings.md and the round 1-3 findings/reports beside it
- the uncommitted M2 work in /Users/sto/workspace/datomworld-ucf-phase2 (read the files directly with Read; git diff is not available across worktrees): src/cljc/yin/vm/linker.cljc (guard: tree-definition-occurrences, computed-yin-def-write?, tree-yin-def-application-query) and test/yin/vm/linker_test.cljc

## What to produce

1. The recommended mechanism for "unshadowable by construction" (special form
   / dedicated AST node recognized before name lookup; reserved-name refusal
   at every write and binder; or other) and why, against the alternatives.
2. Scope: yin/def only, or a defined set of core forms; and which engines and
   hosts must enforce it, including how a foreign UCF engine is required to.
3. What changes: the macro expander, each VM, the UCF spec text, and the
   linker spec. State what happens to code the M2 fix rounds added (which of
   the round 1-4 shadowing guards become unnecessary and can be deleted).
4. Whether the linker's yin/def recognition then becomes sound by
   construction, and any residual case that still needs a static guard.
5. Sequencing: does this land BEFORE the M2 commit (so the guard is never
   committed) or AFTER (commit M2 as is, then replace), and the risk of each.
6. Completion criteria as tests, per host (JVM, Node, Dart). Do not assume
   the current M2 guard is worth keeping.
Distinguish defects from deferred work; flag anything that contradicts a
spec section and say which side should change.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
