Created-GMT: 2026-09-29 09:50:32 GMT
Created-Local: 2026-09-29 16:50:32 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a0ec92-d475-7172-ade6-a6321090db0b (captured)
# Task: Architect ruling — dao.stream.apply independent of the concept of rpc (owner invariant)

Role: Lead System Architect

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-29 16:50:32 +07 (+0700) | Status: active | Rationale: OWNER CHOICE (verbatim selected option "gpt-6-sol — Authored the one-envelope ruling — most context, but correcting its own decision.")

Read-only; no edits; headless — your final response is the deliverable. Master 643b1ba6.

OWNER INVARIANT (verbatim, two messages, 2026-09-29):
  "dao.stream.apply needs to be independent of the concept of rpc"
  "dao.stream.apply needs to be independent of the concept of rpc because it can use a framebuffer"
Treat this as governing. It amends YOUR one-envelope ruling
(collab/1790575143000-architect-one-envelope-ruling.gpt-6-sol.findings.md), which directed: "Define distinct, stable
apply-qualified reason words for not-found, detached, ended, no-surface, oversize, and transport-error." You are now
correcting your own decision: where the invariant and the earlier ruling conflict, the invariant wins; do not defend the
earlier ruling. Keep what remains valid: rpc still uses apply envelopes as its wire values (one envelope), namespaces
stay separate, the VM request map travels unchanged, apply/correlation-id? stays some?.

Orchestrator's inventory on master (verify it; it may be incomplete):
- src/cljc/dao/stream/apply.cljc requires only dao.stream; its own words: request/response shape keys (id op args ok
  error code message outcome), append, idle, gap, handler-error, invalid-request, invalid-response, malformed-request.
  Docstring ~26: "... belongs to the RPC client, not this envelope."
- rpc/transport reasons minted under :dao.stream.apply/* OUTSIDE apply.cljc: detached (rpc.cljc, yin/repl/connect.cljc,
  yin/repl/driver.cljc), ended (rpc.cljc, connect.cljc, yin/vm/ffi.cljc [slice 3d loss error, with :yin.vm.ffi/loss
  end|gap], yin/vm/ffi/remote_serve/responder.cljc), not-found and no-surface (rpc.cljc, connect.cljc), oversize and
  transport-error (per the ruling; locate them), gap (responder.cljc as a loss marker; also an apply.cljc word — check
  whether apply's own gap is a medium-level concept or an rpc one).
- Remote FFI slices 3a-3d, the REPL driver race fix dce6282c, and q on require 643b1ba6 are all on master; read
  src/cljc/dao/stream/rpc.cljc, src/cljc/dao/stream/observe.cljc, src/cljc/yin/repl/{connect,driver,serve,adapter}.cljc,
  src/cljc/yin/vm/ffi.cljc, src/cljc/yin/vm/ffi/remote_serve/*.cljc, test files that assert these words, and
  docs/design/datom.world.md, docs/design/dao.stream.md.

Rule precisely enough to implement without another design round:
1. What "independent of the concept of rpc" requires of dao.stream.apply: which of apply's OWN words and docstrings are
   medium-neutral (valid over a framebuffer, a local pair, a remote channel) and which carry client/transport meaning
   and must leave. Is apply's serve-once! server step itself rpc-shaped (request/response pairing) or medium-neutral?
2. Where each rpc/transport reason moves (e.g. :dao.stream.rpc/* for client completion reasons, :dao.stream.remote/*
   for remote protocol errors), and what an application-level apply error-response may still carry (if anything)
   about loss. Keep the ruling's intent (distinct, stable, non-collapsed words; detached alone rebindable).
3. The VM's FFI loss error (slice 3d, :dao.stream.apply/ended + :yin.vm.ffi/loss): it reports stream end/gap observed
   by the VM on its call-out medium. Re-home it (e.g. :yin.vm.ffi/*), and keep it portable.
4. The pending rpc item: dao.stream.rpc/request! can allocate and send while its response cursor is still an anchor
   (the root of the REPL driver race fixed in the driver only by dce6282c). Rule the rpc-level fix (e.g. a
   non-allocating :dao.stream.rpc/cursor-pending outcome) in the same breaking change, and whether the driver guard
   then goes or stays.
5. Migration: one breaking commit or slices; no backward compatibility is required (dev-only repo). Name every file
   and test that changes, and acceptance tests, including a test that dao.stream.apply's namespace and docs contain no
   rpc/transport vocabulary, and that apply values work over a non-rpc medium (a framebuffer-like or plain local
   medium) with no rpc in scope.
6. OWNER decisions, listed separately.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Then: Verdict (the target contract), answers 1-6 with file:line evidence, and an ordered migration plan.
