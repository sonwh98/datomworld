Created-GMT: 2026-10-06 08:35:52 GMT
Created-Local: 2026-10-06 15:35:52 +07
Coding-Agent: claude
Session-ID: 8b4bda87-5e50-47a0-b0c9-391dba6d3ca7

# Task: head-h3 (round 3: a Dart compile failure and two review P2s)

Role: REPL and Host Integration Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-06 14:13:43 +07 | Status: active | Rationale: same engineer, resumed to fix a host failure the full lane found and two review findings

## 1. BLOCKER: the tree does not compile on ClojureDart

`bb test` on your round-2 tree exits 1 at its first stage: the task
`build:yin-repl-peer` fails with a ClojureDart compilation error "compiling namespace
yin.repl.slice-peer / compiling namespace yin.repl.main" ("Compilation error -
$expletives"); cljd does not print the cause, only the faulty form's text. The orchestrator
reproduced it standalone: `bb build:yin-repl-peer` (log
`collab/h3-dart-peer-build.log`, which has nothing more). The last warning before the
failure is a dynamic `.-length` at `src/cljc/yin/repl/dht.cljc:195` (`byte-length`), so
the new `:cljd` branches of `read-bounded`, `byte-length` and `decode-utf8` (about
lines 156 to 211), and anything else you added for Dart (the `Uint8List` test helper, the
`convert` require), are the first suspects. THAT IS A GUESS. Find the real cause.

**For this item ONLY, the "no Dart runs" rule is lifted**: you MAY run, in the
FOREGROUND, `bb build:yin-repl-peer` (about a minute or two) and the focused Dart test
command for the REPL namespaces (read `docs/agents/build-n-test.md` for the lane
commands and how to run a subset; keep it focused, do NOT run the full `bb test`).
Iterate until the peer build compiles and `yin.repl.dht-head-test`, `main-test`,
`state-test` (and the other portable test namespaces you added or changed) pass on Dart.
Remember the ClojureDart traps: `#?(:cljd nil :clj ...)` with `:cljd` FIRST; typed
interop (`^Uint8List`, `^String`) instead of dynamic member access; Dart has no
`java.nio`, `fs`, or `js/`; EDN with no whitespace before a closer; `Utf8Decoder` and
file reads need `dart:convert` / `dart:io` required the way the repo's other cljd
namespaces do (look at how `dao.space.store.fs` or `read-file-text` does it; the
Architect suggests `read-bounded` and `decode-utf8` may belong beside it: do that ONLY if
it is the simplest way to compile). Also check the Node build still passes
(`bb build:yin-repl-node`).

If the fix changes the SHAPE of `open`/`compose`, the order of read and bind, or the
strictness of the reader, say so loudly in the report: the Architect must re-read it.
Inside the `:cljd` host branches or `parse-int` it does not.

## 2. Reviewer P2: a named pipe at `heads.edn` blocks startup

(`collab/1791275484151-reviewer-head-h3-r2.gpt-6.1-sol.findings.md`, untrusted; verify.)
`dht.cljc` about 174: a FIFO at `heads.edn` blocks in the bounded reader (reproduced by a
focused JVM child that stayed blocked 15 s), while startup already holds the directory
lock. Fix: reject non-regular targets BEFORE a potentially blocking open (a directory,
a FIFO, a device; handle symlinks explicitly: a symlink to a regular file may be
refused or followed, but state which and test it) and, where the host supports it,
validate the opened resource. A refusal is data (a named message), never a hang or a
raw exception. Add a bounded subprocess test for the FIFO refusal on the JVM (timeout
so a regression fails rather than hangs); the Node and Dart equivalents only if the
host can express "not a regular file" cheaply.

## 3. Reviewer P2: cleanup can replace the original refusal with a raw exception

`dht.cljc` about 431: injecting a local-store close failure during an unfollowed-record
refusal yielded a raw `IOException("close failed")` with no `ex-data`, one socket close
and two local-close attempts; the principal/record diagnostic was lost. Fix: preserve the
ORIGINAL refusal through any cleanup failure (close in a way that cannot mask it), close
through the node once ownership transfers, and do not close `base` a second time. Add
tests with throwing socket-close and store-close seams asserting the original refusal
survives, each resource is closed exactly once, and nothing leaks.

## 4. Architect note

The unreadable-file refusal (`dht.cljc` about 326) still says "move it aside" without
stating that this drops every principal's floor; make it match the per-record message
(remove that record, with the cost: rollback protection for that principal ends until a
head installs) and the `yin.repl.md` text.

## Rules (unchanged except item 1)

Same files as before (add none without saying why), no git, no formatter, foreground only
(no `clj -M:test -e`, no background processes beyond the process test's own children), do
not touch `docs/design/*` (say if a sentence must change; if `read-bounded` /
`decode-utf8` move, design 5.10 may need a sentence: say so).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 8b4bda87-5e50-47a0-b0c9-391dba6d3ca7

Then report, per item: the actual cause (item 1) with the evidence, what changed, the test
that pins it; the exact commands and outcomes with assertion counts for the 13-namespace
JVM run, `yin.repl.dht-process-test`, `bb build:yin-repl-peer`, `bb build:yin-repl-node`
and the focused Dart run; kondo on the touched files; and whether any change reaches
the Architect's "comes back to me" cases. Do not claim edits or tests that did not occur.
