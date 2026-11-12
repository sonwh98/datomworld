Created-GMT: 2026-11-11 06:40:00 GMT
Created-Local: 2026-11-11 13:40:00 +0700
Coding-Agent: muse
Session-ID: 32f932e8-e945-4e01-8a58-f044508a17e9
Model: muse-wonder-1.0
Resumed-By: glm-5.3 (session e45fe836; muse died mid-round on two provider 429s - resume-by-state per 1794357400000-engineer-cc3-resume.prompt.md, 2026-11-11)

# cc3 - lowering provenance & oracle C1 occurrence-key bridge: findings

Status: COMPLETE - all seven items landed and verified; the eight-file diff was audited
hunk-by-hunk, fixed where wrong (see the audit table below), and the full foreground
battery is green. Nothing committed.

## PREFLIGHT BOUNDS (work item 7 - recorded BEFORE any tagged oracle run)

Measured 2026-11-11 by compiling the pinned binding fixture
`{:call (latte-kernel.utils/vectorn? [1 2 3] 2)}` through `kernel-compile`
(compile only; no C lane executed) and projecting its stamped tree through
the C1 encoder pipeline:

| Bound | Value | Derivation |
|---|---|---|
| Canonical rows (stamped fixture tree) | 1,316 | `(:rows tree)` count |
| Occurrences (all tree positions) | 3,669 | `vm/occurrences` |
| Application occurrences A | 987 | `vm/application-occurrences` |
| Oracle stamped guest sites (same tree) | 2 | `kernel-stamp-sites` rows - the coverage-separation gap in one line: the oracle set is 2 of 987 |
| Max path depth (flattened steps) | 90 | max over application paths |
| Total application path bytes | 103,650 | sum of `pr-str` lengths of application paths |
| Guest instruction ceiling | 4,000,000 per C lane | RESTATED from `kernel-c` (`latte_oracle_test.clj:2484`, `run-guest-machine ... 4000000`); NOT raised |
| Trace capacity | 1,000,000 records per C lane | RESTATED from the fixture program `(stream/make 1000000)`; NOT raised |
| Worst trace record size | 29 bytes measured; 64 bytes declared ceiling | record `[fname arity caller-site]`: max guest fname 23 chars in the fixture forms, arity and site id are small integers; declared ceiling allows fname <= 40 chars, arity <= 999, site < 10^6 |
| Trace byte bound | 64,000,000 bytes per lane | 1,000,000 records x 64-byte declared ceiling; drain is after halt (`tu/drain`), so peak retained = capacity |
| Binding added guest work | 0 instructions, 0 trace records | the cc3 binding is static/test-side: it reads the already-compiled tree and the already-derived site table; it neither re-stamps nor re-instruments, so the 4,000,000 / 1,000,000 ceilings are occupied exactly as before (the older binding fixture measured 2,024,912 walker / 1,105,792 semantic steps, recorded at `latte_oracle_test.clj:3216`; under both ceilings) |

Failure rule: a tagged run that exceeds any ceiling above is a failed
bounded run; bounds are never silently raised (design 6.3).

Bounds verification (resume session, 2026-11-11): every number above was
re-measured compile-only and matches EXACTLY - 1,316 / 3,669 / 987 stamped-tree
counts, 80 raw path steps / 90 flattened, 103,650 path bytes, 2 oracle sites.
Derivation-basis note: the table rows/occurrences/applications are the
STAMPED-tree counts (the larger tree the C lanes execute); the C1 coverage
equality of item 6 holds over the pre-stamp COMPILED tree (measured 1,309 rows /
3,649 occurrences / 981 applications). One citation corrected from the stub:
the occupancy reference note lives at `latte_oracle_test.clj:3216`, not :2454.

## Per-item disposition

### 1. Semantic row provenance - LANDED (one audit fix)

- `src/cljc/yin/vm/semantic/linearize.cljc:188` `validated-call-origins`: the
  `{[root path] origin}` map a C1 side table claims over the canonical tree.
  Per-occurrence resolution: each row's origin is THAT occurrence's own - a
  tree mixing `[:source ...]` and `[:expansion ...]` applications is never
  stamped with one blanket origin. Every row is held to the contract before
  it is believed: malformed row / unsupported marker version /
  non-occurrence origin (`[:oracle/fixture <id>]` included) / foreign
  root / path naming no `:application` occurrence of this tree refuses with
  `:rule :call-site-row`; two rows for one occurrence refuse with
  `:rule :call-site-conflict`. Non-`:yang/call-site` frontend metadata in
  the same relation is neither believed nor refused. An occurrence with no
  row is simply absent (absence, never invention).
