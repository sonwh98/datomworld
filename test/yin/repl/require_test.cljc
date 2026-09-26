(ns yin.repl.require-test
  "M5 (docs/design/yin.vm.linker.md sections 6.1, 7.2 and 9): `(require
   'foo)` typed at the prompt of `yin.repl` reaches the shell's linker
   interpreter, links the module's manifest over the content pair,
   installs through the child phases, and resumes with the export bound
   -- on every backend the shell supports.  The content source is an
   in-process `dao.jing` store served behind `dao.jing.remote`'s
   handlers; the link pair is the session's, so a link whose source
   cannot answer surfaces as the shell's own `:pending` state, and
   `(abandon)` gives it up.

   Modules are minted four ways (tree, semantic vector, H, R) with
   derivation records under the profiles this linker re-lowers, exactly
   as `yin.vm.linker-manifest-test` mints its corpus; the shell requests
   the format its own kernel names.  The closed corpus links on all four
   backends; the store-carrying one closes over a module binding, which
   the tree scanner conservatively retains (section 4.1), so it links on
   the two backends whose scanners discharge it -- the H/R pair the
   criterion names.  Input lines stay readable by every host's
   non-evaluating reader, so quoted names are spelled `(quote ...)`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [dao.stream :as stream]
            [dao.stream.apply :as apply]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.rpc :as rpc]
            [yin.repl :as repl]
            [yin.repl.link :as link]
            [yin.vm :as vm]
            [yin.vm.content :as content]
            [yin.vm.debruijn-code :as dcode]
            [yin.vm.engine :as engine]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn-vm-contract-test :as b0]
            [yin.vm.ledger :as ledger]
            [yin.vm.linker :as linker]
            [yin.vm.linearize :as linearize]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; AST helpers and the module corpora
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
  (reduce then asts))


(def closed-module
  "`(yin/def f (fn [] (+ 40 2)))`: one export, one retained free name.
   The shell corpus: it links on every backend the shell supports."
  (def! 'f (lam [] (app (v '+) (lit 40) (lit 2)))))


