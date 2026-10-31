Created-GMT: 2026-10-07 10:55:00 GMT
Created-Local: 2026-10-07 17:55:00 +0700
Coding-Agent: glm (glm-5.3)
Session-ID: 39902f34-c344-4c11-adf4-a3b9943ff1aa

# Task: UCF M-next D15 — the composition and the REPL wiring
Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-07 17:55:00 +0700 | Status: active | Rationale: the last new-code slice of stage D; composes every landed holder namespace into the entry point stage E drives

Implement D15 in /Users/sto/workspace/datomworld-d10b (worktree, branch
ucf-d10b-kernel-lift-lower with D10b-B; the branch rebases onto master
before this work begins — D10b-B lands first, and master has moved).
Read first, in the worktree's collab/ and archive: the D plan r3
(archive/1791194000000-architect-m-next-d-plan-r3.claude-fable-5-1
.findings.md — sections 1.5, 1.7, 1.8, 1.11, 1.12 and the D15 test
contract), the D10 inputs ruling (1791240000000-architect-d10-lower
-inputs-ruling.gpt-6-astra.findings.md), the seam ruling
(1791320000000-architect-d14-seam-ruling-astra.gpt-6-astra
.findings.md), and the routing-relevant clauses of UCF 7.11 and
linker-dht 14.2/14.3. Then the landed namespaces you compose:
holder/export.cljc (enter/prepare/encode/abort/freeze/rehydrate),
holder/driver.cljc (the candidate, source/exit and journal halves),
holder/writer.cljc, holder/reader.cljc, holder/evidence.cljc,
holder/export's exclusive-capable? gate (authority.cljc), the front
(authority/front.cljc), and the REPL tick owner
src/cljc/yin/repl/main.cljc (`step-all`, the shutdown drains, the
host loops) with `yin/repl.cljc` and `yin/repl/dht.cljc`.

The contract (r3 1.8 and 1.11/1.12, as amended):

1. **`yin.vm.ucf.compose`** — plain functions, no `yin.repl` on its
   classpath, usable without the REPL:
   - an authority side: open the authority, the judge step, one front
     step per holder, the target and outcome readers, and the
     diagnostic stream;
   - a holder side: the driver step (candidate, running, safepoint,
     proposing, releasing, exiting — D13/D14's phases) wired with the
     composition-supplied seams: the inbound appender, the reply and
     outcome readers, the ledger reader, the lease clock, the
     progress journal, the protection declarations;
   - the exclusivity gate: an exclusive handoff is offered only when
     `authority/exclusive-capable?` holds for the composition's
     required failure model. Exclusive on a memory authority is
     REFUSED (`:yin.k/unsatisfied`), never silently downgraded to
     fork. Fork is offered only when the caller selects fork, and is
     labelled fork.
   - The carrier for bodies and code is the node's existing content
     store and the landed staged module-load path — no second loader,
     no second DHT step owner.
2. **The REPL wiring** (r3 1.12, as amended by residual 4):
   - the custody control-plane step goes inside
     `yin.repl.main/step-all` immediately after `yin.repl.dht/step`
     and before the `refusal`/`admitting?` branches — it carries
     renewals, pending releases, request retries, the judge step and
     front steps, and runs during hydration;
   - the custody program step goes after `driver/repl-step`, before
     `serve/step`;
   - shutdown (residual 4): the control-plane step continues on every
     tick of the existing bounded shutdown drain on all three hosts
     (the JVM loop's drain, Node and Dart's `stop-ticks` budget);
     program execution stays stopped — no custody program step once
     `:running?` is false; `moved?` also reports true while custody
     owes a control-plane write, so cadence holds at the base
     interval;
   - no new thread, timer, or step owner.
3. **Composition tests drive the whole handoff in one process** over
   the landed seams: the memory substrate for protocol tests, a
   file-backed authority (which passes the predicate) for the
   exclusive acceptance row. `compose` must be loadable and drivable
   with no `yin.repl` namespace loaded.

Test contract (r3's D15 row, all eight):
- Exclusive on a memory authority is refused, not forked.
- Fork runs when selected and is labelled fork.
- A whole exclusive handoff runs on a file-backed authority that
  passes the predicate, on each host.
- With the DHT store still hydrating, a renewal and a pending release
  are still sent.
- The plain composition test has no `yin.repl` namespace loaded.
- (residual 4) After `:running?` turns false, with a release held
  back by a full inbound stream: the control-plane step is called on
  every tick of the bounded drain, on each host's loop; the release
  is delivered once the stream accepts it; no program step runs.
- (residual 4) With the stream full for the whole drain: the process
  exits within the existing budget, the release intent is in the
  journal, and a restart sends it.
- (residual 4) `moved?` is true while a custody control-plane write
  is owed.

Acceptance criteria:
- Test-first per behavior; portable `.cljc`; JVM during iteration,
  three lanes at landing.
- Permitted diff: NEW src/cljc/yin/vm/ucf/compose.cljc and its test
  file; src/cljc/yin/repl/main.cljc (the two custody steps, the
  shutdown drain, moved?), src/cljc/yin/repl.cljc if a seam must
  surface there, and their tests. Anything else — especially the
  landed holder namespaces, the engine, or the authority — stop and
  report (that is a ruling).
- The landed holder namespaces are composed, never modified. If a
  public seam is missing, stop and report.

Constraints:
- No git writes. kondo/cljstyle may be sandbox-blocked; note it.
- `#?(:cljd nil :clj ...)` order for JVM-only test branches (:cljd
  first); a 0.0 literal is the integer 0 on JS.
- The REPL wiring changes shared files (main.cljc) — keep the diff
  surgical and re-run the full yin.repl suite, not just the new tests.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red
and green evidence, unresolved concerns, and any incomplete work. Do
not claim edits or tests that did not occur.
