Created-GMT: 2026-11-12 12:57:44 GMT
Created-Local: 2026-11-12 19:57:44 +07
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: Is a native-code interpretation of the yang pipeline possible? (design ruling)

Role: Architect (design ruling; READ-ONLY; the owner requested an independent opinion
from a fresh thread — you have NO prior conversation context)

Model: gpt-6-astra | Host: jupiter (POOL 2) | Static analysis only: the headless
sandbox on this host cannot execute commands (see docs/agents/routing-status.md
2026-11-12 entries). Everything you need is readable in the working tree; no test
runs are required for this ruling.

## The owner's question (verbatim)

> one of the core ideas behind the yin.vm and yang compilers are that one truth many
> interpretations. The yang compiler pipeline is designed to be transformed. Is there
> a reason why the transformation pipeline cannot transform the high level UAST to
> highly optimize native code that runs on par with cpython or even exceed cpython?

## Measured context (today, on the orchestrator's laptop — these artifacts are
## uncommitted, so the data is inlined here; do not look for them in the tree)

The harness compiled the e2e `recursion-test` fib through the real pipeline
(parser stage -> lowering with the bundled prelude -> linearize ->
`derive/register-image`) onto `yin.vm.debruijn.register`, and ran the same
source under CPython 3.9.6:

| fib(n) | Python calls | register-VM run (ms) | ms/call | cpython in-proc (ms) | ratio |
|---|---|---|---|---|---|
| 13 | 753 | 6 559 | 8.7 | 0.063 | ~104 000x |
| 15 | 1 973 | 19 138 | 9.7 | 0.139 | ~138 000x |
| 17 | 5 167 | 44 932 | 8.7 | 0.348 | ~129 000x |

One-time costs outside the timed region: parse+lower+project ~1.8-3.4 s;
`create-vm` (validator + register-hash) ~0.6-0.9 s, flat in n.
Direct step count (stepping `vm/step` to halt): fib(10) 279 470 steps /
177 calls; fib(13) 1 126 478 steps / 753 calls -> ~1 470 register-VM
instructions per Python-level fib call. 8.7 ms/call / 1 470 steps
~ 5.9 us/step — matching docs/benchmark.md's primitive-tier ~5.8 us/step.

## The orchestrator's prior answer (CHALLENGE IT, do not defer to it)

Claim 1: no architectural reason blocks a native backend; the register image is
already a flat three-address IR; "many interpretations" admits a compiled one.
Claim 2: the 10^5 gap decomposes into ~50x work amplification (the bundled
prelude interprets every Python operator in ~200 VM instructions) x ~2 500x
per-step cost (persistent-value interpreter: per-instruction assoc on the
register vector, boundary validation, keyword dispatch, protocol indirection).
Claim 3: AOT of the image alone would NOT reach parity — naive compilation of
dynamic Python lands slower than CPython (which is itself a compiled runtime);
parity requires specializing away the dynamism (53-bit unboxing already exists
in yin.vm.integer's fast kernels; call-site binding via the linked `py`
module; direct opcode binding for `:load-free`), and exceeding CPython
requires PyPy-style tracing/JIT or native codegen with type feedback.
Claim 4: the real semantic obstacles are (a) first-class continuations +
park/resume forcing heap frames or CPS, (b) effects-as-stream-deposits fixing
cross-host byte-identical observable order (constraining which optimizations
are legal, not whether speed is possible), (c) the integer module's bigint
range (max-bits 100000) forcing boxed bignums off the fast path.
Claim 5: the design already supplies the containment for the risk — the
differential-oracle pattern (reference VM as oracle for the fast backend,
as latte-kernel is for yang.clojure), and one-image-three-substrates (JVM
bytecode / JS-WASM / Dart AOT) as the portability-preserving form of "native".

## Your job

Ground every judgment in the tree: read docs/design/datom.world.md (axioms and
invariants), docs/design/yin.vm.md if present, docs/design/yin.vm.debruijn.register.md
(the kernel's contract incl. S5/S6 R4), docs/design/yang.antlr.md sections on the
prelude/linked profile (8.5.x, and the `:yang/python` multi-target notes),
docs/benchmark.md, src/cljc/yin/vm/debruijn/register.cljc,
src/cljc/yin/vm/integer.cljc (fast kernels), src/cljc/yang/python/antlr/prelude.cljc
and lower.cljc, and test/yang/python/antlr/linked_harness.cljc. Then:

1. Answer the owner's question directly and literally: is there a REASON the
   transformation pipeline cannot transform the high-level UAST to highly
   optimized native code that runs on par with cpython or even exceeds it?
   Distinguish sharply between (i) architectural/logical impossibility,
   (ii) invariant-preserving feasibility at real cost, (iii) achievable only
   by abandoning or re-interpreting stated invariants (name which).
2. Rule on each of the orchestrator's Claims 1-5 above: AGREE / AGREE-WITH-
   CORRECTIONS / DISSENT, each with evidence from the tree. Rebuke anything
   the prior analysis got wrong or understated — the owner values dissent
   with proof over confirmation.
3. If you answer "possible", name the first two slices a native interpretation
   plan would actually take (the smallest ones that falsify or confirm your
   own thesis), and say what would falsify Claim 2's decomposition.
4. If you answer "not possible / not on par", identify the single load-bearing
   obstacle and say what would have to change in the architecture's contract
   for parity to be reachable.

End with exactly one verdict line, chosen from:
(A) No reason in principle — pipeline transformation to native can reach and
    exceed cpython; the gap is engineering; state the first slices.
(B) Reachable on par, exceed not without giving up named invariants — name them.
(C) A load-bearing architectural obstacle prevents it — name it precisely.

Constraints: strictly read-only (the run is `-s read-only`); no edits, no
commits, no test runs; do not touch collab/. Repo root is your working
directory. If a command you would like to run cannot execute in this sandbox,
say so in the report and proceed from the sources.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
