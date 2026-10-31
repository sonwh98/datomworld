Completed-GMT: 2026-10-08 22:30:00 GMT
Coding-Agent: Claude (claude-sonnet-5-5), adversarial reviewer

# D16-final adversarial review

**Ruling: CHANGES_REQUESTED.** The tests are sound. The request is for verification the gate itself requires, plus three small evidence gaps. No production rework is needed.

## 1. Containment: pass
- `git status` and `git diff --stat` show two modified files, both under `test/yin/vm/ucf/`. Nothing under `src/` and no `compose.cljc` change.
- The `compose_test.cljc` change is a defaulted `:store` option on `exclusive-world`. The default is unchanged, so inherited rows are unaffected.
- The new rows call only `compose/open!`, `step`, `control-step`, `abort`, `crash-*`, `rebuilt-*` and the journal/reply readers. I found no direct holder-namespace calls. Reading `authority/projection` for assertions is observation, not bypass.

## 2. Reproduction
I ran `clojure -M:test -n yin.vm.ucf.compose-test -n yin.vm.ucf.compose-rows-test`. Result: 54 tests, 934 assertions, 0 failures, 0 errors. That agrees with the implementer's 97/1288 once `yin.repl.main-test` is added. I did not run Node, Dart, the full fast lane, kondo or cljstyle.

## 3. Rows against 14.2.4
- **Row 1: adequate.** Awaiting then refused, with `not-holder` naming h1. The spec's wakeable-writer, direct-resume and install-child variants remain open, as the implementer says.
- **Row 2: mostly adequate.**
  - The `full` / `refused` / `transport-error` / `ok` matrix is a good reading of "only before a proven unadmitted offer".
  - The restart refusal is also covered.
  - **Gap A:** the spec says "crash/reopen after each phase". Only one crash point is exercised, after the first offer attempts. The unknown-acceptance cut between the offer's intent and attempt records is acknowledged as open.
  - **Gap B:** "duplicate evidence creates no second grant" is not asserted in row 2.
- **Row 3: adequate.** Both orderings are covered.
- **Row 4: adequate for same-host.** Both cuts, a stable id and a tally spanning both tenures.
- **Row 5: partial against the literal spec.**
  - The spec's "changed gap/intent" is exercised through a rewritten live value, not through an evicted kept cursor. Eviction rows pre-exist, but the combination does not.
  - The "unreachable" argument (the write-ahead input record forbids divergence) is plausible for recovered inputs and is an Architect reading. It is fine to land as pinned behavior, but it should be recorded as a ruling, not left as a test comment.
  - The quarantine check is `(not (true? ...))`. It also passes when the lookup path returns nil. Use `(false? ...)` or compare against an explicit `nil` / `false` after confirming the key exists, so a wrong path cannot pass.
- **Row 6: a unit variant only.** It is honest about not being a partition proof. Stage E owns the partition half.
- **Row 7:** the two formerly red completion cuts are now green on `4d46b798`. This is credible because the policy-reclaim repair is the base commit. The lost-authority and failure-lower rows are good: they pin exact edges and epochs, and they distinguish the after-durability path (`[:side]`, epoch 1) from the before-durability path (`[:side :side]`, epoch 2).
- **Row 8:** the closed outcome maps, cross-target conflict, transport-error cut and closed-ancestor `:foreign-op-id` rows are strong. Forged outcomes, overflow, attachment failure and store isolation remain open. That is acceptable only if they are assigned explicitly to Stage E.

## 4. The implementer's concerns
- **(a) Interrupted freeze stalls permanently at `:incomplete-preparation`.** Safe, with no re-mint, offer or IO, but not live. This is an Architect ruling, not a test defect. The new rows pin it. Before landing, file a short ruling or a tracked open question, so the pinned shape is not read as endorsed. A later liveness fix would then have to change these rows deliberately.
- **(b) Row 5 write-ahead.** See the row 5 notes above.
- **(c) Abort legality.** The matrix is coherent: `full` and `refused` are provably unadmitted, `transport-error` and `ok` are not. One residual risk: `full` and `refused` are treated as proven-unadmitted by carrier semantics. If a real carrier can answer `full` after partial acceptance, the rule is wrong. Document the carrier contract this relies on.
- **(d) The two red cuts.** Confirmed green by my run, which includes them.

## 5. Portability and hygiene
- The diff has no host-specific interop. The catch uses `#?(:cljd Object :clj Throwable :cljs :default)`, and `first-index` replaces the JVM-only `.indexOf`.
- These are `.cljc` files, so the Node and Dart behavior is unproven. The known CLJD traps (chunked `for`, multi-key `assoc` on nil, whitespace before closers) do not appear in the diff. Only a run can settle this.
- The lint and format results are second-hand ("owner reports clean"). I did not run them.

## 6. Gate completeness
The compose-driven half of Stage D is substantively evidenced on the JVM. It is not yet landable under the gate as written, because:
1. No Node or Dart run exists, and no full JVM fast-lane summary exists (the run was SIGKILLed).
2. `clj -M:kondo` and cljstyle output on the changed files was not observed by the author or by me.
3. The open items in section 3 and the freeze-liveness question need an explicit disposition, either deferred to Stage E by name or closed.

## Required for READY_TO_LAND
1. Run `bb test` (or the lane set `build-n-test.md` names) once for the slice: the full JVM fast lane, Node (`npm ci` first in this worktree) and Dart. Record the counts.
2. Run kondo and cljstyle on the two changed test files and record the result.
3. Tighten the row 5 quarantine assertion, and add the row 2 duplicate-evidence/no-second-grant check or defer it by name.
4. Record the Architect disposition for the freeze-liveness stall and the row 5 write-ahead reading, and list the Stage E carry-overs (row 8 forgery/overflow/store isolation, row 6 partition, row 2 intent-to-attempt cut, row 1 variants).

If items 1 and 2 are green, re-review is a skim. I expect `READY_TO_LAND` then.
