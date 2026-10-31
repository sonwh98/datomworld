**Verdict: SIGN-OFF WITHHELD.** One confirmed High breaks the IP-literal-only rule, and one probable High on the Dart seam needs a probe before commit. Everything else is sound and the re-review should be short.

I ran no lanes; the only thing I executed was one JVM eval to confirm finding 1. Finding 2 is from my memory of dart:io's source, not from a run.

## Findings

| Severity | file:line | Issue | Fix |
|---|---|---|---|
| **High (confirmed)** | `src/cljc/dao/stream/datagram.cljc:108` | `piece-groups` uses `str/blank?`, so a whitespace-only piece beside `::` counts as empty. `ip-literal?` accepts `" ::1"`, `" :: "`, `"\n::"`, `"1::\t"`. On the JVM, `InetAddress/getByName " ::1"` then did a DNS lookup: it blocked 1.1 s and resolved to `125.235.4.59`. That is a name-resolved destination and a waiting `append!`. The test table only covers whitespace for IPv4. | Replace with `(= "" piece)`. Add `" ::1"`, `" ::"`, `":: "`, `"\n::"`, `"1::\t"` to the invalid table in `datagram_test.cljc`. |
| **High (unverified)** | `src/cljd/dao/stream/datagram/dart.cljd:71-74` | `.listen` has no `onError` and ignores `RawSocketEvent.closed`. As I recall dart:io, an OS send error returns 0, then adds the error to the socket stream and closes the socket. If so, one failed send (e.g. an IPv6 literal through a 127.0.0.1-bound socket) raises an unhandled async error and silently kills the socket with no `closed` event. Once the DHT shares the socket, a peer advertising such an address could trigger it. | Probe first: send to `::1` from an IPv4-bound socket, then assert a canary still round-trips. If it reproduces, add `.onError` depositing `send-failed`, and on `RawSocketEvent.closed` mark state closed and deposit `closed` once. |
| Medium | `src/cljc/dao/stream/datagram.cljc:295-297` | The writer passes through any `:dao.stream/outcome` the seam answers, so the exact outcome set holds only by seam convention. A seam answering `:full` or `:refused` would leak an excluded outcome, and no test would fail. | Clamp: any seam outcome outside `ok` / `transport-error` / `closed` becomes `transport-error`. Add a scripted-seam test answering `:dao.stream/full`. |
| Medium | `jvm.clj:72`, `node.cljs:57`, `dart.cljd:35` | Contract §4 says inbound oversize is "counted and never deposited". No seam counts anything, yet all three docstrings and the report say "counted". Never-deposited itself holds and is tested on all hosts. | Strike "counted" from the docstrings and amend §4 (see rulings). Do not add a counter nobody can read. |
| Low | `test/dao/stream/udp_raw_test.cljc:265` | The budget test uses only deposited datagrams. If budget were decremented only on deposit, a flood of DHT maps or garbage would make a step unbounded on the JVM, and no test would fail. The code is correct today. | Add one case: five lifecycle/DHT events with budget 2, then assert the next step still has work left. |
| Low | `test/dao/stream/datagram/{jvm,node,dart}_test` | `send-failed` is only ever provoked by sending on a seam closed behind the writer, which is unreachable through the writer handle. No test covers a live-socket failure. | The Dart probe above doubles as the live-socket case; mirror it on Node and JVM. |
| Low | `src/cljc/dao/stream/datagram.cljc:129` | Zone ids are refused, but JVM and Node report link-local IPv6 sources as `fe80::1%en0`. Those events fail `datagram-event?` and `port-step!` silently drops them. | Acceptable for S1; add one sentence to §2 that scoped link-local addresses are out of scope. |
| Low | `src/cljs/dao/stream/datagram/node.cljs:75-79` | `close!` before `listening` deposits nothing on Node; Dart deposits `closed` in the same case and JVM deposits `bound` then `closed`. | Tolerated by "when it still can"; note it in the Node docstring. |
| Low | `src/clj/dao/stream/datagram/jvm.clj:49` | `.getMessage` can be nil, so a `bind-failed` reason may not be text. | Use `(str e)`, as the other two seams do. |
| Low | `src/cljc/dao/stream/udp.cljc:546-549`, `base64.cljc:48`, `datagram.cljc:255-259` | Each datagram pays the strict Base64 regex four times end to end and CBOR decode twice on receive. The `dht-owned?` docstring calls the double decode forced; a private decoded-value entry under `receive!` would remove it without touching the public surface. | Follow-up, not S1. |
| Info | `docs/design/dao.stream.datagram.md:3`, `docs/design/dao.stream.remote.implementation-plan.md:111-112` | Status still says "not implemented"; the plan still names the deleted `udp/{jvm,node,dart}` seams. | Update with the commit. |
| Info | `src/cljd/dao/stream/datagram/dart.cljd:117-123` | The comment says dart:io "sends nothing" for an empty payload. I suspect it does send, returns 0, and the Dart receive side drops empty datagrams, which a Dart-to-Dart probe cannot tell apart. | Reword to "a 0 return cannot distinguish an empty send from a failed one". A Dart-to-JVM probe would settle it. |

