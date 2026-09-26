(ns yin.vm.attach-image-test
  "yin.vm.linker M4 slice S1 (docs/design/yin.vm.linker.md section 7.3,
   act 3, r6, r7; section 11 criterion 17): `attach-image` on the four
   kernels, hash-safe restore against the offset table, grow-only
   register returns, and the walker's `:lambda` row annotation.

   A module here is `(yin/def f (fn [] 42))` lowered by each kernel's own
   producer. The lift and lower of acts 1 and 4 belong to the engine (a
   later slice); these tests compose them by hand from the kernel
   primitives this slice adds: `image-pc` and `absolute-pc` on the
   positional kernels, the alias column on the semantic VM, and
   `closure-row` and `row-node` on the walker."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as walker]
            [yin.vm.debruijn-code :as dc]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; AST helpers and per-kernel producers
;; =============================================================================

(defn- lit
  [x]
  {:type :literal, :value x})


(defn- variable
  [n]
  {:type :variable, :name n})


(defn- lambda
  [params body]
  {:type :lambda, :params params, :body body})


(defn- app
  [f & args]
  {:type :application, :operator f, :operands (vec args)})


(def ^:private module-ast
  "`(yin/def f (fn [] 42))`: one exported closure."
  (app (variable 'yin/def) (lit 'f) (lambda [] (lit 42))))


