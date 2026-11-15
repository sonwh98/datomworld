Created-GMT: 2026-11-15 19:48:35 UTC
Coding-Agent: codex
Role: engineer
Branch: fix/dao-host-jolt-arms
Base: cb725b1cae3f2c5b47d2277d5ff65a7d3f79ddde

# Shared integer jolt arms and facade audit

## Change and representation

Added `:jolt x` to `dao.host.integer/promote` and `:jolt n` to
`from-native`, after `:cljd` and before `:clj`. The existing JVM, JS and
Dart expressions are unchanged. No other production source, test, require
form, REPL source or DHT source changed.

The bootstrapped pin is `b389f1a7fb553f63a3494d71f8fe3251790bf4d3`.
Its `host/chez/values.ss` and `reader.ss` use Chez's exact numeric tower;
`host/chez/java/host-static-methods.ss:315` explicitly implements
`BigInt/fromBigInteger` and `Numbers/reduceBigInt` as identity because
Chez integers automatically reduce. `host/chez/protocols.ss:474` and
`host/chez/java/host-class.ss:175` classify values within signed 64 bits
as Long and values beyond as BigInt. A Chez bignum can still be a jolt
Long: Chez's fixnum width is smaller than signed 64 bits.

This matches the existing `src/jolt/dao/host/integer.jolt` kernel:
`promote`, `from-native`, `demote`, and `to-native` are identity; arithmetic
uses Chez exact procedures. No Java BigInteger allocation or `long`
conversion belongs in the two new arms, especially for promotion beyond
64 bits.

## Qualification of the original diagnosis

The shared-file failure is reproducible. However, the repository already
has whole-file `.jolt` overrides for all six host namespaces, installed
by `-M:jolt` (`deps.edn:95`). The metadata evidence below confirms those
files supply the configured lane. The two new shared arms are therefore
tested explicitly with `load-file`; ordinary acceptance alone would not
exercise this change.

The evidence does **not** establish an H1/H2 regression in the configured
`-M:jolt` lane. The reported `jolt -Srepro` invocation omits the alias
that supplies the host implementations. With only the two new arms and
no alias, the first failure moves to `.multiply`; that invocation still
does not load the facade. The configured lane passes all load checks.
Replacing the existing host overrides with a second full implementation
in shared reader arms would exceed this surgical repair.

The literal var `yin.repl/main` does not exist in this checkout. Requiring
`yin.repl` succeeds, and requiring the actual entry-point namespace
`yin.repl.main` succeeds and resolves `yin.repl.main/-main`. This is
reported as an acceptance-name discrepancy, not repaired in forbidden
REPL sources.

## Audit

Audited the facade's static require closure, the host overrides it selects,
all shared reader conditionals in the namespaces below, and the mem
backend used in acceptance. Runtime loaded-libs evidence is below.
"Covered" means supplied by the existing `.jolt` file under `-M:jolt`;
the absence of a shared `:jolt` arm by itself is not missing coverage.

