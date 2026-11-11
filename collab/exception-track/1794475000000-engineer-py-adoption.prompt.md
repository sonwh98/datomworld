Created-GMT: 2026-11-11 20:0x GMT
Created-Local: 2026-11-12 03:0x +07 (+0700)
Coding-Agent: codex
Session-ID: pending (provider-generated; capture thread.started.thread_id)

# Task: exception-py-r2r3 adoption — wire python's wrapper minting/reading onto the landed VM provenance primitive

Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: codex gpt-6.1-sol | Assigned: 2026-11-12 03:05 +07 | Status: active

WORKTREE: /Users/sto/workspace/datomworld-f12, branch `exception-py-r2r3`. FIRST: rebase onto current master (the primitive landed as `8c10c5b7` — branch `exception-vm-provenance` merged). You are the only writer. Adoption spec of record: `collab/exception-track/1794456000000-to-python-provenance-adoption-spec.md` + the GO note `1794470000000-to-python-primitive-on-master-adoption-go.md`.

## The adoption (the steward's five points)

1. **Mint** through `cell/dsg__new-provenance [value provenance]` (atomic fresh id+seal+value+provenance; nil provenance refused `{:reason :nil-provenance}`). Your wrapper minting site(s) in the ceded regions switch from `py/make-exc` + visible-slot writes to this door.
2. **Read** via `cell/dsg__provenance [candidate]` — egress (the :917 region) authenticates through it: authenticated live marked cell → the stored condition; anything else → nil → the plain fresh `:yang.call/thrown` path. Never fatal, never mutating.
3. **`py/retained-condition` DIES as an authority source**: remove the `:py/retained-condition` key writes from `py/condition-exception` (:1043 region) and the slot read from egress. Guest-visible writes to wrapper contents stay legal and simply cannot touch provenance.
4. **Transitive-helper closure (the audit sol named your file for)**: `py/condition-exception` must NOT become a guest-reachable constructor accepting arbitrary condition data. Your module exports every `py/` definition — audit the export/projection/alias list so the provenance helpers and private readers are outside guest reach (reserved protected names; check how the module exports and the linked-publication projection pick definitions; the other frontends' adoption notes in this folder show the audit shape).
5. **Acceptance**: the three contract pins (`0326a044`, RED — forge pair, clone-of-genuine, overwrite-attempt) flip GREEN; the eight ledger fixtures + parity pins (`be87df74`) stay green; the shared suites stay green (`exception-source-test`, `source-call-test`, `call-entry-test`, php `cst-test`); kondo 0/0.

## Operational rules (delegate-invocation-reference.md, learned this arc)

- Long test runs: `nohup clj -M:test -n <ns> > /tmp/<name>.log 2>&1 &` then poll with SHORT sleep+tail commands; read results only from "Ran N tests containing M assertions" + the failures/errors lines. Never foreground a clj run that takes minutes (the command timeout SIGTERMs it — two rounds lost to this).
- NEVER run `yang.call.exception-contract-test` locally (owner ruling). Remote acceptance is the steward's.
- Scratch writes inside the dispatch cwd only; quote globs in shell snippets; one simple command per step (compound pipelines trip denial heuristics).
- If the prelude changes: regenerate the scanner goldens (`deferred #{:py}`, three-run byte-identical, abort-gate untouched-group check — generator at `test/resources/yin/vm/semantic/scanner-golden-c4.generate.clj`; its `deferred` set is edited per run).

## Commit plan

(a) the rebase, (b) mint/read rewiring + the visible-key removal, (c) the export audit fix (if the audit finds exposure), (d) goldens if regenerated. Final agent message: the rewiring sites (file:line), the export audit result, the pins' green evidence, suite counts, goldens evidence.
