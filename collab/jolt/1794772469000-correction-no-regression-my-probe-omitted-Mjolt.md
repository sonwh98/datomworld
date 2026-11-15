Created-GMT: 2026-11-15 19:54:29 UTC
Created-Local: 2026-11-16 02:54:29 +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yin.repl jolt orchestrator seat)

# CORRECTION — there is no jolt-lane regression; my probe omitted -M:jolt

The two relay notes filed 2026-11-15 (the host-boundary relay and its facade-chain addendum) are
WRONG and are superseded by this note. Codex gpt-6.1-sol, dispatched to implement the `:jolt`
arms, challenged the diagnosis before landing anything — correctly.

## The facts

The configured jolt lane is `jolt -Srepro -M:jolt` (deps.edn:95, `:jolt {:replace-paths
["src/jolt" ...]}`), and `src/jolt/dao/host/*.jolt` supplies WHOLE-FILE overrides for all six
host namespaces — including an `integer.jolt` whose `promote`/`from-native` are already
identity over Chez's exact numeric tower. The shared cljc `:clj` interop arms are never
selected by the configured lane. My probes ran `jolt -Srepro` WITHOUT `-M:jolt` — a wrong
invocation, not a code defect. H1/H2 introduced no jolt regression.

Re-verified just now at master head, darwin, bootstrapped jolt b389f1a7: with `-M:jolt`,
`(require 'dao.jing)` loads (31 publics), `(require 'yin.repl)` loads, `yin.repl.main/-main`
resolves. This also matches everything already known: the J6g workload W ran the real REPL on
jolt for 108,105 rounds through exactly this lane.

## Dispositions

- The dispatch (codex thread 01a1e0bc, worktree ~/ci/dw-host-jolt-arms on jupiter) is closed
  NO-DEFECT. Codex's two shared `:jolt` arms (`:jolt x` / `:jolt n` in dao.host.integer) are
  NOT merged: production code should not change on a wrong premise, and the configured lane
  never selects those arms. The branch fix/dao-host-jolt-arms stands unmerged as the record.
- Codex's findings file is the authoritative diagnosis and is retained on master beside this
  note (`1794772115397-engineer-dao-host-jolt-arms.findings.md`, committed by the orchestrator
  since codex left the worktree uncommitted per its no-commit-on-wrong-premise judgment).
- The two 2026-11-15 relay notes stand superseded; no host-boundary action is required.
- Process lesson of record: ad-hoc jolt probes MUST use the configured lane invocation
  (`jolt -Srepro -M:jolt`, $JOLT_HOME/bin first on PATH). Every probe in this session that
  omitted `-M:jolt` produced artifacts of the omission, not of the code.

— the yin.repl jolt orchestrator seat
