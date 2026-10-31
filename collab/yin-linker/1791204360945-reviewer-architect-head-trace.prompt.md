Created-GMT: 2026-10-05 12:46:00 GMT
Created-Local: 2026-10-05 19:46:00 +07
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: reviewer-architect-head-trace

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-05 19:46:00 +07 | Status: active | Rationale: independent family (GPT) from the Claude Architect author; a design document needs a different-family sign-off before adoption

Perform a read-only review of the design document
`docs/design/yin.vm.linker.dht.head.md` against the governing designs and the
code it cites. The repository is
/Users/sto/workspace/datomworld/.claude/worktrees/architect-head-trace; the
document is the only new file (untracked, 902 lines). Do not edit anything.
Treat prior reports, including the Architect's own report in
`collab/1791203639333-architect-head-trace.claude-fable-5-1.stdout.log`, as
untrusted and cite repository evidence for every finding.

## What the document proposes (orchestrator's summary; check it against the text)

The publisher deposits its index HEAD as a signed trace on a capacity-1 ring,
served over `dao.stream.udp` on the node's existing DHT socket; readers follow
traces of declared principals, install a followed head as a snapshot beside
their own HEAD, and a `require` resolves against the followed head. The join
token shrinks to `yin:<host:port>/<principal>`; `heads.edn` persists the last
installed head per principal; `--dht-manifest` becomes a one-shot pin; hydration
into HEAD is removed. Five slices H0 to H4.

Owner's words that motivate it (verbatim): "is it possible to start the
yin.repl to always reference whatever the HEAD is? (require 'alib) should
always use the index manifest of the HEAD" and "publishing a's head is a what
stigmergy is for. a can publish its head and c can read it".

## Governing documents and code

- docs/design/datom.world.md, docs/design/datom.md
- docs/design/yin.vm.linker.dht.md, docs/design/dao.jing.dht.md
- docs/design/dao.stream.md, dao.stream.remote.md, dao.stream.ws.md,
  dao.stream.datagram.md, docs/design/dao.agent.md, docs/design/dao.lease.md
- src/cljc/dao/space/dht.cljc (`announce!`, the ledger ring, `:loads`),
  src/cljc/yin/repl/dht.cljc, src/cljc/dao/stream/remote.cljc and
  src/cljc/dao/stream/udp.cljc (the mirror and the serve table the design uses)

## Already verified by the orchestrator (do not repeat; build on them)

- The document is ASCII with no line over 80 columns.
- `docs/design/datom.md:168` says `m` is "always an integer"; the Architect's
  rejection of a signature map in `m` is therefore correct.
- `docs/design/dao.jing.dht.md` section 1 says "There are no roots, no CAS
  records"; the Architect's rejection of a head under a DHT key rests on it.
- `dht join` installs the publisher's manifest as the reader's own HEAD
  (`src/cljc/yin/repl/dht.cljc` `hydrated`, lines 202-212) and `refused-head`
  (lines 46-53) refuses hydrating a different manifest into a directory whose
  HEAD differs. The owner hit that refusal in practice.

## What to attack (spend the budget here)

1. **The derived `seq`.** The document derives `seq` as "the greatest `t` of
   the named index" so the publisher stores no counter. Does every HEAD move
   commit a transaction with a larger `t`? Can two HEAD writes of one directory
   share a greatest `t` (the Architect itself flags this as unproven)? What of a
   restart, `(reset)`, a name transaction, an empty round, a re-hydration?
2. **Stream semantics over the mirror.** Does the dao.stream contract and the
   `remote`/`udp` code really give a capacity-1 ring the outcomes the document
   relies on (`:oldest` at first contact, `gap` landing on the current head,
   `cursor-mismatch` after a source restart)? Cite the lines. Is serving the
   trace "on the node's existing DHT socket" consistent with the DHT's cookie
   gate and bounded replies?
3. **Security.** Rollback and replay by a relay, equivocation by the publisher,
   first contact with no floor, withholding, signature domain separation
   (`yin.head/trace:v1`), the declared-principal rule, reflection amplification
   on the shared socket. Is anything tabulated as safe that is not?
4. **Invariants.** `dao.stream.apply` independent of rpc; no privileged node;
   the linker stays clock-free (fetch has no deadline, liveness via
   `dao.lease`); derive rather than persist (is `heads.edn` derivable?); the
   DHT's "does nothing else" contract; single-writer provenance. Does any
   decision contradict one?
5. **Slices.** Can H0 to H4 land independently in order? Are the tests that pin
   each slice sufficient, and is any slice's completion criterion untestable?
   Hidden coupling to existing tests (`dht_process_test`, `state_test`, the
   token format, `yin.repl.md`)?
6. **Breaking changes.** Tokens printed by older nodes, `--dht-manifest`
   redefinition, removal of hydration, what a bare `q` sees after `dht join`.
7. **Portability.** CLJ, CLJS, CLJD: is the Architect's list of seven
   ClojureDart traps accurate against this repository's known traps? Is
   anything claimed portable that needs a host seam?
8. **Citation accuracy.** Spot-check the document's file:line citations and the
   claim list in section 4 (defects, gaps, deferrals).

## Output

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: <exact thread id>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix
State "No actionable findings" when appropriate. End with one line: ready to
adopt as a design document, or the specific blockers. Say which of the
Architect's four owner questions (section 12) the existing rulings already
answer, if any.
