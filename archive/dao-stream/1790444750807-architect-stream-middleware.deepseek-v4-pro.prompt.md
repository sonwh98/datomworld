Created-GMT: 2026-09-26 17:45:50 GMT
Created-Local: 2026-09-27 00:45:50 +0700
Coding-Agent: deepseek
Session-ID: 7aec5601-a998-45b8-a314-235fd90bcf23

# Task: ring-like middleware for dao.stream (encryption, capability tokens, custom transforms) and its fit with dao.stream.serve

Role: Lead System Architect

Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-09-27 00:45:50 +0700 | Status: active | Rationale: owner requirement on dao.stream middleware; resumes this model's own session

You are a HEADLESS read-only Lead System Architect resuming your own earlier session. Produce the
COMPLETE deliverable in your final response now; no questions. Do not edit files. Do not run anything.
Cite file:line for claims about docs or code.

## New owner statement (verbatim; FIXED constraint, in addition to all earlier ones)
"dao.stream should have ring-like middleware to add things like encryption, authentication/authorization capability tokens, or other custom transformation"

Earlier owner statements still bind: dao.stream.serve invariant (any dao.stream mechanically exposed
over websocket or udp, P2P, no server/client/privileged node, NAT traversal, string toy); "dao.stream
is an abstraction boundary, not a network boundary ... serve will need dao.lease". The owner does not
care about details as long as invariants hold and wants to be told what is impossible. Dev-only repo:
no backward compatibility needed, clean breaks preferred.

## Context
The converged serve design ("mirror and reflection", lease-integrated) is in the three lease-round
findings: /Users/sto/workspace/datomworld/collab/1790444057647-architect-serve-lease.claude-fable-5-1.findings.md (fullest),
.../1790444057691-architect-serve-lease.gpt-6-sol.findings.md, .../1790444057733-architect-serve-lease.deepseek-v4-pro.findings.md.
Its owner-visible caveat was: everything plaintext and ungated until authentication exists. This
statement is the owner's answer to that. Read the contract /Users/sto/workspace/datomworld/docs/design/dao.stream.md
and /Users/sto/workspace/datomworld/docs/design/datom.world.md (the six invariants incl. no raw callbacks,
no implicit control flow, no hidden global state), and, as needed, src/cljc/dao/stream.cljc (the handle
protocol), src/cljc/dao/stream/*.cljc, docs/design/dao.stream.ws.md, and dao.lease.md.

## Decide, as a ruling each (RULING, WHY in 1-3 sentences, RISK)
M1. WHAT "RING-LIKE" MEANS HERE. Ring middleware is a function wrapping a handler, composed in a chain,
    transforming the request on the way in and the response on the way out. Map that onto dao.stream
    with the fewest concepts. Is middleware a handle-to-handle wrapper (a handle that implements the
    same protocol over an inner handle, transforming values/keys and passing cursors and outcomes
    through), an interpreter/step-function over the stream, or something else? Show the smallest
    shape (a signature and one worked example, e.g. value encryption). Check it against the six
    datom.world invariants: is a higher-order wrapper compatible with "no raw callbacks", "no implicit
    control flow", "no hidden global state"? If the ring shape needs a different formulation to comply
    (e.g. declared data plus a pure step, not closures), say so.
M2. WHERE IT ATTACHES. Per-stream (wrap one handle: value transforms, capability checks on a served
    stream) versus per-channel (wrap the two-ended channel: encrypt/authenticate whole messages)
    versus both. Which does the serve design need, and where in the converged design does each sit
    (the table entry's handle on the mirror side; the reflection; the channel)? What must a middleware
    NOT do: it must not break "a served stream is the original stream": cursors, anchors, gap and
    outcome maps cross unchanged; which transformations preserve that and which (e.g. a cipher that
    changes value size, a compressor, a filter that drops elements) need a declared rule?
M3. THE THREE NAMED USES.
    (a) Encryption: value-level vs message-level, key handling, what stays plaintext (identity,
        cursors, op names) and what that leaks; whether it closes the "relay sees everything" caveat.
    (b) Authentication and authorization by capability tokens: where the token rides (a key in the
        request map? a wrapper that attaches it?), who verifies (the mirror-side middleware), what
        "no privileged node" means when a peer refuses a request, how a token relates to a
        dao.lease grant (is a lease grant a capability?), and how attribution for lease renewals
        (the gap the earlier round found) is closed.
    (c) Custom transformation: one further example (compression, redaction, or metering) to show the
        shape generalizes.
M4. PLACEMENT AND DOCUMENTS. Does this belong in dao.stream.md (the contract), a new
    dao.stream.middleware.md, or inside dao.stream.serve.md? Keep network concepts out of the
    abstraction boundary. Is anything in the dao.stream contract or the converged serve design in the
    way (e.g. handles being opaque, declared surfaces, the "no assumed graphs" rule)? What is the
    minimal contract text, if any?
M5. DELTA AND VERDICT. Changes to the converged serve design; whether serve should ship WITH
    middleware in v1 or specify only the attachment points; anything owner-visible or impossible
    (e.g. metadata leakage under encryption, key distribution being out of scope, capabilities without
    a trusted issuer); READY TO SPECIFY or the specific blocker. At most 400 words for the delta.

Be terse; do not restate earlier documents.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <same coding agent used for the run>
Session-ID: <exact Session-ID value from the header above, or the provider value>
