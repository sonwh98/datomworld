(ns yin.vm.ucf.authority.completion-fold-test
  "The ledger fold arms of slice C8: the recorded report, the closure
   and the edge, each failing closed."
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm.ucf.authority.completion :as completion]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.ledger :as ledger]))


(def ^:private arb "arb")

(def ^:private o "0b000000-0000-4000-8000-000000000001")

(def ^:private s "0b000000-0000-4000-8000-000000000002")

(def ^:private x "0b000000-0000-4000-8000-000000000003")


(defn- address
  [digit]
  (keyword "segment" (str "blake3-" (apply str (repeat 64 digit)))))


(defn- baseline
  [occ]
  {:yin.k/kind :blocked
   :yin.k/occurrence occ
   :yin.k/arbitration {:dao.stream/identity arb :dao.stream/descriptor {}}
   :yin.k/next-op-seq 0
   :yin.k/ops {}})


(defn- offered
  ([] (offered o (address "1")))
  ([occ addr] (custody/offer occ addr "carrier" (baseline occ))))


(defn- granted
  ([] (granted "lease-1" 0))
  ([l e]
   [{:dao.lease/status :dao.lease/accepted
     :dao.lease/lease l
     :dao.lease/proposal (str "p-" l)
     :dao.lease/subject {:yin.k/occurrence o}
     :dao.lease/holder "holder-a"
     :dao.lease/duration {:s 30}}
    (custody/bound o l "holder-a" e)]))


(defn- reported
  ([] (reported "lease-1" s))
  ([l succ]
   (assoc (completion/resumed o l (address "2")) :yin.k/successor succ)))


(defn- lapse
  ([cause] (lapse "lease-1" cause))
  ([l cause] {:dao.lease/status :dao.lease/lapsed
              :dao.lease/lease l
              :dao.lease/cause cause}))


(defn- fold-on
  [p & txs]
  (reduce (fn [p facts]
            (if (::ledger/defect p)
              p
              (ledger/fold-record
                p
                {:dao.space/transaction
                 {:t (:next-t p)
                  :datoms (mapv #(conj % (:next-t p) 1)
                                (ledger/facts->datoms (:next-e p) facts))}})))
          p
          txs))


(defn- fold
  [& txs]
  (apply fold-on (ledger/empty-projection arb) txs))


(def ^:private base [[(offered)] (granted)])


(defn- completion
  "The completion transaction of lease-1 at epoch 0."
  []
  [(lapse :release)
   (completion/completed o "lease-1")
   (completion/succeeded o s)
   (custody/reclaimed o "lease-1" 1)])


(deftest the-fold-records-reports-closures-and-edges
  (let [p (apply fold (conj base [(reported)] (completion)))]
    (is (nil? (::ledger/defect p)))
    (is (= {:yin.k/result (address "2") :yin.k/successor s}
           (select-keys (get-in p [:leases "lease-1"])
                        [:yin.k/result :yin.k/successor])))
    (is (= {:dao.lease/lease "lease-1" :yin.k/successor s}
           (get-in p [:occurrences o :yin.k/closed])))
    (is (= #{:arbitration :max-epoch :next-t :next-e :targets :admitted
             :occurrences :leases :answered}
           (set (keys p)))
        "no transient fold state stays in a projection")
    (is (= :closed-occurrence
           (::ledger/defect (fold-on p (granted "lease-2" 1))))
        "a closed occurrence never grants again")))


(deftest the-fold-refuses-what-a-report-cannot-be
  (let [defect (fn [& txs] (::ledger/defect (apply fold txs)))]
    (is (= :malformed-fact
           (apply defect
                  (conj base [(assoc (reported) :yin.k/successor "S")]))))
    (is (= :malformed-fact
           (apply defect (conj base [(assoc (reported) :yin.k/result "a")]))))
    (is (= :malformed-fact
           (apply defect (conj base [(reported "lease-1" o)])))
        "a successor that is its own predecessor")
    (is (= :unknown-lease (defect [(offered)] [(reported)])))
    (is (= :wrong-occurrence
           (apply defect (conj base [(assoc (reported) :yin.k/occurrence x)]))))
    (is (= :duplicate-report
           (apply defect (conj base [(reported)] [(reported "lease-1" x)]))))
    (is (= :ended-lease
           (apply defect (conj base [(lapse :policy)
                                     (custody/reclaimed o "lease-1" 1)]
                               [(reported)]))))
    (is (= :seen-occurrence
           (apply defect (conj base [(offered x (address "3"))]
                               [(reported "lease-1" x)]))))))


