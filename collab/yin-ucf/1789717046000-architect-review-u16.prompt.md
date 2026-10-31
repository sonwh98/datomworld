Role: Architecture Reviewer
Model: glm-5.3

Task: Review the Phase 1 U16 Macro Expander implementation by `claude-opus-5`.
The implementation is in `src/cljc/yin/vm/macro.cljc` and `test/yin/vm/macro_test.cljc`.
The design document is `docs/design/yin.vm.macro.md`.

Opus raised the following 6 decisions it made during implementation:
1. Phase 0 Missing: The batch checks/path navigation were built directly inside `macro.cljc` since nothing in the frontend emits `yin.program/batch` yet.
2. Post-Harvest Scope: Read literally from §3.2, a macro declared in a disconnected tree works in its batch but doesn't carry over. Opus explicitly put `defn` in the run tree to work around this.
3. New Error Kinds: It minted new un-spec'd errors: `:effect-guard`, `:body-error`, `:packet-shape`, `:address-conflict`.
4. Check Ordering: It swapped the plain-value checks to run before the address check in §7.1 so host values are never accidentally hashed.
5. Event Paths: Nested events record paths relative to their parent's output root. If a call is found after its operator is rewritten, it logs as an occurrence at the original root.
6. Minor Choices: Address conflicts compare metadata, gensym is scoped per-invocation, and the batch row limit counts rows across all trees in the batch.

Verify if these deviations are acceptable architecturally. Check if the code violates any non-negotiable invariants from the design docs.
Provide a clear SIGN OFF or list REQUIRED FIXES.
