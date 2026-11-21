Created-GMT: 2026-11-17 17:58:00 GMT
Created-Local: 2026-11-18 00:58:00 ICT (+07)
Coding-Agent: glm
Session-ID: zcode-subagent-reviewer-gambit-host-lane
# Review: Gambit host lane slice (fix/jolt-gambit-host)

Read-only review of `git diff 2687ab0c` in worktree
`/Users/sto/workspace/datomworld/.claude/worktrees/jolt-gambit-host`:
`jolt-toolchain.edn` (+10), `mise.toml` (+9), `src/dev/jolt_bootstrap.clj` (+27/-3).
No commits made, no file outside this findings file written, no suite lanes run.

## Verified-correct items (no finding)

1. **check-gambit vs check-chez conventions** (src/dev/jolt_bootstrap.clj:75-90 vs
   :64-72). Same shape: `mise-bin` resolution at the exact pin, equality check
   against the reported version with the pin named in the failure, an interpreter
   smoke (`(display (+ 1 2))` -> "3", Chez's analogue is `(threaded?)` -> "#t"),
   a one-line `println` summary, returns the absolute path. Version probe differs
   correctly per tool: gsi has no `--version`; `(system-version-string)` was
   verified live to print exactly `v4.9.8` (probe: installed gsi), so the
   `(str "v" version)` comparison is correct here. `binary "bin/gsi"` matches the
   file-name-extraction convention. No `:threaded` key on :gambit is fine —
   check-gambit never reads it and :cmake also omits extra keys.
2. **jolt-toolchain.edn schema consistency** (:gambit at lines ~103-107). Mirrors
   :chez's `{:version :provider :mise :mise-tool :binary}` shape exactly;
   :cmake (:provider :mise, :mise-tool) is the same family. Nothing else
   re-pinned; :observed not extended, which matches its "recorded on the
   platforms actually run" convention. Comment style matches the :chez block.
3. **Scope.** `git status --porcelain` shows exactly the three owned tracked
   files modified plus the two untracked collab artifacts (engineer findings +
   stdout log). Nothing inside build/tools/jolt modified (verified: the clone's
   Makefile/host/ files are not in the diff). jolt-env.sh is generated and
   gitignored, as designed.
4. **JOLT_GAMBIT written and non-empty** — verified in
   build/tools/jolt-env.sh: `export JOLT_GAMBIT='<...>/mise/installs/gambit/4.9.8/bin/gsi'`.
   Note: the pinned jolt Makefile consumes GAMBIT_PREFIX, not JOLT_GAMBIT
   (Makefile:1690; JOLT_GAMBIT only appears as JOLT_GAMBITSTATICS, an unrelated
   seed-statics var), so JOLT_GAMBIT is currently a lane-level convenience, not
   a gate input — harmless, and the brief asked for it.
5. **mise.toml comment accuracy** — the claim "pre-generated C in the tag
   archive, so no gambit bootstrap step" was independently confirmed: the
   v4.9.8 GitHub tag tarball contains gsc/_gambitgsc.c, gsc/_codegen.c, etc.
   (downloaded and listed, then deleted).

## Findings

**P2-1. mise-installed Gambit is built without jolt CI's `--enable-default-runtime-options=f8,-8,t8`.**
Evidence: the plugin at the pinned SHA builds with only
`./configure --enable-single-host --prefix=...` (joeljuca/asdf-gambit@b8a901e lib/utils.bash,
`install_version`), while jolt's reference build uses
`./configure --prefix=/opt/gambit --enable-single-host --enable-default-runtime-options=f8,-8,t8`
(build/tools/jolt/.github/actions/setup-chez/action.yml:88-90). The gambitcheck/gambitkernel/gambiteval
runs in the engineer's findings pass without the defaults, so nothing currently
breaks, but the host Gambit is a different build from the CI reference; any gate
that ever comes to rely on default runtime options will behave differently on
this lane's toolchain than in jolt CI. Disposition: accept for this slice (gates
pass, plugin is the only option and is SHA-pinned); record the flag divergence in
jolt-toolchain.edn's :gambit comment so the upgrade gate knows the builds differ.

