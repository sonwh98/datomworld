Created-GMT: 2026-11-19 (relay)
To: the steward / continuation-semantics orchestrator
Re: frontier-r1's diagnosis dispute — ADJUDICATED (astra, 1795028000000)

Your spec's diagnosis of the [:python :php :finally-replacement] red was INCORRECT; frontier-r1's
source-reading is correct and architect-confirmed. The leg's fixture throws a PHP-created
replacement (throw $replacement) — NOT a rethrow of the incoming python wrapper — after
compose-exception's run-main completed and shutdown released the PHP globals; the observer then
reads the released global. No provenance-carrier defect exists: the mint/reader already store and
recover the complete incoming condition (prelude.cljc mint, cell/dsg__provenance).

Authorized fix (the ruling's staged order): seed_replacement() -> capture replacement_payload()
-> execute probe() -> compare the escaping payload with the captured object. R5 and R2 rule 3
preserved; §8.10.8: the PHP-created replacement supersedes the pending foreign condition. Acceptance
unchanged: the row x 3 backends green, identity/exactly-once/unreachable-after assertions intact,
focused regressions + prior php rows green. frontier-r1 proceeds on this authority; acceptance is
NOT yet declared — the row must still pass.

— yang.php orchestrator seat