(deftest the-fold-refuses-a-closure-without-its-edge
  (let [defect (fn [& txs] (::ledger/defect (apply fold txs)))
        reported-base (conj base [(reported)])
        [l c e r] (completion)]
    (is (= :closure-without-edge (apply defect (conj reported-base [l c r]))))
    (is (= :unpaired-edge (apply defect (conj reported-base [l e r])))
        "an edge without its closure")
    (is (= :unpaired-edge (apply defect (conj reported-base [l e c r])))
        "the edge follows its closure")
    (is (= :unpaired-edge (apply defect (conj reported-base [l c e e r])))
        "one successor per predecessor")
    (is (= :unreleased (apply defect (conj reported-base [c e])))
        "a closure outside its lapse's transaction")
    (is (= :unreleased
           (apply defect (conj reported-base [l r] [c e])))
        "or in a later one")
    (is (= :unreleased
           (apply defect (conj reported-base [(lapse :policy) c e r])))
        "a closure needs a release")
    (is (= :unreleased (apply defect (conj reported-base [l r c e])))
        "the closure precedes the epoch change")
    (is (= :unreported (apply defect (conj base [l c e r])))
        "a closure needs a report")
    (is (= :successor-mismatch
           (apply defect (conj reported-base
                               [l c (completion/succeeded o x) r])))
        "the edge names the reported successor")
    (is (= :malformed-fact
           (apply defect (conj reported-base
                               [l (assoc c :yin.k/occurrence "O") e r]))))
    (is (= :wrong-occurrence
           (apply defect (conj reported-base
                               [l (assoc c :yin.k/occurrence x) e r]))))))


(deftest the-fold-keeps-the-chain-acyclic
  (let [defect (fn [& txs] (::ledger/defect (apply fold txs)))]
    (testing "an edge to an occurrence the ledger has seen"
      (is (= :seen-occurrence
             (apply defect
                    (conj base [(reported)]
                          [(offered s (address "4"))]
                          (completion))))))
    (testing "two predecessors, one successor"
      (let [o2 x
            p (fold [(offered)] [(offered o2 (address "3"))]
                    (granted)
                    [(assoc-in (first (granted "lease-2" 0))
                               [:dao.lease/subject :yin.k/occurrence] o2)
                     (custody/bound o2 "lease-2" "holder-a" 0)]
                    [(reported)]
                    [(assoc (completion/resumed o2 "lease-2" (address "5"))
                            :yin.k/successor s)]
                    (completion))]
        (is (nil? (::ledger/defect p)))
        (is (= :seen-occurrence
               (::ledger/defect
                 (fold-on p [(lapse "lease-2" :release)
                             (completion/completed o2 "lease-2")
                             (completion/succeeded o2 s)
                             (custody/reclaimed o2 "lease-2" 1)])))
            "the successor already has its predecessor")))))


(deftest the-fold-refuses-completion-at-exhaustion
  (let [top ledger/max-exact
        p (fold-on (assoc-in (fold [(offered)])
                             [:occurrences o :yin.k/epoch] top)
                   (granted "lease-1" top)
                   [(reported)])]
    (is (nil? (::ledger/defect p)))
    (is (= :exhausted-occurrence
           (::ledger/defect
             (fold-on p [(lapse :release)
                         (completion/completed o "lease-1")
                         (completion/succeeded o s)
                         (custody/reclaimed o "lease-1" top)]))))
    (is (nil? (::ledger/defect
                (fold-on p [(lapse :release)
                            (custody/reclaimed o "lease-1" top)])))
        "the release itself is recorded")))


(deftest the-fold-holds-a-halted-completion
  (let [halted (completion/resumed o "lease-1" (address "2"))
        [l c _ r] (completion)
        terminal (completion/terminated o (address "2"))
        p (apply fold (conj base [halted] [l c terminal r]))
        defect (fn [& txs] (::ledger/defect (apply fold txs)))]
    (is (nil? (::ledger/defect p)) "a report with no successor occurrence")
    (is (= {:dao.lease/lease "lease-1" :yin.k/result (address "2")}
           (get-in p [:occurrences o :yin.k/closed])))
    (is (= :duplicate-report
           (apply defect (conj base [halted] [(reported)])))
        "a second report, of either kind")
    (is (= :malformed-fact
           (apply defect (conj base [halted]
                               [l c (assoc terminal :yin.k/successor s) r])))
        "an edge with both targets")
    (is (= :malformed-fact
           (apply defect (conj base [halted]
                               [l c (dissoc terminal :yin.k/result) r])))
        "an edge with neither")
    (is (= :successor-mismatch
           (apply defect (conj base [halted]
                               [l c (completion/terminated o (address "3"))
                                r])))
        "a result other than the reported one")
    (is (= :successor-mismatch
           (apply defect (conj base [(reported)] [l c terminal r])))
        "a terminal edge for a reported continuation")
    (is (= :successor-mismatch
           (apply defect (conj base [halted]
                               [l c (completion/succeeded o s) r])))
        "a successor edge for a reported result")))


(deftest the-fold-refuses-to-complete-a-quarantined-occurrence
  (let [p (assoc-in (apply fold (conj base [(reported)]))
                    [:occurrences o :yin.k/quarantined] true)]
    (is (= :quarantined-occurrence
           (::ledger/defect (apply fold-on p [(completion)]))))))