- `linearize.cljc:230` `project-rows` (3-arity; `:273` `:call-provenance`):
  `[[pc origin root path] ...]`, one row per `:call` instruction, joined
  through `flatten-tree`'s own `sources` paths (the same 2.5-path grammar as
  `vm/occurrences` and `vm/application-occurrences` - the green join
  verifies the conventions agree). Rule R applications lower to `:define`
  and yield no call row. The caller still supplies the segment coordinate
  (unchanged contract); the vector and its address are identical with or
  without the side table (asserted).
- AUDIT FIX (mine): the interrupted code stamped `:call-provenance` with the
  caller-supplied single `origin` when no side table was given - exactly the
  one-origin-across-the-set stamping the mob condition forbids. Removed:
  `:call-provenance` is non-empty ONLY from validated C1 rows; the full
  `:provenance` table keeps carrying the caller's origin as before.
  Red-first: new assertions failed against the fallback (evidence below).
- Test: `test/yin/vm/semantic/linearize_test.cljc:398`
  `call-provenance-resolves-per-occurrence-origins` - mixed origins resolved
  per occurrence, Rule R absence, partial-table absence, canonical
  invariance, four refusal arms. AUDIT FIX (mine): the `[:oracle/fixture 1]`
  refusal arm had a bracket typo (the row was unwrapped - its ELEMENTS were
  passed as five separate rows, all filtered, so the refusal never fired and
  the interrupted session's own test was red against its own code); wrapped.

### 2. Native image provenance - LANDED

- `src/cljc/yin/vm/semantic/derive.cljc:60` `call-key-map`: validates a
  `:call-provenance` relation against the vector it claims - a row naming a
  pc that holds no `:call` in THAT vector refuses `:rule :call-provenance`.
  PC equality across formats is never assumed.
- `derive.cljc:79` `stack-image` (2-arity): reconstructs call provenance
  through the stack derivation's OWN pc mapping (the recorded pc is the
  stack pc where the `:call` lands, after operand pushes). Adds a
  `{:kind :call :source pc :origin o :root r :path p}` side-table diagnostic
  at each call pc - the existing `:closure`/`:var` diagnostic shapes are
  undisturbed (asserted).
- `derive.cljc:193` `register-image` (2-arity): layout preserved, so a
  semantic `:call` pc IS the register pc; rows
  `[segment-address pc origin root path]`.
- Both images are byte-identical with or without provenance (asserted
  against the 1-arity derivations).
- Test: `test/yin/vm/semantic/derive_test.cljc:832`
  `native-images-reconstruct-call-provenance`. AUDIT FIX (mine): its refusal
  arm used a bare `(catch Exception ...)` in a `.cljc` - clj-kondo's cljs
  analysis errors on it (recorded red); replaced with the file's portable
  conditional.

### 3. Durable persistence - LANDED (one audit fix)

