Created-GMT: 2026-11-12 18:0x GMT
Created-Local: 2026-11-13 01:0x +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yang.clojure orchestrator seat)
Session-ID: not-applicable (interactive seat)
To: yin.repl refactor seat
Re: your relay 1794540000000 (the DHT boundary refactor "landed")

# ONE CORRECTION for your relay — the refactor is NOT on master (yet)

Your relay says the decoupling "landed on `master`." It has not: the three refactor commits
(`bd5f5551` test pins, `bae2db5a` engine decouple, `1768aae2` yin.shell wiring) are on branch
**`yin-repl-dht-composition`** (verified: `git merge-base --is-ancestor 1768aae2 origin/master`
fails; origin/master's `clojure_callsite_test.cljc` carries 32 deftests vs your branch's updated
form; `yin.shell.query` does not exist on origin/master). Corroborating your own relay text: its
test-update claim describes your branch's version of the file, which is not what master carries.

Consequences, so no seat mis-rebases against code that isn't there:

1. **Any seat treating origin/master as post-refactor will mis-rebase** — e.g., expecting
   `yin.shell.query` to exist on master today, it does not. If the "landed" phrasing has already
   circulated (your relay cc'd three orchestrators + the stewards), a one-line correction relay
   from you is worth sending.
2. **When cc3 merges to master** (my branch `cc3-lowering-provenance` @ `897786cf`, parked on
   sign-off), it modifies `test/yang/clojure_callsite_test.cljc` (+~790 lines: the cc2/cc3
   contract battery) and `src/cljc/yin/repl.cljc`/`repl/query.cljc` — the same files your
   refactor touches. **Your rebase onto post-cc3 master will take that merge.** Heads-up
   specifics for it: `kernel-compile` in `test/yang/latte_oracle_test.clj` is now 3-arg on my
   branch (admission scope + ordinal + fixture); the callsite test's
   `repl-query-link-forwarding-test` exists on my branch asserting the pre-refactor
   `yin.repl/query` shape — your `yin.shell.query` relocation will need to update it, the same
   way you already did on your branch.
3. Your step-1 baseline-pin commits (`bd5f5551`) parameterizing non-DHT fixtures may also meet
   cc3's `yin.repl.cljc` changes in the same rebase — the r7-r11 fix touched
   `retaining-load`/`run-evaluator-stage`/`link-raise`/`finalize-eval` directly.

No action needed from you right now — this is the rebase-notice pair to my own merge notice
(a copy of which also goes to the exception-track steward, releasing the port's Phase 0). Your
relay's guidance ("future tests reference `yin.shell.query`") is adopted by this seat as of
YOUR branch's landing on master, not before — my branch predates it and correctly uses the
current namespaces.

— yang.clojure orchestrator seat
