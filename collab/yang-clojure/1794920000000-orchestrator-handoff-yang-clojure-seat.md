Created-GMT: 2026-11-06 06:40:00 GMT
Created-Local: 2026-11-06 13:40:00 +07
Coding-Agent: interactive (ZCode, yang.clojure orchestrator seat — OUTGOING)
Status: WRITTEN BUT NOT COMMITTED — git is currently blocked machine-wide by the unaccepted Xcode license (see section H). The successor should commit this file once git works again.

# SEAT HANDOFF — yang.clojure orchestrator seat → successor orchestrator

Role: Lead Engineering Orchestrator for the yang.clojure track: the hardening-3 arc, the kernel differential gate (LaTTe oracle), the cell-ref ruling, the cross-language conformance matrix, and the call-site provenance generalization now in design.

Everything below is what the outgoing seat believed at 2026-11-06 ~13:40 +07. It is not fact: re-derive from git log, the files, and the hosts. Governing rules: docs/agents/roles/orchestrator.md (several rules were added 2026-11-04..06 — read them before your first dispatch) and docs/agents/build-n-test.md (remote hosts, mise ordering, per-lane prerequisites). Session log to read: orchestrator-logs/1793385006-zcode-glm-5.3-flash-yang-clojure-track.md — start from the last 12 entries.

## A. TRACK STATE: the kernel gate has reached :pass

The LaTTe differential kernel gate now reports `:gate-status :pass` — **165/165 admitted arities credited, zero unexercised, for the first time in the track's history.**

- Landed: the attribution-as-query implementation, merge commit `a7dc47c2` (implementation commit `d794382e`, branch `attribution-as-query`). It adds caller-side site tagging at compiled-AST application expressions (post-macroexpansion), with the callee-entry record carrying the *caller's* site id; mechanically derived site tables in `test/yang/latte_oracle/slice9_site_tables.edn`; the seven-case refusal battery plus the stale-table diagnostic; and an honest pin migration (13 revisions listed in `test/yang/latte_oracle/attribution_inventory.md` §6).
- Verification (jupiter, at `d794382e`): oracle namespace 30 tests / 2456 assertions / 0 failures; `yang.clojure-test` 95 tests / 922 assertions / 0 failures; `yang.base` 126 tests / 0 failures; kondo 0 errors on the code files.
- The governing ruling: `collab/yang-clojure/1794600000000-architect-attribution-as-query-ruling-of-record.md` (owner-signed after three independent reviews — gpt-6-astra, qwen3.8-max, kimi-k3.1-pro; 13 findings, all incorporated). Its core: attribution is a query over recorded datoms, shape never decides meaning, the caller's site id is what the evidence must carry, and ambiguity yields no credit.
- Earlier landings in the same arc, all on master: the D2 token-packet lane with the uniform 4M budget (`8fb525c4`); the evidence-walk harness fix (`9c973810`); H3-2 proof family (`d035bcd4`); H3-3 twenty-arity family (`c5292e64`); the `cell/authentic?` cell-ref ruling plus the clojure runtime module (`ae91bb8d`), which **closed the `yang.clojure ↔ typescript` conformance skip**; the cljs/dart restore (`acbf8e10`); the float-address pin re-derivation (`e6380a2f3`).

## B. H3-6 — THE LAST STEP (yours to dispatch)

The independent audit that formally closes the kernel gate. Per the versioned attribution section of `test/yang/latte_oracle/slice9_gate.md`:

1. Reconstruct 165/165 **from the raw traces**, by running the attribution query — do not trust `:coverage :entries`.
2. Re-derive the site tables independently and compare them with the derived ones.
3. Then final architect sign-off.

kimi's three non-blocking caveats from the final gate are your checklist:
- (a) The case-3 hash guard needs independent recomputation on the recorded-EDN path (on freshly compiled rows it cannot fire naturally).
- (b) Case 6's dynamic-site sub-assertion is `(when dynamic …)`-guarded — verify it actually fires.
- (c) The stale-site diagnostics are large but recorded (expected for whole-kernel fixtures).

## C. THE NIGHTLY FAILURE (the open item the owner flagged)

- The latest documented nightly incident is the **self-kill over SSH**: `pkill -f "nightly.clj"` matched the invoking shell's own command line and killed the run. The rule is now written in `docs/agents/build-n-test.md` (commit `1ea367ee`): kill remote processes by exact PID, never by name pattern.
- Your job: re-run the nightly on shiva/jupiter using that PID procedure, reading the actual lane output from the hosts (the `~/ci/*` lane logs). Trust the host output, never a summary.
- Known, pre-existing failures you will see and do **not** own:
  - Two php `G-str-offset-write` failures — the yang.php seat's known gap; their legacy-retirement branch carries the update. The retro step-7 review also flagged the stale `docs/agents/known-gaps.md:107` row, which is still on master and belongs to the php seat.
  - If float-address goldens fail again, they are the emitting seat's to re-derive, mechanically, from JVM actuals, with the attribution in the commit message (precedent: `e6380a2f3`, relay `collab/yang-call/1794820000000`).

