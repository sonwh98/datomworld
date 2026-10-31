Completed-GMT: 2026-10-01 13:05:59 GMT
Completed-Local: 2026-10-01 20:05:59 +07
Coding-Agent: claude
Session-ID: c9c76024-1216-4bf5-a6b5-5d725ecc491e

# DRAFT: needs owner approval. LICENSE-EXCEPTION is a draft, not final licence text. This is not legal advice.

## Summary
Worktree `/Users/sto/workspace/datomworld-license`, branch `chore-license-gpl2`. Nothing is staged or committed. I changed only the three authorized files:

```
 M LICENSE            (GPL-3.0 text -> verbatim GPL-2.0 text)
 M package.json       (only the "license" field)
?? LICENSE-EXCEPTION  (new, 29 lines)
```

## 1. LICENSE: GPL-2.0 source and digest
- Fetched from: https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt (Python `urllib`, raw bytes, written unmodified)
- Size: 17984 bytes
- SHA-256: `edaef632cbb643e4e7a221717a6c441a4c1a7c918e6e4d56debc3d8739b233f6`
- This is the current gnu.org revision. The FSF line is `<https://fsf.org/>` and the URL line is `see <https://www.gnu.org/licenses/>`. It is not the older "51 Franklin Street" text that the Linux kernel's COPYING carries. The licence terms are the same in both.

## 2. LICENSE-EXCEPTION (full text)
```
datom.world Linking Exception

Copyright (C) 2021-2026 Sonny To

datom.world is licensed under the GNU General Public License, version 2
only (GPL-2.0-only), as set out in the file LICENSE. It is not licensed
under any later version of the GNU General Public License.

As a special exception to the GPL, the copyright holder gives you
permission to link or otherwise combine datom.world with independent
modules licensed under the Eclipse Public License 1.0 (EPL-1.0), the
Eclipse Public License 2.0 (EPL-2.0), or the Apache License 2.0
(Apache-2.0), and to copy and distribute the resulting combination,
provided that:

  (a) datom.world, and any work based on it, is distributed under the
      terms of GPL-2.0-only, including its corresponding source; and

  (b) each independent module is distributed under the terms of its
      own licence.

An "independent module" is a module that is not derived from or based
on datom.world. Such modules need not be licensed under the GPL, and
the terms of their licences do not become obligations under the GPL.
This exception does not change the licence of datom.world itself.

If you modify datom.world, you may extend this exception to your
version, but you are not obligated to do so. If you do not wish to do
so, delete this exception statement from your version.
```

Models used:
- **The classic GPLv2 "special exception" form:** "As a special exception, the copyright holders give permission to link ...". This form was used, for example, by the OpenSSL exceptions that GPLv2 projects such as wget added, and in the FSF FAQ's template "for a combination with a non-free library".
- **The GNU Classpath exception (GPLv2 + "linking exception"):** the definition of "independent module" ("not derived from or based on this library"), the condition that each linked module also meets its own licence's terms, and the closing "If you modify ... you may extend this exception to your version, but you are not obligated to do so. If you do not wish to do so, delete this exception statement from your version."
- **One deliberate departure from Classpath:** Classpath lets you distribute the combined executable "under terms of your choice". I dropped that. Clause (a) keeps datom.world and works based on it under GPL-2.0-only, so the exception only frees the EPL and Apache modules and does not make datom.world itself permissive. This matches the brief.

Copyright years: the first commit is 2021-11-21 and the latest is 2026-10-01, which gives 2021-2026. Commit author identities are "Sonny To", "Sonny" and "sto", all `son.c.to@gmail.com`, plus one commit by `sto@jupiter.stigmergy.systems`. I took all of these to be the owner, as the brief states.

## 3. package.json
```
  "license": "SEE LICENSE IN LICENSE"
```
Why not `GPL-2.0-only WITH LicenseRef-datomworld-linking-exception`:
- In the SPDX 2.x license-expression grammar (spec 2.3, Annex D), the operand after `WITH` must be a `license-exception-id` from the SPDX License Exceptions list (Annex A.2). `LicenseRef-` identifiers may only stand where a licence is expected, not after `WITH`, so that expression is invalid.
- SPDX 3.0 adds `AdditionRef-<idstring>` for custom exceptions. `GPL-2.0-only WITH AdditionRef-datomworld-linking-exception` would be valid there.
- npm validates `"license"` with `spdx-expression-parse`, which implements the 2.x grammar. It also explicitly accepts `SEE LICENSE IN <filename>`.
- So `SEE LICENSE IN LICENSE` is the valid choice the brief prescribes as the fallback.

