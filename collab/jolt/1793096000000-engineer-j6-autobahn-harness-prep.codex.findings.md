Created-GMT: 2026-10-27 09:30:37 GMT
Created-Local: 2026-10-27 16:30:37 +07
Coding-Agent: codex
Role: Engineer (jolt lane, J6 harness preparation)
Status: prep smoke PASS on Linux; committed/pushed, not merged; J6 policy proposal pending owner ruling

# J6 Autobahn harness preparation

## 1. Delivery and scope

Branch: `j6-autobahn-prep`, based on master at
`823ebe469266a719c9681be9fb5da2ef7e353e33`.
Final commit: `c03dfcbabdff0a2d93f71eeca49e8162b84a922f`.
Pushed to `origin/j6-autobahn-prep`; merging remains the orchestrator's.
Worktree: `/Users/sto/workspace/datomworld/.claude/worktrees/j6-autobahn-prep`.

Commits:

- `25dcf0385`: `test(jolt): prepare pinned Autobahn harness and proposed J6 case gate`
- `2fb9fad3d`: `fix(jolt): address filtered Autobahn case indexes and reject malformed evidence`
- `c03dfcbab`: `fix(jolt): restrict Autobahn server publication to loopback`

The harness lives in **`tools/jolt-autobahn/`**, separate from both native production libraries and J5. Added files: README, `pin.json`, full `cases.json`, `triage.md`, `exceptions.json`, `export_cases.py`, `harness.py`, `test_harness.py`, `stub.clj`, attribution/license and a local pycache ignore. The only existing file changed is `docs/design/yin.repl-jolt-implementation.md`: the J6 row points to an explicitly owner-pending amendment, followed by its short proposal text. No production adapter, source namespace, bb task, codec, parser, frontend, peer fixture or J5 file was changed.

The branch is clean. Master advanced during this task; use `git diff master...j6-autobahn-prep` (merge-base diff), not a two-tip comparison that incorrectly attributes other seats' changes to this branch.

Evidence labels: **V** = executed/observed here; **R** = source read; **P** = proposed, not owner-approved.

## 2. Suite pin and packaging

**V:** official `crossbario/autobahn-testsuite:25.10.1`, locked as:

```
crossbario/autobahn-testsuite@sha256:519915fb568b04c9383f70a1c405ae3ff44ab9e35835b085239c258b6fac3074
```

Platform: `linux/amd64`. The image's source label `6ed6f43` resolves to upstream commit `6ed6f439dc7ed0d7432fe2cf7481b110905ecc5c`; build label 2025-10-07. Version means the image/package version, not an asserted GitHub Release. Runtime inspection found **Python 2.7.18 / PyPy 7.3.20, OpenSSL 3.0.12, Autobahn 0.10.9**. Do not substitute versions inferred from old Dockerfiles.

