Completed-GMT: 2026-10-10 17:40:51 GMT
Completed-Local: 2026-10-11 00:40:51 Asia/Bangkok

(The host clock read about eight hours earlier than the prompt's Created
stamp when this report was written; the times above are the clock's.)

# Report: phase-5 derivations design rewritten for route (a), lower from A

Edited: `docs/design/yin.vm.semantic-register-vm.derivations.md` only,
rewritten whole (1316 lines). Frozen design, linker and UCF documents,
source, tests and the C4 track untouched; nothing staged or committed.

## Removed

- Route (b) entirely: `resolve-vector` emitting resolved tuples, the
  adapter over `lower-stack`/`lower-register`, the byte-equivalence
  gates, the "equivalence as blocking gate" decision procedure and its
  §4.5 record table, the b2/r2 retain-or-re-version machinery, the
  R1-versus-§8.2 comparison and its lemmas, the byte-neutral 5c switch
  of `relowered`/`publish-closure!`, and the lift-contract and
  profile-string owner questions.
- The previous F8 and F10 (adapter route; lift target hashed) and the
  reviewer-rulings block that recorded route-(b) decisions.

## Added

- **§1 the resolved vector**: `:var` → `:load-bound`/`:load-free`,
  `:closure` → arity, over the validated owner tree; a stage value with
  no identity; `code/parse` trees and `analysis/analyze` index it
  directly.
- **§2 A → R**: the frozen §8.2 linear scan made complete: intervals
  from syntactic definitions and `analysis/live` points (widened over
  layout syntax; §9.1 F8 has the amendment), the scan with `end < p`
  expiry and lowest-free assignment, the structural slot, emission into
  the unchanged `:yin.debruijn.register/*` format, `live` filled by the
  format's own `body-liveness`, with the agreement of the two liveness
  analyses stated as a checked property rather than a theorem.
- **§3 A → H**: one emitter over the parse trees (operator, push,
  operands with pushes, call, labels, bodies FIFO) into the unchanged
  `:yin.debruijn.code/*` format; no adapter, no second parse.
- **§4 determinism and the facts A omits** (finding below).
- **§5 the lexical-address law**: `addresses(A)` anchor, four legs, the
  old resolver as a fifth leg until cutover through a walk that mirrors
  definition lowering.
- **§6 verification**: the execution oracle (old and new pipelines on
  the same kernels, `native-normalize`, B0 and register corpus runtime
  rows with the twenty non-value rows compared on their throws, the C4
  linked programs through a locally built backend map over the public
  harness functions, read-only), validators and liveness checks on every
  output, eight hand-derived golden rows, determinism checks.
- **§7 scanners**: carried over from the reviewed design (codex rounds
  1-3 fixes intact), restated so the document stands alone.
- **§8 regeneration**: the table of every constant that moves, when,
  and the mechanical check (recompute from the printed image with the
  public hash function; manifest diff for the C4 golden; descriptors
  unchanged, so `golden-descriptor-hash-test` keeps passing); what keeps
  the old lowerers alive until phase 8 (production callers in
  `linker.cljc:2142-2160, 2440-2449`, `publish.cljc:159-160`,
  `repl.cljc:229`, the already-published manifests, and the oracle).
- **§9 findings**: F8-F14 against the frozen design with exact
  replacement text; §9.2 the unitarity conflicts in `linker.md:216-222`
  (by-identity flow names no tree), `:1776-1783` (`:trusted` never checks
  the tree is reachable), `:1984-1987` (host modules with the tree
  absent), UCF `:511` and `:528-536` (`:yin.k/carried` and the resolution
  order name no tree), each with proposed amendment text; `linker.md:225-232`
  judged consistent (retrieval, not reconstruction).
- **§10 slices** 5a/5b/5c with numbered acceptance lists; no production
  switch in 5c; §11 owner questions: none; §12 portability.

## Hand-derived numbers and their verification

All derived by hand from the A goldens in `corpus.cljc` and the §2-§3
rules, twice; `L` cross-checked against `analysis_test.cljc:131-139`
where it pins the same sets (`:worked-example` `L(4)={1,2}`, `L(5)={1,2,5}`).
No code was run. Hash strings are deliberately not written; the
implementer fills them from the printed image and the reviewer recomputes
them (§8.1).

| Program | R result (registers) | Notable |
|---|---|---|
| `(+ 1 2)` | `[[:load-free 0 +] [:const 1 1] [:const 2 2] [:call 3 0 [1 2] false []] [:halt 3]]` (4) | result slot `T3`, unlike R1's `T0` |
| `(f (g x) y)` | `f=0 g=1 x=2 (g x)=3 y=1 result=2` (4); inner `live [0]`, outer `[]` | matches codex's round-1 confirmation of the §8.2 scan; `live [0]` cross-checked with `body-liveness` by hand |
| `((f))` | `[[:load-free 0 f] [:call 1 0 [] false []] [:call 0 1 [] false []] [:halt 0]]` (2) | matches codex's round-2 derivation |
| `(if c 1 2)` | `[[:load-free 0 c] [:branch-false 0 4] [:const 0 1] [:jump 5] [:const 0 2] [:halt 0]]` (1) | test slot reused for the result |
| `(f (if c 1 2) y)` | `f=0 c=1 result-of-if=1 y=2 result=3` (4) | `v2` interval `[3,7]` spans both arms |
| `((fn [x] (+ x 1)) 10)` | main `[:closure 0 1 4] [:const 1 10] [:call 2 0 [1] false []] [:halt 2]` (3); body `[:load-free 1 +] [:load-bound 2 0 0] [:const 3 1] [:call 4 1 [2 3] true []] [:return 4]` (locals 1, registers 5) | tail call's `rd` interval `[7,8]`, slot never written |
| `(yin/def x 5)`; `(yin/def y (+ 1 2))` | `[[:const 0 5] [:define 1 x 0] [:halt 1]]` (2); `[... [:call 3 0 [1 2] false []] [:define 0 y 3] [:halt 0]]` (4) | the define's result reuses `T0` after the operator expires |
| `(f (resume :p x) y)` | `f=0 x=1 y=1 result=2`, structural slot `T3`, registers 4 | `L(1)=L(0)={}` because the `:resume` cuts the path; corrected once during writing |

H images for the same eight programs are listed in §6.3; they have no
allocation choice and are expected to equal today's `lower-stack`
output, recorded as an observation.

## The facts-A-omits finding

Verified against the code: the row grammar's `:lambda` carries only
`:params` and `:body` (`vm.cljc:1067`), the datom lane's `flatten-tree`
reads only those (`linearize.cljc:103-105`) and reads `:tail?` only in
the `:application` arm (`:116`); shared `:eid`s are expanded positionally
(`linearize_test.cljc:107-119`); entity ids, datom order, `t`/`m` and
provenance never enter the vector (`linearize.cljc:20-22`, UCF §7.3.2).
Since the new lowerers take only `v`, **none of these facts can affect R
or H**; the only artefacts they can affect are the diagnostic side
tables' `:source` columns, outside every hash. The old `resolve` did
carry `:yin/macro?` (`debruijn_resolve.cljc:171`), unread by either old
lowerer. §6.2 item 6 asserts the same-A property both ways so a future
projection change is noticed.

## Unverified

- Every golden in §6.3 is a hand derivation under §2-§3's rules; no
  allocator exists yet to run. Two rows (`((f))`, `(f (g x) y)`) agree
  with codex's independent derivations of the frozen scan; the others
  have only my two passes.
- The claim that the filled `live` (from `body-liveness`) equals the
  physical image of `analysis/live` is stated as a gate property with an
  argument sketch, not proven; the implementation does not depend on it.
- The expectation that H bytes equal today's is an argument from the
  walk's lack of choice, recorded as an observation the 5a test reports.
- The unitarity amendment texts of §9.2 are proposals for documents this
  task does not edit; their line anchors were read at `ab952ace`.
- No tests were run; verification was by reading (`grep`, `sed`, `git
  show`, `date`).
