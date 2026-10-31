Created-GMT: 2026-10-20 09:56:10 GMT
Created-Local: 2026-10-20 16:56:10 +07
Coding-Agent: cmd
Session-ID: 61966f9d-4875-4b42-9545-b0dc8a2c9891
# Review: apt prerequisites (jolt-j0a) + tracked J0b probe (jolt-j0b)
Role: Reviewer (read-only) | Model: qwen/qwen3.8-max

## Verdicts: jolt-j0a SIGNED OFF · jolt-j0b SIGNED OFF

## Findings

| id | sev | branch | file:line | evidence | correction |
|---|---|---|---|---|---|
| F1 | low | j0a | jolt-toolchain.edn:55-56 | Confirmed: `"libssl3"` is wrong for Ubuntu 24.04 - noble's t64 transition moved `libcrypto.so.3` to `libssl3t64`; `libssl3` there is `deinstall ok config-files`, which `dpkg-installed?` (jolt_bootstrap.clj:138-142, exact `install ok installed`) would correctly reject if it were ever promoted to `:required`. Harmless today (`:unverified`, never checked). | Declare the requirement as the **library**, not the package: soname `libcrypto.so.3` + the §6.3 path/version check (already retained), resolving the provider at failure time via `dpkg -S`/apt. If a package name is kept, make it per-release (`libssl3t64` on Ubuntu ≥24.04 / Debian trixie, `libssl3` otherwise). Do this when J1 promotes the entry, and fix the comment now if convenient. |
| F2 | info | j0a | jolt_bootstrap.clj:148-154 | Seam assessed: `JOLT_BOOTSTRAP_TEST_DEBIAN_INSTALLED` can force the Debian path with a fake installed set in production, but every use prints `[test seam]` (never silent), the default is the real dpkg path, and it fails closed on a real Debian host when unset. Acceptable for the probe era. | Drop or flag-gate the seam before J8 packaging. |
| F3 | info | j0b | jolt_udp_probe.sh:17-21 | Spike-layout fallback (`build/jolt-spike/jolt/bin/jolt`) is dead in a tracked clean checkout; if neither layout exists the shell fails with "not found" (nonzero, not silent, but cryptic). | Drop the fallback in the tracked script; fail with "run bb bootstrap:jolt first". Not a blocker. |

## Checks

**(1) jolt-j0a.** Ordering: `check-os-packages` is the first step of `-main` (:171-172), before any `mise exec` ✓. No sudo/apt execution: both appear only inside the `fail!` message text; the only external command is read-only `dpkg-query` ✓. Detection (debian-family?, :124-135): word-split of `ID`/`ID_LIKE` from `/etc/os-release`, quoted or unquoted - correct for Debian/Ubuntu/Mint, false for Fedora (author's parse log agrees); non-Debian hosts print one skip line ✓. Required set justified: `libncurses-dev` has real shiva failure evidence; `gcc`/`make` are honestly labelled as inferred from the same Chez source build (present on shiva, absence not independently shown); `git` is used by the code ✓. Design-doc amendment: change note "fourth" (:70-79) quotes both owner sentences verbatim; §6.1 (:518-529, :541-542), §6.3 (:599-603) and the §13.1 row (:1478-1481) match the owner's words, retain Q4 and the libcrypto path/version check, and remove nothing ✓. Seam-negative evidence (missing `libncurses-dev`, exact `sudo apt-get install -y` command, failure before the Chez line) matches the code paths; orchestrator's real-Debian run on shiva covers the path the author could not.

**(2) jolt-j0b.** I compared the tracked probe against the scratch original myself: same 355-line structure, item-4 region byte-identical (grep of :291-307 matches exactly), differences confined to lines 3-4 (comment + `ns jolt-udp-probe`, matching the filename convention) - consistent with the author's `3,4c3,4` diff claim; script differs only in the probe filename (:58). No logic touched, so my prior J0b review carries over unchanged. bb wiring (:18-21) propagates the exit code exactly like `smoke:jolt`; the script's repo-root-relative env sourcing is fine under bb (cwd = project root); clean checkout needs `bb bootstrap:jolt` first (doc'd, fails nonzero otherwise). No logs/artifacts tracked: only ` M bb.edn` + two new `src/dev` files; `build/` ignored. Orchestrator's macOS re-run: 36/36/3, exit 0, matching the reviewed probe's macOS total.

## Could not verify

- Full byte-level `diff` of the two probe files (no shell); my comparison is structural plus targeted byte-exact greps and the identical orchestrator totals.
- shiva real-Debian-path logs and the `dpkg -S libssl3t64` output (orchestrator evidence, trusted).
- Mode bits (755) on jolt_udp_probe.sh; git statuses of both worktrees beyond the reports.