Caveat: `LICENSE` holds only the GPL text. A reader of `package.json` reaches the exception only by noticing `LICENSE-EXCEPTION` next to it. If the owner wants, a later change could switch to the SPDX 3.0 `AdditionRef-` form once npm tooling accepts it. Per the brief, I made no other change.

## Checks (all run in the foreground)
1. **LICENSE byte-identical to the fetched canonical text: PASS, with a caveat.**
   - I compared LICENSE byte-for-byte (Python) with the copy fetched from gnu.org in this session (`/tmp/gpl-2.0.canonical.txt`). Result: `BYTE-IDENTICAL`, SHA-256 `edaef632...233f6`, 17984 bytes.
   - A second, independent re-fetch for the comparison failed: gnu.org timed out 3 times, and the earlier attempt to write straight into LICENSE also timed out. So the comparison is against the single successful fetch, not a fresh second download.
   - Recommended re-check once the network is up: `curl -s https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt | cmp - LICENSE`.
2. **LICENSE-EXCEPTION is ASCII-only: PASS.** Every byte is in 0x20-0x7E or is LF. The file is 29 lines.
3. **package.json parses: PASS via Python `json.load`.** The license value is `SEE LICENSE IN LICENSE`.
   - The requested `node -e JSON.parse(...)` command was refused by the session's permission gate ("requires approval"), so the node form of the check did NOT run.
   - Equivalent command for the orchestrator: `node -e "JSON.parse(require('fs').readFileSync('package.json','utf8'))"`.

## Legal caveats (not legal advice; have counsel review before any public release)
- **The relicensing only works going forward.** Copies already distributed under GPL-3.0 (and anything published with `package.json` saying ISC) stay available under those terms to whoever received them. As sole copyright holder the owner can relicense future distributions, but cannot revoke those past grants.
- **Sole authorship has to really hold.** Granting an additional permission on GPL-2.0 code requires every copyright holder to agree. Before release, confirm that no vendored or third-party-derived code is in the tree, including copied snippets, generated code from GPL-3.0 sources, and AI-assisted code with unclear provenance.
- **Future contributions** need an inbound policy, either a CLA or an explicit statement that contributions are licensed GPL-2.0-only with this exception. Without one, a contributor's code would arrive as plain GPL-2.0 and the exception would stop covering the whole work.
- **This is not literally the "Linux kernel model".** The kernel is GPL-2.0-only with the narrow `Linux-syscall-note` (user space is not a derived work). It does not have a linking exception for library dependencies. "GPL-2.0-only plus a linking exception" is closer to the GNU Classpath or GCC Runtime Library style, which is what option 1 chose. The copyleft strength on datom.world's own code matches the kernel's.
- **The exception does not by itself fix licence compatibility on the dependencies' side.** It removes the GPL-side objection. Each module's own terms (EPL-1.0 section 3 source and choice-of-law terms, Apache-2.0 NOTICE and patent terms) still apply to that module, which is what clause (b) says. The FSF's view that EPL-1.0 and Apache-2.0 are incompatible is about the GPL's restrictions, and this exception is aimed at exactly that.
- **EPL-2.0** offers GPL compatibility only when the EPL-2.0 code names GPL as a "Secondary License". Covering EPL-2.0 directly in the exception avoids relying on that.
- **Datomic peer:** check the licence of the exact artifact used. Datomic Pro binaries have been distributed under Apache-2.0 since 2023, but older peer versions had a proprietary licence that this exception does not cover.
- **Whether Clojure/JVM dynamic linking makes a "work based on" the program is legally unsettled.** The exception is drafted to work either way.
- **Other dependency licences** (for example MIT, BSD, LGPL, or GPL-3.0-only libraries) are not covered by this exception and were not audited here. GPL-3.0-only components would be incompatible with GPL-2.0-only. A dependency licence audit is recommended before release.
- **GPL-2.0 section 6 ("no further restrictions")** is not breached. The exception only adds permissions, which is the established pattern for these exceptions.

---

# Round 2: the reviewer's REQUEST CHANGES (gpt-6.1-sol) and the owner's scope decisions

**Still a DRAFT that needs owner approval. Not legal advice; have counsel review before any public release.**

Where this section conflicts with Round 1 above, this section wins. That covers the exception text, the package.json value and the caveats.

