Created-GMT: 2026-09-28 05:36:00 GMT
Created-Local: 2026-09-28 12:36:00 +0700
Coding-Agent: codex

# Task: Architect ruling — one envelope contract: dao.stream.rpc converges on dao.stream.apply

Role: Lead System Architect (design ruling)

## Background (all landed on master, fable-signed-off, pushed dd4ab710)

The dao.stream.remote epic is complete (slices 0-8, commits
431a269c..19c44279). The owner ruling 85a11435 kept yin.vm.ffi on
dao.stream.apply and DEFERRED remote-FFI-by-composition to a
post-epic revisit. The owner is now revisiting, with a direction
already endorsed at orchestrator level: converge the envelope.

Current state of the two namespaces:

- dao.stream.apply is the transport-free envelope contract + pure
  dispatch: request/request? and response/response? (id/op/args,
  open to additional keys -- the verbatim-envelope rule of the
  FFI-migration ruling), correlation-id? = any non-nil id
  (apply.cljc:24-28), endpoint pair put-request!/put-response!/
  next-request/next-response, dispatch-request over a handler map,
  serve-once! server loop. It IS the VM's FFI contract: yin source
  syntax (dao.stream.apply/call :op args...) parses to the
  :dao.stream.apply/call AST node (yin.vm.cljc:194); the
  interpreters build apply2 envelopes on call-out (yin.vm.semantic,
  yin.vm.ast_walker, yin.vm.debruijn/register + stack + effects);
  yin.vm.ffi dispatches the answers (apply2/dispatch-request,
  put-response!). The cljs demo bridge speaks apply envelopes.
- dao.stream.rpc (reworked in slice 5, 8b5d907b, gated) is a client
  driver over remote surfaces with its OWN vocabulary:
  request-value/request-value?, success-answer/error-answer/answer?,
  safe-id? + random-safe-id allocation with ids-in-use? and a
  bounded outstanding set, the poll!/handle-event state machine with
  replay/gap/terminal handling, mint-cursor reflection minting, and
  a translation layer (transport-error-reason, rpc.cljc ~432-445)
  that maps dao.stream.remote failures onto APPLY's error vocabulary
  (:dao.stream.apply/not-found, /detached, /transport-error).
- correlation-id?'s docstring already anticipates the dependency:
  "Allocation policy (including the cross-host safe-integer bound)
  belongs to the RPC client, not this envelope."
- Consumers: rpc is used by yin.repl (cljc, driver, connect,
  adapter, serve) and dao.stream.observe. apply is used by 7 VM
  namespaces and the cljs demo.
- Fable final sign-off punch list (collab/1790568188000), the items
  relevant here: P2-4 rpc collapses no-surface/oversize into
  transport-error (a permanent refusal and a configuration error
  sound retryable); P3 the remote core's absorb! does not check that
  an answer's :dao.stream/identity matches the request's, so anyone
  who can write to a reader can forge an answer; P2-2 a pair channel
  whose in-stream answers not-found/channel-gone never ends, so
  waiters retry forever; P2-3 the UCF facade's serve! callback has
  an undocumented idempotence-per-handle requirement and no
  production (leased) serve! exists.

## The question to rule on

Owner-endorsed direction: ONE envelope contract. rpc adopts apply's
request/response shapes as the wire vocabulary, keeping rpc's id
allocation, state machine, and translation. The namespaces stay
separate (contract vs driver). End state: remote FFI means serve the
VM's call-out surface; an rpc-style client answers with plain apply
envelopes; yin.vm.ffi/dispatch-request consumes them unchanged --
zero translation, zero vocabulary drift.

Rule on each sub-question; where the evidence contradicts the
direction, say so (REJECTED with reasons is a valid outcome):

1. Shape mapping: do rpc's request-value/answer fields map onto
   apply's request/response without loss? Enumerate what rpc's
   shapes carry that apply's do not (validation strictness,
   answer-error structure, diagnostics events) and rule where each
   lands: an apply contract extension (additive, open-envelope rule
   respected) or rpc-local state.
2. Id allocation: confirm rpc keeps random-safe-id + bounded
   outstanding. Rule whether correlation-id? stays (some? id) or
   tightens, and whether the allocation policy moves into the
   contract (documented) or a validator (enforced).
3. Error vocabulary: fold fable P2-4 in. Rule the mapping for
   no-surface and oversize (new apply words? a permanent-vs-retryable
   distinction in the vocabulary? rpc-local?) and rule the absorb!
   identity check (P3) as part of the envelope contract -- must an
   answer name the request identity, and where is that enforced?
4. The serving layer: when call-out is served for remote FFI, what
   mints and holds cursors and reflection keys? Address the P2-3
   serve! idempotence requirement in the same ruling. State the
   acceptance criteria for "a VM FFI call answered remotely,
   end-to-end."
5. Migration: rpc's wire shapes just landed and were gated (slice
   5). Rule breaking-now (one commit, tests migrated) vs staged
   (translation shim, deprecate rpc shapes, remove later), and name
   the affected surfaces precisely.
6. Prerequisite ordering: which punch-list items are acceptance
   criteria of this convergence vs separable fixes that can land
   independently.

## Deliverable

A ruling, complete in your final response (you are headless; nobody
will prompt you again):

- Verdict: ADOPTED / ADOPTED WITH CHANGES / REJECTED, with reasoning
  grounded in the code.
- The target contract stated precisely enough to implement without
  a further design round: envelope shapes, id policy, error
  vocabulary, serving-layer responsibilities.
- Migration slices with acceptance criteria.
- What must NOT change and why: the yin syntax name
  (dao.stream.apply/call), the VM-side envelope construction, the
  verbatim-envelope rule, apply's open-envelope protocol.

You may read anything in the repo and run read-only git commands.
Do not edit files. Do not run test suites. Cite file:line evidence
for every claim about current code.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one of:
Verdict: ADOPTED
Verdict: ADOPTED WITH CHANGES
Verdict: REJECTED
