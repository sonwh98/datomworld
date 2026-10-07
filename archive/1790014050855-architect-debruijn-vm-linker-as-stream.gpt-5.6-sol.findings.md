I’ll verify the existing park/resume, completion, and content-loading contracts, then update only the reference-by-hash section and any strictly necessary phase provision.
Completed-GMT: 2026-09-21 18:08:49 GMT  
Completed-Local: 2026-09-22 01:08:49 +07  
Coding-Agent: codex  
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a

## 1. Stream-topology ruling

The framing is sound and follows the datom.world axioms. The linker should be a
stream process, not hidden VM machinery.

Already reused:

- `yin.vm.content` verifies content-addressed rows and vectors.
- `yin.vm.semantic` parks explicit continuation data and resumes it explicitly.
- `yin.vm.completion` computes reachable dependency and parked-state facts.
- `dao.stream` supplies blocked and gap outcomes.
- The module registry is already a value in VM state.

Still new:

- request and response stream conventions;
- a definition-arrival consumer;
- a value-held or streamed name environment;
- a linked instruction dimension containing `:call-hash`;
- explicit retry, timeout, and permanent-absence policy.

One qualification: an existing local VM cannot execute `:call-hash` without
additional opcode semantics. The future linked interpreter must therefore be a
sibling or additive layer. The current de Bruijn VM remains link-agnostic.

## 2. Four open points

1. Cycles: streams transport requests, but do not solve mutual recursion.
   The linker must compute strongly connected components and stable component
   identities.

2. Authority: streams do not determine which ledger or name environment is
   authoritative. That requires an explicit value/stream contract with trust
   and provenance.

3. Verification: Jing/content addressing and `yin.vm.content` provide the
   verification mechanism. The arrival consumer must reject absent or
   mismatched content before producing runnable code.

4. Failure: `blocked`, `gap`, timeout, retry, and permanent absence can all be
   explicit stream events. The owner must choose the retry/terminal policy;
   streams do not choose it automatically.

## 3. Effect on phases

B0 through B5 need no semantic change. B3 already makes loading and
continuation state explicit, and B4 already models park/resume as data
transitions. B5 must retain an injectable loader and must not hide fetches in
the VM.

B6 remains the additive future phase for request/response streams,
`:call-hash`, cycle handling, and failure policy. No change to the existing
semantic VM is required. A future linked interpreter will necessarily add its
own execution support.

## 4. Design edit

Section `§7.1 Future reference-by-hash linking as a stream topology` was updated
to cover:

- linker stream topology;
- park/request/arrival/verify/resume flow;
- reused versus new mechanisms;
- the four open-point ownership rulings;
- unchanged B0-B5 provisions;
- B6’s additive linked-interpreter scope.

The existing §8 owner-decision entry remains accurate and was not changed.

Owner decisions raised:

- authoritative name ledger and trust/provenance rules;
- request identity and response-stream schema;
- SCC and mutual-recursion hashing;
- timeout, retry, gap, and permanent-failure semantics;
- whether the future linked interpreter implements existing VM protocols.
