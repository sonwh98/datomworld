Created-GMT: 2026-09-26 03:05:00 GMT
Created-Local: 2026-09-26 10:05:00 +0700
Coding-Agent: glm
Session-ID: 79bedc98-febc-4e0d-a768-e166fb876644

Provenance: GLM-5.3 ended its plan-mode turn without writing this file; the
orchestrator extracted its "REPORT DRAFT" section (lines 39 to end) verbatim from
GLM's plan file /Users/sto/.claude-glm/plans/read-users-sto-workspace-datomworld-coll-sequential-papert.md.
Claims are UNVERIFIED delegated claims until the orchestrator spot-checks them.

# REPORT DRAFT (the deliverable)

# Scoping yin.vm.linker M3, M4, M5 (read-only; facts for decomposition)

Scope note: M2 is uncommitted in the working tree of
`/Users/sto/workspace/datomworld-ucf-phase2` and under its final gate; it is
read as-is and treated as not final. M2 is not reviewed here. Citations:
`docs/design/yin.vm.linker.md` is cited by line as L:<n>; code as file:line.
Each claim ends VERIFIED (read) or INFERRED.

## A. The M4 entry criterion: the §8.2 assertion policy

**Does any of it exist in code today? No.** A repo-wide search of src/ and
test/ (all hosts) finds zero implementation and zero tests for: signed
assertion/retraction envelopes (`:yin.module/op :assert`/`:retract`),
`:asserted-by`, `principal` (security sense), `equivocation`, `:unauthenticated`,
`:ambiguous-name`, `:unverified-derivation`, `:module-name-mismatch`,
name-env/manifest-registry. The working-tree linker's closed refusal set
`refusal-reasons` (src/cljc/yin/vm/linker.cljc:41-45) contains none of the §8
reasons, and the file contains no occurrence of "manifest", "assert", or
"authority". The symbol `yin.module` appears in no code file. VERIFIED.

Two near misses that are NOT this policy (do not build on them by accident):
- dao.space's datom-level `:db/retract` marker machinery
  (src/cljc/dao/space/schema.cljc:383-384 `emit-retract`, transact.cljc:269-378)
  is the legacy datom validity op, unrelated to signed envelopes. VERIFIED.
