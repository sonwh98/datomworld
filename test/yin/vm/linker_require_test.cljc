(ns yin.vm.linker-require-test
  "yin.vm.linker M4 slice S3 (docs/design/yin.vm.linker.md sections 7.2 to
   7.4): `(require 'foo)` lowers to a link request over the link pair, the
   response restores on its own id, and the module installs as a child
   task whose exports are lifted, relocated, and lowered into the
   receiving task, with the module's store crossing beside them.

   The linker side is a stub responder: it reads the link request stream
   with its own cursor and appends hand-built responses in the completion
   shape of section 6.3, `{:yin.link/id id :status :ok :image {:value v}
   :manifest m :obligations [...]}`. The manifest and derivation records
   are slice S4's; these tests carry only the manifest keys the install
   reads (`:yin.module/name`, `:yin.module/exports`,
   `:yin.module/requires`, `:yin.module/primitives`)."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.await :as await]
            [dao.jing :as jing]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as walker]
            [yin.vm.debruijn-code :as dcode]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.engine :as engine]
            [yin.vm.linearize :as linearize]
            [yin.vm.linker :as linker]
            [yin.repl :as repl]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; AST helpers
;; =============================================================================

(defn- lit
  [x]
  {:type :literal, :value x})


(defn- v
  [n]
  {:type :variable, :name n})


(defn- lam
  [params body]
  {:type :lambda, :params params, :body body})


(defn- app
  [f & args]
  {:type :application, :operator f, :operands (vec args)})


