(ns yin.vm.ucf.holder.inbox
  "Version-1 positional inboxes and canonical receipt reconciliation.
   Journal order is selection order; positions advance only after retention."
  (:require [dao.jing.cbor :as cbor]
            [yin.vm.ucf.custody :as custody]))


(def lanes {:reply :reply-inbox :outcome :outcome-inbox})


(defn- refuse!
  [reason data]
  (throw (ex-info "Invalid holder inbox" (assoc data ::refusal reason))))


(defn check-descriptor!
  [descriptor]
  (when-not (and (map? descriptor) (custody/exact? (:version descriptor)) (= 1 (:version descriptor))
                 (some? (:identity descriptor)) (fn? (:read-at! descriptor)))
    (refuse! :inbox-descriptor {}))
  (cbor/encode (:identity descriptor))
  descriptor)


(defn same-data?
  [left right]
  (= (vec (cbor/encode left)) (vec (cbor/encode right))))


(defn read-at
  [descriptor position]
  (check-descriptor! descriptor)
  (when-not (and (custody/exact? position) (< position custody/max-exact))
    (refuse! :inbox-position-bound {:position position}))
  (let [answer ((:read-at! descriptor) position)]
    (when-not (and (map? answer)
                   (case (:status answer)
                     (:empty :unavailable) (= #{:status} (set (keys answer)))
                     :record (and (= #{:status :position :author :record} (set (keys answer)))
                                  (custody/exact? (:position answer))
                                  (= position (:position answer))
                                  (some? (:author answer)))
                     false))
      (refuse! :inbox-result {:position position}))
    (when (= :record (:status answer))
      (cbor/encode [(:author answer) (:record answer)]))
    answer))


(defn retention?
  [record]
  (and (= :yin.k/inbox (:yin.k/journal record))
       (every? #{:yin.k/journal :yin.k/lane :yin.k/identity :yin.k/position
                 :yin.k/author :yin.k/record :yin.k/binding} (keys record))
       (contains? lanes (:yin.k/lane record))
       (some? (:yin.k/identity record))
       (custody/exact? (:yin.k/position record))
       (< (:yin.k/position record) custody/max-exact)
       (contains? record :yin.k/record)
       (some? (:yin.k/author record))
       (or (not (contains? record :yin.k/binding)) (map? (:yin.k/binding record)))))


(defn retain
  "Fold one receipt in journal order, rejecting gaps or changed contents."
  [receipts record]
  (when-not (retention? record) (refuse! :inbox-retention {}))
  (let [lane (:yin.k/lane record)
        prior (filterv #(= lane (:yin.k/lane %)) receipts)
        position (:yin.k/position record)]
    (when (and (seq prior) (not (same-data? (:yin.k/identity (first prior)) (:yin.k/identity record))))
      (refuse! :inbox-identity {}))
    (cond
      (< position (count prior))
      (if (same-data? record (nth prior position)) receipts (refuse! :inbox-conflict {}))
      (= position (count prior)) (conj receipts record)
      :else (refuse! :inbox-gap {}))))


(defn positions
  [receipts]
  (reduce (fn [positions receipt]
            (update positions (:yin.k/lane receipt) inc))
          {:reply 0 :outcome 0} receipts))


(defn identities
  [receipts]
  (into {} (map (juxt :yin.k/lane :yin.k/identity)) receipts))


(defn reconcile!
  "Recheck durable receipts against reconstructed complete-retention sources."
  [seams receipts]
  (doseq [receipt receipts]
    (let [descriptor (get seams (get lanes (:yin.k/lane receipt)))]
      (when-not (same-data? (:identity descriptor) (:yin.k/identity receipt))
        (refuse! :inbox-identity {}))
      (let [answer (read-at descriptor (:yin.k/position receipt))]
        (when-not (and (= :record (:status answer))
                       (same-data? (:author answer) (:yin.k/author receipt))
                       (same-data? (:record answer) (:yin.k/record receipt)))
          (refuse! (if (= :unavailable (:status answer)) :inbox-unavailable :inbox-conflict) {})))))
  receipts)
