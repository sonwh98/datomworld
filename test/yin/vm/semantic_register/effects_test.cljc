(ns yin.vm.semantic-register.effects-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [yin.vm.ffi :as ffi]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as walker]
            [yin.vm.test-utils :as tu]
            [yin.vm.semantic-register.walker-trace-test :as trace]
            [yin.vm.engine :as engine]
            [yin.vm.module :as module]
            [yin.vm.values :as values]
            [yin.vm.semantic-register :as sr]
            [yin.vm.semantic-register.analysis :as analysis]
            [yin.vm.semantic-register.corpus :refer [lit v lam app tail if-node def!]]
            [yin.vm.semantic-register.parity-test :as parity]))


(defn run-ast
  ([ast] (vm/run (parity/register-machine ast {:env {'vector vector}})))
  ([machine ast] (vm/run (sr/load-ast machine (vm/ast->datoms ast) vm/ast-contract))))


(defn error
  [f]
  (try (f) nil (catch #?(:cljd Object :clj Throwable :cljs :default) e
                 {:message (ex-message e) :data (ex-data e)})))


(defn until-inst
  [machine op]
  (loop [machine machine fuel 1000]
    (when (zero? fuel) (throw (ex-info "Test did not reach instruction" {:op op})))
    (let [{:keys [segment pc]} (:control machine)
          inst (nth (get-in machine [:code segment :vector]) pc)]
      (if (= op (first inst)) machine (recur (vm/step machine) (dec fuel))))))


(defn next-node
  [cursor]
  {:type :stream/next :source cursor})


(defn put-node
  [s x]
  {:type :stream/put :target s :val x})


(defn cursor-node
  [s]
  {:type :stream/cursor :source s})


(defn reader
  []
  (parity/register-machine (next-node (cursor-node {:type :stream/make :buffer 4}))))


(defn reader-with-live-window
  []
  (parity/register-machine
    (app (v 'vector) (lit nil) (next-node (cursor-node {:type :stream/make :buffer 4})) (lit 2))
    {:env {'vector vector}}))


(deftest non-parking-stream-ops
  (let [made (run-ast {:type :stream/make :buffer 4}) s (vm/value made)
        cursor (run-ast made (cursor-node (lit s))) c (vm/value cursor)
        closed (run-ast cursor {:type :stream/close :source (lit s)})]
    (is (= :stream-ref (:type s))) (is (:seal s))
    (is (= :cursor-ref (:type c))) (is (:seal c))
    (is (nil? (vm/value closed)))
    (is (vm/halted? closed))))


(deftest blocked-read-entry-shape
  (let [before (until-inst (reader) :stream-next)
        {:keys [segment pc]} (:control before)
        rd (second (nth (get-in before [:code segment :vector]) pc))
        blocked (vm/step before) entry (first (:wait-set blocked))]
    (is (vm/blocked? blocked))
    (is (= {:segment segment :pc (inc pc) :env (:env before)
            :window {} :k [] :deliver {:deliver :rd :rd rd} :reason :next
            :cursor-ref (:cursor-ref entry) :stream-id (:stream-id entry)} entry))
    (is (= entry (edn/read-string (pr-str entry))))
    (is (empty? (:parked blocked)))
    (is (nil? (:control blocked))) (is (nil? (:k blocked)))))


(deftest saved-window-membership
  (let [before (until-inst (reader-with-live-window) :stream-next)
        {:keys [segment pc]} (:control before)
        inst (nth (get-in before [:code segment :vector]) pc) rd (second inst)
        entry (first (:wait-set (vm/step before)))
        expected (select-keys (:window before) (analysis/saved (get-in before [:code segment :analysis]) (inc pc) rd))]
    (is (= expected (:window entry)))
    (is (= {1 vector 2 nil} (:window entry)))
    (is (contains? (:window entry) 2))
    (is (not (contains? (:window entry) rd)))))


(deftest wake-delivers-to-rd
  (let [blocked (vm/run (reader-with-live-window)) entry (first (:wait-set blocked))
        handle (get (:resources blocked) (:stream-id entry))]
    (stream/append! handle 7)
    (let [ready (engine/check-wait-set blocked)
          restored (engine/resume-from-run-queue ready sr/register-restore)
          expected (assoc (:window entry) (get-in entry [:deliver :rd]) 7)
          done (vm/run restored)]
      (is (= expected (:window restored)))
      (is (= {:segment (:segment entry) :pc (:pc entry)} (:control restored)))
      (is (empty? (:ready-queue restored)))
      (is (= [nil 7 2] (vm/value done)))
      (is (vm/halted? done)) (is (empty? (:wait-set done))))))


;; A bounded transport which can actually answer full (ringbuffer overwrites).
(defn one-slot-stream
  [_capacity]
  (let [slot (atom ::empty) closed? (atom false)]
    {:dao.stream/outcome :dao.stream/ok
     :dao.stream/handle
     (reify
       stream/IDaoStreamReader
       (cursor [_ _anchor] {:dao.stream/outcome :dao.stream/ok :dao.stream/cursor 0})

       (next
         [_ _cursor]
         (if (not= ::empty @slot)
           (let [value @slot] (reset! slot ::empty)
                {:dao.stream/outcome :dao.stream/ok :dao.stream/value value :dao.stream/cursor 0})
           {:dao.stream/outcome (if @closed? :dao.stream/end :dao.stream/blocked)}))


       stream/IDaoStreamWriter

       (append!
         [_ value]
         (cond @closed? {:dao.stream/outcome :dao.stream/closed}
               (not= ::empty @slot) {:dao.stream/outcome :dao.stream/full}
               :else (do (reset! slot value) {:dao.stream/outcome :dao.stream/ok})))


       stream/IDaoStreamClosable

       (close! [_] (reset! closed? true) {:dao.stream/outcome :dao.stream/ok}))}))


(deftest blocked-put
  (let [made (vm/run (parity/register-machine {:type :stream/make :buffer 1} {:make-stream one-slot-stream}))
        s (vm/value made) handle (get (:resources made) (:id s))
        _ (stream/append! handle :first)
        blocked (run-ast made (put-node (lit s) (lit :second)))
        entry (first (:wait-set blocked))]
    (is (vm/blocked? blocked)) (is (= :put (:reason entry)))
    (is (= :second (:datom entry))) (is (= {} (:window entry)))
    (stream/next handle 0)
    (let [restored (engine/resume-from-run-queue (engine/check-wait-set blocked) sr/register-restore)]
      (is (= {(get-in entry [:deliver :rd]) :second} (:window restored)))
      (is (= :second (vm/value (vm/run restored)))))))


(deftest terminal-wake-raises
  (let [made (vm/run (parity/register-machine {:type :stream/make :buffer 1} {:make-stream one-slot-stream}))
        s (vm/value made) handle (get (:resources made) (:id s))]
    (stream/append! handle :first)
    (let [blocked (run-ast made (put-node (lit s) (lit :second)))]
      (stream/close! handle)
      (is (re-find #"Stream append failed" (:message (error #(vm/run blocked)))))
      (is (re-find #"Stream append failed" (:message (error #(run-ast made (put-node (lit s) (lit :third))))))))))


(defn tail-reader
  [main-tail?]
  (let [made (run-ast {:type :stream/make :buffer 4})
        cursor (run-ast made (cursor-node (lit (vm/value made)))) c (vm/value cursor)
        call (app (lam '[c] (tail (app (v 'stream/next!) (v 'c)))) (lit c))
        ast (if main-tail? (tail call) (app (v 'vector) (lit nil) call (lit 2)))]
    (sr/load-ast (assoc cursor :modules (module/register-stream-module (:modules cursor))) (vm/ast->datoms ast) vm/ast-contract)))


(deftest tail-effect-returns-through-k
  (doseq [main-tail? [false true]]
    (let [blocked (vm/run (tail-reader main-tail?)) entry (first (:wait-set blocked))
          handle (get (:resources blocked) (:stream-id entry)) frame (peek (:k entry))]
      (is (vm/blocked? blocked))
      (is (nil? (:control blocked)))
      (is (nil? (:k blocked)))
      (is (= #{:segment :site :k :deliver :reason :cursor-ref :stream-id} (set (keys entry))))
      (is (= {:deliver :return} (:deliver entry)))
      (is (= (if main-tail? 0 1) (count (:k entry))))
      (stream/append! handle 9)
      (let [restored (engine/resume-from-run-queue (engine/check-wait-set blocked) sr/register-restore)]
        (if main-tail?
          (do (is (vm/halted? restored)) (is (= 9 (vm/value restored)))
              (is (not (contains? (:env restored) engine/store-of-key))))
          (do (is (= (assoc (:window frame) (:rd frame) 9) (:window restored)))
              (is (= [nil 9 2] (vm/value (vm/run restored))))))))))


(deftest tail-primitive-pops-frame
  (let [ast (app (v 'f) (app (lam '[z] (tail (app (v 'g) (v 'z)))) (v 'x)) (v 'y))
        before (until-inst (parity/register-machine ast {:env {'f vector 'g inc 'x 7 'y 2}}) :call)
        entered (vm/step before)
        call (until-inst entered :call) frame (peek (:k call)) after (vm/step call)]
    (is (seq (:window frame)))
    (is (= (assoc (:window frame) (:rd frame) 8) (:window after)))
    (is (= [8 2] (vm/value (vm/run after))))))


(deftest park-resume
  (let [ast (app (v 'vector) (lit nil) {:type :vm/park} (lit 2))
        parked (run-ast ast) record (vm/value parked) seg (:program parked)
        facts (get-in parked [:code seg :analysis]) rd (get-in record [:deliver :rd])]
    (is (nil? (:control parked)))
    (is (nil? (:k parked)))
    (is (= :parked-0 (:id record)))
    (is (= :parked-continuation (:type record)))
    (is (= (select-keys (:window parked) (analysis/saved facts (:pc record) rd)) (:window record)))
    (is (= {:deliver :rd :rd rd} (:deliver record)))
    (is (empty? (:wait-set parked)))
    (let [done (run-ast parked {:type :vm/resume :parked-id (:id record) :val (lit 7)})]
      (is (= [nil 7 2] (vm/value done))) (is (empty? (:parked done))))
    (is (re-find #"not found" (:message (error #(run-ast parked {:type :vm/resume :parked-id :missing :val (lit 1)}))))))
  (let [parked (vm/run (parity/register-machine {:type :vm/park})) record (vm/value parked)]
    (is (nil? (:control parked)))
    (is (nil? (:k parked)))
    (is (= {:type :parked-continuation :id :parked-0 :segment (:program parked)
            :pc 1 :env {} :window {} :k [] :deliver {:deliver :rd :rd 0}} record))
    (is (= record (edn/read-string (pr-str record))))))


(deftest resume-return-mode
  (doseq [frame? [false true]]
    (let [base (parity/register-machine (app (v 'vector) (lit :saved) (lit 7)) {:env {'vector vector}})
          seg (:program base)
          frame {:type :return :segment seg :pc 3 :env {} :window {1 vector 2 :saved} :rd 3}
          base (assoc-in base [:parked :p] {:segment seg :site 0 :k (if frame? [frame] []) :deliver {:deliver :return}})
          done (run-ast base {:type :vm/resume :parked-id :p :val (lit 9)})]
      (is (= (if frame? [:saved 9] 9) (vm/value done)))
      (is (vm/halted? done)) (is (empty? (:parked done))))))


(deftest reified-shape
  (let [machine (parity/register-machine {:type :vm/current-continuation})
        seg (:program machine) after (vm/step machine)
        k (get (:window after) 0) payload (values/payload k)]
    (is (values/continuation? k))
    (is (= {:type :reified-continuation :segment seg :pc 1 :env {} :window {} :k [] :deliver {:deliver :rd :rd 0}} payload))
    (is (= payload (edn/read-string (pr-str payload))))))


(deftest foreign-continuation-refused
  (let [k (values/continuation :other {})
        data (:data (error #(vm/run (parity/register-machine (app (v 'k) (lit 1)) {:env {'k k}}))))]
    (is (= {:reason :foreign-value :kind :continuation} (select-keys data [:reason :kind])))))


(deftest store-and-gensym-test
  (let [ast (app (v 'vector) {:type :vm/gensym} {:type :stream/make :buffer 4} {:type :vm/gensym :prefix "g"})
        done (run-ast ast) [a s b] (vm/value done)]
    (is (= :id-0 a)) (is (= :stream-1 (:id s))) (is (= :g-2 b))
    (is (= 3 (:id-counter done)))))


(deftest window-is-a-gc-root
  (let [modules (module/register-cell-module (module/default-registry))
        result (vm/run (parity/register-machine (app (v 'vector) (app (v 'cell/new) (lit :keep)) (app (v 'cell/new) (lit :junk))) {:modules modules :env {'vector vector}}))
        [keep-ref] (vm/value result)
        only-window (assoc result :env {} :control nil :k nil :value nil :window {0 keep-ref})
        collected (engine/collect only-window)]
    (is (= #{:keep} (set (map :value (vals (:heap collected))))))
    (is (= #{} (set (map :value (vals (:heap (engine/collect (assoc only-window :window {}))))))))))


;; The five continuation programs are respelled here; backend registration is stage 2.
(def capture {:type :vm/current-continuation})


(defn then
  [a b]
  (parity/then a b))


(defn with-k
  [body]
  (app (lam '[r] body) capture))


(defn bounded-run
  [machine]
  (loop [machine machine steps 0]
    (cond
      (vm/halted? machine) machine
      (vm/blocked? machine) (throw (ex-info "Continuation value test blocked" {:steps steps}))
      (= steps 10000) (throw (ex-info "Test step bound exhausted" {:steps steps}))
      :else (recur (vm/step machine) (inc steps)))))


(defn register-value=
  [expected ast]
  (let [result (try {:machine (bounded-run (parity/register-machine ast {:env {'vector vector}}))}
                    (catch #?(:cljd Object :clj Throwable :cljs :default) e
                      {:thrown (ex-message e)}))]
    (if-let [done (:machine result)]
      (do (is (= expected (vm/value done))) (is (vm/halted? done)))
      (is (= expected [:thrown (:thrown result)])))))


(deftest continuation-restores-captured-environment
  (let [ast (app (lam '[x]
                      (app (v 'list)
                           (with-k (if-node (app (v '=) (v 'r) (lit 7))
                                            (v 'r)
                                            (app (lam '[x] (app (v 'r) (lit 7))) (lit 9))))
                           (v 'x)))
                 (lit 1))
        walker-machine (walker/vm-load-rows (tu/create-vm {:env {'list list}})
                                            (vm/ast->semantic-bytecode ast) vm/ast-contract)
        register-machine (parity/register-machine ast {:env {'list list}})
        expected [[:value {:type :closure :params '[x]}] [:value 1]
                  [:value :host-fn] [:value {:type :closure :params '[r]}]
                  [:value :continuation] [:value :host-fn] [:value :continuation]
                  [:value 7] [:value false] [:value {:type :closure :params '[x]}]
                  [:value 9] [:value :continuation] [:value 7] [:value 7]
                  [:value :host-fn] [:value 7] [:value 7] [:value true]
                  [:value 7] [:value 1] [:value [7 1]] [:halt [7 1]]]
        w (bounded-run walker-machine)
        r (bounded-run register-machine)]
    (is (= '(7 1) (vm/value w)))
    (is (= '(7 1) (vm/value r)))
    (is (= (parity/trace-normalize (vm/value w)) (parity/trace-normalize (vm/value r))))
    (is (= expected (trace/trace-machine walker-machine false)))
    (is (= expected (trace/trace-machine register-machine true)))))


(deftest escape-discards-pending-frames-test
  (testing "invoked under (+ 1000 ...), the continuation delivers 7 to the
            capture point: the +1000 frame is discarded, the captured +1
            frame is not"
    (register-value= 9
                     (app (v '+)
                          (lit 1)
                          (with-k (if-node (app (v '=) (v 'r) (lit 7))
                                           (app (v '+) (v 'r) (lit 1))
                                           (app (v '+)
                                                (lit 1000)
                                                (app (v 'r) (lit 7)))))))))


(deftest tail-position-invocation-test
  (testing "the continuation invoked as a tail call"
    (register-value= 8
                     (app (v '+)
                          (lit 1)
                          (with-k (if-node (app (v '=) (v 'r) (lit 7))
                                           (v 'r)
                                           (tail (app (v 'r) (lit 7)))))))))


(deftest re-entry-after-return-test
  (testing "a continuation captured inside a returned call, kept in the
            store, re-enters its capture point once per invocation; the
            counter bounds the loop"
    ;; count <- 0
    ;; x <- ((fn [] (current-continuation)))   ; returns before any invoke
    ;; when count = 0: saved <- x
    ;; if count < 3: count <- count + 1, (saved (* 10 count)) else [x count]
    (register-value=
      [30 3]
      (then
        (def! 'count (lit 0))
        (app (lam ['x]
                  (then (if-node (app (v '=) (v 'count) (lit 0))
                                 (def! 'saved (v 'x))
                                 (lit nil))
                        (if-node (app (v '<) (v 'count) (lit 3))
                                 (then (def! 'count
                                         (app (v '+) (v 'count) (lit 1)))
                                       (app (v 'saved)
                                            (app (v '*)
                                                 (lit 10)
                                                 (v 'count))))
                                 (app (v 'conj)
                                      (app (v 'conj) (lit []) (v 'x))
                                      (v 'count)))))
             (app (lam [] capture)))))))


(deftest store-not-rolled-back-test
  (testing "a store write made before the invocation is visible after it"
    (register-value= [1 :written]
                     (with-k (if-node (app (v '=) (v 'r) (lit 1))
                                      (app (v 'conj)
                                           (app (v 'conj) (lit []) (v 'r))
                                           (v 'mark))
                                      (then (def! 'mark (lit :written))
                                            (app (v 'r) (lit 1))))))))


(deftest arity-error-identical-across-vms-test
  (doseq [argc [0 2]]
    (testing (str argc " arguments")
      (register-value= [:thrown "Continuation expects exactly one argument"]
                       (with-k (if-node (app (v '=) (v 'r) (lit 1))
                                        (v 'r)
                                        (apply app (v 'r)
                                               (repeat argc (lit 1)))))))))


(deftest stream-outcomes-and-edn-wake
  (let [blocked (vm/run (reader)) entry (first (:wait-set blocked))
        revived (assoc blocked :wait-set (edn/read-string (pr-str (:wait-set blocked))))
        handle (get (:resources blocked) (:stream-id entry))
        cursor-id (get-in entry [:cursor-ref :id])
        before (get-in blocked [:resources cursor-id :cursor])]
    (is (vm/blocked? (vm/run blocked)))
    (stream/append! handle :from-edn)
    (let [done (vm/run revived)]
      (is (= :from-edn (vm/value done)))
      (is (not= before (get-in done [:resources cursor-id :cursor])))))
  (let [blocked (vm/run (reader)) entry (first (:wait-set blocked))]
    (stream/close! (get (:resources blocked) (:stream-id entry)))
    (is (nil? (vm/value (vm/run blocked)))))
  (let [blocked (vm/run (reader)) entry (first (:wait-set blocked))
        handle (get (:resources blocked) (:stream-id entry))]
    (dotimes [n 8] (stream/append! handle n))
    (is (= :dao.stream/gap (vm/value (vm/run blocked)))))
  (let [made (run-ast {:type :stream/make :buffer 4}) s (vm/value made)
        put (run-ast made (put-node (lit s) (lit 7)))
        handle (get (:resources put) (:id s))
        cursor (:dao.stream/cursor (stream/cursor handle stream/anchor-oldest))]
    (is (= 7 (vm/value put)))
    (is (= 7 (:dao.stream/value (stream/next handle cursor)))))
  (is (some? (error #(run-ast (put-node (lit {:type :stream-ref :id :missing}) (lit 1)))))))


(defn ffi-node
  [op & operands]
  {:type :dao.stream.apply/call :op op :operands (vec operands)})


(defn ffi-program
  []
  (app (v 'vector) (lit nil) (ffi-node :op/echo (lit 7)) (lit 2)))


(deftest ffi-sent
  (let [before (until-inst (parity/register-machine (ffi-program)
                                                    {:env {'vector vector} :ffi-caller-id :caller}) :ffi-call)
        {:keys [segment pc]} (:control before)
        rd (second (nth (get-in before [:code segment :vector]) pc))
        blocked (vm/step before) entry (first (:wait-set blocked)) call-id (:call-id entry)]
    (is (vm/blocked? blocked))
    (is (nil? (:control blocked))) (is (nil? (:k blocked)))
    (is (= [:caller :parked-0] call-id))
    (is (= :next (:reason entry)))
    (is (= {1 vector 2 nil} (:window entry)))
    (is (= {:deliver :rd :rd 3} (:deliver entry)))
    (is (= rd 3))
    (is (= (inc pc) (:pc entry)))
    (is (= (:env before) (:env entry)))
    (is (= (select-keys (:window before) (analysis/saved (get-in before [:code segment :analysis]) (inc pc) rd))
           (:window entry)))
    (is (not-any? #(contains? entry %) [:datom :request-sent :op]))
    (is (= (select-keys entry [:segment :pc :env :window :k :deliver])
           (select-keys (get (:parked blocked) call-id) [:segment :pc :env :window :k :deliver])))
    (let [bridge (ffi/bridge-step (ffi/attach blocked {:op/echo identity}))
          ready (engine/check-wait-set (:vm bridge))
          restored (engine/resume-from-run-queue ready sr/register-restore)]
      (is (:handled? bridge))
      (is (= (assoc (:window entry) rd 7) (:window restored)))
      (is (empty? (:parked restored)))
      (is (= [nil 7 2] (vm/value (vm/run restored)))))))


(deftest ffi-retained
  (let [call-in (:dao.stream/handle (one-slot-stream 1))
        call-out (tu/new-stream 8)
        _ (stream/append! call-in :occupy)
        before (until-inst (parity/register-machine (ffi-program)
                                                    {:env {'vector vector} :call-in call-in
                                                     :call-out call-out
                                                     :call-out-cursor (:dao.stream/cursor (stream/cursor call-out stream/anchor-oldest))}) :ffi-call)
        blocked (vm/step before) entry (first (:wait-set blocked))
        call-id (:call-id entry) request (:datom entry)]
    (is (vm/blocked? blocked))
    (is (= :put (:reason entry)))
    (is (:request-sent entry))
    (is (= call-id (apply2/request-id request)))
    (is (= :op/echo (apply2/request-op request)))
    (is (= [7] (apply2/request-args request)))
    (is (= {1 vector 2 nil} (:window entry)))
    (stream/next call-in 0)
    (let [ready (engine/check-wait-set blocked)
          reader-state (engine/resume-from-run-queue ready sr/register-restore)
          wait (first (:wait-set reader-state))]
      (is (vm/blocked? reader-state))
      (is (false? (:halted? reader-state)))
      (is (nil? (:control reader-state))) (is (nil? (:k reader-state)))
      (is (= (:window blocked) (:window reader-state)))
      (is (= :yin/blocked (:value reader-state)))
      (is (= :next (:reason wait)))
      (is (= call-id (:call-id wait)))
      (is (= (select-keys entry [:segment :pc :env :window :k :deliver])
             (select-keys wait [:segment :pc :env :window :k :deliver])))
      (is (not-any? #(contains? wait %) [:datom :request-sent :op]))
      (is (contains? (:parked reader-state) call-id))
      (is (= request (:dao.stream/value (stream/next call-in 0))))
      (stream/append! (get (:resources reader-state) vm/call-out-stream-key) (apply2/success-response call-id 7))
      (let [done (vm/run reader-state)]
        (is (= [nil 7 2] (vm/value done)))
        (is (empty? (:parked done)))))))


(deftest retained-response-route
  (doseq [carried? [false true]]
    (let [payload {:segment -1 :pc 3 :env {} :window {1 nil} :k [] :deliver {:deliver :rd :rd 2}}
          entry (cond-> (assoc payload :request-sent true :call-id :p :reason :put :datom {:request true})
                  carried? (assoc :response-cursor :custom-cursor :response-stream :custom-stream))
          base (assoc (sr/create-vm) :parked {:p payload})
          after (sr/register-restore base entry (:datom entry))
          wait (first (:wait-set after))]
      (is (vm/blocked? after))
      (is (= payload (select-keys wait (keys payload))))
      (is (= (if carried? :custom-cursor vm/call-out-cursor-key) (get-in wait [:cursor-ref :id])))
      (is (= (if carried? :custom-stream vm/call-out-stream-key) (:stream-id wait)))
      (is (= (:parked base) (:parked after)))
      (is (nil? (:control after)))
      (is (nil? (:k after))))))


(deftest ffi-error-raises
  (let [blocked (vm/run (parity/register-machine (ffi-node :op/fail (lit 7))))
        call-id (:call-id (first (:wait-set blocked)))]
    (stream/append! (get (:resources blocked) vm/call-out-stream-key)
                    (apply2/error-response call-id :test/failed "broken"))
    (is (re-find #"FFI call failed" (:message (error #(vm/run blocked)))))))


(deftest ffi-response-validation
  (let [entry {:segment -1 :pc 1 :env {} :window {} :k [] :deliver {:deliver :rd :rd 0} :call-id :p}
        base (assoc (sr/create-vm) :parked {:p entry})]
    (is (re-find #"malformed" (:message (error #(sr/register-restore base entry :bad)))))
    (is (re-find #"correlate" (:message (error #(sr/register-restore base entry (apply2/success-response :other 8))))))
    (let [done (sr/register-restore base entry (apply2/success-response :p nil))]
      (is (= {0 nil} (:window done)))
      (is (empty? (:parked done))))))


(deftest ffi-pair-required-before-parking
  (let [machine (sr/load-ast (sr/create-vm) (vm/ast->datoms (ffi-node :op/echo)) vm/ast-contract)]
    (is (re-find #"without an FFI call pair" (:message (error #(vm/run machine)))))
    (is (empty? (:parked machine)))
    (is (zero? (:id-counter machine)))))


(deftest declared-profile-refusal
  (let [registry (module/register-host-module
                   (module/default-registry) 'bad
                   {'sneak (fn [] (module/make-effect :vm/store-put {:key 'secret :val 7}))}
                   {'sneak (vm/primitive-profile 'sneak :pure [0] #{} :none)})
        ast (app (v 'bad/sneak))
        data (:data (error #(vm/run (parity/register-machine ast {:modules registry}))))]
    (is (= {:yin.k/status :yin.k/undeclared-effect :yin.k/effect :vm/store-put :yin.k/effects #{}}
           (select-keys data [:yin.k/status :yin.k/effect :yin.k/effects])))))


;; Respelled bridge rows from semantic-ffi-test; helpers load register images.
(defn bridge-throws?
  [f]
  (some? (error f)))


(defn bridge-make-vm
  ([] (bridge-make-vm {}))
  ([opts] (sr/create-vm
            (cond-> (merge {:make-stream tu/make-stream :capability-secret tu/secret} opts)
              (:call-out opts) (assoc :call-out-cursor (vm/mint-oldest (:call-out opts) :test))))))


(defn bridge-run-ast
  [machine ast]
  (vm/run (sr/load-ast machine (vm/ast->datoms ast) vm/ast-contract)))


(defn bridge-resume
  [machine]
  (vm/eval machine nil))


(defn bridge-call-ast
  ([op] (bridge-call-ast op []))
  ([op args] {:type :dao.stream.apply/call :op op :operands (mapv lit args)}))


(deftest bridge-installed-handlers-round-trip-test
  (testing "A host call is dispatched and its parked continuation resumes"
    (let [result (bridge-run-ast (bridge-make-vm {:bridge {:op/echo identity}})
                                 (bridge-call-ast :op/echo [42]))]
      (is (vm/halted? result))
      (is (= 42 (vm/value result)))))
  (testing "Arguments are evaluated left to right before the call"
    (let [result (bridge-run-ast (bridge-make-vm {:bridge {:op/list vector}})
                                 (bridge-call-ast :op/list [1 2 3]))]
      (is (= [1 2 3] (vm/value result)))))
  (testing "The result feeds the instructions after the call site"
    (let [result (bridge-run-ast (bridge-make-vm {:bridge {:op/echo identity}})
                                 {:type :application,
                                  :operator {:type :variable, :name '+},
                                  :operands [{:type :literal, :value 1}
                                             (bridge-call-ast :op/echo [41])]})]
      (is (= 42 (vm/value result))))))


(deftest bridge-manual-bridge-step-test
  (testing "Without installed handlers the VM parks and a driver steps it"
    (let [parked (bridge-run-ast (bridge-make-vm) (bridge-call-ast :op/echo [7]))
          entry (first (:wait-set parked))]
      (is (vm/blocked? parked))
      (is (= :yin/blocked (vm/value parked)))
      (is (= 1 (count (:wait-set parked)))
          "The continuation waits in the polling wait set, not on a waiter")
      (is (= :next (:reason entry)))
      (is (contains? (:parked parked) (:call-id entry)))
      (is (= (:wait-set parked) (edn/read-string (pr-str (:wait-set parked))))
          "The response reader is registers and ids, never a closure")
      (let [attached (ffi/attach parked {:op/echo identity})
            {:keys [handled? vm]} (ffi/bridge-step attached)]
        (is (true? handled?))
        (let [done (bridge-resume vm)]
          (is (vm/halted? done))
          (is (= 7 (vm/value done)))
          (is (empty? (:parked done))))))))


(deftest bridge-error-responses-surface-as-errors-test
  (testing "An unknown op is a portable error response, not an escaping throw"
    (let [parked (bridge-run-ast (bridge-make-vm) (bridge-call-ast :op/missing [1]))
          {:keys [vm handled?]} (ffi/bridge-step (ffi/attach parked
                                                             {:op/other
                                                              identity}))]
      (is (true? handled?) "The response was delivered")
      (is (bridge-throws? (fn [] (bridge-resume vm))))))
  (testing "A failing handler is an error response too"
    (let [parked (bridge-run-ast (bridge-make-vm) (bridge-call-ast :op/boom []))
          bang (fn [] (throw (ex-info "boom" {})))
          {:keys [vm]} (ffi/bridge-step (ffi/attach parked {:op/boom bang}))]
      (is (bridge-throws? (fn [] (bridge-resume vm)))))))


(deftest bridge-a-response-for-another-call-does-not-resume-this-one-test
  (let [parked (bridge-run-ast (bridge-make-vm) (bridge-call-ast :op/echo [1]))
        call-id (:call-id (first (:wait-set parked)))
        call-out (get (:resources parked) vm/call-out-stream-key)]
    (apply2/put-response! call-out (apply2/success-response [:other call-id] 1))
    (let [still (bridge-resume parked)]
      (is (vm/blocked? still)
          "A mis-correlated response is skipped, never this call's value")
      (is (= call-id (:call-id (first (:wait-set still)))))
      (is (= [{:kind :unmatched, :response-id [:other call-id],
               :cell vm/call-out-cursor-key}]
             (first (engine/take-ffi-diagnostics still))))
      (apply2/put-response! call-out (apply2/success-response call-id 2))
      (is (= 2 (vm/value (bridge-resume still))) "Its own response resumes it"))))


(deftest bridge-a-response-for-a-woken-writer-is-left-for-its-reader-test
  (let [parked (bridge-run-ast (bridge-make-vm {:ffi-caller-id "t"}) (bridge-call-ast :op/echo [1]))
        mine (:call-id (first (:wait-set parked)))
        other ["t" :parked-9]
        call-out (get (:resources parked) vm/call-out-stream-key)
        cell (get-in parked [:resources vm/call-out-cursor-key :cursor])
        ;; a retained writer the sweep already woke: its request landed,
        ;; and it is not yet restored as the reader of `other`
        woken-writer {:request-sent true, :call-id other, :reason :put}]
    (apply2/put-response! call-out (apply2/success-response other 9))
    (apply2/put-response! call-out (apply2/success-response mine 1))
    (let [checked (engine/check-wait-set
                    (assoc parked :ready-queue [woken-writer]))]
      (is (= [mine] (map :call-id (:wait-set checked)))
          "the reader behind it keeps waiting")
      (is (= cell (get-in checked [:resources vm/call-out-cursor-key :cursor]))
          "the writer's response was not consumed")
      (is (empty? (first (engine/take-ffi-diagnostics checked)))))
    (let [checked (engine/check-wait-set parked)]
      (is (empty? (:wait-set checked)) "with no such writer, it is skipped")
      (is (= [mine] (map :call-id (:ready-queue checked))))
      (is (= [other] (map :response-id
                          (first (engine/take-ffi-diagnostics checked))))))))


;; =============================================================================
;; The step is once-only
;; =============================================================================

(defn- bridge-scripted-writer
  "A call-out handle whose append outcomes are scripted."
  [outcomes]
  (reify
    stream/IDaoStreamReader
    (cursor
      [_ _arg]
      {:dao.stream/outcome :dao.stream/ok,
       :dao.stream/cursor :out-0})

    (next [_ _arg] {:dao.stream/outcome :dao.stream/blocked})


    stream/IDaoStreamWriter

    (append!
      [_ _arg]
      (let [o (first @outcomes)]
        (swap! outcomes rest)
        {:dao.stream/outcome o}))))


(deftest bridge-full-retains-the-response-and-runs-the-handler-once-test
  (let [calls (atom 0)
        outcomes (atom [:dao.stream/full :dao.stream/ok])
        vm0 (bridge-make-vm {:call-in (tu/new-stream 8),
                             :call-out (bridge-scripted-writer outcomes)})
        parked (ffi/attach (bridge-run-ast vm0 (bridge-call-ast :op/count []))
                           {:op/count (fn [] (swap! calls inc))})
        cursor-before (get-in parked [:bridge :cursor])
        step1 (ffi/bridge-step parked)]
    (testing "A full response stream retains the response and the successor"
      (is (false? (:handled? step1)))
      (is (true? (:retained? step1)))
      (is (= 1 @calls))
      (is (= cursor-before (get-in (:vm step1) [:bridge :cursor])))
      (is (some? (get-in (:vm step1) [:bridge :pending-response]))))
    (testing "The retry appends the retained response without re-running"
      (let [step2 (ffi/bridge-step (:vm step1))]
        (is (true? (:handled? step2)))
        (is (= 1 @calls) "The handler executed exactly once")
        (is (not= cursor-before (get-in (:vm step2) [:bridge :cursor])))
        (is (nil? (get-in (:vm step2) [:bridge :pending-response])))))))


(defn- bridge-gated-call-in
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


(deftest bridge-full-retains-the-unsent-request-test
  (let [attempts (atom [])
        call-in (reify
                  stream/IDaoStreamReader
                  (cursor
                    [_ _arg]
                    {:dao.stream/outcome :dao.stream/ok,
                     :dao.stream/cursor :in-0})

                  (next [_ _arg] {:dao.stream/outcome :dao.stream/blocked})


                  stream/IDaoStreamWriter

                  (append!
                    [_ v]
                    (swap! attempts conj v)
                    {:dao.stream/outcome :dao.stream/full}))
        parked (bridge-run-ast (bridge-make-vm {:call-in call-in, :call-out (tu/new-stream 8)})
                               (bridge-call-ast :op/echo [3]))]
    (testing "A full call-in parks the call with its request retained"
      (is (vm/blocked? parked))
      (is (= 1 (count (:wait-set parked))))
      (let [waiter (first (:wait-set parked))
            request (:datom waiter)
            [parked-id] (keys (:parked parked))]
        (is (= :put (:reason waiter)))
        (is (true? (:request-sent waiter)))
        (is (apply2/request? request))
        (is (= parked-id (apply2/request-id request) (:call-id waiter))
            "The retained request keeps the allocated id for the retry")
        (is (= :op/echo (apply2/request-op request)))
        (is (= [3] (apply2/request-args request)))))
    (testing "The polling wait set retries the identical encoded request"
      (is (vm/blocked? (bridge-resume parked)))
      (is (> (count @attempts) 1) "The wait set retried the append")
      (is (apply = @attempts)))))


(deftest bridge-full-request-is-sent-on-retry-and-answered-test
  (let [parked (bridge-run-ast (bridge-make-vm {:call-in (bridge-gated-call-in (tu/new-stream 8) 1),
                                                :call-out (tu/new-stream 8)})
                               (bridge-call-ast :op/echo [5]))]
    (testing "The retry sent the request; the call waits for its response"
      (is (vm/blocked? parked))
      (is (= 1 (count (:wait-set parked))))
      (is (= :next (:reason (first (:wait-set parked))))))
    (let [{:keys [handled? request-id vm]}
          (ffi/bridge-step (ffi/attach parked {:op/echo identity}))
          done (bridge-resume vm)]
      (is (true? handled?))
      (is (= (first (keys (:parked parked))) request-id))
      (is (vm/halted? done))
      (is (= 5 (vm/value done)))
      (is (empty? (:parked done))))))


(deftest bridge-terminal-append-outcome-ends-the-bridge-test
  (let [vm0 (bridge-make-vm {:call-in (tu/new-stream 8),
                             :call-out (bridge-scripted-writer (atom [:dao.stream/closed]))})
        parked (ffi/attach (bridge-run-ast vm0 (bridge-call-ast :op/echo [1]))
                           {:op/echo identity})
        {:keys [handled? terminal vm]} (ffi/bridge-step parked)]
    (is (false? handled?))
    (is (= :dao.stream/closed terminal))
    (is (= :dao.stream/closed (:terminal (ffi/bridge-step vm))))))


;; =============================================================================
;; Reading the request side
;; =============================================================================

(deftest bridge-gap-at-the-bridge-cursor-is-fatal-test
  (testing "An evicted request can never be answered, so it is reported"
    (let [call-in (tu/new-stream 2)
          vm0 (bridge-make-vm {:call-in call-in,
                               :call-out (tu/new-stream 8),
                               :bridge {:op/echo identity}})]
      (dotimes [n 5] (stream/append! call-in (apply2/request n :op/echo [n])))
      (is (bridge-throws? (fn [] (ffi/bridge-step vm0)))))))


(deftest bridge-history-before-the-cursor-is-not-skipped-test
  (testing "A pre-filled call-in is read from oldest, not from newest"
    (let [call-in (tu/new-stream 8)
          _ (stream/append! call-in (apply2/request :pre :op/echo [:early]))
          vm0 (bridge-make-vm {:call-in call-in,
                               :call-out (tu/new-stream 8),
                               :bridge {:op/echo identity}})
          {:keys [handled? request-id]} (ffi/bridge-step vm0)]
      (is (true? handled?))
      (is (= :pre request-id)))))


;; =============================================================================
;; A VM with no pair, and repeated calls
;; =============================================================================

(deftest bridge-no-call-pair-test
  (let [bare (sr/create-vm {})]
    (testing "A VM without :make-stream or explicit streams holds no pair"
      (is (nil? (ffi/call-pair (:resources bare)))))
    (testing "A non-nil bridge on such a VM is a construction error"
      (is (bridge-throws? (fn [] (sr/create-vm {:bridge {:op/echo identity}})))))
    (testing "A call fails before park-continuation"
      (is (bridge-throws? (fn [] (bridge-run-ast bare (bridge-call-ast :op/echo [1]))))))))


(deftest bridge-completed-calls-leave-no-parked-entries-test
  (let [vm0 (bridge-make-vm {:bridge {:op/echo identity}})
        first-done (bridge-run-ast vm0 (bridge-call-ast :op/echo [1]))]
    (is (empty? (:parked first-done)))
    (let [second-done (bridge-run-ast first-done (bridge-call-ast :op/echo [2]))]
      (is (vm/halted? second-done))
      (is (= 2 (vm/value second-done)))
      (is (empty? (:parked second-done))
          "Repeated host calls do not accumulate in :parked"))))
