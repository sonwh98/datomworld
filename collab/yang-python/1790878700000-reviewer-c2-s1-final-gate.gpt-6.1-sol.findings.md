I’ll read the governing design and rulings, then review the uncommitted code and tests. I won’t edit files or run test suites.

The crossing code restores the receiving handler context before each invoke and clears the named caller slots on suspension. I’m now checking scope detection and captured environments, which need a closer look than the slot transitions alone.

I found a scope-classification defect: the yield scan skips entire nested function, lambda, and class nodes. That also skips defaults and class bases, which the lowerer evaluates in the enclosing scope. If one of those contains the enclosing function’s only yield, lowering incorrectly reports “‘yield’ outside function.”

Completed-GMT: 2026-10-01 18:48:57 GMT
Completed-Local: 2026-10-02 01:48:57 +0700

**REQUEST CHANGES — one P2; no P1. Sign-off withheld.**

**P2 — Generator detection skips yields in nested-definition headers.** [lower.cljc:994](/Users/sto/workspace/datomworld-py-c2gen1/src/cljc/yang/python/antlr/lower.cljc:994) uses `contains-rule?` with `scope-rules`, stopping at entire nested function, lambda, and class nodes. Defaults and class bases execute in the enclosing scope; function defaults are evaluated when the definition executes. [Python 3.9 reference](https://docs.python.org/3.9/reference/compound_stmts.html#function-definitions).

For example:

```python
def g():
    def h(x=(yield 1)):
        pass
```

The scan misses `g`’s only yield, so `gen-sym` becomes nil. Defaults subsequently lower with the enclosing context at `lower.cljc:1046`, causing `lower-yield` to reject this valid generator with “‘yield’ outside function.” Lambda defaults and class bases have the same defect.

Make classification scope-aware: inspect headers evaluated in the enclosing scope while excluding nested bodies. Add regression coverage for function/lambda defaults and class bases, including examples where the header contains the only yield.

The remaining review found no additional S1 defects: capture/invoke ordering restores receiving contexts, the boundary survives body escapes, running-state validation preserves state, and throw into a never-run generator closes without capture. All four grammar positions are handled; no new host-type leakage or weakened assertions found. The unsupported-yield row replacement is the only relaxed existing row.

Read-only review completed; no files edited or test suites run. Supplied verification evidence was accepted. Node/CLJD completion remains with the orchestrator.