## What passed

- **Owner invariants:** the seams are symmetric binds with no server/client wording and no privileged node. Nothing in `dao.stream.apply` is touched. Each seam takes only `{:identity :deposit :bind-host :bind-port :max-bytes}` and no function to invoke.
- **Gap adoption and budget:** `port-step!` decrements on every read, gaps and skips included, and the gap test would fail if adoption broke.
- **Oversize:** outbound is bounded on decoded length; inbound is dropped on all three hosts, and the 1200-byte round trip pins the boundary as `<=`.
- **Portability traps:** `:cljd` is first in every mixed reader conditional; no `array-map`; no cross-namespace `#'`; both hand-built regexes are anchored. A 2 MB Base64 string does not overflow the JVM regex.
- **Unchanged tests:** `udp_test.cljc` and the `dao.stream.remote` tests are untouched, and nothing in `src` or `test` references the deleted seams.

## Rulings

- **(a) Synchronous failure answers `transport-error` and deposits `send-failed`: accepted.** The contract does not forbid it, and the JVM could never satisfy the acceptance bullet otherwise. "Every send failure the seam observes is an event" is a simpler rule than one that depends on whether the host is synchronous. Two conditions:
  - Amend §4 to say so, including that events carry no correlation and a caller must not count both channels.
  - Record the cost: each failed send takes one traffic-ring slot, so a failing sender can evict inbound datagrams.
- **(b1) Dart closed-socket send is `transport-error` plus the event: accepted.** It matches what JVM and Node do for a seam closed behind the writer, and the writer itself answers `closed` first.
- **(b2) Dart zero-length send is a clean `transport-error` with no event: accepted.** The refusal is decided before the host is touched, so it is clean by construction; `invalid-value` would be wrong because `""` is a valid §2 value. Add a host note to §4, and S3 cross-host tests must not originate empty datagrams from Dart. No current consumer sends one.
- **"Counted":** the contract names no channel for the count, so amend §4 to "dropped, never deposited" and defer any counter to the slice that defines where it is observed.
- **Throughput: acceptable for S1.** The acceptance asks only for measure-and-report. 0.94× on the common path is fine. 0.60× on 48 KiB values is still about 7.6 MB/s host-free, well above what a real path delivers. The redundant regex and decode passes are the obvious recovery; queue that as a follow-up.
- **CLJD −1: plausible as a pre-existing flake.** `a-parked-wait-set-keeps-being-polled-with-no-nudges` asserts at least 3 polls within a 120 ms wall-clock sleep, and nothing under waitset requires datagram, udp or base64. The new Dart socket suites add load and could make it more frequent. The orchestrator should run that test on `dac64b41` and on this tree, solo and in the full lane.

## To clear the withhold

1. Fix the `str/blank?` hole and add the test rows.
2. Run the Dart probe and add the `onError` / closed handling if it reproduces.
3. Clamp the writer's outcome set and add its test.
4. Strike "counted", fix the JVM reason, and apply the §4 amendments from the rulings.

The Low test additions may ride the same pass.
