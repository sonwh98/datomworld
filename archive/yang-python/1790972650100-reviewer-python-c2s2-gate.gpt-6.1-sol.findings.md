I’ll review the brief, architecture rulings, and uncommitted delta, then trace the generator state transitions and closure boundaries. I’ll use the orchestrator’s lane results as validation evidence and won’t edit files.


I’m using the code-review-pr skill for the review. The tracked diff matches the reported three files and +475/-5; there is also an untracked `C2-S2-BRIEF.md` copy of the brief. I’ll keep it outside the code review scope.


The crossing and handler-stack code follows the recorded design, and the new tests cover re-entry, nested cleanup, and suspension during unwinding. I found a possible validation bug in `throw`: it may call a class constructor before checking whether the class derives from `BaseException`. I’m checking that against CPython 3.9.6 and a focused reproduction.


The focused reproduction confirms the bug on all four JVM evaluators: `it.throw(C, 7)` runs the non-exception class’s constructor and prints `[7]`; CPython rejects it without running the constructor and prints `[]`. The added-line check also found 11 new lines over the brief’s 80-column limit. ASCII and `git diff --check` pass.


Completed-GMT: 2026-10-02 20:27:01 GMT
Completed-Local: 2026-10-03 03:27:01 Asia/Ho_Chi_Minh

Two changes are required before sign-off.

1. **P1 — `throw` executes invalid exception constructors.** In [prelude.cljc:346](/Users/sto/workspace/datomworld-py-c2gen1/src/cljc/yang/python/antlr/prelude.cljc:346), any class enters normalization; line 350 calls its constructor before `py/as-exception` validates `BaseException` ancestry. Consequently, `it.throw(C, 7)` invokes `C.__init__` even when `C` is not an exception class. Constructor side effects or exceptions become observable during an invalid call.

   A focused reproduction whose constructor appends `7` to a log produced `[7]` on all four JVM evaluators; CPython produced `[]`. [CPython 3.9.6’s implementation](https://github.com/python/cpython/blob/v3.9.6/Objects/genobject.c#L456-L482) checks exception-class eligibility before normalization. Validate ancestry before calling `typ`, and add a regression asserting that rejection neither invokes the constructor nor advances the generator.

2. **P2 — Eleven new lines exceed the required 80 columns.** Locations:
   - `src/cljc/yang/python/antlr/prelude.cljc`: 338, 340, 350, 353, 359, 362, 1779.
   - `test/yang/python/antlr/e2e_c2_test.clj`: 160, 354.
   - `test/yang/python/antlr/prelude_parity_test.cljc`: 244, 266.

   The longest is 97 columns at [prelude.cljc:350](/Users/sto/workspace/datomworld-py-c2gen1/src/cljc/yang/python/antlr/prelude.cljc:350). Wrap these lines to satisfy the implementing brief.

The remaining reviewed architecture is consistent with the rulings: explicit crossings restore handler contexts ([prelude.cljc:235](/Users/sto/workspace/datomworld-py-c2gen1/src/cljc/yang/python/antlr/prelude.cljc:235), [prelude.cljc:286](/Users/sto/workspace/datomworld-py-c2gen1/src/cljc/yang/python/antlr/prelude.cljc:286)); method lookup returns bound-method data without inspecting host closure payloads ([prelude.cljc:368](/Users/sto/workspace/datomworld-py-c2gen1/src/cljc/yang/python/antlr/prelude.cljc:368)). Nested cleanup and suspension during unwinding have coverage ([e2e_c2_test.clj:457](/Users/sto/workspace/datomworld-py-c2gen1/test/yang/python/antlr/e2e_c2_test.clj:457)). Close-on-GC is explicitly deferred ([yang.antlr.md:1808](/Users/sto/workspace/datomworld-py-c2gen1/docs/design/yang.antlr.md:1808)).

Accepted the supplied green JVM, Node, and CLJD lane evidence. The portable parity test runs all four evaluators. ASCII and `git diff --check` passed; additions are float-free. Tracked scope remains three files, +475/-5; an untracked brief copy also exists. No files were edited.

Verdict: REQUEST CHANGES
Sign-off: DENIED