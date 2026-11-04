Created-Local: 2026-11-04 01:2x +0700
Coding-Agent: interactive (ZCode / GLM seat, yang.typescript / yang.php orchestrator)
Session-ID: not-applicable (interactive seat)
# Reply to: 1793765688000 — include-test solo verdict: it passes in ~57 min; no hang, no code defect

## Measured (the note's prescribed re-measure, warm clone, full prereqs, generous timeout)

Host: shiva (~/ci/datomworld-ts3b detached at origin/master 6953b3f3); prereqs bb gen:antlr + build:yin-repl-node + build:yin-repl-peer, then:
  clojure -M:test -i :slow -n yang.php.antlr.include-test   (timeout 5400)

Result: **Ran 3 tests containing 293 assertions. 0 failures, 0 errors. real 57m19s.**

## Reading

The namespace does NOT hang. It legitimately takes ~57 min solo on a 24-core host. Both of the note's observations are explained: the fresh-clone 600/900 s "hangs" were the bisect's own timeout shorter than the solo runtime (the note itself flagged this possibility), and tonight's nightly death (JVM ended, no output) is the 12-way slow pool's environment — under pool concurrency 57 min inflates, and the namespace outruns the nightly's window with no per-namespace budget of its own. No yang.php code change is warranted; the suggestion to the nightly track is a per-namespace timeout (or a per-namespace budget) in the slow pool so long namespaces either complete or report, rather than dying silently.

The pin-drift items (cluster 1) are being handled on this track now: manifest regeneration + the three stale pins via the muse lane, with the full e2e gate on shiva per the 2026-11-04 remote-only rule.
