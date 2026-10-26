(ns yin.vm.ucf.crash-cut-test
  "The crash-cut matrix of M-next C slice C12, the stage-C gate (plan
   1.4, 1.5, 1.7): every authority transition under every journal crash
   cut reopens to its pre-state or its post-state projection and nothing
   else, the journal holds only whole transactions, and the retry of the
   same request converges.

   `rows` is the table: one row per transition, [name pre! transition!
   expected].  `pre!` builds the transition's pre-state on a world (see
   yin.vm.ucf.ledger-fixtures/world) holding an open authority under
   :a, `transition!` makes the request and answers its reply, and
   `expected` names the documented answers: :fresh on a pre-state,
   :replay on a post-state, and :reopened {:pre a :post b} after
   grant/reopen!, which reclaims every live tenure first (the C6, C7, C8
   and C10 slice tests document those answers).  The pre-state and the
   post-state are computed from an uncut twin.

   Every row runs on the memory backend.  `file-rows` re-runs a subset
   on the file backend with real dao.jing.file frames: the cut wraps the
   real frame write, the handle is dropped without close, and the torn
   cut also truncates the file's bytes."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [dao.jing.cbor :as cbor]
            [dao.lease :as lease]
            [dao.space.store.fs :as fs]
            [dao.stream :as stream]
            [dao.stream.journal :as journal]
            [dao.stream.journal.file :as file]
            [dao.stream.memory-log :as memory-log]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.grant :as grant]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.ledger :as ledger]
            [yin.vm.ucf.ledger-fixtures :as lf]))


(def ^:private cuts
  [:before-frame :after-frame-before-visible :torn-frame])


(defn- persisted
  "The state a cut leaves persisted: only a whole frame survives."
  [cut]
  (if (= :after-frame-before-visible cut) :post :pre))


;; =============================================================================
;; Requests
;; =============================================================================

(defn- log
  []
  (:dao.stream/handle
    (memory-log/create! {:dao.stream/type :dao.stream/memory-log})))


(defn- judge
  "A judge over the world's authority, its proposals read from the
   world's medium, rebuilt from the ledger (plan 1.5 step 5)."
  [{:keys [a medium]}]
  (let [ticks (log)]
    (stream/append! ticks (lease/tick {:s 1}))
    (grant/rebuild-judge
      a
      (-> (lease/initial-judge
            (merge (grant/judge-config a lf/duration)
                   {:resolver (fn [source _] source) :self lf/arb}))
          (lease/wire-tick ticks
                           (:dao.stream/cursor
                             (stream/cursor ticks :dao.stream/oldest))
                           :ticks)
          (lease/wire-facts medium
                            (:dao.stream/cursor
                              (stream/cursor medium :dao.stream/oldest))
                            "holder-a")))))


(defn- step!
  "One judge step, answered by what it did to the ledger: :committed
   when it committed, :replayed when it committed nothing, :suspended
   when the authority is poisoned.  A judge step has no reply of its
   own."
  [{:keys [a] :as w}]
  (let [before (:next-t (authority/projection a))]
    (grant/step! a (judge w))
    (let [p (authority/projection a)]
      (cond (nil? p) {:yin.k/status :suspended}
            (= before (:next-t p)) {:yin.k/status :replayed}
            :else {:yin.k/status :committed}))))


(defn- enroll-once!
  "Enroll the target the pre-state's next t mints, unless the projection
   already holds it: enrollment has no request identity of its own, so
   the retry is keyed by the derived target identity."
  [{:keys [a enroll-t]}]
  (if (get-in (authority/projection a)
              [:targets (ledger/target-identity lf/arb enroll-t)])
    {:yin.k/status :replayed}
    (authority/enroll! a)))


(defn- offered
  [w]
  (lf/offer! w (lf/park lf/r 0))
  w)


(defn- granted
  [w]
  (lf/grant! (offered w) lf/r "lease-1" "holder-a")
  w)


(defn- enrolled
  [{:keys [a] :as w}]
  (assoc w :i (:yin.k/target (authority/enroll! a))))


(def ^:private succ-1 (lf/park lf/s1 3 (lf/origin lf/r "lease-1")))


;; =============================================================================
;; The table
;; =============================================================================

