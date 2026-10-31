## Goal Description
Perform an adversarial review of `docs/design/dao.stream.v1-retirement.implementation-plan.md` to verify its claims against the codebase, focusing on high-risk design decisions (D4, D5, D7), dead code assertions, census completeness, parallelism soundness (U3/U4), and grep sweep reliability (Phase 0).

## Findings

1. **[Severe] Phase 0's grep sweeps are insufficient:** 
   The plan correctly identifies that `flutter.cljd` and `web.cljs` are missed by a namespace grep because they call `terminal/bind-stream!` and `put-frame!` without requiring `dao.stream`. However, Phase 0's second grep sweep ("v1 mechanisms by name") explicitly searches for `register-(reader|writer)-waiter!`, which catches the implementation inside `terminal.cljc`, but it *omits* `bind-stream!` and `put-frame!`. Consequently, if any new file introduces a `bind-stream!` call before U3 lands, the Phase 0 sweeps will silently miss it.

2. **[Verified] D4 (Terminal) and D5 (gui.event):** 
   - **D4:** The proposed binding successfully drops waiter registration. It relies on the host's ticker (e.g., `Timer.periodic`) calling `step`, strictly adhering to v2's polling model (no callbacks). It does not sneak the waiter mechanism back in.
   - **D5:** The claim that `dao.gui.event` was already written to the v2 discipline holds completely. `advance` handles `{:result :full}` by returning `:parked` and `ds/next` `:blocked` by returning `:blocked` without registering any callbacks.

3. **[Verified] Dead Code (`yin.io`, `agent.tools`, `ws-demo`):** 
   A global regex search across `src/`, `test/`, and config files confirms these namespaces have strictly zero live consumers outside of their own tests or dead registries. Deleting them removes no live capabilities.

4. **[Verified] Completeness of the census:** 
   A manual spot-check of `dao.stream` requires across the tree (excluding `v2`) confirms that every matching file is either explicitly listed in the migration tables or is part of the v1 implementation itself (slated for deletion in U6).

5. **[Verified] Dependency Order and Parallelism (U3/U4):** 
   U3 and U4 edit completely disjoint regions of `artifact.cljs` and `artifact.cljd`. U3 only modifies the `frame-stream` instantiation, while U4 modifies `runtime-input-stream`, `signal-stream`, `output-streams`, and the read loop. They commute perfectly without merge conflicts.

6. **[Minor] D7 Wire-keyword question:** 
   A "clean break" (renaming wire keywords) is safe under the assumption that nothing outside this repository connects to the REPL. Grep sweeps confirm no cross-repo compatibility promises or external editor plugins are supported in the repo. If the assumption holds, the clean break is sound. (Keeping the subprotocol string `"dao.stream.transit-json"` frozen would be the safer standard fallback if local, uncommitted scripts rely on it).

## Verdict
**NOT READY FOR SIGN-OFF.** 

**What must change first:** 
Phase 0's mechanism grep sweep must be updated to include `bind-stream!|put-frame!` to ensure it catches any newly introduced terminal bindings before U3 lands. Once this change is made, the plan is structurally sound, its claims are verified, and it is ready for execution.
