# Plan: reconcile round 2 — the two repro-backed P1s on packet validation (plan v3)

## Context

`collab/1792302000000-architect-antlr-multi-reconcile-r2.prompt.md` asks the Architect to
revise plan v2 (`collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings-v2.md`)
against the codex re-review
(`collab/1792299000000-reviewer-antlr-multi-plan-re-review.gpt-6.1-sol.findings.md`,
REQUEST CHANGES: two new P1s, both executed against the JVM exporter). The deliverable is
a document (v3); nothing under `src/`, `test/`, `bb.edn`, `deps.edn` or `docs/` changes in
this round. Both findings were re-verified by reading `cst.clj:131-182`, the patched
helper `build/antlr/python3/java/yang/python/antlr/gen/Python3LexerBase.java:29-75,127-144`,
`antlr/python3/manifest.edn` (the `:patches` block), `cst_export_test.clj`, and the
reviewer's probe outputs in its stdout log. Both hold. Nothing was executed.

What the evidence adds beyond the review:

- The helper makes NEWLINE/DEDENT/EOF-time tokens with `positionedToken`, whose
  `CommonToken` constructor takes line and column from the lexer's position **at
  construction** (the start of the following line), and `createDedent` then overrides
  DEDENT's line with the last real token's line (`Python3LexerBase.java:62-64`). So listener
  coordinates on helper tokens are not a projection of the token's char indexes; the span
  is. This is a property of the pinned helper on every target (the JS and Dart
  `CommonToken` constructors copy line/column from the source pair the same way), not a
  JVM exporter defect.
- Today's `:text` on synthetic tokens is runtime fallback text, not evidence: the reviewer's
  probe on `"if x:\n    y = 1"` gives NEWLINE `"<EOF>"`, DEDENT `"<EOF>"`, EOF `"y = 1"`
  (the helper's `commonToken(EOF, "<EOF>")` back-computes a start index from the text
  length). v3 makes synthetic text canonical so no rule and no golden depends on a
  runtime's `getText` fallback.
- The reviewer's probe log also contains an error whose offending token is a helper DEDENT
  (`"if x:\n    return\n    else\nz = 2\n"` → `{:message "mismatched input '' expecting
  INDENT" :line 3 :column 0 :span [26 26]}`, byte 26 being line 4 column 0), which is the
  DEDENT fixture the prompt asks for.
- Only `yang/python/antlr/repl.cljc:67` consumes error coordinates (prints `:line`); no
  consumer reads synthetic-token text or `:synthetic` outside the exporter and its test.

## Action (one step)

Write the "Revised document (v3)" below verbatim to
`collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings-v3.md`
(the v2 round was promoted to a `-v2` file; v2 stays on disk so the reviewer can diff).
The final chat response starts with the two `Completed-GMT / Completed-Local` lines. No
commit (collab/ is never committed).

## Verification

- `git status` shows no tracked file touched.
- The document has a "Delta vs v2" section and ledger rows 10 and 11, each pointing at a
  revised section; every "Correction" clause of the re-review maps to a rule in §1.3 and a
  fixture in §5.1/§5.2.
- Walk the two reviewer repros against §1.3.6 and §1.3.7 once: the DEDENT
  `{:text "" :span [16 16] :synthetic true}` is accepted (policy `:always`, zero-width,
  canonical text); the error `{:line 3 :column 0 :span [13 14]}` is accepted (span on a
  boundary, line within the source, no cross-identity required).

---

# Revised document (v3)

