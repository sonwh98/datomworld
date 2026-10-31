Created-GMT: 2026-10-20 09:49:25 GMT
Created-Local: 2026-10-20 16:49:25 +07
Coding-Agent: claude
Session-ID: 8f45af4f-0557-4cfa-a375-78525ee6a40e
# Findings: J0a - declare and check the Debian apt prerequisites
Role: Stream & Network Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-20 16:49:25 +07 | Status: done (macOS arm64; the Debian path is shown only through the test seam) | Rationale: owner routing "opus-5.5 for implementation, cmd for review"; owner "yes to 1, 2, 3 and 4 as recommended"

- Worktree: `/Users/sto/workspace/datomworld/.claude/worktrees/jolt-j0a`, branch `jolt-j0a` at HEAD `9f1f9144`. Changes are uncommitted and nothing is staged.
- Logs: `/Users/sto/workspace/datomworld/.claude/worktrees/jolt-j0a/build/j0a-logs/ospkg-*.log`.

## Item -> change

| item | change |
|---|---|
| (1) declaration | `jolt-toolchain.edn` gains `:os-packages`. `:debian :required` maps each apt package to the step that needs it; `:debian :unverified` lists candidates that are not checked; `:macos {:manager :homebrew :enforced false}`. Details below. Chez and CMake stay mise-provided. |
| (2) bootstrap check | `src/dev/jolt_bootstrap.clj`: `check-os-packages` is now the **first** step of `-main`, before `check-chez`/`check-cmake`. Those call `mise exec <tool>@<pin>`, which can trigger a mise install/build, so the package check runs before anything mise does. Details below. |
| (2) test seam | `JOLT_BOOTSTRAP_TEST_DEBIAN_INSTALLED=pkg,pkg,…` forces the Debian path and treats exactly those packages as installed (no `dpkg-query`). It prints `[test seam]`. |
| (3) negative/positive logs | Recorded below; warm `bb bootstrap:jolt` and `bb smoke:jolt` both exit 0 on this Mac. |
| (4) design doc | `/Users/sto/workspace/datomworld/docs/design/yin.repl-jolt-implementation.md`, edited in place with nothing removed. Details below. |

**(1) The declaration in `jolt-toolchain.edn`:**

`:required`. Each entry carries a comment on the evidence:

| package | step | evidence |
|---|---|---|
| `libncurses-dev` | mise Chez source build (link) | **verified on shiva** ("cannot find -lncurses" until installed; orchestrator-log J0b-on-shiva entry) |
| `gcc`, `make` | the same Chez source build compiles and links C | the failing step was its link. They were present on shiva, so they are not independently shown missing |
| `git` | `bootstrap:jolt` clone and verification | the code |

`:unverified` (not checked):

| package | step | why unverified |
|---|---|---|
| `g++`, `unzip` | J0c's `bb build:jolt-native` (on `jolt-j0c`) | shown on macOS only, not on Debian |
| `libssl3` | §6.3 libcrypto (J1) | package name per release |

