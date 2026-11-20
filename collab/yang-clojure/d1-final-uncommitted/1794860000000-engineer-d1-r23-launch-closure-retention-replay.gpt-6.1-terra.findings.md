# D1 r23 — expansion guard covers oracle initialization

Worktree mirror; no commit. **Partial, below the items 1–3 checkpoint.** The requested launch configuration, complete dependency closure, durable reconstruction package, package-pinned expansion determination, and isolated replay remain unfinished. No D1 verification or historical runtime attestation is claimed. Existing admissions, outcomes, roots, events, bounds and row-lane restrictions remain unchanged.

## Local probe increment

`test/yang/correspondence_launch_probe.py` now loads `clojure.test` and `yin.vm.macro`, arms `expand` and `expand-batch` to throw, and only then requires `yang.latte-oracle-test` and runs `test-d1-amended-replay-closed-store`. This extends the r22 guard over oracle namespace initialization as well as the test itself. The macro namespace must be initialized before its Vars can be redefined; that initialization is not guarded. Other possible expansion paths are not intercepted. The probe is not pinned to a package and cannot establish historical expansion behavior.

The same probe removes classpath directories 5–7 and copies jars 76–77 without their two observed native entries, retaining all other classpath entries and order. On this machine it passed **1 test, 6 assertions, 0 failures, 0 errors**, including with the guard active during oracle loading. It still resolves source and dependencies from the checkout and local caches, runs installed `/usr/bin/java`, and uses the checkout working directory. It neither proves complete closure nor determines necessity for a future package entrypoint or broader fixture coverage. Script SHA-256: `28391a753573a74a64feddf2ec07d706460e8c08621ece2dda11d3f7f5f41ae9b7991`.

## Remaining work

The r19 runtime object, r20 jar bundle, and r21 directory bundle remain scratch observations, not a durable package. The existing manifest is a draft inventory, not an enforced launch plan. Specify and enforce the exact runtime, package-relative ordered classpath and resource resolution, module path, working directory, JVM options, properties, environment, locale/encoding, initialization, pipeline and supported platform. Establish the source/resource and binary closure for that actual entrypoint, including refusal of undeclared inputs. Retain and identify the package with an owner and discovery mechanism; verify transitive availability after process exit on a scratch copy. The local exclusions above are evidence for the tested path, not a complete closure determination.

Then use the pinned composition and checked execution path to determine expansion applicability, or capture the applicable context and events. Fetch and verify the retained package and run a fresh process resolving compiler code from it, not the invoking checkout; reconstruct adaptation and assembly through compilation and stamping and compare all claimed outputs. Preserve the distinction between later reconstruction and historical provenance, and do not invent admissions or events. Independent classification, image derivation, trace and bounded-run/publication obligations also remain.

The third architect ruling, available prior findings and declarations were consulted. The two earlier ruling filenames and continuation-4 remain unavailable under the supplied names, as reported in earlier findings; no substitute ruling is inferred.

## Validation

The local probe passed as above. Python byte compilation passed with its cache in `/private/tmp`. clj-kondo on the correspondence manifest/dependency files, manifest test and oracle test reported 0 errors and 0 warnings. `git diff --check` passed. Frozen `slice9_site_tables.edn` SHA-256 remains `e435ac4072ba0a3c278fbe91918d22184b43f0a8d6c1e397c09f68dd629beb75`. No full oracle suite, tagged lane, corpus replay, publication or commit was run.
