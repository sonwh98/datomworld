Created-GMT: 2026-10-05 16:46:33 GMT
Created-Local: 2026-10-05 23:46:33 +07
Coding-Agent: claude
Session-ID: 2e396723-5628-413b-b406-88d743d2aa93

# Task: head-h1 (round 3: two P2 findings from the confirmation review)

Role: DaoSpace and DaoJing Storage Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-05 22:57:21 +07 | Status: active | Rationale: same engineer, resumed to close the last two review findings

The independent reviewer confirmed your P1 and P2 fixes are closed
(`collab/1791218577681-reviewer-head-h1-r2.gpt-6.1-sol.findings.md`; untrusted, check
each citation) and found two new P2s. The orchestrator verified the first against the
code. Do not edit `docs/design/yin.vm.linker.dht.head.md` (the orchestrator maintains
it; if a design sentence needs to change to match, say so in your report).

## 1. `index-seq` accepts non-canonical datom rows

`src/cljc/yin/vm/linker/head.cljc` about line 300: `index-seq` accepts any vector with
at least four slots (`(<= 4 (count d))`). Reproduced by the reviewer: hash-valid
indexes containing `[100 :x/y 1 0]` and `[100 :x/y 1 0 0 :extra]` both emit
`:confirmed`, install and restore. Both violate the persisted five-slot datom shape
that `local-datom?` (`src/cljc/dao/datom.cljc` about line 42: a vector of exactly 5,
non-negative integer `e` and `t`, integer `m`, namespaced keyword attribute) enforces.
Require canonical rows inside the existing total check, preferably by using
`local-datom?` itself (keep the check total, inside its `try`, on every host). Add
confirmation AND restore cases for: wrong lengths (4 and 6), an invalid coordinate
(negative or non-integer `e`), an invalid attribute (not a namespaced keyword), and an
invalid metadata slot (non-integer `m`). Each must be refused as data
(`:yin.head/index-invalid`; startup refusal naming the principal on restore), floor
unchanged, nothing installed, no throw. Keep the existing malformed-`t` tests green.

## 2. A loaded foreign-kind record reaches confirmation

`head.cljc` about line 663: a LOADED record of another kind (for example a module-kind
load at an address that is also a valid index manifest) still reaches confirmation
through `loaded-datoms-of`'s local walk; the reviewer reproduced it producing
`:confirmed`. That contradicts the foreign-record rule in the design (5.5, amended: "A
record of any other kind at the candidate's address is neither read, forgotten nor
restarted: the candidate is reported `:yin.head/unloadable` once with that record's
failure and waits until the record's owner removes it, and then loads as a candidate").
Handle foreign kinds BEFORE the status branches: for a record of a kind that is neither
the candidate kind nor the index kind, whatever its status (`:loading`, `:loaded`,
`:failed`), report unloadability once, leave the record untouched, hold no owner-set
entry for it, and acquire the address only after the record is removed. Add a regression
for a LOADED foreign-kind record (and keep the failed module-kind test green); also
assert the follower never reads the foreign record's datoms.

## Scope and process rules (unchanged)

Files: `src/cljc/yin/vm/linker/head.cljc` and `test/yin/vm/linker/head_follow_test.cljc`
(and `test/dao/space/dht_test.cljc` only if a hook is truly needed). No git commands,
no formatter, no Node or Dart runs, no background processes. Verify in the foreground:
`clj -M:test -n dao.space.dht-test -n yin.repl.dht-test -n yin.vm.linker.head-test -n
yin.vm.linker.head-follow-test -n yin.vm.linker.dht-test -n yin.vm.linker.sign-test` and
`clj -M:kondo --lint` on the touched files, with assertion counts. Do not weaken or edit
any existing test. Portable `.cljc` and the ClojureDart traps from the first brief
still apply.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 2e396723-5628-413b-b406-88d743d2aa93

Then report: per item, what changed and the tests that pin it; the exact commands and
outcomes with assertion counts; any design sentence that now needs to change; anything
unresolved.
