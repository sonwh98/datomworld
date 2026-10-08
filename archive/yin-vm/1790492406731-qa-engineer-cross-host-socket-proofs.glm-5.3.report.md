# Slice 3 cross-host socket proofs — complete (all three green)

## What was built (all NEW files; nothing under src/, docs/, or any existing
## test was touched)

- **`test/dao/stream/ws_project_peer.cljc`** (`dao.stream.ws-project-peer`) —
  the cross-host peer program, following the `dao.stream.slice-peer` layout
  (whole program, not a fixture; compiles on all three lanes; kondo 0/0,
  cljstyle clean, ASCII, <= 80 cols).  Two roles over one line-oriented
  Transit command/reply protocol on stdin/stdout, one 5 ms ticker per role:
  - **dial role** (argv = the ws descriptor as Transit JSON text): composes
    `project/dial` over this host's connect seam (`ws.jvm`/`ws.node`/
    `ws.dart` per reader conditional), serves "str-2"/"backward" (complete
    history) through its own mirror so the other direction of the same
    connection is served by this end; commands `:attach` (reflection of
    "str-1"), `:probe` (local descriptor outcome + the confirmation's
    `:dao.stream.remote/surface` from the event writer + ticker health),
    `:read :begin/:next` (the child owns its reading cursor; the parent's
    retry is the cadence), `:quit`.
  - **`--serve` role**: the toy's accepting end (endpoint with one handoff
    slot, `project/make-acceptor`, "str-1"/"hello" table) behind this host's
    listener; reports the bound port as a `:serving` reply learned from the
    host's own bind report; `:sessions` reports adoption, `:quit` exits.
- **`test/dao/stream/ws_project_cross_node_test.cljs`** — the Node parent of
  proofs 1 and 2; each proof is one promise chain over small named steps
  (nil short-circuits, terminal step reports and cleans up, `.catch` never
  leaks the child).  Spawns the JVM peer via `clojure -Spath -M:test` + `java
  -cp ... clojure.main -m dao.stream.ws-project-peer`; without a Clojure CLI
  the pair skips loudly (yin R5 pattern).
- **`test/dao/stream/ws_project_cross_jvm_test.clj`** — the JVM parent of
  proof 3; skips loudly when `build/ws-project-peer` is absent, like
  `a-dart-client-attaches-to-this-jvm-server`.

## The three proofs and their verdicts (sequential, solo)

1. **clj -> Node** (`a-node-dialer-reads-a-jvm-server`): the JVM `--serve`
   child serves behind a real http-kit listener; this Node process dials
   through the real `ws` client, attaches the reflection of "str-1", and
   reads the toy end to end — attach ok + attachment id; cursor ask ok with
   the probe's confirmation carrying `#{:reader}`; `next` -> "hello";
   `next` -> the source's own `:end`; and the JVM acceptor reports exactly
   one adopted session with a clean ticker.  **PASS** (headtree Node lane).
2. **Node -> clj** (`a-jvm-dialer-serves-back-over-one-node-connection`):
   this Node process serves behind a real `ws` server; the JVM dial child
   bootstraps (descriptor echo equal), attaches, learns the surface from the
   probe's answer, reads "hello" then `:end` through its reflection; then the
   clj dial reflects back — the parent attaches a reflection of "str-2" over
   the same accepted session and reads "backward" through the child's
   dial-side mirror, then that direction's own `:end`; both directions rode
   one adopted session.  **PASS** (headtree Node lane).
3. **cljd -> clj** (`a-dart-dialer-reads-a-jvm-server`): the JVM parent
   serves; the compiled Dart exe child dials, bootstraps (descriptor echo
   equal), attaches (ok + attachment id), learns `#{:reader}` and identity
   "str-1" from the probe's answer with a nil ticker error, reads
   "hello" then `:end`; the parent's acceptor adopted exactly one session.
   **PASS** — first as a targeted `clojure -M:test -n
   dao.stream.ws-project-cross-jvm-test` run in the main tree (1 test /
   19 assertions / 0 failures / 0 errors), and again inside the headtree
   full JVM lane.

The browser dials-and-reads lane was **not attempted** — no browser harness
exists in any of the three lanes (noted, as instructed).

## Lane counts (sequential, solo; kondo 0/0 and cljstyle clean on the three
## new files; ASCII, <= 80 cols)

Run against a stable **HEAD + these three files** base (see the environment
section for why the main tree could not host full-lane runs):