```markdown
# ANTLR multi-target plan v3: one grammar, one parse-tree IR, N hosts, any language

Architect plan answering `collab/1792289000000-architect-antlr-multi-plan.prompt.md`,
revised twice: v2 against the codex review
`collab/1792292000000-reviewer-antlr-multi-plan-review.gpt-6.1-sol.findings.md`
(nine findings, all conceded), v3 against the codex re-review
`collab/1792299000000-reviewer-antlr-multi-plan-re-review.gpt-6.1-sol.findings.md`
(REQUEST CHANGES: two new P1s on the v2 conformance rules, both reproduced on the JVM
exporter) per `collab/1792302000000-architect-antlr-multi-reconcile-r2.prompt.md`. It
still gates the engineer round `collab/1792282000000-engineer-antlr-multi-target.prompt.md`;
no worktree `datomworld-antlr-multi` exists yet.

The owner's requirements are unchanged and govern every revision: (R1) ANTLR works for JS
and Dart before the legacy compiler retires; (R2) adding a new ANTLR language stays
mechanical.

## 0. Reconciliation ledger (for the re-review)

Rows 1-9 are the v2 ledger, unchanged. Rows 10-11 answer the re-review.

| # | Sev | Verdict | Evidence | Revised in |
|---|---|---|---|---|
| 1 | P1 | **Conceded.** `validate!` checks only `i < k` and a `seen` set that is not updated between one node's children (`packet.cljc:34-38`): `[1 1 2]` and the index-ordered non-preorder tree both pass; the final count check (`:39-41`) cannot tell. Error packets have no `:yang.cst/nodes`, so `validate!` throws "no root" on a legitimate error packet (`:23-24`). A source-only `conform` cannot check names against grammar tables and v1's listed checks omitted real-token text and UTF-8 boundaries. | §1.2, §1.3 |
| 2 | P1 | **Conceded.** `packets` in `python_antlr_test.cljc:44-49` are built by the `packet` helper of `lower_portable_test.cljc:11-37`: every span `[0 0]`, no `:synthetic`, grammar `"hand"`. `node-shape` (`python_antlr_test.cljc:340-344`) drops `:span` and `:synthetic`. These are lowering fixtures; v1 wrongly called them parity goldens. | §5.1, §5.2, §5.4 |
| 3 | P1 | **Conceded.** `frontends.cljc:17-18` requires the parser namespace statically, and v1's `parser.cljc` (§4) required a generated recognizer on every host. An informational `:targets` set cannot stop a require that fails at compile time (on Dart a missing `package:` import fails the whole cljd compile). | §4.2, §6, §7 |
| 4 | P1 | **Conceded, with a qualification.** `test:cljs` depends only on `build:yin-repl-node` and `test:cljd` only on `build:yin-repl-peer` (`bb.edn:33-38`); both builds compile `yin.repl.frontends` and neither depends on generation. Qualification: babashka runs `:depends` in declaration order, so `test:clj`'s sibling `[build:yin-repl-node gen:python-antlr]` (`bb.edn:30`) works today only because `build:yin-repl-node` does not yet need the generated code. v2 attaches generation to the consuming build tasks and covers the `:cljs-yin-repl` alias (`deps.edn:67-72`), which has its own `:extra-paths`. | §3.4 |
| 5 | P1 | **Conceded.** v1 §2 allowed a shared read-mostly cache on single-threaded hosts; `yang.antlr.md` §4.3 ("Generated recognizers may use shared caches... isolate them by worker, process, or classloader, or provide instance-owned caches"; "Merely constructing a fresh parser is insufficient evidence") admits no such fallback. Both runtimes expose instance-owned caches, so no fallback is needed. | §2.3 |
| 6 | P1 | **Conceded.** `compilation_pipeline.cljs:50` requires `yang.python` and `:158` calls `py/compile` for the demo's `:python` branch; v1 §10's preconditions would all pass while deleting `yang/python.cljc` breaks the `:demo` and `:local` browser builds. `yang.php.md:1296-1306` already makes demo retargeting a retirement prerequisite for PHP. | §10 |
| 7 | P2 | **Conceded.** `yin_repl.cljs:38` requires `yin.repl.driver`, `driver.cljc:22` requires `yin.repl.frontends`; the `:demo`/`:local` browser builds (`shadow-cljs.edn`) therefore reach the catalog. Removing the gate puts the runtime and generated parser into the browser bundle at M2. v2 decides browser inclusion explicitly. | §6.2 |
| 8 | P2 | **Conceded.** v1 §4 listed `:clj` before `:cljd` in its own example against its own rule. `repl_probe.clj:59-68` uses `.charAt` and `.startsWith`; `.charAt` is not Dart `String` API, so purity did not make `scan` portable. M1 depended implicitly on M2 for a profile to construct recognizers with. | §2.4, §4.1, §11 |
| 9 | P2 | **Conceded.** TS upstream helpers are Java/C#/C++ with dialect and prediction-mode requirements (`yang.typescript.md:504-512`); PHP vendors its MIT Java helper beside the `.g4` (`yang.php.md:1319-1325`); the pub `antlr4` licence page carries MIT notices beside the BSD text; descriptive runtime strings enforce nothing; grammars-v4 does not guarantee cross-target compatibility (`yang.antlr.md:535-540`). | §3.1, §3.5, §7, §9 |
| 10 | P1 | **Conceded.** v2 §1.3.6 justified a synthetic token by text evidence (text differs from the source at its position, or overlap with the previous real token). The patched helper makes DEDENT zero-width with `positionedToken(DEDENT, getCharIndex(), getCharIndex() - 1)` (`manifest.edn` `:patches`, `Python3LexerBase.java:62`), so the exporter emits `{:type "DEDENT" :text "" :span [16 16] :synthetic true}` for the required indentation fixture: the source slice at `[16 16]` is `""`, equal to the text, and the preceding NEWLINE ends at 16, so neither justification holds and the fixture of §5.1 fails M1. The converse is also unsound: collapsing any misplaced real token to a zero-width span makes its text differ from the empty slice, so v2's rule cannot catch a falsely synthetic NAME. Root cause: synthetic-ness is a **grammar role** (the helper constructs these tokens; their text is not source evidence), and v2 tried to re-derive it from text. Further evidence that text is not evidence: the reviewer's probe on `"if x:\n    y = 1"` yields NEWLINE `"<EOF>"`, DEDENT `"<EOF>"` and EOF `"y = 1"` as `:text`, all runtime `getText` fallbacks of zero-width or back-computed tokens. | §1.3.1, §1.3.6, §2.1, §5.1, §5.2 |
| 11 | P1 | **Conceded.** v2 §1.3 (error outcomes) required `:line`/`:column` to identify the position at span start. `cst.clj:137-154` records the listener's `line`/`column` arguments verbatim and computes `:span` from the offending token's char indexes. For helper-constructed tokens the two name different places by construction: `positionedToken` builds a `CommonToken` from the lexer's source pair, whose constructor copies the lexer's **current** line and column (the start of the next line, since the NEWLINE lexeme has been consumed), and `createDedent` overrides DEDENT's line with the last real token's line (`Python3LexerBase.java:62-64`). Reproduced: `"if x:\n    y =\nz = 2\n"` → `{:line 3 :column 0 :span [13 14]}`, byte 13 being line 2 column 7; and on a DEDENT, `"if x:\n    return\n    else\nz = 2\n"` → `{:message "mismatched input '' expecting INDENT" :line 3 :column 0 :span [26 26]}`, byte 26 being line 4 column 0. The JS and Dart `CommonToken` constructors copy line/column from the source pair the same way, so this is the pinned helper's behaviour on every target, not a JVM defect to fix before goldens. `yang.antlr.md` §5.2 already rules it: "Canonical spans use half-open offsets into the original source bytes... Line and column values are presentation projections." | §1.3.7, §1.4, §5.1, §5.3 |

Two risks codex judged non-blocking stay as fixtures only: JS code-point input
(`CharStreams.fromString` in antlr4 4.13.2 decodes to code points) and Dart's acceptance
of a null-safe `<3.0.0` package under SDK `>=3.7.0`; both are verified by M0's executable
acceptance (§11), not assumed.

## Delta vs v2 (what the reviewer should re-read)

- New: §1.3.1 synthetic policy as grammar-role data on the profile; §1.3.6 rewritten
  (role-keyed justification, canonical synthetic text, the mutation table); §1.3.7 the
  error-record contract: two coordinate systems, one invariant each, no cross-identity;
  §2.1 canonical synthetic text in `token-record` (the one JVM exporter edit) and
  `:synthetic-policy` in the profile contract; §5.1 corpus gains the helper-NEWLINE,
  helper-DEDENT and EOF-offending error sources; §5.2 layer 1 gains the acceptance and
  mutation tests of §1.3.6 and the error-coordinate fixtures; §5.3 states what "exact"
  means for coordinates; §7 step 3 gains the policy line; §11 M1 acceptance names the
  mutation table and the error fixtures; §12 gains the helper-coordinate parity risk.
- Changed wording: §1.3 rules 3 and 4 (now phrased against the policy), §1.4 invariants 4
  and the new 8, §4.1 example profile (one more key).
- Unchanged: everything else, including §1.2 `validate!`, the preorder walk, checks 1, 2
  and 5 of §1.3 (names, UTF-8 boundaries, rule extents), §2.2-2.4, §3, §4.2, §5.4, §6-§10.
  The original P1-class properties (duplicate edges, non-DFS preorder, real-token text vs
  source bytes, UTF-8 boundaries, terminal ordering, rule extents) are still established:
  §1.3.6 shows for each which rule catches it after the revision.

## Delta vs v1 (kept for history)

New in v2: §0 ledger; §1.2 outcome-specific `validate!`; §1.3 `conform`; §2.3 isolation
mechanism per runtime; §2.4 portable probe; §3.4 build ordering and both classpaths; §3.5
pin enforcement; §4.2 missing-target mechanism; §5 rewritten; §6.2 browser decision; §7
eligibility condition; §10 consumer inventory and demo migration; §11 resequenced.

## Context (unchanged from v1 except where marked)

`(lang :yang.python/antlr)` works on the JVM only. The generator
(`src/dev/yang_antlr_gen.clj`, `bb gen:python-antlr`) produces the Java target only,
`src/clj/yang/antlr/cst.clj` is the only exporter, and `src/cljc/yin/repl/frontends.cljc`
installs the ANTLR frontend under `#?(:cljd nil :clj ...)`. Node and Dart answer
`unavailable-parser` through `yin.repl/frontend-binding` (`repl.cljc:1696-1706`): a
selected frontend whose binding is not usable throws the diagnostic. That mechanism is
reused in §4.2.

Owner rulings (verbatim): "don't remove legacy until antlr works for JS and dart too";
"future languages will be added too and whatever is design should make it easy to add new
antlr supported languages"; "maybe an antlr intermediate representation is needed if antlr
doesn't have that concept already."

Facts (verified against the pinned sources; corrections marked):

- grammars-v4 `python/python3` at `8af0d4c2` ships helper bases for Java, JavaScript,
  Dart, C#, C++, Go, Python3, TypeScript; `desc.xml` tests `Cpp;CSharp;Java;JavaScript;Python3`.
  The Dart helper is not exercised upstream.
