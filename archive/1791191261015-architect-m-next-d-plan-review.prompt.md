Created-GMT: 2026-10-05 09:07:41 GMT
Created-Local: 2026-10-05 16:07:41 +0700
Coding-Agent: codex (gpt-6-astra, resumed thread 01a0f878-281b-7253-ac44-ff2402583d35)
Session-ID: 01a0f878-281b-7253-ac44-ff2402583d35

# Task: adversarial review of the M-next D plan (read-only; a verdict is the deliverable)
Role: Architect (adversarial review)

You reviewed the M-next B amendment r5 and the C3 numeric rulings on this
thread. fable-5.1 has now authored the stage-D plan. Adversarially review
it: find what breaks, what contradicts the published text or the landed
code, what is missing, and what silently widens scope. Do not restyle the
plan; rule on its substance.

## The plan

collab/1791190689957-architect-linker-m-next-d-plan.claude-fable-5-1
.stdout.log — 12 slices D1 to D12: the observe/apply engine seam with a
custody gate, the fenced writer, the recorded reader, version-1
lift/lower/reader over `jing/canonical-bytes` and segment addresses, the
exporting state and abort rule, the candidate/source driver halves with a
progress journal, the plain composition plus REPL wiring, and the D12
safepoint harness and stage-D gate.

## Review against

- docs/design/yin.vm.universal-continuation-format.md as amended through
  C12 (7.2.1, 7.4.1-7.4.3, 7.7.1-7.7.8, 7.9, 7.11.1 with the version-1
  clause block and its stage markers)
- docs/design/yin.vm.linker.dht.md 14.1 to 14.3 (14.2.2's input protocol
  and protection, 14.2.3's exact steps, 14.2.4's test contracts and their
  stage markings, 14.3 item 4)
- The landed C substrate under src/cljc/yin/vm/ucf/ (authority, ledger,
  grant, admission, completion, input, front, checkpoint, custody) and
  src/cljc/dao/stream/journal.cljc — the plan's seams must match real
  signatures and real facts
- src/cljc/yin/vm/engine.cljc and src/cljc/yin/vm/ucf/handoff.cljc (the
  stage-1 version-0 driver) — D2's claim that observe/apply splits every
  IO path, and D3/D5's claims about lower
- The engine gate's interaction with UCF 7.4.1's safepoint table and
  7.7.4's exporting state
- The C12 outcomes: exclusive-capable? is the only exclusivity gate;
  enrollment retry discipline landed in UCF 7.7.7; the C12 gate review's
  rulings (collab/1791125400000-reviewer-c12-gate-gate.glm.findings.md)

## Specific pressure points

1. The held-immediate rule (`:stream/poll`/`:stream/cursor` held one
   step, recorded as `:yin.k/read`, export refuses while held): is it
   sound against 7.4.1, 7.4.3 and the replay protocol, and is the
   no-new-pending-variant claim right?
2. The fenced writer's request id derived from lease and op id: does it
   dedup correctly across regrants (new lease, same op id), and against
   the authority's dedup namespace and C10's frontier rules?
3. Replay divergence ends the run and releases: is that the right
   fail-closed shape under 14.2.2 and 7.7.7, and does releasing on
   `:intent-conflict` contradict the quarantine rule?
4. Version-1 reader runs `checkpoint/inspect` first, then restoration
   grammar: zero-side-effect claim, and the C4 rulings' requirement that
   every `:install` pending has its entry.
5. Deterministic lift as a pure function of the export record: is the
   fixed traversal enough on all three hosts (map iteration order,
   floating point, fresh names)?
6. The abort rule and the unknown-offer-append case: does any path leave
   two runners or lose the fence?
7. Scope: anything D claims that belongs to E, or anything owed at D
   (per UCF 7.11.1's stage-D markers and 14.2.4) the plan drops?
8. The REPL wiring claim that `yin.repl.core` is gone and
   `yin.repl.main/step-all` is the single step owner: verify against the
   tree and say what the plan must cite instead.

Verdict first: ACCEPT, ACCEPT WITH CHANGES, or REQUEST CHANGES, then
numbered findings with the exact text or code each turns on. Be concrete;
a survey is a failure. Read-only: edit nothing, run no suite.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