- JVM `clojure -M:test`: **2,273 tests / 183,335 assertions / 0 failures,
  0 errors** (proof 3 included; one pre-existing loud skip, the yin R5 pair
  without `build/yin-repl-peer`).
- Node `bb test:cljs`'s exact command (`clojure -M:cljs -m
  shadow.cljs.devtools.cli compile slice-peer test`): **2,182 tests /
  49,915 assertions / 0 failures, 0 errors** (proofs 1 and 2 included, no
  skips of mine; the same single pre-existing yin skip).
- Dart, `bb test:cljd`'s exact sequence (build:slice-peer + build:yin-repl-
  peer + `clojure -M:cljd test`): **exit 0, "+2142: All tests passed!"** —
  the peer namespace compiles into the lane's `lib/cljd-out` tree and adds
  no deftests, so the count moves only by the tree's own tests.

## The Dart peer exe

There is no bb task for it (adding one would touch bb.edn, outside this
task's constraints).  The build is the `build:yin-repl-peer` command shape
and is documented in the JVM test's docstring:

    clojure -M:cljd compile dao.stream.ws-project-peer
    dart compile exe lib/cljd-out/dao/stream/ws-project-peer.dart \
      -o build/ws-project-peer

(note the dash filename `ws-project-peer.dart`, and the bb.edn ordering rule
applies: the peer must compile before any cljd test build rewrites
lib/cljd-out).

## Environment limits hit, and how they were handled

- **The main working tree is mid-refactor by a concurrent session and cannot
  host full-lane runs.**  Evidence gathered during this task (all
  uncommitted, none of it mine — `git status` names):
  `src/cljc/yin/vm/linker.cljc` (M) references `answered-text`, defined
  nowhere — it is defined at HEAD; the refactor removed the definition while
  keeping the call.  `dao.await` (unmodified) requires it, so the shadow
  `:test` build dies in `dao.await-test`'s macro pass; five `dao.space.query`
  tests fail on `dao.jing.coordinate/open!`'s changed arity; and during the
  runs `dao/jing/remote*.cljc` were deleted (while `dao/data/
  btree_async_test` still required them) and `dao/jing/content/step.cljc`
  appeared with a live unmatched delimiter.  In-flight files observed over
  the session: linker.cljc, jing.cljc, jing/coordinate.cljc, jing/remote*
  (D), dao/jing/content* (??), btree/storage.cljc, yin/repl/link.cljc,
  yin/repl/require_test.cljc, yin/vm/linker*_test.cljc (M), ucf_test.cljc,
  dao/jing/remote*_test.cljc (D), dao/jing/content*_test.cljc (??).
- **Stable-base workaround**: `git worktree add` is sandbox-blocked here, so
  the lanes were run from a `git archive HEAD` extraction under
  `target/headtree/` plus exactly this task's three files (and the built
  exe, `chmod +x` after copy) and `npm ci` — no git state was touched (no
  commit/stage/checkout/reset/stash; `git archive` is read-only).  A
  classpath shim of HEAD's broken files under `target/shim` was tried first
  and works for compilation, but loses to untracked NEW test files that
  require mid-edit namespaces; the archive route is the honest one.
- **Sandbox**: `bb`, `dart`, `flutter`, `cljstyle`, `git worktree` are not
  directly runnable; every lane command ran through `clojure -M -e` +
  ProcessBuilder with mise's Java 21 and flutter paths (the established
  route in project memory).  Scripts live under `target/` (`run-lane.clj`,
  `shimmed-*.clj`, `headtree-cljd.clj`); `target/headtree/` is disposable.
- The Node proofs need the Clojure CLI on PATH (they resolve the JVM
  classpath themselves and skip loudly without it); under mise or bb this
  always holds.

## Facts worth keeping

- The reflection's `descriptor` op drains the link itself
  (`remote.cljc` `refl-descriptor`), so polling `:probe` files the attach
  confirmation without any wire op — the child's probe reply is the honest
  "the probe's answer crossed the wire" observation.
- Cursor results are maps: extract `:dao.stream/cursor` before the next
  ask.  Passing the whole result map as the cursor makes the ring answer
  `:dao.stream/invalid-cursor` (missing `:dao.stream.ringbuffer/identity`),
  not `:cursor-mismatch` — two of this task's intermediate failures were
  exactly that, plus one `#(@m)`-inside-`#()` reader trap (it compiles to a
  call of the deref result; fixed to `(fn [] @m)`).

Status: COMPLETE