(def rows
  [["enroll"
    (fn [{:keys [a] :as w}]
      (assoc w :enroll-t (:next-t (authority/projection a))))
    enroll-once!
    {:fresh :committed :replay :replayed
     :reopened {:pre :committed :post :replayed}}]

   ["offer"
    identity
    #(lf/offer! % (lf/park lf/r 0))
    {:fresh :committed :replay :replayed
     :reopened {:pre :committed :post :replayed}}]

   ;; The rebuilt judge has answered a recorded proposal, so a post-state
   ;; grants nothing again; reopen reclaims the grant and regrants nothing.
   ["grant (judge step)"
    (fn [w]
      (let [m (log)]
        (stream/append! m (lease/proposal "p-a" (custody/subject lf/r)))
        (assoc (offered w) :medium m)))
    step!
    {:fresh :committed :replay :replayed
     :reopened {:pre :committed :post :replayed}}]

   ;; Reopen's own reclaim is the same :policy lapse, so the retry replays.
   ["reclaim"
    granted
    #(lf/lapse! % "lease-1" :policy)
    {:fresh :dao.stream/ok :replay :dao.stream/ok
     :reopened {:pre :dao.stream/ok :post :dao.stream/ok}}]

   ;; A pre-state's lease is reclaimed by reopen (:policy), so the release
   ;; retry is refused and the occurrence stays open (completion-test
   ;; before-closure).
   ["completion"
    (fn [w]
      (lf/report! (granted w) lf/r "lease-1" succ-1)
      w)
    #(lf/lapse! % "lease-1" :release)
    {:fresh :dao.stream/ok :replay :dao.stream/ok
     :reopened {:pre :dao.stream/invalid-value :post :dao.stream/ok}}]

   ["halted completion"
    (fn [w]
      (lf/report! (granted w) lf/r "lease-1" (lf/halted lf/r "lease-1"))
      w)
    #(lf/lapse! % "lease-1" :release)
    {:fresh :dao.stream/ok :replay :dao.stream/ok
     :reopened {:pre :dao.stream/invalid-value :post :dao.stream/ok}}]

   ;; After reopen's reclaim a retry is stale, committed or not
   ;; (admission-test a-committed-result-is-redelivered-after-reopen).
   ["admit committed"
    (comp enrolled granted)
    #(lf/admit! % "lease-1" 0 lf/r 0 :v)
    {:fresh :committed :replay :replayed
     :reopened {:pre :stale :post :stale}}]

   ;; A quarantined occurrence suspends admissions before tenure.
   ["admit intent-conflict (quarantine)"
    (fn [w]
      (let [w (enrolled (granted w))]
        (lf/admit! w "lease-1" 0 lf/r 0 :v)
        w))
    #(lf/admit! % "lease-1" 0 lf/r 0 :w)
    {:fresh :intent-conflict :replay :suspended
     :reopened {:pre :stale :post :suspended}}]

   ["input record"
    granted
    #(lf/record! % lf/r "lease-1" 0 0 (lf/read-ok 7 1))
    {:fresh :recorded :replay :replayed
     :reopened {:pre :stale :post :stale}}]

   ["close-target"
    enrolled
    #(authority/close-target! (:a %) (:i %))
    {:fresh :committed :replay :replayed
     :reopened {:pre :committed :post :replayed}}]

   ["refusal pair"
    offered
    #(lf/refuse! % "holder-b" "p-b")
    {:fresh :dao.stream/ok :replay :dao.stream/ok
     :reopened {:pre :dao.stream/ok :post :dao.stream/ok}}]])


