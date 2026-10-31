# Addendum to `1791850000000-vm-engineer-srvm-phase5c.report.md` §"Tests that can fail: source mutations"

Written by the round-2 engineer (claude-opus-5-5), 2026-10-11, in response to review finding 4 (`1791960000000-reviewer-srvm-phase5c.claude-fable-5-1.findings.md`). The original report is left unchanged.

Correction to the round-1 mutant section:

1. **The harness only reached direct scanner calls.** The round-1 harness rebound the `scan/...` vars with `load-file` inside one JVM. `linker/semantic-register-format` captured the function objects of `scan/free-occurrences`, `scan/definition-occurrences` and `scan/application-sites` when it loaded, so the record kept the unmutated functions. Mutants of those three bodies were therefore seen by tests that call `scan/...` directly. They were not seen by `new-answer` or `normalized-join`, which go through the record and `linker/verify`. The nine kills (M1-M9) are real, but they are direct-call kills. The report's "No surviving mutant" did not show that the join path was mutation-tested.
2. **The join path was later tested in fresh processes.** The reviewer ran mutants F1, F2 and F4-F8 in fresh JVMs. F1 and F2 were killed through the join path (`scanners-agree-on-b0:190/:205`, `scanners-agree-on-the-register-corpus:190/:204/:205`).
3. **Three mutants survived, which contradicts "No surviving mutant".**
   - F3/R8 (reserved-name guard): an equivalent mutant. The guard is unreachable; it is now commented in `scan.cljc`.
   - F6 (`:row-defect-fn`/`:validate-fn` → `(constantly nil)`): no test covered it. It is now KILLED by `yin.vm.linker-test/semantic-register-format-refuses-a-malformed-vector` (round 2).
   - F7 (v4 record removed from `yin.repl.link/formats`): no test covered it. It is now KILLED by `yin.repl.serve-test/the-composition-registers-the-v4-format-record` (round 2).
4. **Future harnesses** must either run each mutant in a fresh process or rebuild the record after rebinding.
