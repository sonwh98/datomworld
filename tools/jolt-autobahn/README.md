# J6 Autobahn harness preparation

Status: executable harness preparation; **not a WebSocket adapter and not a
conformance claim**. Gate policy below is an owner-review proposal dated
2026-10-27. Neither this directory nor a green smoke report closes J6.

Authorities: [jolt implementation plan](../../docs/design/yin.repl-jolt-implementation.md)
§9.6/§10.1 and [dao.stream.ws](../../docs/design/dao.stream.ws.md).

## 1. Pin and packaging

`pin.json` locks official Autobahn|Testsuite **25.10.1**, Docker image
`crossbario/autobahn-testsuite@sha256:519915fb568b04c9383f70a1c405ae3ff44ab9e35835b085239c258b6fac3074`,
platform `linux/amd64`, and the SHA-256 of both policy files. The image label
identifies source commit `6ed6f439dc7ed0d7432fe2cf7481b110905ecc5c` (short label
`6ed6f43`), built 2025-10-07. The version is an image/package version, not a
claim that a GitHub Release exists. Upstream remains active: inspected master
`ff462c0a7ccb6512ad8442d09dab371a3c40a843` is dated 2026-10-11. Use the pinned
image, not master or `latest`.

[Upstream](https://github.com/crossbario/autobahn-testsuite) explicitly preserves
its Python 2/PyPy runtime for reproducible conformance. The official Docker
runtime is preferable to maintaining an unsupported host Python 2 venv. It
also avoids inventing a replacement suite or trusting an unqualified fork.
The pinned source/package, rather than a current documentation example,
defines the cases. No Python 3 port is assumed. The host driver is Python 3
standard library only. Inspection of the actual pinned image found Python
2.7.18 / PyPy 7.3.20, OpenSSL 3.0.12 and Autobahn 0.10.9; do not infer
its dependency versions from an older upstream Dockerfile. Docker and a
native jolt installation are prerequisites.
No suite code is imported into production.

Use an isolated development host: the old suite runtime is not an exposed
service. Connections are loopback only. Docker runs as the calling UID, drops
capabilities, sets no-new-privileges, and receives only a fresh report directory
and configuration. The fuzzing client uses host networking to reach the
native testee server. The fuzzing server uses a Docker bridge and an explicit
`127.0.0.1:PORT:PORT` publication: upstream `listenWS` does not restrict its
listener to the URL host. On macOS arm64, Docker Desktop
must explicitly support/enable host networking and linux/amd64 emulation;
record that runtime separately from the **native macOS arm64 testee**. A Docker
image passing on Linux does not qualify the macOS testee. If that setup is
unavailable, run the pinned suite on Linux with explicit SSH port forwarding to
the native macOS testee; document the forwarding and use a long-lived testee
launcher. Do not replace the native testee with a Linux-container testee.
The prep evidence currently qualifies the Linux runner only.

To reproduce the catalogue from the immutable image:

```sh
IMAGE=$(python3 -c 'import json; print(json.load(open("tools/jolt-autobahn/pin.json"))["image"])')
docker pull --platform linux/amd64 "$IMAGE"
docker run --rm --platform linux/amd64 --network none \
  -v "$PWD/tools/jolt-autobahn/export_cases.py:/export_cases.py:ro" \
  "$IMAGE" python /export_cases.py > build/autobahn-catalogue.json
python3 tools/jolt-autobahn/harness.py verify-catalogue build/autobahn-catalogue.json
```

Compare exported `version`, each ID, index, category, description and expectation
with `cases.json`. Triage fields are repository policy, not upstream data. Pin
updates require a reviewed case-by-case diff, both-role runs, and explicit
exception reapproval; never refresh hashes to excuse a result. See NOTICE.md.

## 2. Roles and the plug-in seam

| Testee role | Autobahn process | Testee obligation |
|---|---|---|
| server | `wstest -m fuzzingclient -s /config/spec.json` | Listen on loopback `J6_PORT`; stay alive until the driver stops it. |
| client | `wstest -m fuzzingserver -s /config/spec.json` | Connect to `/runCase?case=N&agent=AGENT` for every selected-case index in `J6_CASE_INDEXES`; then `/updateReports?agent=AGENT`, await completion and exit zero. |

The driver invokes an argv vector after `--`, never a shell expansion.
`J6_ROLE`, `J6_PORT`, `J6_URL`, `J6_AGENT`, `J6_CASE_INDEXES` (file path), and
`J6_MODE` are the test-launch contract. `J6_FRAME_LIMIT` and
`J6_MESSAGE_LIMIT` declare the proposed 64 MiB conformance profile; the real
launcher must apply and report these, while the smoke stub keeps its own
small fixed bounds. IDs and runCase numeric indexes are
separate: `/runCase` indexes the filtered configuration (1 through selected
count), not the full 517-case catalogue. The driver emits those filtered
indexes; the catalogue index remains upstream-inventory provenance. Use a unique URL-safe agent name.
Logs, exact config, command, source commit, image/policy hashes, raw case JSON
and upstream HTML, plus `summary.json`/`summary.html` are retained in a **new**
output directory. Nonzero exit is failure, never an exclusion. The driver
cleans up its process groups and its uniquely named container.

Future J6 plugs in a test executable using the **same native frame parser,
UTF-8 validator, masking, fragmentation, close and socket/poller machinery as
production**. Its only test hook echoes decoded raw text/binary messages before
CBOR application admission. The hook must neither substitute another WS
library nor disable protocol validation. Autobahn does not negotiate the
production `dao.stream` CBOR subprotocol or validate CBOR batch/observer
semantics. Those remain the separate C0–C4 / §9.6 / cross-peer gates. Text
framing is not N/A merely because production messages are CBOR binary.

`stub.clj` is intentionally a **test-only shim client/server**, using
`jolt.socket` (which remains forbidden in the production adapter closure).
It has a small ASCII handshake digest and bounded, unfragmented echo/pong/close
handling, a fixed test-only client masking key, and no stream codec. It is not
reusable production code. Only four smoke cases are enabled; both the script
and runner refuse to knowingly use this stub for the proposed full gate.
The driver cannot detect a renamed/disguised stub: real gate provenance and
production-closure inspection remain mandatory.

## 3. Case inventory, exceptions and report threshold (proposal)

[triage.md](triage.md) has **every one of the 517 case IDs**, its class,
description and reason. `cases.json` is the corresponding machine policy.
No wildcard selection or silent skip is used.

| Group | Count | Proposed treatment, both roles |
|---|---:|---|
| 1 framing | 16 | Must pass |
| 2 ping/pong | 11 | Must pass |
| 3 reserved bits | 7 | Must pass |
| 4 opcodes | 10 | Must pass |
| 5 fragmentation | 20 | Must pass |
| 6 UTF-8 | 145 | Must pass |
| 7 close | 37 | 34 must pass; 7.1.6, 7.13.1 and 7.13.2 explicit exceptions below |
| 9 limits/performance | 54 | Must pass under declared test bounds; no speed waiver |
| 10.1.1 auto-fragmentation | 1 | Supplemental must-pass inbound fragmentation check |
| 12 compression | 90 | N/A: no permessage-deflate negotiated |
| 13 compression parameters | 126 | N/A: no permessage-deflate negotiated |

Total: **298 must-pass + 3 exception-listed = 301 executed per role**, 216 N/A
(1,204 executions across both roles on both platforms).
The proposal is additive to §9.6 groups 1–7/9. Large valid-message tests use a
declared 64 MiB message/frame ceiling; separately run production bounded-frame
refusal tests, including sender-declared 1009 versus wire-truncated 1006. Do
not turn protocol violations into passes by lifting validation. Compression
N/A requires the testee to ignore extension offers and negotiate none: the
client never offers and fails an unsolicited extension in the 101; RSV frames
close 1002 (group 3). Enabling extensions invalidates this policy and requires
a new reviewed inventory. No group 12/13 sentinel cases are in the policy.

`exceptions.json` rows name an exact case and testee role, rationale,
owner-proposal reference, and exact raw-result assertions. No wildcard or
blanket FAILED permission exists. Upstream deliberately gives **7.13.1 (5000)**
and **7.13.2 (65535)** `INFORMATIONAL` for both behavior fields. The proposed
exception accepts those labels only when the peer returns **1002**, with no
handshake/drop timeouts. This preserves invalid-close-code rejection; it does
not accept the suite's broad “undefined” response. The 1002 is the jolt row of
[dao.stream.ws.md Host wire codes](../../docs/design/dao.stream.ws.md), a
host-profile choice, not an RFC necessity.

**7.1.6** (256K message, then Close, then Ping) is also exception-listed:
upstream `Case7_1_6.onConnectionLost` overwrites both behaviors with
`INFORMATIONAL` unconditionally (verified at pinned source commit 6ed6f439), so
OK/OK is unsatisfiable. The exception requires `remoteCloseCode` 1000, no
handshake/drop timeouts, and, through the exception row's `forbid_rx_opcodes`,
no Pong (opcode 10) in the report's `rxFrameStats`. That field is always
present in the pinned per-case report, keyed by received opcode (JSON string
keys, so `"10"`); a missing field fails. Whether the pre-close echo completes
or is cut is not asserted.

Six rows cover the three cases in both roles. These exceptions are **not
owner-approved yet**.

Strict threshold: every selected case present exactly once, correct agent and
role, every ordinary `behavior` and `behaviorClose` exactly `OK`; only the
pinned assertion-based exceptions may differ. Missing fields/results, extra
cases, duplicates, FAILED, unlisted NON-STRICT/INFORMATIONAL outcomes, timeouts,
launcher errors and nonzero suite exit all fail. There is no percentage score.
`summary.json` always says `conformance_claim: false`: a report is evidence for
the owner's gate review, not authority to announce lane qualification.

## 4. Reproduction and prep acceptance

Run in an isolated checkout following
[build-n-test](../../docs/agents/build-n-test.md), not another seat's checkout.
Jolt/Shared Chez pins remain in `jolt-toolchain.edn`; no independent pin here.

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

Smoke IDs: `1.1.1`, `1.2.1`, `2.1`, `7.1.1`. Prep acceptance is four reports
in each direction from the jolt stub, accepted smoke summaries and HTML,
policy loaded/hashed, and negative evaluator tests that prove missing,
duplicate, wrong-role/agent, failed, non-strict, timeout and invalid-exception
evidence fails. The exception path is exercised by synthetic **policy tests**,
not claimed as a passing real-adapter close-code test. Raw reports are build
artifacts, not source files. A failing prep does not justify loosening policy.

Real-adapter invocation replaces the command, uses `--mode gate-proposal`, and
records its production test bounds and native platform in the run evidence.
It is intentionally not runnable until J6 supplies that executable.

## 5. Proposed J6 amendment for owner ruling

Before J6 closes: owner approves this pinned 517-case classification and the
six exact exception rows (or requests a revision); native jolt **client and
server** each execute all 301 selected cases on **macOS arm64 and Linux
x86_64** with the strict threshold above (1,204 selected results, no unexplained
skips). Retain raw/summary reports, commit/toolchain/OS/architecture/image IDs,
actual resource bounds, runtime duration and closure inspection. N/A cases
stay listed with reasons. Publish the resulting case list in the J6 brief.

Additionally retain every existing §9.6 gate: malformed/oversize CBOR refused
before guest construction, bounded-frame matrix, native non-waiting send and
exact queued-byte drain, close/error lifecycle, and real jolt↔JVM/Node/Dart
network REPL peer fixtures in both relevant directions. Autobahn proves none
of those application-level properties. No adapter is implemented by this prep.

**Contract discrepancy:** the task's `EAGAIN/ENOBUFS → transport-error +
send-failed; no full` wording is the **J7 UDP** row. The landed WS contract
explicitly permits `full` for queue/backpressure (§dao.stream.ws). Do not amend
WS into UDP semantics here. J6 keeps `send!` non-waiting and exact
`:queued-bytes`; any owner-requested change to WS outcomes needs a separate
ruling. The mixed Node/OS-lock limitation and satisfied C0–C4 preconditions are
not reopened.