(defn- def!
  [k val]
  (app (v 'yin/def) (lit k) val))


(defn- then
  "`first` for its effect, then `second`: `((fn [_] second) first)`."
  [first-ast second-ast]
  (app (lam '[_] second-ast) first-ast))


(defn- progn
  [& asts]
  (reduce (fn [acc ast] (then acc ast)) asts))


(defn- req
  [module-name]
  (app (v 'require) (lit module-name)))


;; =============================================================================
;; Backends: one producer and one constructor each
;; =============================================================================

(def ^:private semantic-loader
  (linearize/ast-loader semantic/vm-load-program))


(defn- stack-image
  [ast]
  (:image (dl/adapt (vm/ast->datoms ast))))


(defn- register-image
  [ast]
  (:image (rc/adapt (second (vm/ast->datoms-with-root ast)))))


(defn- semantic-vector
  [ast]
  (:vector (linearize/lower-rows (vm/ast->semantic-bytecode ast))))


(defn- single-part-obligations
  "Steps 3 to 5a of the real linker over a one-part image."
  [format identity image]
  (:obligations (linker/verify format identity :root {:root image})))


(def backends
  "Each backend: `:image` lowers an AST to what its loader loads; `:vm`
   builds a root task over a program AST with the composition `opts`;
   `:continue` admits the next top-level input to a halted task, as
   `yin.repl` does; `:obligations` runs the real linker's steps 3 to 5a
   over an image."
  {:stack
   {:image stack-image,
    :vm (fn [ast opts]
          (dvm/create-vm (stack-image ast)
                         (assoc opts :contract vm/stack-contract))),
    :continue (fn [task ast]
                (let [img (stack-image ast)
                      attached (dvm/attach-image task img vm/stack-contract)]
                  (assoc attached
                         :pc (dvm/absolute-pc attached
                                              [(dcode/image-hash img) 0])
                         :frames [] :stack [] :continuation []
                         :halted? false :blocked? false :value nil))),
    :obligations (fn [img]
                   (single-part-obligations linker/stack-format
                                            (dcode/image-hash img)
                                            img))},
   :register
   {:image register-image,
    :vm (fn [ast opts]
          (rvm/create-vm (register-image ast)
                         (assoc opts :contract vm/register-contract))),
    :continue (fn [task ast]
                (let [img (register-image ast)
                      attached (rvm/attach-image task img
                                                 vm/register-contract)
                      pc (rvm/absolute-pc attached
                                          [(rcode/register-hash img) 0])
                      body (some #(when (= pc (:start %)) %)
                                 (:bodies (:segment attached)))]
                  (assoc attached
                         :pc pc :frames [] :continuation []
                         :registers (vec (repeat (:registers body) nil))
                         :halted? false :blocked? false :value nil))),
    :obligations (fn [img]
                   (single-part-obligations linker/register-format
                                            (rcode/register-hash img)
                                            img))},
   :semantic
   {:image semantic-vector,
    :vm (fn [ast opts]
          (semantic-loader (semantic/create-vm opts)
                           (vm/ast->datoms ast)
                           vm/ast-contract)),
    :continue (fn [task ast]
                (semantic-loader task (vm/ast->datoms ast) vm/ast-contract)),
    :obligations (fn [img]
                   (single-part-obligations linker/semantic-format
                                            (jing/segment-key img)
                                            img))},
   :walker
   {:image (fn [ast] (vm/ast->semantic-bytecode ast)),
    :vm (fn [ast opts]
          (walker/vm-load-rows (walker/create-vm opts)
                               (vm/ast->semantic-bytecode ast)
                               vm/ast-contract)),
    :continue (fn [task ast]
                (walker/vm-load-rows task (vm/ast->semantic-bytecode ast)
                                     vm/ast-contract)),
    :obligations (fn [bc]
                   (:obligations
                     (linker/verify linker/ast-format
                                    (:root bc)
                                    (:root bc)
                                    (into {}
                                          (map (fn [[id row]]
                                                 [id (subvec row 1)]))
                                          (:rows bc)))))}})


;; =============================================================================
;; The link pair and the stub responder
;; =============================================================================

(defn- link-pair
  "The link pair, and the directory of every stream the composition made,
   by logical identity, which its attacher resolves."
  []
  {:request (tu/new-stream 64),
   :response (tu/new-stream 64),
   :streams (atom {})})


(defn- composition
  "One task's composition over `pair`: the ring buffer, recorded so a
   lowered stream reference (r9) can attach to it; a deterministic
   capability secret, and a deterministic source minting each install
   child's own (r10)."
  [pair]
  (let [streams (:streams pair)]
    {:primitives vm/primitives,
     :modules (module/default-registry),
     :make-stream (fn [capacity]
                    (let [r (tu/make-stream capacity)]
                      (swap! streams assoc (:dao.stream/identity r)
                             (:dao.stream/handle r))
                      r)),
     :capability-secret tu/secret,
     :secret-source (fn [origin] (str tu/secret "/" (name origin))),
     :attach-stream (ringbuffer/make-attacher (fn [ident]
                                                (get @streams ident))),
     :link-request (:request pair),
     :link-response (:response pair)}))


(defn- responder
  "A stub linker over `pair`: `table` maps a module name to the response
   body it answers every request for that name with."
  [pair table]
  {:pair pair,
   :table table,
   :cursor (:dao.stream/cursor (stream/cursor (:request pair)
                                              stream/anchor-oldest)),
   :requests []})


(defn- respond!
  "Read every request the stub has not yet seen and append its response,
   in request order. Returns the advanced responder."
  [r]
  (loop [r r]
    (let [res (stream/next (get-in r [:pair :request]) (:cursor r))]
      (if (= :dao.stream/ok (:dao.stream/outcome res))
        (let [request (:dao.stream/value res)
              body (get (:table r) (:yin.link/name request)
                        {:status :refused,
                         :reason :absent,
                         :name (:yin.link/name request)})]
          (stream/append! (get-in r [:pair :response])
                          (assoc body :yin.link/id (:yin.link/id request)))
          (recur (-> r
                     (assoc :cursor (:dao.stream/cursor res))
                     (update :requests conj request))))
        r))))


(defn- module-response
  "The `:ok` response body for a module AST lowered by `backend`."
  ([backend module-name ast exports]
   (module-response backend module-name ast exports {}))
  ([backend module-name ast exports manifest]
   {:status :ok,
    :image {:value ((:image (get backends backend)) ast)},
    :manifest (merge {:yin.module/name module-name,
                      :yin.module/exports (set exports)}
                     manifest),
    :obligations []}))


(defn- drive
  "Run `vm` and the responder alternately until the VM stops blocking, at
   most `n` rounds. Returns `[vm responder]`."
  ([vm r] (drive vm r 40))
  ([vm r n]
   (loop [vm (vm/run vm)
          r r
          i 0]
     (if (and (vm/blocked? vm) (< i n))
       (let [r (respond! r)]
         (recur (vm/run vm) r (inc i)))
       [vm r]))))


(defn- refusal-of
  "The ex-data of what `thunk` throws, or nil when it returns."
  [thunk]
  (try (thunk)
       nil
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         (or (ex-data e) {}))))


;; =============================================================================
;; A require of an unlinked module links, installs, and resumes
;; =============================================================================

(def ^:private answer-module
  "`(yin/def f (fn [] 42))`."
  (def! 'f (lam [] (lit 42))))


(deftest a-require-links-installs-and-resumes-with-the-module-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [pair (link-pair)
            program (then (req 'foo) (app (v 'foo/f)))
            root ((:vm (get backends backend)) program (composition pair))
            r (responder pair {'foo (module-response backend 'foo
                                                     answer-module ['f])})
            [done r] (drive root r)
            entry (module/module-entry (:modules (:modules done)) 'foo)]
        (testing "the program resumes with the export applied"
          (is (not (vm/blocked? done)))
          (is (= 42 (vm/value done))))
        (testing "one request, by name, under an [origin counter] id"
          (is (= 1 (count (:requests r))))
          (is (= 'foo (:yin.link/name (first (:requests r)))))
          (is (= :t0 (first (:yin.link/id (first (:requests r)))))))
        (testing "the registry entry holds the portable slice and stores"
          (is (= :yin.k/closure (get-in entry [:slice 'f :yin.k/tag])))
          (is (contains? (:stores entry) (:address entry))))
        (testing "no install is left behind, nothing waits"
          (is (empty? (:installs done)))
          (is (empty? (:wait-set done))))))))


;; =============================================================================
;; Correlation: ids, outstanding requires, cursor before append
;; =============================================================================

(defn- respond-reversed!
  "As `respond!`, but every pending request is answered in reverse
   request order."
  [r]
  (let [read-all (fn [cursor acc]
                   (let [res (stream/next (get-in r [:pair :request]) cursor)]
                     (if (= :dao.stream/ok (:dao.stream/outcome res))
                       (recur (:dao.stream/cursor res)
                              (conj acc (:dao.stream/value res)))
                       [cursor acc])))
        [cursor requests] (read-all (:cursor r) [])]
    (doseq [request (reverse requests)]
      (stream/append! (get-in r [:pair :response])
                      (assoc (get (:table r) (:yin.link/name request))
                             :yin.link/id (:yin.link/id request))))
    (-> r
        (assoc :cursor cursor)
        (update :requests into requests))))


(defn- returning
  "`(yin/def f (fn [] x))`: a module exporting a constant function."
  [x]
  (def! 'f (lam [] (lit x))))


(deftest two-outstanding-requires-restore-on-their-own-ids-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [pair (link-pair)
            mk (:vm (get backends backend))
            a (vm/run (mk (then (req 'alpha) (app (v 'alpha/f)))
                          (assoc (composition pair) :origin :ta)))
            b (vm/run (mk (then (req 'beta) (app (v 'beta/f)))
                          (assoc (composition pair) :origin :tb)))
            r (respond-reversed!
                (responder pair
                           {'alpha (module-response backend 'alpha
                                                    (returning 1) ['f]),
                            'beta (module-response backend 'beta
                                                   (returning 2) ['f])}))
            ids (mapv :yin.link/id (:requests r))
            [a _] (drive a r)
            [b _] (drive b r)]
        (testing "two requests on one pair, ids scoped by origin"
          (is (= [:ta :tb] (mapv first ids)))
          (is (apply distinct? ids)))
        (testing "each task restores on its own id, answered out of order"
          (is (= 1 (vm/value a)))
          (is (= 2 (vm/value b))))
        (testing "the response for the other task's id was skipped"
          (is (= [{:kind :unknown, :id (second ids), :entry (first ids)}]
                 (first (engine/take-link-diagnostics a))))
          (is (empty? (first (engine/take-link-diagnostics b)))))))))


(defn- synchronous-writer
  "A link request writer that appends to `handle` and, before returning,
   lets the stub answer: a response that lands before the requester has
   polled anything."
  [handle answer!]
  (reify stream/IDaoStreamWriter
    (append!
      [_ value]
      (let [res (stream/append! handle value)]
        (answer!)
        res))))


(deftest a-response-before-the-first-poll-is-not-skipped-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [pair (link-pair)
            r (atom (responder pair
                               {'foo (module-response backend 'foo
                                                      (returning 5) ['f])}))
            ;; history on the response stream before the request exists
            _ (stream/append! (:response pair)
                              {:yin.link/id [:elsewhere 0], :status :ok})
            writer (synchronous-writer (:request pair)
                                       #(swap! r respond!))
            root ((:vm (get backends backend))
                  (then (req 'foo) (app (v 'foo/f)))
                  (assoc (composition pair) :link-request writer))
            [done _] (drive root @r)]
        (is (= 1 (count (:requests @r))) "answered inside the append")
        (is (= 5 (vm/value done)))
        (testing "history before the mint is never read"
          (is (empty? (first (engine/take-link-diagnostics done)))))))))


;; =============================================================================
;; Transitive requires, the third task, and cycles (section 7.4)
;; =============================================================================

(def ^:private bar-module
  (def! 'g (lam [] (lit 7))))


(def ^:private foo-module
  "Requires `bar` in its own body, and exports a closure calling it."
  (progn (req 'bar)
         (def! 'f (lam [] (app (v '+) (lit 1) (app (v 'bar/g)))))))


(def ^:private parking-ast
  "Parks reading an empty stream it made; its value is the value read."
  (app (lam '[s]
            {:type :stream/next,
             :source {:type :stream/cursor, :source (v 's)}})
       {:type :stream/make, :buffer 4}))


(deftest a-transitive-require-links-while-a-third-task-runs-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [pair (link-pair)
            mk (:vm (get backends backend))
            root (vm/run (mk (then (req 'foo) (app (v 'foo/f)))
                             (composition pair)))
            third (vm/run (mk parking-ast (composition (link-pair))))
            r (respond! (responder pair
                                   {'foo (module-response backend 'foo
                                                          foo-module ['f]),
                                    'bar (module-response backend 'bar
                                                          bar-module ['g])}))
            ;; the round that starts foo's child, which parks on bar
            root (vm/run root)
            install (get-in root [:installs 'foo])
            third (let [entry (first (:wait-set third))
                        handle (get (:resources third) (:stream-id entry))]
                    (stream/append! handle :fed)
                    (vm/run third))]
        (testing "the parent waits on the install; the child on its link"
          (is (vm/blocked? root))
          (is (= [:install] (mapv :reason (:wait-set root))))
          (is (= :parked (:phase install)))
          (is (= [:link-response] (mapv :reason (:wait-set (:vm install))))))
        (testing "the third task ran to completion meanwhile"
          (is (not (vm/blocked? third)))
          (is (= :fed (vm/value third))))
        (let [[done r] (drive root r)
              ids (mapv :yin.link/id (:requests r))]
          (testing "the transitive link completes and the program resumes"
            (is (= 8 (vm/value done)))
            (is (= ['foo 'bar] (mapv :yin.link/name (:requests r)))))
          (testing "the child's request is under the child's own origin"
            (is (= [:t0 :t0.0] (mapv first ids))))
          (testing "the receiving task received the dependency too"
            (is (some? (module/module-entry (:modules (:modules done))
                                            'bar)))))))))


(deftest install-children-and-the-root-mint-distinct-ids-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [pair (link-pair)
            needs (fn [dep]
                    (progn (req dep)
                           (def! 'f (lam [] (app (v (symbol (name dep)
                                                            "g")))))))
            giver (fn [x] (def! 'g (lam [] (lit x))))
            root ((:vm (get backends backend))
                  (progn (req 'a) (req 'b)
                         (app (v '+) (app (v 'a/f)) (app (v 'b/f))))
                  (composition pair))
            r (responder pair
                         {'a (module-response backend 'a (needs 'c) ['f]),
                          'b (module-response backend 'b (needs 'd) ['f]),
                          'c (module-response backend 'c (giver 3) ['g]),
                          'd (module-response backend 'd (giver 4) ['g])})
            [done r] (drive root r)
            by-name (into {} (map (juxt :yin.link/name :yin.link/id))
                          (:requests r))]
        (is (= 7 (vm/value done)))
        (testing "four requests on one pair, four distinct ids"
          (is (= 4 (count by-name)))
          (is (apply distinct? (vals by-name))))
        (testing "the two children count alike; their origins differ"
          (is (= (second (by-name 'c)) (second (by-name 'd))))
          (is (not= (first (by-name 'c)) (first (by-name 'd))))
          (is (every? #(= :t0 (first (by-name %))) ['a 'b])))))))


(deftest a-require-cycle-is-refused-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [pair (link-pair)
            root ((:vm (get backends backend))
                  (then (req 'foo) (app (v 'foo/f)))
                  (composition pair))
            r (responder
                pair
                {'foo (module-response backend 'foo
                                       (progn (req 'bar) (returning 1))
                                       ['f]),
                 'bar (module-response backend 'bar
                                       (progn (req 'foo) (returning 2))
                                       ['f])})
            refusal (refusal-of #(drive root r))]
        (is (= :require-cycle (:reason refusal)))
        (is (= '[foo bar foo] (:chain refusal)))
        (is (= 'foo (:link-module refusal)))))))


;; =============================================================================
;; Step 5b at the receiving task
;; =============================================================================

(deftest step-5b-runs-against-the-live-state-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [run-with (fn [program body]
                       (let [pair (link-pair)
                             root ((:vm (get backends backend))
                                   program (composition pair))]
                         (refusal-of
                           #(drive root (responder pair {'m body})))))
            body (fn [obligations manifest]
                   (assoc (module-response backend 'm (returning 1) ['f]
                                           manifest)
                          :obligations obligations))
            use-m (then (req 'm) (app (v 'm/f)))]
        (testing "a receiver binding an obligation is :shadowed-free"
          (let [refusal (run-with (then (def! 'helper (lit 1)) use-m)
                                  (body [{:name 'helper}] {}))]
            (is (= :shadowed-free (:reason refusal)))
            (is (= 'helper (:name refusal)))))
        (testing "a receiver lacking one is :unresolved-free"
          (is (= :unresolved-free
                 (:reason (run-with use-m (body [{:name 'helper}] {}))))))
        (testing "a same-named primitive of another profile is
                  :unresolved-free"
          (let [refusal (run-with use-m
                                  (body [{:name '+}]
                                        {:yin.module/primitives
                                         {'+ :yin.k.pp/sha256-other}}))]
            (is (= :unresolved-free (:reason refusal)))
            (is (= :yin.k.pp/sha256-other (:expected refusal)))))
        (testing "an equal profile discharges"
          (is (nil? (run-with use-m
                              (body [{:name '+}]
                                    {:yin.module/primitives
                                     {'+ (:yin.k/profile
                                           (vm/profile-of vm/primitives
                                                          '+))}})))))))))


(deftest a-body-read-applied-before-its-definition-keeps-its-obligation-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [{:keys [image obligations]} (get backends backend)
            module (progn (def! 'f (lam [] (v 'y)))
                          (app (v 'f))
                          (def! 'y (lit 1)))
            retained (obligations (image module))
            ;; the tree scanner may also retain the sequencing lambdas'
            ;; reads conservatively (section 4.1); the link carries y
            body (assoc (module-response backend 'm module ['f])
                        :obligations (filterv #(= 'y (:name %)) retained))
            y-fn (fn [] :primitive-y)
            with-y (assoc vm/primitives 'y
                          (assoc (vm/primitive-profile 'y :pure [0] #{} :none)
                                 :yin.k/function y-fn))
            run-with (fn [primitives]
                       (let [pair (link-pair)
                             root ((:vm (get backends backend))
                                   (then (req 'm) (app (v 'm/f)))
                                   (assoc (composition pair)
                                          :primitives primitives))]
                         (drive root (responder pair {'m body}))))]
        (testing "the real linker retains the body's read of y"
          (is (some #(= 'y (:name %)) retained)))
        (testing "without y at the receiver the link is refused"
          (is (= :unresolved-free
                 (:reason (refusal-of #(run-with vm/primitives))))))
        (testing "with y a receiver primitive it links; the export reads
                  the module's own y"
          (is (= 1 (vm/value (first (run-with with-y))))))))))


;; =============================================================================
;; Module stores (section 7.3, r6 and r7)
;; =============================================================================

(def ^:private ctr-module
  "A counter: a module binding, and exports that read it, redefine it,
   and write it with a direct store instruction."
  (progn (def! 'n (lit 0))
         (def! 'bump (lam [] (def! 'n (app (v '+) (v 'n) (lit 1)))))
         (def! 'get-n (lam [] (v 'n)))
         (def! 'setn (lam [] {:type :vm/store-put, :key 'n, :val 5}))))


(def ^:private ctr-exports ['n 'bump 'get-n 'setn])


(defn- run-program
  "Run `program` as a root task of `backend` against a stub answering
   `table`, a map of name -> [ast exports]. Returns the finished task."
  ([backend program table]
   (run-program backend program table (link-pair)))
  ([backend program table pair]
   (let [root ((:vm (get backends backend)) program (composition pair))
         r (responder pair
                      (into {}
                            (map (fn [[n [ast exports]]]
                                   [n (module-response backend n ast
                                                       exports)]))
                            table))]
     (first (drive root r)))))


(defn- ctr-address
  [task]
  (:address (module/module-entry (:modules (:modules task)) 'ctr)))


(deftest a-module-closure-reads-and-writes-its-own-store-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [table {'ctr [ctr-module ctr-exports]}
            reads (run-program backend
                               (progn (def! 'n (lit 99))
                                      (req 'ctr)
                                      (app (v 'ctr/get-n)))
                               table)
            bumped (run-program backend
                                (progn (def! 'n (lit 99))
                                       (req 'ctr)
                                       (app (v 'ctr/bump))
                                       (app (v 'ctr/bump))
                                       (app (v 'ctr/get-n)))
                                table)
            put (run-program backend
                             (progn (req 'ctr)
                                    (app (v 'ctr/setn))
                                    (app (v 'ctr/get-n)))
                             table)]
        (testing "the closure reads the module's n; the task's n neither
                  supplies nor shadows it"
          (is (= 0 (vm/value reads)))
          (is (= 99 (get (vm/store reads) 'n))))
        (testing "a write from one export is visible to the next"
          (is (= 2 (vm/value bumped)))
          (is (= 2 (get-in bumped [:module-stores (ctr-address bumped) 'n])))
          (is (= 99 (get (vm/store bumped) 'n))))
        (testing "a :store-put inside an export writes the module store"
          (is (= 5 (vm/value put)))
          (is (not (contains? (vm/store put) 'n))))
        (testing "another task holds its own instance"
          (is (= 0 (get-in reads [:module-stores (ctr-address reads) 'n]))))))))


(deftest a-module-closure-calling-another-writes-each-store-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [outer (progn (req 'ctr)
                         (def! 'm (lit 0))
                         (def! 'both (lam [] (then (def! 'm (lit 10))
                                                   (app (v 'ctr/bump)))))
                         (def! 'get-m (lam [] (v 'm))))
            done (run-program backend
                              (progn (req 'outer)
                                     (app (v 'outer/both))
                                     (app (v '+)
                                          (app (v '*) (lit 100)
                                               (app (v 'outer/get-m)))
                                          (app (v 'ctr/get-n))))
                              {'outer [outer ['both 'get-m 'm]],
                               'ctr [ctr-module ctr-exports]})]
        (is (= 1001 (vm/value done)))
        (is (not (contains? (vm/store done) 'm)))
        (is (not (contains? (vm/store done) 'n)))))))


(deftest a-dependency-store-crosses-with-the-child-mutations-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [reex (progn (req 'ctr)
                        (app (v 'ctr/bump))
                        (def! 'g (v 'ctr/get-n)))
            table {'reex [reex ['g]], 'ctr [ctr-module ctr-exports]}
            fresh (run-program backend
                               (then (req 'reex) (app (v 'reex/g)))
                               table)
            holding (run-program backend
                                 (progn (req 'ctr)
                                        (app (v 'ctr/bump))
                                        (app (v 'ctr/bump))
                                        (req 'reex)
                                        (app (v 'reex/g)))
                                 table)]
        (testing "the slice carries the dependency's store as the child
                  left it"
          (is (= 1 (vm/value fresh))))
        (testing "a task already holding the dependency keeps its own"
          (is (= 2 (vm/value holding))))))))


(deftest a-missing-module-store-refuses-the-lift-test
  (let [child (vm/run (dvm/create-vm (stack-image (returning 1))
                                     {:primitives vm/primitives,
                                      :contract vm/stack-contract}))
        stray (assoc (get (vm/store child) 'f) :store-of :segment/absent)
        child (assoc-in child [:store 'g] stray)
        refusal (refusal-of #(engine/lift-slice child :segment/own ['g]))]
    (is (= :yin.k/non-portable (:yin.k/status refusal)))
    (is (= :missing-module-store (:yin.k/kind refusal)))))


(defn- store-gets
  "A vector of `[:store-get k]` reads, one per key of `ks`."
  [ks]
  (reduce (fn [acc k] (app (v 'conj) acc {:type :vm/store-get, :key k}))
          (lit [])
          ks))


(def ^:private predictable-keys
  "Every id the engine mints or reserves that a program could guess."
  (concat (map #(keyword (str "stream-" %)) (range 8))
          (map #(keyword (str "cursor-" %)) (range 8))
          [:yin/call-in :yin/call-out :yin/call-out-cursor
           :yin.link/request :yin.link/response]))


(deftest a-forged-resource-key-reads-nothing-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [spy (def! 'peek (lam [] (store-gets predictable-keys)))
            done (run-program backend
                              (progn (def! 's {:type :stream/make,
                                               :buffer 4})
                                     (req 'spy)
                                     (app (v 'conj)
                                          (app (v 'spy/peek))
                                          (store-gets predictable-keys)))
                              {'spy [spy ['peek]]})
            reads (vm/value done)]
        (testing "the task holds a live stream, in its private resources"
          (is (some #(some? (get (:resources done) %)) predictable-keys))
          (is (every? symbol? (keys (vm/store done)))
              "the store holds program bindings only"))
        (testing "a module's forged reads reach nothing"
          (is (= (count predictable-keys) (count (pop reads))))
          (is (every? nil? (pop reads))))
        (testing "nor do the task's own top-level forged reads (r8)"
          (is (= (count predictable-keys) (count (peek reads))))
          (is (every? nil? (peek reads))))))))


(defn- refusal-data
  "The ex-data `thunk` throws, or nil."
  [thunk]
  (refusal-of thunk))


(deftest a-forged-reference-fails-at-effect-dispatch-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [{:keys [continue]} (get backends backend)
            made (run-program backend
                              (def! 's {:type :stream/make, :buffer 4})
                              {})
            sref (get (vm/store made) 's)
            other (engine/issue-ref (assoc made
                                           :capability-secret
                                           "another task's secret")
                                    :stream-ref (:id sref))
            put (fn [target]
                  (vm/run (continue made
                                    {:type :stream/put,
                                     :target (lit target),
                                     :val (lit 1)})))
            next! (fn [cursor]
                    (vm/run (continue made
                                      {:type :stream/next,
                                       :source (lit cursor)})))
            refused (fn [thunk]
                      (let [d (refusal-data thunk)]
                        [(:reason d) (:effect d) (:id d)]))]
        (testing "the task's own sealed reference resolves"
          (is (= :stream-ref (:type sref)))
          (is (engine/authentic-ref? made :stream-ref sref))
          (is (= 1 (vm/value (put sref)))))
        (testing "a literal naming the live stream with no seal is forged"
          (is (= [:forged-resource-reference :stream/put (:id sref)]
                 (refused #(put (dissoc sref :seal))))))
        (testing "a wrong seal is forged"
          (is (= [:forged-resource-reference :stream/put (:id sref)]
                 (refused #(put (assoc sref :seal "00"))))))
        (testing "another task's seal on the same id is forged"
          (is (not= (:seal other) (:seal sref)))
          (is (= [:forged-resource-reference :stream/put (:id sref)]
                 (refused #(put other)))))
        (testing "the FFI pair's ids cannot be named: stream and cell"
          (is (= [:forged-resource-reference :stream/put :yin/call-in]
                 (refused #(put {:type :stream-ref, :id :yin/call-in}))))
          (is (= [:forged-resource-reference :stream/next
                  :yin/call-out-cursor]
                 (refused #(next! {:type :cursor-ref,
                                   :id :yin/call-out-cursor})))))
        (testing "a stream reference is not a cursor reference"
          (is (= [:forged-resource-reference :stream/next (:id sref)]
                 (refused #(next! (assoc sref :type :cursor-ref))))))))))


(deftest a-fabricated-reference-export-fails-the-lift-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [;; the child holds a live stream at the literal's id: an
            ;; unsealed literal is still refused before it is encoded
            forge (progn (def! 's {:type :stream/make, :buffer 4})
                         (def! 'r (lit {:type :stream-ref, :id :stream-0})))
            refusal (refusal-of
                      #(run-program backend
                                    (then (req 'forge) (v 'forge/r))
                                    {'forge [forge ['r]]}))]
        (is (= :yin.k/non-portable (:reason refusal)))
        (is (= :forged-resource-reference (:yin.k/kind refusal)))))))


(def ^:private chan-module
  "A module that makes a stream and a cursor on it, and exports both."
  (progn (def! 's {:type :stream/make, :buffer 4})
         (def! 'c {:type :stream/cursor, :source (v 's)})))


(def ^:private use-chan
  "Put 5 through the exported stream, read it through the exported
   cursor."
  (progn (req 'chan)
         {:type :stream/put, :target (v 'chan/s), :val (lit 5)}
         {:type :stream/next, :source (v 'chan/c)}))


(deftest a-reference-carrying-export-lifts-and-lowers-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [done (run-program backend use-chan
                              {'chan [chan-module ['s 'c]]})
            entry (module/module-entry (:modules (:modules done)) 'chan)
            s (get-in entry [:bindings 's])
            c (get-in entry [:bindings 'c])]
        (testing "the program writes and reads through the exports"
          (is (= 5 (vm/value done))))
        (testing "the slice carries markers, never a seal or a handle"
          (is (= :yin.k/stream (get-in entry [:slice 's :yin.k/tag])))
          (is (= :yin.k/cursor-ref (get-in entry [:slice 'c :yin.k/tag])))
          (is (not (contains? (get-in entry [:slice 's]) :seal)))
          (is (= 1 (count (:cells entry)))))
        (testing "the receiver's resources gain the attachment and the
                  cell; the exports are references re-sealed under the
                  receiver's secret"
          (is (engine/authentic-ref? done :stream-ref s))
          (is (engine/authentic-ref? done :cursor-ref c))
          (is (= (:id s) (get-in done [:resources (:id c) :stream-id]))
              "the lowered cell names the lowered attachment")
          (is (every? symbol? (keys (vm/store done)))
              "no store key is created"))))))


(deftest a-reference-lowers-only-where-it-can-attach-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [run-with (fn [opts]
                       (let [pair (link-pair)
                             root ((:vm (get backends backend))
                                   use-chan
                                   (merge (composition pair) opts))]
                         (refusal-of
                           #(drive root
                                   (responder
                                     pair
                                     {'chan (module-response
                                              backend 'chan chan-module
                                              ['s 'c])})))))]
        (testing "a receiver with no attacher refuses the install"
          (is (= :yin.k/unsatisfied
                 (:yin.k/status (run-with {:attach-stream nil})))))
        (testing "a child minted no secret can issue no reference"
          (is (= :no-capability-secret
                 (:reason (run-with {:secret-source nil})))))))))


(deftest input-after-a-tail-applied-module-closure-is-the-tasks-own-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [{:keys [continue]} (get backends backend)
            done (run-program backend
                              (then (req 'ctr)
                                    (app (lam [] (app (v 'ctr/setn)))))
                              {'ctr [ctr-module ctr-exports]})
            next-input (vm/run (continue done (def! 'z (lit 3))))]
        (is (= 5 (vm/value done)))
        (testing "the halt left no module store context"
          (is (nil? (engine/store-context done))))
        (testing "the next input defines into the task's own store"
          (is (= 3 (get (vm/store next-input) 'z)))
          (is (not (contains? (get-in next-input
                                      [:module-stores (ctr-address done)])
                              'z))))))))


;; =============================================================================
;; The two link states, retries, and the skip and abandon cases
;; =============================================================================

(defn- full-writer
  "A link request writer that answers `full` to its first `n` appends,
   appending nothing, and appends to `handle` afterwards."
  [handle n]
  (let [left (atom n)
        seen (atom [])]
    {:seen seen,
     :writer (reify stream/IDaoStreamWriter
               (append!
                 [_ value]
                 (swap! seen conj value)
                 (if (pos? @left)
                   (do (swap! left dec) {:dao.stream/outcome :dao.stream/full})
                   (stream/append! handle value))))}))


(deftest a-full-request-stream-retries-the-envelope-verbatim-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [pair (link-pair)
            {:keys [seen writer]} (full-writer (:request pair) 2)
            root (vm/run ((:vm (get backends backend))
                          (then (req 'foo) (app (v 'foo/f)))
                          (assoc (composition pair) :link-request writer)))
            entry (first (:wait-set root))]
        (testing "on full the entry stays in :link-request, envelope kept"
          (is (= :link-request (:reason entry)))
          (is (= (:link-id entry) (:yin.link/id (:envelope entry)))))
        (let [[done _] (drive root
                              (responder pair
                                         {'foo (module-response
                                                 backend 'foo (returning 3)
                                                 ['f])}))]
          (testing "the poll retries it until it is appended"
            (is (= 3 (count @seen)))
            (is (apply = @seen))
            (is (= 3 (vm/value done)))))))))


(deftest duplicate-late-and-abandoned-links-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [pair (link-pair)
            mk (:vm (get backends backend))
            answer! (fn [module-name id]
                      (stream/append! (:response pair)
                                      (assoc (module-response
                                               backend module-name
                                               (returning 1) ['f])
                                             :yin.link/id id)))
            ;; duplicate: foo is answered, restores, and the program's
            ;; next require mints its cursor before foo is answered again
            task (vm/run (mk (progn (req 'foo) (req 'bar) (app (v 'foo/f)))
                             (composition pair)))
            foo-id (:link-id (first (:wait-set task)))
            _ (answer! 'foo foo-id)
            task (vm/run task)
            bar-id (:link-id (first (:wait-set task)))
            _ (answer! 'foo foo-id)
            _ (answer! 'bar bar-id)
            [done _] (drive task (responder pair {}))
            ;; late and abandoned: a task parked on two links, one of
            ;; which it gives up on before its response arrives
            quitter (vm/run (mk (then (req 'baz) (app (v 'baz/f)))
                                (assoc (composition pair) :origin :tq)))
            kept (first (:wait-set quitter))
            quit-id [:tq :abandoned]
            two (update quitter :wait-set conj
                        (assoc kept :link-id quit-id :name 'qux))
            abandoned (engine/abandon-link two quit-id :gave-up)
            _ (answer! 'qux quit-id)
            _ (answer! 'baz (:link-id kept))
            polled (engine/check-wait-set (assoc abandoned :ready-queue []))]
        (testing "a duplicate response is skipped by the next entry"
          (is (= 1 (vm/value done)))
          (is (= [{:kind :duplicate, :id foo-id, :entry bar-id}]
                 (first (engine/take-link-diagnostics done)))))
        (testing "an abandoned link is raised to the program as its error"
          (let [refusal (refusal-of #(vm/run abandoned))]
            (is (= :lost (:status refusal)))
            (is (= :gave-up (:reason refusal)))
            (is (= quit-id (:link-id refusal))))
          (is (= [(:link-id kept)] (mapv :link-id (:wait-set abandoned))))
          (is (= :abandoned (get-in abandoned [:link-retired quit-id]))))
        (testing "its response, arriving later, is skipped as late"
          (is (= [{:kind :late, :id quit-id, :entry (:link-id kept)}]
                 (first (engine/take-link-diagnostics polled))))
          (testing "while the live entry matches, installs in the same
                    round, and wakes with its module"
            (is (empty? (:wait-set polled)))
            (is (= ['baz] (mapv :value (:ready-queue polled))))))))))


;; =============================================================================
;; The install child's refusals
;; =============================================================================

(deftest an-install-refuses-a-missing-export-and-a-loader-defect-test
  (doseq [backend (keys backends)]
    (testing (name backend)
      (let [run-with (fn [body]
                       (let [pair (link-pair)
                             root ((:vm (get backends backend))
                                   (then (req 'foo) (app (v 'foo/f)))
                                   (composition pair))]
                         (refusal-of
                           #(drive root (responder pair {'foo body})))))
            missing (run-with (module-response backend 'foo (returning 1)
                                               ['f 'absent]))
            defect (run-with (assoc-in (module-response backend 'foo
                                                        (returning 1) ['f])
                                       [:image :value]
                                       ;; no backend's loader admits it
                                       {:bodies [],
                                        :instructions [[:bogus]],
                                        :root :absent,
                                        :rows {}}))]
        (testing "validated requires every export bound: :export-missing"
          (is (= :export-missing (:reason missing)))
          (is (= ['absent] (:names missing))))
        (testing "a loader defect refuses the install at loading"
          (is (= :refused (:status defect)))
          (is (= :loading (:phase defect))))))))


(deftest a-marker-of-another-binding-discipline-is-refused-test
  (let [stack-task (vm/run (dvm/create-vm (stack-image (returning 1))
                                          {:primitives vm/primitives,
                                           :contract vm/stack-contract}))
        closure (get (vm/store stack-task) 'f)
        marker (module/lift-closure stack-task closure identity)
        semantic-task (semantic/create-vm {})
        register-task (rvm/create-vm nil {})]
    (is (= :positional (:yin.k/binding marker)))
    (is (= :binding-mismatch
           (:reason (refusal-of #(module/lower-closure semantic-task marker
                                                       identity)))))
    (is (= :binding-mismatch
           (:reason (refusal-of #(module/lower-closure register-task marker
                                                       identity))))
        "a positional marker lowers only into its own format")))


;; =============================================================================
;; A publication defect refuses the install (section 7.3, `refused`)
;; =============================================================================
;; A test kernel that is the stack kernel in every respect but one: it
;; throws at a chosen stage of the `linked` transition's acts 3 and 4 --
;; attaching an origin image, or lowering a closure marker.

(defrecord FaultyTask
  [])


(defn- as-stack
  [task]
  (dvm/map->DebruijnVM (dissoc (into {} task) :fault)))


(defn- as-faulty
  [task fault]
  (map->FaultyTask (assoc (into {} task) :fault fault)))


(defn- injected!
  [stage]
  (throw (ex-info "Injected publication failure"
                  {:reason :injected, :stage stage})))


(extend-type FaultyTask
  module/IModuleKernel
  (link-format [t] (module/link-format (as-stack t)))
  (spawn-module [t image opts]
    (let [child (module/spawn-module (as-stack t) image opts)]
      ;; a child that lifts its closures from an image nobody verified
      (if (= :foreign (:stage (:fault t)))
        (as-faulty child {:stage :forge-origin})
        child)))
  (image-identity [t image] (module/image-identity (as-stack t) image))
  (image-holds? [t image segment]
    (module/image-holds? (as-stack t) image segment))
  (attach-module [t image]
    (let [{:keys [stage identity]} (:fault t)]
      (when (and (= :attach stage) (= identity (dcode/image-hash image)))
        (injected! :attach))
      (as-faulty (module/attach-module (as-stack t) image) (:fault t))))
  (lift-closure [t closure encode]
    (cond-> (module/lift-closure (as-stack t) closure encode)
      (= :forge-origin (:stage (:fault t)))
      (assoc :yin.k/segment "an image this scheduler never verified")))
  (lower-closure [t marker decode]
    (when (= :lower (:stage (:fault t))) (injected! :lower))
    (module/lower-closure (as-stack t) marker decode)))


(extend-type FaultyTask
  vm/IVM
  (step [t] (as-faulty (vm/step (as-stack t)) (:fault t)))
  (run [t] (as-faulty (vm/run (as-stack t)) (:fault t)))
  (eval [t ast] (as-faulty (vm/eval (as-stack t) ast) (:fault t)))
  (reset [t] (as-faulty (vm/reset (as-stack t)) (:fault t)))
  (halted? [t] (vm/halted? (as-stack t)))
  (blocked? [t] (vm/blocked? (as-stack t)))
  (value [t] (vm/value (as-stack t))))


(defn- faulted-install
  "Drive a stack root requiring `foo` through the scheduler round alone
   (`engine/check-wait-set`) with its kernel faulting as `fault`, until a
   waiter wakes. Returns the task."
  [table fault]
  (let [pair (link-pair)
        root (vm/run (dvm/create-vm
                       (stack-image (then (req 'foo) (app (v 'foo/f))))
                       (assoc (composition pair)
                              :contract vm/stack-contract)))]
    (loop [task (as-faulty root fault)
           r (responder pair table)
           i 0]
      (let [r (respond! r)
            task (engine/check-wait-set task)]
        (if (or (seq (:ready-queue task)) (< 20 i))
          task
          (recur task r (inc i)))))))


(deftest a-publication-defect-refuses-the-install-test
  (let [store-only (progn (def! 'n (lit 0)) (def! 'f (lam [] (lit 1))))
        cases
        {"attaching the origin image"
         [{'foo (module-response :stack 'foo (returning 1) ['f])}
          {:stage :attach, :identity (dcode/image-hash
                                       (stack-image (returning 1)))}
          :attach],
         "lowering the slice"
         [{'foo (module-response :stack 'foo (returning 1) ['f])}
          {:stage :lower}
          :lower],
         "lowering a store snapshot"
         [{'foo (module-response :stack 'foo store-only ['n])}
          {:stage :lower}
          :lower],
         "receiving a dependency"
         [{'foo (module-response :stack 'foo foo-module ['f]),
           'bar (module-response :stack 'bar bar-module ['g])}
          {:stage :attach, :identity (dcode/image-hash
                                       (stack-image bar-module))}
          :attach]}]
    (doseq [[label [table fault stage]] cases]
      (testing label
        (let [task (faulted-install table fault)
              woken (first (:ready-queue task))]
          (testing "the round returns: the install is refused, not thrown"
            (is (empty? (:installs task)))
            (is (not-any? #(= :install (:reason %)) (:wait-set task))))
          (testing "its waiter wakes with the refusal at phase :linked"
            (is (= :link-refused (:status woken)))
            (is (= :linked (:phase (:refusal woken))))
            (is (= :injected (:reason (:refusal woken))))
            (is (= stage (:stage (:refusal woken)))))
          (testing "nothing is published"
            (is (nil? (module/module-entry (:modules (:modules task))
                                           'foo)))
            (is (nil? (module/module-entry (:modules (:modules task))
                                           'bar)))
            (is (empty? (:module-stores task))))
          (testing "the program sees the refusal as the require's error"
            (is (= :injected
                   (:reason (refusal-of #(vm/run (as-stack task))))))))))))


(deftest an-origin-the-scheduler-never-verified-is-foreign-test
  (let [task (faulted-install
               {'foo (module-response :stack 'foo (returning 1) ['f])}
               {:stage :foreign})
        woken (first (:ready-queue task))]
    (testing "act 2 refuses the install: nothing is published"
      (is (empty? (:installs task)))
      (is (= :link-refused (:status woken)))
      (is (= :foreign-image (:reason (:refusal woken))))
      (is (= "an image this scheduler never verified"
             (:segment (:refusal woken))))
      (is (nil? (module/module-entry (:modules (:modules task)) 'foo))))
    (testing "the program sees the refusal as the require's error"
      (is (= :foreign-image
             (:reason (refusal-of #(vm/run (as-stack task)))))))))


;; =============================================================================
;; The shipped compositions mint each install child's secret (r10)
;; =============================================================================

(def ^:private stream-using-module
  "A module whose body makes a stream, writes 7 through it, and reads it
   back through a cursor: the child must issue two references."
  (def! 'n (app (lam '[s]
                     (then {:type :stream/put, :target (v 's), :val (lit 7)}
                           {:type :stream/next,
                            :source {:type :stream/cursor,
                                     :source (v 's)}}))
                {:type :stream/make, :buffer 4})))


(def ^:private use-n
  (then (req 'sm) (v 'sm/n)))


(defn- sm-responder
  [pair]
  (responder pair {'sm (module-response :walker 'sm stream-using-module
                                        ['n])}))


(deftest a-stream-using-module-installs-through-the-repl-composition-test
  (let [pair (link-pair)
        ;; the link pair is the composition's (M5 wires it into the shell);
        ;; everything else is the REPL's own constructor
        task (-> (repl/make-vm :ast-walker (tu/new-stream 64))
                 (update :resources assoc
                         :yin.link/request (:request pair)
                         :yin.link/response (:response pair))
                 (walker/vm-load-rows (vm/ast->semantic-bytecode use-n)
                                      vm/ast-contract))
        [done _] (drive task (sm-responder pair))]
    (testing "the REPL supplies a secret source for its install children"
      (is (fn? (:secret-source task))))
    (testing "the child issued its references and the module linked"
      (is (not (vm/blocked? done)))
      (is (= 7 (vm/value done))))))


(defn- drive-await
  "Resume a dao.await result and run the responder alternately until it
   stops blocking, at most 40 rounds."
  [result r]
  (loop [result result
         r r
         i 0]
    (if (and (:blocked? result) (< i 40))
      (let [r (respond! r)]
        (recur (await/resume result) r (inc i)))
      result)))


(deftest a-stream-using-module-installs-through-dao-await-test
  (let [pair (link-pair)
        opts {:make-stream tu/make-stream,
              :primitives vm/primitives,
              :link-request (:request pair),
              :link-response (:response pair)}
        done (drive-await (await/run {:ast use-n, :env {}} opts)
                          (sm-responder pair))]
    (testing "dao.await supplies a secret source for its install children"
      (is (fn? (:secret-source (:vm done)))))
    (testing "the child issued its references and the module linked"
      (is (not (:blocked? done)))
      (is (= 7 (:value done)))))
  (testing "a caller's own source is the one used"
    (let [minted (atom [])
          pair (link-pair)
          source (fn [origin]
                   (swap! minted conj origin)
                   (str tu/secret "/" (name origin)))
          done (drive-await (await/run {:ast use-n, :env {}}
                                       {:make-stream tu/make-stream,
                                        :primitives vm/primitives,
                                        :secret-source source,
                                        :link-request (:request pair),
                                        :link-response (:response pair)})
                            (sm-responder pair))]
      (is (= 7 (:value done)))
      (is (= [:t0.0] @minted)))))
