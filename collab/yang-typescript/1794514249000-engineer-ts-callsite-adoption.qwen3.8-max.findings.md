Completed-GMT: 2026-11-13 UNKNOWN GMT
Completed-Local: 2026-11-13 UNKNOWN Asia/Ho_Chi_Minh
Coding-Agent: qwen (Qwen Code / qwen3.8-max — yang.typescript engineer seat)
Session-ID: C10B089C-226A-4F67-BDA3-0F9916FD93DF

# Findings: ts callsite-provenance adoption — `:yang/call-site` v1 on every final `:application` occurrence

Staged at the WORKTREE ROOT as instructed (the canonical collab copy also exists at
`/Users/sto/workspace/datomworld/collab/yang-typescript/1794514249000-engineer-ts-callsite-adoption.qwen3.8-max.findings.md`).

---

## 0. READ THIS FIRST — no shell existed, so there is no execution evidence and no commit

`tool_search` was run **twice** this session (queries: `run shell command bash execute
monitor`; `git commit add stage repository shell command execute terminal process`). Both
times the registry returned only `computer_use__*`, `enter_worktree`, `exit_worktree`,
`exit_plan_mode`, `task_stop`, `send_message`, `web_fetch`. **There is no
`run_shell_command` and no `monitor`.** Per the standing project note, `computer_use` is
not to be re-prompted as a backdoor shell (the owner declined that install), and an `agent`
subagent does not acquire tools its parent lacks.

Therefore, with no possibility of executing anything:

| Required | Status |
|---|---|
| G0 `clojure -M:test -n <ns>` on the new callsite ns | **NOT RUN — impossible.** Zero commands executed. |
| G0 on the touched ts namespaces | **NOT RUN.** |
| Regression set `typescript-provenance-test` + five forgery legs (6/48) | **NOT RUN.** The `6/48` figure is quoted from the brief / a prior seat's findings, **not** reproduced. |
| Regression set `float-address-test` (5/20) | **NOT RUN.** Same: `5/20` is quoted, not reproduced. |
| TDD: observe RED, then GREEN | **NOT DEMONSTRATED.** No red and no green was ever observed. §3 records what *would* have been red, derived by `grep`, not by running. |
| Equality-gate evidence gathered locally | **NONE by execution.** Only by construction + static argument (§5). |
| **Commit on `callsite-ts-adoption`** | **NOT DONE — impossible.** No `git`. All work is **uncommitted working-tree state**. |
| `git status` / `git diff` / `git log` oneliner | **UNAVAILABLE.** Git state read from `.git` files only (§0.1). |
| Code edits | **DONE** (§1). |
| Static blast-radius analysis | **DONE, exhaustively** (§2, §3). |

Every correctness statement below is a claim about **code I read**, not behaviour I
observed. Nothing here should be read as "tests pass". The orchestrator must run the
gates before crediting any of it — and per this track's own history (three rounds went
"locally green, zero ledger rows moved"), a round that did not run the instrument must
not claim rows. This round did not even run its own focused suite.

### 0.1 Git state, read from `.git` files (not from git)

- `/Users/sto/workspace/datomworld-callsite/.git` → `gitdir: /Users/sto/workspace/datomworld/.git/worktrees/datomworld-callsite`
- `/Users/sto/workspace/datomworld/.git/worktrees/datomworld-callsite/HEAD` → `ref: refs/heads/callsite-ts-adoption`

Branch is as expected. I could **not** resolve the tip SHA, so I could not confirm the
brief's stated base `b134207f`, and could not check whether another seat moved HEAD or
wrote foreign files into this worktree mid-round (a known hazard on these shared seats).
Working-tree dirt is undetectable without git (the index is zlib/binary).

Expected `git status --porcelain` at handoff — 6 modified, 2 untracked:

```
 M src/cljc/yang/typescript/antlr/lower.cljc
 M src/cljc/yang/typescript/antlr/prelude.cljc
 M src/cljc/yang/typescript/antlr/safepoint.cljc
 M test/yang/typescript/antlr/linked_prelude_test.cljc
 M test/yang/typescript/antlr/lower_portable_test.cljc
 M test/yang/typescript/antlr/lower_test.clj
?? .qwen/skills/clojure-paren-balance-without-reader/SKILL.md
?? test/yang/typescript_callsite_test.cljc
```

`.qwen/skills/…/SKILL.md` is **not mine** and is excluded from the intended commit per
instruction. This findings file at the worktree root is also not part of the intended
commit (the commit set is exactly the six modified files plus
`test/yang/typescript_callsite_test.cljc`).

### 0.2 The exact commands to run (no counts attached — none were observed)

```
cd /Users/sto/workspace/datomworld-callsite
git status --porcelain                        # confirm exactly the 6 M + the new test file

# G0 — the new callsite namespace (11 deftests + 2 helpers; count UNOBSERVED)
clojure -M:test -n yang.typescript-callsite-test

# G0 — the touched ts namespaces
clojure -M:test -n yang.typescript.antlr.lower-portable-test
clojure -M:test -n yang.typescript.antlr.lower-test
clojure -M:test -n yang.typescript.antlr.linked-prelude-test
clojure -M:test -n yang.typescript.antlr.safepoint-test
clojure -M:test -n yang.typescript.antlr.prelude-module-test

# Mandatory regression set — expected 5 tests / 20 assertions (NOT reproduced here)
clojure -M:test -n yang.typescript.antlr.float-address-test

# Mandatory regression set — expected 6 tests / 48 assertions (NOT reproduced here)
clojure -M:test -n yang.call.exception-contract-test \
  -v yang.call.exception-contract-test/typescript-minted-forgery-test \
  -v yang.call.exception-contract-test/typescript-copied-wrapper-forgery-test \
  -v yang.call.exception-contract-test/typescript-mutation-substitution-test \
  -v yang.call.exception-contract-test/typescript-extracted-seal-stamping-test \
  -v yang.call.exception-contract-test/typescript-store-root-unreachability-test \
  -v yang.call.exception-contract-test/typescript-provenance-test

# Equality gate: the same .cljc on the other lanes (orchestrator, shiva/jupiter)
#   node lane + dart lane over yang.typescript-callsite-test
#   and yang.typescript.antlr.lower-portable-test
```

Intended commit (NOT performed):

```
git add src/cljc/yang/typescript/antlr/lower.cljc \
        src/cljc/yang/typescript/antlr/prelude.cljc \
        src/cljc/yang/typescript/antlr/safepoint.cljc \
        test/yang/typescript/antlr/lower_test.clj \
        test/yang/typescript/antlr/lower_portable_test.cljc \
        test/yang/typescript/antlr/linked_prelude_test.cljc \
        test/yang/typescript_callsite_test.cljc
git commit -m 'feat(yang.typescript): the callsite-provenance adoption — :yang/call-site v1 on production paths'
git status            # confirm; do NOT merge, do NOT push, touch no other branch
```

---

## 1. Changed files, with line ranges

Six files touched: three production, three existing-test repairs, plus one new test
namespace. **Nothing under `src/cljc/yang/core/`, `src/cljc/yang/clojure.cljc`, or
`src/cljc/yin/` was edited** — the generic core was consumed read-only, as bounded.

### 1.1 Production (the adoption)

| File | Lines | Change |
|---|---|---|
| `src/cljc/yang/typescript/antlr/lower.cljc` | **2262-2289** | New `;; Call-site occurrence contract C1 (Addendum A, design §2)` block: the reserved key + version, the coordinate tuple `[origin root-address path :yang/call-site 1]`, the projection rule (the key drops out of canonical rows into the EXISTING generic frontend-metadata side table), the three-way distinction from `:yang/site` **and** from the exception track's `cell/dsg__new-provenance` / `cell/dsg__provenance` family, and the all-or-nothing coverage rule that motivates the `lower-unit*` seam. |
| | **2291-2296** | `lower-module-body` → `(u/annotate-call-sites (module-body (context packet)))`; docstring + "C1 finalized". |
| | **2299-2306** | **NEW** `defn- lower-unit*` — the unmarked seam; body is the old `lower-unit` body verbatim. |
| | **2308-2314** | `lower-unit` → `(u/annotate-call-sites (lower-unit* packet))`. |
| | **2329-2345** | `lower-units`: `mapv lower-unit` → `mapv lower-unit*`; return is `(u/annotate-call-sites (with-runtime prelude main))`. |
| | **2347-2361** | `lower-packet`: `(lower-unit packet)` → `(lower-unit* packet)`; return is `(u/annotate-call-sites (with-runtime prelude main))`; docstring notes C1 finalization. |
| | *2371-2372* | **Unedited on purpose**: `stage-config`'s `:lower` delegates to `lower-packet`, so `lower-transform` / `open-stage` / `step-stage` (`:2375-2403`) inherit finalization. This is the design's "streaming stage" leg. |
| `src/cljc/yang/typescript/antlr/prelude.cljc` | **4364-4370** | The linked **`js`** module tree `module-uast` → `(u/annotate-call-sites (core/module-tree module-definitions []))`; docstring extended with the "markers publish beside its manifest, the manifest keeps schema 1 and its address does not move" rationale. Byte-for-byte the shape already landed in the read-only core at `yang/core/call.cljc:168-175` and in `yang/clojure/prelude.cljc:108-112`. No `:require` line changed (`yang.core.uast :as u` was already there at `:68`). |
| `src/cljc/yang/typescript/antlr/safepoint.cljc` | **186-197** | The linked **`jssp`** module tree `module-uast` → annotated the same way. In scope per design `:278` ("Cover … bundled and linked `js`, `yang.call`, **and safepoint runtime**") and `:161` (family 1 names "TypeScript **`js/jssp`**"). See judgement call J1. |

### 1.2 Existing-test repairs — the complete blast radius was 6 assertions in 3 files

