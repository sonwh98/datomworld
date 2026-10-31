Created-GMT: 2026-09-22 19:16:13 GMT
Created-Local: 2026-09-23 02:16:13 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 341722e7-dd66-4583-996a-da14eaaeb56d (resumed: your register-VM design session)
# Task: architect-linker-b4-continuation-transport — three related design gaps, resolved together
Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-23 02:16:13 +07 | Status: active | Rationale: owner asked to "mob with team.md" on the remaining open questions overnight, for review in the morning; this is the design portion, review is dispatched separately once this lands

Work in /Users/sto/workspace/datomworld (your launch directory; branch
master). You may edit docs/design/yin.vm.debruijn.stack.md (B6 section
only) and docs/design/yin.vm.debruijn.register.md (R5 section, and
section 5's continuation-lifting mention, only). Do not edit any source
file. If you conclude a fourth document is the right home for the
continuation-transport design (a shared concern, not obviously either
VM's alone), say so and propose where, but do not create it without
naming it clearly in your report first.

This is genuinely three related design gaps that surfaced from committing
to R4 unconditionally (docs/design/yin.vm.debruijn.register.md's DECIDED
1, dated this session) -- work through them together since they interact,
but keep each one's resolution clearly separated in your report.

## Gap 1: the linker (B6 for stack, R5 for register) should be built on

## dao.jing, not raw dao.stream

The owner's own words: "the linker is based on dao.stream so the cost of
building it should not be high because dao.stream is already built. it is
just an application of dao.stream to move code across a network" --
followed by, unprompted, "for the linker protocol, as off top of my head
idea, it should use dao.jing over dao.stream."

Verified this session, with file:line citations, before this brief was
written (trust this, it was checked against the real code, not guessed):

- `dao.jing.dht` (`src/cljc/dao/jing/dht.cljc`) already implements
  fetch-by-content-hash with verify-before-trust. Its `make-get`
  (dht.cljc:209-232): reads the local content store first
  (`jing/get local address`); on miss, looks up DHT peers nearest the
  content hash, fetches from each, and -- the exact discipline B6's own
  spec already calls for ("The receiver hashes those bytes before
  decoding") -- checks `(= address (jing/segment-key (:value res)))`
  BEFORE accepting the fetched value; on match, re-materializes locally
  via `jing/materialize!` and throws if the freshly-computed address
  disagrees with the one requested ("fetched content cached under the
  wrong address"). This is verified, working code today, not a proposal.
- Content addresses in `dao.jing` are `:segment/sha256-<64hex>`
  (`src/cljc/dao/jing.cljc`'s `segment-key`/`content-hash`/
  `canonical-bytes`) -- the same hash algorithm and, modulo the
  `:segment/` namespace prefix, structurally the same shape as H and R
  (both `sha256` hex strings per B1's `image-hash` and this design's
  `register-hash`).
- `dao.jing` is layered on `dao.stream`, not a sibling: `dao.jing.remote`
  (`src/cljc/dao/jing/remote.cljc`) is explicitly "Remote content adapter
  over DaoStream v2" and requires `dao.stream.apply`, `dao.stream.rpc`,
  `dao.stream.ws` directly. The owner's framing ("dao.jing over
  dao.stream") matches the actual layering exactly.
- `docs/design/yin.vm.debruijn.stack.md`'s B6 section and
  `docs/design/yin.vm.debruijn.register.md`'s R5 section currently
  mention dao.jing NOWHERE. B6's current text ("a responder process holds
  an explicit value mapping H to image bytes or queries an H-to-address
  datom chosen by its composition") is compatible with a `dao.jing.dht`
  handle but was written generically, before this connection was made.
  This is a genuinely new design decision for you to make, not something
  already implied by the existing text.

Design, for both B6 and R5 together as one linker discipline (they should
almost certainly share an implementation parameterized by format, not be
two hand-written mirrors -- decide and justify):

1. Does H/R become the `dao.jing` content address directly (i.e. is
   `image-hash`/`register-hash`'s sha256 output reused verbatim as the
   `dao.jing` segment key, modulo the `:segment/` prefix), or does a
   thin translation layer sit between them? State the exact relationship.
2. `dao.jing.dht`'s `get` verifies CONTENT integrity (the bytes match
   their own hash) but has no notion of yin.vm-specific semantic
   validity -- B1's `image-defect` validator, the receiver closure check
   over `:load-free` operands (D11/D15), and the register validator's
   own rules are NOT things `dao.jing.dht` does or should do. State
   precisely where the boundary sits: `dao.jing.dht/get` (or whatever
   its actual call site becomes) hands back verified-by-hash bytes; B6/
   R5 then run the existing format validator on those bytes before
   anything executes them. Confirm this composition doesn't duplicate or
   weaken either layer's own check.
3. What is genuinely NEW work versus reused wholesale? Your honest
   estimate of how thin B6/R5 actually are now, given `dao.jing.dht`
   already does the hard parts (peer lookup, fetch, hash verification,
   local caching). Name the remaining new surface precisely (e.g.: a
   decoder from dao.jing's raw bytes to the yin.vm image shape, wiring
   the format validator after fetch, the "host may refuse R and request
   H instead" composition rule already decided in R5's DEFERRED-turned-
   DECIDED text, and test fixtures) rather than a vague "should be
   cheap."
4. `dao.jing.remote`'s client/server split (`create-content-dht` wraps an
   `IDhtNet` transport) -- does B6/R5 need its own responder/server side
   at all, or does the existing dao.jing DHT responder already cover it
   once yin.vm images are materialized as dao.jing content? State this
   plainly; if a yin.vm-specific responder concept is still needed (e.g.
   to answer "do you have H" before the DHT's own peer-lookup happens),
   say why dao.jing's existing responder path doesn't already cover it.
5. Rewrite B6's box in `yin.vm.debruijn.stack.md` and R5's box in
   `yin.vm.debruijn.register.md` to reflect this: file boxes, dependency
   list (does this now depend on `dao.jing` and `dao.jing.dht` being
   available/stable, and is that a real dependency risk worth naming),
   and completion criteria rewritten for what's actually new work versus
   what's inherited from dao.jing's existing guarantees.
6. Sequencing consequence: does R5 still need to be built AFTER B6 (as
   currently framed, "same shape as B6"), or does building both against
   a shared dao.jing-based linker core mean they can be built together or
   even R5 slightly ahead, since the discipline itself is now dao.jing's,
   not something original to B6 that R5 was waiting to copy? State your
   conclusion plainly.

## Gap 2: B4 sequencing

R4's own box (register.md, already committed) says its effects tier
"uses the same... continuation contracts as B3, including the engine seam
B4 implements." B4 (`docs/design/yin.vm.debruijn.stack.md`'s own B4
phase, "effects and continuations" for the STACK VM) has not been
dispatched or started at any point this session -- it has sat as "next
phase after B3" since B3 merged. Now that R4 is committed (not gated),
B4 is a real prerequisite for R4's effects tier being buildable at all,
not an independent nice-to-have for the stack VM alone.

State the real dependency order across B4, B6 (as redesigned above), R2,
R4, and R5, now that gap 1 may also have changed R5's relationship to B6.
Does anything here suggest B4 should be prioritized immediately (dispatch
it next), or is there a defensible reason to sequence R0/R1(done)/live-
set(in progress)/R2 first and let B4 land in parallel on its own track
since it only gates R4's EFFECTS tier, not R4's pure tier (which R1 alone
already supports per your own earlier ruling)? Give a recommended dispatch
order, not just a dependency graph -- the owner wants to know what to
authorize next, not just what depends on what.

## Gap 3: register continuation cross-model transport

Design section 5 (both documents) and DEFERRED note this is undesigned:
how a register-format continuation and a stack-format continuation
interoperate for park/resume across the two VMs, needed once R4's effects
tier (which needs B4's engine seam per gap 2) actually exists. This was
low-priority while R4 was optional; it no longer is, since R4 is
committed.

Design the actual mechanism. You have real material to work from: B3's
continuation shape (the stack VM's `{:segment :pc :frames :free-env
:stack :continuation}`), the register kernel's stated state shape (R4's
box: `{:image :pc :registers :frames :free-env :continuation :store
:status :primitives :modules}`), and the engine seam gap 2 concerns
(`yin.vm.engine.md`'s restore-fn/park-entry-fns convention, already
designed this session). At minimum resolve: can a park recorded under one
VM be resumed under the other directly, or does crossing require an
explicit lift (the way B6's own image-level lift already exists for
executing a fetched image under the semantic VM)? If a lift is required,
what carries the frame/register-file translation, and is it lossy in
either direction (can a register file's dead-register information, if
gap 1's live-set design is in scope for you to reference, safely be
discarded when lifting to a stack continuation's operand stack, or does
information genuinely not survive the round trip)? State the answer even
if it is "cross-model continuation transport is not supported, only
same-model resume is," as long as that is a deliberate, justified
decision rather than a silent gap.

## Deliver

Edit the two named design documents (B6's box, R5's box, section 5's
continuation-lifting mentions in both). If gap 3's design needs its own
section rather than fitting inside section 5, add it there, in whichever
document you judge is the more natural home (likely the register document,
since register continuations are the newer, less-precedented half of the
interop question) with a cross-reference from the other.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then, for each of the three gaps: your decision, the reasoning, exactly
what changed in which document, and anything still needing the owner's
decision. End with your recommended dispatch order for what comes next
(B4, B6/R5 implementation, R2, the continuation-transport implementation),
since the owner wants to authorize the next phase from this report.
