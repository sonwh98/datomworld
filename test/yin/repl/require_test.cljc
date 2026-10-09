(ns yin.repl.require-test
  "M5 (docs/design/yin.vm.linker.md sections 6.1, 7.2 and 9): `(require
   'foo)` typed at the prompt of `yin.repl` reaches the shell's linker
   interpreter, links the module's manifest over the content pair,
   installs through the child phases, and resumes with the export bound
   -- on every backend the shell supports.  The content source is an
   in-process `dao.jing` store served behind the content interpreter
   (`dao.jing.content/serve-step`); the link pair is the session's, so a
   link whose source cannot answer surfaces as the shell's own
   `:pending` state, and `(abandon)` gives it up.

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
            [dao.jing.mem :as mem]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [yin.repl :as repl]
            [yin.repl.frontends :as repl.frontends]
            [yin.repl.link :as link]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.debruijn-vm-contract-test :as b0]
            [yin.vm.linker.publish :as publish]
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
   so this one links on semantic, stack and register, whose scans
   discharge the read."
  (progn (def! 'n (lit 40))
         (def! 'f (lam [] (app (v '+) (v 'n) (lit 2))))))


(def store-exports
  #{'n 'f})


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
   linking.  `overlay` supplies requires and primitive declarations."
  [store ast name exports & [overlay]]
  (let [declared (merge {'+ (get vm/primitives '+)}
                        (into {} (map (fn [[n _]] [n (get vm/primitives n)]))
                              (:yin.module/primitives overlay)))
        result (publish/publish-module!
                 store {:name name :ast ast :exports exports
                        :requires (or (:yin.module/requires overlay) {})
                        :primitives declared})]
    {:address (:address result)
     :h (get-in result [:identities :yin.debruijn.code])
     :r (get-in result [:identities :yin.debruijn.register])}))


(defn- shell
  "A shell of `vm-type` whose content source is the in-process `store`
   and whose name environment resolves `env`."
  [vm-type store env]
  (repl.frontends/create-state {:vm-type vm-type
                      :content-store store
                      :name-env env}))


(defn- silent-client
  "The linker's content-pair client on a wire nothing answers: requests
   are delivered and nothing ever comes back, so every link attempt
   spends its budget and reports the link pending."
  []
  (let [medium #(-> {:dao.stream/type ring/transport-type
                     ring/capacity-key 64}
                    ring/create!
                    :dao.stream/handle)
        responses (medium)]
    {:requests (medium)
     :answers responses
     :cursor (:dao.stream/cursor
               (stream/cursor responses stream/anchor-newest))}))


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
                  (if (= address (get value :jing/get))
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
  (let [state (repl.frontends/create-state {:vm-type :stack})
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
        state (repl.frontends/create-state {:vm-type :stack})
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
        state (assoc (repl.frontends/create-state {:vm-type :stack})
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
        state (repl.frontends/create-state {:vm-type :stack
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
        state (repl.frontends/create-state {:vm-type :stack
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


;; =============================================================================
;; The session link policy (docs/design/yin.repl.link-policy.md, phase 1)
;; =============================================================================

(defn- refusal-of
  "What `create-state` answers to the `:link-policy` `policy`: the
   thrown error when it refuses it (the assembly fails closed), or
   `:accepted` when it does not."
  [policy]
  (try
    (repl.frontends/create-state {:vm-type :stack :link-policy policy})
    :accepted
    (catch #?(:cljd Object :clj Exception :cljs js/Error) e
      e)))


(deftest link-policy-defaults-to-manual-test
  (testing ":manual is the default, and nil passes through to it"
    (is (= :manual (:link-policy (repl.frontends/create-state {:vm-type :stack}))))
    (is (= :manual (:link-policy
                     (repl.frontends/create-state {:vm-type :stack
                                         :link-policy nil})))))
  (let [state (repl.frontends/create-state {:vm-type :stack})
        [pending text] (repl/eval-input state "(require (quote mod))")
        [held _] (repl/eval-input pending "(+ 1 2)")
        [held2 _] (repl/eval-input held "(+ 3 4)")
        [held3 _] (repl/eval-input held2 "(+ 5 6)")]
    (testing "the run parks as today and survives every re-check"
      (is (str/includes? text "pending"))
      (is (some? (:pending-run held3)))
      (is (vm/blocked? (:vm held3))))
    (testing "repl-state names the policy and the no-progress checks"
      (is (= [{:name 'mod :policy :manual :checks 3}]
             (mapv #(select-keys % [:name :policy :checks])
                   (:pending (repl/repl-state held3))))))))


(deftest a-function-policy-abandons-after-n-no-progress-checks-test
  (let [views (atom [])
        policy (fn [view]
                 (swap! views conj view)
                 (if (>= (:checks view) 2)
                   {:abandon :host-gave-up}
                   :keep))
        state (repl.frontends/create-state {:vm-type :stack :link-policy policy})
        [pending _] (repl/eval-input state "(require (quote mod))")
        [held _] (repl/eval-input pending "(+ 1 2)")
        [freed text] (repl/eval-input held "(+ 3 4)")]
    (testing "the policy saw the section 3.1 view, plain data only"
      (is (= [0 1 2] (mapv :checks @views)))
      (is (= [0 1 2] (mapv :lines-retained @views)))
      (is (= ['mod] (mapv :name (:links (first @views)))))
      (is (vector? (:link-id (first (:links (first @views)))))))
    (testing "the second no-progress check ended the require"
      (is (nil? (:pending-run freed)))
      (is (str/includes? text "the session link policy ended the require"))
      (is (str/includes? text "Module link refused: host-gave-up"))
      (is (not (str/includes? text "require pending"))
          "a policy abandon is never mistaken for a run still waiting"))
    (testing "the retained lines dropped once, reported once"
      (is (str/includes? text "dropped 2 lines"))
      (is (= 1 (count (re-seq #"dropped" text)))))
    (testing "the shell returned to the round the require began in"
      (is (>= (or (:id-counter (:vm freed)) 0) 1)
          "the carried id keeps the next require off the retired one")
      (is (= "3" (second (repl/eval-input freed "(+ 1 2)")))))))


(deftest a-keep-policy-never-ends-a-pending-run-test
  (let [state (repl.frontends/create-state {:vm-type :stack
                                  :link-policy (constantly :keep)})
        [pending _] (repl/eval-input state "(require (quote mod))")
        [held _] (repl/eval-input pending "(+ 1 2)")]
    (is (some? (:pending-run held)))
    (is (= 1 (:checks (:pending-run held))))
    (is (= :fn (:policy
                 (first (:pending (repl/repl-state held))))))))


(deftest a-bare-abandon-uses-the-policy-reason-test
  (let [state (repl.frontends/create-state {:vm-type :stack
                                  :link-policy (constantly :abandon)})
        [_ text] (repl/eval-input state "(require (quote mod))")]
    (is (str/includes? text "the session link policy ended the require"))
    (is (str/includes? text "Module link refused: link-policy"))
    (is (not (str/includes? text "require pending")))))


(deftest a-misbehaving-policy-is-kept-and-reported-test
  (let [boom (fn [_] (throw (ex-info "host policy boom" {})))
        state (repl.frontends/create-state {:vm-type :stack :link-policy boom})
        [pending text] (repl/eval-input state "(require (quote mod))")]
    (testing "a throw is one error line, and the run stands"
      (is (str/includes? text "Error: host policy boom"))
      (is (str/includes? text "pending"))
      (is (some? (:pending-run pending)))))
  (let [state (repl.frontends/create-state {:vm-type :stack
                                  :link-policy (constantly :bogus)})
        [pending text] (repl/eval-input state "(require (quote mod))")]
    (testing "an out-of-contract return is kept and reported too"
      (is (str/includes? text "outside its contract"))
      (is (some? (:pending-run pending)))))
  (let [state (repl.frontends/create-state
                {:vm-type :stack
                 :link-policy (fn [_] {::x :not-part-of-the-contract})})
        [pending text] (repl/eval-input state "(require (quote mod))")]
    (testing "a map with no :abandon is out of contract, not an abandon"
      (is (str/includes? text "outside its contract"))
      (is (some? (:pending-run pending)))))
  (let [state (repl.frontends/create-state
                {:vm-type :stack
                 :link-policy (fn [_] {:abandon :reason :extra true})})
        [pending text] (repl/eval-input state "(require (quote mod))")]
    (testing "a map with extra keys beside :abandon is out of contract"
      (is (str/includes? text "outside its contract"))
      (is (str/includes? text "outside its contract"))
      (is (some? (:pending-run pending))))))


(deftest unknown-and-lease-link-policies-are-refused-at-create-state-test
  (doseq [policy [:lease :whenever "manual" 42]]
    (testing (pr-str policy)
      (let [e (refusal-of policy)]
        (is (not= :accepted e))
        (is (= [:manual :fn] (:supported (ex-data e)))))))
  (testing ":lease is refused as not implemented yet"
    (is (str/includes? (ex-message (refusal-of :lease))
                       "not implemented yet"))))


(deftest reset-and-vm-preserve-the-link-policy-test
  (let [policy (constantly :keep)
        state (repl.frontends/create-state {:vm-type :stack :link-policy policy})
        [pending _] (repl/eval-input state "(require (quote mod))")
        [held _] (repl/eval-input pending "(+ 1 2)")
        [held2 _] (repl/eval-input held "(+ 3 4)")
        [reset-state _] (repl/eval-input held2 "(reset)")
        [vm-state _] (repl/eval-input reset-state "(vm :register)")
        [pending' _] (repl/eval-input vm-state "(require (quote mod))")
        [held' _] (repl/eval-input pending' "(+ 1 2)")]
    (testing "the policy survives (reset) and (vm ...)"
      (is (identical? policy (:link-policy reset-state)))
      (is (identical? policy (:link-policy vm-state))))
    (testing "the dropped run's checks went with it; a new park starts at 0"
      (is (nil? (:pending-run reset-state)))
      (is (= 0 (:checks (:pending-run pending'))))
      (is (= 1 (:checks (:pending-run held')))))))


(deftest a-policy-abandon-matches-abandon-s-retained-line-semantics-test
  (let [by-command (let [state (repl.frontends/create-state {:vm-type :stack})
                         [parked _] (repl/eval-input
                                      state "(require (quote mod))")
                         [held _] (repl/eval-input parked "(+ 1 2)")]
                     (repl/eval-input held "(abandon)"))
        by-policy (let [state (repl.frontends/create-state
                                {:vm-type :stack
                                 :link-policy (fn [v]
                                                (if (= 1 (:checks v))
                                                  :abandon
                                                  :keep))})
                        [parked _] (repl/eval-input
                                     state "(require (quote mod))")]
                    (repl/eval-input parked "(+ 1 2)"))
        [command-state command-text] by-command
        [policy-state policy-text] by-policy]
    (testing "both drop the retained line once and report it once"
      (is (str/includes? command-text "dropped 1 line"))
      (is (str/includes? policy-text "dropped 1 line"))
      (is (= 1 (count (re-seq #"dropped" command-text))))
      (is (= 1 (count (re-seq #"dropped" policy-text)))))
    (testing "only the policy abandon says the session policy ended it"
      (is (not (str/includes? command-text "session link policy")))
      (is (= 1 (count (re-seq #"session link policy" policy-text)))))
    (testing "both carry the minted identity and free the shell"
      (is (nil? (:pending-run command-state)))
      (is (nil? (:pending-run policy-state)))
      (is (>= (or (:id-counter (:vm command-state)) 0) 1))
      (is (>= (or (:id-counter (:vm policy-state)) 0) 1))
      (is (= "3" (second (repl/eval-input command-state "(+ 1 2)"))))
      (is (= "3" (second (repl/eval-input policy-state "(+ 1 2)")))))))


(deftest a-late-response-after-a-policy-abandon-is-skipped-test
  "The pair survives the policy abandon as it survives `(abandon)`: the
   abandoned round's request, answered late over the same pair by the
   source that finally works, is skipped as unknown, and the next
   require mints an id of its own (yin.vm.linker.md section 7.2, step
   7)."
  (let [store (mem/create-content-mem)
        {:keys [address]} (publish-module store closed-module 'mod
                                          closed-exports)
        as-other (:address (publish-module store closed-module 'other
                                           closed-exports))
        state (repl.frontends/create-state
                {:vm-type :stack
                 :content-client (silent-client)
                 :name-env {'other as-other 'mod address}
                 :link-policy (fn [v]
                                (if (= 1 (:checks v))
                                  :abandon
                                  :keep))})
        [pending _] (repl/eval-input state "(require (quote other))")
        [held text] (repl/eval-input pending "(+ 1 2)")
        working (assoc held
                       :link-source
                       (link/composition
                         {:content-store store
                          :name-env {'other as-other 'mod address}}))
        [linked _] (repl/eval-input working "(require (quote mod))")
        [done _] (repl/eval-input linked "(mod/f)")]
    (testing "the policy ended the require with the retained line dropped"
      (is (str/includes? text "the session link policy ended the require"))
      (is (str/includes? text "dropped 1 line"))
      (is (nil? (:pending-run held))))
    (testing "the late answer was skipped; the later require linked for real"
      (let [ids (mapv :yin.link/id (pair-requests (:link-pair linked)))]
        (is (= [{:kind :unknown, :id (first ids), :entry (second ids)}]
               (first (engine/take-link-diagnostics (:vm linked)))))
        (is (not= (first ids) (second ids))
            "the carried identity kept the later require off the retired id"))
      (is (= 'mod (:last-value linked)))
      (is (= 42 (:last-value done))))))


(deftest a-completing-re-check-never-consults-the-policy-test
  (let [consults (atom 0)
        store (mem/create-content-mem)
        {:keys [address]} (publish-module store closed-module 'mod
                                          closed-exports)
        state (repl.frontends/create-state
                {:vm-type :stack
                 :content-client (silent-client)
                 :name-env {'mod address}
                 :link-policy (fn [_] (swap! consults inc) :keep)})
        [pending _] (repl/eval-input state "(require (quote mod))")
        [held _] (repl/eval-input pending "(+ 1 2)")
        working (assoc held
                       :link-source
                       (link/composition
                         {:content-store store
                          :name-env {'mod address}}))
        [done _] (repl/eval-input working "(+ 3 4)")]
    (testing "the run completed and ran the retained line, then the new one"
      (is (= 7 (:last-value done)))
      (is (= 3 (:last-value-2 done)))
      (is (nil? (:pending-run done))))
    (testing "the park and pending re-check consulted; completion did not"
      (is (= 2 @consults)))))


(deftest recheck-pending-steps-a-run-without-an-input-line-test
  "Section 4: the host drivers can only trigger a re-check with a typed
   line -- `driver/repl-step` evaluates drained input and nothing else
   -- so the shell carries the public step an unattended host drives at
   its own cadence."
  (let [state (repl.frontends/create-state {:vm-type :stack
                                  :link-policy (constantly :keep)})
        [pending _] (repl/eval-input state "(require (quote mod))")
        [step1 _] (repl/recheck-pending pending)
        [step2 text] (repl/recheck-pending step1)]
    (testing "each step is one no-progress re-check"
      (is (some? (:pending-run step2)))
      (is (= 2 (:checks (:pending-run step2))))
      (is (str/includes? text "pending")))
    (let [empty (repl.frontends/create-state {:vm-type :stack})
          [st text] (repl/recheck-pending empty)]
      (testing "nothing pending: nothing printed, nothing changed"
        (is (nil? text))
        (is (nil? (:pending-run st))))))
  (let [state (repl.frontends/create-state
                {:vm-type :stack
                 :link-policy (fn [v]
                                (if (= 2 (:checks v))
                                  :abandon
                                  :keep))})
        [pending _] (repl/eval-input state "(require (quote mod))")
        [step1 _] (repl/recheck-pending pending)
        [step2 text] (repl/recheck-pending step1)]
    (testing "an unattended policy abandon ends the run with the message"
      (is (nil? (:pending-run step2)))
      (is (str/includes? text "the session link policy ended the require"))
      (is (str/includes? text "Module link refused: link-policy")))))


;; =============================================================================
;; The DHT link source beside the two content sources
;; (docs/design/yin.vm.linker.dht.md 4.1, slice L3)
;; =============================================================================

(deftest a-dht-source-composes-alone-and-the-content-budget-is-unchanged-test
  (testing "a DHT source holds no handle of its own"
    (is (= {:kind :dht} (:content (link/composition {:dht? true})))))
  (testing "and is refused together with either content source"
    (doseq [opts [{:content-store (mem/create-content-mem)}
                  {:content-client (silent-client)}]]
      (is (some? (try (link/composition (assoc opts :dht? true))
                      nil
                      (catch #?(:cljd Object :clj Exception :cljs js/Error) e
                        e))))))
  (testing "the :content-store and :content-client attempt budget is unchanged"
    (is (= 64 link/attempt-budget))
    (let [state (repl.frontends/create-state {:vm-type :stack
                                    :content-client (silent-client)
                                    :name-env {'mod (:address
                                                      (publish-module
                                                        (mem/create-content-mem)
                                                        closed-module 'mod
                                                        closed-exports))}})
          [pending text] (repl/eval-input state "(require (quote mod))")]
      (is (str/includes? text "pending"))
      (is (nil? (:manifest (first (:links (:pending-run pending)))))
          "a content-pair link waits on no load"))))