| File | Lines | Repair |
|---|---|---|
| `test/yang/typescript/antlr/lower_test.clj` | **71-79** | The golden builder `cells` (used only by `var-declaration-golden-test`) wraps its result in `u/annotate-call-sites`. Repairs the three goldens at `:90` (`"var x = 1;"`), `:96` (`"let y = 2;"`), `:102` (`"let z;"`). |
| | **140-145** | `literal-golden-test`'s BigInt expectation — the `js/string->bigint` application — wrapped in `u/annotate-call-sites`. |
| `test/yang/typescript/antlr/lower_portable_test.cljc` | **206-217** | `hand-packet-golden-test`'s literal expected tree wrapped in `u/annotate-call-sites`. **This was the only portable breaking site**, i.e. it would have gone red on Node and Dart as well as the JVM. |
| `test/yang/typescript/antlr/linked_prelude_test.cljc` | **61-63** | `jssp-module-uast-is-one-wide-application-test`: `(dissoc (last operands) :tail?)` → `(dissoc (last operands) :tail? :yang/call-site)`, extending the file's own existing `:tail?`-stripping idiom rather than inventing a new one. Broken only because I marked `jssp` (J1). The sibling assertion at `:67-70` targets a `:literal` and needed no change. |

**Repair idiom — CORRECTED in FIX-ROUND-2 (item 3a).** Five of the six repairs finalize
the *expected* side with the same generic walker (`lower_test.clj:71-79` and `:140-145`,
`lower_portable_test.cljc:206-217`): `(u/annotate-call-sites <literal tree>)` keeps each
golden a literal, hand-written tree and additionally pins that the producer's output is
*exactly* the shared walk's output over it, whereas a stripped comparison would stop
pinning the marking at all.

**The sixth does the opposite, and my original §1.2 wording was wrong to imply otherwise.**
`linked_prelude_test.cljc:61-63` strips the marker from the **PRODUCED** side —
`(dissoc (last operands) :tail? :yang/call-site)` removes `:yang/call-site` from
`hooks/module-uast`'s delivered `require` operand before comparing it to
`(u/sexp->uast '(require (quote js)))`. It is a *strip-the-actual* repair, not a
finalize-the-expected one, because that assertion's whole purpose is to compare one operand
of the published module tree against a plain `sexp->uast` rendering; re-deriving the expected
side through the finalizer there would have obscured what the assertion is about. sol
verified that precision is preserved — the assertion still pins the operand's operator, its
literal `(quote js)` operand, and the absence of everything except the two normalization keys
— so the choice stands; only my description of it was inaccurate. The repo's prior art for the
stripping direction is the `(dissoc … :tail?)` idiom already on that very line, and the private
`strip-call-sites` at `test/yang/clojure_callsite_test.cljc:645-651` (used there for a
different purpose: building *unclaimed* inputs for the refusal legs).

### 1.3 New test namespace

| File | Lines | Content |
|---|---|---|
| `test/yang/typescript_callsite_test.cljc` | **1-728 (NEW)** | ns `yang.typescript-callsite-test`, `.cljc`, **11 deftests + 2 private check helpers**, mirroring `yang.clojure-callsite-test`'s shape. Portable by construction: every fixture is a hand-built CST packet via `yang.typescript.antlr.lower-portable-test`'s public builders (`packet`, `program`, `var-stmt`, `num-expr`, `name-expr`, `log-stmt`) plus two packets copied from that file's own fixtures (`while-x` → one `:loop` site; `function-f` → one `[:call "f" 0]` site). No local parser is involved, so JVM/Node/Dart all compile the same input. Detailed deftest-by-deftest in §6. |

Paren balance of the new file was verified without a reader, using exact-count anchored
ripgrep regexes over every closing line (e.g. `another tree"\){5}$`,
`of its own"\){6}$`, `both origins"\){8}$`, `project ast\){7}$`, `fn-ast\){6}$`) — all
35 top-level forms confirmed balanced. This caught three genuine off-by-one over-closes
during authoring, which were fixed by extracting `check-publication-claim` and
`check-idempotent` helpers. The `clojure-paren-balance-without-reader` project skill
describes the technique.

---

## 2. What the implementation does

### 2.1 One finalization walk per public entry point, at the outermost boundary

Coverage is enforced **all-or-nothing** at two boundaries (§4.2), so marking had to
land after canonical renaming and tail marking, over the **complete composed** tree.
`lower-packet` and `lower-units` compose `lower-unit`'s output with their own generated
applications — `js/run-main`, the two `require`s, `js/init!`, and every `u/then` /
`u/seq-nodes` sequencing lambda. Annotating inside `lower-unit` alone would leave all of
those unclaimed beside a marked unit: a **partial** claim, which `publish-module!` and
`program-batch` both refuse. Hence the private `lower-unit*` seam — the composing entry
points build from unmarked units and walk the composed result **once**. This also
satisfies design `:65`: *"Finalize paths only after macro expansion, generated-name
canonicalization, wrappers, module/SCC assembly, and tail marking that determine the
named tree … A frontend cannot claim adoption by marking just its parser or user-call
lowering arm."*

Ordering: `names/canonicalize` → `u/mark-tails` → `u/annotate-call-sites`. All three
rebuild nodes with `assoc`/`update` (`yang/core/names.cljc:36-58`,
`yang/tails.cljc:11-35`, `yang/core/uast.cljc:136-151`), so each preserves the others'
plain keys **and** node metadata — which is why the `:yang/site` safepoint marks and the
`:tail?` flags survive finalization. Finalizing last guarantees the marker set describes
the tree that is actually handed out. `annotate-call-sites` is idempotent
(`core/uast.cljc:130-134`), so a host that calls the public `lower-unit` and then
re-finalizes cannot double-claim; `idempotence-test` pins that.

Performance note: the bundled profile walks the ~481-definition prelude once per
`lower-packet`. Depth is unchanged — `annotate-call-sites` recurses over the same grammar
slots `mark-tails` already recurses over, and `prelude.cljc:4306-4319` deliberately
**balances** the definition sequence precisely to keep that recursion shallow.
`lower_portable_test.cljc:322-327`'s `tree-depth < 512` pin still holds, because a new
map *key* adds leaves at an existing level, not a level.

### 2.2 The bundled composition fragments are deliberately NOT marked at their own `def`

`prelude/functions-uast` (`prelude.cljc:4321`), `prelude/uast` (`:4327`),
`safepoint/uast` (`:127`), `safepoint/noop-uast` (`:133`), `safepoint/program`,
`safepoint/linked-program` are **composition fragments, not publications**. They are
marked — but only as part of the tree the owning publication finalizes:

- bundled profile → `lower-packet` / `lower-units` walk `(u/then prelude/uast main)`;
- linked profile → the `js` / `jssp` module trees are finalized at their own `def`.

This follows design `:138`: *"Apply common finalization at the **owning publication**,
including direct hand-built UAST accepted as frontend output."* It also avoids marking
the prelude twice per `lower-packet`, and avoids manufacturing a **partially** marked
tree in the hosts that compose `hooks/uast` with a row-round-tripped (therefore
unmarked) derived AST — `test/yang/typescript/antlr/e2e_test.clj:112-116` and
`safepoint_test.clj:141-143`. See judgement call **J2**.

Consequence worth stating explicitly: `float-address-test`'s `prelude-address-test` and
`prelude-float-discipline-test` both read `prelude/uast`, which I did **not** change, so
its pinned golden root (`segment/blake3-6500c67e…bb2c`, `float_address_test.cljc:101-105`)
cannot move. Same for `program-address-test`'s hand-built `float-program` (`:112-131`,
golden at `:140-145`). This is a large part of why I expect the mandatory 5/20 to hold —
but it is an argument, not a measurement.

### 2.3 No parallel mechanism was invented

The ts frontend calls `yang.core.uast/annotate-call-sites` (the generic walk) and nothing
else. The markers reach the side table through the pre-existing
`yin.vm/ast-side-tables` → `encoder/project` path, which picks the key up generically
because it extracts *any* node key namespaced `"yang"`
(`yin/vm.cljc:1203-1220`, the `yang` filter in `frontend-meta-entries`) — the same door
`:yang/site` already uses. The origin is always the **composition's** admission origin,
derived at `encoder.cljc:139-143` as `(vm/source-origin source-medium batch-token j)`;
the frontend stamps none. `stage-envelope-test` pins both halves of that. Validation uses
`yang.clojure`'s public `validate-call-site-coverage!` / `validate-ast-call-sites!` —
reused, not copied, because `yang/core/uast.cljc:126-128` names `yang.clojure` as the
contract's validation owner. See judgement call **J4**.

---

## 3. What the pre-change behaviour was — the RED state I could not observe

Before these edits, `grep annotate-call-sites src/cljc/yang/typescript/` returned **zero**
matches, so no ts tree carried the key. Derived from that, the following would have been
RED pre-change (this is an inference from `grep` plus reading the assertions, **not** an
observed run):

- `equality-gate-test`, `stage-envelope-test`, `boundary-test`,
  `cross-host-equality-test`, `canonical-invariance-test` (third leg),
  `idempotence-test` (second leg), `publication-claim-test` — all would fail, because
  `marked metadata` was `#{}` while `vm/application-occurrences` of the same tree was
  non-empty.
- `encoder-claim-test` — would fail differently: `(encoder/program-batch ast)` returned
  the **5**-element unclaimed shape, not 6, so `(nth batch 5)` did not exist.
- `validation-failure-test`, `safepoint-independence-test` — would error at their `let`
  bindings (`(first markers)` on an empty vector → nil → `(take 3 nil)`).
- `export-audit-pin-test` — the one deftest that would have been **GREEN** pre-change.
  It pins *absence*, and the absence it pins is unchanged by this round. That is exactly
  what an audit pin is for.
- The 6 golden assertions in §1.2 — would have been GREEN pre-change and went RED with
  the adoption, which is why they are repaired in the same change set.

The three golden-repair files are therefore the honest "red first" artefacts of this
round: they encode behaviour that *changed*. I could not demonstrate the sequence.

---

## 4. Static blast-radius analysis (the substitute for the gates I could not run)

Method: two parallel read-only `Explore` subagents (one extracting the design +
orchestrator rulings, one enumerating every ts-tree comparison site in `test/`), then
hand-verification of every load-bearing claim against source myself.

### 4.1 Equality assertions — 6 broken, 6 repaired, nothing else

I enumerated all 20 files under `test/yang/typescript/antlr/` plus every file elsewhere in
`test/` that touches `yang.typescript.antlr.lower` or `.prelude`. The broken sites are
exactly the 6 in §1.2. Everything else is safe, by mechanism:

