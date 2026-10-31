Created-GMT: 2026-10-27 00:37:15 GMT
Created-Local: 2026-10-27 03:37:15 +07
Coding-Agent: claude
Session-ID: 59B70221-8A9E-4D0F-91EF-0A9609418224 (resumed)
# Task: dao.star cart — fix round: the seven review findings
Role: DaoStream and Distributed Protocol Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-27 03:37:15 +07 | Status: active | Rationale: same seat; you built the demo under review

The adversarial review returned NOT SIGNED OFF with seven findings. Read
them first:
`/Users/sto/workspace/datomworld/collab/1793036850448-reviewer-dao-star-cart.gpt-6-1-sol.findings.md`

Work in `/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-demo`.
TDD: failing test first for each where the defect is testable.

**1 (HIGH, `edge.clj:44`) — symlink escape.** `startsWith` is lexical and
`NOFOLLOW_LINKS` guards only the final component, so
`public/images/link → /etc` plus `/images/link/passwd` serves an external
file. Fix: resolve the real path and require containment in the asset
root's real path (or reject any symlink component in the relative path).
Test: an intermediate-directory symlink is refused.

**2 (MEDIUM, `catalog.clj:23`) — tempid collisions.** `(hash name)` is not
collision-free (`"Aa"`/`"BB"`). Allocate collision-free tempids per
distinct name (a counter or a zipmap keyed by name). Test: the `"Aa"`/`"BB"`
pair on different items — save, reload from file, both stay distinct;
remove one, the other survives.

**3 (MEDIUM, `view.cljc:91`) — reconnect absent.** RC.7's `retry: "auto"`
does not re-subscribe after a clean EOF; a lease-driven close ends the
subscription for good. Set `retry: 'always'` on the subscription GET per
RC.7's syntax. Keep the existing reconnect test, and add one asserting the
rendered attribute.

**4 (MEDIUM, `store.clj:122`) — snapshot race.** Snapshots run outside the
atom's serialization, so save-then-save can rename an older snapshot last.
Serialize the whole update-and-snapshot operation (e.g. hold a lock, or do
the snapshot inside the `swap!`'s IO-free derivation followed by a
synchronized write keyed to the version). Test: force rename ordering
(inject writers) and reopen the file.

**5 (MEDIUM, `grid_effects.clj:56`) — double-decoded image URLs.** Store
the canonical DECODED path in the catalog (decode at validation), and have
`enc-path` encode once; or preserve one encoding consistently end to end.
Test: an encoded spelling of an existing filename round-trips to a working
image URL.

**6 (MEDIUM, `view.cljc:58`) — CR/LF damage.** Escape JavaScript line
terminators in `js-lit` (CR, LF, U+2028/2029) and ensure SSE framing
tolerates content containing them (encode content so no raw CR reaches the
SSE stream — RC.7 treats CR as a line terminator). Test: LF, CRLF and bare
CR through selection and save.

**7 (MEDIUM, `store.clj:99`) — silent memory-only default.** When
`CATALOG_PATH` is absent, pick a writable local default (e.g.
`./target/cart-catalog.edn`) OR announce memory-only loudly (log + README).
Your call; keep it honest either way, and document in the demo README.

Acceptance: all demo test namespaces green (`clojure -M:test -n ...`, 0
failures), kondo clean on touched files, no files outside your demo
ownership touched. Do not stage, commit, or docker-build.

Report to
`/Users/sto/workspace/datomworld/collab/1793037435251-stream-engineer-dao-star-cart-fixes.claude-opus-5-5.findings.md`
(a denied write goes in your final response). Begin the final response
exactly with:
Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and timezone>