(def ^:private caller-ast
  "`(f)`: applies the export."
  (app (variable 'f)))


(def ^:private parking-ast
  "`((fn [a b] b) :a ((fn [s] (next (cursor s))) (make 4)))`: parks on
   the empty stream inside a non-tail call, with the outer closure and
   `:a` pending (plain data, which a register frame requires); its value
   is the value read."
  (app (lambda '[a b] (variable 'b))
       (lit :a)
       (app (lambda '[s]
                    {:type :stream/next,
                     :source {:type :stream/cursor, :source (variable 's)}})
            {:type :stream/make, :buffer 4})))


(defn- stack-image
  [ast]
  (:image (dl/adapt (vm/ast->datoms ast))))


(defn- register-image
  [ast]
  (:image (rc/adapt (second (vm/ast->datoms-with-root ast)))))


(defn- semantic-vector
  [ast]
  (:vector (linearize/lower-rows (vm/ast->semantic-bytecode ast))))


(defn- opts
  [contract]
  {:make-stream tu/make-stream,
   :capability-secret tu/secret, :primitives vm/primitives,
   :contract contract})


(defn- stack-vm
  [image]
  (dvm/create-vm image (opts vm/stack-contract)))


(defn- register-vm
  [image]
  (rvm/create-vm image (opts vm/register-contract)))


(defn- stream-of
  "The stream handle the parked reader of `vm` waits on."
  [vm]
  (get (:resources vm) (:stream-id (first (:wait-set vm)))))


(defn- start-at
  "Start the next top-level input at `pc`, as `yin.repl` does after an
   attach."
  [vm pc]
  (assoc vm :pc pc :frames [] :stack [] :continuation []
         :halted? false :blocked? false :value nil))


(defn- register-start-at
  [vm pc]
  (let [body (some #(when (= pc (:start %)) %) (:bodies (:segment vm)))]
    (assoc vm :pc pc :frames [] :continuation []
           :registers (vec (repeat (:registers body) nil))
           :halted? false :blocked? false :value nil)))


(defn- rebase-closures
  "Rebase the `:body-pc` of every positional closure inside `x` by `f`,
   the body references a lifted continuation carries."
  [f x]
  (cond (and (map? x) (= :closure (:type x)))
        (-> x (update :body-pc f) (update :frames #(rebase-closures f %)))
        (map? x) (into {} (map (fn [[k v]] [k (rebase-closures f v)])) x)
        (vector? x) (mapv #(rebase-closures f %) x)
        :else x))


(defn- ex-data-of
  [thunk]
  (try (thunk) nil
       (catch #?(:clj Exception :cljs js/Error :cljd Object) e
         (or (ex-data e) {}))))


;; =============================================================================
;; Stack kernel
;; =============================================================================

(deftest stack-attach-appends-relocated-and-records-a-row-test
  (let [base [[:const 0] [:halt]]
        module (stack-image module-ast)
        vm (stack-vm base)
        attached (dvm/attach-image vm module vm/stack-contract)
        offset (count base)
        combined (:segment attached)]
    (testing "load-image records the base row [H 0 n]"
      (is (= [[(dc/image-hash base) 0 2]] (:images vm))))
    (testing "the image is relocated by the held length and appended"
      (is (= (+ offset (count module)) (count combined)))
      (is (= base (subvec combined 0 offset)))
      (is (= (dc/image-hash (subvec combined 0 offset)) (dc/image-hash base)))
      (is (some #(and (= :closure (first %)) (<= offset (nth % 2)))
                (subvec combined offset))
          "the closure's body pc moved into the appended range"))
    (testing "one [identity offset length] row; :hash is the concatenation's"
      (is (= [[(dc/image-hash base) 0 2]
              [(dc/image-hash module) offset (count module)]]
             (:images attached)))
      (is (= (dc/image-hash combined) (:hash attached))))
    (testing "the registers are untouched"
      (is (= (select-keys vm [:pc :stack :frames :continuation :halted?
                              :store :value])
             (select-keys attached [:pc :stack :frames :continuation
                                    :halted? :store :value]))))
    (testing "an image already held is not attached twice"
      (is (= attached (dvm/attach-image attached module vm/stack-contract))))
    (testing "the image is admitted alone under the stamp"
      (is (= :contract-missing
             (:rule (ex-data-of #(dvm/attach-image vm module nil))))))))


(deftest stack-empty-attach-and-out-of-range-pcs-test
  (let [base [[:const 0] [:halt]]
        module (stack-image module-ast)
        attached (dvm/attach-image (stack-vm base) module vm/stack-contract)
        ident (dc/image-hash module)
        n (count module)]
    (testing "an empty image attaches no row and leaves the hash"
      (is (= attached (dvm/attach-image attached [] vm/stack-contract))))
    (testing "absolute-pc places exactly the pcs image-pc lifts"
      (is (= 2 (dvm/absolute-pc attached [ident 0])))
      (is (= (+ 2 n) (dvm/absolute-pc attached [ident n]))
          "one past the end of the last row")
      (is (nil? (dvm/absolute-pc attached [ident (inc n)])))
      (is (nil? (dvm/absolute-pc attached [ident -1])))
      (is (nil? (dvm/absolute-pc attached [(dc/image-hash base) 2]))
          "one past a row that is not the last is the next row's pc"))))


(deftest stack-parked-entry-restores-after-an-attach-test
  (let [parked (vm/run (stack-vm (stack-image parking-ast)))
        entry (first (:wait-set parked))
        attached (dvm/attach-image parked (stack-image module-ast)
                                   vm/stack-contract)]
    (testing "the parked entry records its image row, beside :format"
      (is (seq (:stack entry)) "a non-empty operand stack")
      (is (seq (:frames entry)) "a non-empty frame stack")
      (is (seq (:continuation entry)) "a pending return frame")
      (is (= (:hash parked) (:image entry)))
      (is (some #(= (:image entry) (first %)) (:images attached))))
    (testing "the attach changed :hash and touched no register"
      (is (not= (:hash parked) (:hash attached)))
      (is (= (select-keys parked [:pc :stack :frames :continuation])
             (select-keys attached [:pc :stack :frames :continuation]))))
    (stream/append! (stream-of attached) 41)
    (let [done (vm/run attached)]
      (testing "the entry restores at the same pcs, :hash never a key"
        (is (= 41 (vm/value done))))
      (testing ":segment is never assigned from the entry"
        (is (= (:segment attached) (:segment done)))
        (is (= (:images attached) (:images done)))
        (is (= (:hash attached) (:hash done)))))
    (testing "the restore itself keeps the grown code space"
      (let [restored (dvm/stack-restore attached entry 41)]
        (is (= (:segment attached) (:segment restored)))
        (is (= (:pc entry) (:pc restored)))
        (is (= (:frames entry) (:frames restored)))
        (is (= (:continuation entry) (:continuation restored)))
        (is (= (conj (:stack entry) 41) (:stack restored)))))
    (testing "an entry naming no row of the table is refused"
      (is (= :continuation-format
             (:rule (ex-data-of
                      #(dvm/stack-restore attached
                                          (assoc entry :image "absent")
                                          41))))))))


(defn- stack-export
  "Run the module in a child and lift its export `f` to `[identity pc]`."
  []
  (let [child (vm/run (stack-vm (stack-image module-ast)))
        f (get (vm/store child) 'f)]
    {:closure f, :marker (dvm/image-pc child (:body-pc f))}))


(defn- stack-apply-export
  "Attach the module to `parent`, lower the export by its table, and apply
   it from a freshly attached caller image."
  [parent {:keys [closure marker]}]
  (let [attached (dvm/attach-image parent (stack-image module-ast)
                                   vm/stack-contract)
        body-pc (dvm/absolute-pc attached marker)
        caller (stack-image caller-ast)
        with-caller (-> attached
                        (assoc-in [:store 'f] (assoc closure :body-pc body-pc))
                        (dvm/attach-image caller vm/stack-contract))
        start (dvm/absolute-pc with-caller [(dc/image-hash caller) 0])]
    {:body-pc body-pc,
     :vm attached,
     :value (vm/value (vm/run (start-at with-caller start)))}))


(deftest stack-exported-closure-relocates-per-receiver-test
  (let [{:keys [closure marker] :as export} (stack-export)
        module (stack-image module-ast)
        short-parent (vm/run (stack-vm [[:const 0] [:halt]]))
        long-parent (vm/run (stack-vm (stack-image parking-ast)))
        a (stack-apply-export short-parent export)
        b (stack-apply-export long-parent export)]
    (testing "the lift is image-relative"
      (is (= [(dc/image-hash module) (:body-pc closure)] marker)))
    (testing "each receiver places the body at its own nonzero offset"
      (is (= (+ 2 (:body-pc closure)) (:body-pc a)))
      (is (= (+ (count (stack-image parking-ast)) (:body-pc closure))
             (:body-pc b)))
      (is (= (nth module (:body-pc closure))
             (nth (:segment (:vm a)) (:body-pc a)))))
    (testing "two tasks of different lengths each apply the export"
      (is (= 42 (:value a)))
      (is (= 42 (:value b))))))


(deftest stack-continuation-lifted-after-attach-rebases-test
  (let [program (stack-image parking-ast)
        base [[:const 0] [:halt]]
        parent (dvm/attach-image (vm/run (stack-vm base)) program
                                 vm/stack-contract)
        parked (vm/run (start-at parent
                                 (dvm/absolute-pc parent
                                                  [(dc/image-hash program)
                                                   0])))
        entry (first (:wait-set parked))
        lift #(dvm/image-pc parked %)
        lifted (-> entry
                   (update :pc lift)
                   (update :continuation
                           (fn [k] (mapv #(update % :return-pc lift) k))))
        receiver (stack-vm program)
        lower #(dvm/absolute-pc receiver %)
        body #(lower (lift %))
        lowered (-> lifted
                    (update :pc lower)
                    (update :continuation
                            (fn [k] (mapv #(update % :return-pc lower) k)))
                    (update :stack #(rebase-closures body %))
                    (update :frames #(rebase-closures body %))
                    (update :continuation #(rebase-closures body %))
                    (assoc :image (dc/image-hash program)))]
    (testing "the parked pcs fall in the attached row, not the base"
      (is (= (dc/image-hash program) (first (:pc lifted))))
      (is (= (+ (count base) (second (:pc lifted))) (:pc entry))))
    (testing "the receiving table places every pc"
      (is (= (second (:pc lifted)) (:pc lowered)))
      (is (= (mapv #(- (:return-pc %) (count base)) (:continuation entry))
             (mapv :return-pc (:continuation lowered)))))
    (testing "the lowered entry runs to the same value"
      (is (= 41 (vm/value (vm/run (dvm/stack-restore receiver lowered
                                                     41)))))
      (is (= 41 (vm/value (vm/run (dvm/stack-restore
                                    (assoc parked :blocked? false
                                           :wait-set [])
                                    entry
                                    41))))))))


;; =============================================================================
;; Register kernel
;; =============================================================================

(deftest register-attach-appends-relocated-and-records-a-row-test
  (let [base (register-image (lit 0))
        module (register-image module-ast)
        vm (register-vm base)
        attached (rvm/attach-image vm module vm/register-contract)
        offset (count (:instructions base))
        combined (:segment attached)]
    (testing "load-image records the base row [R 0 n]"
      (is (= [[(rcode/register-hash base) 0 offset]] (:images vm))))
    (testing "instructions and bodies are shifted by the held length"
      (is (= (+ offset (count (:instructions module)))
             (count (:instructions combined))))
      (is (= (mapv #(-> % (update :start + offset) (update :end + offset))
                   (:bodies module))
             (subvec (:bodies combined) (count (:bodies base))))))
    (testing "one row; :hash is R of the concatenation"
      (is (= [(rcode/register-hash module) offset
              (count (:instructions module))]
             (peek (:images attached))))
      (is (= (rcode/register-hash combined) (:hash attached))))
    (testing "the registers are untouched"
      (is (= (select-keys vm [:pc :registers :frames :continuation
                              :halted? :store :value])
             (select-keys attached [:pc :registers :frames :continuation
                                    :halted? :store :value]))))
    (testing "an image already held is not attached twice"
      (is (= attached
             (rvm/attach-image attached module vm/register-contract))))))


(deftest register-empty-attach-and-out-of-range-pcs-test
  (let [base (register-image (lit 0))
        module (register-image module-ast)
        attached (rvm/attach-image (register-vm base) module
                                   vm/register-contract)
        ident (rcode/register-hash module)
        off (count (:instructions base))
        n (count (:instructions module))]
    (testing "an empty image attaches no row and leaves the hash"
      (is (= attached
             (rvm/attach-image attached
                               {:bodies [], :instructions []}
                               vm/register-contract))))
    (testing "absolute-pc places exactly the pcs image-pc lifts"
      (is (= off (rvm/absolute-pc attached [ident 0])))
      (is (= (+ off n) (rvm/absolute-pc attached [ident n]))
          "one past the end of the last row")
      (is (nil? (rvm/absolute-pc attached [ident (inc n)])))
      (is (nil? (rvm/absolute-pc attached [ident -1])))
      (is (nil? (rvm/absolute-pc attached [(rcode/register-hash base) off]))
          "one past a row that is not the last is the next row's pc"))))


(deftest register-parked-entry-restores-after-an-attach-test
  (let [parked (vm/run (register-vm (register-image parking-ast)))
        entry (first (:wait-set parked))
        attached (rvm/attach-image parked (register-image module-ast)
                                   vm/register-contract)]
    (testing "the entry records its image row"
      (is (= (:hash parked) (:image entry)))
      (is (seq (:continuation entry))))
    (stream/append! (stream-of attached) 41)
    (let [done (vm/run attached)]
      (is (= 41 (vm/value done)))
      (testing ":segment is never assigned from the entry or a frame"
        (is (= (:segment attached) (:segment done)))
        (is (= (:images attached) (:images done)))
        (is (= (:hash attached) (:hash done)))))
    (testing "an entry naming no row of the table is refused"
      (is (= :continuation-format
             (:rule (ex-data-of
                      #(rvm/register-restore attached
                                             (assoc entry :image "absent")
                                             41))))))))


(deftest register-entry-parked-after-an-attach-restores-test
  (let [program (register-image parking-ast)
        parent (rvm/attach-image (vm/run (register-vm (register-image
                                                        (lit 0))))
                                 program
                                 vm/register-contract)
        parked (vm/run (register-start-at
                         parent
                         (rvm/absolute-pc parent
                                          [(rcode/register-hash program) 0])))
        entry (first (:wait-set parked))]
    (testing "the payload names a concatenated code space and its table"
      (is (= 2 (count (:images entry))))
      (is (= (:images parked) (:images entry))))
    (testing "each image of the code space is checked alone, and the
              entry restores"
      (stream/append! (stream-of parked) 41)
      (is (= 41 (vm/value (vm/run parked)))))
    (testing "a table row naming an image the code space does not hold
              is a defect"
      (is (= :continuation-segment
             (:rule (ex-data-of
                      #(rvm/register-restore
                         parked
                         (assoc-in entry [:images 1 0] "forged")
                         41))))))))


(deftest register-entry-table-must-cover-the-code-space-exactly-test
  (let [program (register-image parking-ast)
        parked-on (fn [base]
                    (let [parent (rvm/attach-image base program
                                                   vm/register-contract)
                          pc (rvm/absolute-pc parent
                                              [(rcode/register-hash program)
                                               0])]
                      (vm/run (register-start-at parent pc))))
        parked (parked-on (vm/run (register-vm (register-image (lit 0)))))
        entry (first (:wait-set parked))
        [[_ _ base-len] [_ off len]] (:images entry)
        defect (fn [parked entry]
                 (ex-data-of #(rvm/register-restore parked entry 41)))]
    (testing "a missing row leaves instructions uncovered"
      (let [d (defect parked (update entry :images subvec 0 1))]
        (is (= :continuation-segment (:rule d)))
        (is (= base-len (:covered d)))))
    (testing "an overlapping row is refused"
      (let [d (defect parked
                (assoc-in entry [:images 1] [(first (peek (:images
                                                            entry)))
                                             (dec off) (inc len)]))]
        (is (= :continuation-segment (:rule d)))
        (is (= (dec off) (second (:row d))))))
    (testing "reordered rows are refused"
      (let [d (defect parked (update entry :images (comp vec reverse)))]
        (is (= :continuation-segment (:rule d)))
        (is (some? (:row d)))))
    (testing "a zero-length row's identity is checked too"
      (let [empty-parked (parked-on (register-vm nil))
            empty-entry (first (:wait-set empty-parked))]
        (is (zero? (nth (first (:images empty-entry)) 2)))
        (is (nil? (defect empty-parked empty-entry))
            "the genuine empty base row is accepted")
        (let [d (defect empty-parked
                  (assoc-in empty-entry [:images 0 0] "forged"))]
          (is (= :continuation-segment (:rule d)))
          (is (= "forged" (first (:row d)))))))))


(deftest register-call-in-flight-survives-a-nested-attach-test
  (let [parked (vm/run (register-vm (register-image parking-ast)))
        frame (peek (:continuation parked))
        module (register-image module-ast)
        attached (rvm/attach-image parked module vm/register-contract)
        _ (stream/append! (stream-of attached) 41)
        done (vm/run attached)]
    (testing "no call frame carries the code space"
      (is (some? frame))
      (is (not (contains? frame :segment)))
      (is (not (contains? frame :hash))))
    (testing "the callee's return lands in the grown code space"
      (is (= 41 (vm/value done)))
      (is (= (:segment attached) (:segment done)))
      (is (= (:hash attached) (:hash done))))
    (testing "the attachment is intact after the return and runs"
      (let [start (rvm/absolute-pc done [(rcode/register-hash module) 0])
            defined (vm/run (register-start-at done start))]
        (is (= :closure (:type (get (vm/store defined) 'f))))))))


(defn- register-export
  []
  (let [child (vm/run (register-vm (register-image module-ast)))
        f (get (vm/store child) 'f)]
    {:closure f, :marker (rvm/image-pc child (:body-pc f))}))


(defn- register-apply-export
  [parent {:keys [closure marker]}]
  (let [attached (rvm/attach-image parent (register-image module-ast)
                                   vm/register-contract)
        body-pc (rvm/absolute-pc attached marker)
        caller (register-image caller-ast)
        with-caller (-> attached
                        (assoc-in [:store 'f] (assoc closure :body-pc body-pc))
                        (rvm/attach-image caller vm/register-contract))
        start (rvm/absolute-pc with-caller [(rcode/register-hash caller) 0])]
    {:body-pc body-pc,
     :value (vm/value (vm/run (register-start-at with-caller start)))}))


(deftest register-exported-closure-relocates-per-receiver-test
  (let [{:keys [closure] :as export} (register-export)
        short-parent (vm/run (register-vm (register-image (lit 0))))
        long-parent (vm/run (register-vm (register-image parking-ast)))
        a (register-apply-export short-parent export)
        b (register-apply-export long-parent export)]
    (testing "each receiver places the body at its own nonzero offset"
      (is (< (:body-pc closure) (:body-pc a) (:body-pc b))))
    (testing "two tasks of different lengths each apply the export"
      (is (= 42 (:value a)))
      (is (= 42 (:value b))))))


(deftest register-continuation-lifted-after-attach-rebases-test
  (let [program (register-image parking-ast)
        parent (rvm/attach-image (vm/run (register-vm (register-image
                                                        (lit 0))))
                                 program
                                 vm/register-contract)
        parked (vm/run (register-start-at
                         parent
                         (rvm/absolute-pc parent
                                          [(rcode/register-hash program) 0])))
        entry (first (:wait-set parked))
        receiver (register-vm program)
        rebase #(rvm/absolute-pc receiver (rvm/image-pc parked %))
        rebase-frame #(-> % (update :site-pc rebase) (update :return-pc rebase))
        lowered (-> entry
                    (update :pc rebase)
                    (update :site-pc rebase)
                    (update :continuation #(mapv rebase-frame %))
                    (update :regs #(rebase-closures rebase %))
                    (update :frames #(rebase-closures rebase %))
                    (update :continuation #(rebase-closures rebase %))
                    (assoc :segment (:segment receiver)
                           :hash (:hash receiver)
                           :images (:images receiver)
                           :image (rcode/register-hash program)))]
    (testing "the receiving table places every pc"
      (is (< (:pc lowered) (:pc entry)))
      (is (= (- (:pc entry) (:pc lowered))
             (- (:return-pc (peek (:continuation entry)))
                (:return-pc (peek (:continuation lowered)))))))
    (testing "the lowered entry runs to the same value"
      (is (= 41 (vm/value (vm/run (rvm/register-restore receiver lowered
                                                        41))))))))


;; =============================================================================
;; Semantic kernel
;; =============================================================================

(def ^:private load-ast
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(defn- semantic-vm
  []
  (semantic/create-vm {:make-stream tu/make-stream,
                       :capability-secret tu/secret
                       :primitives vm/primitives}))


(deftest semantic-attach-mints-a-local-id-and-an-alias-test
  (let [module (semantic-vector module-ast)
        vm (semantic/load-vector (semantic-vm) (semantic-vector (lit 0))
                                 vm/semantic-contract)
        attached (semantic/attach-image vm module vm/semantic-contract)
        address (jing/segment-key module)
        local (get (:code-aliases attached) address)]
    (testing "a fresh local id below every held id, aliased by address"
      (is (some? local))
      (is (not (contains? (:code vm) local)))
      (is (= address (get-in attached [:code local :address]))))
    (testing "control, env, stack, k, program, and store are unchanged"
      (is (= (select-keys vm [:control :env :stack :k :program :store
                              :halted? :value])
             (select-keys attached [:control :env :stack :k :program :store
                                    :halted? :value]))))
    (testing "an image already aliased is not attached twice"
      (is (= attached
             (semantic/attach-image attached module vm/semantic-contract))))
    (testing "the vector is admitted under the stamp"
      (is (= :contract-missing
             (:rule (ex-data-of
                      #(semantic/attach-image vm module nil))))))))


(deftest semantic-parked-entry-restores-after-an-attach-test
  (let [parked (vm/run (load-ast (semantic-vm) (vm/ast->datoms parking-ast)))
        entry (first (:wait-set parked))
        attached (semantic/attach-image parked (semantic-vector module-ast)
                                        vm/semantic-contract)]
    (is (seq (:stack entry)))
    (is (= (:code parked) (select-keys (:code attached) (keys (:code parked))))
        "existing local ids are never renumbered")
    (stream/append! (stream-of attached) 41)
    (is (= 41 (vm/value (vm/run attached))))))


(defn- semantic-apply-export
  [parent closure address]
  (let [attached (semantic/attach-image parent (semantic-vector module-ast)
                                        vm/semantic-contract)
        local (get (:code-aliases attached) address)
        lowered (assoc closure :segment local)]
    {:local local,
     :value (-> attached
                (assoc-in [:store 'f] lowered)
                (semantic/load-vector (semantic-vector caller-ast)
                                      vm/semantic-contract)
                vm/run
                vm/value)}))


(deftest semantic-exported-closure-lowers-per-receiver-test
  (let [module (semantic-vector module-ast)
        child (vm/run (semantic/load-vector (semantic-vm) module
                                            vm/semantic-contract))
        closure (get (vm/store child) 'f)
        address (some (fn [[a l]] (when (= l (:segment closure)) a))
                      (:code-aliases child))
        short-parent (semantic-vm)
        long-parent (-> (semantic-vm)
                        (semantic/load-vector (semantic-vector (lit 0))
                                              vm/semantic-contract)
                        (semantic/load-vector (semantic-vector (lit 1))
                                              vm/semantic-contract))
        a (semantic-apply-export short-parent closure address)
        b (semantic-apply-export long-parent closure address)]
    (is (= (jing/segment-key module) address))
    (testing "the receivers mint different local ids for one address"
      (is (not= (:local a) (:local b))))
    (testing "each applies the export"
      (is (= 42 (:value a)))
      (is (= 42 (:value b))))))


(deftest semantic-closure-entry-outside-its-image-is-refused-test
  (let [image (semantic-vector module-ast)
        child (vm/run (semantic/load-vector (semantic-vm) image
                                            vm/semantic-contract))
        closure (get (vm/store child) 'f)
        marker (module/lift-closure child closure identity)
        lower #(module/lower-closure child (assoc marker :yin.k/entry %)
                                     identity)]
    (is (= (:entry closure) (:entry (lower (:entry closure)))))
    (doseq [entry [(count image) -1 nil]]
      (is (= :origin-not-attached (:reason (ex-data-of #(lower entry))))
          (str "entry " entry)))))


;; =============================================================================
;; AST walker
;; =============================================================================

(defn- walker-vm
  []
  (walker/create-vm {:make-stream tu/make-stream, :capability-secret tu/secret,
                     :primitives vm/primitives}))


(defn- load-rows
  [vm ast]
  (walker/vm-load-rows vm (vm/ast->semantic-bytecode ast) vm/ast-contract))


(defn- lambda-row-id
  "The id of the `:lambda` row with `params` in `bc`."
  [bc params]
  (some (fn [[id row]]
          (when (and (= :lambda (nth row 1)) (= params (nth row 2))) id))
        (:rows bc)))


(deftest walker-load-annotates-lambda-nodes-and-closures-test
  (let [bc (vm/ast->semantic-bytecode module-ast)
        done (vm/run (load-rows (walker-vm) module-ast))
        closure (get (vm/store done) 'f)
        id (lambda-row-id bc [])]
    (testing "the loader holds the rows and indexes each :lambda row"
      (is (= (:rows bc) (select-keys (:rows done) (keys (:rows bc)))))
      (is (= id (get (:row-index done) [(lit 42) []]))))
    (testing "the :lambda transition copies the row id into the closure"
      (is (= id (:lambda closure))))
    (testing "the program is the decoder's AST, annotation aside"
      (is (= module-ast (dissoc (:program done) :tail?))))))


(deftest walker-attach-adds-rows-and-extends-the-index-test
  (let [base (load-rows (walker-vm) (lit 0))
        bc (vm/ast->semantic-bytecode module-ast)
        attached (walker/attach-image base bc vm/ast-contract)
        id (lambda-row-id bc [])]
    (testing "rows are added by id and the [node params] index extended"
      (is (= (get (:rows bc) id) (get (:rows attached) id)))
      (is (= id (get (:row-index attached) [(lit 42) []]))))
    (testing "control, env, k, program, and store are unchanged"
      (is (= (select-keys base [:control :env :k :program :store :value])
             (select-keys attached [:control :env :k :program :store
                                    :value]))))
    (testing "the rows are admitted under the stamp"
      (is (= :contract-missing
             (:rule (ex-data-of #(walker/attach-image base bc nil))))))))


(deftest walker-parked-entry-restores-after-an-attach-test
  (let [parked (vm/run (load-rows (walker-vm) parking-ast))
        attached (walker/attach-image parked
                                      (vm/ast->semantic-bytecode module-ast)
                                      vm/ast-contract)]
    (is (vm/blocked? attached))
    (is (= (select-keys parked [:control :env :k :wait-set])
           (select-keys attached [:control :env :k :wait-set])))
    (stream/append! (stream-of attached) 41)
    (is (= 41 (vm/value (vm/run attached))))))


(def ^:private shared-body-ast
  "Two `:lambda` rows sharing one body row, `(fn [x] 1)` and `(fn [y] 1)`;
   the program's value is the second closure."
  (app (lambda '[a b] (variable 'b))
       (lambda '[x] (lit 1))
       (lambda '[y] (lit 1))))


(deftest walker-shared-body-lifts-from-its-recorded-row-test
  (let [bc (vm/ast->semantic-bytecode shared-body-ast)
        done (vm/run (load-rows (walker-vm) shared-body-ast))
        closure (vm/value done)
        x-row (lambda-row-id bc '[x])
        y-row (lambda-row-id bc '[y])]
    (testing "one body row under two :lambda rows"
      (is (= (nth (get (:rows bc) x-row) 3) (nth (get (:rows bc) y-row) 3))))
    (testing "the closure lifts from its recorded source row"
      (is (= y-row (:lambda closure)))
      (is (= y-row (walker/closure-row done closure))))
    (testing "a closure with no recorded id resolves by [node params]"
      (is (= x-row (walker/closure-row done
                                       (-> closure
                                           (dissoc :lambda)
                                           (assoc :params '[x]))))))
    (testing "a fabricated pair matching no row is :unrooted-body"
      (is (= :unrooted-body
             (:yin.k/kind (walker/closure-row done
                                              (-> closure
                                                  (dissoc :lambda)
                                                  (assoc :params '[z]))))))
      (is (= :yin.k/non-portable
             (:yin.k/status (walker/closure-row
                              done (assoc closure :params '[z]))))
          "a recorded id whose row disagrees refuses the same way"))))


(deftest walker-row-nodes-are-decoded-once-and-held-test
  (let [child (vm/run (load-rows (walker-vm) module-ast))
        closure (get (vm/store child) 'f)
        body-id (nth (get (:rows child) (:lambda closure)) 3)]
    (testing "row-node answers from the held decode, not a rebuild"
      (is (identical? (walker/row-node child body-id)
                      (walker/row-node child body-id))))
    (testing "the closure's body is the held node itself"
      (is (identical? (:body closure) (walker/row-node child body-id))))
    (testing "an attach extends the held decode without rebuilding it"
      (let [attached (walker/attach-image child
                                          (vm/ast->semantic-bytecode
                                            shared-body-ast)
                                          vm/ast-contract)]
        (is (identical? (walker/row-node child body-id)
                        (walker/row-node attached body-id)))))))


(deftest walker-exported-closure-lowers-structurally-equal-test
  (let [child (vm/run (load-rows (walker-vm) module-ast))
        closure (get (vm/store child) 'f)
        id (walker/closure-row child closure)
        parent (walker/attach-image (vm/run (load-rows (walker-vm) (lit 0)))
                                    (vm/ast->semantic-bytecode module-ast)
                                    vm/ast-contract)
        body (walker/row-node parent (nth (get (:rows parent) id) 3))
        lowered (assoc closure :body body)]
    (is (= (:body closure) body))
    (is (= 42 (-> parent
                  (assoc-in [:store 'f] lowered)
                  (load-rows caller-ast)
                  vm/run
                  vm/value)))))
