Created-GMT: 2026-11-13 11:36:09 GMT
Coding-Agent: codex
Role: Engineer
Status: partial; consumer integration and include gate remain incomplete

PHP site sets now have signed-entry, entry-datoms, discover and signed-entries helpers, following the Python site-set discovery pattern. Discovery checks the declared signer's principal as well as its signature. derive-verified checks the manifest-bound record via the existing PHP verifier, validates and hashes each input tree row, and applies the published occurrence marks with the requested safepoint profile. It returns the input/output derivation record and the published site-set address; it does not publish or serve the derived module.

Verified sequentially: tick-fixtures-test 1 test / 8 assertions green; siteset-test 5 / 21 green; yang.safepoint-test 9 / 20 green. clj-kondo on both touched code/test files: 0 errors, 0 warnings. include-handoff-test completed with 3 failures / 0 errors: semantic, stack and register each returned `L` instead of `LT`.

Next steps: integrate discovery from signed linker index datoms into a PHP linker stage (see yang.python.antlr.siteset/stage and interpreter composition); publish/pin a module exporting the tick hook and derive the dependency closure bottom-up, rebind SCC alias requirements to derived bundle addresses, and materialize derivation records. Ensure the derivation record pins the intended published full site-set address as well as the selected sites when these differ. Apply the selected profile to the entry production paths and exercise the linked harness through this stage. Current helpers are not wired into any execution path; do not claim production ticks or include acceptance. Statement selection and registry fixture work remains part I.
