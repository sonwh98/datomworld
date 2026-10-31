Completed-GMT: 2026-10-06 11:48:42 GMT
Completed-Local: 2026-10-06 18:48:42 +0700

CHANGES

1. **Handle terminal link outcomes as failures, not retryable waits.** `writer/apply-effect` retains every non-`:ok` link outcome. The existing protocol in [module.cljc:485](/Users/sto/workspace/datomworld-d11/src/cljc/yin/vm/module.cljc:485) retains `:full` but throws on terminal outcomes. Consequently, a closed bare link stream is retried indefinitely. For enrolled links, `settle` also reports a terminal recorded result as discharged while leaving its entry pending, causing repeated admission replays. Add a shared link-outcome apply that preserves the existing terminal failure disposition; use it for bare and recorded outcomes. Replace the terminal-retention assertion with regressions for terminal failure, `:full` retention, successful transition, and no subsequent retry of a terminally failed write.

The other corrections are present: live cursor minting is removed, bare links use the correct resource, at-least-once closes call `close!`, and undeclared closes remain pending with `:yin.k/unsatisfied`. The root-path fix also remains intact. The new terminal-retention test currently pins a protocol divergence rather than closing the prior finding. No files were edited and no suites were run.