- `src/cljc/yin/vm/linker/publish.cljc:325-345`: a C1-claiming publication
  projects WITH its validated rows (`project-rows tree nil (:rows claim)`)
  so every image's call provenance is resolved per-occurrence at lowering
  time; an unclaimed publication projects exactly as before. The stack image
  hashed into the manifest is the same derivation (`:image` selected; the
  register image with `:call-provenance` dissoc'd) - manifest, hashes and
  addresses unchanged.
- `publish.cljc:386-420`: the instruction-provenance relation
  `{:yin.vm.provenance/schema 1 :root ... :semantic [[seg pc origin root path] ...] :stack [...] :register [...]}`
  is materialized as a SIDE ARTIFACT outside the schema-1 manifest hash (the
  cc1 precedent: the `:yang.call-site` record names manifest, table, and now
  `:yang.call-site/provenance`; the manifest never names the record -
  asserted in the test).
- `publish.cljc:536` `published-instruction-provenance`: retrieval held to
  the same manifest/root linkage as `published-call-site-table`; nil when no
  record verifies or the publication claimed no C1 - legacy unannotated
  publications persist none and remain fully loadable (tested).
- `src/cljc/yin/repl.cljc:158` `packet->projection`: the choke point that
  used to select `(:vector ...)` and discard the rest now keeps the full
  projection (`packet->vector` delegates; behavior unchanged).
- `repl.cljc:171` `packet-call-provenance`: resolves an expanded packet's
  three images' call provenance from the expander's RETAINED tables
  (`call-site-tables`, keyed `[admission-origin final-root]`); a root claimed
  by two retained admissions refuses `:rule :ambiguous-call-site-table` (a
  table is never borrowed across admissions); a root with no retained table
  answers nil (absence, never invention). Segment coordinates are the
  images' own content identities as the loaders compute them
  (`ucf/code-address`, `dcode/image-hash`, `rcode/register-hash`).
- AUDIT FIX (mine): the function derived each image twice (once to hash,
  once with provenance). Now each image is derived once with provenance
  attached; its own (unchanged) hash is patched into the rows afterward -
  same values, half the derivations, the publish-side pattern.
- Tests: `test/yang/clojure_callsite_test.cljc:1968`
  `published-instruction-provenance-test` (persistence outside the manifest,
  every persisted row a claimed occurrence key, all three lanes non-empty
  where calls exist, the manifest's closed schema-1 key set, legacy
  loadability) and `:2012` `repl-packet-call-provenance-test`
  (retained-table resolution, absence, ambiguity refusal). AUDIT FIX (mine):
  the ambiguity arm's inline catch spelled the reader conditional so that
  on the cljd platform the `:default` branch matched BEFORE `:cljd`,
  yielding the keyword `:default` as the catch type; replaced with the
  file's own `failure-rule` helper.

### 4. Datom lane decision - RESTRICTION (D1's stated dependency)

`project-datoms` (`src/cljc/yin/vm/semantic/linearize.cljc:349`) keeps its
`[pc entity]` provenance and carries NO occurrence key K. The
entity-to-occurrence bridge (design 2.4: explicit entity-to-occurrence
mapping with owning batch/root/path, refusing ambiguity;
`encoder/datom-entity-occurrences` is the starting machinery) is DEFERRED TO
D1 AS ITS STATED DEPENDENCY; until it lands, cc3's provenance claims cover
the ROW LANE ONLY, and the datom lane's `[pc entity]` shape must not be
read as a K bridge. Recorded in the `project-datoms` docstring and pinned by
`test/yin/vm/semantic/linearize_test.cljc:463`
`datom-lane-provenance-names-entities-only`. A restriction, not a silent
gap.

### 5. Oracle binding - LANDED (two audit fixes)

- `test/yang/latte_oracle_test.clj:2472`: `kernel-compile` now retains
  `:compiled-ast` - the CHRONOLOGY condition (mob condition 2): compilation
  happens FIRST, stamping by insertion AFTERWARD, so the binding is derived
  from the pre-stamp compiled tree's validated C1 rows while the site ids
  are read off the stamped tree (stamping wraps argument nodes in place;
  every other node keeps its path - the bijection's
  `(every? marked (vals binding))` assertion verifies that path alignment at
  runtime, every run).
- `:3427` `cc3-walk` (row-grammar paths over a map AST - the same positions
  as `vm/occurrences`), `:3450` `cc3-stamp-site-id`, `:3458`
  `cc3-oracle-binding`: each stamped oracle integer site maps to its
  post-expansion C1 key K = [origin root path] with origin
  `[:source :program <canonical-source-hash> 0]` - a RECORDED admission
  coordinate in `vm.cljc:938-958`'s validator shape (asserted
  `vm/occurrence-origin?`); `[:oracle/fixture <id>]` alone is never
  minted (asserted false).
- `:3502` `test-cc3-oracle-site-c1-binding`: the tagged deftest. Verified:
  (a) bijection - the stamped sites equal the binding's keys, no two sites
  share one occurrence (distinct-vals), every bound key is a validated C1
  occurrence; (b) BOTH C LANES (walker, semantic) run the SAME stamped
  artifact under the recorded ceilings and every EXECUTED site id resolves
  through the binding to the IDENTICAL K set in both lanes (2 sites each);
  (c) Rule R - `:define` paths never appear as call-provenance keys.