| Namespace | Arm / entries audited | Disposition |
| --- | --- | --- |
| dao.host.integer | `promote`, `from-native`: JVM `BigInteger/valueOf` / `.toBigInteger` | Added identity `:jolt` arms; explicit shared-file probe red before / green after. Existing override already has matching identities. |
| dao.host.integer | `big-carrier?`, `exact-integer?`, `safe-native?`, `host-float?`, `nan-float?`, `infinite-float?` | Covered by Chez predicates and value ranges in integer.jolt; no added arms. |
| dao.host.integer | `demote`, `to-native`, `native->double`, `trunc-native` | Covered by identity / Chez exact-inexact conversion in integer.jolt. |
| dao.host.integer | `add`, `sub`, `mul`, `quot-trunc`, `rem-trunc`, `compare-big` | Covered by Chez exact arithmetic in integer.jolt. Shared JVM `.multiply` is an observed load blocker **without** the alias at float's powers-of-ten initializer; see exact failure below. |
| dao.host.integer | `bit-and-big`, `bit-or-big`, `bit-xor-big`, `shift-left-big`, `shift-right-big`, `magnitude-bit-length`, `to-radix-string` | Covered by Chez logand/logor/logxor/ash/bitwise-length/number->string; avoids jolt core's long-only bit operations. |
| dao.host.integer | `parse-decimal`, `gcd`, `magnitude-bytes` | Covered by Chez parsing/gcd and byte-array construction. Facade smoke includes a >64-bit integer. |
| dao.host.float | `words`, `bits->float64`, `finite?`, `integral?`; `fields` and float-text kernel consumers | Covered by float.jolt's binary64 primitives and integer kernel. Top-level zero/one/ten, powers of ten and float constants load successfully. Shared file without alias still reaches the JVM multiplication noted above. |
| dao.host.number | `float64-of`, `float-value`, `decimal?`, `decimal-of`, `decimal-exponent`, `decimal-mantissa`, `ratio?`, `ratio-of`, `ratio-numerator`, `ratio-denominator`; carrier deftype / print-method conditionals | Covered by number.jolt, including `BigDecimal/valueOf` to preserve scale and Chez exact ratios. The shared JVM decimal constructor is not used. Initial zero loads; decimal and ratio round trips pass. |
| dao.host.bytes | `bytes?`, `native-int?`, `length`, `alloc`, `byte-at`, `slice`, `from-vector`, `set!`, `copy!`; portable `get`, `equal?`, `to-vector` | Covered by bytes.jolt over jolt byte arrays. JVM array interop is not selected. Used for encoding, mem snapshots, hashing and Base64 smoke. |
| dao.host.text | `unit-count`, `code-unit`, `units->string`, `utf8-encode`, `byte-count`, `utf8-decode`, `nfc`; portable surrogate/code-point checks | Covered by text.jolt, including strict UTF-8 and normalization. Shared JVM charset/Normalizer imports are not selected. Non-ASCII and astral text round trips pass. |
| dao.host.hash | JVM imports and `blake3-bytes`, `sha256-bytes` | Covered by hash.jolt's native wrapper and provenance gate. Wrapper must be built; missing wrapper was the initial configured-lane load failure in the fresh worktree, fixed by building, not source edits. Default BLAKE3 mem smoke passes. |
| dao.jing.cbor | `type-name`, `host` catch, integer recognition, `float64?`, `frame->value` list conditional; migrated numeric/bytes/text consumers and top-level constants | Shared portable namespace, no override. JVM-compatible type/catch/list arms work on jolt; every migrated host operation is supplied by the six overrides. Load and mixed numeric/text encoding/decoding pass. No added arms. |
| dao.stream | No reader conditionals or requires | Portable protocols, data and validation; loads. |
| dao.stream.base64 | StringBuilder constructor/append/toString conditionals | JVM-compatible jolt StringBuilder works; explicit binary encode/decode round trip passes. No added arms. |
| dao.stream.observe | No reader conditionals; requires dao.stream | Portable observer state machine; loads. |
| dao.jing | Object sentinel, StringBuilder hex rendering, Throwable catches | Existing jolt compatibility supports these JVM arms. Facade load, BLAKE3 address minting/verification, duplicate materialization, read and close pass. No added arms. |
| dao.jing.mem | No reader conditionals; requires dao.jing and dao.jing.cbor | Portable closure-backed byte store; constructed with `create-content-mem`, then put/get/close through facade. The facade itself exports no backend constructor. |
| clojure.string / clojure.edn / jolt.scheme / jolt.ffi | Runtime libraries used by the facade and host overrides | Not H1/H2 repository host arms; loaded successfully. |

No additional missing host coverage blocks the configured facade or REPL
load. No top-level conditional requires were introduced.

## Environment and build evidence

All commands ran in this worktree. The sandbox makes the ordinary mise
state directory read-only; used worktree-local state, trusted the config,
then activated the requested environment:

```bash
export MISE_STATE_DIR="$PWD/build/local-state/mise"
mise trust
eval "$(mise env -s bash)"
bb bootstrap:jolt
source build/tools/jolt-env.sh
export PATH="$JOLT_HOME/bin:$PATH"
```

GitHub DNS/network access is unavailable here. Materialized a local copy
of `/home/sto/ci/datomworld-j6ev/build/tools/jolt`; bootstrap verified the
declared origin, full SHA, clean source and all seven pinned submodules.
Native source archives came from that checkout's local cache and passed
the build's digest checks. C++ parser generation attempted a network
fetch and failed (`SocketException: Operation not permitted`), so reused
the cached generated C++ files. Verified all eight actual file BLAKE3
digests against this checkout's identical Python manifest before relying
on the build. No toolchain declaration or qualification was changed.

Successful bootstrap output, verbatim:

```text
os-packages: all 6 required Debian packages installed: gcc git libncurses-dev libssl-dev libx11-dev make
chez 10.4.1 threaded #t /home/sto/.local/share/mise/installs/chezscheme/10.4.1/bin/scheme
cmake version 4.4.4 /home/sto/.local/share/mise/installs/cmake/4.4.4/cmake-4.4.4-linux-x86_64/bin/cmake
jolt b389f1a7fb553f63a3494d71f8fe3251790bf4d3 with 7 submodules verified
jolt smoke (+ 1 2) => 3
wrote /home/sto/ci/dw-host-jolt-arms/build/tools/jolt-env.sh
```

`bb build:jolt-native` exited 0, building the runtime, both parser
variants, BLAKE3, crypto and file wrappers; final line, verbatim:

```text
wrote build/native/linux-x86_64/build-record.edn
```

Parser cache verification output:

```text
:cached-cpp-digests-ok 8
```

## Direct shared-arm regression evidence

Before editing, ran:

```clojure
(load-file "src/cljc/dao/host/integer.cljc")
(dao.host.integer/from-native 0)
```

Under `jolt -Srepro -M:jolt -e`, verbatim output:

```text
Unhandled exception (IllegalArgumentException): No matching field or method: BigInteger/valueOf
  at 1:46
```

After editing, explicitly loaded the same shared file and asserted
promotion preserves 0, +/-1, +/-9007199254740991, both signed-long
endpoints, 9223372036854775808N, -9223372036854775809N and 10^40.
Asserted `from-native` preserves each listed value within signed-long
range. Exit 0; verbatim output:

