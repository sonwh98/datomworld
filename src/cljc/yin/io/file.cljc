(ns yin.io.file
  "Effect descriptor and handler for live-tail file streams (dao.stream.file).
   Self-registers into the 'yin.io module and effect registry on require.

   Unlike yin.io.file-output-stream / file-input-stream (clj-only handlers), the
   handler is registered under :default — clj, cljd, and cljs — since the
   underlying FileStream supports every host. Lifting the older handlers to
   :default is tracked as future work in the design doc."
  (:require
    [dao.stream :as ds]
    [dao.stream.file]
    [yin.module :as module]))


(defn file
  "Returns an effect descriptor that opens a live-tail file stream."
  ([path] (file path nil))
  ([path opts] (merge {:effect :io/file, :path path} (when (map? opts) opts))))


(module/register-module! 'yin.io {'file file})


(defn- handle
  [state effect opts]
  (let [stream-id (:id opts)
        stream (ds/open! (cond-> {:type :file, :path (:path effect)}
                           (contains? effect :capacity) (assoc :capacity
                                                               (:capacity effect))
                           (:eviction-policy effect) (assoc :eviction-policy
                                                            (:eviction-policy
                                                              effect))))]
    {:value {:type :stream-ref, :id stream-id},
     :state (assoc-in state [:store stream-id] stream)}))


(module/register-effect-handler! :io/file handle)
