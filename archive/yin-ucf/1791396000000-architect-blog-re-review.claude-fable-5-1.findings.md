# Architect re-review: "Beyond Monolithic Runtimes: Languages as Distributed Tuples and Streams"

Role: Lead System Architect (claude-fable-5-1)
Date: 2026-10-08 (Asia/Ho_Chi_Minh)
Subject: `public/chp/blog/languages-as-distributed-tuples-and-streams.blog` (commit e6421ca3)
Previous review: `collab/1791395000000-architect-blog-review.claude-fable-5-1.findings.md`
Tree reviewed: master@e6421ca3

## Verdict

**ACCEPTED.** Every blocking item from the first review is closed, and all
recommended non-blocking items were taken. Two cosmetic nits remain below;
either may be applied by the Writer without another Architect pass, or
left as is.

## Item-by-item

### 1. Primitives count and module surfaces — closed

Line 18 now reads "three pure modules ... with 55 exports between them. The
entire Python prelude reaches the host through 40 of those names plus the
VM's 22 core primitives." That is the exact table from review §1.1
(cell 3, data 26, integer 26 = 55; Python calls 40; `yin.vm/primitives` 22).
Per-module lines 20 to 22 carry 3 / 26 / 26 and the corrected
characterizations: "UTF-16 units and Unicode code points", "exact
arithmetic bounded by declared bit and digit limits", "and the runtime
context" in place of "frame stacks". Abstract (line 3) and intro (line 12)
dropped "40 host primitives" and "arbitrary-precision" for "three pure host
modules" and "exact integers to a declared bound". Section 1 title is the
suggested one. Line 24 states the second-language claim as a bet checkable
from the published manifest and cites §8.5.6; the exclamation mark is gone.

### 2. Tense discipline — closed

- DHT (section 3, lines 47 to 52): lead-in is "Phase C4 (§8.5.6) makes the
  Python prelude a linker module named `py` ... Once that lands:", and the
  three bullets sit under that conditional. The upgrade bullet now reads "A
  new program pins a new hash, while a running task keeps the image it
  started with" (ruling 11) and the reproducibility bullet is scoped to "A
  published package pins its prelude" (the "Addresses" distinction). The
  "gigabytes" claim is "hundreds of megabytes".
- Module shape (lines 55 to 61): matches `yang.antlr.md` line 2677 to 2680
  verbatim except for the per-import `(require 'pym.c)` line, which is
  correctly omitted for a leaf module; the comment "hoisted content
  delivery" paraphrases "hoisted: code delivery only" acceptably.
- Virtual POSIX (section 5, line 74): "Here is where the architecture
  points next" frames the four bullets as plan. `dao.space.store` is gone;
  line 77 says content-addressed blobs in the `dao.jing` store with
  stream-backed directory trees over them. Line 78 says "a dial or a serve
  over `dao.stream.remote-channel`, which is itself a stream". Line 80
  separates what landed (UCF identity and wire form) from what is pending
  (heap lift), with the "Computation Moves, Data Stays" back-link.
- Intro (line 12): "C4 (the linked prelude), is designed and under way";
  the invented name "The Linked Runtime" is gone.

### 3. Prelude semantics — closed

`py/truthy` (prelude line 1472), `py/make-class` (line 595) and
`py/getattr` (line 884) are the real symbols. Line 32 says
"single-inheritance attribute lookup and dynamic dispatch"; MRO is gone.
Line 27 lists "lambda calculus, continuations, effects, and the cell and
stream modules". Section 2 title is "What a Prelude Is".

### 4. Rows example — closed, one nit

Lines 35 to 43 show `a + b` lowered to an application of `py/add`, which is
what the compiler emits (`yang/python/antlr/lower.cljc` line 539 maps `"+"`
to `py/add`). The negative-id EAV style is the one
`universal-ast-vs-assembly.blog` uses (lines 17 and 190), so the series is
consistent. Line 44's point ("no host names ... appear") is the right
payoff.

Nit (a): line 41 packs two attributes into one row,
`[-2 :yin/type :variable :yin/name py/add]`, while rows 42 and 43 and every
row in the sibling posts carry one attribute each. Split it:

```
[-2 :yin/type :variable]
[-2 :yin/name py/add]
```

### 5. Symmetric ignorance — closed, one nit

Line 64 says "multiple independent observers can read the exact same
stream concurrently, each completely ignorant of the others", and line 70
states the invariant in the master design's own words ("the evaluator never
consults an index to run, and the index never evaluates a form to index
it"). "Pipeline" is gone from the title and body. "and macros" is replaced
by the parenthetical that the expander is one more observer on the syntax
side of a stream boundary, which matches `yin.vm.macro` and the master
design. The `$ast` bullet (line 69) now attributes indexing to
`dao.space.index` and the relation to `yin.repl`, queried through
`dao.space.query/q`. The `[tag & slots]` payload versus `[id tag & slots]`
logical row distinction (line 64) matches `yin.vm.code-as-tuples.md` line
179.

Nit (b): line 70 opens "As explored in AST Datom Streams and Universal AST
vs Assembly, this symmetric ignorance is the foundational invariant". Those
posts show rows and lowering; the invariant sentence itself comes from the
"Streams" section of `datom.world.md`. Suggested: "This symmetric ignorance
is the master design's load-bearing invariant: ... For the rows themselves,
see AST Datom Streams and Universal AST vs Assembly." Optional.

### 6. Format consistency — closed

All six lists are `[:ul.bulleted` (zero bare `[:ul`). Three series
back-links are present and all four targets exist in `public/chp/blog/`:
`ast-datom-streams-bytecode-performance.blog`,
`universal-ast-vs-assembly.blog`, `computation-moves-data-stays.blog`. The
file reads as EDN with the expected four keys (`:blog/title`, `:blog/date`,
`:blog/abstract`, `:blog/content`). The abstract is two sentences. Tone
pass taken: "massive", "true", "seamlessly", "the Soul of a Language", "the
Whole World" and the exclamation mark are all gone.

## Summary for the orchestrator

- Verdict: ACCEPTED as committed at e6421ca3. Publishable.
- Optional follow-up, no re-review needed: split the two-attribute row
  (nit a) and reattribute the invariant sentence (nit b).