- The JS and Dart lexer bases carry the same NEWLINE/DEDENT `commonToken` construction the
  Java patch fixes; the patch is ported per target. **Added (v3, finding 11):** every
  target's `CommonToken(source, type, channel, start, stop)` constructor copies line and
  column from the source pair's lexer at construction time; the ported patch therefore
  reproduces the Java helper's coordinates on helper tokens, and the goldens pin them.
- JS helpers: ESM, `import antlr4 from 'antlr4'`, circular import with the generated parser.
  Dart helpers: `package:antlr4/antlr4.dart`, `dart:io`. No licence notice in either.
- Runtimes: npm `antlr4` 4.13.2 (BSD-3-Clause, ESM, node>=16); pub `antlr4` 4.13.2 (SDK
  `>=2.12.0 <3.0.0`, deps `logging`, `collection`). **Corrected (finding 9):** the pub
  package's published licence combines BSD-3-Clause with MIT notices for bundled parts; the
  manifest records the full published text at pin time, not "BSD-3-Clause".
- ANTLR has no target-neutral tree; the repo's neutral form is CST packet v1
  (`cst.clj:16-35`), read through `packet.cljc`. **Corrected (finding 2):** the hand-built
  packets of `lower_portable_test.cljc` and `python_antlr_test.cljc` are lowering fixtures
  (zero spans, no synthetic flags, grammar `"hand"`), not parser goldens.
- TS (`yang.typescript.md` 5.3) and PHP (`yang.php.md` 6) are JVM-only for helper
  availability reasons: TS has Java/C#/C++ bases upstream (plus dialect-flag and
  prediction-mode requirements of its own); PHP has Java (vendored, MIT), JavaScript,
  C#, C++, Python3, Antlr4ng bases and no Dart one.

## The design in one paragraph (unchanged)

ANTLR gives "one grammar, N generated recognizers". The repo adds the other half: one
target-neutral parse-tree IR that every host's exporter produces and every language's
lowering consumes. That IR is CST packet v1, now formally owned by `packet.cljc` with an
outcome-specific validator and a conformance oracle. A language is: a manifest (grammar,
per-target helpers, patches, digests, runtime pins), a ~40-line `parser.cljc` with one
reader-conditional branch per generated target, its lowering over the IR, one catalog
`install` line, and its goldens. Nothing per language touches the generator, the exporter,
the packet reader, or the REPL.

## 1. The IR: `:yang.cst/v1`, formalized

### 1.1 Home and ruling (unchanged)

The IR is the existing packet shape, not a new schema. `packet.cljc` owns it: the shape
doc at `cst.clj:16-35` moves there verbatim; `packet-version` moves there. D1 (the
diagnostic namespace) stays with lift L2 of `yang.typescript.md` 17.

### 1.2 Structural validation is outcome-specific (unchanged from v2)

`packet/validate!` is rewritten; its contract is per outcome:

- Every outcome: `:yang.cst/version 1`, `:yang.cst/outcome` in
  `#{:yang.cst/ok :yang.cst/syntax-error :yang.cst/source-error}`, `:yang.cst/complete true`,
  `:yang.cst/grammar` a string, `:yang.cst/unit` present. Unknown outcome: malformed.