- **Rendered / outcome comparisons** (the large majority): `prelude_parity_test`,
  `prelude_test`, `class_prelude_test`, `object_parity_test`, `object_e2e_test`,
  `e2e_test` (`every-vm=` → `render/output`), `corpus_test` (rendered lines vs the Node
  oracle; `packets-are-current-test` compares **CST** packets, never UAST),
  `source_call_test`, all of `test/yang/call/*`, `test/yang/cross_lang_test.clj`.
- **Both-sides-lowered comparisons**, equal because both sides move together:
  `lower_test.clj:404-419` and `:556-567` (`class-renaming-law-test`),
  `lower_portable_test.cljc:249-263`, `:272-291`, and
  `transform-routes-programs-and-diagnostics-test` (`:330-345`) — the last compares
  `{:yin/batch [(lower/lower-packet var-x)] …}` against `lower-transform`'s output and is
  safe *specifically because* `stage-config`'s `:lower` delegates to `lower-packet`.
- **Slot / operator / metadata / `pr-str`-length reads** that never compare a whole tree:
  `read-golden-test`, `site-marks-test`, `site-marks-survive-test`,
  `linked-and-bundled-profiles-test` (relative `<` on `pr-str` length — the gap widens),
  `linked-source-requires-convention-before-js-test` (compares `(:operator %)` only).
- **Row / root / address comparisons**: `float_address_test.cljc` — 5 deftests / 20
  assertions, matching the brief's stated 5/20 exactly. Unaffected per §2.2.
- `safepoint_test.clj:154-199` filters `:yin/frontend-metadata` on
  `(= :yang/site (nth % 3))`, so new C1 rows are filtered out and the `kinds` frequencies
  are unchanged; `:201-203` compares two identical pipeline runs.
- `linked_prelude_test.cljc:101`, `:120` compare `hooks/module-uast` / `prelude/module-uast`
  against `(:ast spec)` — the same var on both sides. No golden address is pinned anywhere
  in that file or in `linked_harness.cljc` (verified: the only `blake3`/`segment/` literals
  under `test/yang/typescript/` are the two in `float_address_test.cljc:102` and `:141`).

No `strip-call-sites`-equivalent existed anywhere in the ts test tree before this round.

### 4.2 The two boundaries that REFUSE partial marking — both verified safe

1. **`yin.vm.encoder/program-batch`** → `check-call-site-claim!`
   (`yin/vm/encoder.cljc:275-306`, applied eagerly over **every** member at `:400`):
   throws `:yang/call-site-incomplete-coverage` / `-unsupported-version` unless the
   markers exactly cover the projected tree. A member with **no** markers makes no claim
   and passes — which is why pre-adoption artifacts still load (design `:157`'s
   additive-only policy). Reachability from ts today: TypeScript is **not registered** in
   the REPL frontend catalog (`yin/repl/frontends.cljc:92-146` installs only clojure, php,
   python — corroborated by the manifest revisions at `:41-56`, where only
   `clojure-manifest` carries `:yang.frontend/revision "c1-1"`), and no ts test calls
   `program-batch`. The ts paths use `encoder/project` / `encoder/load`, whose
   `project-member` map-AST arm (`encoder.cljc:106-110`) emits side tables with **no**
   coverage check. `encoder-claim-test` exercises `program-batch` on a ts tree anyway,
   because that is the boundary a future catalog registration would hit.
2. **`yin.vm.linker.publish/publish-module!`** → `claims-call-sites?` + `call-site-claim`
   (`publish.cljc:183-222`, dispatched at `:313-321`): refuses
   `:yin.link.publish/incomplete-call-site-coverage` (or `-unsupported-call-site-version`)
   on a partial or bad claim. **This is the live one** —
   `test/yang/typescript/antlr/linked_harness.cljc:53-68` publishes `js` and `:70-78`
   publishes `jssp`. Both module trees are now **fully** marked at their own `def`, so both
   take the claimed lane. The three compositions that embed a marked ts subtree inside
   unmarked scaffolding were each checked:
   - `test/yang/call/conformance.clj:216-221` — **already guarded**:
     `(cond-> raw (claims-call-sites? raw) u/annotate-call-sites)` re-annotates the whole
     donor module tree, so the moment `lower-unit` emits a marker the donor's coverage
     becomes complete. This is the single most load-bearing line for the mandatory 6/48,
     and it was clearly written in anticipation of this round. The ts profile's `:unit` at
     `:133` is `(names/canonicalize (ts-lower/lower-unit packet))`, and `canonicalize`
     preserves the key (§2.1).
   - `conformance.clj`'s `compose` (`:271-283`) and `export-program` (`:257-263`) **do**
     produce deliberately partial trees — but they are only ever **run**: `run-program`
     (`:298-306`) → `h/run-linked` → `linked_harness/backends` (`:115-124`) →
     `vm/ast->datoms` or `sr-linearize/project` at **1 arity**, which passes
     `call-site-rows` nil so `by-occurrence` is nil and `:call-provenance` is `[]` with no
     validation (`yin/vm/semantic/linearize.cljc:255-287`). Same for `exception-call`
     (`:441-448`) and `run-exception-program` (`:470-493`).
   - `test/yang/typescript/antlr/source_call_test.cljc:33-42` builds its *published* donor
     from **PHP's** `lower-module-body` (unmarked until php adopts), so the donor stays
     wholly unmarked and publishes unannotated; its `bundled` / `ast` trees are partially
     marked but only run. **Note for the php seat:** that helper has no
     `claims-call-sites?` guard, so it becomes a publication refusal the moment php adopts
     C1 without adding one.
   - `exception_probes.clj:19-20`'s `boundary-donor` publishes a **python** donor —
     untouched. The kit reads only `js-prelude/runtime-keys`
     (`exception_contract_test.clj:117`) and `js-prelude/module-exports` (`:119`, `:121`),
     neither of which I changed.

### 4.3 Address / hash invariance — the chain verified by reading

