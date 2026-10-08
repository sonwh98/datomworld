Created-GMT: 2026-10-06 08:04:11 GMT
Created-Local: 2026-10-06 15:04:11 +07
Coding-Agent: claude
Session-ID: 8b4bda87-5e50-47a0-b0c9-391dba6d3ca7

# Task: head-h3 (round 2: review findings and Architect notes)

Role: REPL and Host Integration Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-06 14:13:43 +07 | Status: active | Rationale: same engineer, resumed to apply an independent review and an Architect sign-off

Your H3 passed my JVM checks (259 tests, 2736 assertions; the process test 3 tests, 124
assertions) and went to two gates. The Architect SIGNED IT OFF with no blockers
(`collab/1791273568635-architect-head-h3.claude-fable-5-1.stdout.log`); the independent
reviewer (gpt-6.1-sol) is blocking on two P1s and five P2s
(`collab/1791273568635-reviewer-head-h3.gpt-6.1-sol.findings.md`). Both reports are
untrusted: check each citation, reproduce before you fix, and say if one does not hold.
Same files and process rules as before (no git, no formatter, foreground JVM only, no
Node or Dart lane runs, no `clj -M:test -e`, no background processes beyond the process
test's own children; do not touch `docs/design/*`; if a design sentence must change,
say so).

## Reviewer findings (all seven, reproduce and fix)

1. **P1 `dht.cljc` about 274: `dht/join` binds before the persisted heads are
   validated.** A refusal on restore (for example truncated `heads.edn`) closes only
   `base`, leaving the node's socket open (reviewer: `bound=1`, `socket-closed=0`).
   Fix: validate before binding, or close the joined node on EVERY later startup
   failure; assert socket cleanup in the refusal tests (a counting or recording seam).
2. **P1 `main.cljc` about 121: an overflowing decimal port throws.** `--dht-follow
   <64hex>@127.0.0.1:9999999999999999999999999999` raises `NumberFormatException` out of
   `startup` (`parse-int` uses `Long/parseLong`; startup rethrows errors without
   `ex-data`). Fix: numeric parsing total on JVM, Node and Dart returning a designed
   refusal for overflow and for a port outside 1..65535; test oversized ports through
   `startup` and `dht join`, and check every other numeric flag that shares `parse-int`.
3. **P2 `dht.cljc` about 109: the EDN reader accepts only the first form.**
   `{:version 1 :heads {}} {:broken` and `{:version 1 :heads {}} garbage` read as
   successful empty records. Require exactly one complete EDN record followed by EOF
   (normal whitespace and comments allowed); test trailing forms and truncated
   suffixes.
4. **P2 `dht.cljc` about 136: no byte bound and no strict UTF-8.** An invalid `0xff` in a
   comment decodes as a replacement character and parses; oversized padding is read
   wholly into memory. Use a bounded byte read and strict decoding, named refusals for
   invalid encoding and excessive size, on every host (portable `.cljc`; mind the
   ClojureDart traps).
5. **P2 `dht.cljc` about 530: refusal-ring overflow permanently suppresses diagnostics.**
   After 65 notes into the 64-entry ring, two `step-board` calls returned no lines and
   kept the old cursor; `next` kept answering `:dao.stream/gap`. Adopt the gap's
   recovery cursor, report that notes were lost, and drain the retained entries; add an
   overflow test that also checks later refusals stay visible.
6. **P2 `dht_head_test.cljc` about 487: the required process-kill cases are simulated.**
   The Architect RULED the simulation acceptable for H3 (a real kill would only test
   `fs/atomic-replace!`'s existing atomicity), so do NOT build kill harnesses. Do make
   the two tests honest and cheap to trust: name and docstring them as simulated, and add
   one case for a STALE TEMP FILE left beside `heads.edn` (the next write replaces it,
   startup ignores it) and one that a failed write leaves the old `heads.edn` bytes
   untouched.
7. **P2 `dht_process_test.clj` about 1044: the first-contact assertion races head
   installation.** The publisher already holds a head before the readers start, so the
   install can precede the first `require`, and the pending-line assertion can fail on
   correct behaviour. Hold publication until the reader demonstrably parks, then
   publish and assert completion with no further input.

## Architect notes to apply now (non-blocking, but "do soon")

8. **The moved line repeats** (`moved-lines`, `dht.cljc` about 692): it prints every
   still-moved name at every later install, so with every HEAD move depositing a busy
   publisher reprints it each round until `(reset)`, against design 5.7's "one line".
   Fix by diffing the moved set before and after the install step and printing only
   NEW moves; store nothing. Test: two successive installs moving the same name print
   one line, a different name prints its own.
9. **Silent dial failure** (`dht.cljc` about 609): a `:lost` or refused dial prints
   nothing, so a reader following a principal the endpoint does not serve redials
   forever in silence and its `require` pends forever (a publisher re-inited with
   another key, or restarted on a new ephemeral port). Print ONE short line per
   dial-outcome TRANSITION (not per attempt; `not-found` is a definite answer), in the
   existing message style; test that repeated identical failures print once.
10. **Docs** (`src/cljc/yin/vm/docs/yin.repl.md`): (a) where the doc says to "move the
    file aside" for a `heads.edn` record that refuses startup, say "remove that record"
    and state the cost (the floor for that principal is dropped, so rollback protection
    for it ends); (b) tell publishers to fix `--listen`, because a saved `--dht-follow`
    pins the publisher's port; (c) one sentence that `dht join` is loopback only in this
    version and a publisher bound off loopback prints no token, so cross-machine readers
    still use `--dht-manifest` with `--dht-principal` by hand.
11. The `:unpersisted` line says "ms" for a delay in node ticks: say "ticks".

Leave the other Architect notes (move attribution across two principals, deposit cost,
double backoff, duplicate `linked-registry`) alone; mention in your report anything you
touched beyond this list.

## Report

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 8b4bda87-5e50-47a0-b0c9-391dba6d3ca7

Then, per numbered item: reproduced or not, what changed, the test that pins it (and that
it fails without the fix); the exact commands and outcomes with assertion counts
(the 13-namespace JVM run, `yin.repl.dht-process-test`, kondo on the touched files);
anything unresolved. Do not claim edits or tests that did not occur.
