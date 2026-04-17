(ns yin.io.file-output-stream
  "Effect descriptor and handler for file output streams.
   Self-registers into the 'io module and effect registry on require."
  (:require
    #?(:clj [dao.stream :as ds])
    [yin.module :as module]))


(defn file-output-stream
  "Returns an effect descriptor that opens a file output stream."
  ([path] (file-output-stream path nil))
  ([path opts]
   (merge {:effect :io/file-output-stream
           :path path}
          (if (number? opts) {:chunk-size opts} opts))))


(module/register-module! 'io {'file-output-stream file-output-stream})


#?(:clj
   (do
     (defn- handle
       [state effect opts]
       (let [stream-id (:id opts)
             stream (ds/open! {:type :file-output-stream :path (:path effect)})
             stream-ref {:type :stream-ref :id stream-id}]
         {:value stream-ref
          :state (assoc-in state [:store stream-id] stream)}))

     (module/register-effect-handler! :io/file-output-stream handle)))