- `:yang.cst/ok`: `:yang.cst/root 0`; `:yang.cst/nodes` a non-empty vector; no
  `:yang.cst/errors`. **Preorder, uniqueness and reachability are established by one
  recursive walk** from node 0 with a counter: visiting node `i` requires `(:id (nth nodes
  counter)) = counter = i`, then walks its `:children` in order, each child's id being the
  counter at the moment it is visited; after the walk the counter equals `(count nodes)`.
  This rejects duplicate edges (the second visit finds the counter past the id), the
  index-ordered non-preorder tree (`[0 1 3 2]`: node 1's child must be 2), forward
  references, and unreachable nodes, in one pass. A rule node's `:children` is a vector
  (possibly empty); a token node has no `:children`. `:kind` in `#{:rule :token}`; rule
  nodes carry a string `:rule`, token nodes a string `:type` and string `:text`; every node
  a `[s e]` span of two non-negative integers with `s <= e`.
- `:yang.cst/syntax-error` and `:yang.cst/source-error`: no `:yang.cst/nodes`, no
  `:yang.cst/root`; `:yang.cst/errors` a non-empty vector of records whose `:kind` is
  `:syntax-error` (line >= 1, column >= 0, span nil or `[s e]`) or `:source-error`, each with
  a string `:message`.

A legitimate error packet therefore validates. The tests `validate!` gains: the two codex
counterexamples, an error packet, a token with children, a rule without `:children`.

### 1.3 `conform`: content against source, vocabulary and policy (revised, findings 10, 11)

`packet/conform` takes `packet`, the sealed `source` string, and a `vocabulary`
`{:rule-names #{...} :token-names #{...} :synthetic-policy {...}}`. It runs after
`validate!`. Returns the packet or throws a qualified `malformed-cst` naming the first
violated invariant. `:rule-names` and `:token-names` come from the running host's generated
tables (`cst/vocabulary worker`, §2.1); `:synthetic-policy` comes from the language's
profile (§1.3.1). The conformance helper (§5.2) assembles the map; `conform` itself is
host-free and language-free.

#### 1.3.1 Synthetic policy: grammar role as data (new, finding 10)

A grammar with lexer helpers constructs tokens that no source bytes produced (Python's
DEDENT, the end-of-input NEWLINE, every grammar's EOF). Whether a token type may or must
be synthetic is a property of the grammar and its helper, so it is declared once per
language on the profile, not inferred from text:

```clojure
:synthetic-policy {"NEWLINE" :at-end, "DEDENT" :always}     ; Python3; INDENT is absent: never
```

Values: `:never` (the default for every type not listed, and for every real-text token
type such as NAME, NUMBER, ASSIGN, INDENT), `:always` (the helper constructs every token
of this type; it is zero-width), `:at-end` (the helper constructs one at end of input;
elsewhere the type is a real token). `"EOF"` is `:always` in every grammar and is merged in
by `conform`; a language's policy need not list it. A grammar without helpers declares
nothing.

The policy is grammar data in cljc, like `grammar-id`, so it is the same on every host and
is written into the goldens file beside the vocabulary (§5.1). It is **not** read by the
exporter: the exporter decides `:synthetic` from host-token evidence it alone has (char
indexes, §2.1), the oracle decides from role; two independent derivations that must agree
on every golden. That is the "independent host-token evidence or explicit grammar-specific
policy" the reviewer asked for, supplied as both.

#### 1.3.2 Checks for `:yang.cst/ok`

1. **Names** (unchanged): every `:rule` is in `:rule-names`; every `:type` is in
   `:token-names` or is `"EOF"`; every `:type` in `:synthetic-policy` is in `:token-names`
   (a policy naming a type the grammar lacks is a profile error, caught here).
2. **Byte bounds and UTF-8 boundaries** (unchanged): let `B` be the set of byte offsets
   that start a code point in `source`, plus `utf8-length`. Every span end is in `B`.
   (Portable: build `B` by walking code points with `utf8-length` as `cst.clj:68-88`
   does; no host string internals.)
3. **Real-token text**: for a token without `:synthetic`, `s < e` and the source bytes at
   `[s e]` decode to exactly `:text` (decode via the code-point index that `B` gives;
   compare strings). Unchanged in substance; it is now the only text-vs-source rule.
4. **Terminal ordering** (unchanged): walking tokens in preorder, each real token's `s` is
   >= the previous real token's `e`; a synthetic token has `s = e` and sits at an offset
   >= the previous real token's `e` and <= the next real token's `s`.
5. **Rule extents over real descendants** (unchanged): a rule's span equals `[min s, max e]`
   over the real tokens in its subtree; a rule with no real descendant is zero-width at an
   offset between its neighbouring real tokens.
6. **Synthetic flag is justified by role** (rewritten, finding 10). For every token, with
   `p = (get policy type :never)` and `"EOF"` as `:always`:
   - `:never`: the token must not carry `:synthetic`. (Rule 3 then applies. This is what
     catches the reviewer's converse: a NAME collapsed to a zero-width span and flagged
     synthetic is rejected here by its type, not by its text.)
   - `:always`: the token must carry `:synthetic` and be zero-width (`s = e`; rule 4
     places it). A DEDENT with width, which is what the unpatched upstream helper
     produces (it reads the newline it follows), is rejected here.
   - `:at-end`: the token carries `:synthetic` iff `s = e = utf8-length`; otherwise it is
     real and rule 3 applies. (The Python3 helper's end-of-input NEWLINE is emitted at
     `getCharIndex()` when `LA(1)` is EOF, so it is always at the end; every other NEWLINE
     has width.)
   - **Canonical synthetic text**: a synthetic token's `:text` is `""`, except `"EOF"`
     whose `:text` is `"<EOF>"`. The exporter writes it (§2.1) and `conform` checks it.
     Text on a synthetic token is therefore never evidence of position and never a
     runtime's `getText` fallback (today the JVM emits `"<EOF>"` for an end-of-input
     NEWLINE and DEDENT and `"y = 1"` for the helper-built EOF of `"if x:\n    y = 1"`).

   Which rule now establishes each original P1-class property: duplicate edges and
   non-DFS preorder, §1.2 walk; real-token text vs source bytes, rule 3 (every unflagged
   token) plus rule 6 (`:never` types cannot hide behind the flag); UTF-8 boundaries, rule
   2; terminal ordering, rule 4; rule extents, rule 5.

   **Acceptance fixtures** (from §5.1, must pass `conform`): `"x = 1\n"` (EOF only);
   `"if x:\n    y = 1\nz = 2\n"` (INDENT `"    "` real at `[6 10]`, DEDENT `""` at
   `[16 16]` synthetic, three real NEWLINEs, EOF at `[22 22]`); `"if x:\n    y = 1"` (end
   NEWLINE, DEDENT and EOF all synthetic at `[15 15]`, text `""`, `""`, `"<EOF>"`).

   **Mutation table** (each mutation is applied to one node of a golden; `conform` must
   throw naming the rule given):

   | Mutation of the indentation golden | Rejected by |
   |---|---|
   | NAME `"x"` `[0 1]` → `:synthetic true`, span `[1 1]`, text unchanged | 6 (`:never`) |
   | NAME `"x"` → `:synthetic true`, span `[1 1]`, text `""` | 6 (`:never`) |
   | INDENT → `:synthetic true`, span `[10 10]`, text `""` | 6 (`:never`) |
   | DEDENT loses `:synthetic` | 6 (`:always`) |
   | DEDENT span → `[15 16]` (unpatched-helper shape) | 6 (`:always`, width) |
   | DEDENT text → `"\n"` | 6 (canonical text) |
   | first NEWLINE `[5 6]` → `:synthetic true`, span `[6 6]`, text `""` | 6 (`:at-end`, 6 ≠ 22) |
   | last NEWLINE `[21 22]` → `:synthetic true`, span `[22 22]`, text `""` | 5 (its `stmt` ancestors still end at 22) |
   | EOF loses `:synthetic` | 6 (`:always`) |
   | EOF text → `""` | 6 (canonical text) |
   | NAME `"y"` text → `"q"`, flag and span unchanged | 3 |
   | NAME `"y"` span → `[10 12]` | 2 or 3 (boundary fine, text mismatch) |

   The eighth row is deliberate: it shows the rules are a system, and that a mutation
   consistent with rule 6 alone is still caught, because a token cannot move without its
   ancestors' extents moving.

#### 1.3.3 Checks for error outcomes: two coordinate systems, one invariant each (new, finding 11)

An error record carries two independent descriptions of where ANTLR failed. They are
**not required to identify the same position**, and the exporter does not reconcile them:

- `:span` is in **source coordinates**: half-open UTF-8 byte offsets, computed by the
  exporter from the offending token's char indexes through the code-point-to-byte table
  (`cst.clj:137-143`): `[byte(start), byte(stop + 1)]`, or `[byte(start), byte(start)]`
  when the token is zero-width (`stop < start`, the helper tokens); for a lexer error,
  `[byte(tokenStartCharIndex), byte(input.index)]` (`:144-147`); `nil` when the listener
  supplies no token and the recognizer is not a lexer. Invariant: `s <= e`, both in `B`
  (rule 2), so a span always names bytes of the sealed source on code-point boundaries.
  This is the canonical position (`yang.antlr.md` §5.2); a consumer that wants a line and
  column projects them from `s` and the source.
- `:line`/`:column` are in **ANTLR coordinates**: the listener's `line` and
  `charPositionInLine` arguments, captured verbatim (`:152-153`). ANTLR takes them from
  the offending token (parser errors) or the lexer state (lexer errors). For a token the
  lexer read from the source they name its first character (1-based line, 0-based
  code-point column) and agree with the span. For a token a helper constructed they are
  whatever the helper set: the pinned Python3 helper gives NEWLINE/DEDENT/EOF-time tokens
  the lexer's position at construction, which is the start of the following line, and
  overrides DEDENT's line with the last real token's line (`Python3LexerBase.java:62-64`).
  Invariant the exporter maintains: `line >= 1`, `line <= 1 + (count of "\n" in source)`,
  `column >= 0`. Nothing more: they are a presentation projection owned by ANTLR, kept
  because the `:message` was rendered against that token and because tooling that reads
  ANTLR output expects them.

`conform` for `:syntax-error` records checks exactly: span (when present) satisfies rule
2; `:line` and `:column` satisfy their bounds. It does not relate the two. For
`:source-error`: nothing beyond `validate!`.

The v2 sentence "`:line`/`:column` are consistent with the span" is withdrawn. The
contract that replaces it for cross-target parity is §5.3: every host captures the same
listener values, so the goldens pin both systems exactly, disagreement included. The
conformance set therefore contains errors whose offending token is a helper NEWLINE and a
helper DEDENT (§5.1), each pinned with its disagreeing coordinates as the expected value.

Consumer note (no change this round): `yang/python/antlr/repl.cljc:67` prints `:line`;
for `"if x:\n    y =\nz = 2\n"` the REPL says line 3 where CPython says line 2. If that is
to change, the REPL projects from `:span`; it is a REPL decision, not a packet one.

Not checked by `conform`: that the root's end equals `utf8-length` (false when the source
ends in untokenized trivia); fixtures assert it where true.

`conform` costs nothing at runtime (tests only).

### 1.4 Normative invariants (restated; they are what §1.2-1.3 check)

1. Ids are DFS-preorder indexes, root 0, children a tree (§1.2 walk).
2. `:rule` and `:type` come from the host's generated tables (§1.3.2 rule 1); `"EOF"` for
   EOF.
3. Spans are half-open UTF-8 byte offsets on code-point boundaries (rule 2); each target
   converts its own index unit at the boundary.
