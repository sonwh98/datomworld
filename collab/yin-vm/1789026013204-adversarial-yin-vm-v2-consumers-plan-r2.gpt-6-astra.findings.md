Completed-GMT: 2026-09-10 07:41:51 GMT
Coding-Agent: codex
Session-ID: 01a089fa-08cc-77a0-84a2-479c45507441
# Task: adversarial review r2 confirmation — two new findings
Role: Adversarial Review

I’ll re-read the revised plan and check the six fixes, with particular attention to the language port and Flutter startup checks. I’ll keep this read-only and focused on readiness to implement.

The six fixes are substantially in place, and the Python/PHP port fits the existing AST-to-datoms path. I found one remaining omission: the demo shell has three `pipeline/...` references in its toolbar beyond the render branches named in the plan. Dropping the require would leave those references unresolved.

**Request changes.** The six r1 fixes are substantially addressed, but two concrete omissions remain.

1. **[P1] The demo migration leaves references to the deleted pipeline namespace.**  
   [Plan:109](/Users/sto/workspace/datomworld/docs/design/yin.vm-consumers.implementation-plan.md:109) removes the require and main render branch, but [demo.cljs:277](/Users/sto/workspace/datomworld/src/cljs/datomworld/demo.cljs:277) also contains a toolbar referencing `pipeline/show-explainer-video!`, `pipeline/layout-controls`, and `pipeline/app-state`. Those references become unresolved even though their conditional branch is unreachable. Explicitly remove that toolbar block or migrate its retained features.

2. **[P2] The Flutter startup smoke references an obsolete entry point.**  
   [Plan:190](/Users/sto/workspace/datomworld/docs/design/yin.vm-consumers.implementation-plan.md:190) adopts [dao_gui.md:41](/Users/sto/workspace/datomworld/src/cljd/datomworld/demo/dao_gui.md:41), whose command compiles nonexistent `datomworld.main`. The actual entry point is `datomworld.demo.main`; it opens a picker and calls `dao-gui/start!` only when **“dao.gui Prototype”** is selected ([main.cljd:40](/Users/sto/workspace/datomworld/src/cljd/datomworld/demo/main.cljd:40)). Specify the current entry point, selecting that demo, and observing “listening.” The keyword sweep, contract test, and actual widget startup together sufficiently cover the reported explicit-argument defect; merely launching the picker does not.

Confirmation of the requested fixes:

- **Runtime regression suite:** correctly moved to migration; dropping its three unused requires resolves r1.
- **Flutter argument:** `(create-state {})` correctly takes the new default. The startup procedure needs the correction above.
- **Aliases and launcher:** all five aliases are correctly named, the Dart launcher is deleted, and the sweep now covers both.
- **Python/PHP port:** sound. Both compilers return AST maps accepted by `vm/ast->datoms`; the v2 REPL already uses the same language dispatch. Technically these are `:type` AST maps, not yet `:yin/*` datoms.
- **Hash aliases:** the proposed mappings preserve all three old URLs. One prose correction: opening an old URL retains its old hash; `demo->hash` does not automatically canonicalize it on arrival.
- **Boundary:** the VM/R4 gate and global stream-rename gate are now properly separated.

Read-only confirmation pass; no builds run or files edited.