(def closed-exports
  #{'f})


(def closed-value
  "What the export answers: B0's own baseline for the corpus."
  (app (v '+) (lit 40) (lit 2)))


(def store-module
  "A module binding `n`, and an export reading it, so the install
   carries a store snapshot across the child-to-parent boundary.  The
   tree scanner conservatively retains the in-body read of `n`
   (section 4.1) and a manifest declares only primitives and requires,
   so this one links on the backends whose join discharges it -- the
   H and R pair."
  (progn (def! 'n (lit 40))
         (def! 'f (lam [] (app (v '+) (v 'n) (lit 2))))))


(def store-exports
  #{'n 'f})


(defn- stack-image
  [ast]
  (:image (dl/adapt (vm/ast->datoms ast))))


(defn- register-image
  [ast]
  (:image (rc/adapt (second (vm/ast->datoms-with-root ast)))))


(defn- semantic-vector
  [ast]
  (:vector (linearize/lower-rows (vm/ast->semantic-bytecode ast))))


(def plus-profile
  "The published profile address of `+`, the one free name both corpora
   retain."
  (get-in vm/primitives ['+ :yin.k/profile]))


(defn- publish-module
  "Publish `ast` as the module `name` in `store`: its tree, its three
   lowered images, their derivation records under the profiles this
   linker re-lowers, and the schema-1 manifest naming them.  Returns
   `{:address a :h h :r r}`: the manifest's content address and the two
   lowered identities, which are distinct for any corpus worth
   linking.  `overlay` replaces manifest fields, for a module that
   requires another."
  [store ast name exports & [overlay]]
  (let [tree (vm/ast->semantic-bytecode ast)
        tree-addr (content/materialize-tree! store tree)
        sem (content/materialize-vector! store (semantic-vector ast))
        h-img (stack-image ast)
        r-img (register-image ast)
        h (dcode/image-hash h-img)
        r (rcode/register-hash r-img)
        h-addr (jing/materialize! store h-img)
        r-addr (jing/materialize! store r-img)
        mint (fn [out profile]
               (jing/materialize!
                 store (assoc (ledger/derive-record tree-addr out)
                              :yin.ledger/profile profile)))
        manifest {:yin.module/name name
                  :yin.module/schema linker/manifest-schema
                  :yin.module/contracts
                  {:yin.ast/code vm/ast-contract
                   :yin.semantic/code vm/semantic-contract
                   :yin.debruijn.code vm/stack-contract
                   :yin.debruijn.register vm/register-contract}
                  :yin.module/tree tree-addr
                  :yin.module/derivations
                  {:yin.semantic/code (mint sem ledger/lowering-profile)
                   :yin.debruijn.code (mint h linker/stack-lowering-profile)
                   :yin.debruijn.register
                   (mint r linker/register-lowering-profile)}
                  :yin.module/index {h h-addr, r r-addr}
                  :yin.module/exports (set exports)
                  :yin.module/requires {}
                  :yin.module/primitives {'+ plus-profile}
                  :yin.module/footprint {:store-keys #{} :effects #{}}}]
    {:address (jing/materialize! store (merge manifest overlay))
     :h h, :r r}))


(defn- shell
  "A shell of `vm-type` whose content source is the in-process `store`
   and whose name environment resolves `env`."
  [vm-type store env]
  (repl/create-state {:vm-type vm-type
                      :content-store store
                      :name-env env}))


(defn- silent-client
  "A `dao.stream.rpc` client on a wire nothing answers: requests are
   delivered and nothing ever comes back, so every link attempt spends
   its budget and reports the link pending."
  []
  (let [medium #(-> {:dao.stream/type ring/transport-type
                     ring/capacity-key 64}
                    ring/create!
                    :dao.stream/handle)
        responses (medium)]
    (rpc/client-state (medium) responses
                      (:dao.stream/cursor
                        (stream/cursor responses stream/anchor-newest)))))


(defn- withholding
  "`comp` with a content request medium that never delivers a request for
   `address`: the far end holds the content but never hears the ask, so
   a link whose manifest is `address` spends its budget and stays
   pending while every other link over the same source completes."
  [comp address]
  (let [wire (:dao.stream/handle
               (ring/create! {:dao.stream/type ring/transport-type
                              ring/capacity-key 1024}))]
    (assoc-in comp [:content :requests]
              (reify
                stream/IDaoStreamDescriptor
                (descriptor [_] (stream/descriptor wire))


                stream/IDaoStreamReader

                (cursor [_ anchor] (stream/cursor wire anchor))

                (next [_ c] (stream/next wire c))


                stream/IDaoStreamWriter

                (append!
                  [_ value]
                  (if (some #{address} (apply/request-args value))
                    {:dao.stream/outcome :dao.stream/ok}
                    (stream/append! wire value)))


                stream/IDaoStreamClosable

                (close! [_] (stream/close! wire))))))


(defn- pair-requests
  "Every link request on `pair`, oldest first."
  [pair]
  (loop [c (:dao.stream/cursor (stream/cursor (:requests pair)
                                              stream/anchor-oldest))
         acc []]
    (let [r (stream/next (:requests pair) c)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- require-and-apply
  "One prompt session over `store`: require the module, then apply its
   export.  Returns `[answered result]` -- the require's own answer, read
   as the round's value rather than its rendered text (the shell renders
   a quoted symbol `'mod` on JVM and Node and `(quote mod)` on Dart), and
   `{:value v :state s}` for the export's round."
  [vm-type store address]
  (let [state (shell vm-type store {'mod address})
        [state' _] (repl/eval-input state "(require (quote mod))")
        [state'' _] (repl/eval-input state' "(mod/f)")]
    [(:last-value state')
     {:value (:last-value state''), :state state''}]))


(defn- answers-42?
  [result]
  (and (= 42 (:value result))
       (= (b0/normalize 42)
          (b0/normalize (:value result)))))


;; =============================================================================
;; The whole path from the prompt (completion criterion 10)
;; =============================================================================

(deftest a-require-at-the-prompt-links-installs-and-resumes-test
  (doseq [vm-type [:ast-walker :semantic :stack :register]]
    (testing (name vm-type)
      (let [store (mem/create-content-mem)
            {:keys [address]} (publish-module store closed-module 'mod
                                              closed-exports)
            [answered result] (require-and-apply vm-type store address)]
        (testing "the require answers with the module name"
          (is (= 'mod answered)))
        (testing "the export, lowered from the linked image, applies"
          (is (= (b0/normalize (vm/value (vm/eval (tu/create-vm)
                                                  closed-value)))
                 (b0/normalize (:value result))))
          (is (= 42 (:value result))))
        (testing "nothing stayed pending or blocked"
          (is (nil? (:pending-run (:state result))))
          (is (not (vm/blocked? (:vm (:state result))))))))))


(deftest the-h-and-r-backends-link-one-manifest-b0-equal-test
  "The criterion's own clause: the same manifest linked by `(vm :stack)`
   and `(vm :register)` -- by H and by R, through each one's derivation
   record -- produces B0-equal results."
  (let [store (mem/create-content-mem)
        {:keys [address h r]} (publish-module store store-module 'mod
                                              store-exports)
        [stack-answered stack] (require-and-apply :stack store address)
        [register-answered register] (require-and-apply :register store
                                                        address)]
    (testing "one manifest, two distinct lowered identities"
      (is (not= h r)))
    (testing "both prompt sessions answer the same value"
      (is (= (b0/normalize (:value stack))
             (b0/normalize (:value register))))
      (is (answers-42? stack))
      (is (answers-42? register))
      (is (= 'mod stack-answered))
      (is (= 'mod register-answered))
      (testing "the store crossed with the export, not into the task's own"
        (is (not (contains? (vm/store (:vm (:state stack))) 'n)))
        (is (some? (seq (get-in (:state stack)
                                [:vm :module-stores]))))))))


;; =============================================================================
;; Refusal and pending at the prompt
;; =============================================================================

(deftest a-name-the-environment-lacks-is-refused-test
  (let [store (mem/create-content-mem)
        {:keys [address]} (publish-module store closed-module 'mod
                                          closed-exports)
        state (shell :stack store {'mod address})
        [state' text] (repl/eval-input state "(require (quote other))")
        [state'' _] (repl/eval-input state' "(require (quote mod))")
        [state''' _] (repl/eval-input state'' "(mod/f)")]
    (testing "the require's error names the refusal"
      (is (str/includes? text "absent"))
      (is (nil? (:pending-run state')))
      (testing "and the rolled-back shell carries the minted id forward"
        (is (>= (or (:id-counter (:vm state')) 0) 1))))
    (testing "the named module still links on the next line"
      (is (= 'mod (:last-value state'')))
      (is (nil? (:pending-run state''))))
    (testing "and the shell never reused the refused round's link id"
      (is (= 42 (:last-value state'''))))
    (testing "the shell survives it all"
      (is (= "3" (second (repl/eval-input state''' "(+ 1 2)")))))))


(deftest a-link-that-stays-pending-does-not-wedge-the-shell-test
  (let [state (repl/create-state {:vm-type :stack})
        [pending text] (repl/eval-input state "(require (quote mod))")
        [held text2] (repl/eval-input pending "(+ 1 2)")
        [freed text3] (repl/eval-input held "(abandon)")]
    (testing "the prompt returns with the link reported, not held"
      (is (str/includes? text "pending"))
      (is (str/includes? text "(abandon)"))
      (is (str/includes? text "run when it completes"))
      (is (some? (:pending-run pending)))
      (is (vm/blocked? (:vm pending))))
    (testing "repl-state reports the pending link"
      (is (= ['mod] (mapv :name (:pending (repl/repl-state pending))))))
    (testing "ordinary input is retained with the notice, not evaluated"
      (is (str/includes? text2 "pending"))
      (is (str/includes? text2 "run when it completes"))
      (is (nil? (:last-value held)))
      (is (= 1 (count (:pending-lines (:pending-run held))))))
    (testing "(abandon) raises the require's error and frees the shell"
      (is (str/includes? text3 "abandoned"))
      (is (str/includes? text3 "dropped 1 line"))
      (is (nil? (:pending-run freed)))
      (is (>= (or (:id-counter (:vm freed)) 0) 1)
          "the carried id keeps the next require off the retired one")
      (is (= "3" (second (repl/eval-input freed "(+ 1 2)")))))))


(deftest a-late-response-after-abandon-does-not-settle-a-later-require-test
  "The pair survives a rollback, so a response that lands after the
   rollback -- here the pending round's own request, finally answered --
   must never settle the later require: identity minted before the
   rollback is carried onto the base, and the late response is skipped
   as unknown (yin.vm.linker.md section 7.2, step 7)."
  (let [store (mem/create-content-mem)
        {:keys [address]} (publish-module store closed-module 'mod
                                          closed-exports)
        as-other (:address (publish-module store closed-module 'other
                                           closed-exports))
        state (repl/create-state {:vm-type :stack})
        [pending _] (repl/eval-input state "(require (quote other))")
        [held _] (repl/eval-input pending "(abandon)")
        ;; the wire starts answering, over the same surviving pair
        working (assoc held
                       :link-source (link/composition
                                      {:content-store store
                                       :name-env {'other as-other
                                                  'mod address}}))
        [linked _] (repl/eval-input working "(require (quote mod))")
        [done _] (repl/eval-input linked "(mod/f)")]
    (testing "the late answer to the abandoned request was skipped"
      (let [ids (mapv :yin.link/id (pair-requests (:link-pair linked)))]
        (is (= [{:kind :unknown, :id (first ids), :entry (second ids)}]
               (first (engine/take-link-diagnostics (:vm linked)))))
        (is (not= (first ids) (second ids))
            "the carried identity kept the later require off the retired id")))
    (testing "the later require linked for real and its export applies"
      (is (= 'mod (:last-value linked)))
      (is (= 42 (:last-value done))))))


(deftest an-install-wait-abandons-explicitly-test
  "`(abandon)` derives from the parked VM's live waits: `foo`'s install
   child parks on its own require of `bar`, whose manifest ask the
   source never hears, so the root waits on the `:install`.  The install
   is dropped through the engine's own `refused`, its waiter raises, and
   the child origin it minted is carried onto the base: when the child's
   request is finally answered, the late response is skipped, and the
   next require of `foo` mints a child -- and a `bar` request -- of its
   own."
  (let [store (mem/create-content-mem)
        as-bar (:address (publish-module store closed-module 'bar
                                         closed-exports))
        as-foo (:address (publish-module
                           store
                           (progn (app (v 'require) (lit 'bar))
                                  (def! 'h (lam [] (app (v 'bar/f)))))
                           'foo #{'h}
                           {:yin.module/requires {'bar as-bar}
                            :yin.module/primitives
                            {'require (get-in vm/primitives
                                              ['require :yin.k/profile])}}))
        names {'foo as-foo, 'bar as-bar}
        state (assoc (repl/create-state {:vm-type :stack})
                     :link-source (withholding
                                    (link/composition {:content-store store
                                                       :name-env names})
                                    as-bar))
        [held text] (repl/eval-input state "(require (quote foo))")
        [freed text2] (repl/eval-input held "(abandon)")
        working (assoc freed
                       :link-source (link/composition {:content-store store
                                                       :name-env names}))
        [linked _] (repl/eval-input working "(require (quote foo))")]
    (testing "the root waits on the install; the child on its own link"
      (is (str/includes? text "pending"))
      (is (= [:install] (mapv :reason (:wait-set (:vm held))))))
    (testing "the install waiter raises the abandonment as its error"
      (is (str/includes? text2 "abandoned")))
    (testing "the shell returns to the base with the child origin carried"
      (is (nil? (:pending-run freed)))
      (is (>= (or (:origins (:vm freed)) 0) 1)))
    (testing "the child's late response is skipped, not settled"
      (let [ids (mapv :yin.link/id (pair-requests (:link-pair linked)))]
        (is (= ['foo 'bar 'foo 'bar]
               (mapv :yin.link/name (pair-requests (:link-pair linked)))))
        (is (= (count ids) (count (distinct ids)))
            "the second child never reused the abandoned child's id")
        (is (= [{:kind :unknown, :id (second ids), :entry (nth ids 2)}]
               (first (engine/take-link-diagnostics (:vm linked)))))))
    (testing "the next require links through a child of its own"
      (is (= 'foo (:last-value linked))))))


(deftest lines-typed-while-pending-run-once-when-it-completes-test
  "A line typed while a require is pending is retained and evaluates
   exactly once, in typing order, when the link completes -- here on the
   very next line, whose re-check finishes the require over a content
   source that has started answering."
  (let [store (mem/create-content-mem)
        {:keys [address]} (publish-module store closed-module 'mod
                                          closed-exports)
        state (repl/create-state {:vm-type :stack
                                  :content-client (silent-client)
                                  :name-env {'mod address}})
        [pending _] (repl/eval-input state "(require (quote mod))")
        [held text2] (repl/eval-input pending "(+ 1 2)")
        working (assoc held
                       :link-source (link/composition
                                      {:content-store store
                                       :name-env {'mod address}}))
        [done text3] (repl/eval-input working "(+ 3 4)")]
    (testing "the attempt over the silent wire stays pending"
      (is (some? (:pending-run pending))))
    (testing "the typed line was retained, not evaluated"
      (is (str/includes? text2 "pending"))
      (is (nil? (:last-value held))))
    (testing "completion ran the retained line, then the new one, once"
      (is (str/includes? text3 "3"))
      (is (str/includes? text3 "7"))
      (is (= 7 (:last-value done)))
      (is (nil? (:pending-run done))))))


(deftest a-retained-require-that-parks-stops-the-replay-test
  "A retained line that starts a require of its own which cannot complete
   parks the shell again: the replay stops there, and the lines after it
   -- the triggering line last -- stay retained in typing order for that
   require's completion.  The source never hears the ask for `other`'s
   manifest, so that link stays pending while `mod` completes."
  (let [store (mem/create-content-mem)
        {:keys [address]} (publish-module store closed-module 'mod
                                          closed-exports)
        as-other (:address (publish-module store closed-module 'other
                                           closed-exports))
        state (repl/create-state {:vm-type :stack
                                  :content-client (silent-client)
                                  :name-env {'mod address}})
        [pending _] (repl/eval-input state "(require (quote mod))")
        [held _] (repl/eval-input pending "(+ 1 2)")
        [held' _] (repl/eval-input held "(require (quote other))")
        [held'' _] (repl/eval-input held' "(+ 3 4)")
        working (assoc held''
                       :link-source (withholding
                                      (link/composition
                                        {:content-store store
                                         :name-env {'mod address
                                                    'other as-other}})
                                      as-other))
        [parked text] (repl/eval-input working "(+ 5 6)")
        [freed text2] (repl/eval-input parked "(abandon)")]
    (testing "mod completed and the line before the second require ran once"
      (is (= [3 'mod nil]
             ((juxt :last-value :last-value-2 :last-value-3) parked)))
      (is (not (str/includes? text "7")))
      (is (not (str/includes? text "11"))))
    (testing "the second require parked the shell and was not lost"
      (is (= ['other] (mapv :name (:pending (repl/repl-state parked)))))
      (is (vm/blocked? (:vm parked))))
    (testing "the lines after it, the triggering line last, stay retained"
      (is (= ["(+ 3 4)" "(+ 5 6)"]
             (mapv :trimmed (:pending-lines (:pending-run parked))))))
    (testing "(abandon) drops every retained line and says so"
      (is (str/includes? text2 "abandoned"))
      (is (str/includes? text2 "dropped 2 lines"))
      (is (nil? (:pending-run freed)))
      (is (= 3 (:last-value freed))
          "the rollback returns to the round the second require began in"))))