- `yin.vm.ledger/assert-name` / `retract-name`
  (src/cljc/yin/vm/ledger.cljc:110-133) are §8.3-style *plain* `:db/add`
  naming ops whose docstrings explicitly say they bypass §8.2 ("a retraction
  carries no provenance link"). Tested in test/yin/vm/ledger_test.cljc:217.
  VERIFIED.

**No cryptographic facility exists anywhere in dao.jing** (no sign / ed25519 /
keypair / hmac in src/ or test/) — and none is needed: the spec makes the
verify function composition-supplied (L:1749-1758, ":authority holds, per
principal, the key and a composition-supplied verify function; the linker
implements no cryptographic primitive itself"). The one dao.jing primitive the
policy does need exists: `canonical-bytes` (src/cljc/dao/jing.cljc:279), used
with `segment-key` (jing.cljc:279-298) for envelope content ids and signing
bases. VERIFIED.

**Where the spec says it lives.** Implementation in
`src/cljc/yin/vm/linker.cljc` (the §10 file box, L:2008-2063, lists no new
source file for it; `:name-env` and `:authority` are `link-state` slots,
L:806-807); tests in the new `test/yin/vm/linker_authority_test.cljc`
(L:2016, "M4 entry"). VERIFIED (spec); placement of the code in linker.cljc vs
a fresh namespace is a naming/file-box decision — see below.

**Inputs and outputs (fixed by L:1686-1798).**
- Input: assertion/retraction envelopes as plain maps (assertion:
  `{:yin.module/op :assert :yin.module/name n :yin.module/manifest addr
  :yin.module/asserted-by p :yin.module/seq s}`; retraction: `:op :retract`,
  `:yin.module/of` = segment-key of the assertion, L:1690-1703), transacted as
  datoms `[ev :yin.module/envelope env]` + `[ev :yin.module/proof proof]`
  (L:1705); an `:authority` map naming, per principal, the proof kind
  (`{:yin.module/signature sig}` over `canonical-bytes` of the envelope, or
  `{:yin.module/attested stream-identity}` for a one-writer log), the key /
  stream identity, the per-principal sequence floor, and the snapshot
  (published index address or cursor position) (L:1747-1778).
- Processing: three passes per principal at the snapshot — dedup by content
  id, equivocation detection on equal seq, order-and-honor vs the floor
  (L:1719-1743); retraction binding by assertion id only
  (`:dangling-retraction` otherwise, L:1710-1713).
- Output: a name-env snapshot `{name manifest-address}` with `:absent` for
  zero and `:ambiguous-name` naming every remaining address and asserter for
  more than one (L:1785-1788); provenance `:yin.link/provenance` on successful
  resolution (L:1789-1792). Discard kinds: `:unauthenticated` (`:no-proof` /
  `:bad-proof`), `:dangling-retraction`, `:replay`, `:equivocation`.

**The exact M4-entry test cases** (L:1930-1940, verbatim list): a signed
assertion by a declared principal resolves; a bare `:asserted-by` with no
proof is `:unauthenticated`; a bad signature is `:unauthenticated`; an
attested log's assertion copied onto another stream is `:unauthenticated`; an
undeclared principal is ignored; a signed retraction bound to an assertion's
id removes that assertion only, and a retraction naming no assertion or signed
by another principal is discarded; an exact duplicate envelope is honored once
and never counted as equivocation; a replayed sequence and a distinct
equivocating pair are discarded; two proven assertions refuse
`:ambiguous-name`; snapshot advance.

**Can it be built independently of M3's stepped core and of linker.cljc's
fetch? Yes, functionally.** The policy is a pure function over plain data
(envelopes + proofs + authority config) producing a snapshot map plus
diagnostics. It touches no stream, no format record, no fetch path, and no
kernel. Its only repo dependencies (`segment-key`, `canonical-bytes`) are
committed. VERIFIED (by reading the spec's data flow; no code exists to
conflict with).

**Proposed split into slices (2-4 gate-able, with file ownership):**
- **A1 — envelope + proof verification (pure).** Envelope shape validation,
  content-id computation, signature-verify call-through (composition-supplied
  fn), attested-log identity check, the three replay/equivocation passes,
  retraction binding. Owner: linker.cljc (additive defns at file end) or a
  new `yin.vm.linker.authority` namespace. Tests: the entry list above minus
  resolution.
- **A2 — snapshot resolution.** Fold honored assertions into the per-name
  outcome (`:absent` / unique / `:ambiguous-name`), provenance data, snapshot
  advance semantics (a `link-state` rebuild, never an ambient re-read,
  L:1774-1778). Tests: resolves / absent / ambiguous / snapshot-advance.
- **A3 — datom ingestion.** Reading `[ev :yin.module/envelope]` /
  `[ev :yin.module/proof]` datoms from a dao.space source at a snapshot
  (index-from-datoms is the existing pattern, linker working tree). Tests:
  end-to-end from transacted datoms.
A1+A2 are one comfortable gate; A3 can be a second. All three are
parallel-safe from a committed base: they add new functions and one new test
file, touching nothing M3 rewrites inside linker.cljc. The only collision is
textual (same file as M3) — if the owner wants zero overlap, placing A in a
new namespace removes even that, at the cost of a file-box amendment
(DECISION: owner/Architect — same-file additive vs new namespace).

## B. M4 rest: slices, order, needs, collisions

Current-state anchors (all VERIFIED, working tree):
- module.cljc (161 lines): `register-module [r name fns]` :36 (no profiles),
  `resolve-module` :46, `register-stream-module` :129, `require-handler` :141
  (registry hit → value; miss → **throws** ex-info; no park, no wait states),
  `default-registry` :156; `stream-module` bindings are pure effect
  constructors :99-124.
- engine.cljc (683 lines): shared machinery only — `resolve-var` :54
  (env → store → primitives → module registry, :72-78), `handle-effect` :529
  (default branch dispatches the registry's effect handler :582-587 — the
  path `require` rides today), `check-wait-set` :329, `scheduler-round` :466,
  `park-continuation` :480, `resume-continuation` :494,
  `handle-stream-block` :514, `gensym` :505. No image/backend code at all.
- Backends: `ast_walker.cljc` (`vm-load-rows` :773), `semantic.cljc`
  (`load-vector` :726, `load-image` :536), `debruijn/stack.cljc`
  (`load-image` :111, private `install-image` :96), `debruijn/register.cljc`
  (`load-image` :148, private `install-image` :115; `reset` reinstalls :604).
  All loaders are replace-semantics entry points. `attach-image` exists
  **nowhere** in the repo (zero hits, any spelling).
- repl.cljc (985 lines): private `append-stack-image`/`append-register-image`
  :102-141 (concatenate + rehash + set :pc), `make-vm` :428-437
  (`register-stream-module (default-registry)`), `vm-constructors` :67-74,
  `program-loaders` :144-173, `rebuild-session` :867.
- M2 working tree already provides the four format records with the full
  slot set incl. `:definitions-fn`/`:applications-fn`
  (linker.cljc `stack-format` :637, `register-format` :655,
  `semantic-format` :674, `ast-format` :696), generic `fetch` :1103 (arities
  :1145-1149), `trusted-fallback` :1276, `verifying-fallback` :1287.
  VERIFIED (agent-read; treat as not final until M2 commits).

### Slices

**S1 — Kernel attach (files: the four backend files + repl.cljc).**
`attach-image` per kernel (semantic: fresh local id + alias column write;
stack/register: relocate by held length, append, one `[identity offset
length]` offset-table row, recompute `:hash`; walker: add rows keyed by id and
the `[node params]` row index), hash-safe restore (r6: table-membership check,
no `:segment` write-back from entries), grow-only returns (r7: register frame
drops `:segment`/`:hash`; return-transition restores pc/frames/registers/
continuation only), walker `:lambda` row-id annotation at decode, repl
`append-*-image` rewritten over attach-image (L:1155-1216, L:1181-1185).
- Needs from M3: **nothing**. Needs from M2: nothing. Can start from a
  committed base **today**, before M2 lands. VERIFIED (kernel-internal work;
  the only inputs are image values kernels already hold).
- Internal parallelism: four disjoint files; repl.cljc is the fifth piece.
  Sequencing inside repl.cljc with M5 is the only shared file (M5 comes
  later; no overlap in time if M5 waits, which it must anyway).
- Tests: completion criteria 17 (parked restore at nonzero pc, register call
  in flight over a nested attach, relocation of exported closures,
  continuation lifted after attach rebased by the receiving table).

**S2 — Host-module registration (files: module.cljc + callers).**
`register-host-module r name fns profiles` with UCF profile-class enforcement
(refuse `:host`-class, undeclared host state, missing profile; L:1820-1833),
`register-stream-module` over it, registry value shape change to
`{name {:manifest m :address a :derivation d :slice ... :stores ...}}`
(L:1864-1873, "no shim ... clean break").
- Needs: `yin.vm/primitive-profiles` (exists, src/cljc/yin/vm.cljc:398).
  Nothing from M2/M3. Can start today.
- Blast radius (one commit must rewrite all callers — VERIFIED list):
  src/cljc/dao/await.cljc:88-89, src/cljc/yin/repl.cljc:436,
  src/cljs/datomworld/demo/compilation_pipeline.cljs:87,
  src/cljs/datomworld/demo/continuation_stream.cljs:83, plus engine/module
  tests (`resolve-module` keeps its signature, so completion.cljc:443 and
  linker.cljc's 5b use are safe). TRAP: every `stream-module` binding
  (`make`,`put!`,`cursor`,`next!`,`close!`) must carry a profile in
  `primitive-profiles` or registration refuses — verify before landing.

**S3 — Require lowering + install child (files: module.cljc + engine.cljc,
one owner).** `require-handler` registry-hit / joins-install-waiters / miss →
mint `:dao.stream/newest` cursor **then** build the `:link-request` wait entry
(cursor-before-append, L:979-997), `[origin counter]` link ids (L:999-1011),
append/retry-on-`full` state machine to `:link-response` (L:1013-1022),
id-correlated restore in `check-wait-set` with duplicate/late/unknown/
abandoned handling (L:1038-1060), the `:installs` child task with
loading/running/parked/validated/linked/refused phases (L:1087-1519),
`link-module` (L:1818), lift/lower with `:yin.k/binding` and `:yin.k/store-of`
markers, module stores (`:module-stores`, active-store routing, frame
threading), the private `:resources` table (r8), resource lowering (r9),
sealed references + lift-authenticates-before-encode (r10/r11), cycle
detection over install ancestry (L:1528-1547).
- Needs from S1: attach-image (act 3 of the linked transition). Needs from
  M3: only the *data shapes* (request envelope key set L:832-849, response
  completion shape L:870-876) — codeable against the spec now; integration
  tests need a responder, which can be a stub appending hand-built responses
  until Lane 1 (M3 + S4) lands. INFERRED (spec-driven; no code exists).
- This is the monster slice; it is also the only one touching engine.cljc,
  so nothing else may touch engine.cljc in parallel.

**S4 — Linker manifests (files: linker.cljc + linker_test.cljc).**
`:yin.module/manifest` and `:yin.ledger/record` format records, schema
validation (one schema version), contract-before-content + name check right
after manifest verification (L:1612-1655), derivation policies
`:verifying`/`:trusted` via `yin.vm.ledger` (`derive-record` ledger.cljc:53,
`verify-derivation` ledger.cljc:156 — both exist), `:yin.module/index` merge
into `:indexes`, fallbacks reading derivations from the manifest
(L:1636-1650).
- Needs from M2: the format-record machinery and fetch pipeline (records,
  `refused` builders, worklist) — i.e. S4 starts from the M2 commit. Needs
  slice A for by-name resolution in integration (by-address manifest fetch
  tests do not).
- Collides with M3 inside linker.cljc → serialize M3 → S4 under one owner,
  or partition carefully (M3 rewrites fetch's transport, S4 adds records +
  by-name path; the by-name path *is* stream-adjacent, so one owner is the
  safe call). DECISION: owner assigns linker.cljc serially.

**S5 — UCF doc amendments (file:
docs/design/yin.vm.universal-continuation-format.md).** Sections 7.4.1 +
7.4.3 (`:link-request`/`:link-response`/`:install` safepoints and pending
variants), 7.5.1 (`:yin.k/binding`, `:yin.k/store-of`, re-sealed references),
7.5.3 + 7.6.2 (private resource table, decode targets, store model) —
content already fixed by r6-r11 (L:2101-2108). Doc-only; any-time parallel;
no dependencies. (Criterion 12's round-trip *tests* belong to S3, not here.)

**Landing order:** S1, S2, A1-A3, S5 may all run in parallel from a committed
base (S1/S2/S5 even before M2 commits). M3 next in linker.cljc; then S4.
S3 can develop in parallel after S1's per-kernel `attach-image` signatures
are pinned (DECISION: Architect fixes the four signatures up front so S1 and
S3 don't ping-pong), integrating once M3+S4 answer real link requests.
**A must merge before S4's by-name resolution over dao.space** — that is
the entry criterion's force (L:1682-1684: "no linker may resolve a name from
dao.space assertions before it is implemented and tested"); map-shaped
name-envs in tests don't trigger it.
linker_require_test.cljc (the per-backend M4 list) is the integration gate
after M3+S4+S3. M5 last (repl.cljc wiring; also repl.cljc touched by S1's
append rewrite — S1 lands first, so no conflict).

### M4 test list grouped by slice (L:1946-1997)

- S4 (linker): `:module-name-mismatch`; the three swapped-image
  `:derivation-mismatch` tests + the fourth (tree ≠ manifest tree);
  `:undeclared-free` (manifest declares nowhere); four-way manifest through
  four kernels (criterion 22); `:unverified-derivation` under `:verifying`
  vs `:trust :composition` under `:trusted` (criterion 18).
- S3 (engine+module): two outstanding requires restore on own ids; response
  before poll not skipped; transitive require while a third task runs;
  `:require-cycle`; module-store semantics block (closure reads module's
  value; write visible to sibling export in-task only; two tasks two stores;
  dependency store snapshot with child mutations; `:store-put` inside export;
  module closure calling module closure); `:shadowed-free`;
  `:unresolved-free` (same-named primitive, different profile); forged
  resource key reads nothing; forged sealed-reference cases
  (`:forged-resource-reference`, lift-side `:yin.k/non-portable`);
  reference-carrying export lifts/lowers into `:resources`; REPL tail-call
  store routing; read-in-body-before-definition retains obligation
  (`:undeclared-free` unless declared).
- S1 (kernels): parked entry recorded before attach restores after it, same
  pcs, `:segment` never assigned; register call in flight over nested attach
  returns into grown code space; two tasks with different image lengths
  require same module, each applies export correctly; walker shared-body
  `:unrooted-body`.
- S5/M5 boundary: `(vm :stack)`/`(vm :register)` linking the same manifest by
  H and R with B0-equal results (exercises everything; gate at M5).
- A1-A3: the entry list in section A above (linker_authority_test.cljc).

## C. M3 and M5 dependencies

**M3 needs from M2 (names and shapes).** M3 = `link-state`, `request-link`,
`step`, `abandon` over `dao.jing.remote.step` or `dao.stream.rpc` on a
ring-buffer pair; `fetch` re-implemented as the blocking driver over
`{:state link-state :drive fn}`; `verify` (steps 3-5a pure) and `discharge`
(step 5b pure) exported (L:1916-1920, L:886-919). The committed HEAD
(0fc931fc) is the M1 state: two records (`stack-format` HEAD:92,
`register-format` HEAD:100) with the old slot set, sync `fetch` HEAD:220,
`publish!` HEAD:280, `trusted-fallback`/`verifying-fallback` HEAD:349/360,
and a 20-test linker_test. The uncommitted M2 diff (+2364/−339 across 5
files; linker.cljc alone +1121) is what M3 consumes, by name:
- the four records as values for `link-state`'s `:formats` map —
  `stack-format` (working :637), `register-format` (:655),
  `semantic-format` (:674), `ast-format` (:696) — carrying the full §4.1 slot
  set incl. `:definitions-fn`/`:applications-fn`. Note `register-format` is
  the register-VM record def, not a registration function; **no
  `register-format` fn and no `builtin-formats` table exist or are planned**
  (the ns docstring: "no global loader, registry, callback, or cache");
  records are explicit arguments. VERIFIED.
- the six-step verification logic M2 implements inside `fetch` (working
  :1103, arities :1145-1149): the `:parts-fn` worklist, per-part
  `:row-defect-fn` (:600), composition bounds + `default-bounds` (:1008),
  and the step-5a dominance join — which M2 actually built, as
  `closure-spans`/`spans-conform?`/`vector-layout`/`conditional-ranges`/
  `degraded-occurrences`/`step-order`/`path-order`, the three datalog
  queries `tree-occurrence-query`/`tree-definition-query`/
  `tree-application-query`, walkers `tree-free-name-occurrences`/
  `tree-definition-occurrences`, and scanners `stack-free-names` (:77),
  `register-free-names` (:86), `semantic-free-names` (:245),
  `semantic-free-occurrences` (:205). M3 restructures exactly this code into
  per-link resumable state, so these internal names are the working
  interface. VERIFIED (grep of working tree).
- the refusal builders `refused`/`refused?`/`ok?` (:48-62) and the extended
  `refusal-reasons` (:41, now incl. `:invalid-request :parts-limit
  :contract-mismatch :use-before-definition`), `address-attribute` (:720),
  `index-from-datoms` (:727), `publish!` (:1205),
  `verify-same-root-pairing` (:1251), `trusted-fallback` (:1276) /
  `verifying-fallback` (:1287) — the fallback pair is re-pointed at
  manifest derivations by S4.
VERIFIED for the working tree; INFERRED for the final M2 shape (still in
gate — 38 of 58 linker tests are also uncommitted, incl. every dominance
case).

**Can any of M3 start before M2 commits?** The transport skeleton — request
envelope admission (the §6.3 closed key set, L:832-859), `link-state`
bookkeeping, the `step` loop over `dao.stream.rpc` (`client-state`,
`request!`, `poll!`, `take-completed`, `serve-once!` — all VERIFIED in
src/cljc/dao/stream/rpc.cljc), `abandon`, and the local-fetch traffic test
scaffolding (get-counting handle) — is M2-independent and could be written
from HEAD today. The per-link *verification* state machine cannot: it drives
`:parts-fn`/`:row-defect-fn`, which exist only in the uncommitted M2. Since
M2 is in its final gate, the better use of a parallel lane is S1/S2/S5
(zero M2 dependency) and A (zero M2 dependency); pre-starting M3's skeleton
saves little and risks churn against a moving linker.cljc. INFERRED
(judgment on spec + git facts).

**M5 needs from M4.** Everything user-visible: a working by-name link
(S4 + A), the require lowering and install child (S3), attach-image-based
`append-*-image` in repl.cljc (S1), and the host-module registry (S2).
M5 itself is small: compose the link pair per VM and the content pair per
connection in repl.cljc (`connect` already opens the RPC client; repl.cljc
make-vm :428-437 and the connect path), so `(require 'foo)` at the prompt
exercises the whole path (L:1999-2001). M5 also owns one open decision:
the failure-policy timing options (§12 bullet 4). VERIFIED (spec) +
INFERRED (repl.cljc wiring points).

## D. Traps for implementers

Cross-host (JVM / Node / Dart):
1. **Dart load-time registration.** Format-record tables and any
   authority/principal registries must not rely on bare top-level side
   effects — CLJD drops them; force registration at mint time (project
   memory: cljd-load-time-side-effects). Applies to S4's record defs and S2.
2. **Signature tests must use a portable stub verify.** No crypto exists on
   any host and ed25519 is not tri-host; the spec's composition-supplied
   verify fn lets tests pass a pure fn. Keep the `:bad-signature` test
   shape-level. (A1)
3. **`:pending` stepping tests:** poll helpers must test pred results for
   truthiness (`false` is a final answer for some preds); reversed `>=`/`<=`
   bounds flake on slow hosts (project memory). Applies to M3's
   one-part-per-step test and S3's wait-state tests.
4. **cljd.test fixtures** are 0-arg setup + 1-arg teardown (dual-arity fn),
   not use-fixtures maps; `@#'x` can't read private vars cross-host. (S3/S4
   tests.)
5. **kondo cljc gated defns:** JVM-only WebSocket test code (M3's B6
   criterion-1 path; `serve-content!` src/cljc/dao/jing/remote.cljc:932 is
   the JVM server) must be gated so the cljs pass doesn't flag ungated
   private vars; let-bind map-key locks before locking. Ring-buffer path is
   the portable one; Dart/Node never see the ws path.
6. **Piped `bb test:cljd` logs drop deftest names** (reporter artifact);
   verify a "missing" test with `flutter test` on the one compiled file
   before believing it failed.
7. **Fresh-worktree test lanes** need clojure ProcessBuilder + mise's Java 21
   and flutter paths, npm ci first (project memory) — every parallel lane
   below runs in its own worktree and hits this.

Spec-internal / staleness:
8. **§9's M2 paragraph omits `:definitions-fn`/`:applications-fn` and the
   step-5a dominance join** (it names only the obligations rename and three
   additions, L:1899-1902), while §4.1/§5.1-5.4/§10 require them. The M2
   working tree implemented them anyway (records at linker.cljc:637-696;
   the join machinery listed in section C; 38 of 58 linker tests are the
   dominance/Rule-R cases, e.g.
   `a-definition-dominating-every-application-discharges-a-body-occurrence`,
   `an-application-before-the-definition-retains-the-obligation`).
   The M4 test list then assigns further 5a-behavior tests without saying
   which milestone built the join. Treat the join as M2 work (it exists in
   the tree) — but if the final gate trims it, the join migrates to S4 and
   S4 grows by ~400 lines. Flag to the owner before slicing S4.
   VERIFIED discrepancy.
9. **The worktree is dirty and mid-gate, and the UU is not a merge:** the
   status shows `UU test/yin/vm/content_test.cljc`, but there is no
   MERGE_HEAD/CHERRY_PICK_HEAD/rebase state — it is a **stash-apply
   remnant**: the M2 work was stashed
   (`stash@{0}` "m2-uncommitted-before-rule-r-ff"), HEAD fast-forwarded to
   the Rule R commit 0fc931fc (which did not touch linker.cljc or
   content.cljc), and the stash re-applied; content_test.cljc conflicted,
   was hand-resolved in the working tree (no markers remain), and only
   needs staging to clear the UU. `stash@{0}` still holds an older snapshot
   of the same M2 work (its linker_test diff is 1058 lines vs the current
   1287 — work continued after the pop) and the stash stack is shared
   across sessions/worktrees. Consequences: (a) M2's commit blocker is one
   `git add`, (b) parallel lanes must branch from a **committed** base,
   never this tree, (c) drop `stash@{0}` only after the M2 commit lands,
   from the session that owns it. VERIFIED (git forensics: reflog, index
   stages, blob ids).
10. **`reset` vs the offset table:** register.cljc's `reset` reinstalls the
    base image via `install-image` (register.cljc:604). The spec's r6/r7
    rules make the offset table kernel state that "only the loaders and
    attach-image write" (L:1219-1221) but never say what `reset` does to it.
    Pin this before S1 lands (DECISION: Architect — likely: reset rebuilds
    the table from the base image row, since reset is a fresh run).
    INFERRED gap.
11. **Bare `fetch` 5b receiver:** working-tree fetch already consults
    `module/resolve-module` (linker.cljc:770) for discharge; S2's registry
    shape change (`{name {...}}` instead of `{name {sym fn}}`) will break
    that call site unless `resolve-module`'s task-local `:bindings` read is
    wired in the same commit. VERIFIED call site exists; INFERRED breakage.
12. **Prompt vs spec naming:** the "two wait states" are `:link-request` and
    `:link-response` (L:1019-1022) — there are no `:waiting-deps`/
    `:waiting-download` states anywhere; don't hunt for them.
13. **`serve-content!` naming:** §6.1's server is `dao.jing.remote/serve-content!`
    (remote.cljc:932, WebSocket/JVM); the portable test path is
    `dao.stream.rpc/serve-once!` over `default-handlers` (remote.cljc:71) on
    ring buffers — M3's own text says so (L:1917-1919). VERIFIED both exist.
14. **Profile coverage for stream-module:** see S2 trap — registration
    refuses profile-less bindings; check all five stream constructors appear
    in `yin.vm/primitive-profiles` (vm.cljc:398) before S2 lands. VERIFIED
    enforcement rule; coverage INFERRED-unchecked.

## E. §12 open decisions: which slice needs what settled

| §12 decision | Blocks | Can defer |
|---|---|---|
| Key material / rotation / signature scheme | **Nothing in-repo.** A1's tests use a stub verify; the spec fixes the envelope + proof shapes and leaves the scheme to compositions. | Real-deployment key rotation: indefinitely (a later design; §12 says so). |
| Runtime capability confinement of host modules | Nothing in S1-S5: r8-r11's private `:resources` + sealed references ARE in scope and are S3 work. Full token-wiring confinement is engine/composition work outside this doc. | The confinement wiring itself: post-M4. |
| SCC identity (B7) | Only §7.4's multi-module one-response delivery. S4/S3 as sliced here deliver one module per response and refuse cycles — no SCC need. | Until B7 dependency-closure delivery. |
| Failure policy (retry/deadline; repl timing options) | **M5 only** (§12 names it "an M5 decision"). M3's `step` reports `:pending`; policy is composition data. | M3/S3/S4 entirely. |
| Contract-pinned AST/semantic identities | Nothing — linker prepared (`:identity-matches-fn`, `:indexes` are the only slots that would change). | Indefinitely; a UCF/code-as-tuples amendment. |
| Stepped DHT client | Nothing — M3's test drives the DHT handle behind `serve-content!` (L:787-795). | Indefinitely; a dao.jing.dht design. |
| Unrecognized outcomes (OD-1) | Nothing blocking: M3 classifies unknown content answers as `transport-error` (as dao.stream.observe/step does) until OD-1 is accepted. | Until dao.stream accepts OD-1. |
| Cross-task module stores | Nothing — per-task instances are the spec'd behavior; a shared-cell composition is future effect-discipline work. | Indefinitely. |

Net: **no §12 decision blocks any proposed slice except the failure-policy
choice, which blocks only M5.** The decisions that must be made *inside* the
milestone work are placement/signature calls, not §12 items: A's namespace
choice, S1's four attach-image signatures (pin before Lane 3 and Lane 5
start), and trap 10's reset-vs-offset-table rule — Architect/owner calls,
recorded above.

## Parallel-execution summary (the decomposition the orchestrator asked for)

Base facts: HEAD of ucf-phase2 is 0fc931fc (= ucf-rule-r; M1 + Rule R
committed); the working tree additionally holds uncommitted M2 (see trap 9);
`master` has diverged by 11 docs-only commits (no code); existing worktrees:
the main checkout (master), this one, and datomworld-ucf-rule-r (same commit
as here). VERIFIED. Lanes below each get their own worktree off a committed
base — off today's HEAD for the † lanes, off the M2 commit for the rest.

From the M2 commit (or today, marked † for "can start pre-M2"):
- Lane 1 (linker.cljc, serial): M3 stepped core → S4 manifests. One owner.
- Lane 2 † (pure/new code): A1-A3 authority policy (new fns + new test
  file; zero coupling to Lane 1's functions).
- Lane 3 † (four kernel files + repl.cljc append rewrite): S1, splittable
  per kernel across owners once attach signatures are pinned.
- Lane 4 † (module.cljc + callers): S2, then merges into Lane 5's owner.
- Lane 5 (module.cljc + engine.cljc): S3 require lowering + install child —
  starts once S1 signatures exist, integrates after Lanes 1+2 land.
- Lane 6 † (docs): S5 UCF amendments.
- M5 (repl.cljc) after all lanes merge.
Collisions to respect: linker.cljc (Lanes 1+2 if A stays in-file — keep A
additive or give it a namespace); engine.cljc (Lane 5 only); module.cljc
(Lanes 4+5 — same owner); repl.cljc (Lane 3 then M5, never simultaneous).

Sequencing note for GLM's remaining credits (resets 2026-09-27 01:26): the
† lanes are the safe immediate spend — no dependency on the M2 gate, no
dependency on each other. Lane 1 should wait for the M2 commit; if the reset
arrives first, Lane 2/3/4/6 work is where the budget goes.
