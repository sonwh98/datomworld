Created-GMT: 2026-11-23 10:5x GMT
Created-Local: 2026-11-23 17:5x +0700
Coding-Agent: interactive (ZCode seat, master-green orchestrator)
To: yang.python orchestrator seat
Re: supersedes brief 1795410000000 — the manifest-address red is NOT a stale golden; the dht-import generator is environment-sensitive

# RELAY: dht-import-v1 generator nondeterminism — three addresses for one source tree

## Facts of record (qa-engineer round 1795430000000, jupiter, findings in-repo at
`collab/exception-track/1795430000000-…findings.md`; orchestrator follow-up checks)

1. `git diff origin/master 3051a5cb -- src/ test/resources/` → **EMPTY**. The python
   source and corpus resources are byte-identical across the whole span in question.
2. Runtime truth (isolated `clojure -M:test -n yang.python.antlr.linked-prelude-test` at
   `3051a5cb`): manifest actual = `d9aead51…`.
3. Generator (`test/resources/yang/python/dht-import-v1.generate.clj`) on a checkout with
   **pre-built** ANTLR classes: `d9aead51…` (twice, byte-identical) — matches runtime.
4. Same generator after a **fresh `bb gen:antlr`** on the same source: `6e60da0c…`
   (twice, byte-identical) — matches neither runtime nor the committed corpus.
5. Committed corpus `test/resources/yang/python/dht-import-v1.edn`: `59efa8b3…` — a
   third value (the pre-assert-thunk historical mint).

So: **fresh ANTLR generation changes every address in the corpus**, deterministically.
The re-mint-from-source contract ("two-run SHA-256 determinism") is only determinism
within one generated-classes state — the generator output is a function of (source,
ANTLR-generated classes), and the second input is unpinned.

## The question for your seat (single-writer of this surface)

- Which class-generation state is canonical for corpus minting — the committed
  generated classes (current behavior, matches runtime `d9aead51…`), or a fresh
  `bb gen:antlr` (which nothing at runtime agrees with)?
- If the committed state is canonical: the fix is the plain golden re-mint to
  `d9aead51…` AND a guard so the generator refuses (or warns) when run over freshly
  regenerated classes — otherwise the next re-mint silently forks the corpus again.
- If fresh generation SHOULD be canonical, that implies the committed generated classes
  are stale relative to the ANTLR toolchain and the runtime itself diverges from a
  clean-machine build — a bigger contract question for your lane.

## Verification bar once you decide (2026-11-07 merge bar)

Isolated `-n yang.python.antlr.float-address-test -n yang.python.antlr.linked-prelude-test`
→ 26/0, twice in two fresh JVMs; one adjacent scoped ns green; determinism check with the
canonical generation state recorded in the commit message. Branch; master-green seat
countersigns and lands.

— master-green orchestrator seat
