Created-GMT: 2026-09-20 18:18:50 GMT
Created-Local: 2026-09-21 01:18:50 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: 923b8885-4549-4b46-ad11-0731ebb614ef
# Task: implement D0+D1 of the de Bruijn projection
Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-21 01:18:50 +07 | Status: active | Rationale: fresh weekly budget; strongest implementation seat available; compiler-layer work per routing

You are working in the git worktree at /Users/sto/workspace/worktree-debruijn-impl
(branch debruijn-impl). Work only inside this worktree. Do not stage, commit,
merge, or push anything — the orchestrator owns git state.

Read first, in this order:
1. docs/design/yin.vm.debruijn-projection.md — the governing contract. Every
   sentence is a rule. Your scope is ONLY phases D0 and D1 (section 7).
2. docs/design/datom.md — the dimension protocol the :yin.debruijn/*
   descriptor must be published through.
3. src/cljc/yin/vm/engine.cljc — bind-params (rightmost-wins is the rule your
   resolver must match), and the emitter's ast->datoms-with-root in the same
   namespace tree; src/cljc/yin/vm/ast_walker.cljc — the node grammar's
   authoritative walk order.
4. src/cljc/yin/vm/encoder.cljc — existing encoder; read to avoid duplicating
   or altering it. Do not modify it.
5. docs/agents/build-n-test.md — how to run the test lanes.

Deliverables (D0+D1 only):
- New namespace src/cljc/yin/vm/debruijn.cljc implementing:
  - D0: the :yin.debruijn/* dimension descriptor and hash domain separator
    published per the datom.md protocol; the canonical value table (section 5,
    including numeric canonicalisation rules) as data/validation; one
    host-dispatched NFC normalize seam — :clj uses java.text.Normalizer (NFC),
    :cljs uses String.prototype.normalize("nfc"), :cljd delegates to the
    unorm_dart package (owner-settled, Unicode 16.0);
    root framing and input validation per section 2 (exactly one root,
    assert-only with only :db/retract diagnosed, fact index reset per frame,
    unknown :yin/* diagnostic, other namespaces ignored, :yin/macro-name and
    :yin/tail? tolerated-and-ignored, unexpanded-macro detection rule and its
    stated limit); and the node grammar table from section 2 as the walk
    vocabulary.
  - D1: the scope resolver per section 3 — ordered frame vectors, depth 0
    innermost, position from source-leftmost 0, inner-to-outer search,
    right-to-left within a frame (rightmost-wins duplicates), {:bound [depth
    position]} and {:free name}, memo keyed by [source-eid complete
    frame-vector stack] as optimisation only. No hashing in this phase —
    node hashes are D2/D3.
  - pubspec.yaml: add the unorm_dart dependency for the :cljd NFC seam. This
    is the ONLY existing file you may touch, and only that addition. If the
    dependency demands further file changes, STOP and report instead.
- New test namespace test/yin/vm/debruijn_test.cljc covering the section 8
  matrix rows that D0+D1 can exercise: renamed binders at one and multiple
  nesting levels; nearest shadowing with [frame-depth position] results;
  duplicate parameters (fn [x x] x) proving rightmost-wins; free names
  preserved; every node type from the section 2 grammar walking in fixed
  child order; shuffled datom input producing the same resolved graph; shared
  nodes under equal and unequal lexical contexts; :yin/tail? present vs absent
  identical; diagnostics for dangling refs, duplicate facts, cycles,
  missing/multiple roots, unknown node types, unknown :yin/* attributes,
  retracts, unexpanded macros (lambda-operator rule), unsupported values, and
  partial frames at end of stream; adjacent rooted graphs reusing -16-based
  temporary eids. Match the existing test style in test/yin/vm/.

Hard box (section 9 of the design — violation fails the phase): do not modify
the :yin/* schema, the emitter, ast_walker, engine, linearizer, semantic VM,
encoder.cljc, dao.stream, dao.lease, dao.stream.waitset, or ANY existing test
file. New files are src/cljc/yin/vm/debruijn.cljc and
test/yin/vm/debruijn_test.cljc, plus the single pubspec.yaml dependency line.

Environment (mise is untrusted in this worktree — export before running
builds; one simple command per step; if the sandbox denies a compound
command, split it into single commands; never use --dangerously-skip-permissions):

  M=$HOME/.local/share/mise/installs
  export JAVA_HOME="$M/java/17"
  export PATH="$JAVA_HOME/bin:$M/clojure/1.12.4.1602/bin:$M/babashka/1.13.223:$M/babashka/1.13.223/bin:$M/flutter/3.47.4-stable/bin:$M/node/25.6.1/bin:$M/cljstyle/0.17.642:$M/cljstyle/0.17.642/bin:$PATH"

Verification (report exact commands and assertion counts for each):
- JVM focused: the new namespace's tests via the JVM lane (see
  docs/agents/build-n-test.md; `bb test:clj` runs the full JVM suite — if a
  focused alias exists prefer it).
- CLJS: same namespace under the Node lane.
- CLJD: `bb test:cljd` — the namespace must compile and pass under Dart,
  proving the unorm_dart seam resolves. If the pub dependency cannot resolve,
  report the exact error and leave the seam's :cljd branch diagnosing rather
  than silently passing.
- Existing suites: report whether you ran them; do not fix failures outside
  your box — report them.

Completion criteria for this dispatch (from the design): shuffled input
produces the same resolved semantic graph; all bindings resolve to the
specified [frame-depth position] pairs or {:free name}; all D0 diagnostics
diagnose; the descriptor is published with its hash domain; zero changes to
files outside the box.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes per host, unresolved concerns,
and any incomplete work. Do not claim edits or tests that did not occur.
