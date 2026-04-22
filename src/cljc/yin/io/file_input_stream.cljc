(ns yin.io.file-input-stream
  "Effect descriptor and handler for file input streams.
   Self-registers into the 'io module and effect registry on require."
  (:require
    #?(:clj [dao.stream :as ds])
    #?(:clj [dao.stream.file-input-stream])
    [yin.module :as module]))


(defn file-input-stream
  "Returns an effect descriptor that opens a file input stream."
  ([path] (file-input-stream path nil))
  ([path opts]
   (merge {:effect :io/file-input-stream
           :path path}
          (if (number? opts) {:chunk-size opts} opts))))


(module/register-module! 'yin.io {'file-input-stream file-input-stream})


#?(:clj
   (do
     (defn- handle
       [state effect opts]
       (let [stream-id (:id opts)
             descriptor (cond-> {:type :file-input-stream
                                 :path (:path effect)}
                          (:chunk-size effect) (assoc :chunk-size (:chunk-size effect)))
             stream (ds/open! descriptor)
             stream-ref {:type :stream-ref :id stream-id}]
         {:value stream-ref
          :state (assoc-in state [:store stream-id] stream)}))

     (module/register-effect-handler! :io/file-input-stream handle)))