## Files changed (nothing staged or committed)
- **`LICENSE`:** unchanged since Round 1. Still the GPL-2.0 text with SHA-256 `edaef632...233f6`.
- **`LICENSE-EXCEPTION`:** rewritten; full text below.
- **`package.json`:** `"license": "SEE LICENSE IN LICENSE-EXCEPTION"`.
- **`package-lock.json`:** only the root package (`packages[""]`) `"license"` changed, to `"SEE LICENSE IN LICENSE-EXCEPTION"`. No dependency entries, versions or integrity hashes were touched; `git diff` shows exactly 1 line changed.
- **`project.clj`:** deleted from the working tree with plain `rm`. It is not staged; `git status` shows ` D project.clj`.
- **`src/cljc/yin/vm/docs/documentation_index.md`:**
  - Removed the `project.clj` bullet under "Configuration" (line 103).
  - In the file tree, removed the `project.clj # Leiningen config` line and changed `deps.edn` to the last-child glyph `└──` (lines 226-227).
- **`src/cljc/yin/vm/docs/summary.md`:** removed "Works with `lein` (project.clj) - Alternative" (line 36).
- **`src/cljc/yin/vm/docs/yin_vm_tests.md`:**
  - Lines 21 and 32: replaced `lein run -m clojure.main <file>` with `clj <file>`. That is the same command form the file's own "Using `clj` (Recommended)" section already uses.
  - Deleted the whole "### Using Leiningen (Alternative)" subsection (lines 108-114). Removing only lines 112-113 would have left an empty heading and an empty code block.

## Fixes
1. **P1, the whole-work requirement:**
   - A new paragraph says, as Classpath does, that linking may make a combined work which the GPL would otherwise require to be licensed as a whole under the GPL.
   - The grant then permits distributing the combination "without licensing the combination as a whole under the GPL".
   - Clause (a) limits the GPL-2.0-only requirement to "datom.world and any modified version of datom.world (but not the independent modules)". That separates modifications of datom.world from the combination.
   - Classpath's "under terms of your choice" is deliberately not used, so datom.world code cannot be relicensed through the combination.
2. **P2, the source requirement:**
   - Clause (a) now requires datom.world's source "by any of the options in section 3 of the GPL". This keeps the written-offer option (3b) and the noncommercial pass-along option (3c).
   - A separate sentence states that any source obligation for an independent module "arises solely under that module's own licence, not under the GPL". This addresses binary-only Apache-2.0 modules and section 3's "all modules it contains" language.
3. **P2, licence pointer:**
   - `package.json` and the `package-lock.json` root now point to `LICENSE-EXCEPTION`.
   - That file names GPL-2.0-only, points to `LICENSE`, and carries the additional permission.
   - It is a single filename, which matches npm's documented `SEE LICENSE IN <filename>` convention.
   - `WITH LicenseRef-...` is still invalid under SPDX 2.3, as in Round 1.
4. **P1, `project.clj`:** deleted on the owner's decision ("I'm not using project.clj"). This removes the conflicting `EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0` metadata. The docs that pointed at it or at lein are fixed as listed above.

## LICENSE-EXCEPTION (FULL final text, 35 lines)
```
datom.world Linking Exception

Copyright (C) 2021-2026 Sonny To

datom.world is licensed under the GNU General Public License, version 2
only (GPL-2.0-only), as set out in the file LICENSE, with the additional
permission below. It is not licensed under any later version of the GPL.

Linking datom.world statically or dynamically with other modules may
make a combined work based on datom.world, which the GPL would otherwise
require to be licensed as a whole under the GPL.

As a special exception, the copyright holder gives you permission to
link or otherwise combine datom.world with independent modules licensed
under the Eclipse Public License 1.0 (EPL-1.0), the Eclipse Public
License 2.0 (EPL-2.0), or the Apache License 2.0 (Apache-2.0), and to
copy and distribute the resulting combination without licensing the
combination as a whole under the GPL, provided that:

  (a) datom.world and any modified version of datom.world (but not the
      independent modules) are distributed under GPL-2.0-only, and you
      comply with the GPL for that code, including providing its
      source code by any of the options in section 3 of the GPL; and

  (b) for each independent module, you meet the terms and conditions
      of that module's own licence.

An "independent module" is a module that is not derived from or based
on datom.world. Any obligation to provide or offer the source code of
an independent module arises solely under that module's own licence,
not under the GPL.

If you modify datom.world, you may extend this exception to your
version, but you are not obligated to do so. If you do not wish to do
so, delete this exception statement from your version.
```

