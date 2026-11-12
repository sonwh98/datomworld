Created-GMT: 2026-11-11 09:35:00 GMT
Created-Local: 2026-11-11 16:35:00 +07 (+0700)
Coding-Agent: deepseek
Session-ID: 8fb2c509-b690-40dd-acad-9c6c1962ad42
# Task: engineer — land R3 WITHOUT the VM seam (owner ruled NO)
Role: Engineer
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-11-11 16:35 +0700 | Status: active | Rationale: knows this code best (refuted the false diagnosis, ran the pins, knows the paths); the change is surgical and you have the context.

## The owner's decision (recorded in 1794372000000, read the CONSEQUENCES section)

**NO** — yin.vm stays untouched. Land the raw-payload half; protocol retention reverts to the
witness mechanism; the replay/mutation weakness becomes a documented limitation.

## The change (branch `exception-r3-impl`, worktree /Users/sto/workspace/datomworld-r3-orch, base fbd8da0f)

1. **Revert `src/cljc/yin/vm/engine.cljc` and `src/cljc/yin/vm/module.cljc` to their c9c5533c
   state** (`git checkout c9c5533c -- src/cljc/yin/vm/engine.cljc src/cljc/yin/vm/module.cljc`) —
   removing the `:retained` mark, `:cell/new-retained`, `cell/retained?`, and the `:cell/set!`
   refusal. Verify `git diff c9c5533c..HEAD -- src/cljc/yin/vm/` is EMPTY when done.
2. **In `src/cljc/yang/clojure.cljc`**: keep the RAW ordinary-payload delivery (the `(A)` split's
   `:thrown`/unqualified arms and the raw delivery at the boundary) EXACTLY as at fbd8da0f. Change
   ONLY the protocol-retention carrier: `condition-wrapper-form` reverts to the c9c5533c
   witness-based version (contents `[visible condition witness]`, witness = compiler-minted
   closure binding `{visible, condition, dsg__call_token}`; `condition-retained-form` =
   the c9c5533c complete-contents re-derivation). Do NOT reintroduce the per-call-site
   visible/predicate routers — the raw-payload half makes them unnecessary. The minter publishes
   `cell/new` (guest-admissible) as the carrier primitive again, since the witness — not heap
   identity — is what authenticates.
3. **`test/yang/clojure_test.clj`**: keep `test-exception-r3-astra-probes` but EDIT the two
   forged-retention pins that asserted VM behavior (`cell/retained?` truth, `:retained-cell`
   refusal). They now assert the WITNESS semantics: a guest copy of the contents in a fresh cell
   decodes as an ordinary payload (fresh `:yang.call/thrown` — R5's expected semantics), and
   `cell/set!` on the caught wrapper SUCCEEDS as an ordinary cell write (documented limitation).
   Keep every other probe (nested `=`, map-key, `count`, `assoc`, nil-truthiness, aliased
   predicate, `(= e original)` with a cell payload, saved retain→rethrow byte-identical).

## Known limitation to assert, not hide

Add one pin documenting the accepted trade: a guest that catches a protocol wrapper and mutates it
via `cell/set!` before rethrowing forwards the SUBSTITUTED condition — this is the documented
limitation of the witness mechanism under the owner's NO (ruling consequence 2). The pin names the
limitation in a comment so R4's proof finds it.

## Verification

`clojure -M:test -n yang.clojure-test` (focused, allowed) + `clj -M:kondo --lint` on all four
files. **NEVER run `yang.call.exception-contract-test` locally.** Commit + push
`exception-r3-impl`. Check `git status` before committing: only the four files above.

End with Completed-GMT/Completed-Local and: **NO-VM LANDING DONE: <sha>** or **BLOCKED: <what>**.
