Created-GMT: 2026-09-26 17:50:22 GMT
Created-Local: 2026-09-27 00:50:22 +0700
Coding-Agent: claude
Session-ID: 506ecf77-3b05-43cf-9579-ad73759f7aa6

# Task: capability-agnostic seam so a future ShiBi capability system integrates with dao.stream middleware and serve

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-27 00:50:22 +0700 | Status: active | Rationale: owner correction that authorization/authentication belong to the unspecified ShiBi capability system; resumes this model's own session. gpt-6-sol held in reserve to gate the eventual spec (budget unknown).

You are a HEADLESS read-only Lead System Architect resuming your own earlier session. Produce the
COMPLETE deliverable in your final response now; no questions. Do not edit files. Do not run anything.
Cite file:line for claims about docs or code.

## New owner statement (verbatim; FIXED; it CORRECTS the last round)
"authorization/authentication are the job of shibi capability system which isn't even spec out yet. the design of the shibi capability system must integrate with middleware for dao.stream.serve"

Earlier owner statements still bind: the dao.stream.serve invariant; "dao.stream should have ring-like
middleware to add things like encryption, authentication/authorization capability tokens, or other
custom transformation"; dao.stream is an abstraction boundary, not a network boundary; serve will need
dao.lease. The owner does not care about details as long as invariants hold and wants impossibilities
stated. Dev-only repo, no backward compatibility, clean breaks preferred.

## What went wrong last round
The middleware round designed capability tokens inside the middleware/serve design: deepseek made a
lease grant the capability; fable put a `:dao.stream/cap` open key, an issuer-equals-verifier HMAC
token, a `require-cap`/`with-cap` pair and a new `:dao.stream/refused` outcome; codex (not in this
round) said a lease grant is NOT a capability and proposed a `:dao.stream/unauthorized` outcome.
All of that is capability-system design, which belongs to ShiBi, which is not specified yet. Read the
last-round answers: /Users/sto/workspace/datomworld/collab/1790444750726-architect-stream-middleware.claude-fable-5-1.findings.md,
.../1790444750767-architect-stream-middleware.gpt-6-sol.findings.md, .../1790444750807-architect-stream-middleware.deepseek-v4-pro.findings.md
(the lease-round findings 1790444057*-architect-serve-lease.* hold the converged serve design).

## What ShiBi is, as far as the repo says (verify; it is unimplemented)
docs/bootstrap.md:65 ("Implement the ShiBi token system natively across all hosts. (Status: Not implemented yet)");
README.md; public/chp/shibi.chp (capability token system and economic primitive; anyone can issue
infinitely many purpose-specific tokens; stream permissions, computational budgets, agent migration
credentials, API rate limits); docs/design/dao.space.security.md:33 ("Shibi (Macaroon-style) design, the
`:yin/capability` attribute ... attenuatable, offline-verifiable, revocable caveats"); docs/design/dao.stream.discovery.md:181-190
(unresolved: capability or currency; a currency needs double-spend resolution and global consensus);
docs/design/macro-design.md:37,71; docs/design/daostream-udp-design.md:343 (descriptors carry `:shibi`
tokens); docs/design/yin.repl.link-policy.md:205 (ShiBi not needed for lease attribution when the shell
owns the media). Do NOT design ShiBi. Design only what dao.stream middleware and dao.stream.serve must
offer so that a capability system of that kind can plug in later without changing them.

## Decide, as a ruling each (RULING, WHY in 1-3 sentences, RISK)
S1. BOUNDARY. List, item by item, what the middleware and serve specs own versus what the future ShiBi
    spec owns. For each capability-shaped proposal in the three last-round answers (lease grant as
    capability, an open key carrying the token, issuer/verifier HMAC scheme, attenuation/delegation,
    revocation, the refusal outcome and its name, bearer-token replay protection, token-to-lease
    binding), say KEEP as a generic seam, MOVE to the ShiBi spec, or DROP.
S2. THE SEAM. The smallest capability-agnostic interface: (a) how a credential of unspecified format
    travels with a request (a reserved open key? per-request or per-channel?); (b) where an authorize
    step sits (mirror-side middleware, with the verifier supplied as composition data, a pure function
    of credential, request and context returning allow or refuse-with-reason); (c) where a credential is
    attached (reflection-side middleware); (d) how a verifier obtains context it needs (a ledger,
    revocation state, time via dao.lease ticks) without hidden global state, as explicit composition
    streams. Show signatures and one worked example with a TRIVIAL stand-in policy (for example an
    allow-list keyed on the channel) so the seam is exercised without ShiBi.
S3. WHAT THE SEAM MUST NOT FORECLOSE. Check the seam against what ShiBi is described as needing:
    attenuation and delegation (a credential that is a chain), offline verification, revocation,
    caveats bounding op/identity/time/budget, tokens issued by any peer with no privileged issuer,
    spendable or metered use (a stateful budget) without hidden state, migration credentials for
    agents. Say for each: supported by the seam, needs a seam extension (name it), or unresolved because
    it depends on the open capability-versus-currency decision. The middleware must not depend on that
    decision.
S4. REFUSAL. What the generic refusal is in the dao.stream contract, named WITHOUT the word capability
    (the contract must stay capability-free), whether it needs its own outcome under OD-1's rule, and how
    the reflection presents it.
S5. LEASE ATTRIBUTION. Confirm lease attribution (the gap from the lease round) is closed by per-author
    media alone and does NOT depend on ShiBi, per yin.repl.link-policy.md:205 and the lease round.
S6. DELTA AND VERDICT. The corrected middleware design: what serve ships in v1 (the seam plus a trivial
    policy, not a capability system), what is explicitly deferred to the ShiBi spec, the document
    placement (dao.stream.middleware.md, serve.md, and a stub or pointer for ShiBi), and READY TO SPECIFY
    or the blocker. At most 400 words for the delta. Owner-visible: anything that cannot be decided
    until ShiBi is specified, and the recommended order of work.

S7. NAME. Owner question, verbatim: "can you suggest a better name than dao.stream.serve? what do you think of dao.stream.peer ?"
    The orchestrator's view (challenge it): "serve" names one half and one role (a server), against the
    owner invariant of no server or client; "peer" names the symmetric party but is an entity, and the
    converged design deliberately has no protocol-level peer (a peer is a channel end; no peer ids), so
    dao.stream.peer risks implying identity, membership and discovery, which live in dao.stream.discovery.
    Candidates: dao.stream.remote (matches the converged descriptor type :dao.stream/remote; but
    dao.jing.remote exists and its transport half is being retired), dao.stream.mirror (the mirror step
    plus reflection), dao.stream.link, dao.stream.channel, dao.stream.wire, dao.stream.expose,
    dao.stream.peer, dao.stream.serve. Pick ONE, in one sentence of WHY, and say which of the others you
    would reject and why. Name the existing namespaces it would collide with or shadow
    (src/cljc/dao/stream/*, dao.jing.remote, dao.stream.serving). The owner only cares that the name
    does not contradict the invariant.

Be terse; do not restate earlier documents.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <same coding agent used for the run>
Session-ID: <exact Session-ID value from the header above, or the provider value>
