(ns yin.vm.linker-test
  "B6 (docs/design/yin.vm.linker.md S11): completion tests for
   `yin.vm.linker`. Every refusal and success is exercised for all four
   format records -- the two de Bruijn formats and, from M2, the two
   storage-derived ones -- through the one `fetch` function. Jing
   addresses are always computed, never pinned; only H and R are pinned
   as goldens (S8). The corpus holds only print-stable scalars (symbols,
   longs), so the same test passes on the JVM, Node, and Dart."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.dht :as dht]
            [dao.jing.mem :as mem]
            [dao.jing.remote :as remote]
            [dao.stream :as stream]
            [dao.stream.apply :as apply]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.rpc :as rpc]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.content :as content]
            [yin.vm.debruijn-code :as dcode]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn-vm-contract-test :as b0]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.linearize :as lin]
            [yin.vm.linker :as linker]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- lit
  [v]
  {:type :literal, :value v})


(defn- v
  [s]
  {:type :variable, :name s})


(defn- lam
  [params body]
  {:type :lambda, :params params, :body body})


(defn- app
  [op & args]
  {:type :application, :operator op, :operands (vec args)})


(defn- tail
  [node]
  (assoc node :tail? true))


(def ^:private worked-example
  "`((fn [x] (+ x 1)) 10)`: one free name, `+`."
  (app (lam '[x] (tail (app (v '+) (v 'x) (lit 1)))) (lit 10)))


(def ^:private closed-program
  "`((fn [x] x) 42)`: no free names at all."
  (app (lam '[x] (tail (v 'x))) (lit 42)))


(def ^:private other-program
  "`(* 6 7)`: a different image, for swapped index entries and pairings."
  (app (v '*) (lit 6) (lit 7)))


(def ^:private unknown-free
  "`(nope 1)`: a free name no receiver resolves."
  (app (v 'nope) (lit 1)))


(def ^:private nested-capture
  "`((fn [x] (fn [y] x)) 1)`: `x` read inside the inner body is bound by
   the outer closure's params, across the out-of-line bodies."
  (app (lam '[x] (tail (lam '[y] (v 'x)))) (lit 1)))


(defn- store-put
  [k val]
  {:type :vm/store-put, :key k, :val val})


(def ^:private def-then-use
  "`((fn [_ _] 0) (vm/store-put x 1) x)`: the binding is the first
   operand; the second operand reads it after the binding executes."
  (app (lam '[a b] (lit 0)) (store-put 'x 1) (v 'x)))


(def ^:private use-then-def
  "`((fn [_ _] 0) x (vm/store-put x 1))`: the read precedes every
   definition of its name."
  (app (lam '[a b] (lit 0)) (v 'x) (store-put 'x 1)))


(def ^:private branch-def
  "`((fn [_ _] 0) (if true (vm/store-put x 1) 0) x)`: the binding sits
   in an `:if` branch; the read keeps its obligation."
  (app (lam '[a b] (lit 0))
       {:type :if, :test (lit true),
        :consequent (store-put 'x 1), :alternate (lit 0)}
       (v 'x)))


(def ^:private body-def
  "`((fn [_ _] 0) (fn [] (vm/store-put x 1)) x)`: the binding sits
   inside a lambda body."
  (app (lam '[a b] (lit 0)) (lam '[] (store-put 'x 1)) (v 'x)))


(def ^:private define-then-apply
  "`((fn [_ _] 0) (vm/store-put x 1) ((fn [] x)))`: the binding executes
   before the application site that may apply the closure reading x."
  (app (lam '[a b] (lit 0)) (store-put 'x 1) (app (lam '[] (v 'x)))))


(def ^:private apply-then-define
  "`((fn [_ _] 0) ((fn [] x)) (vm/store-put x 1))`: the closure reading
   x is applied before the binding executes."
  (app (lam '[a b] (lit 0)) (app (lam '[] (v 'x))) (store-put 'x 1)))


(def ^:private yin-def-then-read
  "`((fn [_ _] 0) (yin/def x 1) x)`: the definition form the macro
   expander produces (section 4.1) binds `x`; the second operand reads
   it after the binding executes."
  (app (lam '[a b] (lit 0))
       (app (v 'yin/def) (lit 'x) (lit 1))
       (v 'x)))


(def ^:private yin-def-reads-its-own-name
  "`((fn [_ _] 0) (yin/def x x))`: the value operand reads `x`, and
   every engine evaluates the value before it writes the key -- the read
   precedes the definition recorded at the invocation position."
  (app (lam '[a b] (lit 0))
       (app (v 'yin/def) (lit 'x) (v 'x))))


(def ^:private yin-def-of-the-quoted-symbol
  "`((fn [_ _] 0) (yin/def x 'yin/def) x)`: a literal whose value is the
   symbol `yin/def` is ordinary data (Rule R), so this is a definition
   of `x` as that symbol."
  (app (lam '[a b] (lit 0))
       (app (v 'yin/def) (lit 'x) (lit 'yin/def))
       (v 'x)))


(def ^:private yin-def-rebound-then-def
  "`((fn [_ _ _ _] 0) (vm/store-put k 1) (yin/def yin/def 0)
     (yin/def x 1) x)`: a definition whose key is `yin/def`. Rule R makes
   the name syntax, never a definition key: `:reserved-name`."
  (app (lam '[a b c d] (lit 0))
       (store-put 'k 1)
       (app (v 'yin/def) (lit 'yin/def) (lit 0))
       (app (v 'yin/def) (lit 'x) (lit 1))
       (v 'x)))


(def ^:private store-put-yin-def-then-def
  "`((fn [_ _ _] 0) (vm/store-put yin/def 0) (yin/def x 1) x)`: a
   direct store-put of the name `yin/def`. Rule R makes the name never a
   store key: `:reserved-name`."
  (app (lam '[a b c] (lit 0))
       (store-put 'yin/def 0)
       (app (v 'yin/def) (lit 'x) (lit 1))
       (v 'x)))


(def ^:private computed-key-then-def
  "`((fn [_ _ _] 0) (yin/def (id 'yin/def) 0) (yin/def x 1) x)`: a
   definition whose key is computed. Rule R admits only a literal-symbol
   key: `:reserved-name`."
  (app (lam '[a b c] (lit 0))
       (app (v 'yin/def) (app (v 'id) (lit 'yin/def)) (lit 0))
       (app (v 'yin/def) (lit 'x) (lit 1))
       (v 'x)))


(def ^:private aliased-yin-def-then-def
  "`((fn [_ _ _] 0) ((fn [setter] (setter 'yin/def 0)) yin/def)
     (yin/def x 1) x)`: the definition operator passed as a value and
   called through a parameter. Rule R admits the variable only as a
   definition's operator: `:reserved-name`."
  (app (lam '[a b c] (lit 0))
       (app (lam '[setter] (app (v 'setter) (lit 'yin/def) (lit 0)))
            (v 'yin/def))
       (app (v 'yin/def) (lit 'x) (lit 1))
       (v 'x)))


(def ^:private scan-vector
  "A hand-built canonical vector: an unconditional binding, one inside a
   jump's target range, a call site, and a closure body reading `w`."
  [[:store-put 'y 1]     ; 0 unconditional
   [:jump 4]             ; 1 skips 2 and 3
   [:store-put 'z 1]     ; 2 inside the jump's target range
   [:call 0 false]       ; 3 also inside it
   [:halt]               ; 4
   [:closure [] 6]       ; 5
   [:var 'w]             ; 6 free, inside the closure body
   [:return]])           ; 7


(def ^:private scan-stack-vector
  "`scan-vector` in the stack dimension's own mnemonics."
  [[:store-put 'y 1]
   [:jump 4]
   [:store-put 'z 1]
   [:call 0 false]
   [:halt]
   [:closure 0 6]
   [:load-free 'w]
   [:return]])


(def ^:private unreadable-vector
  "A well-formed vector whose closure body spans the scope walk cannot
   read: the body starts at pc 0, so no pc is provably outside every
   body and every scanner degrades (section 4.1)."
  [[:closure [] 0] [:store-put 'x 1] [:call 0 false] [:var 'q]
   [:return]])


(def ^:private no-site-vector
  "`(vm/store-put v 1)`, then a closure reading `v`, then halt: nothing
   applies the closure before halt."
  [[:store-put 'v 1]
   [:closure [] 3]
   [:halt]
   [:var 'v]
   [:return]])


(defn- ast-datoms
  [ast]
  (second (vm/ast->datoms-with-root ast)))


(defn- stack-image
  [ast]
  (:image (dl/adapt (ast-datoms ast))))


(defn- register-image
  [ast]
  (:image (rc/adapt (ast-datoms ast))))


(defn- semantic-vector
  "The canonical instruction vector `ast` lowers to: the payload of the
   `:yin.semantic/code` format (section 5.2)."
  [ast]
  (:vector (lin/lower-rows (vm/ast->semantic-bytecode ast))))


(def ^:private vector-formats
  "The three formats whose positions are pcs (section 4.1)."
  [[:H linker/stack-format stack-image]
   [:R linker/register-format register-image]
   [:SEM linker/semantic-format semantic-vector]])


(def ^:private formats
  "Each format record beside the lowering that mints its images. For the
   two storage-derived formats the mint side is `yin.vm.content`
   (section 9) and the identity is its own address, so the index maps
   identity to itself (section 4.2, step 1)."
  [[:H linker/stack-format stack-image]
   [:R linker/register-format register-image]
   [:AST linker/ast-format vm/ast->semantic-bytecode]
   [:SEM linker/semantic-format semantic-vector]])


(defn- storage-derived?
  "True for the two formats whose identity is its own Jing address
   (section 3)."
  [format]
  (contains? #{:yin.ast/code :yin.semantic/code} (:format format)))


(defn- identity-for
  "The identity `image` carries under `format`: the format's own mint
   for the single-payload formats, the root row id for a tree, whose
   mint is the root body (section 5.1)."
  [format image]
  (if (= :yin.ast/code (:format format))
    (:root image)
    ((:identity-fn format) image)))


(defn- stored-payload
  "The payload the mint side stores for `image`: the image itself for
   the single-payload formats, the root row's body for a tree (D3)."
  [format image]
  (if (= :yin.ast/code (:format format))
    (subvec (get (:rows image) (:root image)) 1)
    image))


(def ^:private receiver
  "The standard receiver: the full primitive registry, nothing shadowing."
  {:primitives vm/primitives})


(defn- requested
  "The fetch options of a requester that runs `format`'s own contract,
   with the step-2 `bounds`: the fetch requires the contract (section 4.2
   step 0), so every caller names it."
  ([format] (requested format {}))
  ([format bounds] (assoc bounds :contract (:contract format))))


(defn- publish
  "Store `image` for `format`; return `{:identity id :address a :index
   idx}`. The de Bruijn formats publish through `linker/publish!` and
   read the index back from the datom; the storage-derived formats mint
   through `yin.vm.content` (section 9) and index identity to itself."
  [handle format image]
  (if (storage-derived? format)
    (let [id (if (= :yin.ast/code (:format format))
               (content/materialize-tree! handle image)
               (content/materialize-vector! handle image))]
      {:identity id, :address id, :index {id id}})
    (let [datom (linker/publish! handle format image)]
      {:identity (first datom),
       :address (nth datom 2),
       :index (linker/index-from-datoms format [datom])})))


(defn- raw-publish
  "Store `image` for `format` without the mint side's validation (the
   mint refuses a malformed payload before the write): a tree's rows
   materialize individually, anything else as one payload. Return
   `{:identity id :index idx}`."
  [handle format image]
  (if (= :yin.ast/code (:format format))
    (let [id (:root image)]
      (doseq [row (vals (:rows image))]
        (jing/materialize! handle (subvec row 1)))
      {:identity id, :index {id id}})
    (let [id ((:identity-fn format) image)
          address (jing/materialize! handle image)]
      {:identity id, :index {id address}})))


(defn- row-tree
  "A one-row tree `{:root id, :rows {id row}}` whose root body
   `[tag & slots]` is stored under its own true address."
  [body]
  (let [id (jing/segment-key body)]
    {:root id, :rows {id (into [id] body)}}))


(defn- app-body
  "A well-formed `:application` body whose operator slot references
   `child-id`."
  [child-id]
  [:application child-id [] false])


(defn- tamper
  [value]
  [:tampered value])


(defn- tamper-bytes
  "The canonical bytes of the tampered value the bytes decode to."
  [bs]
  (jing/canonical-bytes (tamper (cbor/decode bs))))


(defn- corrupt-store
  "A local store whose reads return corrupted bytes for every address."
  [store]
  (assoc store
         :get-bytes-fn
         (fn [address not-found]
           (let [x ((:get-bytes-fn store) address not-found)]
             (if (identical? x not-found) x (tamper-bytes x))))))


(defn- corrupt-at
  "A local store whose reads return corrupted bytes for `address` alone."
  [store address]
  (assoc store
         :get-bytes-fn
         (fn [a not-found]
           (let [x ((:get-bytes-fn store) a not-found)]
             (if (and (= a address) (not (identical? x not-found)))
               (tamper-bytes x)
               x)))))


(defn- counting-store
  "A local store that counts its reads per address in the atom `counts`."
  [store counts]
  (assoc store
         :get-bytes-fn
         (fn [a not-found]
           (swap! counts update a (fnil inc 0))
           ((:get-bytes-fn store) a not-found))))


;; =============================================================================
;; Goldens: H and R only, never a Jing address (S8)
;; =============================================================================

(deftest pinned-identities-are-host-independent
  (testing "the receiver computes the same H and R on every host"
    (is (= (str "52791d4a2d0f4649b6d90e0c7e0a9067"
                "c96008ed4ecdc6b77f08a15644893d0c")
           (dcode/image-hash (stack-image worked-example))))
    (is (= (str "c0aefe2fa287cbc702e6680b4c783516"
                "7cb6458b05680e9271b331dd8c42bb21")
           (rcode/register-hash (register-image worked-example))))))


;; =============================================================================
;; Free-name scanners and format records
;; =============================================================================

(deftest free-name-scanners-read-every-load-free
  (is (= '[+] (linker/stack-free-names (stack-image worked-example))))
  (is (= '[+] (linker/register-free-names (register-image worked-example))))
  (is (= [] (linker/stack-free-names (stack-image closed-program))))
  (is (= [] (linker/register-free-names (register-image closed-program))))
  (is (= '[nope] (linker/stack-free-names (stack-image unknown-free))))
  (is (= '#{+} (linker/semantic-free-names
                 (semantic-vector worked-example))))
  (is (= '#{nope} (linker/semantic-free-names
                    (semantic-vector unknown-free))))
  (is (= '#{} (linker/semantic-free-names
                (semantic-vector closed-program))))
  (is (= '#{} (linker/semantic-free-names
                (semantic-vector nested-capture)))
      "a read in the inner body binds through the outer closure")
  (is (= '[+]
         (mapv :name ((:obligations-fn linker/ast-format)
                      (vm/ast->semantic-bytecode worked-example))))
      "the AST scanner yields the section 4.1 records")
  (is (= '[nope]
         (mapv :name ((:obligations-fn linker/ast-format)
                      (vm/ast->semantic-bytecode unknown-free)))))
  (is (= '[]
         (mapv :name ((:obligations-fn linker/ast-format)
                      (vm/ast->semantic-bytecode closed-program))))))


(deftest format-records-name-their-contract
  (is (= :yin.debruijn.code (:format linker/stack-format)))
  (is (= :yin.debruijn.register (:format linker/register-format)))
  (is (= :yin.semantic/code (:format linker/semantic-format)))
  (is (= :yin.ast/code (:format linker/ast-format)))
  (is (= "b2" (:contract linker/stack-format)))
  (is (= "r2" (:contract linker/register-format)))
  (is (= "v3" (:contract linker/semantic-format)))
  (is (= "v3" (:contract linker/ast-format)))
  (is (= [vm/stack-contract vm/register-contract vm/semantic-contract
          vm/ast-contract]
         (mapv :contract [linker/stack-format linker/register-format
                          linker/semantic-format linker/ast-format]))
      "each record implements the contract its backend loader requires")
  (is (= :yin.debruijn.code/address
         (linker/address-attribute linker/stack-format)))
  (is (= :yin.debruijn.register/address
         (linker/address-attribute linker/register-format))))


;; =============================================================================
;; Section 4.1: the position-bearing scanners
;; =============================================================================

(deftest ast-scanners-yield-position-bearing-records
  (let [tree (vm/ast->semantic-bytecode def-then-use)
        root (:root tree)
        {:keys [obligations-fn definitions-fn applications-fn]}
        linker/ast-format]
    (is (= [{:name 'x, :at [root [[3 0]]], :conditional? false}]
           (definitions-fn tree))
        "the store-key query carries the binding row's path")
    (is (= [{:name 'x, :at [root [[3 1]]], :in-body? false}]
           (obligations-fn tree))
        "vm/free-names' rules, wrapped to carry each occurrence's path")
    (is (= [{:at [root [[3 2]]]}] (applications-fn tree))
        "one application site per application row, at its invocation
         position: the walker applies only after the operands run")))


(deftest ast-definitions-mark-branch-and-body-bindings-conditional
  (let [{:keys [definitions-fn]} linker/ast-format]
    (doseq [[label ast] [[:branch branch-def] [:body body-def]]
            :let [tree (vm/ast->semantic-bytecode ast)
                  root (:root tree)]]
      (testing label
        (is (= [{:name 'x, :at [root [[3 0] 3]], :conditional? true}]
               (definitions-fn tree))
            "any :if branch or lambda body enclosing the binding makes
             it conditional")))))


(deftest vector-scanners-yield-position-bearing-records
  (doseq [[label format v] [[:SEM linker/semantic-format scan-vector]
                            [:H linker/stack-format scan-stack-vector]]]
    (testing label
      (is (= [{:name 'w, :at 6, :in-body? true}]
             ((:obligations-fn format) v))
          "the free occurrence carries its pc and body enclosure")
      (is (= [{:name 'y, :at 0, :conditional? false}
              {:name 'z, :at 2, :conditional? true}]
             ((:definitions-fn format) v))
          "the binding inside the jump's target range is conditional")
      (is (= [{:at 3}] ((:applications-fn format) v))
          "the call opcode is one application site"))))


(deftest register-scanners-yield-position-bearing-records
  (let [image (register-image def-then-use)
        {:keys [obligations-fn definitions-fn applications-fn]}
        linker/register-format
        defs (definitions-fn image)
        occs (obligations-fn image)]
    (is (= [{:name 'x, :conditional? false}]
           (mapv #(select-keys % [:name :conditional?]) defs)))
    (is (= [{:name 'x, :in-body? false}]
           (mapv #(select-keys % [:name :in-body?]) occs)))
    (is (< (:at (first defs)) (:at (first occs)))
        "the definition's pc precedes the occurrence's")
    (is (seq (applications-fn image))
        "the call opcodes are application sites")))


;; =============================================================================
;; Step 6: the verified image, one fetch for all four formats (S11.14)
;; =============================================================================

(deftest fetch-returns-the-verified-image
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            image (mint worked-example)
            {:keys [identity index]} (publish store format image)
            res (linker/fetch store index format identity receiver
                              (requested format))]
        (is (linker/ok? res))
        (is (not (linker/refused? res)))
        (is (= (:format format) (:format res)))
        (is (= identity (:identity res)))
        (is (= image (:value res)))
        (is (jing/segment-matches? (:address res)
                                   (stored-payload format image)))
        (is (contains? (set (map :name (:obligations res))) '+)
            "the free-name obligations travel with the image")
        (is (= res (linker/fetch store index format identity receiver
                                 (requested format)))
            "fetch is a pure read: no cache, no registry changes the answer")
        (jing/close! store)))))


(deftest index-may-be-a-map-or-a-function
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            {:keys [identity index]} (publish store format
                                              (mint closed-program))]
        (is (linker/ok? (linker/fetch store index format identity
                                      {} (requested format))))
        (is (linker/ok? (linker/fetch store #(get index %) format identity
                                      {} (requested format))))
        (jing/close! store)))))


;; =============================================================================
;; Step 0: the admission contract (S11.7)
;; =============================================================================

(deftest a-request-naming-another-contract-is-contract-mismatch
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            image (mint worked-example)
            {:keys [identity index]} (publish store format image)
            counts (atom {})
            res (linker/fetch (counting-store store counts) index format
                              identity receiver {:contract "other"})]
        (is (= {:status :refused, :reason :contract-mismatch,
                :expected "other", :actual (:contract format)}
               res)
            "the record's contract is the one implemented")
        (is (zero? (count @counts))
            "no content is requested under the wrong contract")
        (is (linker/ok? (linker/fetch store index format identity
                                      receiver
                                      {:contract (:contract format)}))
            "the record's own contract is admitted")
        (doseq [old ["v2" "b1" "r1"]]
          (is (= :contract-mismatch
                 (:reason (linker/fetch store index format identity
                                        receiver {:contract old})))
              "a requester running a pre-Rule R revision is refused"))
        (jing/close! store)))))


(deftest a-request-omitting-the-contract-is-invalid-request
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            {:keys [identity index]} (publish store format
                                              (mint worked-example))
            counts (atom {})
            fetch (fn [& args]
                    (apply linker/fetch (counting-store store counts)
                           index format identity args))]
        (doseq [res [(fetch) (fetch receiver) (fetch receiver nil)
                     (fetch receiver {}) (fetch receiver {:max-parts 8})
                     (fetch receiver {:contract nil})]]
          (is (= {:status :refused, :reason :invalid-request,
                  :missing :contract}
                 res)
              "the fetch requires the requester's contract"))
        (is (zero? (count @counts))
            "no content is requested without a contract")
        (jing/close! store)))))


;; =============================================================================
;; Step 1 and 2: :absent (S11.4)
;; =============================================================================

(deftest absent-identity-or-payload-is-refused
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            image (mint worked-example)
            identity (identity-for format image)
            unstored (jing/segment-key [:none])]
        (is (= {:status :refused, :reason :absent, :identity identity}
               (linker/fetch store {} format identity receiver
                             (requested format)))
            "step 1: the index has no entry")
        (is (= {:status :refused, :reason :absent, :address unstored}
               (linker/fetch store {identity unstored} format identity
                             receiver (requested format)))
            "step 2: the address has no payload in the store")
        (is (= :absent
               (:reason (linker/fetch store {identity :segment/garbage}
                                      format identity receiver
                                      (requested format))))
            "an index entry that is no Jing address has no payload")
        (jing/close! store)))))


;; =============================================================================
;; Step 2: :address-mismatch (S11.5)
;; =============================================================================

(deftest local-storage-corruption-is-an-address-mismatch
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            image (mint worked-example)
            {:keys [identity index]} (publish store format image)
            res (linker/fetch (corrupt-store store) index format identity
                              receiver (requested format))]
        (is (= :address-mismatch (:reason res)))
        (is (not (contains? res :value))
            "mismatched bytes are refused undecoded")
        (is (= (index identity) (:address res)))
        (jing/close! store)))))


;; RPC-reply corruption is now refused at the client ingress boundary
;; (remote.cljc hash-verify + strict decode on every found reply), so
;; read-address's documented fail-closed catch classifies the handle
;; failure :absent; store-level corruption (above) still reaches the
;; linker's own step-2 check and remains :address-mismatch.
(deftest corrupt-rpc-response-is-classified-absent
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            image (mint worked-example)
            {:keys [identity index]} (publish store format image)
            handlers (remote/default-handlers store)
            client (remote/content-client
                     ::corrupt
                     (fn [_ op args]
                       (let [resp (apply (get handlers op) args)]
                         (update resp :value
                                 #(jing/bytes->base64
                                    (tamper-bytes (jing/base64->bytes %))))))
                     (fn [_] nil))]
        (is (= :absent
               (:reason (linker/fetch client index format identity
                                      receiver (requested format)))))
        (jing/close! client)
        (jing/close! store)))))


;; =============================================================================
;; DHT: a peer serving mismatched content is rejected before load (S11.6)
;; =============================================================================

(def ^:private any-address
  "A GridNet serving key for a peer that answers every address with one
   payload -- never a real address, so a per-address map and an
   any-address payload cannot collide."
  ::any-address)


(defrecord ^:private GridNet
  [self peers served]

  dht/IDhtNet

  (self-peer [_] self)


  (known-peers [_ _target n] (vec (take n peers)))


  (find-closer [_ _peer _target] peers)


  (store-content! [_ _peer _address _payload] false)


  (fetch-content
    [_ peer address]
    (if-let [pair (find served (:id peer))]
      (if-let [at (or (find (val pair) address)
                      (find (val pair) any-address))]
        {:found? true,
         :value (jing/bytes->base64 (jing/canonical-bytes (val at)))}
        {:found? false, :value nil})
      {:found? false, :value nil}))


  (close-net! [_] nil))


(defn- peer
  [port]
  {:id (dht/node-id "127.0.0.1" port), :host "127.0.0.1", :port port})


(defn- grid-handle
  "A DHT handle over an empty local store whose peers serve `served`
   (peer port -> an address -> payload map; the `any-address` key makes a
   peer answer every address with one payload)."
  [served]
  (let [self (peer 1)
        peers (mapv peer (keys served))]
    (dht/create-content-dht
      {:net (->GridNet self peers
                       (into {} (map (fn [[p x]] [(:id (peer p)) x])) served)),
       :local (mem/create-content-mem)})))


(deftest dht-peer-with-mismatched-content-is-absent
  (doseq [[label format mint] formats]
    (testing label
      (let [image (mint worked-example)
            identity (identity-for format image)
            index (if (storage-derived? format)
                    {identity identity}
                    {identity (jing/segment-key image)})
            payload (stored-payload format image)
            ;; a multi-part format needs each row served at its own
            ;; address; a single payload is served for any address
            honest-value (if (= :yin.ast/code (:format format))
                           (into {}
                                 (map (fn [[id row]] [id (subvec row 1)]))
                                 (:rows image))
                           {any-address payload})
            forged (grid-handle {2 {any-address (tamper payload)}})
            honest (grid-handle {2 {any-address (tamper payload)}
                                 3 honest-value})]
        (is (= :absent
               (:reason (linker/fetch forged index format identity
                                      receiver (requested format))))
            "make-get filters the forged payload; no peer has valid data")
        (is (linker/ok? (linker/fetch honest index format identity
                                      receiver (requested format)))
            "a later honest peer is accepted")
        (is (= payload
               (jing/get (:local honest) (index identity) nil))
            "the verified payload is cached by the DHT, not by the linker")
        (jing/close! forged)
        (jing/close! honest)))))


;; =============================================================================
;; Step 3: :hash-mismatch (S11.7)
;; =============================================================================

(defn- foreign-identity
  "An identity `image` would not have under `format`: another descriptor
   hash for the de Bruijn formats (same canonical bytes, another
   descriptor), another payload's key under another registered algorithm
   for the storage-derived ones, whose identity has no preimage but the
   payload itself (section 3)."
  [format image]
  (if (storage-derived? format)
    (jing/segment-key [:a-different-payload] {:algorithm :sha256})
    (jing/sha256
      (str "a-different-descriptor-hash"
           (if (= linker/stack-format format)
             (dcode/encode-image image)
             (rcode/encode-register-image image))))))


(deftest wrong-program-at-the-indexed-address-is-a-hash-mismatch
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            wanted (identity-for format (mint worked-example))
            {:keys [identity address]} (publish store format
                                                (mint other-program))
            swapped {wanted address}
            foreign (foreign-identity format (mint other-program))]
        (is (= {:status :refused, :reason :hash-mismatch,
                :expected wanted, :actual identity}
               (linker/fetch store swapped format wanted receiver
                             (requested format)))
            "a stale or swapped index entry")
        (is (= {:status :refused, :reason :hash-mismatch,
                :expected foreign, :actual identity}
               (linker/fetch store {foreign address} format foreign
                             receiver (requested format)))
            "a descriptor or contract version disagreement")
        (jing/close! store)))))


(deftest a-vector-index-entry-at-another-vector-is-a-hash-mismatch
  (let [store (mem/create-content-mem)
        wanted (semantic-vector worked-example)
        other (semantic-vector other-program)
        other-address (content/materialize-vector! store other)
        index {(jing/segment-key wanted) other-address}]
    (is (= {:status :refused, :reason :hash-mismatch,
            :expected (jing/segment-key wanted),
            :actual (jing/segment-key other)}
           (linker/fetch store index linker/semantic-format
                         (jing/segment-key wanted) receiver
                         (requested linker/semantic-format)))
        "the index pointed the identity at a different valid vector")
    (jing/close! store)))


;; =============================================================================
;; Step 4: :descriptor-defect (S11.8, S11.9)
;; =============================================================================

(defn- invalid-register-image
  "The worked example's register image with one boundary tuple rewritten
   to a hashable but validator-rejected one."
  []
  (let [image (register-image worked-example)
        end (:end (first (:bodies image)))]
    (update-in image [:instructions end] (fn [t] [:return (nth t 1)]))))


(deftest structural-defect-is-refused-before-load
  (doseq [[label format image]
          [[:H linker/stack-format [[:load-bound 5 0] [:halt]]]
           [:R linker/register-format (invalid-register-image)]
           [:SEM linker/semantic-format [[:jump 9]]]
           [:AST linker/ast-format (row-tree [:literal 1 :extra])]]]
    (testing label
      (let [store (mem/create-content-mem)
            {:keys [identity index]} (raw-publish store format image)
            res (linker/fetch store index format identity receiver
                              (requested format))]
        (is (= :descriptor-defect (:reason res)))
        (is (= identity (:identity res)))
        (is (= ((:validate-fn format) image) (:defect res)))
        (is (not (contains? res :value)) "the image is never handed over")
        (jing/close! store)))))


(defn- bad-live-image
  "The worked example's register image with one boundary live set
   rewritten to a well-formed but wrong answer."
  []
  (let [image (register-image worked-example)
        pc (first (keep-indexed (fn [pc t]
                                  (when (rcode/boundary-opcodes (nth t 0)) pc))
                                (:instructions image)))
        slot (rcode/live-slot-index
               (nth (nth (:instructions image) pc) 0))
        live (nth (nth (:instructions image) pc) slot)]
    (assoc-in image [:instructions pc slot] (if (seq live) [] [0]))))


(deftest register-live-set-defect-is-refused-before-load
  (let [store (mem/create-content-mem)
        image (bad-live-image)
        {:keys [identity index]} (publish store linker/register-format image)
        res (linker/fetch store index linker/register-format identity
                          receiver (requested linker/register-format))]
    (is (= :descriptor-defect (:reason res)))
    (is (contains? #{:live-exact :live-bounds} (:rule (:defect res))))
    (jing/close! store)))


;; =============================================================================
;; M2: the bounded worklist over a tree (section 9, M2)
;; =============================================================================

(deftest a-tree-fetch-carries-its-parts-and-obligations
  (let [store (mem/create-content-mem)
        tree (vm/ast->semantic-bytecode worked-example)
        root (content/materialize-tree! store tree)
        res (linker/fetch store {root root} linker/ast-format root
                          receiver (requested linker/ast-format))]
    (is (linker/ok? res))
    (is (= root (:address res)) "the identity is its own address")
    (is (= tree (:value res)))
    (is (= (set (keys (:rows tree))) (set (keys (:parts res))))
        "every row of the tree is a fetched part, by address")
    (is (= (into {} (map (fn [[id row]] [id (subvec row 1)])) (:rows tree))
           (:parts res))
        "a part is the stored body at its address")
    (is (= [{:name '+, :at [root [2 3 2]], :in-body? true}]
           (:obligations res))
        "the obligation carries its occurrence path and lambda enclosure")
    (jing/close! store)))


(deftest a-single-payload-fetch-carries-no-parts
  (let [store (mem/create-content-mem)
        image (stack-image worked-example)
        {:keys [identity index]} (publish store linker/stack-format image)
        res (linker/fetch store index linker/stack-format identity
                          receiver (requested linker/stack-format))]
    (is (linker/ok? res))
    (is (not (contains? res :parts)) "no parts map for one payload")
    (is (= image (:value res)))
    (jing/close! store)))


(deftest tree-with-one-absent-child-row-is-absent-naming-it
  (let [store (mem/create-content-mem)
        child-id (jing/segment-key [:literal 1])
        root-body (app-body child-id)
        root (jing/segment-key root-body)]
    (jing/materialize! store root-body)
    (is (= {:status :refused, :reason :absent, :address child-id}
           (linker/fetch store {root root} linker/ast-format root
                         receiver (requested linker/ast-format)))
        "a tree with one absent child row is :absent naming that row")
    (jing/close! store)))


(deftest tree-with-one-corrupt-child-row-is-an-address-mismatch
  (let [store (mem/create-content-mem)
        child-body [:literal 1]
        child-id (jing/segment-key child-body)
        root-body (app-body child-id)
        root (jing/segment-key root-body)]
    (jing/materialize! store root-body)
    (jing/materialize! store child-body)
    (let [res (linker/fetch (corrupt-at store child-id)
                            {root root} linker/ast-format root receiver
                            (requested linker/ast-format))]
      (is (= :address-mismatch (:reason res)))
      (is (= child-id (:address res)) "the corrupt row is named")
      (is (not (contains? res :value))
          "mismatched bytes are refused undecoded"))
    (jing/close! store)))


(deftest tree-with-a-bad-tag-child-fetches-none-of-its-slots
  (let [store (mem/create-content-mem)
        grandchild-id (jing/segment-key [:literal 1])
        child-body [:bogus grandchild-id]
        child-id (jing/segment-key child-body)
        root-body (app-body child-id)
        root (jing/segment-key root-body)
        counts (atom {})]
    (jing/materialize! store root-body)
    (jing/materialize! store child-body)
    (let [res (linker/fetch (counting-store store counts)
                            {root root} linker/ast-format root receiver
                            (requested linker/ast-format))]
      (is (= :descriptor-defect (:reason res))
          "a root well-formed but for the child's tag refuses")
      (is (= child-id (:address res)) "the malformed row is named")
      (is (= {:rule :tag, :path []} (:defect res)))
      (is (= 1 (get @counts root 0)) "the root is read once")
      (is (= 1 (get @counts child-id 0)) "the child is read once")
      (is (zero? (get @counts grandchild-id 0))
          "no fetch of that child's slots"))
    (jing/close! store)))


(deftest exceeding-a-composition-bound-is-parts-limit
  (let [store (mem/create-content-mem)
        leaf-body [:literal 5]
        leaf-id (jing/segment-key leaf-body)
        mid-body (app-body leaf-id)
        mid-id (jing/segment-key mid-body)
        root-body (app-body mid-id)
        root (jing/segment-key root-body)]
    (doseq [body [root-body mid-body leaf-body]]
      (jing/materialize! store body))
    (testing "the :max-parts bound"
      (let [res (linker/fetch store {root root} linker/ast-format root
                              receiver
                              (requested linker/ast-format {:max-parts 1}))]
        (is (= :parts-limit (:reason res)))
        (is (= {:bound :max-parts, :address mid-id}
               (select-keys res [:bound :address]))
            "naming the bound and the address it was hit at")))
    (testing "the :max-depth bound"
      (let [res (linker/fetch store {root root} linker/ast-format root
                              receiver
                              (requested linker/ast-format {:max-depth 1}))]
        (is (= :parts-limit (:reason res)))
        (is (= {:bound :max-depth, :address leaf-id}
               (select-keys res [:bound :address])))))
    (testing "the :max-bytes bound"
      (let [res (linker/fetch store {root root} linker/ast-format root
                              receiver
                              (requested linker/ast-format {:max-bytes 1}))]
        (is (= :parts-limit (:reason res)))
        (is (= {:bound :max-bytes, :address root}
               (select-keys res [:bound :address]))
            "the first payload alone exceeds it")))
    (jing/close! store)))


(deftest max-bytes-is-checked-before-decode
  (let [store (mem/create-content-mem)
        ;; one row that is both oversized and malformed: the budget, not
        ;; the row, must be what refuses it under a tight bound
        big (into [:literal] (concat (range 512) [:extra]))
        root (jing/materialize! store big)]
    (is (= {:status :refused, :reason :parts-limit, :bound :max-bytes,
            :address root}
           (select-keys (linker/fetch store {root root} linker/ast-format
                                      root receiver
                                      (requested linker/ast-format
                                                 {:max-bytes 64}))
                        [:status :reason :bound :address]))
        "the oversized row is refused before its slots are decoded and
         judged")
    (is (= :descriptor-defect
           (:reason (linker/fetch store {root root} linker/ast-format
                                  root receiver (requested linker/ast-format))))
        "under the finite defaults the same row is judged: the refusal
         above is the budget's, not the row's")
    (jing/close! store)))


(deftest a-hostile-byte-store-cannot-bypass-the-byte-cap
  (let [store (mem/create-content-mem
                {(jing/segment-key [:literal 1])
                 (jing/canonical-bytes (into [:literal] (range 1000)))})
        address (jing/segment-key [:literal 1])
        res (linker/fetch store {address address} linker/ast-format
                          address receiver
                          (requested linker/ast-format {:max-bytes 64}))]
    (is (= {:status :refused, :reason :parts-limit, :bound :max-bytes,
            :address address}
           (select-keys res [:status :reason :bound :address]))
        "the byte cap is checked before hashing or decoding, so the
         oversized payload is refused even though its address mismatches")
    (let [res (linker/fetch store {address address} linker/ast-format
                            address receiver (requested linker/ast-format))]
      (is (= :address-mismatch (:reason res)))
      (is (not (contains? res :value))
          "under the defaults the mismatch is named, and its bytes are
           not decoded for refusal evidence"))
    (jing/close! store)))


(deftest the-parts-budget-is-enforced-at-enqueue-time
  (let [store (mem/create-content-mem)
        leaf-id (jing/segment-key [:literal 5])
        other-leaf (jing/segment-key [:literal 6])
        mid-body (app-body leaf-id)
        mid-id (jing/segment-key mid-body)
        root-body [:application mid-id [other-leaf] false]
        root (jing/segment-key root-body)]
    (doseq [body [root-body mid-body]]
      (jing/materialize! store body))
    (let [counts (atom {})
          res (linker/fetch (counting-store store counts)
                            {root root} linker/ast-format root
                            receiver
                            (requested linker/ast-format {:max-parts 2}))]
      (is (= {:bound :max-parts, :address other-leaf}
             (select-keys res [:bound :address]))
          "the child beyond the budget is refused while its sibling is
           enqueued, before either is fetched")
      (is (zero? (get @counts mid-id 0))
          "no child beyond the budget is ever read")
      (is (= 1 (get @counts root 0))))
    (jing/close! store)))


(deftest a-zero-parts-budget-refuses-the-root-unread
  (let [store (mem/create-content-mem)
        root (jing/materialize! store [:literal 5])
        counts (atom {})
        res (linker/fetch (counting-store store counts) {root root}
                          linker/ast-format root receiver
                          (requested linker/ast-format {:max-parts 0}))]
    (is (= {:status :refused, :reason :parts-limit, :bound :max-parts,
            :address root}
           (select-keys res [:status :reason :bound :address]))
        "a non-positive parts quota refuses even a single-part root as
         :parts-limit naming the bound and the root")
    (is (zero? (get @counts root 0)) "the root is not read")
    (jing/close! store)))


(deftest a-fetch-without-explicit-bounds-is-finite
  (let [store (mem/create-content-mem)
        root (loop [child (jing/segment-key [:literal 1]), i 0]
               (if (= i 300)
                 child
                 (let [body (app-body child)]
                   (jing/materialize! store body)
                   (recur (jing/segment-key body) (inc i)))))
        res (linker/fetch store {root root} linker/ast-format root
                          receiver (requested linker/ast-format))]
    (is (= {:reason :parts-limit, :bound :max-depth}
           (select-keys res [:reason :bound]))
        "the linker's own finite `default-bounds` end the walk")
    (jing/close! store)))


;; =============================================================================
;; Section 4.2 step 5a: the occurrence/definition join
;; =============================================================================

(deftest a-definition-discharges-a-use-after-it
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            {:keys [identity index]} (publish store format
                                              (mint def-then-use))
            res (linker/fetch store index format identity
                              {} (requested format))]
        (is (linker/ok? res))
        (is (= [] (:obligations res))
            "the unconditional definition dominating the read discharges
             it: the image is closed")
        (jing/close! store)))))


(defn- run-fetched
  "The VM that ran the verified image `res` of `format` to completion on
   its own backend, loaded under the record's contract."
  [format res]
  (let [contract (:contract format)
        image (:value res)]
    (vm/run
      (case (:format format)
        :yin.ast/code (ast-walker/vm-load-rows (tu/create-vm) image contract)
        :yin.semantic/code (semantic/load-vector (semantic/create-vm) image
                                                 contract)
        :yin.debruijn.code (dvm/create-vm image
                                          (assoc receiver :contract contract))
        :yin.debruijn.register (rvm/create-vm
                                 image (assoc receiver :contract contract))))))


(deftest a-definition-discharges-its-reads-in-every-format
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            {:keys [identity index]} (publish store format
                                              (mint yin-def-then-read))
            res (linker/fetch store index format identity receiver
                              (requested format))]
        (is (linker/ok? res))
        (is (= [] (:obligations res))
            "the read after the definition is discharged, and the
             definition operator is syntax, never an obligation")
        (is (= 1 (get (vm/store (run-fetched format res)) 'x)))
        (jing/close! store)))))


(deftest a-constant-key-yin-def-application-is-a-definition
  (let [tree (vm/ast->semantic-bytecode yin-def-then-read)
        root (:root tree)]
    (is (= [{:name 'x, :at [root [[3 0] [3 2]]], :conditional? false}]
           ((:definitions-fn linker/ast-format) tree))
        "the definition form is a definition at its invocation position
         -- the application row's path extended one step past its
         operands, where the engine writes the key")
    (is (= [{:name 'x, :at [root [[3 1]]], :in-body? false}]
           ((:obligations-fn linker/ast-format) tree))
        "the definition operator is never an occurrence")))


(deftest vector-definitions-read-the-define-instruction
  (doseq [[label format mint] vector-formats]
    (testing label
      (let [image (mint yin-def-then-read)
            [d] ((:definitions-fn format) image)
            [o] ((:obligations-fn format) image)]
        (is (= {:name 'x, :conditional? false}
               (select-keys d [:name :conditional?]))
            "the :define instruction is the definition")
        (is (= 'x (:name o)) "the read is the only obligation")
        (is (< (:at d) (:at o)) "and the definition precedes it")))))


(deftest a-yin-def-value-operand-read-precedes-the-definition
  (let [tree (vm/ast->semantic-bytecode yin-def-reads-its-own-name)
        root (:root tree)]
    (is (= [{:name 'x, :at [root [[3 0] [3 2]]], :conditional? false}]
           ((:definitions-fn linker/ast-format) tree))
        "the binding is recorded at the invocation position, one step
         past the two operands"))
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            {:keys [identity index]} (publish store format
                                              (mint
                                                yin-def-reads-its-own-name))]
        (is (= {:status :refused, :reason :use-before-definition, :name 'x}
               (select-keys (linker/fetch store index format identity
                                          receiver (requested format))
                            [:status :reason :name]))
            "every engine evaluates the value before it writes the key, so
             the read of x inside it precedes the definition and is not
             discharged: :use-before-definition")
        (jing/close! store)))))


(deftest a-definition-whose-value-is-the-quoted-symbol-links
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            {:keys [identity index]}
            (publish store format (mint yin-def-of-the-quoted-symbol))
            res (linker/fetch store index format identity receiver
                              (requested format))]
        (is (linker/ok? res) "the literal symbol is ordinary data")
        (is (= [] (:obligations res)))
        (is (= 'yin/def (get (vm/store (run-fetched format res)) 'x))
            "x is defined as the symbol")
        (jing/close! store)))))


(defn- reserved-refusal
  "The fetch of `tree` published raw (the mint side refuses it before
   the write), reduced to its reason and defect rule."
  [tree]
  (let [store (mem/create-content-mem)
        {:keys [identity index]} (raw-publish store linker/ast-format tree)
        res (linker/fetch store index linker/ast-format identity receiver
                          (requested linker/ast-format))]
    (jing/close! store)
    {:reason (:reason res), :rule (:rule (:defect res))}))


(def ^:private reserved-refused
  {:reason :descriptor-defect, :rule :reserved-name})


(deftest a-module-that-rebinds-yin-def-is-reserved-name
  (let [tree (vm/ast->semantic-bytecode yin-def-rebound-then-def)]
    (is (thrown? #?(:cljd Object :clj Exception :cljs :default)
          (content/materialize-tree! (mem/create-content-mem) tree))
        "the mint side refuses it before the write")
    (is (= reserved-refused (reserved-refusal tree))
        "yin/def is never a definition key")))


(deftest a-direct-store-put-of-yin-def-is-reserved-name
  (is (= reserved-refused
         (reserved-refusal
           (vm/ast->semantic-bytecode store-put-yin-def-then-def)))
      "yin/def is never a store key"))


(deftest a-computed-key-yin-def-write-is-reserved-name
  (is (= reserved-refused
         (reserved-refusal (vm/ast->semantic-bytecode computed-key-then-def)))
      "a definition takes only a literal-symbol key"))


(deftest an-aliased-yin-def-call-is-reserved-name
  (is (= reserved-refused
         (reserved-refusal
           (vm/ast->semantic-bytecode aliased-yin-def-then-def)))
      "yin/def is a variable only as a definition's operator"))


(defn- rekey
  "`image` of `format` with every `:store-put` and `:define` key renamed
   to `k`: a register instruction carries its key after the destination
   register."
  [format image k]
  (let [slot (if (= :yin.debruijn.register (:format format)) 2 1)
        rekey-t (fn [t]
                  (if (contains? #{:store-put :define} (nth t 0))
                    (assoc t slot k)
                    t))]
    (if (map? image)
      (update image :instructions (partial mapv rekey-t))
      (mapv rekey-t image))))


(deftest a-vector-store-or-definition-key-yin-def-is-reserved-name
  (doseq [[label format mint] vector-formats
          ast [def-then-use yin-def-then-read]]
    (testing label
      (let [store (mem/create-content-mem)
            image (rekey format (mint ast) 'yin/def)
            {:keys [identity index]} (raw-publish store format image)
            res (linker/fetch store index format identity receiver
                              (requested format))]
        (is (= reserved-refused
               {:reason (:reason res), :rule (:rule (:defect res))}))
        (jing/close! store)))))


(deftest a-use-before-every-definition-is-use-before-definition
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            image (mint use-then-def)
            {:keys [identity index]} (publish store format image)
            res (linker/fetch store index format identity
                              {} (requested format))]
        (is (= {:status :refused, :reason :use-before-definition,
                :name 'x}
               (select-keys res [:status :reason :name])))
        (is (= (:at (first ((:obligations-fn format) image))) (:at res))
            "naming the occurrence and its position")
        (jing/close! store)))))


(deftest a-conditional-definition-discharges-nothing
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            {:keys [identity index]} (publish store format
                                              (mint branch-def))]
        (is (= {:status :refused, :reason :unresolved-free, :name 'x}
               (select-keys (linker/fetch store index format identity
                                          {} (requested format))
                            [:status :reason :name]))
            "an occurrence whose only definitions are conditional and
             earlier retains its obligation")
        (jing/close! store)))))


(deftest a-definition-in-a-lambda-body-discharges-nothing
  (doseq [[label format mint] vector-formats]
    (testing label
      (let [store (mem/create-content-mem)
            {:keys [identity index]} (publish store format
                                              (mint body-def))]
        ;; the out-of-line body lands after the main sequence, so the
        ;; only definition is conditional and later
        (is (= {:status :refused, :reason :use-before-definition,
                :name 'x}
               (select-keys (linker/fetch store index format identity
                                          {} (requested format))
                            [:status :reason :name])))
        (jing/close! store))))
  (testing "AST"
    (let [store (mem/create-content-mem)
          {:keys [identity index]}
          (publish store linker/ast-format
                   (vm/ast->semantic-bytecode body-def))]
      ;; the tree's slot order puts the operand before the read: the
      ;; only definition is conditional and earlier
      (is (= {:status :refused, :reason :unresolved-free, :name 'x}
             (select-keys (linker/fetch store index linker/ast-format
                                        identity
                                        {} (requested linker/ast-format))
                          [:status :reason :name])))
      (jing/close! store))))


(deftest a-definition-dominating-every-application-discharges-a-body-occurrence
  (doseq [[label format mint] vector-formats]
    (testing label
      (let [store (mem/create-content-mem)
            {:keys [identity index]} (publish store format
                                              (mint define-then-apply))
            res (linker/fetch store index format identity
                              {} (requested format))]
        (is (linker/ok? res))
        (is (= [] (:obligations res))
            "the binding executes before every application site, so the
             body occurrence is discharged")
        (jing/close! store))))
  (testing "AST"
    (let [store (mem/create-content-mem)
          {:keys [identity index]}
          (publish store linker/ast-format
                   (vm/ast->semantic-bytecode define-then-apply))
          res (linker/fetch store index linker/ast-format identity
                            {} (requested linker/ast-format))]
      (is (linker/ok? res))
      (is (= [] (:obligations res))
          "the binding in the operand executes before the enclosing
           application's invocation, so the body occurrence is
           discharged")
      (jing/close! store))))


(deftest an-application-before-the-definition-retains-the-obligation
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            {:keys [identity index]} (publish store format
                                              (mint apply-then-define))]
        (is (= {:status :refused, :reason :unresolved-free, :name 'x}
               (select-keys (linker/fetch store index format identity
                                          {} (requested format))
                            [:status :reason :name]))
            "the site the definition does not dominate keeps the
             obligation; for the tree the inner application's invocation
             precedes the definition")
        (jing/close! store)))))


(deftest a-body-occurrence-with-no-application-site-is-discharged
  (let [store (mem/create-content-mem)
        address (content/materialize-vector! store no-site-vector)]
    (is (linker/ok? (linker/fetch store {address address}
                                  linker/semantic-format address
                                  {} (requested linker/semantic-format)))
        "nothing applies the closure before halt, so the body occurrence
         runs against every unconditional definition of its name")
    (jing/close! store)))


(deftest an-unreadable-vector-layout-degrades-conservatively
  (let [store (mem/create-content-mem)
        address (content/materialize-vector! store unreadable-vector)
        format linker/semantic-format]
    (is (= [{:name 'q, :at nil, :in-body? true}]
           ((:obligations-fn format) unreadable-vector))
        "every occurrence is retained, at no position")
    (is (= [{:name 'x, :at nil, :conditional? true}]
           ((:definitions-fn format) unreadable-vector))
        "no definition discharges anything")
    (is (= [{:at nil}] ((:applications-fn format) unreadable-vector))
        "no application position is usable")
    (is (= {:status :refused, :reason :unresolved-free, :name 'q}
           (select-keys (linker/fetch store {address address} format
                                      address {} (requested format))
                        [:status :reason :name])))
    (jing/close! store)))


(deftest obligations-are-checked-by-name-and-record
  (let [occ {:name '+, :at 0, :in-body? false}]
    (is (nil? (linker/free-name-defect {:primitives {'+ 0}} [occ]))
        "a legacy symbol-keyed registry discharges the name")
    (is (nil? (linker/free-name-defect
                {:primitives {{:name 'f, :at 0, :in-body? true} 0}}
                [{:name 'f, :at 1, :in-body? false}]))
        "a registry keyed by the scanner's own records discharges by
         name: a composition that derives its receiver from the same
         scan binds every name it scanned")
    (is (= {:status :refused, :reason :unresolved-free, :name 'nope}
           (linker/free-name-defect receiver
                                    [{:name 'nope, :at 0,
                                      :in-body? false}])))
    (is (= {:status :refused, :reason :shadowed-free, :name '+}
           (linker/free-name-defect (assoc receiver :free-env {'+ 0})
                                    [occ])))))


;; =============================================================================
;; Identity under every registered algorithm (S11.6 of the master spec)
;; =============================================================================

(deftest sha256-minted-payloads-verify-under-their-own-algorithm
  (let [store (mem/create-content-mem)
        v (semantic-vector worked-example)
        address (jing/materialize! store v {:algorithm :sha256})]
    (is (not= (jing/segment-key v) address) "the default is :blake3")
    (is (= :sha256 (jing/segment-algorithm address)))
    (is (linker/ok? (linker/fetch store {address address}
                                  linker/semantic-format address
                                  receiver (requested linker/semantic-format))))
    (is (= v (:value (linker/fetch store {address address}
                                   linker/semantic-format address
                                   receiver
                                   (requested linker/semantic-format)))))
    (jing/close! store))
  (let [store (mem/create-content-mem)
        child-body [:literal 7]
        child-id (jing/materialize! store child-body {:algorithm :sha256})
        root-body (app-body child-id)
        root (jing/materialize! store root-body {:algorithm :sha256})]
    (is (= :sha256 (jing/segment-algorithm root)))
    (is (linker/ok? (linker/fetch store {root root} linker/ast-format
                                  root receiver (requested linker/ast-format)))
        "the tree verifies under the algorithm its rows carry, children
         included")
    (jing/close! store)))


;; =============================================================================
;; Step 5: free-name closure (S11.10, S11.11)
;; =============================================================================

(deftest unresolved-free-name-is-refused
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            {:keys [identity index]} (publish store format
                                              (mint unknown-free))]
        (is (= {:status :refused, :reason :unresolved-free, :name 'nope}
               (linker/fetch store index format identity receiver
                             (requested format))))
        (jing/close! store))
      (let [store (mem/create-content-mem)
            {:keys [identity index]} (publish store format
                                              (mint worked-example))]
        (is (= {:status :refused, :reason :unresolved-free, :name '+}
               (linker/fetch store index format identity {} (requested format)))
            "the empty receiver accepts only closed images")
        (jing/close! store)))))


(deftest shadowed-free-name-is-refused
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            {:keys [identity index]} (publish store format
                                              (mint worked-example))]
        (doseq [shadowing [(assoc receiver :free-env {'+ 0})
                           (assoc receiver :store {'+ 0})]]
          (is (= {:status :refused, :reason :shadowed-free, :name '+}
                 (linker/fetch store index format identity shadowing
                               (requested format)))))
        (jing/close! store)))))


(deftest module-names-resolve-through-the-registry
  (let [receiver' {:modules {:modules {'io {'print :host-print}}}}]
    (is (nil? (linker/free-name-defect receiver' '[io/print])))
    (is (= :unresolved-free
           (:reason (linker/free-name-defect receiver' '[io/nope]))))))


;; =============================================================================
;; Same-root pairing (S11.12, S11.13)
;; =============================================================================

(defn- mint-root
  "Publish both images of `ast` and return what a composition holds."
  [store ast]
  (let [h (publish store linker/stack-format (stack-image ast))
        r (publish store linker/register-format (register-image ast))]
    {:H (:identity h), :h-index (:index h), :R (:identity r)}))


(deftest trusted-fallback-names-its-trust
  (let [store (mem/create-content-mem)
        {:keys [H R h-index]} (mint-root store worked-example)
        pairing (linker/pairing-datoms 'root H R)
        res (linker/trusted-fallback store h-index 'root pairing receiver)]
    (is (linker/ok? res))
    (is (= :composition (:trust res)))
    (is (= {:root 'root, :from R} (:fallback res)))
    (is (= H (:identity res)))
    (is (= :yin.debruijn.code (:format res)))
    (is (= {:status :refused, :reason :absent, :root 'elsewhere}
           (linker/trusted-fallback store h-index 'elsewhere pairing
                                    receiver)))
    (jing/close! store)))


(deftest verifying-fallback-re-lowers-the-named-root
  (let [store (mem/create-content-mem)
        {:keys [H R h-index]} (mint-root store worked-example)
        other (mint-root store other-program)
        source (ast-datoms worked-example)]
    (testing "an authentic pairing is accepted"
      (is (= {:status :ok, :root 'root, :H H, :R R}
             (linker/verify-same-root-pairing 'root H R source)))
      (let [res (linker/verifying-fallback
                  store h-index 'root (linker/pairing-datoms 'root H R)
                  source receiver)]
        (is (linker/ok? res))
        (is (= :verified (:trust res)))
        (is (= (stack-image worked-example) (:value res)))))
    (testing "a swapped pairing is refused"
      (is (= {:status :refused, :reason :pairing-mismatch, :root 'root,
              :expected {:H (:H other), :R R}, :actual {:H H, :R R}}
             (linker/verify-same-root-pairing 'root (:H other) R source)))
      (is (= :pairing-mismatch
             (:reason (linker/verifying-fallback
                        store (merge h-index (:h-index other)) 'root
                        (linker/pairing-datoms 'root (:H other) R)
                        source receiver)))))
    (jing/close! store)))


;; =============================================================================
;; Execution parity under the B0 normalizer (S11.2)
;; =============================================================================

(defn- named-value
  [ast]
  (b0/normalize (vm/value (vm/eval (tu/create-vm) ast))))


(defn- lifted-value
  [named-vector]
  (b0/normalize
    (vm/value (vm/run (semantic/load-vector (semantic/create-vm)
                                            named-vector
                                            vm/semantic-contract)))))


(deftest fetched-images-execute-like-local-code
  (doseq [ast [worked-example closed-program other-program]]
    (let [store (mem/create-content-mem)
          local (named-value ast)
          h (publish store linker/stack-format (stack-image ast))
          r (publish store linker/register-format (register-image ast))
          t (publish store linker/ast-format
                     (vm/ast->semantic-bytecode ast))
          s (publish store linker/semantic-format (semantic-vector ast))
          fh (linker/fetch store (:index h) linker/stack-format
                           (:identity h) receiver
                           (requested linker/stack-format))
          fr (linker/fetch store (:index r) linker/register-format
                           (:identity r) receiver
                           (requested linker/register-format))
          ft (linker/fetch store (:index t) linker/ast-format
                           (:identity t) receiver (requested linker/ast-format))
          fs (linker/fetch store (:index s) linker/semantic-format
                           (:identity s) receiver
                           (requested linker/semantic-format))]
      (is (= local (lifted-value (dl/lift (:value fh))))
          "H: lifted to :yin.code/* and run on the semantic VM")
      (is (= local (lifted-value (rc/lift (:value fr))))
          "R: lifted to :yin.code/* and run on the semantic VM")
      (is (= local
             (b0/normalize
               (vm/value (run-fetched linker/stack-format fh))))
          "H: run directly on the de Bruijn stack kernel")
      (is (= local
             (b0/normalize
               (vm/value (run-fetched linker/semantic-format fs))))
          "SEM: the vector loads on the semantic VM as minted")
      (is (= local
             (b0/normalize (vm/value (run-fetched linker/ast-format ft))))
          "AST: the tree loads on the walker")
      (jing/close! store))))


;; =============================================================================
;; Transfer over dao.stream (S11.1, S11.3)
;; =============================================================================
;; The receiver knows only the identity and an index. Its content handle is
;; `dao.jing.remote/content-client`; every request and response crosses a
;; pair of `dao.stream` ring buffers as RPC envelopes, served by
;; `default-handlers` over the publisher's store. This path is portable, so
;; every host runs it; the JVM additionally runs the WebSocket transport.

(defn- ring-handle
  []
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key 64})))


(defn- stream-client
  "A content handle whose calls travel over dao.stream to a publisher
   serving `handlers`."
  [handlers]
  (let [requests (ring-handle)
        responses (ring-handle)
        oldest #(:dao.stream/cursor (stream/cursor % :dao.stream/oldest))
        server-cursor (atom (oldest requests))
        client-state (atom (rpc/client-state requests responses
                                             (oldest responses)))
        serve! (fn []
                 (let [r (stream/next requests @server-cursor)]
                   (reset! server-cursor (:dao.stream/cursor r))
                   (stream/append! responses
                                   (apply/dispatch-request
                                     handlers (:dao.stream/value r)))))
        call (fn [_ op args]
               (let [requested (rpc/request! @client-state op args)
                     id (:dao.stream.rpc/id requested)]
                 (serve!)
                 (let [done (remote/call-step (:dao.stream.rpc/state requested)
                                              id 8)]
                   (reset! client-state (:state done))
                   (remote/completion-value (:completion done)))))]
    (remote/content-client ::stream call (fn [_] nil))))


(deftest images-transfer-over-dao-stream
  (doseq [[label format mint] formats]
    (testing label
      (let [publisher (mem/create-content-mem)
            image (mint worked-example)
            {:keys [identity index]} (publish publisher format image)
            client (stream-client (remote/default-handlers publisher))
            res (linker/fetch client index format identity receiver
                              (requested format))]
        (is (linker/ok? res))
        (is (= image (:value res)))
        (is (= (named-value worked-example)
               (cond
                 (= :yin.ast/code (:format format))
                 (b0/normalize (vm/value (run-fetched format res)))

                 (= :yin.semantic/code (:format format))
                 (b0/normalize (vm/value (run-fetched format res)))

                 (= :yin.debruijn.code (:format format))
                 (lifted-value (dl/lift (:value res)))

                 :else (lifted-value (rc/lift (:value res))))))
        (is (= :absent
               (:reason (linker/fetch client {identity
                                              (jing/segment-key [:none])}
                                      format identity receiver
                                      (requested format))))
            "an absent address over the stream is :absent")
        (jing/close! client)
        (jing/close! publisher)))))


(deftest images-transfer-over-the-websocket-transport
  #?(:cljd (is true "the WebSocket constructors are JVM-only")
     :clj
     (let [port (+ 20000 (rand-int 30000))
           publisher (mem/create-content-mem)
           server (remote/serve-content! (remote/default-handlers publisher)
                                         port)]
       (try
         (let [client (remote/connect-content! (str "ws://127.0.0.1:" port))]
           (try
             (doseq [[label format mint] formats]
               (testing label
                 (let [image (mint worked-example)
                       {:keys [identity index]} (publish publisher format
                                                         image)
                       res (linker/fetch client index format identity
                                         receiver (requested format))]
                   (is (linker/ok? res))
                   (is (= image (:value res))))))
             (finally (jing/close! client))))
         (finally ((:stop! server)) (jing/close! publisher))))
     :cljs (is true "the WebSocket constructors are JVM-only")))
