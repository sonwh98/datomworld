Created-GMT: 2026-09-22 20:37:31 GMT
Created-Local: 2026-09-23 03:37:31 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: pending (new session, caller-generated; capture thread.started.thread_id)
# Task: architect-hash-registry-plan — design a plan to make dao.jing/yin.vm hash-algorithm-agile (BLAKE3 default, multihash-style)
Role: Lead System Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-23 03:37:31 +07 | Status: active | Rationale: orchestrator seat is near its own usage limit until ~4am local; owner instructed routing this plan-creation task to gpt-5.6-sol instead

Work in /Users/sto/workspace/datomworld (read-only; this is a design task,
not an implementation task -- do not write any file, do not run git
add/commit). Produce the complete plan as your final report text; the
orchestrator will decide whether and where to commit it as a doc.

## Context

datom.world's content-addressing system (`dao.jing`) and its de Bruijn VM
work (`yin.vm`) currently hash everything with SHA-256, one algorithm,
hardcoded. The owner wants a plan to make BLAKE3 the default hash
algorithm across all three hosts (JVM/CLJS/ClojureDart, i.e. `:clj`,
`:cljs`, `:cljd` in this codebase's reader-conditional style), while
supporting multiple hash algorithms the way IPFS's multihash format
does (self-describing algorithm, not a hardcoded single choice).

## Read first, in full

- src/cljc/dao/jing.cljc, in full -- especially `sha256`/`sha256-bytes`
  (lines ~271-299), `content-hash`/`canonical-bytes` (~306-333),
  `segment-key`/`segment-address?`/`segment-hash` (~337-366), and
  `materialize!`/`get` (~368-433). This is the ONE file that currently
  owns every hash primitive in the system.
- docs/design/dao.jing.md, in full -- especially the "Canonical
  encoding" section (~176-197) and the "Content addressing is
  implemented, transitionally" section (~405-450). There is an existing,
  OPEN, unrelated migration already documented here: the
  order-normalized-print encoder is transitional and will be replaced by
  a pinned CBOR canonical byte encoding, which will ALSO change every
  content-hash/segment-key/address in the system when it lands. Your
  plan must explicitly address sequencing against this existing item,
  not ignore it.
- src/cljc/yin/vm/debruijn_code.cljc lines ~520-546 (`image-hash`, B1's
  H) and the equivalent register-format hash function in
  src/cljc/yin/vm/debruijn_register_code.cljc (search for
  `register-hash`/`image-hash`) -- these call `jing/sha256` directly on
  encoded bytes to produce a bare hex string, NOT a `:segment/...`
  address. This is a second, separate consumer of the hash primitive,
  with its own frozen golden-pinned contract-version tests
  (test/yin/vm/debruijn_register_contract_test.cljc and its stack-format
  sibling) that would need explicit handling if its algorithm changes.
- Grep for every call site of `jing/sha256`, `jing/content-hash`,
  `jing/segment-key`, `jing/segment-address?`, `jing/segment-hash`
  across src/cljc to confirm (or correct) the claim that these are the
  ONLY hash entry points other modules use -- no other module should be
  hashing directly or hardcoding the "sha256" string literal. If you
  find one that does, that is a real finding for your plan to name.

## An orchestrator-drafted starting point exists -- use it as a baseline to critique, not a spec to transcribe

The orchestrator (a different session) has already drafted a plan
covering: a `:segment/<algo>-<hex>` generalization of the existing
`:segment/sha256-<hex>` address format (chosen because IPFS's actual
binary varint multihash format does not fit this project's constraint
that addresses must print/read as valid EDN -- `segment-key`'s own
docstring states this constraint explicitly); a small closed algorithm
registry in dao.jing.cljc; BLAKE3 as the new default with SHA-256 kept
as a permanently supported legacy algorithm (no forced rehash, since
content addressing means old data stays valid under its own address
forever); a phased rollout (registry+primitive with no default change,
then flip the default, then portability/maintenance hardening, then
docs); and a recommendation to freeze yin.vm's H/R image-hash algorithm
independent of dao.jing's default (to avoid re-pinning the de Bruijn
VM's frozen golden test values mid-epic) unless a contract-version bump
is already forcing a re-pin for another reason.

A prior portability spike (a separate delegate session, findings you
should treat as evidence, not re-verify from scratch unless you have
reason to distrust them) found: all three hosts have a native-free,
byte-identical BLAKE3 option -- `io.github.rctcwyvrn/blake3` 1.3 (JVM,
pure bytecode, MIT, but its Maven release is from 2020 -- stale),
`@noble/hashes` 2.4.0 (Node/CLJS, pure JS, MIT, actively maintained),
`blake3_dart` 1.0.0 (Dart/CLJD, pure Dart, MIT, single release Jan 2026,
short track record, verified against official BLAKE3 test vectors). All
three plus an independent Rust-to-WASM cross-check produced the
identical digest for the same fixed input string. The Dart host
currently has NO external crypto dependency at all (its SHA-256 is
hand-rolled in dao.jing.cljc itself) -- BLAKE3 would be its first
external crypto package.

Your job: independently verify or challenge this baseline against the
actual code you read, then produce your own complete, opinionated plan
-- agree with the baseline where it holds up, diverge from it (with
reasons) wherever your own reading of the code suggests a better
design, a missed call site, a missed risk, or a scoping mistake.

## What your plan must cover

1. The exact address-format change (or your alternative, with reasons
   for diverging from the `:segment/<algo>-<hex>` baseline if you do).
2. Exactly which functions in dao.jing.cljc change and how (signatures,
   default-algorithm handling, backward parsing of existing sha256
   addresses).
3. Whether any call site outside dao.jing.cljc needs to change (name
   them if so; this should be close to zero per the centralization
   claim above -- confirm or refute that claim with evidence).
4. Explicit disposition for yin.vm's H/R (image-hash/register-hash):
   your own recommendation, not just the baseline's.
5. Sequencing against the open CBOR canonical-encoding migration in
   docs/design/dao.jing.md -- your own recommendation on order, with
   reasons.
6. A phased rollout with concrete completion criteria per phase, in
   this project's own style (see docs/design/yin.vm.debruijn.register.md
   phases R0-R5 for the house style of phase boxes, if useful as a
   reference for tone/structure -- do not copy its content, this is a
   different subsystem).
7. Library choices per host (you may accept the spike's findings or
   name better alternatives if you know of any), with explicit
   maintenance-risk callouts (the JVM library's staleness in particular).
8. What the plan explicitly does NOT attempt, and why (scope
   discipline -- this codebase's own convention, see
   [[project_no_backward_compat_needed]]-style reasoning: no forced
   migration, no IPFS binary format, no scope creep into CBOR).

## Never

Do not write any file. Do not propose implementation code beyond small
illustrative signatures if useful. Do not touch anything outside this
plan -- no side critique of unrelated design docs unless it is directly
load-bearing for this plan's sequencing recommendation (e.g. the CBOR
item, which is explicitly in scope above).

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: <your thread id>
Then the complete plan, written as a standalone document (so the
orchestrator can promote it directly into docs/design/ if it holds up),
followed by a short section titled "Divergences from the baseline"
naming every place you disagreed with the orchestrator's draft and why.