(def file-rows
  "The rows re-run on the file backend."
  #{"reclaim" "completion" "admit committed" "input record"})


;; =============================================================================
;; Comparison
;; =============================================================================

(defn- canon
  "`v` with each lease id of projection `p` named by its grant's t: the
   judge mints lease ids at random, so two runs agree only up to them."
  [p v]
  (walk/postwalk-replace
    (into {} (map (fn [[l e]] [l (str "lease@" (:dao.space/t e))]))
          (:leases p))
    v))


(defn- canon-projection
  [p]
  (canon p p))


(defn- whole-transactions
  "Fold `frames` record by record from the empty projection: every frame
   decodes to one whole transaction the ledger can hold.  Answers the
   projection, or the first defect."
  [frames]
  (reduce (fn [p bs]
            (let [v (try (cbor/decode bs)
                         (catch #?(:cljd Object
                                   :clj Throwable
                                   :cljs :default)
                                _
                           ::unreadable))
                  p' (if (= ::unreadable v)
                       {::ledger/defect :unreadable-frame}
                       (ledger/fold-record p (:dao.stream.journal/value v)))]
              (if (::ledger/defect p') (reduced p') p')))
          (ledger/empty-projection lf/arb)
          (rest frames)))


(defn- status
  [rep]
  (lf/answer rep))


(def ^:private poisoned
  "A poisoned authority's answers: :suspended from its own transitions,
   transport-error through the judge's writer (grant/writer)."
  #{:suspended :dao.stream/transport-error})


;; =============================================================================
;; The twin
;; =============================================================================

(defn- open-world
  "A fresh memory world with its authority open."
  []
  (let [w (lf/world)]
    (assoc w :a (::authority/authority
                  (authority/open! (journal/memory-backend (:frames w)
                                                           nil))))))


(defn- twin
  "The uncut run of row: the pre and post projections and frames, and
   the transition's fresh and replayed answers."
  [[_ pre! transition!]]
  (let [w (pre! (open-world))
        a (:a w)
        pre-p (authority/projection a)
        pre-f @(:frames w)
        fresh (status (transition! w))
        post-p (authority/projection a)
        post-f @(:frames w)
        replay (status (transition! w))]
    (is (= post-p (authority/projection a)) "the twin's retry changes nothing")
    {:pre pre-p :post post-p :pre-frames pre-f :post-frames post-f
     :fresh fresh :replay replay}))


;; =============================================================================
;; The memory matrix
;; =============================================================================

(defn- reopened
  "The authority grant/reopen! opens over a copy of `frames`."
  [frames]
  (let [r (grant/reopen! (journal/memory-backend (atom frames) nil) nil)]
    (is (= :open (:yin.k/status r)) (pr-str r))
    (::authority/authority r)))


(defn- run-cut
  [[_ pre! transition! expected] tw cut]
  (let [w (pre! (open-world))
        frames (:frames w)
        _ (authority/close! (:a w))
        a1 (::authority/authority
             (authority/open! (journal/memory-backend frames cut)))
        state (persisted cut)
        target (get tw state)]
    (testing "the cut transition poisons"
      (is (contains? poisoned (status (transition! (assoc w :a a1)))))
      (is (contains? poisoned (status (transition! (assoc w :a a1))))
          "and the poisoned authority stays so")
      (is (nil? (authority/projection a1))))
    (let [r (authority/open! (journal/memory-backend frames nil))
          a2 (::authority/authority r)
          p2 (authority/projection a2)
          kept @frames]
      (testing "(a) the reopened projection is the pre or the post state"
        (is (= :open (:yin.k/status r)) (pr-str r))
        (is (contains? #{(canon-projection (:pre tw))
                         (canon-projection (:post tw))}
                       (canon-projection p2)))
        (is (= (canon-projection target) (canon-projection p2))
            (str "the cut leaves the " (name state) " state")))
      (testing "(c) the journal holds whole transactions only"
        (is (= p2 (whole-transactions kept)))
        (is (= (count (get tw (if (= :pre state) :pre-frames :post-frames)))
               (count kept)))
        (is (= (map #(canon target (cbor/decode %))
                    (get tw (if (= :pre state) :pre-frames :post-frames)))
               (map #(canon p2 (cbor/decode %)) kept))
            "the frames are the twin's, up to judge-minted lease ids"))
      (testing "(b) the retry converges"
        (is (= (if (= :pre state) (:fresh expected) (:replay expected))
               (status (transition! (assoc w :a a2)))))
        (is (= (canon-projection (:post tw))
               (canon-projection (authority/projection a2)))))
      (testing "(b) after grant/reopen!, the documented answer"
        (is (= (get-in expected [:reopened state])
               (status (transition! (assoc w :a (reopened kept))))))))))


(deftest every-transition-under-every-cut-on-the-memory-backend
  (doseq [[n _ _ expected :as row] rows]
    (testing n
      (let [tw (twin row)]
        (is (= (:fresh expected) (:fresh tw)) "the twin answers fresh")
        (is (= (:replay expected) (:replay tw)) "and replays")
        (is (not= (:pre tw) (:post tw)) "the transition changes the ledger")
        (doseq [cut cuts]
          (testing (name cut)
            (run-cut row tw cut)))))))


;; =============================================================================
;; The file subset
;; =============================================================================

(defn- temp-dir
  []
  (str "target/test-ucf-crash-cut-" (random-uuid)))


(defn- cleanup-dir!
  [dir]
  #?(:cljd (try (.deleteSync (dart-io/Directory. dir) .recursive true)
                (catch Object _ nil))
     :clj (let [f (java.io.File. ^String dir)]
            (when (.isDirectory f)
              (doseq [child (.listFiles f)]
                (.delete ^java.io.File child))
              (.delete f)))
     :cljs (try (.rmSync (js/require "fs") dir
                         #js {:recursive true :force true})
                (catch :default _ nil))))


(defn- file-length
  [path]
  #?(:cljd (.lengthSync (dart-io/File. path))
     :clj (.length (java.io.File. ^String path))
     :cljs (.-size (.statSync (js/require "fs") path))))


(defn- truncate-file!
  "Cut the file at path to n bytes, as a crash mid-write leaves it."
  [path n]
  #?(:cljd (let [raf (.openSync (dart-io/File. path)
                                .mode dart-io/FileMode.append)]
             (try (.truncateSync raf n) (finally (.closeSync raf))))
     :clj (with-open [raf (java.io.RandomAccessFile. ^String path "rw")]
            (.setLength raf (long n)))
     :cljs (.truncateSync (js/require "fs") path n)))


