Completed-GMT: 2026-10-15 15:28:04 GMT
Completed-Local: 2026-10-15 22:28:04 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending (claude -p; the harness does not expose it)
Brief: ./collab/1793230000000-architect-8g-review.prompt.md

# 8g documentation review (Architect)

Tree: worktree datomworld-srvm-p8g, branch srvm-phase8g @ 9fab76c6, the
uncommitted docs diff (30 tracked files, +1516/-8964, plus the four
untracked renamed companions). Read-only; this report is the only artifact.
Inputs: the codex writer report (`1792077633912-writer-8g-codex.report.md`),
the writer brief, the phase-8 plan, the 8f-1 ruling and review, the 8f-2
review, the 8f-3 NaN ruling, and the code on this HEAD (grep, not the docs).

## 1. What I verified, against the code and git

### 1.1 Statements about what the code is now (brief item 1)

| claim in the docs | checked against | result |
|---|---|---|
| `yin.vm.ucf` is only `code-address` | `src/cljc/yin/vm/ucf.cljc` (11 lines, one defn over `jing/segment-key`) | TRUE |
| no SHA-256 in `yin.vm`; H, R, descriptor, dimension and node hashes are BLAKE3; descriptors declare `:hash :blake3` | `grep -rniE 'sha256|sha-256' src/cljc/yin/vm` empty; `jing/blake3` at `debruijn_code.cljc:534,550`, `debruijn_register_code.cljc:259,271`, `debruijn.cljc:966,1028`; `:hash :blake3` at `debruijn_code.cljc:125`, `debruijn_register_code.cljc:202`, `debruijn.cljc:139` | TRUE |
| A is the code identity; the native format records still mint their request identity by H/R under `:yin.debruijn.code/address` / `:yin.debruijn.register/address`, through `publish!` and `index-from-datoms` (linker.md §3 amendment) | `linker.cljc:664,682` (`:identity-fn debruijn-code/image-hash` / `register-hash`), `:697` (`semantic-format` identity `jing/segment-key`), `address-attribute` `:944-948`, `index-from-datoms` `:951`, `publish!` `:2051-2060` | TRUE, and honestly stated as residue |
| linker.md §5.2: current record is `linker.cljc:693`, `:contract "v4"` from `yin.vm.semantic.code/contract`, validator `well-formed?`, scanners `free-occurrences`/`definition-occurrences`/`application-sites`, loader `yin.vm.semantic/load-vector` | `linker.cljc:693-704`; `semantic/code.cljc:18-21` (`"v4"`); `semantic/scan.cljc:24,47,66`; `semantic.cljc:303` | TRUE |
| native kernels are backends over the documented fragment, four pre-attach checks, harness-only exclusions, no general gate (UCF 7.11, debruijn.stack/register amendments, continuation §4.6) | `semantic/layout.cljc:916-948` `lower-defect`: `:ambiguous-image`, `:installs`, `:free-env-value`, `:continuation-registers` (register only); `debruijn/stack.cljc` and `debruijn/register.cljc` present; continuation §4.6 (line 1089) says exactly this | TRUE, not widened |
| stack grammar is `debruijn-code`'s own literal data, 23 mnemonics, contract version 2 (8f-1 R1) | `debruijn_code.cljc:10,46-51,101`; 8f-1 ruling R1 and its review | TRUE |
| old lowerers died at 8e; `derive` lowers from A via `resolve-name` | `bfab4d66` deletes `debruijn_resolve/linearize/register_compile.cljc`; `semantic/derive.cljc:33` calls `db/resolve-name`; `stack-image` `:60`, `register-image` `:154` | TRUE |
| `handoff.cljc`, `checkpoint.cljc`, `completion.cljc` died in 8f-1, not 8d; `code.cljc`, `linearize.cljc`, old `semantic.cljc` and the `ucf.cljc` v3 halves died in 8f-3; 8d deleted tests and fixtures only | `e1f6a580` stat (handoff 3783, checkpoint 541, completion 757 deleted); `7b8d2a93` stat (code 467, linearize 502, semantic 1058 deleted, ucf.cljc 451 lines removed); `249f8799` stat (tests/fixtures/support only) | TRUE |
| `ucf/rows/checkpoint.cljc` is the surviving inspector and its docstring still says it "replaces `yin.vm.ucf.checkpoint`" (ledgered) | `ucf/rows/checkpoint.cljc:1-7` | TRUE |
| `frame.cljc:141` compares free-env bindings by host `=` | `semantic/frame.cljc:140-143` | TRUE, line number exact |
| `yin.vm.malformed-rows/malformed-vectors` has no reader | defined `test/yin/vm/malformed_rows.cljc:54`; no other reference under src/test; `scan_test.cljc:214` is a deftest with its own inline vectors | TRUE |
| `vm/opcase` is gone and the optimisation note still names it | `bytecode-vm-optimization.md:336`; `opcase` deleted in 8f-3 per the 8f-1 review D1 | TRUE |
| format.md: UCF carries border tables, the new `yin.vm.semantic.md` none | `grep -c '^+--'`: UCF 4, semantic.md 0, team.md 41 | TRUE |