## D. COORDINATION THREADS (all relayed through collab/; the owner distributes)

1. **Addendum A v3 is SIGNED** (`collab/yang-clojure/1794630000000-architect-attribution-addendum-a-RECORD.md`; reviewed by astra and qwen, both endorsed). It makes call-site annotation the declared standard for every yang.* compilation pipeline. Key constraints:
   - Annotation attaches to UAST application **occurrences** through the existing `[origin root-address path]` contract and the frontend-metadata side table.
   - **No new canonical AST slot** (provenance is deliberately excluded from hashed code content).
   - R1's deferred durable correspondence record was **activated at signature**.
2. **The generalization design document is delivered**: `collab/yang-clojure/1794670000000-architect-callsite-generalization-design.gpt-6-astra.findings.md`. It contains the occurrence contract (reserved key `:yang/call-site 1`, distinct from the safepoint `:yang/site` marks), full coverage of every application occurrence, the durable-record envelope, adoption coverage with a mandatory host-lane set and a cross-host occurrence-key equality gate, a measurement plan, and correctness gates. The frontend adoption briefs must cite this document. Note it deliberately does **not** freeze the oracle evidence schema — that is the format-race control.
3. **Design inputs from the ts/php seat are queued for intake** (`collab/yang-call/1794800000001`): the prelude_module emitter must be inside the cross-host equality check; the reserved provenance key should ride the existing side table with one centrally fixed name; and the **dynamic-call-site edge case** (`$$`, `extract`, `compact`, eval — runtime-determined source positions) needs a defined provenance rule. They offered PHP-side fixtures for that case.
4. **Relays ready for the owner to distribute**: `collab/yang-call/1794650000000` (generalization heads-up to ts/php/python) and `collab/jolt/1794660000000` (jolt heads-up plus the J2 re-run reminder).
5. **The jolt load-path arc**: my fix `a6c25106` (jolt-conditional require) broke shadow-cljs — shadow refuses a top-level `(require …)` as `ns*`. The python seat caught it (`collab/yang-clojure/1794800000000`). Fixed by `acbf8e10` with per-platform libspecs in the ns form (`:clj` and `:cljd` → `clojure.tools.reader`, `:cljs` → `cljs.tools.reader`, `:jolt` → nothing). Their clean-compile check confirms it. **Jolt's own J2 re-run on `acbf8e10` is still outstanding** and is theirs.

## E. DECISIONS ALREADY MADE — do not re-open

1. `registered-definition?/1` is credited through the ruled call-site attribution. The older stand / named-adapter / out-of-scope options are **superseded** by the signed ruling.
2. The **interim asymmetry** is documented, not a bug: the ts/py `js/cell?` and `py/cell?` predicates still test shape alone until their owning seats sweep them. Look-alike data answers at Clojure positions and may still abort at theirs. §8.10 of `docs/design/yang.antlr.md` states this. Do not "fix" it from the Clojure side.
3. The four-seat agreement holds: each seat owns its own prelude files. Never land edits into another seat's prelude; strip and discard them from your merges.

## F. TRAPS THAT COST THIS SEAT TIME

1. **glm's 5-hour usage cap.** A cap-killed dispatch becomes a long-lived retry process with buffered output. It looks alive to `ps` (check CPU, and even that can mislead) and dead to the log (a 74-byte banner). Look for the 429 / cap-reached strings, which only flush at exit. The round may also come back to life after the window resets.
2. **The `[claude-code:unrecognized_model]` banner is a warning**, not a death. Healthy rounds print it too.
3. **glm runs as the `claude` binary** (`claude --model glm-5.3`). `pgrep glm` misses it. This caused two false "it's dead" alarms.
4. **Background-task protocol** (orchestrator.md `97269e58`): every dispatch and remote gate runs in a tracked background task. Never use foreground sleeps. A cancelled wait means re-check, not restart.
5. **Test boundary**: individual tests may run locally; suite-scale runs (namespaces, subsystems, full suites) go to shiva/jupiter through `~/ci` clones, using the trust / fetch / merge-base ritual in build-n-test.md. The cljs lane needs `mise` java@21 and `npm install` (the closure-compiler classes are class-file 65; java 17 fails before the tests).
6. **Pin re-derivation ownership**: after a shared-runtime emission moves content-addressed goldens, the emitting seat re-derives the pins mechanically from JVM actuals and relays to the owning seats.
7. **Delegate death counts capability failures**, not quota windows. GLM's r4 and r5 dispatches both died on 429 caps and recovered after the reset.
8. **What worked in review**: three independent reviewers on one document (astra → qwen → kimi), each finding what the previous layer could not, with every claim grounded in file and line. Keep that pattern.

## G. INVENTORY

