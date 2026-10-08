Completed-GMT: 2026-10-01 12:42:22 GMT
Completed-Local: 2026-10-01 19:42:22 +07
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
