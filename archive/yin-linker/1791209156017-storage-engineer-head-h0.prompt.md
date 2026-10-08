Created-GMT: 2026-10-05 14:05:56 GMT
Created-Local: 2026-10-05 21:05:56 +07
Coding-Agent: claude
Session-ID: c7244636-7620-475e-b23a-a591106058b2

# Task: head-h0

Role: DaoSpace and DaoJing Storage Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-05 21:05:56 +07 | Status: active | Rationale: complex agentic coding per team.md; Claude-family author, so a different-family reviewer (gpt-6.1-sol) gates it before commit

Implement slice **H0 (the trace and its rule)** of the published head trace in
/Users/sto/workspace/datomworld/.claude/worktrees/head-h0 (a git worktree of
master; the design is merged at docs/design/yin.vm.linker.dht.head.md).

## Read first

- docs/design/yin.vm.linker.dht.head.md: section 5.2 (the trace schema), the
  `judge` table in 5.5 (every outcome row), section 6 (the plain API: `verify`,
  `seq-of`, `judge`), and section 11 slice H0 (your acceptance criteria).
- docs/design/yin.vm.linker.dht.md section 6.3 (the name-envelope signing you
  must stay domain-separated from) and 5.1 (unwritten-row recovery).
- src/cljc/yin/vm/linker/sign.cljc and test/yin/vm/linker/sign_test.cljc,
  test/yin/vm/linker/sign_vectors.edn (how name envelopes are signed and how the
  vectors are laid out and checked on all hosts).
- src/cljc/dao/space/transactor.cljc (about line 117, `derive-next-t`: the first
  transaction is `t = 0`) and src/cljc/yin/repl/index.cljc (about lines 189-233
  rehydration, 222-233 `(reset)`, 378-435 the publishing path), which `seq-of`
  must be shown consistent with.

## What to build

- New `src/cljc/yin/vm/linker/head.cljc`: `trace` (builds and signs a trace from
  a key, a manifest and a sequence, exactly the shape of 5.2), `verify` (true or
  false, never throws), `seq-of` (the greatest `t` among datoms; `nil` for no
  datoms) and `judge` (`(judge principal floor installed trace)`, pure, producing
  every row of the 5.5 table as data, with no source argument and no candidate
  argument). Follow the design's exact keywords (`:yin.head/envelope`,
  `:yin.head/principal`, `:yin.head/manifest`, `:yin.head/seq`,
  `:yin.head/proof`, `:yin.head/signature`, and the outcome keywords
  `:yin.head/malformed`, `:wrong-principal`, `:bad-proof`, `:stale`,
  `:equivocation`, `:candidate`, `:duplicate`). If the table or section 6 leaves a
  return shape unspecified, choose the plainest data shape, write it in the
  namespace docstring, and list it in your report so the reviewer can check it.
- `src/cljc/yin/vm/linker/sign.cljc`: add the head domain-separation prefix
  `yin.head/trace:v1\n` (the signed message is that prefix as UTF-8 bytes followed
  by `dao.jing/canonical-bytes` of the envelope; the primitive is the existing
  Ed25519, unchanged).
- `test/yin/vm/linker/sign_vectors.edn` and new
  `test/yin/vm/linker/head_test.cljc`.

## Acceptance criteria (H0 of the design, verbatim; all must hold)

- One trace under RFC 8032 TEST 1's seed is in the vectors with its canonical
  bytes, signed message and signature, byte for byte on all three hosts; one has
  a sequence above 2^32.
- Tampering with the principal, manifest or sequence, a flipped signature bit,
  another key, an extra key in either map, and a name-envelope signature offered
  as a head proof each fail.
- A trace through `dao.stream.cbor` verifies on each host.
- **Sequence zero.** With a `nil` floor a trace of sequence 0 is a candidate.
  With floor 0: sequence 1 a candidate, sequence 0 with the installed manifest a
  duplicate, with another `:equivocation`. A negative or non-integer sequence is
  `:malformed`. `seq-of` of one transaction is 0, asserted against
  `dao.space.transactor`.
- `judge` produces every row of 5.5, and its arity admits no source and no
  candidate.
- `seq-of` over a rehydrated index equals `seq-of` before the restart; a further
  round raises it; two HEAD writes of one directory never share it across rounds,
  name transactions, `(reset)` and the unwritten-row recovery of
  `yin.vm.linker.dht.md` 5.1. **If a HEAD move that commits no transaction is
  found, STOP and report it; do not work around it. The design reopens section
  5.2 in that case.**

## Scope and process rules

- Work only in the four named files. If a dependency needs another file, stop and
  ask; do not edit it.
- **Do not run any git command** (no add, commit, status-changing command, stash,
  reset). The orchestrator stages and commits after review.
- **Do not run cljstyle or the formatter**; the orchestrator formats before commit.
- Verify in the **foreground** with focused JVM runs only, for example
  `clj -M:test -n yin.vm.linker.head-test -n yin.vm.linker.sign-test`, and
  `clj -M:kondo --lint <your files>`. Do not start background processes (a lane
  started in the background dies with your turn). **Do not run the Node or Dart
  lanes**; the orchestrator runs them at landing and will report any host failure
  back to you.
- Portable `.cljc` only; do not edit anything host-specific. Known traps: a
  `#?(:clj ...)` branch is also read by the ClojureDart pass, so a JVM-only test
  is `#?(:cljd nil :clj ...)` with `:cljd` FIRST; a `0.0` literal is the integer 0
  on JS (use `(cbor/float64 0)` or `0.5` in portable tests); ClojureDart's EDN
  reader throws on whitespace before a closer; `(- x)` is `0 - x` on Dart; do not
  use a duplicate `_` parameter in a protocol method; do not use `#'ns/private-var`
  across namespaces. Sequences above 2^32 are required to round-trip on JS, so keep
  them as exact integers through canonical bytes and cbor.
- Preserve unrelated changes, do not weaken any existing test, and do not change
  the name-envelope signing in any way: a head trace and a name envelope must not
  verify as each other.
- If the design contradicts the code or itself, say so in your report and do not
  silently diverge.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: c7244636-7620-475e-b23a-a591106058b2

Then report: changed files; the exact test and lint commands you ran with their
outcomes (assertion counts); every return shape you chose; the seq-of evidence
for each HEAD-move case; unresolved concerns; and any incomplete work. Do not
claim edits or tests that did not occur.