(defn- cut-backend
  "File backend b whose next frame write suffers `cut` around the real
   write, then throws: nothing written, the whole frame written, or the
   frame written and the file's last bytes torn off."
  [b dir cut]
  (let [write! (:dao.stream.journal/write-frame! b)
        armed (atom cut)]
    (assoc b :dao.stream.journal/write-frame!
           (fn [bs]
             (let [c @armed
                   path (str dir "/" file/content-name)]
               (reset! armed nil)
               (when-not (= :before-frame c) (write! bs))
               (when (= :torn-frame c)
                 (truncate-file! path (- (file-length path) 3)))
               (if (nil? c)
                 {:dao.stream/outcome :dao.stream/ok}
                 (throw (ex-info "crash cut" {:cut c}))))))))


(defn- run-file-cut
  [[_ pre! transition! expected] tw cut]
  (let [dir (temp-dir)
        opened (atom [])
        open-backend (fn []
                       (let [b (::file/backend (file/backend! dir))]
                         (swap! opened conj b)
                         b))
        state (persisted cut)]
    (try
      (let [b (open-backend)]
        ((:dao.stream.journal/write-frame! b) (lf/header-frame))
        (let [w (pre! (assoc (lf/world)
                             :a (::authority/authority (authority/open! b))))
              _ (authority/close! (:a w))
              a1 (::authority/authority
                   (authority/open! (cut-backend b dir cut)))]
          (is (contains? poisoned (status (transition! (assoc w :a a1)))))
          ;; The process dies: the lock is released, nothing is closed.
          (fs/unlock! (::file/lock b))
          (let [b2 (open-backend)
                a2 (::authority/authority (authority/open! b2))
                p2 (authority/projection a2)]
            (testing "(a) the reopened projection is the pre or the post state"
              (is (= (get tw state) p2) (str "the " (name state) " state")))
            (testing "(c) the file holds whole transactions only"
              (is (= p2 (whole-transactions
                          (:dao.stream.journal/frames
                            ((:dao.stream.journal/frames b2)))))))
            (testing "(b) the retry converges"
              (is (= (if (= :pre state) (:fresh expected) (:replay expected))
                     (status (transition! (assoc w :a a2)))))
              (is (= (:post tw) (authority/projection a2)))))))
      (finally
        (doseq [b @opened] (file/close! b))
        (cleanup-dir! dir)))))


(deftest a-subset-under-every-cut-on-the-file-backend
  (doseq [[n :as row] (filter #(contains? file-rows (first %)) rows)]
    (testing n
      (let [tw (twin row)]
        (doseq [cut cuts]
          (testing (name cut)
            (run-file-cut row tw cut)))))))
