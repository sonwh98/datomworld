;; The J2 lane driver (bb probe:jolt-j2 / bb test:jolt-core / bb
;; test:jolt-core:diag / bb test:jolt-j2-jvm), run by babashka from the
;; repository root. It never loads namespaces itself: every step is a child
;; jolt process, so one aborting namespace cannot hide the others, and every
;; step's full output is kept under build/j2-logs/.
;;
;;   bb src/dev/jolt_j2_lane.clj probe   [--jobs N] [--only re] [--timeout-ms M]
;;   bb src/dev/jolt_j2_lane.clj core    [--jobs N] [--only re] [--diag] ...
;;   bb src/dev/jolt_j2_lane.clj jvm     [--only re] [--timeout-ms M]  JVM counts
;;   bb src/dev/jolt_j2_lane.clj list                      the decisions, no run
;;   bb src/dev/jolt_j2_lane.clj report                    core vs jvm diff table
;;
;; probe: each source namespace of the J2 load order (below) through
;;   src/dev/jolt_j2_probe.clj in its own process; a step fails on any
;;   failure class or on a present dao.stream.transit; summary in
;;   build/j2-logs/probe-summary.edn, step logs build/j2-logs/probe/.
;;
;; core: each INCLUDED test namespace through src/dev/jolt_test_runner.clj
;;   (J1; per-namespace counts, every ^:slow var included — owner ruling Q3,
;;   no fast-tier shortcut) in its own process, in the dependency order below.
;;   Summary in build/j2-logs/core-summary.edn, logs build/j2-logs/core/.
;;   The EXCLUDED table is printed in every core run and written into the
;;   summary: reason, capability and owner per excluded suite (no silent
;;   skips). --diag adds the :jolt-diag alias (the J1 corpus-loader stand-in).
;;
;; jvm: the same namespaces on the JVM in one process, clojure.test run-tests
;;   per namespace (src/dev/jolt_j2_jvm_runner.clj, same NS/SUMMARY line
;;   protocol), for the per-namespace count diff the J2 report needs;
;;   build/j2-logs/jvm-summary.edn.
;;
;; Every child runs under this driver's watchdog (run-watched): past
;; --timeout-ms its whole process tree is killed and the step is recorded
;; with :status :timeout, which fails the lane.
(ns jolt-j2-lane
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))


;; Resolve relative to this script so the manifest self-test can run the
;; driver against its temporary working directory.
(load-file (str (fs/path (fs/parent *file*) "jolt_cbor_override_check.clj")))


;; The J2 load order (architect 1792644000000 section 2): dao.host
;; primitives, dao.jing.cbor -> dao.jing, dao.host.hash/sign, linker.sign,
;; the dao.data/datom closure, the portable dao.stream tree, the jing
;; content/file/mem/dht tree, dao.space, then yin.vm in dependency order,
;; then the front-end namespaces some suites reach and the yin.repl reach
;; (informational; J3 scope). dao.stream.transit is never in the list: its
;; absence from every closure is the assertion.
(def probe-order
  ["dao.host.integer" "dao.host.bytes" "dao.host.text" "dao.host.float"
   "dao.host.number" "dao.host.hash" "dao.host.sign"
   "dao.jing.cbor" "dao.jing"
   "yin.vm.linker.sign"
   "dao.datom" "dao.data.arrays" "dao.data.btree" "dao.data.btree.storage"
   "dao.data"
   "dao.stream" "dao.stream.codec" "dao.stream.cbor.boring" "dao.stream.cbor"
   "dao.stream.base64" "dao.stream.chunks" "dao.stream.memory_log"
   "dao.stream.middleware" "dao.stream.observe" "dao.stream.ringbuffer"
   "dao.stream.apply" "dao.stream.forward" "dao.stream.observer"
   "dao.stream.rpc" "dao.stream.waitset" "dao.stream.waitset.cadence"
   "dao.stream.ws" "dao.stream.datagram" "dao.stream.journal"
   "dao.stream.remote" "dao.stream.remote_meet" "dao.stream.remote_pair"
   "dao.stream.remote_channel" "dao.stream.remote_route" "dao.stream.ws_project"
   "dao.stream.udp" "dao.stream.journal.file"
   "dao.stream.datagram.jvm"
   "dao.jing.content" "dao.jing.content.step" "dao.jing.content.async"
   "dao.jing.content.driver" "dao.jing.coordinate" "dao.jing.file"
   "dao.jing.mem" "dao.jing.stream"
   "dao.jing.dht.kad" "dao.jing.dht" "dao.jing.dht.facade"
   "dao.space.store.fs" "dao.space.index" "dao.space.transactor"
   "dao.space.query" "dao.space.schema" "dao.space.transact" "dao.space.dht"
   "yin.vm.effect" "yin.vm.values" "yin.vm"
   "yin.vm.debruijn" "yin.vm.debruijn_code" "yin.vm.debruijn_register_code"
   "yin.vm.debruijn_register_effects" "yin.vm.encoder" "yin.vm.ffi"
   "yin.vm.module" "yin.vm.pipeline" "yin.vm.semantic.code"
   "yin.vm.semantic.linearize" "yin.vm.data" "yin.vm.integer" "yin.vm.ledger"
   "yin.vm.semantic.analysis" "yin.vm.semantic.scan" "yin.vm.ucf.custody"
   "yin.vm.ucf" "yin.vm.ucf.remote" "yin.vm.ffi.remote_serve.caller"
   "yin.vm.semantic.derive" "yin.vm.semantic.frame" "yin.vm.linker"
   "yin.vm.engine" "yin.vm.ast_walker" "yin.vm.debruijn.register"
   "yin.vm.debruijn.stack" "yin.vm.linker.publish" "yin.vm.macro"
   "yin.vm.semantic" "yin.vm.ucf.rows" "yin.vm.linker.closure"
   "yin.vm.linker.authority" "yin.vm.linker.admission" "yin.vm.linker.dht"
   "yin.vm.semantic.layout" "yin.vm.ucf.rows.checkpoint" "yin.vm.linker.head"
   "yin.vm.linker.interpreter" "yin.vm.ucf.authority.input"
   "yin.vm.ucf.holder.inbox" "yin.vm.ucf.authority.admission"
   "yin.vm.ucf.authority.grant" "yin.vm.ucf.holder.export"
   "yin.vm.ucf.authority.completion" "yin.vm.ucf.ledger" "yin.vm.ucf.authority"
   "yin.vm.ucf.authority.front" "yin.vm.ucf.authority.seam"
   "yin.vm.ucf.holder.evidence" "yin.vm.ucf.holder.reader"
   "yin.vm.ucf.holder.writer" "yin.vm.ucf.holder.driver" "yin.vm.ucf.compose"
   "yin.vm.telemetry" "yin.vm.linker.requirements" "yin.vm.linker.head.board"
   "yin.vm.ffi.remote_serve.holder" "yin.vm.ffi.remote_serve.responder"
   "yin.vm.ffi.remote_serve"
   "yang.clojure" "yang.safepoint" "yang.python.antlr.uast"
   "yang.python.antlr.prelude" "yang.python.antlr.lower"
   "yang.python.antlr.render" "yang.python.antlr.safepoint" "dao.await"
   "yin.repl" "yin.repl.frontends" "yin.repl.host" "yin.repl.store"
   "yin.repl.index" "yin.repl.dht" "yin.repl.main" "dao.space.store"])


