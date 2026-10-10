(ns yin.vm.semantic-register.effects-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
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
