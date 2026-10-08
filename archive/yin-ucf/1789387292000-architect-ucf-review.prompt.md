Created-GMT: 2026-09-14 12:01:32 GMT
Created-Local: 2026-09-14 19:01:32 +07
Coding-Agent: codex
Session-ID: pending (provider-generated; capture thread.started.thread_id from JSONL)
# Task: Architectural review of the Universal Continuation Format draft
Role: Architect
Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-14 19:01:32 +07 | Status: active | Rationale: Architecture and failure-mode review; author was claude-fable-5.1 (Claude family), reviewer must be independent.

## Subject

`docs/design/yin.vm.universal-continuation-format.md` (684 lines, uncommitted) — the Phase 5 protocol draft authored by claude-fable-5.1. It specifies how a parked CESK configuration of the semantic VM (`docs/design/yin.vm.semantic.md`) is lifted into a portable, content-addressed value and lowered on a heterogeneous engine, answering five blockers: code identity, safepoints, portable encoding, dependency closure, ownership arbitration.

## Read first

- `docs/design/yin.vm.universal-continuation-format.md` — the document under review
- `docs/design/yin.vm.semantic.md` — §1–§6 are the contract this builds on (especially §2.2 segment attrs, §2.4 opcode table, §3.5 park/resume, §4.1 configuration, §6.4 reserved superinstruction/lexical-addressing work)
- `docs/design/dao.jing.md` and `src/cljc/dao/jing.cljc` (`segment-key`, the transitional-encoding open item)
- `docs/design/dao.lease.md` — lease vocabulary the ownership section adopts
- `docs/design/dao.stream.md` — *Explicitly Absent* (take/lease), *Envelopes*, *Retention and Gaps*
- `src/cljc/yin/vm/semantic.cljc` — the reference machine (parked records, `resolve-var`, `:primitives`)
- `src/cljc/dao/stream/observer.cljc`, `src/cljc/dao/stream/apply.cljc` — handle `descriptor`, `:dao.stream.apply/id`

## Review charter

Judge the draft as an architecture reviewer: does each of the five blocker solutions actually close its blocker under the system's invariants (no hidden global state, no implicit control flow, no callbacks, no shared mutable state, no layer collapsing, no assumed graphs)? Challenge, do not summarize. In particular:

1. **Code identity (§7.3).** Is the canonical instruction stream total and deterministic across hosts? Sorting `[e a v]` triples mixes numbers, keywords, symbols and vectors as values — is the sort and the `dao.jing/segment-key` hash well-defined for every operand the §2.4 opcode table admits? Does dropping `:yin.code/derived-from`/`:yin.code/source` plus pc-renormalization really make distinct compilations converge, and can two semantically different segments collide? Is the contract-stamp mechanism (§7.3.3) sufficient to pin opcode semantics, and is the loader-keyed-by-address change (§7.3.4) a backward-compatible extension of `yin.vm.semantic.md` §3.1 or an unflagged contract change?
2. **Safepoints (§7.4).** Is the safepoint table complete against the §2.4 opcode set (every parking transition listed? `:stream-put` full → resume accumulator is *the written value* — right?). Is `pc+1` valid for every listed instruction under §2.6 well-formedness? Are foreign-engine safepoint datoms derivable purely from the canonical stream as claimed?
3. **Portable encoding (§7.5).** Reverse primitive lookup by host identity — soundness and failure modes. Value-table sharing semantics: are `:yin.k/ref` addresses stable when the table is threshold-dependent (inline vs table changes the encoding, hence the value's own `:yin.k/id` — is that intended and harmless?). Cycle refusal adequacy.
4. **Dependency closure (§7.6).** Store-slice reachability via "every store key a `:var` in any required segment could resolve to" — computable? The merge rule "slice wins on collision" — any case where that is wrong? FFI-pair exclusion and `:yin.k/pending` travel — consistent with §3.4/§3.5?
5. **Ownership (§7.7).** The deepest question: the leased subject is a *content address* — data anyone may hold. `dao.lease.md` grounds the grantor in possession of a resource; here the possessed resource is "the right to run", which no one possesses. Is the grantor's authority well-founded, or does this need a new grounding? Is emitter-as-ordinary-candidate plus fencing-by-`:yin.k/incarnation` actually sufficient against the source-wakeup race and duplicate resumes? Any liveness concern (grantor lost, arbitration medium partitioned) the draft waves off?
6. **Cross-cutting.** Outcome algebra completeness (§7.9), invariant-compliance table honesty (§7.10), open items (§7.11) — anything claimed closed that is not, or missing from the list? Any place the draft quietly violates an axiom or invents vocabulary that collides with existing `:yin.k/*`, `:yin.safepoint/*`, `:dao.lease/*` namespaces?

## Deliverable

A structured review, in this order:

1. Verdict: `APPROVE` | `APPROVE-WITH-FINDINGS` | `REJECT`.
2. Numbered findings, each with severity `P1` (blocks acceptance — architectural defect or unsound mechanism), `P2` (must fix before implementation phase), `P3` (editorial/consistency), the exact section/line in the draft, the defect, and the concrete fix. If a challenged mechanism is actually sound, say so explicitly under a short "verified sound" list rather than staying silent.
3. A one-paragraph assessment of whether the five blockers are genuinely closed by the draft or only relocated.

Ground every finding in the governing documents and source; cite file and line. Produce the complete review in this turn — do not stop at a plan or ask for human input.

Begin the final response exactly with:

Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and named timezone>
Coding-Agent: codex
Session-ID: <this run's thread id if known, else the value from your context>

Provenance correction (append-only): provider-generated thread id captured from JSONL
thread.started = 01a09fcc-3b9f-7172-b592-f15348d6c88b. Follow-ups use
`codex exec resume 01a09fcc-3b9f-7172-b592-f15348d6c88b --json -`.
