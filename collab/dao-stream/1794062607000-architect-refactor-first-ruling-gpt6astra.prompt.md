Created-GMT: 2026-11-07 12:4x GMT
Created-Local: 2026-11-07 19:4x +07
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: Architect ruling — refactor-first vs baseline-first for the yin.repl/DHT boundary (owner request for a second opinion)

Role: Lead System Architect

Implementers:
- Model: gpt-6-astra | Assigned: 2026-11-07 19:4x +07 | Status: active | Rationale: owner-requested architect ruling; codex family independent of the muse family that authored R1-R7

Perform a read-only architecture review of the sequencing decision below, per
docs/agents/roles/architect.md. Do not edit files.

## Read first

- docs/design/datom.world.md (foundational invariants)
- docs/design/dao.jing.md, docs/design/dao.jing.dht.md, docs/design/dao.stream*.md (the storage/transport layering)
- src/cljc/yin/repl/dht.cljc (1139 lines of DHT composition INSIDE yin.repl — the disputed code)
- src/cljc/yin/repl.cljc (:876-976), src/cljc/yin/repl/main.cljc (:886-1077), src/cljc/yin/repl/index.cljc (:369, :146-170, :363-411)
- src/cljc/dao/space/index.cljc, src/cljc/dao/data/btree.cljc (where R1-R3 live)
- The artifact trail: collab/dao-space/1794056541000 (owner byte-size directive),
  collab/dao-space/1794057586000 (muse architect verdict R1-R7),
  collab/dao-stream/1794057236000 and 1794057461000 (owner addenda: stream-wide
  bound; dao.stream stays a byte stream, chunking is transport-layer),
  collab/yin-repl/1794061973000 (owner directive: yin.repl knows no DHT)

## The dispute to rule on

OWNER POSITION (latest): master is broken because of the DHT dependency in
yin.repl (a framer that must guess frame sizes because the transport knowledge
is embedded in the engine, not derived from the composition). Getting green by
keeping the dependency and patching code that assumes it (fix 5's
:branching-factor 64 in yin/repl/dht.cljc, the cb2cbe11 :repair-slots commit,
budget reads in index.cljc:369) entrenches the bad shape and makes the
refactor harder. Therefore: refactor-first — move the DHT composition out of
yin.repl into a composition layer that derives the frame budget from the
transport; the dht-family reds (ack collapse, dart ticker-idle, node
composition failure) are fixed BY the refactor. Only boundary-neutral fixes
(php frontend install, compile-EOF pin, golden fix, stack-size prepend,
recursion-error V8 sizing on Linux) merge before/during the refactor.

ORCHESTRATOR POSITION (conceded, recorded for your review): baseline-first —
green the scoped test set under the current architecture, then refactor. The
orchestrator conceded this was wrong for the dht-family reds (a green state
built on seam patches is not a stable baseline; the patches are load-bearing
for the coupling being removed) but kept the baseline value for the
boundary-neutral fixes.

MUSE PRIOR VERDICT (R1-R7, collab/dao-space/1794057586000): sequenced byte-cut
implementation on nightly-reds-fix NOW, before any refactor — R7 explicitly
chose that. Your ruling must explicitly confirm or overturn R7 and say what
of R1-R6 survives the refactor-first sequencing.

## Rule on

1. SEQUENCING: refactor-first vs baseline-first, for (a) the dht-family reds,
   (b) the boundary-neutral reds, (c) the R1-R3 dao.space work. What merges,
   in what order, and what verification gates each step.
2. THE COMPOSITION LAYER'S API: what does the relocated module (today's
   yin/repl/dht.cljc) look like from the engine's side? Specify the minimum
   contract: what yin.repl exposes (publish requests, content store handle,
   manifest addresses), what the composition drives (announce, head-follow,
   step/banner), and where the frame budget derivation lives (R5's boundary).
   Is index.cljc:369's branching-factor read restatable as a store-declared
   capability without a schema change?
3. INVARIANT RISK: what breaks if the 1139-line relocation happens while the
   scoped test set is partially red (dart ticker-idle, node composition)?
   Name the specific invariants (frontier gate, restore Settings threading,
   repair cadence, cross-host parity) that need pinning BEFORE moving code.
4. R-SET ADAPTATION: which of R1-R6 survive as-written into the refactor-first
   world, which move into the composition layer's design, and which die.
5. NIGHTLY EXPOSURE: what triage protocol applies while master's dht-family
   lanes are red during the refactor window (known-seam reds vs new reds).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report: severity | file:line | invariant/evidence | recommended
correction, plus your explicit ruling on points 1-5, and the requested
properties that passed review.
