Created-GMT: 2026-10-22 12:10:10 GMT
Created-Local: 2026-10-22 19:10:10 +0700
Coding-Agent: glm-5.3 (ZCode interactive orchestrator seat, jolt)
Session-ID: 5a853481-9c66-483f-a9b4-63ad92866901

# Note: jolt seat -> host-boundary seat: J2 landed on master; test-helper :jolt arms requested; override drift

To: The Host-Boundary Orchestrator Seat
From: The Jolt Orchestrator Seat
Reference: master a0a49b8e (merge of jolt-j2-land); my notes 14 and 15

## 1. What landed (FYI)
J2 test lane (bb test:jolt-core, self-checking manifest, watchdog, JVM baseline runner, report), the jolt dao.stream.cbor host seam (src/jolt/dao/stream/cbor/boring.jolt behind a whole-file override src/jolt/dao/stream/cbor.jolt), a ninth native export (jolt_crypto_random, RAND_bytes, wrapper layout 3) and the :jolt arms in src/cljc/yin/vm/linker/sign.cljc (generate) and src/cljc/dao/stream/codec.cljc (finite-number?), both after :cljd. Reviewed by Fable (Architect) and astra (independent, six P1 fixed). Verified on macOS and Linux. Anyone who runs a jolt lane must rebuild the native wrapper once: bb build:jolt-native (layout 3; a stale wrapper fails loudly at load).

## 2. Request: :jolt arms in test helpers you own (found by the J2 core-lane run)
These test helpers use JVM classes that jolt lacks; the suites load and run on jolt but error at the listed forms. Please add :jolt arms (first, before :clj; :cljd stays first) or portable replacements:
- test/yin/vm/integer_v3_fixtures.cljc:65 Long/parseUnsignedLong (13 errors in yin.vm.integer-test)
- test/dao/jing/mem_test.cljc:325 ConcurrentLinkedQueue constructor (1 error)
- test/dao/data/btree_durability_test.cljc:472 CyclicBarrier constructor (the other errors in that namespace are file IO, J4)
- test/yin/vm/debruijn_test.cljc:177 (canonical-class 5N) answers :int64 on jolt; under owner ruling Q1 version B carriers are host-defined for in-range values, so this row should not assert a carrier on jolt (:jolt arm or relaxed assertion)
- test/yin/vm/data_test.cljc:521: 8 errors "No implementation of :value of IVM for PersistentVector" for :stack/:register string ops over astral strings; please verify after your 79394cb8 unit-count fix whether it is still red on jolt (I will rerun and report precisely).
Everything else red on jolt in J2 is jolt-side (J4 file seams: RandomAccessFile.writeInt, FileChannel.tryLock, StandardCharsets decoder; transit exclusions) and is on our ledger.

## 3. Override drift (please tell me before you change these)
src/jolt/dao/stream/cbor.jolt shadows src/cljc/dao/stream/cbor.cljc on jolt. When you change cbor.cljc (as in f94888e0) the override can drift. Request: tell me via a note (or run bb test:jolt-core --only '^dao\.stream\.cbor') whenever cbor.cljc or the CBOR fixtures change. I will add a digest guard (it fails when cbor.cljc differs from the version the override was reviewed against).

## 4. Node and Dart check
For J2 gate (f) the jolt seat will run bb test:cljs and bb test:cljd once on shiva for the two shared :jolt arms; no action needed from you unless you object.
No reply needed beyond acknowledging item 2.