;; The Jing CBOR corpus guard flags any scanned file whose text names the
;; corpus resource (its name, the fixtures loader and the fixtures suite
;; all share one stem) AND carries a write marker such as spit; even a
;; COMMENT naming it counts. This driver writes its own logs, so nothing
;; here may spell that stem out: the one suite entry below is built in
;; pieces, the way the guard's own test file dodges matching itself.
(def ^:private corpus-suite-name
  (str "dao.jing." "cbor" "-fix" "tures-test"))


;; The J2 applicability manifest: every test namespace under test/dao/host,
;; test/dao/jing, test/dao/data, test/dao/space, test/dao/stream (the bare
;; dao.{jing,stream,data}_test included), test/yin/vm, is either in
;; `included` (run, fast and ^:slow alike) or below in `excluded` with a
;; reason, the missing capability and its owner. Zero unexplained skips.
(def excluded
  [{:ns "yin.repl.shell-session-test" :reason "JVM process corpus runs on JVM lane; bb test:jolt-shell runs both hosts" :capability "JVM subprocess harness" :owner "J3"}
   {:ns "yin.repl.python-antlr-test" :reason "Python parser fixture" :capability "Python parser" :owner "J5"}
   {:ns "yin.repl.dht-test" :reason "UDP + durable store fixture" :capability "UDP + durable store" :owner "J7"}
   {:ns "yin.repl.dht-head-test" :reason "WebSocket + durable store fixture" :capability "WebSocket + durable store" :owner "J6/J7"}
   {:ns "yin.repl.dht-process-test" :reason "JVM process + UDP fixture" :capability "JVM process + UDP" :owner "J7"}
   {:ns "yin.repl.serve-connect-wire-test" :reason "JVM WebSocket peers fixture" :capability "JVM WebSocket peers" :owner "J6"}
   {:ns "yin.repl.build-test" :reason "JVM build tool fixture" :capability "JVM build tool" :owner "J8"}
   {:ns "yin.repl.host.jvm-test" :reason "JDK host fixture" :capability "JDK host" :owner "J6"}
   {:ns "yin.repl.host.node-test" :reason "Node host fixture" :capability "Node host" :owner "J6"}
   {:ns "dao.host.gate-test"
    :reason "the H0 freeze gate reads src text via tools.reader; its own docstring says JVM lane only"
    :capability "source-text gate (tools.reader)" :owner "host-boundary seat"}
   {:ns "yin.vm.linker.layering-test"
    :reason "same gate class: reads src ns forms via tools.reader, JVM only"
    :capability "source-text gate (tools.reader)" :owner "host-boundary seat"}
   {:ns "yin.vm.store-write-audit-test"
    :reason "same gate class: reads every src file as forms via tools.reader"
    :capability "source-text gate (tools.reader)" :owner "host-boundary seat"}
   {:ns "dao.stream.transit-test"
    :reason "the transit codec itself; no transit library on jolt, CBOR profile only (design 9.4)"
    :capability "transit codec" :owner "jolt seat (design 9.4 ruling)"}
   {:ns "dao.stream.codec-test"
    :reason "requires dao.stream.transit" :capability "transit codec"
    :owner "jolt seat (design 9.4 ruling)"}
   {:ns "dao.stream.codec-fixtures-test"
    :reason "requires dao.stream.transit" :capability "transit codec"
    :owner "jolt seat (design 9.4 ruling)"}
   {:ns "dao.stream.ws-codec-test"
    :reason "requires dao.stream.transit" :capability "transit codec"
    :owner "jolt seat (design 9.4 ruling)"}
   {:ns "dao.stream.remote-channel-test"
    :reason "requires dao.stream.transit and dao.stream.ws"
    :capability "transit codec + ws sockets" :owner "J6/J7"}
   {:ns "dao.stream.remote-route-test"
    :reason "requires dao.stream.transit" :capability "transit codec"
    :owner "jolt seat (design 9.4 ruling)"}
   {:ns "dao.stream.ws-test"
    :reason "requires dao.stream.transit" :capability "transit codec"
    :owner "jolt seat (design 9.4 ruling)"}
   {:ns "dao.stream.ws-project-test"
    :reason "requires dao.stream.transit" :capability "transit codec"
    :owner "jolt seat (design 9.4 ruling)"}
   {:ns "dao.stream.ws.jvm-test"
    :reason "JVM websocket socket lane" :capability "ws sockets" :owner "J6/J7"}
   {:ns "dao.stream.ws.node-test"
    :reason "Node websocket lane (cljs)" :capability "ws sockets + Node peer" :owner "J6/J7"}
   {:ns "dao.stream.ws.dart-test"
    :reason "Dart websocket lane (cljd)" :capability "ws sockets + Dart peer" :owner "J6/J7"}
   {:ns "dao.stream.ws.browser-test"
    :reason "browser websocket lane (cljs)" :capability "ws sockets + browser" :owner "J6/J7"}
   {:ns "dao.stream.ws-project-cross-jvm-test"
    :reason "cross-host ws peer suite" :capability "ws sockets + peer" :owner "J6/J7 (J2b oracle)"}
   {:ns "dao.stream.ws-project-cross-node-test"
    :reason "cross-host ws peer suite (Node)" :capability "ws sockets + Node peer"
    :owner "J6/J7 (J2b oracle)"}
   {:ns "dao.stream.ws-project-jvm-test"
    :reason "cross-host ws peer suite (JVM)" :capability "ws sockets + peer" :owner "J6/J7 (J2b oracle)"}
   {:ns "dao.stream.udp-test" :reason "UDP sockets" :capability "udp sockets" :owner "J6/J7"}
   {:ns "dao.stream.udp-raw-test" :reason "UDP sockets" :capability "udp sockets" :owner "J6/J7"}
   {:ns "dao.stream.datagram.jvm-test"
    :reason "JVM datagram socket seam" :capability "udp sockets" :owner "J6/J7"}
   {:ns "dao.stream.datagram.node-test"
    :reason "Node datagram lane (cljs)" :capability "udp sockets + Node" :owner "J6/J7"}
   {:ns "dao.stream.datagram.dart-test"
    :reason "Dart datagram lane (cljd)" :capability "udp sockets + Dart" :owner "J6/J7"}
   {:ns "dao.jing.stream-test"
    :reason "requires dao.stream.transit (missed by the round-1 manifest; found by the round-2 load evidence)"
    :capability "transit codec" :owner "jolt seat (design 9.4 ruling)"}
   {:ns "dao.jing.dht.socket-test"
    :reason "binds real datagram sockets" :capability "udp sockets" :owner "J6/J7"}
   {:ns "yin.vm.ffi.remote-serve-test"
    :reason "the FFI remote serve suite" :capability "FFI-remote" :owner "J7"}
   {:ns "yin.vm.ffi.remote-serve.responder-test"
    :reason "the FFI remote responder suite" :capability "FFI-remote" :owner "J7"}
   {:ns "yin.vm.ucf.stage-e-process-test"
    :reason "spawns child processes" :capability "child processes" :owner "J7"}
   {:ns "yin.vm.linker.cross-host-transfer-test"
    :reason "cross-host transfer over dao.stream.transit + remote peers"
    :capability "transit codec + peer" :owner "J2b + J6/J7"}
   {:ns "dao.space.dht-test"
    :reason "socket behaviour on jolt is unproven; deferred to J6/J7"
    :capability "udp datagram host seam (java.net.DatagramSocket)" :owner "J6/J7"}
   {:ns "yin.vm.linker.dht-test"
    :reason "socket behaviour on jolt is unproven; deferred to J6/J7"
    :capability "udp datagram host seam" :owner "J6/J7"}
   {:ns "yin.vm.linker.dht-end-to-end-test"
    :reason "socket behaviour on jolt is unproven; deferred to J6/J7"
    :capability "udp datagram host seam + yin.repl" :owner "J6/J7 + J3"}
   {:ns "yin.vm.linker.head-board-test"
    :reason "socket behaviour on jolt is unproven; deferred to J6/J7"
    :capability "udp datagram host seam" :owner "J6/J7"}
   {:ns "yin.vm.linker.head-follow-test"
    :reason "socket behaviour on jolt is unproven; deferred to J6/J7"
    :capability "udp datagram host seam" :owner "J6/J7"}
   {:ns "dao.stream.journal.file-test"
    :reason "durable journal fixture requires FileChannel.tryLock"
    :capability "file lock (tryLock)" :owner "J4"}
   {:ns "yin.vm.ucf.durability-test"
    :reason "durable journal fixture requires FileChannel.tryLock"
    :capability "file lock (tryLock)" :owner "J4"}
   {:ns "yin.vm.ucf.stage-e-rows-test"
    :reason "durable journal fixture requires FileChannel.tryLock"
    :capability "file lock (tryLock)" :owner "J4"}
   {:ns "yin.vm.ucf.ledger-fixture-test"
    :reason "durable journal fixture requires FileChannel.tryLock"
    :capability "file lock (tryLock)" :owner "J4"}
   {:ns "yin.vm.ucf.compose-rows-test"
    :reason "durable journal fixture requires FileChannel.tryLock"
    :capability "file lock (tryLock)" :owner "J4"}
   {:ns "yin.vm.ucf.compose-test"
    :reason "durable journal fixture requires FileChannel.tryLock"
    :capability "file lock (tryLock)" :owner "J4"}
   {:ns "dao.space.store.fs-test"
    :reason "UTF-8 decoder fails and dangling symlink returns absence instead of refusal"
    :capability "UTF-8 decoder + symlink refusal" :owner "J4"}])