I did not declare `curl`: no step in J0a or J0c uses it (jolt's deps resolve without curl, and J0c fetches through the babashka HTTP client).

**(2) What the bootstrap check does:**
- **Debian detection:** `/etc/os-release` `ID` or `ID_LIKE` contains the word `debian`.
- **Check:** each `:required` package goes through `dpkg-query -W -f=${Status}`, which must answer `install ok installed`.
- **On failure:** `missing Debian packages: <names>`, the step for each, and `install them with: sudo apt-get install -y <names>`, then exit 1.
- **It never runs sudo or apt.**
- **Other hosts:** one line, `os-packages: not a Debian-family host; package check skipped`.

**(4) The design-doc edits:**
1. A dated "Change note 2026-10-20, fourth" after the third note, quoting both owner sentences.
2. **§6.1:** a new paragraph on host OS packages (Debian family + apt, `:os-packages` declared, checked by bootstrap before mise, never runs sudo/apt, macOS Homebrew assumed and unenforced, Chez/CMake still mise), plus a bullet in the "`jolt-toolchain.edn` records" list.
3. **§6.3:** the Linux bullet now says `libcrypto.so.3` is installed with apt on the Debian family and declared in `:os-packages`, and that the path/version check is retained (an apt upgrade that moves or changes it fails until requalified).
4. **§13.1:** a new row "Linux packages (2026-10-20)" with the owner's words and the recorded meaning. Every grid row is still 169 characters wide.

## Evidence (commands run in the worktree, foreground)

| command | exit | log | what it shows |
|---|---|---|---|
| `JOLT_BOOTSTRAP_TEST_DEBIAN_INSTALLED=git,gcc,make bb bootstrap:jolt` | **1** | `ospkg-neg-missing.log` | `missing Debian packages: libncurses-dev` … `sudo apt-get install -y libncurses-dev`. The Chez/mise line never prints, so the failure is before mise. |
| `JOLT_BOOTSTRAP_TEST_DEBIAN_INSTALLED=git,make bb bootstrap:jolt` | **1** | `ospkg-neg-missing2.log` | Two missing packages, one command: `sudo apt-get install -y gcc libncurses-dev` |
| `JOLT_BOOTSTRAP_TEST_DEBIAN_INSTALLED=git,gcc,make,libncurses-dev bb bootstrap:jolt` | **0** | `ospkg-pos-all.log` | `os-packages: all 4 required Debian packages installed: gcc git libncurses-dev make`, then the normal bootstrap (Chez 10.4.1, CMake 4.4.4, jolt 96d7b866 + 7 submodules, smoke 3) |
| `bb bootstrap:jolt` (warm, real macOS path) | **0** | `ospkg-warm-macos.log` | `os-packages: not a Debian-family host; package check skipped`, then the normal bootstrap |
| `bb smoke:jolt` | **0** | `ospkg-smoke.log` | `SUMMARY ok` |
| `bb /tmp/osrel_test.clj` (the same `/etc/os-release` parse expressions over sample texts) | 0 | `ospkg-osrelease-parse.log` | Ubuntu 24.04 (`ID=ubuntu`, `ID_LIKE=debian`) true; Debian 12 true; Mint (`ID_LIKE="ubuntu debian"`) true; Fedora false |
| `mise exec -- cljstyle check src/dev/jolt_bootstrap.clj jolt-toolchain.edn` | 0 | n/a | clean |

## NOT done or NOT verified
- **The real Debian path was not run.** The actual `/etc/os-release` read and `dpkg-query` calls on a Debian host, and the exact `dpkg-query` status string on Ubuntu 24.04, are untested by me; the orchestrator's shiva run covers them. I checked the parsing logic only against sample texts, through a copy of the expressions rather than the function itself, which reads the fixed path.
- `gcc`, `make` and `git` are declared from the steps that use them. None was shown missing on a clean Debian host.
- `:unverified` packages are not checked by design, and the libcrypto apt package name and version check is J1 work.
- I did not decide whether to check `build-essential` instead of `gcc`/`make` (`build-essential` would add `g++`, `dpkg-dev` and others not shown to be needed).
- **macOS:** nothing is enforced, as decided; Homebrew `openssl@3` stays §6.3's later gate.

## Files changed
- `/Users/sto/workspace/datomworld/.claude/worktrees/jolt-j0a/jolt-toolchain.edn`
- `/Users/sto/workspace/datomworld/.claude/worktrees/jolt-j0a/src/dev/jolt_bootstrap.clj`
- `/Users/sto/workspace/datomworld/docs/design/yin.repl-jolt-implementation.md` (untracked in the main tree; edited in place)
- New untracked logs: `/Users/sto/workspace/datomworld/.claude/worktrees/jolt-j0a/build/j0a-logs/ospkg-*.log`

Report: `/Users/sto/workspace/datomworld/collab/1792489765000-engineer-jolt-j0a-os-packages.claude-opus-5-5.findings.md`