4. A real token's text is the source at its span; a synthetic token is zero-width, is
   synthetic by its type's declared role, and carries canonical text (rules 3, 6).
5. Terminals are ordered and non-overlapping; rule spans cover real descendants (rules 4, 5).
6. A syntax error yields an error packet with errors and no nodes (§1.2).
7. `:yang.cst/grammar` names grammar revision and tool; the target does not appear in the
   packet, in node fields, or in diagnostic text (§5.3).
8. An error's `:span` is source coordinates; its `:line`/`:column` are ANTLR coordinates
   captured verbatim; they are independent (§1.3.3).

## 2. The exporter: `yang.antlr.cst` on three hosts

### 2.1 Contract (v1 plus one export and one edit; revised, finding 10)

`cst.clj` stays, with **one edit**: `token-record` (`cst.clj:157-182`) keeps its
evidence-based `synthetic?` decision (`real-text` from the token's char indexes differs
from the ANTLR text, or the token overlaps the previous real token; a zero-width token has
no `real-text` and is therefore synthetic) and then writes canonical text:

```clojure
text (if synthetic? (if (= type Token/EOF) "<EOF>" "") (.getText t))
```

Existing goldens are unaffected (`["EOF" "<EOF>" [6 6]]` in `cst_export_test.clj` holds;
the missing-final-newline test asserts flag and span only). `cst.cljs` and `cst.cljd`
implement the same public names: `make-worker`, `parse-unit`, `parse-transform`,
`utf8-bytes`, `first-failure` (§2.4), and **`vocabulary`** (new):
`{:rule-names #{...} :token-names #{...}}` from the worker's parser (`ruleNames`, symbolic
and literal names). The profile contract gains one optional key:
`{:grammar-id :make-lexer :make-parser :start :synthetic-policy}`; the exporter ignores
`:synthetic-policy` (§1.3.1). The error listener contract is §1.3.3 on every host: capture
`line`/`column` verbatim, compute the span from char indexes.

`parse-transform` and `source-error` are host-free and move to a cljc helper (engineer's
call on the namespace; minimal diff), so each host file holds only the host walk.

### 2.2 Host walks (unchanged from v1)

Node: `CharStreams.fromString` (code points), `CommonTokenStream`, an `ErrorListener`
subclass, `ctx.children`, `ctx.ruleIndex`, `parser.ruleNames`, `symbolicNames`/`literalNames`,
`node.symbol.{type,start,stop,text}`. Dart: `InputStream.fromString`, `CommonTokenStream`,
`BaseErrorListener`, `childCount/getChild`, `TerminalNode.symbol`, `ruleNames`, `vocabulary`.
Recorded ClojureDart traps apply (reader-conditional ordering, `for` over chunked seqs,
multi-key `assoc` on nil, unary minus, protocol `[_ _]`).

### 2.3 Worker isolation: the mechanism per runtime, no fallback (unchanged from v2)

The v1 fallback ("document that the host is single-threaded and the cache is read-mostly")
is removed. `yang.antlr.md` §4.3 requires isolation; both runtimes support the JVM's
mechanism exactly:

| Runtime | Instance-owned caches | How the worker installs them |
|---|---|---|
| JVM (today) | `DFA[]` per decision, `PredictionContextCache` | `setInterpreter` with `LexerATNSimulator`/`ParserATNSimulator` (`cst.clj:242-258`) |
| antlr4 npm 4.13.2 | `DFA` array, `PredictionContextCache` exported | `lexer._interp = new LexerATNSimulator(lexer, atn, decisionToDFA, cache)`, same for the parser's `_interp` |
| antlr4 pub 4.13.2 | `DFA`, `PredictionContextCache` | `lexer.interpreter = LexerATNSimulator(lexer, atn, decisionToDFA, cache)`, same for the parser |

`make-worker` on each host builds fresh DFA arrays and caches from the recognizer's ATN
(as `cst.clj:100-124`); `parse-unit` installs them on every fresh lexer and parser. The
generated recognizers' static shared caches are never used by a worker's parse.

Verification (in `yang.antlr.conformance`, every host): an **interleaved-worker test**:
workers `w1`, `w2`; parse A on `w1`, B on `w2`, A' (a variant of A that exercises other
decisions) on `w1`, then A on `w2`; every packet equals the packet a fresh worker gives
for the same source; `w1`'s and `w2`'s DFA arrays and caches are distinct objects
(`identical?` false); and on the JVM, Node and Dart alike the recognizer's static
`_decisionToDFA`/`_sharedContextCache` is not the object installed on the worker. If a
runtime's generated code reaches its static cache through a path the worker cannot
redirect, that is a blocking M1 finding, not a documented exception.

### 2.4 Probe (unchanged from v2)

`scan`/`probe` move to `src/cljc/yang/python/antlr/repl_probe.cljc` **with portable string
access**: `(nth text i)` for a character, `subs` plus `=` (or `clojure.string/starts-with?`
on a `subs`) for the closer test, `count` for length; no `.charAt`, `.startsWith`,
`.offsetByCodePoints`. The host-specific `first-failure` becomes a `yang.antlr.cst` export
(`first-failure worker source` answering `nil | :end | :early`) per host. The probe's
existing JVM tests become cljc and run on three hosts (a scan-golden table is portable by
construction).

## 3. The build: one manifest, N targets (unchanged from v2)

### 3.1 Manifest (v1 shape; `:runtime` becomes structured, finding 9)

As v1, with these corrections:

```clojure
:targets
{:java       {:runtime {:maven "org.antlr/antlr4-runtime" :version "4.13.2"} ...}
 :javascript {:runtime {:npm "antlr4" :version "4.13.2"} ...}
 :dart       {:runtime {:pub "antlr4" :version "4.13.2"} ...}}
:license {...  "antlr4 4.13.2 (npm)" "BSD-3-Clause (LICENSE.txt at pin)"
          "antlr4 4.13.2 (pub)" "<full published licence text summary: BSD-3-Clause plus the MIT notices it carries>"
          "JavaScript/Python3LexerBase.js" "no notice; UNRESOLVED" ...}
```

Helper files are fetched by digest into `build/antlr/<grammar>/upstream` as today. The
"vendor `.g4` only" rule has one recorded exception: a helper with an explicit licence
notice may be vendored beside the `.g4` with its notice (PHP's `Java/PhpLexerBase.java`,
`yang.php.md:1319-1325`); the manifest's `:files` entry says `:vendored` either way, as
today's generator already supports.

### 3.2 Patches, digests, landing dirs (unchanged from v1)

Per-target `:patches` (positioned-token port for JS and Dart), per-target `:generated`
digests and `generated-digests.edn`, ASCII check per target. Java byte-identical. Node:
`build/antlr/python3/js` with ESM under `yang/antlr/gen/python3/`; Dart: `lib/antlr/python3`
(`lib/` is gitignored, package root `datomworld_cljd`).

### 3.3 Generator loop (unchanged)

`yang_antlr_gen.clj` loops over `:targets`; `-Dlanguage=` is the per-target switch; the
package-clause prepend and `javac` stay Java-only.

### 3.4 Build ordering and classpaths (finding 4)