- AUDIT FIXES (mine): (1) the lowering block's `keys-of` destructured the
  4-element semantic rows `[pc origin root path]` with the 5-element native
  shape, comparing `[root path nil]` garbage against `[origin root path]` -
  a permanent self-inequality (recorded red: 2 failures at :3552/:3553);
  fixed with `sem-keys` for the 4-element rows. (2) the no-two-sites check
  was `(= (count binding) (count (vals binding)))` - vacuously true for ANY
  map; fixed to `(= (count binding) (count (into #{} (vals binding))))`.

### 6. Coverage separation - LANDED

The same deftest verifies the two relations SEPARATELY:
- The complete C1 set: `(= eligible marked)` - every final application
  occurrence of the compiled tree carries exactly its marker (981
  applications over the compiled tree; the fixture's library wrapping and
  generated calls included; 987 counted on the stamped tree).
- The oracle set: 2 sites of 981 - the strict-inequality assertion pins
  that uncredited helpers, duplicate-content occurrences and
  transformation-generated applications are C1 occurrences but NOT oracle
  sites (the bijection is oracle-site to its selected compiled occurrence,
  never the full set).

### 7. Preflight bounds - recorded, then verified

The bounds table above was recorded by the interrupted session BEFORE any
tagged run (compile-only measurement). The resume session re-measured every
number compile-only - all match exactly - and the tagged run then executed
under the restated 4,000,000 / 1,000,000 ceilings (neither raised; the
binding adds 0 guest instructions and 0 trace records; both lanes
`:completed`).

## Audit of the interrupted session's diff (resume-by-state, hunk-by-hunk)

The eight-file diff was structurally sound but had NEVER BEEN RUN. Eight
defects found and fixed (details at the items above):

| # | Where | Defect | Disposition |
|---|---|---|---|
| 1 | linearize.cljc `project-rows` | blanket-origin fallback stamped `:call-provenance` across all calls when no side table given - the exact forbidden one-origin-across-the-set | removed, red-first (assertions added, red recorded, fix, green) |
| 2 | linearize_test.clj oracle-fixture refusal arm | bracket typo splatted the row into five non-rows - the arm never fired; the session's own test was red against its own code | row wrapped; green |
| 3 | latte_oracle_test.clj lowering block | `keys-of` shape mismatch - permanent self-inequality | `sem-keys` destructure for 4-element rows; green |
| 4 | latte_oracle_test.clj bijection | vacuous count-vs-vals check (always true for any map) | distinct-vals set count |
| 5 | derive_test.clj:878 | bare `(catch Exception ...)` in `.cljc` - kondo cljs error | file's portable conditional; kondo red recorded |
| 6 | clojure_callsite_test.cljc ambiguity arm | inline reader conditional where `:default` shadows `:cljd` (keyword `:default` as catch type on cljd) | the file's `failure-rule` helper |
| 7 | repl.cljc `packet-call-provenance` | each image derived twice (hash pass + provenance pass) | single derivation, coordinate patched after |
| 8 | linearize_test.cljc:410 | unused binding `by-path` (kondo warning) | removed |

Everything else in the diff audited correct as written: the path-grammar
join (linearize flatten-tree, vm/occurrences, encoder side tables), the
stamping chronology handling, the publish-side out-of-hash placement, the
refusal rules, the legacy-loadability paths, and the walker/semantic lane
assertions.

## Red-first evidence

