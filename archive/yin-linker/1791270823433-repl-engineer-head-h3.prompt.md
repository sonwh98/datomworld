Created-GMT: 2026-10-06 07:13:43 GMT
Created-Local: 2026-10-06 14:13:43 +07
Coding-Agent: claude
Session-ID: 8b4bda87-5e50-47a0-b0c9-391dba6d3ca7

# Task: head-h3

Role: REPL and Host Integration Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-06 14:13:43 +07 | Status: active | Rationale: normal routing (engineers on opus); an Architect signs the work off and a different-family reviewer (gpt-6.1-sol) gates it; the owner authorized commit, merge and push on Architect sign-off

Implement slice **H3 (the REPL)** of the published head trace in
/Users/sto/workspace/datomworld/.claude/worktrees/head-h3 (a git worktree of current
master, which already contains H0, H1, H2 and the `link.cljc` kind-conflict fix:
`src/cljc/yin/vm/linker/head.cljc` (trace, `judge`, the follower),
`src/cljc/yin/vm/linker/head/ws.cljc` (`serve`, `serve-step`, `dial`, `dial-step`,
`handle`, `close!`, loopback only, positive bind port), name resolution in
`dao.stream.remote` and `ws-project`). Do not weaken any of them.

## Read first

- docs/design/yin.vm.linker.dht.head.md: ALL of it that H3 touches, in particular
  5.4 (publisher behaviour: when `deposit!` is called), 5.5 (reader behaviour,
  the persistence ordering confirm, persist, install), 5.6 (when `(require 'alib)`
  refreshes), 5.7 (persistence and relink: `heads.edn`, the move line, `(reset)`),
  5.8 (command line and saved state), 5.9, 5.10 (portability), section 6 (the
  `yin.head` host module and the plain Clojure API rows), section 7 (limits and
  defaults), section 8.3 (what the first cross-machine transport is and is not),
  10 (failure modes), and **section 11 slice H3 (your acceptance criteria: implement
  EVERY bullet)**.
- docs/design/yin.vm.linker.dht.md (link attempt order, section 9 response rows incl.
  the kind-conflict row), src/cljc/yin/vm/docs/yin.repl.md (the user-facing document
  you must update), src/cljc/yin/repl/main.cljc, state.cljc, dht.cljc, query.cljc,
  link.cljc, src/cljc/yin/repl.cljc, test/yin/repl/dht_process_test.clj and the tests
  beside each file.
- The H2 Architect sign-off notes you MUST honour
  (`collab/1791239192386-architect-head-link-h2-signoff.claude-fable-5-1.stdout.log`
  in `.claude/worktrees/head-link`, or summarised here):
  1. `head.ws/dial` composes no resend policy: pass `:dao.stream.remote/resend-after`
     or own the liveness in the host, so a named request lost on the wire does not
     leave the dial `:resolving` forever.
  2. `dial` does not check its host: H3 decides what is dialed and MUST keep that to
     the token's loopback address (section 8: off-loopback is out of scope).
  3. An attached dial never becomes `:lost` itself; loss shows as the follower's
     `:source-lost`. There is no `head.ws/stop` (stopping is the host's unbind on
     `:listener`). H3 must `close!` the old dial before composing the next.
  4. A late `:bind-failed` replaces the server value with the refusal and drops
     `:listener`: fine only if a failed bind holds no resources on any host. Check
     each host (JVM http-kit, Node, Dart) and say so.
  5. The Node and Dart bullets of H2 rest on an in-process fake network; H3's end to
     end case must cross a REAL listener on the JVM and on Node.

## Owner questions (design section 13): defaults to implement now

The owner has not answered the five questions; none blocks the core. Implement the
design's recommendations and keep each behaviour easy to change: (1) no relink in a
live session: the move line plus `(reset)`; (2) a bare `q` after `dht join` answers
the reader's own index, the publisher's facts via `(dao.space.dht/q <manifest> ...)`
after an explicit `load-index`; (3) every HEAD move deposits a head; (4) WebSocket,
loopback only; (5) safety without a progress guarantee. Note in your report any place
where one of these is baked in rather than a small change.

## Scope and process rules

- Work only in the files H3 lists (design section 11): `src/cljc/yin/repl/dht.cljc`,
  `main.cljc`, `state.cljc`, `query.cljc` (the `yin.head` host module: it holds NO
  rule, no fold, verification or file write), `link.cljc`, `src/cljc/yin/repl.cljc`
  (the first-contact pending), `test/yin/repl/dht_process_test.clj`, the tests beside
  each, and the `yin.repl` documents (`src/cljc/yin/vm/docs/yin.repl.md`, and the
  `--help` text). If a dependency needs another file, stop and ask. Do not edit the
  head design document except where H3's section says an amendment is carried; if a
  sentence there must change, say so in your report. `docs/design/dao.stream.md` is
  not amended.
- **No git commands.** No formatter or cljstyle. Verify in the **foreground** with
  focused JVM runs (`clj -M:test -n <namespaces>`; do NOT use `clj -M:test -e`, it
  hangs) and `clj -M:kondo --lint <files>`, with assertion counts. No background
  processes (the end to end JVM process test starts its own child processes: that is
  its own foreground work; build `target/yin-repl.js` the way the repo documents in
  `docs/agents/build-n-test.md` if the Node leg needs it). **No full Node or Dart
  lane runs**; the orchestrator runs `bb test` at landing and reports host failures
  back. Portable `.cljc`; ClojureDart traps: `#?(:cljd nil :clj ...)` with `:cljd`
  FIRST for JVM-only code; no `0.0` literals in portable tests; EDN with no
  whitespace before a closer (`heads.edn` is read on Dart); `(- x)` is `0 - x`; no
  duplicate `_` protocol parameters; no `#'ns/private` across namespaces; `for` over
  more than 32 elements can hand the body nil on Dart (use `keep`/`mapv`).
- Nothing may throw on a value read from a file, a token, a flag or the wire:
  malformed `heads.edn`, tokens and flags are refusals as data, tested.
- Keep the user-visible texts short and in the existing style of `yin.repl.md`.
  No backward compatibility is needed (dev-only repo): clean breaks over shims.
- If the design contradicts the code or itself, STOP that item and say so with
  file:line; do not silently diverge.

## If you cannot finish everything

Land a coherent prefix in this order and report exactly what remains:
(1) token parsing and printing (`yin:<host:port>/<principal>`, a third part refused with
the pin message), `heads.edn` read and write (all four bad-file cases), `--dht-follow`,
the saved flags, `dht join` saving `--dht-follow`; (2) the `yin.head` host module, the
publisher's `deposit!` calls and the board endpoint, the follower step in the REPL
loop, the first-contact pending and `(abandon)`; (3) the write-precedes-install seam
and the move line; (4) the end to end process test and the docs.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 8b4bda87-5e50-47a0-b0c9-391dba6d3ca7

Then report: changed files; the exact commands and outcomes with assertion counts;
every H3 criterion with done / partly / not done and the test that pins it; the
flags, token and `heads.edn` shapes you chose; how each H2-note above was honoured;
where an owner-question default is baked in; unresolved concerns; anything incomplete.
Do not claim edits or tests that did not occur.