- This seat holds **no worktrees** (cljrt, evwalk, h31, h32 were all reclaimed after their merges). Verify with `git worktree list`.
- The jupiter scratch clone is `~/ci/datomworld-d2`. It is reusable: run the trust / fetch / merge-base ritual every time.
- Untracked files in the shared checkout under `collab/` belonging to other seats (the node-shard trio, orch-lane logs) should be left alone. File your own arc's strays per the write-time rule.
- The review threads are all filed under `collab/yang-clojure/` and `collab/yang-call/`. The ruling-of-record and its three review logs are the permanent record.

## H. THE GIT BLOCKAGE (blocking this handoff's commit)

As of 2026-11-06 ~13:30 +07 **every git operation on this machine fails** with "You have not agreed to the Xcode license agreements", because `/usr/bin/git` is the Apple shim and it now requires the license acceptance. This affects `git log`, `git status`, and `git commit` alike — hooks are not the cause.

The owner must run, once, in a terminal: `sudo xcodebuild -license`

Until that is done, the successor cannot commit, push, or inspect history locally. Options: run the works from a remote host (`~/ci` clones on jupiter/shiva have a working git), or wait for the owner to accept the license.

## I. FIRST ACTIONS FOR THE SUCCESSOR

1. Run `sudo xcodebuild -license` (section H) and commit this handoff file.
2. Read orchestrator.md and the new build-n-test.md sections.
3. Re-derive track state: master log, the session log's last entries, the ruling-of-record, the design findings document.
4. Re-run the nightly properly (section C) — it is the open item the owner flagged.
5. Dispatch H3-6 (section B) with kimi's caveats and the design document as its brief inputs.
6. Distribute the two ready relays (section D.4).
7. Absorb the ts/php design inputs (section D.3) when you draft the frontend adoption briefs.

---

# ADDENDUM — deltas since this handoff was written (2026-11-06 evening → 2026-11-07 early AM). Read after the main document.

1. **The exception track opened** (owner-ruled, separate track; python seat = steward): ruling v2 on master (collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md) — §8.10.7 enforced uniformly in all four frontends. THIS SEAT OWES TWO ROUNDS: **R3** (protocol failures retain and forward the complete condition — qualified reason + evidence — through the public escape at yang.clojure.cljc:1089/:1306) and **R4** (the intra-unit outcome-token channel: equivalence proof across caught/uncaught/protocol/binding/higher-order, or unify onto the raise-exit path). Sequencing of record: JS → PHP → Python → R3 → R4. **The round-0 kit (the FAILING fixtures + scaffolding) is NOT yet on master** — the dispatch holds until the steward lands it (flagged: collab/exception-track/1795050000000). The acknowledgment relay: 1795020000000.
2. **The cc1 unit (the call-site contract) is mid-fix-chain**: round 1 (muse, e6522ceb) → gate REJECT (4 pipeline findings); fix round 1 (muse, b825498e) → gate REJECT (2 custody residuals; F3/F4 closed); fix round 2 (kimi-k3.1-pro subagent, c4abd23c) → remote-verified green (CALLSITE 10/91/0, PUBLISH+CLOSURE 28/399/0, CATALOG 8/42/0) → round-3 re-gate **gate-fail** (3 findings: F1 needs per-occurrence origin keying per design §2.1's own K = [origin root path] rule — the origin distinguishes same-root/different-admission expansions; 2 batch-claim validation defects; 1 version-reason diagnostic). **The loop signature appeared — implementer dispatching is stopped.** The design-grounded fix is precise and small; the owner was to choose: the seat applies it + round-4 re-gate (recommended), another delegate, or park.
3. **The float-address pins re-derived** (e6380a2f3): the cljrt emission moved the shared prelude/manifest content; python/ts goldens updated mechanically from JVM actuals; relay collab/yang-call/1794820000000.
4. **The jolt load-path arc closed**: a6c25106 broke shadow-cljs (the python seat caught it); fixed by acbf8e10 (per-platform libspecs in the ns form); the python seat's clean-compile check confirms. Jolt's J2 re-run on acbf8e10 still theirs.
5. **The dao.stream relay sent** (collab/dao-stream/1794955000000): 2 batch-cljs failures in their async cross-process test — environmental or a real regression, their call.
6. **The Xcode-license git blockage** hit and was resolved by the owner (`sudo xcodebuild -license`) — if git fails the same way again, that is the fix.
7. **The H3-6 audit is NOT this seat's to dispatch** — another seat reassigned it to gpt-6.1-sol on jupiter (7fc6024a) after kimi's 429; this seat relayed the auditor's missing inputs (collab/yang-call/1794980000000). The ts/php seat adopted the per-frontend equality-gate scope (1795010000000).
8. **The cc1 re-gate verdict on the round-3 findings**: codex's review log collab/yang-clojure/1795020000000-reviewer-cc1-fix2.codex.stdout.log — read it before resuming the cc1 fix chain.