## Checks (all run in the foreground)
1. **LICENSE unchanged since Round 1: PASS.** The Python SHA-256 is `edaef632cbb643e4e7a221717a6c441a4c1a7c918e6e4d56debc3d8739b233f6`, 17984 bytes. A fresh byte-comparison against gnu.org is still pending; the orchestrator will redo it before commit.
2. **LICENSE-EXCEPTION is ASCII-only: PASS.** Every byte is printable ASCII or a line feed. The file is 35 lines.
3. **package.json and package-lock.json parse: PASS via Python `json.load`.**
   - `package.json` license is `SEE LICENSE IN LICENSE-EXCEPTION`.
   - The lockfile's `packages[""].license` is `SEE LICENSE IN LICENSE-EXCEPTION`.
   - `node -e` was refused by the permission gate ("requires approval"), so the node form of the check did not run.
4. **git shows `project.clj` deleted: PASS.** The `git status --short` line is ` D project.clj`.
5. **No tracked reference to project.clj or lein outside collab/archive: PASS for this project; out-of-scope mentions remain.**
   - Command: `git grep -n -i -E 'project\.clj|lein' -- ':(top)' ':(top,exclude)collab/archive'`.
   - There are no `project.clj` references left.
   - None of the remaining hits point at a missing file or tell anyone to run lein on this project. None are in files authorized this round, so I left them:
     - `.gitignore` and `.zcodeignore` lines 9-12: `.lein-*` ignore patterns. These are harmless, and deleting them is optional cleanup the owner could authorize.
     - `docs/jolt-vs-jank.md:137,206,208`: describes the jank project's Leiningen roadmap.
     - `public/chp/blog/code-entropy-evolution.blog:366`: names the third-party tool `lein-ns-dep-graph` in a blog post.
     - The other matches are false positives from the substring (`CFBundleInfoDictionaryVersion`, `getRuleIndex`, `BLOCK "...FileInfo"`).

**Problem I did not fix:** the doc commands `clj test_add.clj` and `clj test_simple_continuation.clj`, and `./run_tests.sh`, point at root-level files that are not tracked in the repo (`git ls-files` returns nothing for them). The doc's existing "Recommended" clj section already pointed at them before this round. I kept the doc's own command form rather than invent a new target. If the orchestrator wants it, the stale "Run:" lines and sections could be removed in a separate authorized pass.

## Caveats, corrected per the reviewer (these replace the Round 1 caveats where they differ)
- **Historical metadata:**
  - The repo previously carried conflicting signals: GPL-3.0 in `LICENSE`, `ISC` in `package.json`/`package-lock.json`, and `EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0` in `project.clj`.
  - That conflict creates uncertainty about what earlier recipients were granted. It does not conclusively establish an ISC grant (or any single grant) for every file published before.
  - Relicensing governs future distributions only. It cannot revoke grants already made, whatever their scope turns out to be.
- **Third-party material:** sole commit authorship does not establish ownership of everything in the tree. Material copied, vendored, adapted or generated from other sources (including AI-assisted code of unclear provenance) keeps its own copyright and licence. It must be identified and checked for compatibility with GPL-2.0-only before the exception can be relied on for the whole work.
- **Inbound policy:** an explicit policy is recommended, either a CLA/DCO or a stated "contributions are licensed GPL-2.0-only with the datom.world Linking Exception". Without a CLA, a contribution does not automatically arrive without the exception; terms may be implied from the project's stated licence. An explicit policy removes that ambiguity.
- **Dependency and code audit:**
  - Before release, audit all transitive dependencies (Maven, npm, Dart/Flutter), local and SNAPSHOT dependencies (for example `stigmergy/wocket` from the deleted `project.clj`, if it is still used elsewhere), and generated or vendored code.
  - The exception covers only EPL-1.0, EPL-2.0 and Apache-2.0 modules.
  - MIT and BSD dependencies generally need no linking exception.
  - LGPL compatibility depends on the LGPL version and on compliance with its relinking and source terms.
  - GPL-3.0-only components are incompatible with GPL-2.0-only.
- **Datomic:** the vendor states the Datomic binaries are Apache-2.0 (https://www.datomic.com/on-prem-eula.html). Verify the notices of the pinned artifact version.
- **GPL-2.0 section 6 ("no further restrictions"):** the exception is written only to add permissions. The Round 1 assurance is now qualified: the reviewer found no separate section 6 problem, and the whole-work and source ambiguities it flagged are addressed by fixes 1 and 2 above. Counsel should confirm.
- **Kernel analogy, Clojure and JVM dynamic linking, and EPL-2.0 Secondary License:** unchanged from Round 1.
