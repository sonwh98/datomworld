Completed-GMT: 2026-10-01 12:50:06 GMT
Completed-Local: 2026-10-01 19:50:06 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a0f782-a3c1-7173-9857-646af3b75f30


- **P1 | LICENSE-EXCEPTION:16 | Evidence:** “datom.world, and any work based on it” includes the combined work on the interpretation that linking creates a derivative work. Requiring that entire work to be GPL-2.0-only conflicts with the intended permission to retain EPL/Apache terms. **Concrete fix:** Explicitly waive GPLv2’s whole-work licensing requirement for the permitted combination while requiring datom.world code and modifications to that code to remain GPL-2.0-only. Distinguish those modifications from the combination. The [Classpath exception](https://spdx.org/licenses/Classpath-exception-2.0.html) expressly addresses distribution terms for the resulting executable.

- **P2 | LICENSE-EXCEPTION:17 | Evidence:** “including its corresponding source” does not explain whether GPLv2 §3’s requirement for source of **all executable modules** remains applicable to independent modules. It also appears to require accompanying source, narrowing §3’s written-offer alternatives. This matters particularly for binary-only Apache-licensed dependencies. **Concrete fix:** Preserve GPLv2 §3 compliance options for datom.world and explicitly state whether independent-module source obligations arise solely under those modules’ licences.

- **P2 | package.json:28 | Evidence:** The pointer reaches only generic GPL text, missing both the project’s version-only declaration and additional permission. **Concrete fix:** Use `"SEE LICENSE IN LICENSE-EXCEPTION"`; that file already identifies GPL-2.0-only and points to `LICENSE`. Preserve `LICENSE` byte-for-byte and distribute both files. Naming two files after the prefix is not npm’s documented single-filename convention. The fallback itself is valid; `WITH LicenseRef-…` is invalid under [SPDX 2.3](https://spdx.github.io/spdx-spec/v2.3/SPDX-license-expressions/). See [npm’s licence-field documentation](https://docs.npmjs.com/cli/v11/configuring-npm/package-json/#license).

- **P1 | project.clj:4 | Evidence:** Existing metadata still offers `EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0`, directly contradicting the new version-only grant. **Concrete fix:** Obtain scope authorization to align the name and URL with GPL-2.0-only plus this custom exception before distribution. This pre-existing conflict was omitted from the writer’s caveats.

- **P2 | package-lock.json:10 | Evidence:** Root-package metadata still says `ISC`. **Concrete fix:** Include an authorized root-licence metadata update matching `package.json`, preserving dependency licences and versions.

The draft expressly excludes later GPL versions and provides no apparent permission to relicense datom.world code under EPL/Apache or link proprietary-only modules. The optional exception-removal wording follows Classpath’s established pattern. I found no separate §6 violation, but the writer’s categorical assurance should be qualified until the combination and source ambiguities above are resolved.

The caveats also need qualification: conflicting historical ISC/GPL metadata creates uncertainty about what was granted; it does not conclusively establish an ISC grant for every published file. Sole commit authorship does not establish ownership of incorporated third-party material. An explicit inbound policy is sensible, but absence of a CLA does not automatically mean every contribution arrives without the exception.

The Datomic caveat is supported by the [vendor’s Apache-2.0 statement](https://www.datomic.com/on-prem-eula.html); verify the pinned artifact’s notices. A dependency audit remains necessary, including transitives, local SNAPSHOT dependencies, and generated/vendored code. MIT/BSD dependencies generally need no linking exception; LGPL compatibility depends on version and compliance.

Read-only review completed; no files changed. The local GPL digest matches the supplied digest. Independent GNU retrieval timed out, so fresh byte-equivalence remains unverified. These are drafting risks for owner/counsel review.

Verdict: REQUEST CHANGES  
Sign-off: WITHHELD
