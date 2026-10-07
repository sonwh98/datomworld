Created-GMT: 2026-09-22 09:11:16 GMT
Created-Local: 2026-09-22 16:11:16 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 341722e7-dd66-4583-996a-da14eaaeb56d (resumed: your register-VM redesign session)
# Task: architect-b2-resolve-then-linearize — apply the owner's decision to the stack design doc
Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-22 16:11:16 +07 | Status: active | Rationale: resumed session, same context as the register-VM redesign you just finished; this is the stack-design-side edit you flagged as deferred to the owner

Work in /Users/sto/workspace/datomworld (your launch directory; branch
master). You may edit docs/design/yin.vm.debruijn.stack.md only. Do not
edit docs/design/yin.vm.debruijn.register.md again (it is done) or any
source file.

## The owner's decision

You flagged, in your register-VM redesign, that refactoring B2 into
`resolve` followed by a stack linearization of the resolved tuples "edits
the stack design's section 3 and B2 box" and deferred it to the owner. The
owner chose: refactor B2 now, while it is uncommitted, into resolve-then-
linearize (not the "leave B2 fused, rely on the address law" alternative).

## What to do

Edit docs/design/yin.vm.debruijn.stack.md's section 3 (lowering and scope)
and the "B2: named-datom lowerer adapter" phase box to specify the new
two-stage architecture precisely enough that an implementer can build it
without guessing:

1. B2 becomes two stages: a `resolve` stage producing the resolved tuples
   (as you defined them in yin.vm.debruijn.register.md section 2.1 -- the
   named `:yin/*` datoms with `:variable` resolved via `resolve-name` and
   `:lambda` carrying arity instead of params, side table for binder
   names/provenance, everything else exact), and a `lower-stack` stage
   consuming resolved tuples and producing the existing B1-shaped stack
   image. State plainly that this REMOVES B2's current vector-level scope
   reconstruction (`closure-body-ranges`, `layout-conforms?`, `body-owner`,
   `chain-of` -- named in your register redesign's section 2.2) since that
   machinery existed only because resolution was previously placed after
   linearization.
2. State where `resolve` and `lower-stack` each live: is `resolve` its own
   new namespace shared by both the stack and register lowerers (matching
   how the register design's section 2.1 describes "a new resolver
   namespace"), with `yin.vm.debruijn-linearize` keeping only
   `lower-stack` plus the lift/adapt public surface B2's own completion
   criteria already require? Decide and state the file box precisely
   (which namespace, which file, what moves out of
   src/cljc/yin/vm/debruijn_linearize.cljc and where it goes).
3. Note that `lower-stack` must reproduce the named linearizer's exact
   flattening walk order, occurrence expansion, and body layout itself
   (since `yin.vm.linearize/lower` cannot consume resolved tuples), and
   that structural-comparison-with-the-named-vector becomes a required
   test rather than following by construction, as your register redesign
   already noted.
4. Keep the completion criteria from B2's existing phase box (determinism,
   every node/opcode handled, lexical validation, structural comparison,
   round trips, the lift correctness law) but restate them against the new
   two-stage shape where they refer to the old fused structure.
5. Do not touch B0, B1, B3, B4-B7, or any decided section (D1-D16) unless
   this change genuinely requires it -- say explicitly if it does not.

Keep the project's design-doc conventions this document already uses.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: exactly what you changed in section 3 and the B2 phase box, the file
box for `resolve` (new namespace/file) and `lower-stack`
(src/cljc/yin/vm/debruijn_linearize.cljc, reduced scope), and anything
still needing the owner's decision (e.g. the resolved-tuple attribute
vocabulary/namespace you deferred to R0 in the register design).