- `bb.edn`: `gen:python-antlr` becomes `gen:antlr` (all grammars, all targets,
  `--grammar`/`--target` filters). **`:depends [gen:antlr]` is attached to the consuming
  build tasks**: `build:yin-repl-node` and `build:yin-repl-peer`. `test:clj`,
  `test:all:clj`, `test:slow:clj` keep a direct dependency too (the JVM lane loads classes
  without a build task). The `test:cljs`/`test:cljd` families inherit through the builds.
  Every remaining `gen:python-antlr` reference (docs, `build-n-test.md`) is renamed.
- `deps.edn`: `build/antlr/python3/js` is added to **both** `:cljs` and `:cljs-yin-repl`
  `:extra-paths` (`deps.edn:39,67-72`); the `:dev` alias too, since `watch demo` runs
  through it. Dart needs no path: generated files are package sources.
- Acceptance is a **clean direct build**, not a test wrapper: from a clean `build/` and
  `lib/antlr`, `bb build:yin-repl-node` and `bb build:yin-repl-peer` each succeed
  on their own, and `clj -M:cljs-yin-repl` starts.

### 3.5 Pins, lockfiles, consistency (finding 9)

- `package.json` gains `"antlr4": "4.13.2"` (exact) and `package-lock.json` is updated in
  the same commit; `pubspec.yaml` gains `antlr4: 4.13.2` and `pubspec.lock` likewise.
- `gen:antlr` **enforces** that for every target in the manifest the runtime pin equals
  the installed/locked version: Maven via the `:antlr-gen` classpath (as today), npm via
  `package-lock.json`'s resolved `antlr4` version, pub via `pubspec.lock`. A mismatch fails
  generation with the three versions printed. Tool and runtimes remain one release.

## 4. The parser namespace per language

### 4.1 Shape (one key added to the profile; finding 10)

```clojure
(ns yang.python.antlr.parser
  (:require [yang.antlr.cst :as cst] [yang.stage :as stage]
            #?@(:cljd [["package:datomworld_cljd/antlr/python3/Python3Lexer.dart" :as l]
                       ["package:datomworld_cljd/antlr/python3/Python3Parser.dart" :as p]]
                :cljs [["antlr4" :as antlr4]
                       ["/yang/antlr/gen/python3/Python3Lexer.js" :default Python3Lexer]
                       ["/yang/antlr/gen/python3/Python3Parser.js" :default Python3Parser]]
                :clj  []))
  #?(:clj (:import (yang.python.antlr.gen Python3Lexer Python3Parser))))

(def grammar-id "grammars-v4/python/python3@8af0d4c2... antlr-4.13.2")   ; one string, every host

(def synthetic-policy {"NEWLINE" :at-end, "DEDENT" :always})             ; grammar role, every host (§1.3.1)

(def profile
  {:grammar-id  grammar-id
   :synthetic-policy synthetic-policy
   :make-lexer  #?(:cljd (fn [cs] (l/Python3Lexer cs))  :cljs (fn [cs] (Python3Lexer. cs))  :clj (fn [cs] (Python3Lexer. cs)))
   :make-parser #?(:cljd (fn [ts] (p/Python3Parser ts)) :cljs (fn [ts] (Python3Parser. ts)) :clj (fn [ts] (Python3Parser. ts)))
   :start       #?(:cljd (fn [^p/Python3Parser p] (.file_input p)) :cljs (fn [p] (.file_input p)) :clj (fn [^Python3Parser p] (.file_input p)))})
```

`:cljd` first in every conditional (recorded trap). The Dart branch types the parser
argument so the entry call is static. `make-worker`, `parse-source`, `open-stage`,
`step-stage` unchanged.

### 4.2 Hosts without a target: explicit composition (unchanged from v2; finding 3)

A language's `parser.cljc` has exactly one `:require` branch and one profile branch per
target in its manifest `:targets`. For a host whose target is absent, the branch is
**absent from the require form** (`#?@(:cljd [] ...)`, so no import is attempted; on Dart a
missing `package:` import is a compile failure, which is why nil-valued vars are not
enough) and `profile` is `nil` on that host:

```clojure
(def profile #?(:cljd nil :cljs nil :clj {...}))     ; a Java-only language
```

The catalog installs through one helper in `frontends.cljc`:

```clojure
(defn- antlr-install [catalog manifest profile binding]
  (frontend/install catalog manifest (if profile binding (dissoc binding :parse))))
```

A binding without `:parse` is not usable, so selecting the language answers
`unavailable-parser` through the existing path (`repl.cljc:1696-1706`); the engineer
confirms `usable-binding` answers nil for it (otherwise install `{}`). The frontend
manifest's `:yang.grammar/targets` set is **derived** from the grammar manifest's
`:targets` keys at generation time (the generator writes `build/antlr/<g>/targets.edn`,
read by a macro or copied as data; engineer's call), and a per-language test asserts on
each host `(= (contains? targets <host-target>) (some? parser/profile))`, so the three
sources (manifest, parser branches, catalog) cannot disagree silently.

**Demonstration (M2 acceptance)**: a test-only Java-only language,
`test/yang/javaonly/antlr/parser.cljc`, whose `:clj` branch reuses the Python3 Java
recognizers and whose other branches are absent, installed beside Python in a test
catalog on all three hosts: the JVM parses through it; Node and Dart compile, build, and
answer `unavailable-parser` for it while `:yang.python/antlr` parses. No new grammar is
needed for the demonstration.

## 5. CST equivalence: the parity story (findings 1, 2, 10, 11)

### 5.1 Genuine goldens, exported by the JVM (corpus extended, findings 10, 11)

`src/dev/yang_antlr_goldens.clj` (the `dht-import-v1.edn` pattern) writes
`test/yang/antlr/fixtures/python3-goldens.edn`: for each fixture source, the **complete**
packet the JVM exporter produces (every node field, edges, spans, synthetic flags,
canonical synthetic text, `:yang.cst/grammar`, errors with both coordinate systems), plus
`python3-vocabulary.edn` (the JVM's rule-name and token-name sets **and** the profile's
`:synthetic-policy`, so a policy drift is a reviewable diff too). The corpus:

- ok sources: the four session lines of `python_antlr_test`; `"x = 1\n"`; the `é𝄞` line;
  the indentation case `"if x:\n    y = 1\nz = 2\n"`; the open-indent no-final-newline
  case `"if x:\n    y = 1"`; the owner's comprehension line; an empty source.
- error sources, chosen so each offending-token class appears once: real token
  `"def f(:\n    return 1\n"` (line 1, column 6, span `[6 7]`, coordinates agree); lexer
  error `"x = $\n"` (span `[4 5]`); EOF `"x = 1"` (line 1, column 5, span `[5 5]`, agree);
  **helper NEWLINE** `"if x:\n    y =\nz = 2\n"` (line 3, column 0, span `[13 14]`, byte
  13 is line 2 column 7); **helper DEDENT** `"if x:\n    return\n    else\nz = 2\n"`
  (third record `"mismatched input '' expecting INDENT"`, line 3, column 0, span
  `[26 26]`, byte 26 is line 4 column 0; the first two records on that source are a real
  `'else'` and a helper NEWLINE, so one source covers three classes). The values in
  parentheses are what the JVM exporter emits today and are the expected goldens; the
  generator writes them, it does not hand-edit them.

Regenerating is a `bb` task; a drift in the committed file is a reviewable diff.

### 5.2 Three layers (layer 1 extended, findings 10, 11)

1. **Exporter conformance (per target, language-free)**: `yang.antlr.conformance` (cljc
   test helper) takes a profile, the goldens, and the vocabulary file. On the running host,
   for each fixture: `validate!` and `conform` pass, `conform` receiving
   `(assoc (cst/vocabulary worker) :synthetic-policy (:synthetic-policy profile))`; the
   host's packet **`=` the full golden packet** (for error packets, after §5.3 message
   normalization); the host's vocabulary sets `=` the golden sets. Plus: the interleaved-
   worker test (§2.3); the UTF-8 fixture asserting root end `= utf8-bytes` where the fixture
   ends in a real token; **the §1.3.2 acceptance fixtures and the full mutation table**,
   run against the goldens on every host (the oracle is portable, so the table runs three
   times); and **the error-coordinate fixtures of §5.1**, asserting `=` on `:line`,
   `:column` and `:span` exactly as pinned, disagreement included. `cst_export_test.clj`
   becomes `.cljc` and runs on three hosts through it.