The 7.11 wording: "the canonical kernel and its two backends" is used, the
pointer to continuation §4.6 is present, the 7.3 canonicalizer and the
7.4.1 stack-shaped safepoint halves are marked removed un-ported under
8f-1 R2 with the 8f-3 execution noted. Matches the owner-approved brief.

### 1.2 Q6(c) outcome and links (brief item 2)

- `git diff -B -M HEAD` pairs `yin.vm.semantic-register-vm.md` to
  `yin.vm.semantic.md` at 91% similarity: +95/-20. The 20 deletions are
  the status header, the "working name / replaces §2 §4 §5 of
  yin.vm.semantic.md" paragraph and the §10 intro sentence, each
  replaced by the implemented-status text, the `<a id="history">`
  block and the Met notes. No rule, table, transition or section of the
  design is lost.
- The four companions, diffed against their HEAD predecessors with
  `diff -I 'semantic.register'`: every remaining hunk is a path rename
  (`semantic_register` to `semantic`), a lane/kernel name, or a dated
  addition (the cutover note, the §4.6 and §8.4/§8.5 "Met" paragraphs,
  derivations §7.4-§8.3 status paragraphs). Nothing deleted.
- The history block's facts match the deleted v3 doc's header
  (`git show HEAD:docs/design/yin.vm.semantic.md`): revision 1
  2026-09-18 published `"v2"` (status corrected 2026-09-25), revision 2
  2026-09-25 published `"v3"` with `:define` and `yin/def`; the four
  axioms and six invariants as listed. ucf-revisions I-6 repeats them
  consistently.
- Relative links: every `](...)` target in the 20 touched docs resolves
  on disk (datom.world, datom, dao.jing, dao.stream, dao.lease,
  dao.space.query, yin.vm.macro, yin.vm.linker.dht, the four companions,
  ucf-transport-tuples, the v2 amendment, ucf-revisions,
  roles/orchestrator); the `#history` anchor exists. The one dangling
  link that existed before (`yin.vm.implementation-plan.md` in the
  divergence register) was converted to dated prose. Bare mentions of
  files not on disk (`yin.vm.debruijn.linker.md`,
  `yin.vm.lowering-profile.md`, `v1-retirement`, `v2-consumers`) are
  all in untouched lines and already historical.
- Acceptance greps: `yin.vm.semantic-register` outside the orchestrator
  log hits 9 lines, all dated (the 2026-10-10 "forthcoming" quotes in
  the three debruijn docs, the history header, §10 item 4/8 of the
  migration record, the evaluator §1.1 and continuation §0 history
  sentences). `semantic-register-vm` likewise. The orchestrator log is
  byte-identical. `sha256` in `src/cljc/yin/vm`: none.

### 1.3 Repointing judgments and §10 Met notes (brief item 3)

The nine old-citer repointings are right in kind: code-as-tuples and its
plan, UCF, ucf-revisions, the divergence register, engine.md (old §3.5 to
new §5 is the correct section), format.md, the three debruijn docs, and
the frozen design's own §1 ruling 2, §3.4, §5, §9 citations all say
"deleted v3 design" and point at `#history`. The §7.3.2 precedence note
in UCF is the right anchor for `"v3"` opcode-table citations.

§10 Met notes against `git log --merges master`:

| phase | doc says | git | verdict |
|---|---|---|---|
| 4 | Met 2026-10-10/11, 4a `44fbafb4`, 4b `993d5411` | merges 2026-10-10 22:46 and 2026-10-11 11:41 | correct |
| 5 | Met 2026-10-11, 5a `71b4f4d7`, 5b `5e6c6f79`, 5c `290c4476` | 5a merge 2026-10-11 14:55; `5e6c6f79` and `290c4476` are review-fix commits of 2026-10-11 on the slice branches; 5b and 5c merged to master 2026-10-12 06:33 (`7517f30e`, `e5df547a`) | date off by one for the landing; P3 |
| 6 | Met 2026-10-13/14, 6a/6b/6c and `6c-pre` `296f75fd` | 6a merged 2026-10-12 19:51, 6b 2026-10-13 00:32, 6c 2026-10-13 15:28, docs-6c-amend 2026-10-13; nothing on 10-14 | should read 2026-10-12/13; P3 |
| item 6 | 61 law-4 / 350 law-5 native, 89/534 reference, owner-accepted, justified by the fragment | owner ruling 2026-10-13 (memory, phase-8 plan, continuation §4.6) | correct |
| 7 | Met 2026-10-14, 196 SIGKILLed lives, slow tier green; the Dart `dht-end-to-end` failure is the other seat's, pre-existing | phase 7 merged to master `67b98907` 2026-10-14 12:38; 196 lives and the `dht-end-to-end` red/fix (`355bd84b` red on all hosts, fixed `08a3a722` by the admission track) are in the orchestrator log 11226-11248 | correct; the Dart-specific attribution is the brief's and the handoff's, not re-run here |
| 8 | Met 2026-10-15, slices 8ab/8c/8d/8e/8f-1/8f-2/8f-3/8g, `:hash :blake3` at 8ab, `SemanticVM` at 8f-2b, transport sources and completion at 8f-1, ucf halves at 8f-3, NaN at 8f-3d | `2b9f2b47`, `3d1a2c1e`, `e1f6a580`, `7b8d2a93`, `23c00ba3` | correct |

### 1.4 known-gaps (brief item 4)

Present and accurate: `frame.cljc:141` NaN gap (open, P3, cites the
8f-3 ruling §5 correctly); binder refusal collapsed to the projection's
`:slot-kind` rule (open, P2; `debruijn.cljc:527` `slot-kind-ok?` is the
one binder check, nothing in `debruijn/stack.cljc` or `register.cljc`
mentions binders); dead `malformed-vectors` fixture (open, P3); the
deletion ledger for completion and the UCF v3 halves (R2/R3, 8f-1 vs
8f-3 attribution correct); the two closure rows (scanner golden
`6fc65722`, address lint `00243b57`, both 2026-10-14) agree with git;
the historical-prose row for `vm/opcase` and `rows/checkpoint.cljc`.

Missing: see P2-1 below.

### 1.5 The 8964 deleted lines (brief item 5)

2824 + 894 + 1571 + 1602 = 6891 lines are the four companions at their
old paths (byte-identical moves modulo the rename, §1.2); 1155 is the
frozen design at its old path (paired rename, +95/-20 net); the
remainder is the old v3 body under the `yin.vm.semantic.md` path (its
836-line body plus the diff noise of a full rewrite) and the small
per-file edits. Nothing of the new design is lost.

### 1.6 The nine src/test edits (brief item 6)

`git diff -- src test`: nine hunks, each exactly one docstring or
comment line changing `yin.vm.semantic-register-vm` to
`yin.vm.semantic` (or `.continuation.md`, `.cursor-cell-numbering.md`)
in the citation; no code token touched. (`git diff -w` was refused by
the sandbox; the plain diff is sufficient, the hunks are one line each.)
The writer's kondo run over the nine files (0 errors, 0 warnings) and
`git diff --check` (clean) are reported, not re-run here; nothing in a
docstring can change kondo's answer.

## 2. Findings

**P1 (blocking):** none.

**P2 (fix before commit, docs only, three lines):**