**P2-2. Fresh `mise install` now runs a ~15-20 min Gambit source build including `make check`, with no checksum pin, and is unproven on Linux.**
Evidence: plugin `install_version` runs `make check` before `make install` and
downloads `github.com/gambit/gambit/archive/v<ver>.tar.gz` with `curl -fsSL` and
no hash comparison; GitHub archive tarballs are documented as not byte-stable.
Engineer's own install took ~18 min (findings §1). Every Linux CI/nightly clone
that runs `mise install` will now pay this cost and can fail for reasons outside
the repo's control (make-check flake on the runner, archive regeneration,
missing build deps beyond the already-declared gcc/make). The plugin is also a
small community plugin never exercised on Debian. Disposition: orchestrator must
run one Linux `mise install gambit` (with a timeout) before landing; if make
check is too heavy, a forked plugin dropping it is the fallback, not a blocker now.

**P2-3. Gambit is now FATAL in bootstrap on every host — accepted scope, but it compounds P2-2.**
Evidence: src/dev/jolt_bootstrap.clj:345 calls `check-gambit` unconditionally and
it calls `fail!` on any mismatch (:86-88); mise-bin also fails if
`mise exec gambit@4.9.8 -- which gsi` resolves to nothing (:53-61). A host whose
gambit build fails now cannot bootstrap at all — including hosts that only want
the Chez/JVM lane. This is within the brief ("decide, and record why") and the
engineer's fail-closed rationale matches jolt's own JOLT_REQUIRE_GAMBIT
philosophy (Makefile:1694-1703), so no change requested; but combined with P2-2
the Linux nightly's `bb bootstrap:jolt` now depends on a 20-minute untested
source build. Disposition: keep fatal; gate the landing on the P2-2 Linux probe.

**P3-1. The "must be last table" constraint on `[plugins]` is not recorded in mise.toml.**
Evidence: mise.toml:43-44 has `[plugins]` last; the engineer's findings §backend
documents that mid-file placement broke mise's TOML validation of the flutter
map ("invalid type: map, expected a string"). TOML itself is table-ordered, so a
future edit that inserts a table after `[plugins]` will break mise parsing on
every host with no hint in the file. Disposition: one comment line above
`[plugins]` noting it must remain the final table.

**P3-2. `[plugins]` is mise's legacy asdf-bridge form; newer mise versions may deprecate it.**
Evidence: mise --version reports 2026.2.19 with 2026.11.9 available; the plugin
form pins `gambit` to the asdf plugin, not a mise backend. Behavior today is
verified working. Disposition: note only; revisit if a mise upgrade breaks
resolution (the failure mode is `mise-bin`'s clear "mise has no gambit@4.9.8"
message).

**P3-3. A failed gsi probe reports the generic jolt-clone remedy.**
Evidence: `check-gambit` runs its probes through `sh` (src/dev/jolt_bootstrap.clj:84-85);
`sh`'s failure path (:48-49) appends "Remedy: git -C build/tools/jolt checkout --
. && bb bootstrap:jolt", which is irrelevant to a gambit version mismatch (the
clone is untouched). Identical to the check-chez convention, so this is
consistency, not a bug. Disposition: none required; optional cosmetic follow-up.

## Checks run (all read-only)

- `git -C <worktree> diff 2687ab0c` and `git status --porcelain` — full diff
  scoped to the three owned files; nothing in build/tools/jolt modified.
- Read src/dev/jolt_bootstrap.clj (whole file), mise.toml (whole), and the
  jolt-toolchain.edn region around :chez/:gambit/:cmake.
- Grepped jolt's pinned clone for GAMBIT_PREFIX/JOLT_GAMBIT consumers
  (Makefile:1685-1703, host/gambit/seed-statics.sh:12,55) and read
  .github/workflows/tests.yml + .github/actions/setup-chez/action.yml:70-99 for
  the CI Gambit build flags.
- Fetched joeljuca/asdf-gambit@b8a901e7af845c8b0a0ee1adc5b67687ba4c582b
  bin/install and lib/utils.bash from GitHub raw (read-only) — confirmed the
  `make check` + `--enable-single-host` + no-checksum claims.
- Live probe: ran the installed gsi `-e "(display (system-version-string))"` → `v4.9.8`.
- Live probe: downloaded the v4.9.8 tag tarball and listed its C files to
  verify the "pre-generated C" claim (then deleted it).
- `mise ls gambit` → 4.9.8 resolved from the worktree mise.toml; `mise --version` → 2026.2.19.
- Read the engineer's findings file
  collab/jolt/1794934400000-engineer-gambit-host-lane.glm-5.3-flash.findings.md
  and its brief (/Users/sto/workspace/datomworld/collab/jolt/1794934400000-engineer-gambit-host-lane.prompt.md).
- NOT run: bb test / any suite lane; nothing written except this findings file.

DONE