**R:** [upstream](https://github.com/crossbario/autobahn-testsuite) is not dead: inspected master `ff462c0a7ccb6512ad8442d09dab371a3c40a843` has a 2026-10-11 merge. It deliberately preserves a Python 2/PyPy conformance runtime. Recommendation: isolate and pin the official image; do not take on a host Python 2 venv, unqualified fork, or a new handwritten replacement for the 517-case oracle. The Python 3 host driver uses only the standard library.

**V:** `export_cases.py` ran inside the immutable image; `harness.py verify-catalogue` compared all 517 IDs, indexes, categories, descriptions and expectations against the committed catalogue. Both policy files are SHA-256 locked:

- cases: `0731e59a2af755c91c725c0e4cd88edb2b2853f025cacbb21fa683c6b1f86eb1`
- exceptions: `75e7f3418127c7845bbfc842026861020143c0eacf1dcac13269129678425c52`

Upstream descriptions/expectations retain Apache-2.0 attribution and license. Triage is our proposed policy, not upstream endorsement.

## 3. Roles, adapter seam and case triage

| Native testee | Pinned suite | Launch rule |
|---|---|---|
| jolt server | `wstest -m fuzzingclient -s /config/spec.json` | Suite reaches loopback native server using host networking. |
| jolt client | `wstest -m fuzzingserver -s /config/spec.json` | Suite server uses bridge networking with **127.0.0.1-only** Docker port publication; client runs selected cases then awaits `/updateReports`. |

The latter distinction matters: **R**, upstream `listenWS(factory, sslContext)` does not restrict its listener to the host portion of the configured URL. The final runner confines its published port explicitly. It does not touch other containers.

The future adapter launcher receives `J6_ROLE`, `J6_PORT`, `J6_URL`, `J6_AGENT`, `J6_CASE_INDEXES`, `J6_MODE`, `J6_FRAME_LIMIT` and `J6_MESSAGE_LIMIT`. `/runCase?case=N` uses **filtered `specCases` indexes**, not catalogue-wide indexes. The first live client smoke exposed this error; it was fixed, and both directions passed subsequently. The final runner refuses stale output directories, collects exact configuration/argv/commit/runtime/policy metadata, cleans its own process groups/container, and emits JSON plus HTML.

**P:** the production conformance testee must expose an echo hook at the **same native WebSocket parser/framing/UTF-8/close/socket-poller seam** used by production, before CBOR admission. This is test plumbing, not a replacement WS implementation. Autobahn arbitrary text/binary echo cannot run through the application CBOR decoder. CBOR-only production operation does not make RFC text framing N/A. Application semantics and peer gates remain separate.

The committed **517-row `triage.md`** names every case and its reason; `cases.json` is machine-readable policy:

| Groups | Cases | Proposed classification |
|---|---:|---|
| 1 / 2 / 3 / 4 / 5 / 6 | 16 / 11 / 7 / 10 / 20 / 145 | All must pass |
| 7 | 37 | 35 must pass; two constrained informational cases |
| 9 | 54 | Must pass under declared test bounds; no speed waiver |
| 10.1.1 | 1 | Supplemental inbound fragmentation must-pass |
| 12 / 13 | 90 / 126 | N/A only while compression is not negotiated |

Total **299 must-pass + 2 exception-listed + 216 N/A**; **301 execute per role**. The two exceptions are **7.13.1 (close 5000)** and **7.13.2 (close 65535)**. Upstream always labels these INFORMATIONAL. Four exact case/role rows require both informational labels **and actual `remoteCloseCode=1002`**, with no timeout flags. No FAILED waiver is permitted. The out-of-range/protocol-error basis is [RFC 6455 §§7.4/11.7](https://www.rfc-editor.org/rfc/rfc6455.html#section-11.7). This is a proposed stricter response assertion, not acceptance of upstream's broad “undefined” language.

The proposed full-gate profile declares 64 MiB frame/message limits; real launchers must apply/report them. The smoke stub has its own much smaller fixed limits. Existing production oversized-frame refusal tests still apply. All selected ordinary results must have `behavior=OK` and `behaviorClose=OK`. Missing/duplicate/unexpected cases, malformed reports, wrong agent/role, missing fields, unlisted non-strict outcomes, timeouts and subprocess errors fail. No percentage threshold or runtime-created waiver exists.

## 4. Executed prep acceptance

**V:** dedicated scratch clone, `jupiter.stigmergy.systems:/home/sto/ci/datomworld-j6-autobahn-prep`, from the pushed branch, through tmux per `build-n-test.md`. No edits to the remote shared workspace. The task expressly made jupiter available for this prep; these results do not replace shiva's lane-qualification role.

Environment: Linux x86_64, kernel `7.0.0-38-generic`, glibc 2.39; Docker **29.9.2**; host Python **3.12.3**. `bb bootstrap:jolt` verified the repository pin `b389f1a7fb553f63a3494d71f8fe3251790bf4d3`, its seven submodules, Chez **10.4.1**, CMake **4.4.4**, required OS packages, and `(+ 1 2) => 3`.

The **jolt-host** stub is not Python, JVM, Node or Dart. It deliberately uses the jolt.socket shim for tiny test-only echo/pong/close handling. That shim remains forbidden in the **production** J6 adapter closure. The stub has a fixed test masking key, bounded unfragmented frames, and an ASCII handshake digest with the RFC known-answer assertion. Both runner and script prohibit knowingly selecting it for the full proposed gate. This code makes **no RFC 6455 or dao.stream conformance claim**.

Final runs, source commit `c03dfcbabdff0a2d93f71eeca49e8162b84a922f`:

| Check | Result |
|---|---|
| 8 evaluator/policy tests, locally and remote | PASS; includes missing/duplicate/extra/malformed/wrong-agent/wrong-role/non-strict/timeout/refused-exception evidence |
| jolt server vs pinned fuzzing client | **4/4**, exit 0, 10.207 s |
| jolt client vs pinned fuzzing server, restricted port mapping | **4/4**, exit 0, 9.741 s |
| All 517 pinned upstream catalogue records | MATCH |
| Both JSON + HTML summaries, raw upstream case reports | Produced; four case JSONs plus index per role |
| Relative links, triage count, `git diff --check`, clean worktree | PASS |

Smoke IDs: `1.1.1`, `1.2.1`, `2.1`, `7.1.1`. Every actual result was `OK/OK`; zero missing results or report issues. Exception loading and its exact-response path are tested synthetically; no claim is made to have run the two exception cases against a real adapter.

Retained evidence under the remote clone:

- `build/j6-run-final.log`
- `build/j6-prep-server-final/{summary.json,summary.html,testee.log,wstest.log,spec.json,raw/}`
- `build/j6-prep-client-final/{summary.json,summary.html,testee.log,wstest.log,spec.json,raw/}`
- `build/autobahn-catalogue.json`, `build/autobahn-runtime.txt`

Final summary SHA-256:

- server: `198a15defe0101ec0f6c3a406bb30c01d3f17d7460bf5f09d879d6c8be3b6d98`
- client: `8f799a3ecceeed477e935e0c307d0dbe430ce640b26015881306870349b93e81`

No full adapter conformance, production peer tests, macOS Docker/emulation smoke, or full repository suite was run. This isolated harness changes no production require graph. macOS-native testee evidence is still required for J6; Docker on Linux is not a substitute. The README describes the required macOS host-network/emulation setup, not a claim that it was exercised.

## 5. Reproduction

In the feature worktree or a new remote scratch clone at the final commit:

```sh
bb bootstrap:jolt
. build/tools/jolt-env.sh
python3 -m unittest discover -s tools/jolt-autobahn -v
python3 tools/jolt-autobahn/harness.py check
python3 tools/jolt-autobahn/harness.py run --role server --mode smoke \
  --agent jolt_stub_server --out build/j6-prep-server -- \
  "$JOLT_HOME/bin/jolt" -Srepro -Sdeps '{:replace-paths [] :replace-deps {}}' \
  tools/jolt-autobahn/stub.clj
python3 tools/jolt-autobahn/harness.py run --role client --mode smoke \
  --agent jolt_stub_client --out build/j6-prep-client -- \
  "$JOLT_HOME/bin/jolt" -Srepro -Sdeps '{:replace-paths [] :replace-deps {}}' \
  tools/jolt-autobahn/stub.clj
```

Output directories must not already exist; choose new names for reruns. See `tools/jolt-autobahn/README.md` §1 for the immutable image pull/catalogue verification commands and §2 for the real-launcher contract. Raw reports remain build artifacts, not committed source.

## 6. J6 owner-ruling proposal and contract discrepancy

**P:** approve/revise the pinned 517-case policy and four exact exception rows before using them as J6 acceptance. Require native client **and** server on macOS arm64 **and** Linux x86_64: 301 selected cases each, **1,204 results**, strict threshold, all raw provenance retained. Keep every existing §9.6 gate and real jolt↔JVM/Node/Dart peer fixture. The design row amendment is explicitly a proposal, not an adopted rule. No real adapter is supplied here; the runner's `gate-proposal` mode is prepared for J6 to supply one.

**R, correction to the task's attribution:** the no-`full`, would-block/ENOBUFS → transport-error + send-failed policy is the **J7 UDP** row (`yin.repl-jolt-implementation.md:1452` at this branch). WebSocket expressly allows `:dao.stream/full` when its send buffer cannot accept a value (`dao.stream.ws.md:647–651`). J6's actual row (`:1443–1450`) requires non-waiting send and exact queued-byte drain. This prep preserves those contracts rather than silently copying UDP semantics into WS. Any intended WS policy change requires a separate owner ruling. The mixed Node/OS-lock limitation and the satisfied C0–C4 dependency were not reopened.

**Disposition: PREP SMOKE PASS (Linux, both roles); branch ready for review/merge by the orchestrator. J6 conformance and the proposed case/exception policy remain unapproved/unproven.**
