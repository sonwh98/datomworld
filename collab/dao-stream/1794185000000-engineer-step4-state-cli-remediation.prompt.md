Created-GMT: 2026-11-11 09:30:00 GMT
Created-Local: 2026-11-11 16:30:00 +07
Coding-Agent: qwen
Model: qwen3.8-max
Role: Distributed Systems and Protocol Engineer
Worktree: /Users/sto/workspace/datomworld-yin-repl-dht
Branch: yin-repl-dht-composition

# Task: Step 4 — Remediate State & CLI test decoupling (§6, §9 Exit condition 4)

## Context & Findings
Per `docs/design/yin.repl.dht-composition.md` §6:
> `yin/repl/state.cljc:1–27,44–69`: Saves DHT flags, distinguishes one-shot flags, parses trust principals. Saved configuration **moves to composition**. Neutral principal parsing may move to the existing signing/admission layer; engine trust commands must not import CLI state.

In Step 4, `yin.repl.state` was parameterized so `flag-table` is extensible, and `yin.shell.cli` holds `state-table` and `flag-table`.
However, `test/yin/repl/state_test.cljc` tests the DHT-flag saving and subcommand expansion (`dht init`, `dht serve`, `dht join`, `--dht-peer`, `--dht-publish`, etc.) against the bare functions `state/split-args`, `state/load-flags`, and `repl/expand-args`.

When running on `shiva` (`clj -M:test -n yin.repl.state-test`), `test/yin/repl/state_test.cljc` expects the complete flag table (either by passing `cli/flag-table` explicitly in test assertions or having `yin.repl.state` recognize composition flags when `yin.shell.cli` is on classpath).

## Mission:
1. Examine `test/yin/repl/state_test.cljc` and `src/cljc/yin/repl/state.cljc`:
   - Ensure `state/split-args`, `state/load-flags`, `state/save!`, and `state/flags->args` seamlessly use the composition's flag table when called with default arities in tests, or parameterize `test/yin/repl/state_test.cljc` so that DHT-specific flag tests explicitly test `cli/flag-table` or move DHT state tests to composition tests while keeping engine state tests clean.
   - Specifically fix the failures in `state_test.cljc`:
     - `a-dht-subcommand-resumes-and-serve-stops-publishing`
     - `args-split-into-saved-flags-and-the-rest`
     - `dht-join-saves-its-follow-and-a-bare-start-resumes-it`
     - `a-bad-saved-state-or-a-clashing-flag-refuses-startup`
     - `a-value-that-begins-with-two-dashes-is-a-missing-value`
     - `state-round-trips-through-its-file`
2. Maintain Gate A Invariant:
   - `rg --hidden --no-ignore -n -F -- 'dao.jing.dht' src/cljc/yin/repl/ src/cljc/yin/repl.cljc` => ZERO matches.
3. Strict Laptop Load Invariant:
   - Do NOT run heavy test suites locally on laptop.
   - Run only G0 syntax and balance checks locally.

Output your diagnosis and diff changes.