1. Fallback removal (audit #1): new assertions that a bare caller origin
   yields EMPTY `:call-provenance` were RED against the interrupted code
   (recorded: FAIL at linearize_test.cljc:443, actual showed both call pcs
   blanket-stamped `[[3 [:source :medium :batch 0] ... [[3 0]]] [6 ... []]]`),
   GREEN after the fix.
2. Items 1-3 src surfaces: the four new deftests RED with the four src
   files reverted to HEAD - linearize/derive:
   `ArityException: Wrong number of args (3) passed to: project-rows`;
   callsite namespace: compile failure
   `No such var: publish/published-instruction-provenance` (covers both the
   publish and repl tests). Restored, all green.
3. Portability fix (audit #5): clj-kondo reported
   `derive_test.cljc:878:28: error: Unresolved symbol: Exception` with the
   bare catch (recorded), 0 errors after.
4. Oracle test fixes (audits #2/#3): the pre-fix tagged run's two
   recorded failures at :3552/:3553 (keys-of self-inequality) and the
   pre-fix linearize-test failure at the oracle-fixture arm are the red;
   the re-runs are green.

## Verification (foreground, explicit timeouts, nothing backgrounded at landing)

| Lane | Command | Outcome |
|---|---|---|
| The battery | `clojure -M:test -n yang.clojure-callsite-test` | 33 tests, 379 assertions, 0 failures, 0 errors |
| Touched semantic namespace | `clojure -M:test -n yin.vm.semantic.linearize-test` | 24 tests, 1,121 assertions, 0 failures, 0 errors |
| Touched derive namespace | `clojure -M:test -n yin.vm.semantic.derive-test` | 27 tests, 6,212 assertions, 0 failures, 0 errors |
| Oracle (narrow selection: binding + bijection + refusals) | `clojure -M:test -n yang.latte-oracle-test -v yang.latte-oracle-test/test-cc3-oracle-site-c1-binding` | 1 test, 20 assertions, 0 failures, 0 errors - both C lanes `:completed`, every executed site bound, identical K sets |
| repl safety (packet-to-vector refactor) | `clojure -M:test -n yin.repl-test` | 52 tests, 268 assertions, 0 failures, 0 errors |
| kondo (all 8 changed files) | `clj-kondo --lint` over the 8 files (via the ProcessBuilder lane; `clojure -M:kondo` is approval-gated in this worktree) | errors: 0, warnings: 0 |

Publish's touched tests live in the callsite battery (the cc2 pattern:
`publish-module!` / `published-call-site-table` coverage is there). The kit
contract test is N/A on this laptop (three measured non-completions, logged)
and is covered by shiva at landing, per the brief.

## Invariants

- Zero canonical change: vector + address identical with/without the side
  table (linearize test); stack/register images byte-identical with/without
  provenance (derive test); manifest stays the closed schema-1 key set with
  unchanged hashes and addresses (callsite test).
- Legacy unannotated packets remain fully loadable (callsite test; the
  retrieval answers nil; code paths untouched).
- Origins never minted: `validated-call-origins` refuses every non-origin
  shape; the oracle binding uses only a validator-shaped RECORDED admission
  coordinate; metadata never enters rows, CBOR, or hashing (side artifacts
  only, outside the manifest hash).

## Owner-visible items

1. dht forwarding need (the cc2 custody item, closed on the publish side):
   `dht/publish-name!` (`src/cljc/yin/vm/linker/dht.cljc:70`) still
   select-keyses `[:address :links]` and discards the publication result's
   `:call-sites` handle - which now carries `:provenance` beside `:record`
   and `:table`. dht.cljc is forbidden to this round, and the fix does NOT
   require editing it: the publish side exposes the full retained handle
   (`publish-closure!`'s result `:call-sites {:record ... :table ...
   :provenance ...}`). The one-line forwarding need for the dht owner: add
   `:call-sites` to dht.cljc:70's select-keys (or forward the record
   address) so DHT publications retain their provenance handle.
2. D1 dependency (the datom-lane restriction, item 4) - restated: the
   entity-to-occurrence bridge is D1's to land; cc3's claims are row-lane
   only.

## Unverified items

- The suite-scale battery and the kit contract test: shiva at landing (per
  the brief; not run here).
- The full oracle suite: shiva-scale; only the cc3 deftest ran (per the
  brief).
- cljs/cljd lanes: not run on this laptop (JVM-only verification per the
  brief). The portable-catch fixes exist to keep those lanes compilable;
  verified by kondo's dual clj/cljs analysis (0 errors), not by a lane run.
- `src/dev/latte_oracle_tables.clj` regeneration: untouched by cc3 (evidence
  regeneration is D1's round, R7) - the binding reads compiled fixtures
  directly and writes no evidence files.
- Four-host publication blast radius (design 3.3): prelude republication on
  Node/Dart/Jolt is a D1/cc4 concern; this round's publication changes are
  additive side artifacts only.
