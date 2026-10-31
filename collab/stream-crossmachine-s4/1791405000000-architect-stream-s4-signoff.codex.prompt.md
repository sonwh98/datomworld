Created-GMT: 2026-10-08 07:10:00 GMT
Created-Local: 2026-10-08 14:10:00 ICT

# Task: Track B Slice S4 Architectural Sign-Off Review

Role: Lead System Architect
Model: Codex (gpt-6-astra)
Repository root: /Users/sto/workspace/datomworld-stream-s3a
Branch: stream-crossmachine-s4

Perform a read-only architecture review and sign-off evaluation of Track B Slice S4: "Beyond loopback, terminal causes, and ephemeral listeners".

Read first:
- docs/design/datom.world.md
- docs/design/dao.stream.remote.md
- docs/design/dao.stream.ws.md
- collab/1791403500000-architect-stream-s4-spec.claude-fable-5-1.findings.md
- collab/1791404000000-engineer-stream-s4.claude-opus-5-5.findings.md
- collab/1791404500000-reviewer-stream-s4.codex.findings.md (Adversarial review verdict: ACCEPT)

Evaluate:
1. Foundational invariants (6 non-negotiable invariants, driver-paced clock-free execution, sole boundary at dao.stream.remote-channel).
2. Architectural decisions D1 through D9:
   - D1: Projection cause recording (`:cause`, `:opened?`) on closure; accessors `cause` and `opened?`.
   - D2: Neutral dial causes (`:ended`, `:dropped`, `:not-served`, `:unreachable`, `:expired`) on `:lost`.
   - D3: `connect/observe-terminal` refinement: refine only `detached` terminal; update `reattachable?`.
   - D4 / D5 / D6: Ephemeral port 0 admission in `make-endpoint`, binding on OS-allocated port, and dynamic descriptor finalization on `:bind-succeeded`; removal of `ephemeral-port-unsupported` from REPL.
   - D7: Driver-paced DHT head board stop machine in `yin.repl.dht/stop!` and `main/stop-tick`.
   - D8: Loopback-net ephemeral port allocator.
   - D9: Strict boundary gate: zero `:ws/` or `ws-project` tokens outside `test/yin/repl/host/`.
3. CLJ/CLJS/CLJD multi-host portability and test evidence.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report:
- Architectural Evaluation per decision/invariant
- Defect / Gap findings (if any)
- Sign-Off Verdict: ACCEPTED or REJECTED

Write your report to `collab/1791405000000-architect-stream-s4-signoff.codex.findings.md`.