1. `docs/agents/known-gaps.md` — the historical-prose ledger row lists
   only `vm/opcase` and `rows/checkpoint.cljc`. Eight more source and
   test citations of `docs/design/yin.vm.semantic.md` with v3 section
   numbers were made wrong by the rename and lie outside the nine-line
   allowance, so they must be ledgered, not silently left: `src/cljc/yin/vm.cljc:655`
   (§2), `src/cljc/datomworld/demo/continuation_handoff.cljc:67`,
   `src/cljs/datomworld/demo/continuation_stream.cljs:10` (§7),
   `src/cljd/yin/register_bench_cljd.cljd:6` (§8),
   `src/cljc/yin/vm/docs/jfr_benchmark_analysis.md:5,268` (§8),
   `test/bench/yin_vm_bench.cljc:3` (§8), `test/yin/repl_test.cljc:37`
   (§8), `test/yin/vm/semantic/linearize_test.cljc:286` (§2.7). In the
   renamed design §2 is the machine, §7 is "What A is" and §8 is the
   derivations, so a reader following these lands on the wrong rule.
   Add one row (evidence "8g Architect review", owner seat the
   register-VM track, closing slice "open: prose-only pass, retarget to
   the history block or the right new section", P3).

**P3 (nits; apply with P2-1 or ledger):**

1. `yin.vm.semantic.md` §10 phase 6: "Met 2026-10-13/14" should be
   "Met 2026-10-12/13" (6a merged 2026-10-12, 6b and 6c 2026-10-13;
   nothing of phase 6 landed on 10-14).
2. `yin.vm.semantic.md` §10 phase 5: "Met 2026-10-11" cites 5b/5c by
   their review-fix commits; the master merges are 2026-10-12
   (`7517f30e`, `e5df547a`). Either say "2026-10-11/12" or cite the
   merges, as phase 4 does.
3. `known-gaps.md` row "register design doc §2/§2.1 derivation prose":
   evidence says "8f-1 ruling R6 list", but R6 lists debruijn.stack.md,
   derivations.md, the frozen design, linker.md, UCF and ucf-revisions,
   not debruijn.register.md §2. Cite the brief (item 6) or the 8e
   review instead.
4. UCF 7.11 (line ~3004): "Use the canonical kernel and its two
   backends, checked on the documented fragment ..." reads as an
   imperative fragment. "The canonical kernel and its two backends are
   checked on the documented fragment ..." keeps the owner's phrase and
   parses.
5. `yin.vm.semantic.continuation.md` has two headings numbered 4.6
   (lines 1027 and 1089; pre-existing at HEAD). The docs now cite "§4.6"
   for the retained-fragment text, the second one. Renumber to 4.7 in a
   later prose pass; not this slice's defect.
6. ucf-revisions I-6 says 8d "removed the old-codec tests (16 files,
   141 deftests)". 8d's commit touches 31 files; I could not reproduce
   the 16 (the sandbox refused the stat listing). The 141 matches the
   phase-8 plan. Treat the file count as unverified.
7. `known-gaps.md` pre-existing `:try?` row cites `ucf/handoff.cljc:2036`,
   a deleted file. Evidence column, historical; harmless, could gain
   "(deleted 8f-1)" when next touched.

## 3. Invariants

A is the only code identity (docs say so; the H/R residue in the linker
index is stated as checksum keys, not identity). Native kernels are
backends, documented fragment, not widened (layout.cljc's four checks
are what the docs claim; no new refusal class invented in prose). Tree
always available (linker §3 amendment keeps `source-index` and
`:yin.link/trees`). BLAKE3 everywhere in `yin.vm`, SHA-256 explicitly
mintable in dao.jing only. Derive, don't persist (no new tables; the
docs record the literal opcode table as 8f-1's one exception). No
compat shim (no alias namespace, no old doc kept under a redirect).
Minimal diff on code (nine one-line docstring edits). collab untouched
by the writer except its report.

## 4. Verdict

The documentation is accurate against the code and git history, the
rename is lossless, the links resolve, and the acceptance greps pass.
One ledger row is missing for eight source citations the rename
invalidated, plus two §10 dates that the brief asked to be checked
against git and are off by a day. These are three doc-line edits with
no re-review needed beyond reading the three lines.

CHANGES-REQUIRED (scoped: P2-1 plus P3-1 and P3-2; the orchestrator may
apply them directly and commit on green lanes without another Architect
round).
