(ns yin.vm.debruijn.stack-effects-test
  "B4 (docs/design/yin.vm.debruijn.stack.md, 'B4: effects and
   continuations'): completion tests for the engine seam of
   `yin.vm.debruijn.stack` -- streams, primitives as effects, FFI, gensym,
   `:current-continuation`, `:park`, `:resume` -- and for the three
   additive engine edits `yin.vm.engine.md` section 7 gives B4.

   Two kinds of program run here. Hand-built instruction vectors, as the
   B3 test builds them, pin instruction-level behaviour: the stack layout
   each effect opcode reads, the shape of a parked entry, the refusals.
   Parity fixtures take one named AST through both lowerers -- `adapt`
   onto this VM, `yin.vm.linearize` onto the semantic VM, B4's parity
   oracle -- on fresh instances every time (D4), and compare under B0's
   normalizer (`yin.vm.debruijn-vm-contract-test/normalize`), reused rather
   than reimplemented."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [yin.vm :as v2]
            [yin.vm.debruijn-code :as dc]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-vm-contract-test :as b0]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; Helpers
;; =============================================================================

(defn- throws?
  [thunk]
  (try (thunk) false
       (catch #?(:clj Exception :cljs js/Error :cljd Object) _ true)))


(defn- throws-ex-data
  [thunk]
  (try (thunk) nil
       (catch #?(:clj Exception :cljs js/Error :cljd Object) e
         (or (ex-data e) {}))))


(defn- caught
  "The B0 error normalization of what `thunk` throws, or the normalized
   value it returns."
  [thunk]
  (try (b0/normalize (thunk))
       (catch #?(:clj Exception :cljs js/Error :cljd Object) e
         (b0/normalize-error e))))


(defn- make-vm
  "A fresh de Bruijn VM over `segment`, wired to the ring buffer and the
   standard primitive registry as the semantic VM's own defaults are."
  ([segment] (make-vm segment {}))
  ([segment opts]
   (dvm/create-vm segment
                  (merge {:make-stream tu/make-stream,
                          :primitives v2/primitives}
                         opts))))


(defn- run-segment
  ([segment] (run-segment segment {}))
  ([segment opts] (v2/run (make-vm segment opts))))


(def ^:private load-ast (linearize/ast-loader semantic/vm-load-program))


(defn- semantic-run
  "Run `ast` on a fresh semantic VM, B4's parity oracle."
  [ast]
  (v2/run (load-ast (semantic/create-vm {:make-stream tu/make-stream})
                    (v2/ast->datoms ast))))


(defn- debruijn-image
  [ast]
  (:image (dl/adapt (v2/ast->datoms ast))))


(defn- debruijn-run
  "Run `ast` through `adapt` on a fresh de Bruijn VM."
  [ast]
  (run-segment (debruijn-image ast)))


(defn- lit
  [v]
  {:type :literal, :value v})


(defn- variable
  [n]
  {:type :variable, :name n})


(defn- lambda
  [params body]
  {:type :lambda, :params params, :body body})


(defn- app
  [f & args]
  {:type :application, :operator f, :operands (vec args)})


(defn- let1
  "`((fn [param] body) init)`."
  [param init body]
  (app (lambda [param] body) init))


(defn- read-first-ast
  "Make a stream of `capacity`, mint a cursor on it, and read one value."
  [capacity]
  (let1 's
        {:type :stream/make, :buffer capacity}
        {:type :stream/next,
         :source {:type :stream/cursor, :source (variable 's)}}))


(def ^:private read-first-segment
  "`read-first-ast` by hand: the stream ref is consumed by :stream-cursor,
   the cursor ref by :stream-next, each leaving its result on the stack."
  [[:stream-make 4]    ; 0: stream ref
   [:stream-cursor]    ; 1: cursor ref
   [:stream-next]      ; 2: parks on the empty stream
   [:halt]])           ; 3


(defn- blocked-reader
  "Run `read-first-segment`: the parked VM and the stream handle it reads."
  []
  (let [parked (run-segment read-first-segment)
        entry (first (:wait-set parked))]
    [parked (get (v2/store parked) (:stream-id entry))]))


(def ^:private payload-keys
  [:segment :pc :frames :stack :continuation :format :hash])


(defn- host-value?
  [x]
  (some fn? (tree-seq coll? seq x)))


;; =============================================================================
;; 1. Bookkeeping: the engine's keys replace :status
;; =============================================================================

(deftest bookkeeping-test
  (testing "a fresh VM carries the engine's bookkeeping keys and no :status"
    (let [vm (make-vm [[:const 1] [:halt]])]
      (is (not (contains? vm :status)))
      (is (every? #(contains? vm %)
                  [:blocked? :halted? :wait-set :ready-queue :parked
                   :id-counter :value :make-stream]))
      (is (= (dc/image-hash [[:const 1] [:halt]]) (:hash vm))
          ":hash is the loaded image's H")
      (is (not (v2/halted? vm)))
      (is (not (v2/blocked? vm)))))
  (testing "an empty segment starts halted with an empty program, as the
            semantic VM's create-vm does; load-image loads work into it"
    (let [vm (make-vm [])]
      (is (v2/halted? vm))
      (is (= vm (v2/run vm)) "nothing to run")
      (let [loaded (dvm/load-image vm [[:const 9] [:halt]])]
        (is (not (v2/halted? loaded)))
        (is (= 9 (v2/value (v2/run loaded)))))))
  (testing ":halt and a :return on an empty continuation write the stack
            top into :value, which `value` reads"
    (is (= 3 (v2/value (run-segment [[:const 3] [:halt]]))))
    (is (= 4 (v2/value (run-segment [[:closure 0 3] [:call 0 false] [:halt]
                                     [:const 4] [:return]]))))
    (is (= 5 (v2/value (run-segment [[:const 5] [:return]]))))))


;; =============================================================================
;; 2. Streams: make, put, cursor, next, close
;; =============================================================================

(deftest stream-make-put-next-round-trip-test
  (testing "make, def it as s, put 7, then read it back through a cursor"
    (let [first-run (run-segment [[:load-free 'yin/def] ; 0: def
                                  [:const 's]           ; 1: def s
                                  [:stream-make 4]      ; 2: def s sref
                                  [:call 2 false]       ; 3: sref (store s = sref)
                                  [:push]               ; 4
                                  [:const 7]            ; 5: sref 7
                                  [:stream-put]         ; 6: 7 (the appended value)
                                  [:halt]])             ; 7
          sref (get (v2/store first-run) 's)]
      (is (= 7 (v2/value first-run)) "put yields the appended value")
      (is (= [7] (:stack first-run)) "the target ref and the value were popped")
      (is (= :stream-ref (:type sref)))
      ;; A second program over the same store reads it back.
      (is (= 7 (v2/value (run-segment [[:load-free 's]     ; 0: s
                                       [:stream-cursor]    ; 1: cursor ref
                                       [:stream-next]      ; 2: 7
                                       [:halt]]            ; 3
                                      {:store (v2/store first-run)}))))))
  (testing "the :stream-put layout is the value on top and the target ref
            beneath it, both popped -- `lower-stack` emits target, push,
            value, stream-put"
    (let [done (run-segment [[:stream-make 4] [:push] [:const :v] [:stream-put]
                             [:halt]])]
      (is (= :v (v2/value done)))
      (is (= [:v] (:stack done)) "only the put's value remains"))))


(deftest stream-close-and-end-test
  (testing "close yields nil; a read at the end of a closed stream is nil"
    (let [[parked handle] (blocked-reader)]
      (stream/close! handle)
      (let [done (v2/run parked)]
        (is (v2/halted? done))
        (is (nil? (v2/value done))))))
  (testing ":stream-close consumes the ref and leaves nil on the stack"
    (let [done (run-segment [[:stream-make 4] [:stream-close] [:halt]])]
      (is (nil? (v2/value done)))
      (is (= [nil] (:stack done))))))


(deftest gap-is-a-value-the-program-sees-test
  (let [[parked handle] (let [parked (run-segment [[:stream-make 2]
                                                   [:stream-cursor]
                                                   [:stream-next]
                                                   [:halt]])]
                          [parked (get (v2/store parked)
                                       (:stream-id (first (:wait-set parked))))])]
    (dotimes [n 5] (stream/append! handle n))
    (is (= :dao.stream/gap (v2/value (v2/run parked))))))


(deftest stream-errors-name-their-outcome-test
  (testing "put on an unknown stream reference"
    (is (= {:message "Invalid stream reference",
            :data {:ref {:type :stream-ref, :id :nope}}}
           (caught #(run-segment [[:const {:type :stream-ref, :id :nope}]
                                  [:push] [:const 1] [:stream-put] [:halt]])))))
  (testing "put on a closed stream"
    (let [[parked handle] (blocked-reader)
          sref {:type :stream-ref, :id (:stream-id (first (:wait-set parked)))}]
      (stream/close! handle)
      (is (= "Stream append failed"
             (:message (caught #(run-segment [[:const sref] [:push] [:const 1]
                                              [:stream-put] [:halt]]
                                             {:store (v2/store parked)}))))))))


;; =============================================================================
;; 3. Blocking and waking through the seam
;; =============================================================================

(deftest an-empty-stream-parks-the-reader-test
  (let [[parked _] (blocked-reader)
        entry (first (:wait-set parked))]
    (testing "the reader parks with one polling wait entry"
      (is (v2/blocked? parked))
      (is (not (v2/halted? parked)))
      (is (= :yin/blocked (v2/value parked)))
      (is (= 1 (count (:wait-set parked))))
      (is (= :next (:reason entry)))
      (is (= :cursor-ref (get-in entry [:cursor-ref :type]))))
    (testing "the entry is this machine's register payload after the
              instruction, tagged with its model and image, and nothing
              else live"
      (is (= 3 (:pc entry)) "pc advanced past :stream-next")
      (is (= [] (:stack entry)) "the cursor ref was popped")
      (is (= [] (:frames entry)))
      (is (= [] (:continuation entry)))
      (is (= read-first-segment (:segment entry)))
      (is (= :yin.debruijn.code (:format entry)))
      (is (= (:hash parked) (:hash entry)))
      (is (not (contains? entry :resume)))
      (is (not (contains? entry :env)) "no named register leaks in")
      (is (not (host-value? (:wait-set parked))))
      (is (= (:wait-set parked) (edn/read-string (pr-str (:wait-set parked))))
          "the wait set survives an EDN round-trip"))))


(deftest a-value-wakes-the-parked-reader-test
  (let [[parked handle] (blocked-reader)
        cursor-id (get-in (first (:wait-set parked)) [:cursor-ref :id])
        before (get-in parked [:store cursor-id :cursor])]
    (testing "polling without a value leaves the reader parked"
      (is (v2/blocked? (v2/run parked))))
    (stream/append! handle :a)
    (let [done (v2/run parked)]
      (testing "an appended value resumes after :stream-next, on the stack"
        (is (v2/halted? done))
        (is (= :a (v2/value done)))
        (is (= [:a] (:stack done)))
        (is (empty? (:wait-set done)))
        (is (empty? (:ready-queue done))))
      (testing "the stored cursor advances to the returned successor"
        (is (not= before (get-in done [:store cursor-id :cursor])))))))


(deftest a-wait-set-read-back-from-edn-resumes-test
  (let [[parked handle] (blocked-reader)
        revived (assoc parked
                       :wait-set (edn/read-string (pr-str (:wait-set parked))))]
    (stream/append! handle :from-edn)
    (is (= :from-edn (v2/value (v2/run revived))))))


(deftest step-runs-one-scheduler-round-between-continuations-test
  (let [[parked handle] (blocked-reader)]
    (testing "a blocked step polls and stays blocked when nothing woke"
      (is (v2/blocked? (v2/step parked))))
    (stream/append! handle :s)
    (testing "a blocked step restores the woken entry through stack-restore
              (engine/scheduler-round bound to it) and continues from pc 3"
      (let [resumed (v2/step parked)]
        (is (not (v2/blocked? resumed)))
        (is (= 3 (:pc resumed)))
        (is (= [:s] (:stack resumed)))
        (is (= :s (v2/value (v2/step resumed))) "the :halt at pc 3")))))


(defn- scripted-stream
  "A stream handle whose append outcomes are scripted and whose appends
   are recorded; reads always block."
  [outcomes seen]
  (reify
    stream/IDaoStreamReader
    (cursor
      [_ _]
      {:dao.stream/outcome :dao.stream/ok, :dao.stream/cursor :c0})

    (next [_ _] {:dao.stream/outcome :dao.stream/blocked})


    stream/IDaoStreamWriter

    (append!
      [_ v]
      (let [o (first @outcomes)]
        (swap! outcomes rest)
        (swap! seen conj v)
        {:dao.stream/outcome (or o :dao.stream/ok)}))))


(deftest a-full-stream-parks-the-writer-and-retries-test
  (let [outcomes (atom [:dao.stream/full :dao.stream/ok])
        seen (atom [])
        sref {:type :stream-ref, :id :scripted}
        ;; Stepped, not run: `run` would poll the wait set and retry at once.
        parked (nth (iterate v2/step
                             (make-vm [[:const sref] [:push] [:const 7]
                                       [:stream-put] [:push] [:const :after]
                                       [:halt]]
                                      {:store {:scripted (scripted-stream outcomes
                                                                          seen)}}))
                    4)
        entry (first (:wait-set parked))]
    (testing "a full append parks a writer entry carrying the datom to retry"
      (is (v2/blocked? parked))
      (is (= :put (:reason entry)))
      (is (= :scripted (:stream-id entry)))
      (is (= 7 (:datom entry)) "handle-effect stamped the value")
      (is (= 4 (:pc entry)))
      (is (= [] (:stack entry)) "value and target both popped")
      (is (= :yin.debruijn.code (:format entry)))
      (is (= [7] @seen)))
    (testing "the wait set retries the identical append and resumes with
              the put's value on the stack, then runs on past it"
      (let [done (v2/run parked)]
        (is (v2/halted? done))
        (is (= [7 7] @seen))
        (is (= [7 :after] (:stack done)))
        (is (= :after (v2/value done)))))))


;; =============================================================================
;; 4. Primitives as effects
;; =============================================================================

(deftest primitive-effect-descriptors-are-dispatched-test
  (testing "yin/def, a primitive returning a :vm/store-put effect, writes the
            store and yields the value"
    (let [done (run-segment [[:load-free 'yin/def] [:const 'answer] [:const 42]
                             [:call 2 false] [:halt]])]
      (is (= 42 (v2/value done)))
      (is (= 42 (get (v2/store done) 'answer)))))
  (testing "require through the module registry value"
    (let [registry (module/register-module (module/default-registry)
                                           'my.lib
                                           {'answer 42})
          done (run-segment [[:load-free 'require] [:const 'my.lib]
                             [:call 1 false] [:push]
                             [:load-free 'my.lib/answer] [:halt]]
                            {:modules registry})]
      (is (= 42 (v2/value done)))))
  (testing "an unknown effect is an error naming it"
    (let [prims (assoc v2/primitives 'weird (fn [] {:effect :test/nope}))]
      (is (= {:message "Unknown effect", :data {:effect :test/nope}}
             (caught #(run-segment [[:load-free 'weird] [:call 0 false] [:halt]]
                                   {:primitives prims})))))))


(deftest a-blocking-primitive-effect-parks-after-the-call-site-test
  (let [registry (module/register-stream-module (module/default-registry))
        segment [[:load-free 'stream/next!]  ; 0: f
                 [:stream-make 4]            ; 1: f s
                 [:stream-cursor]            ; 2: f c
                 [:call 1 false]             ; 3: (next! c) -> parks
                 [:push]                     ; 4
                 [:const :tail]              ; 5
                 [:halt]]                    ; 6
        parked (run-segment segment {:modules registry})
        entry (first (:wait-set parked))]
    (testing "the module call's effect parks with the continuation after the
              call: f and its argument popped, pc past the :call"
      (is (v2/blocked? parked))
      (is (= :next (:reason entry)))
      (is (= 4 (:pc entry)))
      (is (= [] (:stack entry)))
      (is (not (host-value? (:wait-set parked))) "no primitive fn parked"))
    (stream/append! (get (v2/store parked) (:stream-id entry)) :woken)
    (let [done (v2/run parked)]
      (is (= [:woken :tail] (:stack done)))
      (is (= :tail (v2/value done))))))


;; =============================================================================
;; 5. Gensym
;; =============================================================================

(deftest gensym-test
  (testing "a fresh id from the engine's counter, on the stack"
    (let [done (run-segment [[:gensym "id"] [:halt]])]
      (is (= :id-0 (v2/value done)))
      (is (= 1 (:id-counter done)))))
  (testing "the counter advances per mint, and a :push after each keeps it"
    (is (= :g-2 (v2/value (run-segment [[:gensym "g"] [:push] [:gensym "g"] [:push]
                                        [:gensym "g"] [:halt]])))))
  (testing "deterministic across fresh instances"
    (let [segment [[:gensym "p"] [:push] [:gensym "p"] [:halt]]]
      (is (= (v2/value (run-segment segment)) (v2/value (run-segment segment))
             :p-1))))
  (testing "one counter for every minted id: a stream took :stream-0, so the
            gensym after it is -1"
    (is (= :id-1 (v2/value (run-segment [[:stream-make 4] [:push] [:gensym "id"]
                                         [:halt]]))))))


;; =============================================================================
;; 6. FFI: the two-step
;; =============================================================================

(deftest installed-handlers-round-trip-test
  (testing "a host call is dispatched and its parked continuation resumes"
    (let [done (run-segment [[:const 42] [:ffi-call :op/echo 1] [:halt]]
                            {:bridge {:op/echo identity}})]
      (is (v2/halted? done))
      (is (= 42 (v2/value done)))
      (is (empty? (:parked done)) "the answered call left :parked")))
  (testing "arguments are taken from the stack in order"
    (is (= [1 2 3] (v2/value (run-segment [[:const 1] [:const 2] [:const 3]
                                           [:ffi-call :op/list 3] [:halt]]
                                          {:bridge {:op/list vector}})))))
  (testing "the result feeds the instructions after the call site"
    (is (= 42 (v2/value (run-segment [[:load-free '+] [:const 1] [:const 41]
                                      [:ffi-call :op/echo 1] [:call 2 false]
                                      [:halt]]
                                     {:bridge {:op/echo identity}}))))))


(deftest manual-bridge-step-test
  (let [parked (run-segment [[:const 7] [:ffi-call :op/echo 1] [:halt]])
        entry (first (:wait-set parked))]
    (testing "without handlers the call parks as a call-out reader"
      (is (v2/blocked? parked))
      (is (= :yin/blocked (v2/value parked)))
      (is (= :next (:reason entry)))
      (is (= v2/call-out-stream-key (:stream-id entry)))
      (is (contains? (:parked parked) (:call-id entry)))
      (is (= 2 (:pc entry)))
      (is (= [] (:stack entry)) "the argument was popped")
      (is (= (select-keys (get (:parked parked) (:call-id entry)) payload-keys)
             (select-keys entry payload-keys))
          "the reader carries the parked record's registers")
      (is (= (:wait-set parked) (edn/read-string (pr-str (:wait-set parked))))
          "the response reader is registers and ids, never a closure"))
    (testing "a driver steps the bridge and the call resumes"
      (let [{:keys [handled? vm]} (ffi/bridge-step
                                    (ffi/attach parked {:op/echo identity}))]
        (is (true? handled?))
        (let [done (v2/run vm)]
          (is (v2/halted? done))
          (is (= 7 (v2/value done)))
          (is (empty? (:parked done))))))))


(deftest error-responses-surface-as-errors-test
  (testing "an unknown op is a portable error response that raises on resume"
    (let [parked (run-segment [[:const 1] [:ffi-call :op/missing 1] [:halt]])
          {:keys [vm handled?]} (ffi/bridge-step
                                  (ffi/attach parked {:op/other identity}))]
      (is (true? handled?))
      (is (throws? (fn [] (v2/run vm))))))
  (testing "a response for another call does not resume this one"
    (let [parked (run-segment [[:const 1] [:ffi-call :op/echo 1] [:halt]])
          call-id (:call-id (first (:wait-set parked)))
          call-out (get (v2/store parked) v2/call-out-stream-key)]
      (apply2/put-response! call-out
                            (apply2/success-response [:other call-id] 1))
      (let [data (throws-ex-data (fn [] (v2/run parked)))]
        (is (= call-id (:call-id data)))
        (is (= [:other call-id] (:response-id data)))))))


(defn- gated-call-in
  "A call-in handle delegating to a real stream, whose first `gate-count`
   appends return `full` without reaching the delegate."
  [delegate gate-count]
  (let [gate (atom gate-count)]
    (reify
      stream/IDaoStreamReader
      (cursor [_ anchor] (stream/cursor delegate anchor))

      (next [_ cursor] (stream/next delegate cursor))


      stream/IDaoStreamWriter

      (append!
        [_ v]
        (if (pos? @gate)
          (do (swap! gate dec)
              {:dao.stream/outcome :dao.stream/full})
          (stream/append! delegate v))))))


(deftest full-call-in-retains-the-unsent-request-test
  (let [attempts (atom [])
        call-in (reify
                  stream/IDaoStreamReader
                  (cursor
                    [_ _]
                    {:dao.stream/outcome :dao.stream/ok,
                     :dao.stream/cursor :in-0})

                  (next [_ _] {:dao.stream/outcome :dao.stream/blocked})


                  stream/IDaoStreamWriter

                  (append!
                    [_ v]
                    (swap! attempts conj v)
                    {:dao.stream/outcome :dao.stream/full}))
        parked (run-segment [[:const 5] [:ffi-call :op/echo 1] [:halt]]
                            {:call-in call-in, :call-out (tu/new-stream 8)})
        waiter (first (:wait-set parked))]
    (testing "a full call-in parks the call as a writer retrying its request,
              on this machine's registers"
      (is (v2/blocked? parked))
      (is (= 1 (count (:wait-set parked))))
      (is (= :put (:reason waiter)))
      (is (true? (:request-sent waiter)))
      (is (= :op/echo (:op waiter)))
      (is (= v2/call-in-stream-key (:stream-id waiter)))
      (is (apply2/request? (:datom waiter)))
      (is (= (:call-id waiter) (apply2/request-id (:datom waiter))
             (first (keys (:parked parked))))
          "the retained request keeps the parked id for correlation")
      (is (= [5] (apply2/request-args (:datom waiter))))
      (is (= 2 (:pc waiter)))
      (is (= [] (:stack waiter)))
      (is (= :yin.debruijn.code (:format waiter)))
      (is (= (:hash parked) (:hash waiter))))
    (testing "the polling wait set retries the identical encoded request"
      (is (v2/blocked? (v2/run parked)))
      (is (> (count @attempts) 1) "the wait set retried the append")
      (is (apply = @attempts)))))


(deftest full-request-is-sent-on-retry-and-answered-test
  (let [parked (run-segment [[:const 5] [:ffi-call :op/echo 1] [:halt]]
                            {:call-in (gated-call-in (tu/new-stream 8) 1),
                             :call-out (tu/new-stream 8)})
        reader (first (:wait-set parked))
        record (get (:parked parked) (:call-id reader))]
    (testing "the retry sent the request, and stack-restore re-parked the
              woken writer as the response reader through
              ffi/response-wait-entry: the machine stays blocked"
      (is (v2/blocked? parked))
      (is (= 1 (count (:wait-set parked))))
      (is (= :next (:reason reader)))
      (is (= v2/call-out-stream-key (:stream-id reader)))
      (is (not (contains? reader :request-sent)))
      (is (not (contains? reader :op)))
      (is (not (contains? reader :datom)))
      (is (some? record) "the parked call is still outstanding")
      (is (= (select-keys record payload-keys)
             (select-keys reader payload-keys))
          "the register payload rode through the writer step verbatim"))
    (testing "the answered response resumes it"
      (let [{:keys [handled? request-id vm]}
            (ffi/bridge-step (ffi/attach parked {:op/echo identity}))
            done (v2/run vm)]
        (is (true? handled?))
        (is (= (:call-id reader) request-id))
        (is (v2/halted? done))
        (is (= 5 (v2/value done)))
        (is (empty? (:parked done)))))))


(deftest no-call-pair-test
  (let [bare (dvm/create-vm [[:const 1] [:ffi-call :op/echo 1] [:halt]])]
    (testing "a VM without :make-stream or explicit streams holds no pair"
      (is (nil? (ffi/call-pair (v2/store bare)))))
    (testing "a call fails before park-continuation, stranding nothing"
      (is (throws? (fn [] (v2/run bare))))
      (is (empty? (:parked bare))))))


;; =============================================================================
;; 7. :current-continuation, :park, :resume
;; =============================================================================

(deftest current-continuation-test
  (let [segment [[:const 1] [:push] [:current-continuation] [:halt]]
        done (run-segment segment)
        k (v2/value done)]
    (testing "the continuation after the instruction, as a tagged payload"
      (is (= :reified-continuation (:type k)))
      (is (= 3 (:pc k)))
      (is (= [1] (:stack k)) "the stack before the continuation was pushed")
      (is (= [] (:frames k)))
      (is (= [] (:continuation k)))
      (is (= segment (:segment k)))
      (is (= :yin.debruijn.code (:format k)))
      (is (= (:hash done) (:hash k))))
    (testing "B0 compares it by type only, matching the named VM's tag"
      (is (= {:type :reified-continuation} (b0/normalize k))))))


(def ^:private park-then-resume-segment
  "One image that parks on its first run and, run again from the top,
   resumes its own parked continuation with 42. Only the store carries
   state across the two runs, so this is a same-image park/resume with no
   free-name leak (D4)."
  [[:store-get :ran?]     ; 0
   [:branch-false 5]      ; 1: first run -> 5
   [:const 42]            ; 2
   [:resume :parked-0]    ; 3: deliver 42 to pc 7
   [:halt]                ; 4: (unreached)
   [:store-put :ran? true] ; 5
   [:park]                ; 6: parks {pc 7, stack [true]}
   [:halt]])              ; 7: on resume, value 42


(deftest park-test
  (let [parked (v2/run (make-vm park-then-resume-segment))
        record (v2/value parked)]
    (testing "park halts the machine with the parked record as its value"
      (is (v2/halted? parked))
      (is (not (v2/blocked? parked)))
      (is (= :parked-continuation (:type record)))
      (is (= :parked-0 (:id record)))
      (is (= record (get (:parked parked) :parked-0)))
      (is (= 7 (:pc record)))
      (is (= [true] (:stack record)))
      (is (= :yin.debruijn.code (:format record)))
      (is (= (:hash parked) (:hash record))))
    (testing "the record is pure data"
      (is (= record (edn/read-string (pr-str record)))))
    (testing "B0 compares it by type only"
      (is (= {:type :parked-continuation} (b0/normalize record))))))


(deftest resume-test
  (let [parked (v2/run (make-vm park-then-resume-segment))]
    (testing "a second run of the same image resumes the parked continuation:
              the resume value is conjed onto the parked stack and control
              continues after the park"
      (let [done (v2/run (v2/reset parked))]
        (is (v2/halted? done))
        (is (= 42 (v2/value done)))
        (is (= [true 42] (:stack done)))
        (is (empty? (:parked done)) "the resumed continuation leaves :parked")))
    (testing "the same, through engine/resume-continuation with stack-restore"
      (let [resumed (engine/resume-continuation parked :parked-0 :direct
                                                dvm/stack-restore)]
        (is (= 7 (:pc resumed)))
        (is (= [true :direct] (:stack resumed)))
        (is (= :direct (v2/value (v2/run resumed))))))
    (testing "resuming an unknown parked id is an error"
      (is (thrown-with-msg?
            #?(:clj Exception :cljs js/Error :cljd Object)
            #"not found"
            (run-segment [[:const 1] [:resume :parked-9] [:halt]]))))))


(deftest resume-restores-frames-and-continuation-test
  (testing "a park inside a called closure keeps its frame and return frame,
            and the resumed body returns through them"
    (let [segment [[:store-get :ran?]     ; 0
                   [:branch-false 5]      ; 1
                   [:const :v]            ; 2
                   [:resume :parked-0]    ; 3
                   [:halt]                ; 4
                   [:store-put :ran? true] ; 5
                   [:closure 1 10]        ; 6
                   [:const 10]            ; 7
                   [:call 1 false]        ; 8: body(10), returns to 9
                   [:halt]                ; 9: value 11
                   [:park]                ; 10: parks {frames [[10]], k [ret 9]}
                   [:load-free '+]        ; 11: after resume
                   [:load-bound 0 0]      ; 12: 10
                   [:const 1]             ; 13
                   [:call 2 false]        ; 14: 11
                   [:return]]             ; 15
          parked (v2/run (make-vm segment))
          record (v2/value parked)]
      (is (= [[10]] (:frames record)))
      (is (= 1 (count (:continuation record))))
      (let [done (v2/run (v2/reset parked))]
        (is (= 11 (v2/value done)) "the frame's positional local survived")
        (is (= [true 11] (:stack done))
            "the resume value :v and the return landed on the caller's stack")))))


;; =============================================================================
;; 8. Refusals: same model, same image
;; =============================================================================

(deftest cross-model-continuation-is-refused-test
  (testing "a semantic-VM-shaped parked record carries no :format and is
            refused with :continuation-format, before any register is
            written"
    (let [vm (make-vm [[:const 1] [:resume :parked-0] [:halt]])
          named {:type :parked-continuation, :id :parked-0,
                 :segment -1, :pc 3, :env {}, :stack [7], :k []}
          data (throws-ex-data
                 (fn [] (v2/run (assoc vm :parked {:parked-0 named}))))]
      (is (= :continuation-format (:rule data)))
      (is (nil? (:format data)))
      (is (= :yin.debruijn.code (:expected-format data)))
      (is (= (:hash vm) (:expected-hash data))))))


(deftest mismatched-image-hash-is-refused-test
  (let [parked (v2/run (make-vm park-then-resume-segment))
        other [[:const :other] [:resume :parked-0] [:halt]]
        loaded (dvm/load-image parked other)]
    (testing "load-image keeps the parked record but changes H"
      (is (contains? (:parked loaded) :parked-0))
      (is (not= (:hash parked) (:hash loaded))))
    (testing "resuming a continuation parked under another image is refused"
      (let [data (throws-ex-data (fn [] (v2/run loaded)))]
        (is (= :continuation-format (:rule data)))
        (is (= (:hash parked) (:hash data)))
        (is (= (:hash loaded) (:expected-hash data)))))
    (testing "a woken wait entry of another image is refused the same way
              on its way out of the ready queue"
      (let [[blocked handle] (blocked-reader)
            reloaded (dvm/load-image blocked other)]
        (stream/append! handle :x)
        (is (= :continuation-format
               (:rule (throws-ex-data
                        (fn []
                          (engine/scheduler-round reloaded
                                                  dvm/stack-restore))))))))
    (testing "the rule is checked by stack-restore itself"
      (is (= :continuation-format
             (:rule (throws-ex-data
                      (fn []
                        (dvm/stack-restore parked
                                           {:format :yin.debruijn.code,
                                            :hash "not-this-image",
                                            :pc 0, :stack []}
                                           1)))))))))


;; =============================================================================
;; 9. Parity with the semantic VM, under B0's normalizer
;; =============================================================================

(deftest effect-value-parity-test
  (doseq [[label ast]
          [["stream/make" {:type :stream/make, :buffer 4}]
           ["put yields the value" (let1 's {:type :stream/make, :buffer 4}
                                         {:type :stream/put,
                                          :target (variable 's),
                                          :val (lit 7)})]
           ["close yields nil" (let1 's {:type :stream/make, :buffer 4}
                                     {:type :stream/close, :source (variable 's)})]
           ["cursor" (let1 's {:type :stream/make, :buffer 4}
                           {:type :stream/cursor, :source (variable 's)})]
           ["gensym" {:type :vm/gensym, :prefix "p"}]
           ["two gensyms" (app (lambda '[a] {:type :vm/gensym, :prefix "p"})
                               {:type :vm/gensym, :prefix "p"})]
           ["store put then get" (app (lambda '[ignored] {:type :vm/store-get,
                                                          :key :b4/k})
                                      {:type :vm/store-put, :key :b4/k, :val 5})]
           ["yin/def" (app (variable 'yin/def) (lit 'k) (lit 9))]
           ["current-continuation" {:type :vm/current-continuation}]
           ["park" {:type :vm/park}]
           ["put on an unknown ref" {:type :stream/put,
                                     :target (lit {:type :stream-ref, :id :nope}),
                                     :val (lit 1)}]]]
    (testing label
      (is (= (caught #(v2/value (semantic-run ast)))
             (caught #(v2/value (debruijn-run ast))))))))


(deftest blocked-and-woken-parity-test
  (let [ast (read-first-ast 4)
        named (semantic-run ast)
        db (debruijn-run ast)
        wake (fn [vm value]
               (stream/append! (get (v2/store vm)
                                    (:stream-id (first (:wait-set vm))))
                               value)
               (b0/normalize (v2/value (v2/run vm))))]
    (testing "both block on the empty stream with the same outcome"
      (is (v2/blocked? named))
      (is (v2/blocked? db))
      (is (= (b0/normalize (v2/value named)) (b0/normalize (v2/value db))
             :yin/blocked)))
    (testing "both wake to the same value"
      (is (= (wake named :a) (wake db :a) :a)))))


(deftest end-and-gap-parity-test
  (testing "a closed stream ends with nil on both"
    (let [ast (read-first-ast 4)
          close-and-run (fn [vm]
                          (stream/close! (get (v2/store vm)
                                              (:stream-id (first (:wait-set vm)))))
                          (b0/normalize (v2/value (v2/run vm))))]
      (is (= (close-and-run (semantic-run ast)) (close-and-run (debruijn-run ast))
             nil))))
  (testing "eviction surfaces as :dao.stream/gap on both"
    (let [ast (read-first-ast 2)
          overrun (fn [vm]
                    (let [handle (get (v2/store vm)
                                      (:stream-id (first (:wait-set vm))))]
                      (dotimes [n 5] (stream/append! handle n))
                      (b0/normalize (v2/value (v2/run vm)))))]
      (is (= (overrun (semantic-run ast)) (overrun (debruijn-run ast))
             :dao.stream/gap)))))


(deftest ffi-parity-test
  (let [ast {:type :dao.stream.apply/call,
             :op :op/echo,
             :operands [(lit 42)]}
        named (v2/run (load-ast (semantic/create-vm {:make-stream tu/make-stream,
                                                     :bridge {:op/echo identity}})
                                (v2/ast->datoms ast)))
        db (run-segment (debruijn-image ast) {:bridge {:op/echo identity}})]
    (is (= (b0/normalize (v2/value named)) (b0/normalize (v2/value db)) 42))
    (is (= (empty? (:parked named)) (empty? (:parked db)) true))))


(deftest store-parity-test
  (testing "the store slice a program writes agrees, host handles excluded"
    (let [ast (app (variable 'yin/def) (lit 'k) {:type :stream/make, :buffer 4})
          slice (fn [vm] (b0/normalize (select-keys (v2/store vm) ['k])))]
      (is (= (slice (semantic-run ast)) (slice (debruijn-run ast))
             {'k {:type :stream-ref, :id :stream-0}})))))
