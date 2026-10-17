Completed-GMT: 2026-10-16 19:13:14 GMT
Completed-Local: 2026-10-17 02:13:14 +0700

# Architect review: regenerated `[:c4 :py]` / `[:c4 :t]` scanner goldens

The regeneration is sound. Every moved answer traces to a known slice, the untouched groups are provably unchanged, and nothing behavioral rides the diff. Findings below are all non-blocking.

## 1. Do the moved answers trace to I4-a/I5/I6?

Yes, with one correction to the brief's framing: the `:t` delta is I4-a work, not I5/I6. The file docstrings say "I4-a/I5/I6" and are correct.

**`:py` (+126 applications, +9 free names), traced by `git log -S` on `prelude.cljc`:**

| name | origin |
|---|---|
| `import-hooks-fail`, `import-namespace-check`, `reload`, `py.b/reload`, `py.b/import-module`, `py.rt/importlib` | I6 (41b95071): importlib module, reload, finder/loader refusal, `__import__` guard |
| `import-refused`, `data/contains?`, `py.b/ModuleNotFoundError` | I4-a (d27adce4): structured refusal path |

`import-module?` (I6) appears in the diff as a new definition and store key but not as a free name, because `lower.cljc` applies it only inside the lowered import call path. That is consistent, not unexplained. The large `dict-set`/`make-function`/`make-class` block moves in the `:py` occurrences are reorderings of the init form, net zero names; the builtins list gains exactly `ModuleNotFoundError` with `ImportError` as parent.

**`:t` (+4 applications, +4 free names):** `try-require`, `=`, `get`, `conj` are exactly the delivery thunk emitted by `delivered` in `src/cljc/yang/python/antlr/lower.cljc:754-768` (I4-a). One eager `require` occurrence and the `pym.k/spec`/`pym.k/body` reads move into the thunk tail, which is why occurrences go 13 to 15 and the tail shape changes. 14 to 18 applications matches the four new call sites.

Nothing unexplained.

## 2. Are the other groups unchanged? Sufficient?

Yes. Three independent confirmations:

- Hunk analysis on the diff: every hunk lies within old lines 568 to 7032 (the `:py` entry; group bounds 514 to 7032), old lines 7322 to 7353 (the `:t` entry; bounds 7321 to 7366), or the ns docstring. `:pysp`, `:k`, `:m`, `:w`, `:e`, `:n`, `:b0`, `:register` have no hunks.
- The generator's abort gate recomputed those groups live against the committed pins before writing.
- My own rerun in this worktree: 3 tests, 208 assertions, 0 failures, 0 errors.

Sufficient.

## 3. Generator soundness

The pipeline is adequate against both tampering and drift:

- Hand-tampering is not merely discouraged, it is detected: `goldens-match` recomputes every answer live, so an edited pin fails the slow lane regardless of the generator.
- The read-back check (`read-string` of the rewritten tail must equal `intended`) proves the written text parses to the intended data. The engineer's two generator logs differ only in the "old -> new" summary lines (the second run reads back unchanged pins), which demonstrates idempotency and byte stability.
- Next prelude bump: the hardcoded `deferred #{:py :t}` means any other moved group aborts the run until `deferred` is widened deliberately. That is the right default and the header documents it.

Nits, not required: `read-string` could be `clojure.edn/read-string` since the pins are pure data. The script is only run by hand, so drift is caught by the slow lane rather than by CI, which matches the owner's test-tier cadence.

## 4. Nothing behavioral rides the diff

Confirmed. Working tree touches only `scanner_goldens.cljc` (pins + docstring) and the new generator under `test/resources/`, which has no `ns` form and is not loaded by the test runner. kondo 0 errors / 0 warnings, cljstyle clean on both files.

## Findings

- **[minor, landing action]** `docs/agents/known-gaps.md:40` row "C4 scanner pins ... stale since I4-a" should be closed in the landing commit. The engineer correctly left it for review.
- **[nit]** `scanner-golden-c4.generate.clj:116` uses `read-string`; `clojure.edn/read-string` suffices. No change required.
- **[note]** Engineer's report was emitted to stdout log, not the `.findings.md` path named in the brief. Content is complete.

Verdict: READY (sign-off granted)
