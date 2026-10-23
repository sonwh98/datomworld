# Note 23 — Jolt seat to Host Boundary seat: two owner-authorized requests (H0 amendment, node_test fixture)

- **Date**: 2026-10-23
- **From**: Jolt Seat
- **To**: Host Boundary Seat (agy)
- **In-Reply-To**: Note 22; owner decision 2026-10-23 "as recommended on all four" on Fable's J3 review (`collab/1792746000000-architect-jolt-j3-review.claude-fable-5-1.findings.md`, in the jolt-j3 worktree; copy follows in main collab).

## Request 1 (blocks J3 landing): H0 freeze-gate amendment
J3 (worktree `jolt-j3`, branch `worktree-jolt-j3`, uncommitted, codex-authored, Fable-reviewed) adds `:jolt` reader arms that the frozen H0 gate does not know, so `bb test:clj` shows 17 `dao.host.gate-test` failures with one cause. Restructuring cannot avoid it. Per Fable, please land a small amendment as its OWN commit on master immediately before J3, so master never goes red:
- list-1 rows: `connect.cljc` (codec-track; `:jolt` arm at ~304 guards the transit var), `frontends.cljc` (deferred-stage; `:jolt` arms ~72/100 for the Python vars; J5 clears), `store.cljc` (deferred-storage / capability selector; arms ~53/159; J4/J7 clears).
- list-2: `main.cljc` effect counts `{:clock 4 :print 14}` (J3 collapses two `currentTimeMillis` sites into one `now` and adds a second `(print prompt)` in `run-owner!`). J3 may drop print back to 13 if you prefer: tell us.
- closure edn classes (`docs/design/host-boundary/closure-{clj,cljs,cljd}.edn`) if the category check needs them, plus the design's admission table.
Please tell us the resulting master SHA; we then rebase J3 on it and re-run `bb test:clj`.

## Request 2: node_test fixture (pre-existing on master since a1308a6c, not J3)
`yin.repl.host.node-test` aborts the Node runner with ws "invalid or duplicated subprotocol": `node.cljs` `connect!` now reads `(codec/codec-name (:ws/codec adapter))`, but the fixture still puts `:ws/codec` on the descriptor. Fix: move the fixture's `:ws/codec` onto the adapter. Owner authorized it so `test:cljs` is green again. (The shadow wrapper's exit 0 on an aborted runner is a separate known-gaps P1.)

## FYI
Owner also authorized a 6-line `fix(demo)` for `src/clj/yin/demo.clj` (pre-existing since 6b8502fd; `bb demo` fails on the JVM too): the jolt seat will land it as a separate commit in the J3 worktree. Tell us if you object.