```text
:shared-source /home/sto/ci/dw-host-jolt-arms/src/cljc/dao/host/integer.cljc
:shared-integer-arms-ok
```

## Jolt facade and REPL load evidence

Command (exit 0):

```bash
jolt -Srepro -M:jolt -e "(require 'dao.jing) (println :jing-load-ok) (require 'yin.repl) (println :yin-repl-load-ok) (println :yin.repl/main (resolve 'yin.repl/main)) (require 'yin.repl.main) (println :yin.repl.main/-main (boolean (resolve 'yin.repl.main/-main)))"
```

Verbatim output:

```text
:jing-load-ok
:yin-repl-load-ok
:yin.repl/main nil
:yin.repl.main/-main true
```

Mem smoke constructed `(dao.jing.mem/create-content-mem)`, materialized
one map through `dao.jing/materialize!`, checked equality through the
three-argument `dao.jing/get`, materialized it again to exercise the
`:present` path, and closed through `dao.jing/close!`. Value:

```clojure
{:message "jolt λ 😀" :integers [0 -1 9223372036854775808N]
 :float 1.5 :decimal (dao.jing.cbor/decimal -2 123)
 :ratio (dao.jing.cbor/ratio 2 3)}
```

Also checked Base64 encode/decode equality for bytes `[0 127 128 255]`.
`jolt -Srepro -M:jolt build/arms-smoke.clj` exited 0; verbatim output:

```text
:host-source dao.host.integer/from-native dao/host/integer.jolt
:host-source dao.host.float/fields dao/host/float.jolt
:host-source dao.host.number/decimal-of dao/host/number.jolt
:host-source dao.host.bytes/from-vector dao/host/bytes.jolt
:host-source dao.host.text/utf8-encode dao/host/text.jolt
:host-source dao.host.hash/blake3-bytes dao/host/hash.jolt
:mem-put-get-ok :segment/blake3-f7d24ceebea29e0da4e0a0c101b312d65ab9ff625fd0778ba66117f3a7f6d7a4
:base64-ok
:facade-closure (clojure.string dao.host.bytes dao.host.float dao.host.hash dao.host.integer dao.host.number dao.host.text dao.jing dao.jing.cbor dao.jing.mem dao.stream dao.stream.base64 dao.stream.observe)
```

Separately constructed `(yin.repl/create-state)` without options, asserted
`:index-store-spec` is `:mem`, then materialized/read `{:default-mem 42}`
through `dao.jing` using its `:index-store`. Exit 0, verbatim output:

```text
:repl-default-mem-ok
```

`bb test:jolt-host` also exited 0. Final summary, verbatim:

```text
SUMMARY {:namespaces 4, :load-failures 0, :tests 31, :assertions 848, :pass 848, :fail 0, :error 0}
```

## DHT record (non-gating)

`jolt -Srepro -M:jolt -e "(require 'dao.jing.dht) (println :dht-load-ok)"`
exited 0. Verbatim output:

```text
:dht-load-ok
```

## Invocation without the host alias

`jolt -Srepro -e "(require 'dao.jing) (println :jing-without-alias-ok)"`
exited 1 after the two arms were added. Exact first failure, verbatim:

```text
Unhandled exception (IllegalArgumentException): No matching method multiply found taking 1 args for class java.lang.Long
  at ./src/cljc/dao/host/float.cljc:283:1
  trace:
    dao.host.integer/mul (./src/cljc/dao/host/integer.cljc:226)
    seq-more-force (x2)
    seq->list
    ldr-load-body
    for-each
    ns-require+use
    ldr-load-body
    ns-require+use
    ldr-load-body
    ns-require+use
```

## JVM evidence

`bb run-clj` is not a task in this checkout, so used the authorized
equivalent `clojure -M -e "(require 'dao.jing) (require 'yin.repl)
(println :jvm-ok)"`. Exit 0, verbatim output:

```text
Loading initial Timbre config from: :default
WARNING: load already refers to: #'clojure.core/load in namespace: yin.vm.encoder, being replaced by: #'yin.vm.encoder/load
:jvm-ok
```

`clojure -M:test -e :slow -n dao.host.conformance-test -n dao.host.float-test`
exited 0. Summary, verbatim:

```text
Testing dao.host.conformance-test

Testing dao.host.float-test

Ran 19 tests containing 690 assertions.
0 failures, 0 errors.
```

## Commit blocked by workspace permissions

Implementation and verification are complete, but no commit was created.
Both `git add` and `git commit` were rejected because this linked
worktree's Git metadata is outside the sandbox's writable roots:

```text
fatal: Unable to create '/home/sto/ci/datomworld-j6e-linux/.git/worktrees/dw-host-jolt-arms/index.lock': Read-only file system
```

The session's approval policy is `never`, so escalation is unavailable.
The branch remains `fix/dao-host-jolt-arms` at the base recorded above.
The two deliverables remain unstaged; the pre-existing untracked
`codex-run.stdout.log` was left alone. Intended commit subject:
`fix(host): avoid H1/H2 BigInteger promotion interop on jolt`.
