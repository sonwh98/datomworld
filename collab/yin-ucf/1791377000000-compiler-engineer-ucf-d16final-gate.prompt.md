Created-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Created-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm (glm-5.3)
Session-ID: 43a4f336-c5d5-44b9-90e1-fca9ee34d674

# Task: UCF M-next D16-final — the compose-driven gate rows through yin.vm.ucf.compose
Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: glm-5.3 | Assigned: <local timestamp> | Status: active | Rationale: compose.cljc's author; the rows drive the composition you built

Implement D16-final in /Users/sto/workspace/datomworld-d10b (worktree,
branch ucf-d15-compose at f6711518 with D15 landed; create your own
branch ucf-d16-final off it). D16 splits: the non-composition half
(safepoint harness, fencing/retained-write rows) is LANDED (adcedefc);
YOUR round is the compose-driven half — the 14.2.4 rows that route
through `yin.vm.ucf.compose`, same host, on three lanes. Read first:
docs/design/yin.vm.linker.dht.md 14.2.4 (the eight test contracts,
their stage markings, and the note that D owns same-host rows),
UCF 7.11.1 clause 10's stage-D half, the D plan r3's D16 contract
(archive/1791194000000-architect-m-next-d-plan-r3.claude-fable-5-1
.findings.md — "**D16.**" section), and the landed code you drive:
src/cljc/yin/vm/ucf/compose.cljc (your D15 composition: open!, the
holder/source/restart/hand-off/enroll/abort operations,
control-step/program-step/step/stop, the inbox adapters),
holder/driver.cljc, holder/export.cljc, holder/writer.cljc,
holder/reader.cljc, holder/evidence.cljc, and the D16-prep harness
(test/yin/vm/ucf/safepoint_harness_test.cljc,
fencing_rows_test.cljc) whose fixtures and helpers you reuse.

The contract (r3's D16 compose rows):

1. **14.2.4 rows 1 to 5, 7 and 8 through the composition, same host:**
   row 1 (one admitted holder over two encodings; the other candidate
   :awaiting-grant then :not-holder), row 2 (the source emits nothing
   until its own grant), row 3 (concurrent reclaim/admission
   serializes), row 4 (zero or one commit; the recorded result
   redelivered; external IO earns no exactly-once), row 5 (replay
   divergence: evict a kept-cursor value, recover with durable inputs
   and once without; divergence at the same id is :intent-conflict, no
   commit), row 7 (crash cuts around completion: successor append,
   resumed report, release append, authoritative closure), row 8 (the
   full admission-order fixture through the composition).
2. **Row 6's retry logic as a unit test** (the partition itself is
   stage E): a lost lease fact or reply, retry unchanged requests.
3. **The stage-D gate's compose halves:** exclusive refused on a
   memory authority; fork labelled fork; the wired composition's
   journal cuts around freeze/storage/fencing (the D14 recovery seam's
   fresh-receiver rehydration rows).

Test contract: test-first per row; portable .cljc; three lanes at
landing. Reuse compose_test.cljc's world/fixture machinery and the
D16-prep harness's helpers.

Acceptance criteria:
- Permitted diff: test/yin/vm/ucf/* row files (plus shared test
  helpers). compose.cljc and the landed namespaces are NOT in the
  permitted diff — a missing public seam: stop and report.
- The rows drive `yin.vm.ucf.compose`, never the holder namespaces
  directly (that was D's protocol tests; yours go through the
  composition).

Constraints:
- No git writes. kondo/cljstyle may be sandbox-blocked; note it.
- `#?(:cljd nil :clj ...)` order for JVM-only test branches (:cljd
  first); a 0.0 literal is the integer 0 on JS.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red
and green evidence, unresolved concerns, and any incomplete work. Do
not claim edits or tests that did not occur.