2. **Packet parity (per language)**: `precompiled-packets-are-the-parsers-test` is split:
   (a) the hand-built lowering fixtures' `node-shape` equals the golden packets'
   `node-shape` (fixtures stay what they are, lowering inputs; this pins them to real
   parses); (b) on every host the installed parser's packet `=` the golden for each
   session line. `langs` becomes `[:yang.python/cst :yang.python/antlr]` on all hosts.
3. **Program parity (end to end)**: the REPL session scenarios, the prompt-suppress
   regression and the owner's comprehension line run through `:yang.python/antlr` on Node
   and Dart; expected output is the JVM's. Layer 2 implies 3 because the lowering is
   portable and already proven on packets; layer 3 is what the owner sees.

### 5.3 Diagnostic parity rules (coordinates clarified, finding 11)

For error packets the cross-target contract is `=` on the whole packet after
**message normalization in the exporter**: `:kind`, `:line`, `:column`, `:span`, error
count and order are compared exactly; `:message` is compared after each exporter applies
`packet/normalize-message` (trim, collapse internal whitespace, normalize the runtime's
token-display quoting to ANTLR's Java form `'x'`/`<EOF>`/`''` for an empty display). "Exactly"
for `:line`/`:column` means the ANTLR coordinates of §1.3.3 as each runtime's listener
reports them; since every target's `CommonToken` constructor copies line and column from
the lexer at construction and the ported patch constructs helper tokens at the same
points, the helper-token coordinates are expected to be `=` across targets, and the
goldens are the proof. A target whose runtime reports different coordinates on a helper
token is an M1 finding to the Architect (the fix is in the ported helper or the exporter's
listener, never a relaxed fixture). Where a runtime's `DefaultErrorStrategy` wording still
differs, the exporter rewrites to the Java wording (invariant 7: the target does not
appear in the packet). Fixture pairs exist for a parser error (`def f(:`), a lexer error
(`x = $`), and the helper NEWLINE and DEDENT errors of §5.1, so every listener path is
covered. If a wording cannot be normalized mechanically, that is an M1 finding to the
Architect, not a relaxed fixture.

### 5.4 Fixtures kept as they are (unchanged from v2)

`lower_portable_test.cljc`'s `packet` helper and `python_antlr_test`'s `packets` remain
lowering fixtures (zero spans, `"hand"`); nothing asks `conform` to pass them. v1's claim
"conform passes every precompiled packet" is withdrawn.

## 6. Catalog and SPI changes (unchanged from v2)

### 6.1 Gate removal (as v1)

`frontends.cljc` loses the `#?(:cljd [] :clj ...)` wrappers; the ANTLR frontend installs
on every host through `antlr-install` (§4.2). Revision "f3" bumps; the grammar map gains
`:yang.grammar/targets` (derived, §4.2). No SPI bump. Docstrings flip. Doc amendments as
v1 §6 plus `yin.repl.md:48-70` (the "JVM only" paragraph).

### 6.2 Browser inclusion: decided (finding 7)

The browser builds (`:demo`, `:local`) reach the catalog via `driver.cljc:22`, so after
6.1 they include the npm runtime and the generated JS parser. **Decision: include it.**
Reasons: R1 asks for ANTLR on JS; the compilation demo must stop calling the legacy
compiler before M3 (§10), and an in-browser parser is the simplest retarget; the repo is
dev-only. Acceptance at M2: `clj -M:cljs -m shadow.cljs.devtools.cli release demo` compiles
and the main module's minified size before and after is recorded in the M2 report.
Fallback, chosen before merging if the delta exceeds about 1.5 MB minified: a separate
composition, where `driver` takes the catalog from the host and the browser passes one
without the ANTLR entry (the frontend is then installed with no `:parse`, §4.2, so the
browser REPL answers `unavailable-parser` honestly) while the demo keeps the legacy
path until a packet-based retarget (PHP's B8.9 pattern) lands.

## 7. Adding a language after this lands (the mechanical recipe; findings 3, 9, 10)

**Eligibility condition** (stated up front): the grammar's embedded actions are
target-portable or absent, and a helper base exists and is tested for each target you
claim; grammars-v4 does not guarantee cross-target compatibility (`yang.antlr.md:535-540`),
so a helper without upstream CI (Dart today) is accepted only through the goldens of §5.

For language L with grammar G:

1. `antlr/<G>/manifest.edn`: vendor the `.g4` files (and any helper that carries its own
   notice, §3.1); list helpers per target, patches, licence rows, runtime pins. A target
   without a helper is **omitted from `:targets`**.
2. `bb gen:antlr --grammar <G>`; record digests; the pin check passes.
3. `src/cljc/yang/<L>/antlr/parser.cljc`: one require branch and one profile branch per
   target in `:targets`; absent targets have no branch and `profile` nil there (§4.2);
   **one `:synthetic-policy` line** naming the helper-constructed token types and their
   role (§1.3.1), or none if the grammar has no lexer helper.
4. The lowering over the IR through `packet/*` only (§8).
5. Goldens: sources for `yang_antlr_goldens` (at least one per offending-token class the
   grammar's helper can produce) and a vocabulary file; the targets test of §4.2.
6. `frontends.cljc`: one `antlr-install` line.
7. Optional: a probe (`scan` in cljc over `cst/first-failure`).

Steps 2-6 are the same for every language; the generator, exporter, packet reader, REPL
and catalog are not edited. A Java-only language coexists with Python on Node and Dart
builds by construction (demonstrated in M2). TS and PHP gain a host the moment a helper
for that target is added to their manifests.

## 8. Boundary (unchanged from v1)

Per language: lowering, scope, prelude, publication, primitives, probe rules, refusal and
diagnostic namespaces, entry rule, trailing-newline normalization, synthetic policy.
Generic: generator, `packet.cljc`, the three `yang.antlr.cst` files,
`yang.antlr.conformance`, `yang.frontend`. The IR carries names only; no language-specific
packet fields; more needs an IR version.

## 9. Reconciliation with the TS and PHP designs (unchanged from v2)

- (a) Already covered: manifest and vendoring discipline (`yang.typescript.md` 5.1,
  `yang.php.md` 6), the worker profile contract, per-target helper versioning
  (`yang.antlr.md` 4.2), lifts L1-L4 (section 17). This plan edits none of them.
- (b) New for Python on JS/Dart: multi-target generation, the exporters, the IR
  formalization (§1), the conformance helper and goldens, the probe split, the gate
  removal and availability mechanism, the pins and their enforcement.
- (c) Generalizes: everything in (b) except Python's manifest rows and profile. For TS
  the remaining per-target work is the helper port **plus** its dialect flag and
  prediction-mode pins per target (`yang.typescript.md:504-518`); for PHP, the upstream
  JavaScript base makes Node a helper-port problem and Dart a new-helper problem
  (`yang.php.md:1319-1331`). "Add the helper to the manifest" is the mechanical part; the
  helper itself is not.
- Collision to sequence: L2 edits `packet.cljc` (D1); this plan edits `validate!` and adds
  `conform`. Whichever lands second rebases; different functions.

## 10. Legacy retirement (unchanged from v2; finding 6)

Preconditions, all demonstrable before the round opens:

1. M2 landed with layers 1-3 green on three hosts and the comprehension line evaluating
   on Node and Dart (R1).
2. **Consumer inventory**: a fresh `grep -rn 'yang\.python\b'` over `src/` and `test/`,
   excluding `yang.python.antlr`, with every hit dispositioned. Today's inventory:
   `compilation_pipeline.cljs:50,158` (demo, `py/compile`); `frontends.cljc:15,68-69`
   (legacy install); `repl.cljc:894` and `yin.repl.md` (docs naming `:yang.python/legacy`);
   `src/cljc/yang/docs/{python,architecture,README}.md`; `test/yang/python_test.*` and
   every test selecting `:yang.python/legacy` (for example
   `python_antlr_test.cljc:329-334`); `subsystems.edn:50-51` (prefix stays, covers antlr).
3. **Demo migration landed** (in M2 under §6.2, or as its own slice if the fallback is
   chosen): the demo's `:python` branch parses through the ANTLR frontend and lowers
   through `yang.python.antlr`; the engineer verifies the pipeline's AST consumer
   (`vm/ast->datoms`) accepts the ANTLR lowering's output, or the demo takes checked-in
   packets (PHP's B8.9 pattern).
4. Every legacy test scenario ported or recorded as dropped; docs name the retirement.

The round then deletes `src/cljc/yang/python.cljc` and its tests, the `:yang.python/legacy`
install, and amends the docs. Not part of the first engineer round.

## 11. Implementation slices (M1 acceptance extended; findings 4, 8, 10, 11)

| Slice | Scope | Acceptance |
|---|---|---|
| M0 build | manifest `:targets` and structured `:runtime`; generator loop; JS and Dart generation with ported patches; digests; `gen:antlr` attached to `build:yin-repl-node`/`build:yin-repl-peer` and the JVM test tasks; both cljs classpaths; `package.json`+lock, `pubspec.yaml`+lock; pin-consistency check | Java digests byte-identical; JS/Dart artifacts generated, ASCII, digest-pinned; `bb gen:antlr` reproducible from clean; clean direct `bb build:yin-repl-node` and `bb build:yin-repl-peer` succeed; **executable smoke on Node and Dart**: a test namespace requires the generated recognizer and runtime, constructs lexer and parser, calls `file_input` on `"x = 1\n"` and reads back `ruleNames`; `CharStreams.fromString` code-point mode confirmed; `pub get` resolves `antlr4 4.13.2` under SDK `>=3.7.0`; Dart dynamic-interop policy: the cljd compile log's dynamic-call warning count for the smoke namespace is zero (typed constructor and entry calls) |
| M1 IR + exporters + profile | `validate!` rewrite (§1.2) and `conform` (§1.3) with the codex counterexamples, the §1.3.2 acceptance fixtures and mutation table, and the §1.3.3 error checks as tests; `token-record` canonical synthetic text (§2.1, before goldens are generated); `cst/vocabulary`; shared transform; `cst.cljs`, `cst.cljd` with instance-owned caches (§2.3) and the §1.3.3 listener contract; **`parser.cljc` for Python** with `:synthetic-policy`; `yang_antlr_goldens` and the committed goldens (§5.1 corpus, including the five error sources); `yang.antlr.conformance` with the interleaved-worker test; `cst_export_test` to cljc; probe split with portable strings (§2.4) | layer 1 green on JVM, Node, Dart: full-packet `=` to goldens including the UTF-8 fixture, the indentation and open-indent fixtures (DEDENT `""` `[16 16]` accepted by `conform`), and the five error fixtures under the §5.3 rules with helper-token coordinates pinned as emitted; every row of the mutation table rejected on every host naming its rule; vocabulary sets and policy equal; interleaved-worker test green; `validate!` rejects both counterexamples and accepts error packets |
| M2 catalog + REPL + browser | gate removal, `antlr-install`, derived `:yang.grammar/targets`, targets test, Java-only fixture language demonstration (§4.2); revision bump; layers 2-3 on all hosts; browser release build and bundle measurement, demo `:python` retarget (§6.2, §10.3); doc amendments (§6.1) | layers 2 and 3 green on three hosts; `(lang :yang.python/antlr)` evaluates the comprehension line at the Node and Dart prompts; Node and Dart answer `unavailable-parser` for the Java-only fixture while Python parses; `release demo` compiles, size delta recorded, decision A confirmed or fallback B applied; quick tiers green per host; kondo + cljstyle clean |
| M3 legacy retirement | §10 | its own round, opens only with the §10 inventory dispositioned |

Lanes per slice: `bb test:clj` per iteration; full three-host quick tier at each landing,
one lane set at a time, foreground; focused ANTLR namespaces with `DATOM_SLOW_TESTS=1` at
M2.

## 12. Risks (v2 list, amended)

- Index units (JS/Dart UTF-16 vs code points): the `é𝄞` fixture and `conform`'s boundary
  check are the guards; highest-probability parity defect.
- **Helper-token coordinates across runtime ports** (new): the ported JS/Dart helpers must
  construct NEWLINE/DEDENT at the same lexer positions as the Java patch for `:line`/
  `:column` to be `=`; the helper NEWLINE and DEDENT error goldens are the guard, and a
  mismatch is an M1 finding (§5.3), not a fixture relaxation.
- Dart helper untested upstream; bounded to one patched file; pub SDK bound verified in M0.
- Circular ESM import between the JS lexer base and generated parser under shadow-cljs
  classpath JS; verified by the M0 smoke (fallback `:js-options :resolve`).
- Diagnostic wording across runtimes may resist normalization (§5.3): escalate, do not
  relax.
- Licence: four more helpers without notices; `:blocked-until` stays; pub licence recorded
  in full.
- Browser bundle growth (§6.2): measured, with a named fallback.
- Generated static caches reachable past the worker on some runtime (§2.3): blocking M1
  finding if found.
- Generation needs network on first run; ClojureDart traps in the exporter walk.

## Verification (end to end)

1. `bb gen:antlr` from clean `build/` and `lib/antlr`; Java digests vs
   `git show HEAD:antlr/python3/manifest.edn`; pin check passes.
2. Clean direct `bb build:yin-repl-node`, `bb build:yin-repl-peer`; `clj -M:cljs-yin-repl`
   starts.
3. `bb test:clj`, `bb test:cljs`, `bb test:cljd` quick tiers, foreground, one at a time;
   `Testing yang.antlr.conformance-test` and `yin.repl.python-antlr-test` appear in the
   Node and Dart output.
4. Node prompt and Dart peer: `(lang :yang.python/antlr)` then the comprehension line;
   output equals the JVM's. The Java-only fixture answers `unavailable-parser` there.
5. `clj -M:cljs -m shadow.cljs.devtools.cli release demo`; size delta recorded.
6. `DATOM_SLOW_TESTS=1` focused ANTLR namespaces.
```
