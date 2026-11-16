Created-GMT: 2026-11-14 05:5x GMT
Created-Local: 2026-11-14 12:5x +07
Dispatched-By: master-green orchestrator seat (interactive; owner-authorized concurrent dispatch)
Destination: codex exec -m gpt-6.1-terra on jupiter (POOL 2), session D-LINT
Task-ID: 1794634500000-engineer-dlint-siteset-conform

# ENGINEER: dao.jing architectural-lint red — the PHP siteset round's equality validation

## Evidence

JVM nightly at `23de41a9` (jupiter, 20261114-0709), lane `dao.jing`, ONE failure:

    FAIL in (architectural-lint-sweeps-production-sources) (hash_registry_contract_test.cljc:479)
    source sweep: no equality comparison against dao.jing default minting vars
    Found forbidden equality validation in src/cljc/yang/php/antlr/siteset.cljc: (defn derive-verified …

Owning commits: the PHP siteset round `e0d915e2`..`f7726e49` ("Add PHP site set discovery and
verified derivation helpers" etc.), which added equality-style validation in
`src/cljc/yang/php/antlr/siteset.cljc` without conforming to the lint contract in
`test/dao/jing/hash_registry_contract_test.cljc`.

## Mission

Make the lint green WITHOUT weakening the lint. Read the lint contract first (what the sweep
forbids and why — equality comparison against dao.jing default minting vars defeats content
addressing). Then conform `siteset.cljc`'s validation to the sanctioned pattern (probably
compare derived content addresses via the registry API instead of raw equality against minting
vars). If the correct fix is contract-side (the sweep's flags-equality-validation? has a false
positive on a legitimate pattern), STOP and report — the lint is the contract, and its owner
(the dao.jing/architecture lane) adjudicates; do not edit the lint test to make it pass.

## File ownership

`src/cljc/yang/php/antlr/siteset.cljc` (+ at most one sibling php file if the validation
helper lives there). NOT the lint test, NOT dao.jing src.

## Verification (scoped only)

1. `clojure -M:test -n dao.jing.hash-registry-contract-test` (confirm exact ns from the file)
   → 0F/0E on jupiter.
2. Unchanged-evidence on the php side: `clojure -M:test -n yang.php.antlr.siteset-test`
   (confirm ns) → same counts as master pre-fix.
3. Foundation: `bb test:sub:clj dao.stream` green.

## Deliverables

Branch `fix/dlint-siteset-conform` from current origin/master; findings
`collab/exception-track/1794634500000-engineer-dlint-siteset-conform.codex-gpt-6.1-terra.findings.md`
(root cause of the lint hit, diff summary, all rc/Ran lines, head sha). Never merge to master;
rebase before push if master moves.
