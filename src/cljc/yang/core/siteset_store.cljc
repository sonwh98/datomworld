(ns yang.core.siteset-store
  "Shared tree loading and buffered store operations."
  (:require [yin.vm :as vm]))


(defn load-tree
  "The canonical row set `{:root :rows}` of the tree at `root` in `handle`,
   every row fetched by its address, depth-first: `{:status :missing
   :address a}` for the first row the handle does not hold, nil when a row
   is not its address or no tree row."
  [read-at absent? handle root]
  (loop [todo [root]
         rows {}]
    (if-let [id (peek todo)]
      (if (contains? rows id)
        (recur (pop todo) rows)
        (let [body (read-at handle id)
              slots (when (vector? body)
                      (get vm/semantic-bytecode-grammar (first body)))]
          (cond
            (absent? body) {:status :missing, :address id}
            slots (recur (into (pop todo)
                               (mapcat (fn [[_ kind] v]
                                         (case kind
                                           :node [v]
                                           :nodes v
                                           nil))
                                       slots
                                       (rest body)))
                         (assoc rows id (into [id] body)))
            :else nil)))
      {:root root, :rows rows})))


(defn overlay
  "A write-buffering handle over `base`: reads see the buffer first, then
   `base`; puts land in the buffer only, in put order. `flush!` writes the
   buffer to `base`; dropping the overlay writes nothing."
  [base]
  (let [buf (atom {:bytes {}, :order []})
        sentinel #?(:cljs (js-obj)
                    :default (Object.))]
    {:handle (assoc base
                    :get-bytes-fn (fn [address not-found]
                                    (let [bs (get-in @buf [:bytes address])]
                                      (if (nil? bs)
                                        ((:get-bytes-fn base) address not-found)
                                        bs)))
                    :put-bytes-fn (fn [address bs]
                                    (if (or (contains? (:bytes @buf) address)
                                            (let [existing ((:get-bytes-fn base)
                                                            address
                                                            sentinel)]
                                              (and (some? existing)
                                                   (not (identical? sentinel
                                                                    existing)))))
                                      :present
                                      (do (swap! buf (fn [b]
                                                       (-> b
                                                           (assoc-in [:bytes address]
                                                                     bs)
                                                           (update :order conj
                                                                   address))))
                                          :inserted))))
     :flush! (fn []
               (let [{:keys [bytes order]} @buf]
                 (doseq [a order] ((:put-bytes-fn base) a (get bytes a)))))}))
