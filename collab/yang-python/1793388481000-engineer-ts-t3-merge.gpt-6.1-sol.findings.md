I’ll read the repository rules and constructor tests, merge `origin/master`, and resolve the three conflicts. Then I’ll run the JVM quick tier and kondo with explicit timeouts, leaving the merge uncommitted for review.

---

The merge produced three conflict blocks, all in `prelude.cljc`. I’ll keep master’s `js/call` routing and move T3’s class-call rejection and `new.target` arity handling into the shared entry on `make-function-nt`. That preserves both behaviors. The other blocks will retain T3’s kind-based constructor check and the combined definition list.

---

The resolution is in place, and kondo reports 0 errors and 0 warnings across the TypeScript source/tests and `yang.core.call-test`. The first JVM command selected zero tests because I supplied the regex to the wrong runner option. I’m correcting the selector and will verify the namespace list and counts. I’ll also run the existing constructor and shared-call tests that the quick tier excludes.

---

The JVM run found two stale address goldens: adding master’s shared-call runtime changes the bundled prelude hash and the program hash that includes it. I’ll update both pins and rerun the quick tier; Node/Dart validation of those pins remains with the orchestrator. The merge-wide whitespace check also flagged three lines inherited unchanged from master; the resolved prelude passes.

---

I verified that `js/call` matches master verbatim, and that `js/is-constructor?` and the combined definition list match T3. The quick run is still active and has reported only the two stale pins so far. The pins are now updated; the next run will validate them.

---

The quick run has passed the module publication checks and reached the lowering tests, with no additional failures reported. Its scope is all TypeScript quick tests plus `yang.core.call-test`. The focused follow-up will cover class-call refusal, `new.target`, bound construction, and shared-call identity and eligibility.

---

The merge has no unresolved conflicts, and both custom files are staged. Kondo is clean at 0 errors and 0 warnings, and cljstyle passes. The quick run is progressing through object parity tests with no further failures reported. Once it finishes, I’ll validate the updated pins and run the focused semantic tests.

---

The quick run has finished object parity and reached the prelude module checks. It remains within the 15-minute timeout, with only the two stale-hash failures reported before the pin update.

---

The runtime code has stayed unchanged during the quick run; the only follow-up edits are the two address pins. I’ll recheck those golden tests alongside the focused constructor and shared-call tests. The run is now in the prelude tests.

---

The quick tier finished: **105 tests, 3,065 assertions, 2 failures, 0 errors**. Both failures were the stale address goldens; no other assertions failed. The eight-test follow-up is now running to verify the new pins and exercise constructor and shared-call behavior on all four JVM guest VMs.

---

The follow-up passed: **8 tests, 512 assertions, 0 failures, 0 errors**. Both updated goldens are verified, along with the constructor, `new.target`, bound-function, receiver, argument-presence, and shared-call eligibility checks. I’m writing the review handoff and confirming that the merge remains uncommitted.

---

Merge resolved and staged on `yang-ts-t3`; **no commit**.

- **Factories / `js/call`:** preserved master’s `yang.call/invoke-with` routing verbatim. T3’s `make-function-nt`, `:nt?`, descriptors, class-call refusal, and `new.target` handling survive through the shared entry.
- **`js/is-constructor?`:** retained T3’s kind-based form verbatim.
- **Definitions:** retained the union, including T3/T4 definitions and master’s shared-call wiring.

Validation, foreground with explicit timeouts:

- JVM quick, TypeScript + core call: **105 tests, 3,065 assertions; 2 stale-golden failures, 0 errors**.
- Re-pinned both goldens. Follow-up covering both pins plus six semantic tests: **8 tests, 512 assertions; 0 failures/errors**. No second complete quick run.
- Kondo: **0 errors, 0 warnings**. Cljstyle passed.

No unresolved semantic ambiguity. Node/Dart golden verification remains with the orchestrator.

[Detailed handoff, including inherited whitespace findings](/Users/sto/workspace/datomworld-ts-t3/collab/1793387762000-engineer-ts-t3-merge.codex.findings.md)