`vm/ast->semantic-bytecode` hashes only §2.3 grammar slots and drops other node keys
(`yin/vm.cljc:1113-1186`), so `:root` and `:rows` are byte-identical marked or not.
Downstream: `publish.cljc:353-358` materializes the **schema-1** manifest first and the C1
side table never enters it (*"so identical specifications under distinct admissions share
one manifest address"*); `derive/stack-image` returns `{:image … :side-table …}` and
`cond->`s `:call-provenance` **beside** `:image`
(`yin/vm/semantic/derive.cljc:152-159`); `derive/register-image` assoc's it onto an
otherwise identical image (`:229-239`) and `publish.cljc:340-347` hashes `(:image …)` /
`(dissoc register-full :call-provenance)`. So flipping `js` and `jssp` from the unclaimed
lane to the claimed lane changes the *side* artifacts (a `:call-sites` key on the
publication result, a manifest record, provenance segments) and **not** the tree address,
the semantic/stack/register image hashes, `h-address`/`r-address`, or the manifest address.
Independent corroboration that the claimed lane is already production-proven for a ts
composition: `yang.core.call/module-uast` has been C1-marked since cc1
(`yang/core/call.cljc:168-175`) and `linked_harness`'s `published` delay publishes it
*first*, into the same store. This is the mechanism satisfying brief requirement 2, and
`canonical-invariance-test` pins it.

Also verified unaffected: `core/free-name-vec` over the newly-marked `module-uast`
(`yang/core/prelude.cljc:80-96` branches on `(:type node)` and returns `acc` for any
non-map value, so the marker's value `1` is a no-op and the key is never visited);
`core/module-spec` (`:138-159`) is AST-shape-agnostic; `vm/ast->datoms` writes only
grammar fields.

### 4.4 One real observable delta, decided consciously

`safepoint/derive-envelope` on a ts envelope changes behaviour. Today `derive-call-sites`
(`yang/safepoint.cljc:298-386`) finds no C1 rows and answers nil, so the derived envelope
carries `:yin/frontend-metadata []` and **no** `:yang.safepoint/correspondence`. After this
change it answers rows plus a correspondence. **No ts caller supplies `:injected-origin`** —
`safepoint/transform` (`:455`) calls the 3-arity, as do
`test/yang/typescript/antlr/e2e_test.clj:71-86`, `safepoint_test.clj:132-136`, and
`linked_prelude_test.cljc:249-262` — so the injected hook applications stay **unclaimed by
design** (`safepoint.cljc:303-306`: *"without one it is recorded as injected and left
unclaimed"*) and the derived envelope's C1 rows deliberately do **not** cover the derived
tree. That is safe today only because nothing re-projects a derived envelope: all four ts
call sites stop at `vm/semantic-bytecode->ast` / `tree-of` / `vm/validate-rows`, and
`check-projected-claim!` fires only on `project-member`'s already-projected arm
(`encoder.cljc:112-121`). **[SUPERSEDED per sol step-7 r2 + FIX-ROUND-2: the actual behavior
of routing a derived envelope through `encoder/project` is a SILENT DROP of the envelope's
C1 rows — `encoder.cljc:115` reads only member-local tables, `:158` replaces the envelope's;
the refusal claimed above does not happen. This is sol's P1 (inherited shared-encoder gap),
relayed for owner assignment (collab/exception-track/1794529200000); derived-envelope
re-projection stays OUTSIDE accepted C1 routes until the fix lands.]** See judgement call
**J3** (amended by sol to named obligation TS-SP-C1).

**Corrected per sol r2: `ts-sp-c1-obligation-test` pins both sides** (partial correspondence
with strict-subset proof; row-drop at re-projection with unchanged canonical batch — a fix
to the shared encoder FLIPS the documenting assertion rather than passing it quietly), so the
"no ts test asserts" claim is superseded. `insertion-is-deterministic-test` stays green because
`derive-call-sites` is deterministic (sorts by `jing/canonical-compare` and
`(sort-by (comp pr-str key) by-path)`).

### 4.5 Frontends that have adopted C1 in this worktree

Clojure only, plus the shared `yang.call` module in core. TypeScript after this round.
**Python and PHP: zero `annotate-call-sites` matches** under `src/cljc/yang/python/antlr/`
and `src/cljc/yang/php/antlr/` — the only `call-site` hits in the python prelude are prose
about `*args`/`**kwargs` (`prelude.cljc:1110`, `:1113`). So the two sibling adoption rounds
are still ahead, and `source_call_test.cljc`'s unguarded donor helper (§4.2) is theirs to fix.

---

## 5. The equality gate — what I could and could not gather locally

The adopted ruling (`collab/yang-call/1794980000000-orchestrator-two-relays-equality-and-h36-audit-inputs.md:10`,
adopted verbatim at `collab/yang-call/1795010000000-orchestrator-reply-equality-gate-per-frontend.md:6,9`):
*"the cross-host occurrence-key equality gate is part of EACH frontend's own acceptance —
not a separate post-all-three gate … it never compares across frontends … the per-frontend
gates are the whole gate."* Design `:266-272` defines what must be equal: identical final
canonical roots and the full set of `[origin root path]` keys on JVM, Node and Dart (plus
Jolt for the mandatory portable paths); identical C1 rows and per-format instruction
provenance; for TS/PHP packet routes, *"compare compilation of the same source-derived
validated CST"*; and a separate negative fixture where identical text under two distinct
supplied admission tokens must demand different origins.

**Evidence gathered by execution: NONE.** No lane was run — not JVM, not Node, not Dart, not
Jolt. I have zero `Ran N tests` lines to report.

What was built instead, and what it is worth:

1. **The fixture is host-independent by construction.** Every packet is hand-built through
   `lower-portable-test`'s public builders, whose ids are preorder *"as the parser numbers
   them"*. No local parser runs, which is exactly design `:271`'s TS leg — *"This proves
   compilation equality, not nonexistent local-parser parity."* The file is `.cljc` and uses
   the reference's `#?(:cljd Object :clj Exception :cljs :default)` catch idiom, so it loads
   on all three lanes. The ts local-parser restriction (`docs/design/yang.typescript.md:113-116,517-518`)
   is therefore never touched.
2. **`cross-host-equality-test`** is the local half of the gate, in three legs: (a) two
   independent lowerings of one packet answer one root and one coordinate set, and that set
   equals the occurrence relation recomputed from canonical rows alone; (b) an
   **independently finalized literal tree** — the `var x = 1;` module body re-expressed by
   hand as `u/app`/`u/lam`/`u/then`/`u/lit` source inside the test — answers the *same* root
   address and the *same* `[root path]` set as `lower-module-body` of the packet, so the
   paths are pinned as literal source data and a lane whose path arithmetic differed would
   fail against a literal, not against another host's answer; (c) the root node's *kind*
   decides whether `[]` is a coordinate (`lower-module-body` → `:application` root → `[]`
   claimed; `lower-unit` → `:lambda` root → `[]` not claimed), and every composed production
   tree claims its own root.
3. **`equality-gate-test`** runs the reference's core assertion over all eleven production
   trees: the eligible set — derived in-test from `vm/application-occurrences` and the row
   tags, independently of the producer's walk — EQUALS the marker set, on both the
   origin-bearing and the origin-free `[root path]` projection; both validators answer true;
   and every row is exactly the core's 5-coordinate tuple under one root.
4. **`stage-envelope-test`** pins origin semantics on the real streaming-stage envelope for
   both profiles: coordinates carry the **stage's** admission origin
   `[:source :yang.ts/program [:hand 0] 0]` (medium + the packet's unit as batch token, per
   design `:139`) and carry **none** of the test file's own origin — the frontend stamps no
   origin of its own.

**Still missing, and the orchestrator's to run:** the actual JVM / Node / Dart lane matrix
(plus Jolt for the mandatory portable paths — design `:247`: *"No frontend may drop Jolt
because shared code compiled on another host"*), and design `:272`'s **negative** leg (one
packet admitted twice under distinct tokens must yield different origins). I did not write
the negative leg: `stage-envelope-test` already pins that the origin is supplied by the
composition and never minted by the frontend, and minting a second admission token inside a
frontend test felt like testing the encoder rather than ts. **Say the word and I'll add it.**

**Gap I could not close without a shell:** a *pinned golden* of the actual coordinate set
(root address string + path list). Producing one requires running `vm/ast->semantic-bytecode`
once and reading the answer. Recommended follow-up for whoever runs this ns first: print
`(sort (marked-pairs …))` and `(:root …)` for `(lower/lower-packet var-x {:prelude :linked})`
and freeze them as literals — that upgrades leg 2(b) from "two derivations agree" to "all
lanes agree with a frozen value", which is what design `:266` actually asks for.

---

## 6. The new test namespace, deftest by deftest

`test/yang/typescript_callsite_test.cljc`, ns `yang.typescript-callsite-test`, 728 lines.
Its header docstring carries the required key-family citation (`:yang/call-site` vs
`:yang/site` vs the exception track's `cell/dsg__new-provenance` / `cell/dsg__provenance`,
lines 11-31) and states the equality-gate ruling with its citation and its
never-cross-frontend clause (lines 33-45).

Production trees covered (11): `lower-module-body`; `lower-unit`; `lower-units` (linked ×2
units, linked ×0 units, bundled); `lower-packet` (linked over three fixtures — plain `var-x`,
a `:loop`, a `:call` — and bundled); `lower-transform`'s envelope member under both profiles
(own deftest, own origin); `js` `module-uast`; `jssp` `module-uast`.

| # | deftest | Mirrors the reference's | Asserts |
|---|---|---|---|
| 1 | `equality-gate-test` `:267` | `equality-gate-test` | eligible == marked on both projections, both validators true, exact 5-tuple shape, single root, markers non-empty, over all 11 trees |
| 2 | `stage-envelope-test` `:292` | (new — design `:139`'s stage row) | the stage envelope's member is finalized; its coordinates carry the stage's admission origin; coverage validates; the test's own origin yields zero markers there |
| 3 | `boundary-test` `:317` | `boundary-test` | every `:application` and only an `:application` carries the key; every emitted node type is a grammar type (so the shared walk's "Unknown AST node type" refusal is unreachable from ts); literal payloads are never entered; the generated dispatch K set `{js/run-main js/call js/get-value js/global-get cell/new cell/set! require js/init!}` is all marked (design `:278`'s "generated member/constructor/runtime dispatch applications get their own K"); an immediately-applied lambda (cell binding, sequencing step) is an ordinary marked occurrence; both module trees mark their `yin/def` occurrences |
| 4 | `validation-failure-test` `:364` | `validation-failure-test` | all nine rules: identical-delivery dedupe; `-conflict`; `-unsupported-version`; `-nonexistent-path`; `-non-application`; `-incomplete-coverage`; `-malformed` (4- and 6-coordinate rows); `-duplicate-entry` under `{:declared? true}`; `-multi-claim` in all three validator modes plus the `:site`/`:origins` diagnostic contents. The non-application target path is **derived** from `vm/occurrences` + row tags (sorted by `pr-str` for host independence) rather than hardcoded, so it cannot rot |
| 5 | `safepoint-independence-test` `:466` | `safepoint-independence-test` | the `:loop` and `[:call "f" 0]` marks still reach `safepoint/sites`; the two keys never cross rows; the relation's keyword keys are exactly `#{:yang/call-site :yang/site}` (**no ts-private table**); coverage validates over the combined relation; and the mechanism split pinned both directions — the safepoint mark exists only in node `meta` and never as a plain key, the C1 marker only as a plain key and never in `meta` |
| 6 | `canonical-invariance-test` `:512` | `canonical-invariance-test` | identical `:root` **and** `:rows` marked vs stripped over all 11 trees; identical `sr-linearize/project` `:vector` and `:address`; the key present in `:yin/frontend-metadata` while absent from `pr-str` of `:yin/batch` and `:yin/source-positions` — brief requirement 2 (unitarity) pinned directly |
| 7 | `encoder-claim-test` `:549` | `encoder-claim-test` | `program-batch` on a finalized ts tree answers a **6**-element batch with verdict `[true]` (this member now *claims*); dropping one marker refuses with `-incomplete-coverage`; a version-2 marker refuses with `-unsupported-version`; a fully stripped tree still yields the **5**-element unclaimed shape — the additive-compatibility pin (design `:157`) |
| 8 | `publication-claim-test` `:583` (helper `:569`) | `published-module-test` | for `js` and `jssp`: the root is a marked wide application, `validate-ast-call-sites!` passes (exactly the eligible-vs-marked equality `publish-module!`'s `call-site-claim` computes before choosing the claimed lane), and the projected marker set is non-empty. The *real* publication witness is `linked_prelude_test.cljc`'s `^:slow publication-test` (`:158-166`), which publishes both modules through `linked_harness` and asserts `:ok` on all three vector formats — **not run here** |
| 9 | `idempotence-test` `:602` (helper `:592`) | (new) | re-finalizing is `=` to the tree and moves no coordinate, over the cheap + bundled trees — what makes a host composing an already-finalized unit safe |
| 10 | `cross-host-equality-test` `:611` | (new — the ruling's local half) | see §5 |
| 11 | `export-audit-pin-test` `:677` | (new — the F2 pattern of record) | see below |

**The export-audit pin (brief requirement 4).** I followed the *landed python F2* shape
(`test/yang/call/python_exception_test.clj:34-51`), which is strictly stronger than the ts
twin it mirrors (`test/yang/call/exception_contract_test.clj:118-123`): not merely a
deny-list over `module-exports` / `runtime-keys`, but a **closed set equality** over the
definitions that touch the private names, so a *renamed* wrapper also flips it red. Three legs:

1. **Deny-list over the guest-visible surface.** For both `js` and `jssp`: no name in
   `module-exports` or `runtime-keys` contains `call-site`, `callsite`, `call_site`, `dsg__`,
   or `provenance`.
2. **Closed set equality over `prelude/definitions`.** The set of definition keys whose form
   tree references any of `#{cell/dsg__new-provenance cell/dsg__provenance
   dsg__condition-exception}` must be **exactly `#{}`** — the concrete pin that the two key
   families never touch, since the js prelude authenticates a retained condition through its
   own sealed companion cell (`prelude.cljc:1653-1714`: `js.rt/retained-seal` + a
   wrapper-identity back-reference), not the VM's provenance doors. Same leg for
   `:yang/call-site` → `#{}`, pinning that C1 is compile-time frontend metadata and never a
   guest-readable definition.
3. **Completeness of the audited surface.** `prelude/module-exports` is exactly the
   `js`-namespaced keys of `prelude/definitions` (so the internal `js.rt/*` store keys are
   provably not exported), and `hooks/module-exports` is exactly `hooks/function-definitions`
   stripped — the latter mirroring `linked_prelude_test.cljc:87-88`'s existing pin. Leg 3 is
   what makes legs 1-2 an audit of the whole surface rather than a sample.

Pre-verified statically so these are green by construction, not by hope: `grep dsg__` over
`src/cljc/yang/typescript/` returns exactly one hit — my own new comment at
`lower.cljc:2278`; `grep provenance` over `src/cljc/yang/typescript/antlr/prelude.cljc`
returns exactly one hit, a comment at `:1669`.

---

## 7. The four judgement calls needing the orchestrator's ruling

**J1 — Is `jssp` in scope? I marked it.**
`safepoint.cljc:186-197` now finalizes the `jssp` module tree. Grounds: design `:278` says
"Cover … bundled and linked `js`, `yang.call`, **and safepoint runtime**", and `:161`'s
persisted-artifact family 1 names "TypeScript **`js/jssp`**". `jssp` is genuinely published
on a production path (`linked_harness.cljc:70-78`, witnessed by
`linked_prelude_test.cljc:153-157`), so leaving it unmarked would leave a published
production module without C1 — a coverage gap acceptance would find. Cost: it broke one
assertion (`linked_prelude_test.cljc:61-63`), repaired in §1.2.
**If you instead read "safepoint runtime" as the hook-insertion *stage* rather than the
published `jssp` module, revert `safepoint.cljc:186-197` AND
`linked_prelude_test.cljc:61-63` together** — reverting one without the other breaks the test.

**J2 — The bundled composition fragments are not marked at their own `def`.**
`prelude/uast`, `prelude/functions-uast`, `hooks/uast`, `hooks/noop-uast`, `hooks/program`,
`hooks/linked-program` stay unmarked as standalone vars; they are finalized only as part of
the tree the owning publication produces (§2.2), per design `:138` ("Apply common
finalization at the **owning publication**"). **The risk if you rule the other way:** marking
the fragments at their `def` makes every host that composes a fragment with an unmarked tree
— `e2e_test.clj:112-116` (`hooks/program` over a row-round-tripped derived AST),
`safepoint_test.clj`, `source_call_test.cljc:57-63` — produce a **partially** marked tree,
which is a false claim at any publication or batch boundary and is refused outright by
`publish-module!`. **The risk of my choice:** if any host publishes `prelude/uast` directly
as frontend output rather than composing it through `lower-packet`, that publication is
unclaimed. I found no such host in this worktree — the only direct consumers are
`lower.cljc:2284` (`with-runtime`) and the float/prelude tests. Ruling requested: confirm
"owning publication" is the right seam for ts, or name the fragment that must self-finalize.

**J3 — RULED by sol at step 7; no longer an open question. Now obligation `TS-SP-C1`.**
*(This entry replaces my FIX-ROUND-1 "ruling requested" framing, which asked whether the ts
safepoint path should supply an admission origin. sol ruled; the text below is the ruling as I
understand it and have implemented it.)*

Omitting `:injected-origin` is a **NAMED OBLIGATION, `TS-SP-C1`**: a ts derived envelope
carries **partial correspondence**, and that is *not* complete derived-tree adoption. The
injected `js.sp/loop` / `jssp/loop` hook applications are recorded in
`:yang.safepoint/correspondence` as `{:kind :safepoint/injected :path p}` with **no origin**,
while every surviving occurrence keeps its own incoming origin. Consequence, now a standing
rule rather than my judgement: **derived-envelope re-projection stays OUTSIDE the accepted C1
routes until the shared-encoder gap is resolved.** The gap sol found is P1 and lives in the
SHARED encoder at `yin/vm/encoder.cljc:115` (the already-projected member arm, which reads
`:frontend-metadata` off the *member* — where `safepoint/derive-envelope` never puts it, since
the rows ride the *envelope*) and `:158` (`project-envelope`'s `mapcat` over members, which
then overwrites the envelope-level relation with the empty concatenation). Net effect:
re-projecting a derived ts envelope **silently drops** its C1 rows with no refusal and no
diagnosis. That is cross-track owned and explicitly **not** this round's to fix, so no `src/`
file was touched in FIX-ROUND-2.

Implemented as `ts-sp-c1-obligation-test` (`test/yang/typescript_callsite_test.cljc:972-1035`),
a documenting regression test that **PASSES by pinning the limitation** and names the
obligation in every message, so the suite is not red-lit and a future fix in the shared encoder
flips it loudly rather than passing quietly. Part 1 pins the partial-correspondence shape
(correspondence present; surviving entries mapped and origin-bearing inside `:input`; injected
entries present and origin-less; claimed paths a **strict subset** of the derived tree's
application occurrences). Part 2 pins the row-drop at re-projection: rows present on entry,
`(:yin/frontend-metadata reprojected)` **`[]`** after, canonical `:yin/batch` untouched
(provenance-only drop), and the correspondence record still retrievable because
`project-envelope` assocs onto the incoming envelope.

**J4 — The C1 validator lives in `yang.clojure`, so a ts test depends on the clojure frontend.**
`yang/core/uast.cljc:126-128` states: *"The walk lives here, beside the other shared tree
transforms, so every frontend's module trees finalize the same way; **`yang.clojure` owns the
contract's validation over canonical rows**."* I therefore call
`yang/validate-call-site-coverage!` and `yang/validate-ast-call-sites!` rather than
reimplementing them — reimplementing would be precisely the "parallel mechanism" the brief
forbids. `yang.clojure` is genuinely portable (`.cljc`; its docstring says *"written in .cljc
format to run on both JVM and Node.js"*; its ns form carries
`#?@(:jolt [] :cljd [] :clj […] :cljs […])` reader conditionals at `:29-40`) and the
dependency is light (`dao.jing`, `yang.core.call`, `yang.core.uast`, `yin.vm`), with
`test/yang/clojure_callsite_test.cljc` as precedent. But **php and python will each inherit
the same cross-frontend dependency.** Ruling requested: authorize a follow-up lift of
`validate-call-site-coverage!` / `validate-ast-call-sites!` / `call-site-metadata` /
`eligible-application-sites` / `marked-application-sites` into `yang.core` (a core change, so
a STOP-and-report for me, not something I did), so three frontends are not all requiring a
fourth's namespace to validate a language-neutral contract.

---

## 8. Three citation notes on the brief itself

**C1 — the cited design path does not exist.** The brief's line 20 points at
`collab/yang-clojure/1794670000000-architect-callsite-provenance-**generalization**.gpt-6-astra.findings.md`.
The real artifact is
`collab/yang-clojure/1794670000000-architect-callsite-**generalization-design**.gpt-6-astra.findings.md`
(386 lines; siblings `.prompt.md` and `.stdout.log` under the same stem). `glob
collab/**/*callsite*` and `collab/**/*generalization*` both confirm no
`callsite-provenance-generalization` artifact exists anywhere in `collab/`. The superseded
PREP-DRAFT (`1794480000000-…PREP-DRAFT.md:26`) carries the same wrong filename, so this will
keep biting downstream seats. **I read the real one** and cite it by line throughout.

**C2 — the cited core line range overshoots the file.** The brief's line 22 says
`src/cljc/yang/core/uast.cljc:116-175`. That file is **152 lines**. The C1 block is
`:113-152` — header comment `:113-128`, `annotate-call-sites` `:129-151`, terminating in
`(= :application tag) (assoc :yang/call-site 1)` at `:151`. The described *content* is exactly
right (the reserved key, version `1`, the coordinate tuple, the projection rule, the module
publication wiring); only the range is stale. Similarly the brief's line 21 describes the
reference test as having "32 deftests"; `test/yang/clojure_callsite_test.cljc` is 2837 lines
with **38** `deftest` forms (cc3 grew it from 32, and it has grown again since —
`collab/yang-clojure/1794505596000-to-yinrepl-refactor-seat-cc3-merged-your-rebase-takes-my-test-file.md:12`
records "cc3 grew it from 32 to 44 deftests", so even that note is now stale). I mirrored the
reference's *shape*, not its count — see C3 for why 11 deftests is the right ts number.

**C3 — the design's mandatory TypeScript `publication.cljc` inventory item is still open, and
neither brief carries it.** Design `:168`, verbatim: *"The addendum names TypeScript
`publication.cljc`, but no `src/cljc/yang/typescript/antlr/publication.cljc` exists in the
inspected checkout. TS prelude publication is presently visible in its linked harness above;
its design describes later publication work (`docs/design/yang.typescript.md:1347,1545`).
Whether an in-flight seat supplies a new file, renamed path, or equivalent hook is **unknown —
the implementation round's inventory decides**. It remains a named mandatory inventory item,
not a silent omission or an invented present file."* Design `:383` repeats it as an owner risk.
Neither the brief nor the PREP-DRAFT mentions `publication.cljc` at all. **My inventory's
answer: the "equivalent hook" branch.** As of this worktree the ts frontend has exactly five
portable files (`lower`, `prelude`, `render`, `safepoint`, `scope` under
`src/cljc/yang/typescript/antlr/`) plus `src/clj/yang/typescript/antlr/cst.clj`, and no
`publication.cljc`; the ts publication surface lives inside `prelude.cljc` (`definitions`
`:4289`, `functions-uast` `:4321`, `uast` `:4327`, `module-name` `:4337`, `runtime-keys`
`:4342`, `module-definitions` `:4358`, `module-uast` `:4364`, `module-exports` `:4370`,
`free-names` `:4375`, `module-spec` `:4392`) and `safepoint.cljc:145-234` for `jssp`, and its
only witness is `test/yang/typescript/antlr/linked_harness.cljc:53-78`. I finalized both
module trees there. I did **not** create a `publication.cljc` — `:168` explicitly forbids
"invented present file". Note the asymmetry: design `:150`, `:280` and `:282` give PHP and
Python explicit `publication.cljc` anchors but TypeScript none.

---

## 9. Other unresolved items

- **Design `:284`'s full acceptance bundle is largely unmet**, and mostly unmeetable without a
  shell: no input/commit/profile pins beyond the branch name, **no C1 coverage counts**, no
  four-host results, no cross-host equality report, no before/after budgets (design `:336`'s
  ts row: static = user units + bundled and separately published `js/yang.call/jssp`, with
  source→packet and packet→UAST timed separately; dynamic = constructor/member/indirect
  dispatch, exception helpers, admitted suspension paths, prelude/helper volume counted), no
  legacy-load/missing-provenance refusal evidence beyond `encoder-claim-test`'s
  5-element-batch assertion, no D1 verification result. Design `:314-329` also demands finite
  numeric preflight bounds with no "TBD" — I have none, and exceeding a declared bound blocks
  that acceptance run. **Treat this round as implementation-complete, evidence-free.**
- **`docs/design/yang.typescript.md` has no C1 section.** `grep 'call-site|callsite|C1'` over
  that file returns one unrelated hit (`:1693`); §16, which the brief told me to read, does not
  describe the occurrence contract. `docs/` was outside my surface bounds so I did not touch
  it. Someone should document the contract in the ts design doc as `yang/core/uast.cljc:113-128`
  now does for the core, and record the J1-J4 rulings there.
- **REPL catalog registration is still outstanding for ts** (`yin/repl/frontends.cljc:92-146`),
  which is why my suite has no counterpart to the reference's post-expansion / REPL-retention /
  publisher-scope / `module-from-index` families (`post-expansion-coverage-test`,
  `twin-identical-content-distinct-origin-test`, `repl-*`, `module-from-index-c1-*`). Design
  `:137` records exactly this: *"Standard catalog does not currently register TypeScript;
  direct TS paths still require adoption."* **11 deftests is therefore the right ts count
  today, and that is a recorded decision rather than an omission** — but it also means the ts
  round cannot exercise the expander's `:expansion`-origin legs at all, and will need a second
  pass once ts is catalogued.
- **The php seat inherits a trap**: `test/yang/typescript/antlr/source_call_test.cljc:33-42`'s
  `source-module` publishes a donor built from **php's** `lower-module-body` with no
  `claims-call-sites?` guard, so it becomes a `:yin.link.publish/incomplete-call-site-coverage`
  refusal the moment php adopts C1 partially. `conformance.clj:216-221` is the guard to copy.

**STOP conditions: none triggered.** The generic core (`src/cljc/yang/core/`,
`src/cljc/yang/clojure.cljc`, `yin/vm.cljc`, `yin/vm/encoder.cljc`,
`yin/vm/linker/publish.cljc`, `yin/vm/semantic/linearize.cljc`,
`yin/vm/semantic/derive.cljc`, `yang/safepoint.cljc`) looked complete and coherent, not
mid-flight: cc1's `annotate-call-sites`, cc3's occurrence contract and the `program-batch`
per-member claim verdicts, and `83b52a0b`'s generic `:yin.module/call-sites` handling are all
present and mutually consistent, and `yang.core.call/module-uast` plus
`yang.clojure.prelude/module-uast` are already finalized — two working precedents to mirror.
Nothing needed adapting, so nothing was adapted. "Derive, don't persist" is respected: no
persisted fact was added — the marker is a plain node key that projection turns into a
side-table row, and the application predicate itself remains derived from canonical rows
(design `:57`). J4 is filed as a *flag*, not a redesign, per the brief's instruction.

---

## FIX-ROUND-1

Responding to the orchestrator's first execution results. **Recorded results, as reported to
me** (I still have no shell — `tool_search` re-confirmed no `run_shell_command`, no `monitor`,
no git tool — so these are the orchestrator's numbers, not mine, and I ran nothing):

- `LOWER` **28 tests / 777 assertions GREEN** — so `lower-test`, `lower-portable-test` and the
  other lowering namespaces pass with the adoption in place. This confirms my two golden
  repairs in those files (§1.2) were correct, and confirms `float-address-test`'s golden roots
  did not move for the lower namespaces.
- `SRC` **2 tests / 22 assertions GREEN** — `source_call_test`, the partially-marked-tree
  composition I flagged as run-only in §4.2. Confirms that analysis.
- `REG6` **6 tests / 48 assertions GREEN** — `typescript-provenance-test` plus the five forgery
  legs. **The mandatory exception-track regression set is intact**, which confirms the
  load-bearing claim in §4.2: `conformance.clj:216-221`'s `claims-call-sites?` guard absorbs
  the newly-marked `lower-unit` and re-finalizes the donor wholesale.
- `float-address-test` and `prelude-module-test` **UNVERIFIED** — the group load was killed.
  Still outstanding; my §2.2 argument that `float-address-test` cannot move (it reads
  `prelude/uast` and a hand-built `float-program`, neither in the change set) remains an
  argument, not a measurement. `prelude-module-test` reads `prelude/module-spec` /
  `module-exports`, and `module-spec` is AST-shape-agnostic (§4.3), so I expect green — also
  unverified.

Two defects were reported. Both are now fixed. Two files changed in this round; no other file
was touched, and **no production file changed** — both defects were in test code.

### FIX 1 — COMPILE ERROR `linked_prelude_test.cljc:66` `Unable to resolve symbol: definitions`

**Root cause: my §1.2 golden edit changed the paren count of the form it edited.** The
pristine assertion was two lines closing three levels on the second line:

```clojure
    (is (= (u/sexp->uast '(require (quote js)))
           (dissoc (last operands) :tail?)))          ; 3 trailing ')' : dissoc, =, is
```

My replacement split those three closers across two lines but wrote **four**:

```clojure
    (is (= (u/sexp->uast '(require (quote js)))
           (dissoc (last operands) :tail? :yang/call-site))   ; 2 : dissoc, =
        "the delivered require, minus its tail mark and its C1 marker"))   ; 2 : is, LET  <-- one too many
```

The stray fourth `)` closed the enclosing `(let [ast … operands … definitions …]` of
`jssp-module-uast-is-one-wide-application-test` one level early. Everything after it — the
four `(testing …)` blocks at former lines 64-80 — landed **outside** the `let`, so their
references to the `definitions` binding (first at line 66, `placeholders (take n definitions)`)
had nothing to resolve against. Hence `Unable to resolve symbol: definitions` at `:66` rather
than at the line I had edited: the symptom surfaced four lines downstream of the cause.

**Fix** (`test/yang/typescript/antlr/linked_prelude_test.cljc:61-63`): drop the extra closer so
the message line ends the `is` and nothing more.

```clojure
    (is (= (u/sexp->uast '(require (quote js)))
           (dissoc (last operands) :tail? :yang/call-site))
        "the delivered require, minus its tail mark and its C1 marker")
```

Net paren delta versus the pristine file is now **zero** (2 trailing on line 62 + 1 on line 63
= 3, exactly as before), so the namespace's balance is restored to its original state and the
only surviving difference in this file is `:yang/call-site` added to the `dissoc` key list plus
the message string. Verified by exact-count anchored regex: `its C1 marker"\)$` matches at
`:63` (one closer), and the untouched `placeholders))))` at `:70` is unchanged.

**Process lesson, recorded so the next round does not repeat it:** my paren-balance
verification (§1.3) was applied to the **new** file only — all 35 top-level forms of
`typescript_callsite_test.cljc` were checked with exact-count regexes, and that is where it
caught three real off-by-one over-closes during authoring. I did **not** apply the same check
to the three *existing* files I edited, on the reasoning that a small `edit` replacement was
low-risk. That reasoning was wrong: adding a message string to an `is` form changes which
closer lands on which line, which is precisely the class of error the check exists to catch.
**Rule going forward: any edit that adds or removes a line inside a nested form gets the same
exact-count regex treatment as a new file, and the net paren delta versus the pristine text is
computed explicitly.** The two `lower_test.clj` / `lower_portable_test.cljc` edits survived only
because `LOWER` ran green — luck of the draw, not verification.

### FIX 2 — SEMANTIC CONFLICT `typescript_callsite_test.cljc:308`: the TEST was wrong, not the stage

**Which side is wrong: the test.** The production stage path is correct and must stay marked.
The design is unambiguous that the streaming stage is a mandatory production path:

> `…generalization-design.gpt-6-astra.findings.md:278` (§5.3, the TypeScript work package) —
> **"TypeScript.** Cover `lower-module-body`, `lower-unit`, `lower-units`, `lower-packet`,
> **streaming stage**, bundled and linked `js`, `yang.call`, and safepoint runtime."

> same file, `:65` (§2.3 Finalization and transformations) — "Finalize paths only after macro
> expansion, generated-name canonicalization, wrappers, module/SCC assembly, and tail marking
> that determine the named tree. … Low-level functions returning fragments may defer
> finalization until an owning root and origin exist; **each executable/publication ingress
> must invoke the common finalizer. A frontend cannot claim adoption by marking just its
> parser or user-call lowering arm.**"

> same file, `:133` (§3.2 producer-to-consumer inventory, the TypeScript row) — "TypeScript
> packet/body/unit/multi-unit/**stage**: `yang/typescript/antlr/lower.cljc:2262-2363` | Cover
> bundled and linked runtime paths, canonical names and tail marks, all units and generated
> runtime calls. **Preserve packet/source association separately.**"

> same file, `:139` (§3.2, the "Common streaming stage: `yang/core/stage.cljc:25-59`" row) —
> "**It currently supplies medium plus packet unit as batch token.** Audit distinct
> re-admissions of the same unit: C1 requires fresh admission identity while retry keeps the
> token; packet identity alone is insufficient if reused for distinct admissions."

Read together, these four lines settle it. The stage is named twice in the mandatory coverage
inventory (`:133`, `:278`) and `:65` forbids deferring finalization past an executable ingress.
And `:139` fixes **where the origin comes from**: the stage supplies *medium plus packet unit
as batch token*, i.e. the origin is the **admission's**, minted by the composition — which is
exactly what `encoder.cljc:139-143` implements via `(vm/source-origin source-medium
batch-token j)`. So a `:bundled` stage envelope carrying 8+ coordinates under
`[:source :yang.ts/program [:hand 0] 0]` is the *required* behaviour, not a defect. Skipping
marking on the stage-envelope path would have violated `:65` and `:278` outright.

**Actual root cause of the false assertion:** a dead parameter. My helper was a copy of the
reference suite's `marked` (`test/yang/clojure_callsite_test.cljc:100-106`), which takes **no**
origin argument and projects each row's own origin:

```clojure
;; what I had — `origin` was bound and never used
(defn- marked-under
  "The `[origin root-address path]` of a C1 marker relation."
  [metadata origin]
  (into #{} (comp (filter #(= :yang/call-site (nth % 3)))
                  (map (fn [[o r path _ _]] [o r path])))
        metadata))
```

Because the filter never mentioned `origin`, `(marked-under metadata origin)` answered **every**
marker in the relation regardless of which origin it carried. The stage assertion
`(is (empty? (marked-under metadata origin)))` therefore asked "are there no C1 rows at all?"
instead of the question its own message claimed ("does any row carry *this file's* origin?") —
and correctly failed, since the stage envelope is finalized and does carry rows, under the
admission origin. The assertion message was right; the predicate underneath it was not.

**Fix** (`test/yang/typescript_callsite_test.cljc:145-159`): make `marked-under` genuinely
origin-scoped, so its name, docstring and arity match its behaviour.

```clojure
(defn- marked-under
  "The `[origin root-address path]` of the C1 rows of `metadata` claimed
   under `origin` — the ORIGIN-SCOPED marker set. Filtering on the row's
   own origin coordinate is what makes the query meaningful: a relation
   admitted under a DIFFERENT origin answers empty here, which is how
   `stage-envelope-test` proves the frontend stamps no origin of its own.
   (The origin-free projection a consumer derives from canonical rows alone
   is `marked-pairs`, below.)"
  [metadata origin]
  (into #{}
        (comp (filter #(and (= :yang/call-site (nth % 3))
                            (= origin (nth % 0))))
              (map (fn [[o r path _ _]] [o r path])))
        metadata))
```

The division of labour is now explicit and matches design §2.2: `marked-under` is the
**origin-scoped** query (the producer's correspondence claim), `marked-pairs` is the
**origin-free** `[root-address path]` projection (what a consumer derives from canonical rows
alone). `marked` is unchanged — still `(marked-under metadata origin)` — and every other call
site keeps its meaning, because each one queries metadata projected under the same origin it
passes: `equality-gate-test` (`project` uses `origin`), `safepoint-independence-test`
(`vm/ast-side-tables … origin`), and `canonical-invariance-test`'s third leg (`project` again).
I checked all four call sites individually rather than assuming.

**Additionally strengthened** (`:316-323`) so the intent cannot be misread again, and so a
blanket-stamp bug would be caught rather than merely permitted: the negative leg's message now
states what it actually pins, and a new positive leg asserts every C1 row of the envelope
carries exactly the one admission origin.

```clojure
          (is (empty? (marked-under metadata origin))
              "no C1 row of the envelope carries THIS file's origin: the
               frontend finalizes the tree and the composition admits it, so
               the frontend stamps no origin of its own")
          (is (= #{admission} (into #{} (map first) (marker-rows metadata)))
              "and every C1 row carries exactly the stage's admission
               origin — one origin, supplied by the composition, per the
               design's streaming-stage inventory row: the stage supplies
               medium plus packet unit as batch token")
```

The positive leg is the one that would catch a frontend inventing or blanket-stamping origins —
design `:69`'s prohibition ("do not mint a fake `:expand` merely to satisfy the shape") and
`:139`'s fresh-admission-identity requirement, in test form. Both profiles (`:bundled` and
`:linked`) run it, since the `doseq` covers both.

Balance re-verified after the edit by exact-count regex: `as batch token"\){6}$` matches at
`:323` — six closers for `is`, `let`, inner `testing`, `doseq`, outer `testing`, `deftest`,
which is the same count the pre-fix form had, so the deftest's shape is unchanged.

### Files changed in FIX-ROUND-1

| File | Lines | Change |
|---|---|---|
| `test/yang/typescript/antlr/linked_prelude_test.cljc` | **61-63** | Removed the stray fourth `)` that closed the `deftest`'s `let` early. Net paren delta versus pristine is now zero; the only surviving difference in this file is `:yang/call-site` in the `dissoc` key list plus the message string. |
| `test/yang/typescript_callsite_test.cljc` | **145-159** | `marked-under` now filters on the row's origin coordinate (was a dead parameter); docstring rewritten to state the origin-scoped vs origin-free division of labour. |
| | **316-323** | `stage-envelope-test`: negative leg's message corrected to what it pins; new positive leg asserting `#{admission}` is the complete set of C1 row origins. |

**No production file changed in this round.** `lower.cljc`, `prelude.cljc` and
`safepoint.cljc` are untouched since §1.1 — in particular the stage-envelope path still
finalizes, as design `:65`, `:133`, `:139` and `:278` require.

### Still outstanding after FIX-ROUND-1

- `linked_prelude_test.cljc` must be re-run: FIX-1 makes the namespace **load**, but nothing
  yet confirms `jssp-module-uast-is-one-wide-application-test` and the `^:slow publication-test`
  (which publishes both `js` and `jssp` through `linked_harness` — the real publication witness
  named in §6 row 8) both pass. That is the highest-value re-run in this round.
- `float-address-test` (5/20) and `prelude-module-test` remain UNVERIFIED from the first run.
- `stage-envelope-test` has never been executed in any form, so FIX-2's corrected expectation
  is itself unverified. It is the assertion most likely to need another pass: the positive leg
  assumes a single member index 0 in the stage envelope, which follows from
  `core/stage.cljc:36-38` wrapping `(lower state packet)` in a one-member `source-envelope`,
  but I could not run it.
- No commit was made (no git). The full working-tree state is now 6 modified files + 1 new test
  file, with `.qwen/` and this findings document excluded from the intended commit set (§0.1).

---

## FIX-ROUND-2

Step-7 verdict was **FIX-LIST**. The stage-envelope repair (FIX-ROUND-1 item 2) was ACCEPTED
with its citation verified (design `:65` / `:133` / `:278` for production-path finalization,
plus `:38-39` for origin ownership). Three in-scope items were assigned; all three are done.

**Scope discipline observed:** `src/` was **not** touched at all this round. The P1 sol found is
in the SHARED encoder (`yin/vm/encoder.cljc:115` and `:158`), owned cross-track, and is
therefore documented and pinned rather than fixed (§7 J3, item 3b below). Both changed files
are test-side: `test/yang/typescript_callsite_test.cljc` (grew 760 → **1035** lines, 11 → **14**
deftests) and this findings document. No tests were run (still no shell — `tool_search`
re-checked); no commit was made.

### Item (1) — P2 coordinate pin + the design-`:139` distinct-admission-token NEGATIVE fixture

**`test/yang/typescript_callsite_test.cljc:121-132` (new `project-under`)** — `project` with an
explicit batch token, so one packet can be admitted twice under two supplied tokens. This is
the only way to exercise admission freshness from inside a frontend suite.

**`:192-195` (new `row-origins`)** — the distinct occurrence origins a C1 relation carries.
Placed *after* `marker-rows` (`:187`) deliberately: I first wrote it above its dependency and
caught the forward reference before saving, since a `defn-` body resolving a later var is a
compile error, not a warning.

**`:818-846` (new `reviewed-var-x-paths`)** — the literal expected coordinate paths for the
smallest fixture, `(lower/lower-module-body var-x)` = `var x = 1;`. **Hand-derived, not read
back** from `vm/application-occurrences`, from the row grammar (`yin/vm.cljc:1017-1038`:
`:application` slots are `[:operator :node]` at position 2, `[:operands :nodes]` at 3,
`[:tail? :bool]` at 4; `:lambda` is `[:params :syms]` at 2, `[:body :node]` at 3) and the
design's path rule at `:39` (*"scalar child slot steps are row positions; a child in a `nodes`
slot is `[slot-position member-index]`. The root is `[]`"*). The tree is the checked-in golden
at `lower_portable_test.cljc:206-217`, so the derivation is reviewable against a literal:

```
[]                     the cell-binding application (root)
[[3 0]]                its operand 0 — (cell/new :js/undefined)
[2 3]                  the binding lambda's body — the outer `then`
[2 3 [3 0]]            that `then`'s operand 0 — the inner `then`
[2 3 [3 0] [3 0]]      the inner `then`'s operand 0 — (cell/set! …)
```

Five applications, five paths. The docstring also records what must **not** appear: the lambdas
at `[2]`, `[2 3 2]`, `[2 3 [3 0] 2]`, and every literal. This is the reference suite's style at
`clojure_callsite_test.cljc:119` — literal paths written out, `(:root tree)` substituted for
the address — applied to a ts fixture.

**`:849-859` (new `reviewed-var-x-root`)** — the reviewed root pin, **currently `nil`, and I am
flagging that explicitly rather than letting it pass silently.** A root is
`segment/blake3-<hex>` over the root row; it cannot be derived by hand and I have no shell, so
I cannot freeze it honestly. Inventing a plausible-looking hex string would be exactly the
fabrication this round's honesty clause forbids. While nil, `coordinate-pin-test` pins the root
**relationally** with three host-independent assertions that need no hash; filling the slot
from the first green run upgrades the pin to a frozen absolute golden with no other edit.
**Action for whoever runs this first:** set `reviewed-var-x-root` to
`(:root (:tree (project (lower/lower-module-body var-x))))`.

**`:862-895` (new `coordinate-pin-test`)** — two legs. Leg 1 asserts the hand-derived set
*is* the whole marker set, that it equals the eligible set the canonical rows imply (so hand
derivation and occurrence relation agree), that the origin-free path projection equals the
literal set, and that coverage validates. Leg 2 is the root pin: the `if-let` takes the frozen
branch when `reviewed-var-x-root` is set, and until then asserts the root is a well-formed
`segment/blake3-` address, is **identical with the markers stripped**, and is **identical after
a second finalization walk**.

**`:898-946` (new `distinct-admission-token-test`)** — the design-`:139` / `:272` negative
fixture, in two legs:

- *Distinct tokens → distinct origins.* One packet (`var-x`), two supplied batch tokens
  `[:hand 0]` / `[:hand 1]`. Asserts the two origins differ; each relation carries exactly its
  own single origin; the two coordinate **sets** differ; **but** the two canonical roots are
  equal and the two origin-free path sets are equal — *"an occurrence is (content address,
  origin), never content alone"* and *"cross-host equality is REPLAY equality, and must not
  turn occurrences into content identities"* (design `:272`). Coverage validates under each
  admission separately.
- *Retry keeps its token.* The same packet admitted twice under the SAME token reproduces the
  SAME origin and the SAME coordinate set — the other half of design `:139` (*"C1 requires fresh
  admission identity **while retry keeps the token**"*), pinning that a retry fabricates no new
  admission identity.

The first leg also asserts `(= [:hand 0] (:yang.cst/unit var-x))` — both admissions carry the
**same packet identity**, which is precisely why the supplied token must carry freshness:
design `:139`'s *"packet identity alone is insufficient if reused for distinct admissions."*

### Item (2) — P2 closed-set audit extended to `jssp`, plus a renamed-wrapper negative fixture

**`:709-760` (new shared helpers).** The audit was refactored out of the deftest body so the
real scan and the negative fixture run the *same* predicate — otherwise the fixture would prove
nothing about the production audit:

- `guest-surface-deny-strings` `:709-713` — the deny-list, now a named def.
- `exposed-name?` `:715-718` — the name-based leg as a reusable predicate.
- `exception-track-doors` `:721-729` — `#{cell/dsg__new-provenance cell/dsg__provenance
  dsg__condition-exception}`, documented as the DIFFERENT key family (landed `8c10c5b7`) and as
  what the js runtime uses instead (its own sealed companion cell, `prelude.cljc:1653-1714`).
- `audit-definition-users` `:732-742` — the closed set of definition keys whose form **TREE**
  references any of `names`.
- `audit-guest-c1-keys` `:745-751` — the same scan for `#{:yang/call-site}`.
- `renamed-hook-wrapper-definitions` `:754-760` — the negative fixture:
  `'[[js.sp/condition-slot (fn [c] (cell/dsg__new-provenance c))] [js.sp/read-slot (fn [s]
  (cell/dsg__provenance s))]]`. Both names are **neutral** — no `dsg__`, no `provenance` — and
  both bodies reach a reserved door.

**`:763-810` (`export-audit-pin-test`, rewritten).** The gap sol named is closed: the door-user
and guest-C1-key scans now run over **both** definition lists, via
`(doseq [[label definitions] [["js" prelude/definitions] ["jssp" hooks/function-definitions]]] …)`.
Previously only `prelude/definitions` was scanned, so a neutrally named `js.sp/…` wrapper
calling `cell/dsg__provenance` would have passed the audit while shipping exactly the leak it
exists to prevent. A third leg is the **negative fixture**, proving the audit is not vacuous:
`(is (= '#{js.sp/condition-slot js.sp/read-slot} (audit-definition-users
renamed-hook-wrapper-definitions exception-track-doors)))` — both renamed wrappers are caught;
`(is (= #{} (audit-guest-c1-keys renamed-hook-wrapper-definitions)))` — the two audits stay
independent; and a nested leg asserts
`(not-any? exposed-name? (map first renamed-hook-wrapper-definitions))`, i.e. **the name-based
deny-list leg alone would have MISSED both**, which is the concrete justification for the
tree-scanning closed set. The fourth leg (audited surface == whole guest-visible surface) is
unchanged in substance.

### Item (3) — findings amendments

**(3a) Golden-repair claim corrected — §1.2.** My FIX-ROUND-0 text said the goldens were
repaired by finalizing the expected side, implying all six. **Inaccurate: five of six do; the
sixth, `linked_prelude_test.cljc:62`, strips `:yang/call-site` from the PRODUCED side**
(`(dissoc (last operands) :tail? :yang/call-site)`) because that assertion's purpose is to
compare one operand of the published `jssp` module tree against a plain `sexp->uast` rendering.
sol verified precision is preserved; the choice stands and only my description was wrong. §1.2
now says so explicitly, names which five finalize and which one strips, and records why.

**(3b) J3 replaced by the ruled obligation `TS-SP-C1` — §7.** My FIX-ROUND-1 J3 asked the
orchestrator to *rule* on whether the ts safepoint path should supply an admission origin, and
framed the derived table as merely "not a complete-coverage claim". Per sol's ruling that
framing is replaced: omitting `:injected-origin` is **named obligation `TS-SP-C1`** — a ts
derived envelope carries **partial correspondence**, which is explicitly *not* complete
derived-tree adoption — and **derived-envelope re-projection stays OUTSIDE the accepted C1
routes until the shared-encoder gap is resolved.** The gap is P1, lives in the SHARED encoder at
`encoder.cljc:115` (the already-projected member arm reads `:frontend-metadata` off the member,
where `derive-envelope` never puts it — the rows ride the envelope) and `:158`
(`project-envelope`'s `mapcat` overwrites the envelope-level relation with the empty
concatenation), so re-projection **silently drops** the C1 rows with no refusal and no
diagnosis. Cross-track owned; not touched.

New documenting test: **`ts-sp-c1-obligation-test` `:972-1035`**, with `derived-medium`
`:954-957` and the `derive` helper `:960-969` (which calls `safepoint/derive-envelope` at its
3-arity, exactly as all four ts callers do — `safepoint/transform` at `yang/safepoint.cljc:455`,
`e2e_test.clj:71-86`, `safepoint_test.clj:132-136`, `linked_prelude_test.cljc:249-262`). **It
PASSES by documenting the limitation and names `TS-SP-C1` in every message; the suite is not
red-lit.** Part 1 pins the partial-correspondence shape: correspondence present; surviving
entries mapped and origin-bearing inside `:input`; injected entries present and
`(not-any? :origin injected)`; every claimed path a real application of the derived tree
`(every? eligible-paths claimed-paths)` — [FIX-ROUND-3: the expression as originally authored
had the arguments reversed; corrected at first execution and re-greened]; and the claim a
**STRICT** subset `(not= claimed-paths eligible-paths)`. Part 2 pins the row-drop at
re-projection: rows present on entry, `(is (= [] (:yin/frontend-metadata reprojected)))`
after, canonical `:yin/batch` identical (provenance-only drop), and
`:yang.safepoint/correspondence` still retrievable because `project-envelope` assocs onto the
incoming envelope. §4.4 SUPERSEDED per sol r2 (silent drop, not refusal — see the amendment
there); §7 J3 carries the ruling (named obligation TS-SP-C1).

**(3c) Admission-freshness obligation, recorded.** From sol's ask-1 qualification, as relayed to
me, the standing obligation is: **admission freshness is a property of the SUPPLIED batch token,
never of the packet.** One packet admitted twice as two distinct admissions must yield two
distinct occurrence origins; a retry of one admission keeps its token and therefore keeps its
origin. Packet identity alone is insufficient when reused for distinct admissions — design
`:139`, verbatim: *"It currently supplies medium plus packet unit as batch token. Audit distinct
re-admissions of the same unit: C1 requires fresh admission identity while retry keeps the
token; packet identity alone is insufficient if reused for distinct admissions."* Reinforced by
`:272`: *"A separate negative fixture admits identical text twice with distinct supplied
admission tokens and demands different origins. Cross-host equality is replay equality; it must
not turn occurrences into content identities."*

For ts concretely: `core/stage.cljc:36-38` derives the batch token from the packet's
`:yang.cst/unit`, and every fixture in this suite is built by `lower-portable-test/packet`, which
hardcodes `:yang.cst/unit [:hand 0]`. So **a ts host that re-admits the same unit for a genuinely
distinct admission must mint a fresh token at the composition boundary** — the frontend will not
do it for them, and must not. `distinct-admission-token-test` `:898-946` is the pin, and it
asserts the shared-packet-identity premise explicitly so the obligation cannot be satisfied by
accident. **Caveat, stated honestly:** I do not have sol's ask-1 text verbatim — only the
orchestrator's one-line summary — so this paragraph records the obligation as I derived it from
that summary plus design `:139`/`:272`. If ask-1 said more (a specific ts host, a specific
re-admission path, or a numbering rule for tokens), it is not captured here and should be
relayed so I can pin it too.

### Process note — one more off-by-one caught by the regex check, not by reading

Rewriting `export-audit-pin-test` introduced a fourth closing paren on the
`"…scans form trees"` line (`:797`), which closed the `deftest` early and would have left the
final `(testing …)` block top-level with an unmatched delimiter — the *same* defect class as
FIX-ROUND-1's `linked_prelude_test.cljc:66` compile error. The exact-count regex sweep caught it
before handoff (`scans form trees"\){3}$` did not match; reading the line did not reveal it).
This is the second time in two rounds that the regex check found what reading missed, so the
FIX-ROUND-1 rule is confirmed worth keeping: **every closing line in every touched file gets an
exact-count regex assertion, and any edit that changes a line count inside a nested form gets
its net paren delta computed against the pristine text.** Verified this round at `:772` (4),
`:783` (4), `:789` (1), `:792` (1), `:797` (3), `:810` (3), `:879` (5), `:895` (6), `:931` (5),
`:945` (4), `:969` (4), `:1008` (3), `:1034` (4). All 14 `deftest` forms and every helper `defn-`
confirmed at column 0 in dependency order, with no forward references.

### Still outstanding after FIX-ROUND-2

- **`reviewed-var-x-root` is `nil`** — the absolute root golden is unfrozen; see item (1) for
  the one-line action that freezes it.
- The three new deftests (`coordinate-pin-test`, `distinct-admission-token-test`,
  `ts-sp-c1-obligation-test`) and the rewritten `export-audit-pin-test` have **never been
  executed**. Highest risk in `ts-sp-c1-obligation-test`: it assumes `hooks/profile` injects at
  least one hook into `(lower/lower-packet while-x {:prelude :linked})` — true by construction
  (`while-x` carries a `:loop` mark and `hooks/profile` maps `:loop` → `js.sp/loop`), and
  `(is (seq injected))` fails informatively if that ever stops holding. Second highest:
  `coordinate-pin-test`'s hand-derived path set, which is exactly the kind of thing a first run
  should be expected to correct.
- `linked_prelude_test` still needs its re-run (FIX-ROUND-1's outstanding item), as do
  `float-address-test` (5/20) and `prelude-module-test`.
- No commit. Working tree: 6 modified files + `test/yang/typescript_callsite_test.cljc`, with
  `.qwen/` and this findings document excluded from the intended commit set.
