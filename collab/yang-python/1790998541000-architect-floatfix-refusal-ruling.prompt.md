Created-GMT: 2026-10-03 03:35:41 GMT
Created-Local: 2026-10-03 10:35:41 +07 (+0700)
Coding-Agent: claude (fable-5-1 session 1379a095-acd5-4d9b-a645-75ab92207d1c) and codex (gpt-6-astra, resume of thread 01a0f878-281b-7253-ac44-ff2402583d35)
Session-ID: 1379a095-acd5-4d9b-a645-75ab92207d1c (fable); astra resumes 01a0f878-281b-7253-ac44-ff2402583d35

# Task: Float-fix — ruling on generic-yin arithmetic over the float64 carrier

Role: Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-03 10:35 +07 | Status: active | Rationale: standing mob (routing: owner-decision questions go to the architect pair)
- Model: gpt-6-astra | Assigned: 2026-10-03 10:35 +07 | Status: active | Rationale: standing mob partner; independent opinion, same brief

Read-only. Answer independently; do not edit.

## The question

The uncommitted float-fix change (worktree /Users/sto/workspace/datomworld-py-floatfix, branch
yang-python-floatfix, base 69e58662) implements docs/design/yang.antlr.md section 8.5.5 (float
addresses; read it on master at /Users/sto/workspace/datomworld). It also adds a behavior that the
8.5.5 text does not record. In src/cljc/yin/vm.cljc (~370-420, `carrier-refusing`, `refuse-carrier`),
the generic-yin primitives `+ - * / < > <= >=` now throw
`{:yin.k/status :yin.k/non-portable, :yin.k/kind :float64-carrier, :yin.k/name <op>}` on JavaScript
when handed Jing's float64 carrier (tag 27). On JVM and Dart the carrier is a host double, so the
same primitives compute. `= == !=` stay host `=`. The stated reason: JS would otherwise coerce the
carrier through its text and return a string or a wrong number, and unwrapping it inside generic yin
would recreate the 1 vs 1.0 divergence. Python computes on floats only through its explicit seam
`data/float-value`. It is tested only for `+` (test/yang/python/antlr/float_address_test.cljc ~316-335).
Both independent static reviewers flagged it as behavior beyond the ruling.

Decide, with reasons grounded in the datom.world invariants (yin.vm knows nothing about its profiles;
one representation; same address on every host; derive, don't persist):

1. Is this refusal correct to ship in the float-fix slice, or should generic yin keep host-native
   arithmetic (the carrier then being a Python-profile concern only)?
2. If it ships: where is it recorded (8.5.5 text, UCF scalar-arm paragraph, both)? Is the JS-only
   asymmetry acceptable, or must the refusal be uniform across hosts? Is `+`-only test coverage
   enough, or must every op in `carrier-refusing` be pinned?
3. If it does not ship: what replaces it, and which part of the float-fix change must be reverted?

Give a recommendation, not a survey. State a one-line verdict first: SHIP-AS-IS / SHIP-WITH-CHANGES
(list them) / REMOVE.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
