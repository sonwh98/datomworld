Created-GMT: 2026-09-22 16:24:59 GMT
Created-Local: 2026-09-22 23:24:59 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 7e07b6da-0624-4b87-8533-378b43c3d302 (resumed: your targets design session)
# Task: architect-targets-resolved-tuples-correction — reconsider the foreign-host source representation
Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-22 23:24:59 +07 | Status: active | Rationale: owner correction on your own committed design's section 3.1

Work in /Users/sto/workspace/datomworld (your launch directory; branch
master). docs/design/yin.vm.debruijn.targets.md is now committed. You may
edit it further. Do not edit any other file.

## The owner's correction, verbatim

Quoting the document's own line ("pipeline over the committed
`:yin.debruijn.code/*` stack image"), the owner said:

"it should not be the stack image but the transformation before:
semantic tuples to de bruijn transformation"

## What this means and what you must reconcile

Your committed document's section 3.1 made a specific, reasoned choice:
Direction B (foreign hosts) and Direction A (native/source emitters) both
consume the identity-bearing STACK IMAGE (H), never resolved tuples,
explicitly because:

1. Resolved tuples have no hash, no identity, no sharing role (stack
   design section 3.1) -- keying anything on them would give them the
   cache-key role D12 and derive-don't-persist deny them.
2. Invariant I shares executable code over the stream linker; what is
   fetched by H must be sufficient to run. If an emitter consumed
   resolved tuples, a host that fetched an image by H could not compile
   it -- it would need the named datoms and the resolver, which B6 does
   not ship.

The owner is now saying the foreign-host pipeline should target resolved
tuples (the de Bruijn encoding of the semantic tuples) instead -- the
SAME shared artifact the stack and register lowerers both already peer-
project from, per the stack design's section 3 and the register design's
section 2. This is consistent with the exact pattern this project settled
on twice already this session: a foreign host would be a THIRD peer
lowering off resolved tuples, analogous to how the register lowerer is a
peer of the stack lowerer, rather than a host that specifically inherits
the stack lowerer's `:push`/argc-shaped instruction set.

You must reconcile this against the two reasons you gave for the opposite
choice. Do not simply overwrite section 3.1 without addressing them
directly:

- If a foreign host lowers from resolved tuples, what IS the fetchable,
  verifiable, shareable unit invariant I requires? Options to consider,
  and pick or propose your own: (a) resolved tuples gain an identity
  after all (revisit whether D9/D12/derive-don't-persist genuinely forbid
  this, given a NEW consumer -- foreign-host lowering -- now exists where
  none did before; the stack design's own reasoning for "no identity" was
  argued from the consumers that existed AT THE TIME, which was only the
  stack and register lowerers, both able to regenerate resolved tuples
  locally from named datoms they already trust); (b) a foreign host still
  fetches the STACK image by H for the sharing/fetch step (invariant I
  satisfied exactly as today), then LOCALLY reconstructs resolved tuples
  by lifting the stack image back through B3's existing lift function
  (`lift(image, side-table) -> named datoms`, already designed) and then
  re-running the SAME `resolve` the stack lowerer uses, before running its
  own resolved-tuples-to-host lowering -- i.e., fetch-by-H stays exactly
  as invariant I requires, but the foreign host's OWN lowering pipeline
  starts one step further upstream than the stack image, reusing lift
  plus resolve rather than decoding stack-specific instruction shapes; (c)
  something else you judge better. Give your own reasoned answer, do not
  just present options neutrally.
- Whichever you choose, be explicit about what changes in section 3.1,
  3.6 (the shared emitter front end, currently described as decoding the
  STACK image into a target-neutral body form -- does this front end now
  operate on resolved tuples instead, and if so does "decode an image
  into basic blocks with static stack effects" even still make sense, or
  does a resolved-tuples-based front end look completely different,
  closer to how the stack lowerer's OWN flattening walk works, since
  resolved tuples are tree-shaped, not already-flattened?), section 5
  (the Rust worked example -- T1's host currently consumes wire bytes of
  the stack image; does it now consume resolved tuples, or does it stay
  as-is per option (b) above with a new upstream stage before it?), and
  the phase table in section 7 (does T0, the conformance kit, need to
  change what it exports?).
- State plainly whether this changes T-D2 (currently: "Direction B
  consumes canonical image bytes as-is... Direction A consumes an
  identity-bearing executable image... never resolved tuples or named
  datoms") and, if so, update that DECIDED item's text, do not leave it
  contradicting the rest of the document.
- If you conclude the ORIGINAL choice (consume the stack image) was
  actually correct and the owner's framing doesn't hold up under the
  identity/fetchability constraint, say so plainly and explain exactly
  why, citing the specific place your reasoning survives scrutiny. Do not
  change the document to agree with the owner if you don't actually
  agree; a wrong correction accepted uncritically is worse than pushback,
  and this project's own history this session includes exactly that
  happening in the other direction (an architect's blocking finding was
  correct, an earlier pushback attempt against it was wrong) -- weigh
  this correction with the same rigor, not automatic deference.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: your conclusion (resolved tuples, the stack image, or a hybrid, and
why); exactly what you changed in the document and where; anything still
needing the owner's decision.
