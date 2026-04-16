(ns yin.io
  "File I/O module for Yin VM.
   Registers effect builders as the 'io module and JVM handlers in the effect registry."
  (:require
    [dao.stream :as ds]
    [yin.module :as module]))


(defn input-stream
  ([path] (input-stream path 65536))
  ([path chunk-size]
   {:effect :io/input-stream
    :path path
    :chunk-size chunk-size}))


(defn output-stream
  [path]
  {:effect :io/output-stream
   :path path})


;; Module registration
(module/register-module!
  'io
  {'input-stream input-stream
   'output-stream output-stream})


;; Effect handler registration
#?(:clj
   (do
     (defn- handle-input-stream
       [state effect opts]
       (let [id (:id opts)
             descriptor {:type :file-input-stream
                         :path (:path effect)
                         :chunk-size (:chunk-size effect)}
             stream (ds/open! descriptor)
             new-store (assoc (:store state) id stream)
             stream-ref {:type :stream-ref, :id id}]
         {:value stream-ref
          :state (assoc state :store new-store)}))

     (defn- handle-output-stream
       [state effect opts]
       (let [id (:id opts)
             descriptor {:type :file-output-stream
                         :path (:path effect)}
             stream (ds/open! descriptor)
             new-store (assoc (:store state) id stream)
             stream-ref {:type :stream-ref, :id id}]
         {:value stream-ref
          :state (assoc state :store new-store)}))

     (module/register-effect-handler! :io/input-stream handle-input-stream)
     (module/register-effect-handler! :io/output-stream handle-output-stream)))