;; Dependency order (the max rank of each suite's source requires), so a low
;; failure is reported before the suites that ride on it.
(def included
  ["yin.repl-test" "yin.repl.owner-test" "yin.repl.state-test" "yin.repl.index-test" "yin.repl.query-test" "yin.repl.ast-index-test" "yin.repl.ast-query-e2e-test" "yin.repl.driver-test" "yin.repl.adapter-test" "yin.repl.embed-test" "yin.repl.trust-test" "yin.repl.require-test" "yin.repl.frontend-catalog-test" "yin.repl.main-test" "yin.repl.connect-test" "yin.repl.serve-test" "yin.repl.store-test"
   "dao.jing.dht.facade-test" "dao.stream.waitset.driver-test"
   "yin.vm.linker.production-pipeline-test"
   "dao.data.arrays-test" "dao.data.sorted-user-type-test"
   "dao.host.bytes-test" "dao.host.text-test"
   "dao.jing.dht.kad-test" "dao.stream-test"
   "dao.stream.memory-log-test" "dao.stream.waitset.cadence-test" "dao.data-test"
   "dao.data.btree-protocols-test" "dao.data.btree-test" "dao.data.btree-transients-test"
   "dao.host.float-test" "dao.stream.apply-test" "dao.stream.cbor-test"
   "dao.stream.chunks-test" "dao.stream.middleware-test" "dao.stream.observe-test"
   "dao.stream.ringbuffer-test" "dao.stream.waitset-test" "dao.host.conformance-test"
   "dao.jing.cbor-content-equality-test" "dao.stream.datagram-test"
   "dao.stream.forward-test" "dao.stream.observer-test" "dao.stream.remote-meet-test"
   "dao.stream.remote-pair-test" "dao.stream.remote-test" "dao.stream.rpc-test"
   "dao.jing-test" "dao.jing.cbor-conformance-test" corpus-suite-name
   "dao.jing.cbor-test" "dao.jing.hash-registry-contract-test" "dao.stream.base64-test"
   "dao.stream.journal-test" "dao.data.btree-durability-test" "dao.host.hash-test"
   "dao.jing.content.step-test" "dao.jing.dht-test"
   "dao.jing.mem-test"
   ;; Rejoined (J4a): excluded with capability "file random access
   ;; (writeInt)" (owner J4) until dao.jing.file's :jolt arms reached
   ;; native/jolt-file through dao.host.file.
   "dao.jing.file-test" "yin.vm.linker-authority-test"
   "yin.vm.linker.sign-test" "dao.data.btree-async-test" "dao.jing.content-test"
   "dao.space.index-numeric-test"
   "dao.space.transactor-test" "dao.space.index-test" "dao.space.positional-query-test"
   "dao.space.query-numeric-test" "dao.space.schema-test"
   "dao.space.transact-test" "yin.vm.bytes-to-str-test" "yin.vm.debruijn-code-test"
   "yin.vm.linker-authority-ingestion-test" "yin.vm.parity-test" "yin.vm.ast-walker-test"
   "yin.vm.module-test" "yin.vm.pipeline-test" "yin.vm.semantic.code-test"
   "yin.vm.semantic.load-paths-test" "dao.space.query-test"
   "dao.space.stigmergy-test" ; rejoined (J4a)
   ;; Listed 2026-10-25: added by 3d77ed52 without a manifest entry, which
   ;; made check-manifest! refuse every lane mode; 17 tests, 57 assertions,
   ;; 0 failures on jolt (macOS).
   "dao.space.datomic-query-conformance-test"
   "yin.vm.ledger-test"
   "yin.vm.semantic.analysis-test" "yin.vm.debruijn-register-code-test"
   "yin.vm.semantic.scan-test" "yin.vm.debruijn-register-effects-test"
   "yin.vm.debruijn-test" "yin.vm.semantic.frame-test" "yin.vm-test"
   "yin.vm.attach-image-test" "yin.vm.cell-test" "yin.vm.continuation-invoke-test"
   "yin.vm.data-test" "yin.vm.debruijn.register-test" "yin.vm.debruijn.stack-effects-test"
   "yin.vm.debruijn.stack-parity-test" "yin.vm.debruijn.stack-tail-completion-test"
   "yin.vm.debruijn.stack-test" "yin.vm.effect-forgery-test" "yin.vm.encoder-test"
   "yin.vm.engine-gate-test" "yin.vm.engine-test" "yin.vm.ffi-test"
   "yin.vm.heap-reclamation-test" "yin.vm.integer-test" "yin.vm.linker-manifest-test"
   "yin.vm.linker-step-test" "yin.vm.linker-test" "yin.vm.linker.closure-test"
   "yin.vm.linker.remote-transfer-test" "yin.vm.macro-test"
   "yin.vm.macro-build-test" ; rejoined (J4a)
   "yin.vm.mint-test" "yin.vm.rule-r-test"
   ;; Rejoined 2026-10-24: excluded with capability "transit codec via
   ;; yin.repl" (owner J3) until J3 landed (0c2bb870/8b0fbb60); on jolt
   ;; yin.repl.connect now takes its `:jolt []` arm, so yin.repl no longer
   ;; requires dao.stream.transit.
   "yin.vm.linker-require-test" "yin.vm.linker.head-test"
   "yin.vm.linker.publish-test" "yin.vm.linker.admission-test"
   ;; Rejoined 2026-10-24 on the fork re-pin b389f1a7 (jolt-toolchain.edn):
   ;; jolt-lang's assoc on a non-Associative deftype now throws as on the JVM,
   ;; so the suite needs no yin/vm.cljc arm.
   "yin.vm.host-typed-values-test"
   "yin.vm.semantic-engine-test" "yin.vm.semantic-ffi-test"
   "yin.vm.semantic-stream-observer-test" "yin.vm.semantic.effects-test"
   "yin.vm.semantic.linearize-test" "yin.vm.semantic.link-test"
   "yin.vm.semantic.parity-test" "yin.vm.semantic.vm-test"
   "yin.vm.semantic.walker-trace-test" "yin.vm.stream-poll-test"
   "yin.vm.telemetry-test" "yin.vm.ucf.remote-test" "yin.vm.ucf.rows-custody-test"
   "yin.vm.ucf.rows-test" "yin.vm.semantic.layout-test"
   "yin.vm.semantic.safepoint-harness-test" "yin.vm.ucf.rows-checkpoint-test"
   "yin.vm.linker.admission-doc-test" "yin.vm.semantic.derive-test"
   "yin.vm.ucf.holder.export-test" "yin.vm.ucf.authority.grant-test"
   "yin.vm.ucf.authority.completion-fold-test" "yin.vm.ucf.authority.reclaim-test"
   "yin.vm.ucf.crash-cut-test" "yin.vm.ucf.custody-test"
   "yin.vm.ucf.ledger-test" "yin.vm.ucf.authority-test"
   "yin.vm.ucf.authority.admission-test" "yin.vm.ucf.authority.completion-test"
   "yin.vm.ucf.authority.inherited-test" "yin.vm.ucf.authority.input-test"
   "yin.vm.ucf.holder.evidence-test"
   "yin.vm.ucf.authority.front-test"
   "yin.vm.ucf.fencing-rows-test" "yin.vm.ucf.holder.reader-test"
   "yin.vm.ucf.holder.writer-test" "yin.vm.ucf.holder.driver-test"])


;; Read only namespace tokens, never evaluate forms or resolve reader branches.
;; Include host-only extensions too: existing exclusions name Node/Dart suites.
(defn check-manifest!
  "Check the applicability manifest against a test root; throw with every
   unlisted namespace (and path) and stale entry before any lane work."
  [test-root included-nses excluded-suites]
  (let [dirs ["dao/host" "dao/jing" "dao/data" "dao/space" "dao/stream" "yin/vm" "yin/repl"]
        bare ["dao/jing_test" "dao/stream_test" "dao/data_test" "yin/vm_test" "yin/repl_test"]
        extensions ["clj" "cljc" "cljs" "cljd"]
        files (->> (concat
                     (mapcat #(->> (fs/glob (fs/path test-root %) "**")
                                   (filter (fn [file]
                                             (and (fs/regular-file? file)
                                                  (re-find #"_test\.(clj|cljc|cljs|cljd)$"
                                                           (str file)))))) dirs)
                     (for [stem bare ext extensions
                           :let [file (fs/path test-root (str stem "." ext))]
                           :when (fs/regular-file? file)] file))
                   (sort-by str))
        inventory (reduce (fn [acc file]
                            (if-let [[_ ns-name] (re-find #"\(ns\s+([^\s()\[\]{}\";]+)" (slurp (str file)))]
                              (update acc ns-name (fnil conj []) (str file))
                              (throw (ex-info (str "J2 manifest: no ns form in " file)
                                              {:file (str file)}))))
                          (sorted-map) files)
        listed (set (concat included-nses (map :ns excluded-suites)))
        unlisted (remove #(contains? listed %) (keys inventory))
        stale (sort (remove #(contains? inventory %) listed))]
    (when (or (seq unlisted) (seq stale))
      (throw (ex-info
               (str/join "\n"
                         (concat ["J2 manifest FAILED:"]
                                 (for [ns-name unlisted]
                                   (str "UNLISTED " ns-name " -- "
                                        (str/join ", " (get inventory ns-name))))
                                 (for [ns-name stale]
                                   (str "STALE " ns-name " -- no test file"))))
               {:unlisted (select-keys inventory unlisted) :stale (vec stale)})))
    {:namespaces (count inventory) :files (count files)}))


;; ---------------------------------------------------------------------------
;; children
;; ---------------------------------------------------------------------------


;; Where every log and summary goes, and the runner core mode hands each
;; namespace to. The environment overrides exist for the timeout self-check
;; (src/dev/jolt_j2_timeout_check.clj), which must not touch build/j2-logs.
(def logs
  (or (System/getenv "JOLT_J2_LOGS") "build/j2-logs"))


(def core-runner
  (or (System/getenv "JOLT_J2_CORE_RUNNER") "src/dev/jolt_test_runner.clj"))


(defn- dash
  [ns]
  (str/replace ns #"[./]" "_"))


(defn- kill-tree!
  "Kill child process `proc` and every process below it: SIGTERM to the
   whole tree, then SIGKILL to whatever is still alive after `grace-ms`.
   The descendants are listed BEFORE any signal, since a child whose parent
   dies is re-parented to init and drops out of the tree."
  [proc grace-ms]
  (let [root (.toHandle ^Process (:proc proc))
        tree (conj (vec (iterator-seq (.iterator (.descendants root)))) root)
        alive #(filterv (fn [h] (.isAlive h)) tree)
        await-tree (fn []
                     (loop [n (quot grace-ms 100)]
                       (when (and (pos? n) (seq (alive)))
                         (Thread/sleep 100)
                         (recur (dec n)))))]
    (run! #(.destroy %) tree)
    (await-tree)
    (run! #(.destroyForcibly %) (alive))
    (await-tree)
    (mapv #(.pid %) (alive))))


(defn run-watched
  "Run `cmd` (an argv vector) as a child, waiting at most `timeout-ms` (nil:
   no limit); answers {:exit :timed-out :survivors}. Never throws.

   The deadline is this driver's own watchdog, not p/shell's :timeout:
   babashka.process has no such option and ignores it silently, which let a
   namespace run 5+ hours past --timeout-ms 3600000 on shiva. On the
   deadline the whole process tree is killed (kill-tree!), so a launcher
   shell or a test's own subprocesses cannot keep the step alive."
  [cmd timeout-ms]
  (let [proc (try (apply p/process {:continue true} cmd)
                  (catch Exception _ nil))
        r (cond (nil? proc) nil
                timeout-ms (deref proc timeout-ms ::timeout)
                :else (deref proc))
        timed-out (= ::timeout r)
        survivors (when timed-out (kill-tree! proc 5000))]
    {:exit (cond (nil? proc) -1
                 timed-out :timed-out
                 :else (or (:exit r) -1))
     :timed-out timed-out
     :survivors survivors}))


(defn- run-jolt
  "Run one child jolt step. `script` and `args` run under
   -M:jolt:jolt-test (:jolt-diag added when `diag?`), stdout+stderr to `log`;
   answers {:exit :out :timed-out :survivors} (run-watched). Never throws."
  [{:keys [script args log diag? timeout-ms]}]
  (fs/create-dirs (fs/parent log))
  (let [aliases (if diag? "-M:jolt:jolt-diag:jolt-test" "-M:jolt:jolt-test")
        inner (str ". build/tools/jolt-env.sh && "
                   "exec \"$JOLT_HOME/bin/jolt\" -Srepro " aliases
                   " " script " \"$@\" > " log " 2>&1")
        r (run-watched (into ["sh" "-c" inner "jolt"] args) timeout-ms)]
    (assoc r :out (try (slurp log) (catch Exception _ "")))))


(defn- parse-lines
  "The last line starting with `prefix` in `out`."
  [out prefix]
  (some #(when (str/starts-with? % prefix) %)
        (reverse (str/split-lines out))))


(defn- opts
  [args]
  (loop [{:as acc}
         {:jobs 1 :only nil :timeout-ms nil :diag false}
         [k v & more :as args] args]
    (if (empty? args)
      acc
      (case k
        "--jobs" (recur (assoc acc :jobs (parse-long v)) more)
        "--only" (recur (assoc acc :only (re-pattern v)) more)
        "--timeout-ms" (recur (assoc acc :timeout-ms (parse-long v)) more)
        "--diag" (recur (assoc acc :diag true) (when v (cons v more)))
        (do (println "unknown argument" k) (System/exit 2))))))


(defn- select-nses
  [only coll]
  (if only (filter #(re-find only %) coll) coll))


;; ---------------------------------------------------------------------------
;; probe mode
;; ---------------------------------------------------------------------------


(defn- probe-step
  [timeout-ms ns]
  (let [log (str logs "/probe/" (dash ns) ".log")
        r (run-jolt {:script "src/dev/jolt_j2_probe.clj" :args [ns]
                     :log log :timeout-ms timeout-ms})
        out (:out r)
        probe (or (parse-lines out "PROBE ") "PROBE unknown (no output)")
        added (or (parse-lines out "ADDED ") "ADDED []")
        transit (or (parse-lines out "TRANSIT ") "TRANSIT unknown")
        [_ _ class detail] (str/split probe #" " 4)
        ok? (and (number? (:exit r)) (zero? (:exit r))
                 (= "load-ok" class)
                 (= "TRANSIT absent" transit) (not (:timed-out r)))]
    {:ns ns :ok ok? :class class :detail (or detail "")
     :exit (:exit r) :timed-out (:timed-out r)
     :transit transit :added added :log log
     :line probe}))


(defn- run-parallel
  "Run `f` over `coll` at most `jobs` at a time (chunks of futures, each
   chunk fully deref'd before the next), answering the results in order."
  [jobs f coll]
  (into []
        (mapcat (fn [chunk]
                  (mapv deref (mapv (fn [x] (future (f x))) chunk))))
        (partition-all jobs coll)))


(defn mode-probe
  [args]
  (let [{:keys [jobs only timeout-ms]} (opts args)
        nses (vec (select-nses only probe-order))
        timeout-ms (or timeout-ms 600000)]
    (println "J2 probe:" (count nses) "namespaces, jobs" jobs
             ", timeout" timeout-ms "ms, logs under" (str logs "/probe/"))
    (when (empty? nses) (println "nothing selected") (System/exit 0))
    (let [results (if (= 1 jobs)
                    (mapv (fn [ns] (probe-step timeout-ms ns)) nses)
                    (run-parallel jobs #(probe-step timeout-ms %) nses))
          bad (remove :ok results)]
      (doseq [{:keys [ns ok class detail transit timed-out]} results]
        (println (if ok "ok   " "FAIL ") ns (when-not ok (str "| " class " " detail
                                                              (when timed-out " (timed out)")
                                                              " | " transit))))
      (println "PROBE-SUMMARY" (count nses) "steps," (count bad) "failed")
      (spit (str logs "/probe-summary.edn")
            (pr-str {:steps (count nses)
                     :failed (count bad)
                     :results (mapv #(dissoc % :added) results)}))
      (System/exit (if (empty? bad) 0 1)))))


;; ---------------------------------------------------------------------------
;; core / jvm modes
;; ---------------------------------------------------------------------------


(defn- core-step
  [diag? timeout-ms ns]
  (let [log (str logs "/core/" (dash ns) ".log")
        r (run-jolt {:script core-runner :args [ns]
                     :log log :diag? diag? :timeout-ms timeout-ms})
        out (:out r)
        load-failure (parse-lines out "LOAD-FAILURE ")
        summary (parse-lines out "SUMMARY ")
        counts (when-let [s (some-> summary (subs (count "SUMMARY ")))]
                 (try (edn/read-string s) (catch Exception _ nil)))
        failing (->> (str/split-lines out)
                     (filter #(re-find #"^(FAIL|ERROR|LOAD-FAILURE)" %))
                     (take 5))]
    {:ns ns :exit (:exit r) :timed-out (:timed-out r)
     :status (cond (:timed-out r) :timeout
                   load-failure :load-failure
                   (nil? counts) :no-summary
                   (pos? (+ (:fail counts 0) (:error counts 0)
                            (:load-failures counts 0))) :failed
                   :else :ok)
     :survivors (:survivors r)
     :counts (cond counts counts
                   load-failure {:load-failure load-failure}
                   :else (if (:timed-out r) {:timed-out true} {:no-summary true}))
     :failing failing :log log}))


(defn mode-core
  [args]
  (let [{:keys [jobs only timeout-ms diag]} (opts args)
        nses (vec (select-nses only included))
        timeout-ms (or timeout-ms 3600000)]
    (println "J2 core lane:" (count nses) "namespaces (of" (count included)
             "included)," (count excluded) "excluded with reason, jobs" jobs
             (when diag ", DIAG (jolt-diag alias)"))
    (when (empty? nses) (println "nothing selected") (System/exit 0))
    (let [results (if (= 1 jobs)
                    (mapv (fn [ns] (core-step diag timeout-ms ns)) nses)
                    (run-parallel jobs #(core-step diag timeout-ms %) nses))]
      (println)
      (println "excluded suites (reason | capability | owner):")
      (doseq [{:keys [ns reason capability owner]} excluded]
        (println "  " ns "--" reason "|" capability "|" owner))
      (println)
      (let [tally (fn [k] (reduce + (map #(get-in % [:counts k] 0) results)))
            bad (remove #(= :ok (:status %)) results)
            summary {:namespaces (count nses)
                     :load-failures (count (filter #(= :load-failure (:status %)) results))
                     :timeouts (count (filter #(= :timeout (:status %)) results))
                     :tests (tally :tests) :assertions (tally :assertions)
                     :pass (tally :pass) :fail (tally :fail) :error (tally :error)}]
        (doseq [{:keys [ns counts status survivors]} results]
          (println (case status
                     :ok "ok        " :failed "FAILED    " :load-failure "LOAD-FAIL "
                     :timeout "TIMEOUT   " :no-summary "NO-SUMMARY")
                   ns (pr-str counts)
                   (when (seq survivors) (str "(still alive after kill: " survivors ")"))))
        (println)
        (when (some #(str/starts-with? % "yin.repl") nses)
          (println "J3 per-deftest capability decisions: test/resources/yin/repl/jolt-applicability.edn")
          (doseq [row (:test-exclusions (edn/read-string
                                          (slurp "test/resources/yin/repl/jolt-applicability.edn")))]
            (println "EXCLUDED-TEST" (pr-str row))))
        (println "CORE-SUMMARY" summary)
        (spit (str logs "/core-summary.edn")
              (pr-str {:included (count nses)
                       :excluded excluded
                       :summary summary
                       :results results}))
        (System/exit (if (empty? bad) 0 1))))))


(defn mode-jvm
  [args]
  (let [{:keys [only timeout-ms]} (opts args)
        nses (vec (select-nses only included))]
    (println "J2 JVM counts:" (count nses)
             "namespaces, one JVM process, clojure.test run-tests per namespace"
             "(src/dev/jolt_j2_jvm_runner.clj)"
             (if timeout-ms (str ", timeout " timeout-ms " ms") ", no timeout"))
    (when (empty? nses) (println "nothing selected") (System/exit 0))
    (fs/create-dirs logs)
    (let [cp (-> (p/shell {:out :string} "clojure" "-Spath" "-A:test" "-A:dev")
                 :out str/trim)
          _ (spit (str logs "/jvm-classpath.txt") cp)
          log (str logs "/jvm-run.log")
          r (run-watched
              (into ["sh" "-c"
                     (str "cp=\"$1\"; shift; exec java -cp \"$cp\" clojure.main"
                          " src/dev/jolt_j2_jvm_runner.clj \"$@\" > " log " 2>&1")
                     "jvm" cp]
                    nses)
              timeout-ms)
          out (try (slurp log) (catch Exception _ ""))
          ;; one NS line per namespace: NS <ns> tests N assertions M pass P
          ;; fail F error E; one LOAD-FAILURE <ns> -- <message> line per
          ;; namespace that did not load
          per-ns (into {}
                       (for [line (str/split-lines out)
                             :when (or (str/starts-with? line "NS ")
                                       (str/starts-with? line "LOAD-FAILURE "))
                             :let [parts (zipmap (iterate inc 1) (str/split line #"\s+"))]]
                         [(get parts 2)
                          (if (= "NS" (get parts 1))
                            {:tests (parse-long (get parts 4))
                             :assertions (parse-long (get parts 6))
                             :pass (parse-long (get parts 8))
                             :fail (parse-long (get parts 10))
                             :error (parse-long (get parts 12))}
                            {:load-failure line})]))
          load-failures (vec (filter #(str/starts-with? % "LOAD-FAILURE")
                                     (str/split-lines out)))
          summary (when-let [s (parse-lines out "SUMMARY ")]
                    (try (edn/read-string (subs s (count "SUMMARY ")))
                         (catch Exception _ nil)))
          status (cond (:timed-out r) :timeout
                       (nil? summary) :no-summary
                       :else :complete)]
      (println "exit" (:exit r) "status" status
               "namespaces reported" (count per-ns) "of" (count nses)
               "load failures" (count load-failures))
      (doseq [l load-failures] (println " " l))
      (doseq [ns nses
              :let [c (get per-ns ns)]]
        (println (cond (nil? c) "MISSING   "
                       (:load-failure c) "LOAD-FAIL "
                       (pos? (+ (:fail c) (:error c))) "FAILED    "
                       :else "ok        ")
                 ns (pr-str c)))
      (when summary (println "SUMMARY" (pr-str summary)))
      (spit (str logs "/jvm-summary.edn")
            (pr-str {:namespaces (count nses)
                     :status status
                     :exit (:exit r)
                     :per-ns per-ns
                     :load-failures load-failures
                     :summary summary
                     :log log}))
      ;; the baseline is information, not a gate: JVM failures do not fail
      ;; this step, an incomplete run does
      (System/exit (if (= :complete status) 0 1)))))


(defn mode-report
  [_]
  (let [core (try (edn/read-string (slurp (str logs "/core-summary.edn")))
                  (catch Exception _ nil))
        jvm (try (edn/read-string (slurp (str logs "/jvm-summary.edn")))
                 (catch Exception _ nil))]
    (when-not core (println "no core summary") (System/exit 2))
    (let [jvm-per-ns (:per-ns jvm)
          cmp (fn [c j]
                (let [k [:tests :assertions :pass :fail :error]]
                  (= (select-keys c k) (select-keys (or j {}) k))))
          rows (for [{:keys [ns counts status]} (:results core)]
                 [ns (select-keys counts [:tests :assertions :pass :fail :error])
                  (get jvm-per-ns ns)
                  (case status
                    :load-failure :jolt-load-failure
                    :timeout :jolt-timeout
                    :no-summary :jolt-no-summary
                    (if (get jvm-per-ns ns)
                      (if (cmp (select-keys counts [:tests :assertions :pass :fail :error])
                               (get jvm-per-ns ns))
                        :same :differs)
                      :no-jvm-row))])]
      (println "namespace | jolt (tests/assertions/pass/fail/error) | jvm | verdict")
      (doseq [[ns c j v] rows]
        (println (name v) ns "jolt" (pr-str c) "jvm" (pr-str j)))
      (println)
      (println "jvm summary:" (pr-str (:summary jvm)))
      (println "core summary:" (pr-str (:summary core))))))


(defn mode-list
  [_]
  (println "probe order (" (count probe-order) "):")
  (run! #(print % " ") probe-order)
  (println)
  (println)
  (println "included (" (count included) "), excluded (" (count excluded) "):")
  (doseq [{:keys [ns reason capability owner]} excluded]
    (println "  " ns "--" reason "|" capability "|" owner)))


(defn -main
  [& args]
  (let [[mode & more] args]
    (when (contains? #{"list" "core" "probe" "jvm"} mode)
      (try
        (println "J2 manifest OK:" (pr-str (check-manifest! "test" included excluded)))
        (println "Jolt CBOR override OK:"
                 (pr-str ((resolve 'jolt-cbor-override-check/check!) false)))
        (catch clojure.lang.ExceptionInfo e
          (binding [*out* *err*] (println (ex-message e)))
          (System/exit 1))))
    (case mode
      "probe" (mode-probe more)
      "core" (mode-core more)
      "jvm" (mode-jvm more)
      "list" (mode-list more)
      "report" (mode-report more)
      (do (println "unknown mode" mode) (System/exit 2)))))


;; Run only as the script, so the timeout self-check can load this file.
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
