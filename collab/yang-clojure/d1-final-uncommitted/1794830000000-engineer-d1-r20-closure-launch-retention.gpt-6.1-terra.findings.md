# D1 r20 — resolved jar bytes retained in a scratch bundle

Worktree mirror only; no commit. **Partial, below the requested items 1–3 checkpoint.** This captures actual observed binary artifacts, not a proven executable closure or a reconstruction package. There is no D1 verification, historical runtime attestation, or change to admissions, roots, events or outcomes. Existing bounds and row-lane restrictions remain.

## Jar-byte checkpoint

The r19 observational inventory references 154 ordered classpath entries: 10 directories and 144 regular files, all named `.jar`. From the already captured r18 tar objects, each archive was SHA-256 checked against the r19 inventory before its `content` regular file was read. The 144 uncompressed jar byte strings total 67,797,111 bytes. Their classpath indices, observed original paths (descriptive only), archive SHA-256, byte SHA-256 and sizes are recorded in `test/yang/correspondence_r20_jar_inventory.edn` (SHA-256 `67d3e617c7540d2682c1905363585b86657350e93b5b07b382b59607176a6377`). Names `jars/NNN.jar` preserve the original classpath indices including directory gaps.

Using the r15 `yang.correspondence-dependency/store-bundle!`, these exact bytes were stored in the file-backed Jing store `target/d1-r20-jar-store`, bundle address `:segment/blake3-b8334907babdabd8a99e47f023eec7f5059502ecf80d23a13e260a9e492324d0`. The store occupies approximately 97 MiB. After the writer exited, a separate Clojure process reopened it and `fetch-bundle!` retrieved all 144 objects; their ordered names and SHA-256 values matched the receipt. The helper is `test/yang/correspondence_jar_bundle.clj`; the verification command is:

```
clojure -M test/yang/correspondence_jar_bundle.clj verify target/d1-r20-jar-store test/yang/correspondence_r20_jar_inventory.edn target/d1-r20-jars
```

The source directory argument is unused by `verify`; `store` reads `NNN.jar` from that directory and checks each receipt digest before writing. `target/d1-r20-jars` is a scratch extraction, not an independent retained source. The receipt is a byte inventory, not an execution manifest. The store is under `target/`, has no durable retention owner or availability promise, and is not the r7 proposed checked-in artifact. No claim is made that every jar is needed, that directories and native dependencies are complete, or that these are the historical producer's loaded bytes. The observed classpath is the r18 snapshot and can differ from the current worktree.

## Still required

1. Identify and retain the exact source/resource and indispensable precompiled/native inputs, resolve directory entries and ordering, and establish an execution closure by isolated reconstruction. The jar bundle alone does not complete item 1.
2. Specify and validate the actual build/load and launch configuration, including entrypoint, ordered classpath/module path, JVM options, working-directory mapping, properties, environment, locale/encoding, initialization, pipeline and contracts. No launch manifest was produced.
3. Select a retention owner and durable discovery/location, retain the complete addressed package, and verify availability after process exit using scratch-copy-before-open. The existing scratch store is not that retention.
4. Substantiate the direct path's expansion bypass from the pinned composition and checked execution, or retain the applicable expansion context and events. Historical uncertainty remains.
5. Fetch and verify the package, launch a fresh isolated process with compiler code from that package, reconstruct assembly and compilation and compare the entire claimed chain. The producer-side pilot and jar reopen are not this test. Later reconstruction cannot invent historical admission or expansion facts. The subsequent corpus, independent verification, publication and bounded execution obligations remain outstanding.

The third architect ruling and available findings/declarations were consulted. The earlier ruling filenames and continuation-4 remain unavailable under the supplied names as reported in r17–r19; no substitute ruling is inferred.

## Validation

Separate-process bundle reopen verified all 144 entries and their ordered SHA-256 values. clj-kondo on the new helper reported 0 errors and 0 warnings. `git diff --check` passed before this findings file; the frozen v1 `slice9_site_tables.edn` SHA-256 remains `e435ac4072ba0a3c278fbe91918d22184b43f0a8d6c1e397c09f68dd629beb75`. No full oracle suite, tagged lane, corpus reconstruction, publication or commit was run.
