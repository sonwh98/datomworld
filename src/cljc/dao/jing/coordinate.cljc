(ns dao.jing.coordinate
  "Explicit interpreter from serializable DaoJing coordinates to local live
   content-store handles. Coordinates are data; this namespace owns the
   closed-world interpretation of the backends implemented in this release."
  (:require [dao.jing.content.step :as step]
            [dao.jing.file :as file]
            [dao.stream :as stream]
            #?@(:cljd []
                :clj [[dao.jing.content.driver :as driver]])))


(defn- validate-file!
  [{:keys [path], :as coordinate}]
  (when-not (and (= #{:dao.jing/type :path} (set (keys coordinate)))
                 (string? path)
                 (not (empty? path)))
    (throw (ex-info "invalid file-backed DaoJing coordinate"
                    {:coordinate coordinate})))
  coordinate)


(defn- validate-remote!
  "The two-descriptor coordinate (docs/design/dao.stream.remote.md,
   section 5): a requests descriptor the client writes and an answers
   descriptor the client reads, both plain data -- the :url form and its
   options are gone with the module that spoke them."
  [{:dao.jing/keys [requests answers], :as coordinate}]
  (when-not (and (= #{:dao.jing/type :dao.jing/requests :dao.jing/answers}
                    (set (keys coordinate)))
                 (map? requests)
                 (map? answers))
    (throw (ex-info "invalid remote DaoJing coordinate"
                    {:coordinate coordinate})))
  coordinate)


(defn- attach-open!
  "Attach one descriptor through the composition's attach entry and
   return its handle; any other outcome throws, so nothing half-attached
   escapes to the caller."
  [attach! descriptor role]
  (let [result (attach! descriptor)]
    (if-not (= :dao.stream/ok (:dao.stream/outcome result))
      (throw (ex-info "the remote DaoJing coordinate refused an attachment"
                      {:role role
                       :descriptor descriptor
                       :result (dissoc result :dao.stream/handle)}))
      (:dao.stream/handle result))))


(defn- newest
  "The cursor `handle` mints at :newest -- or the anchor itself when the
   mint must wait for the far end (a reflection's cursor ask is answered
   across the channel), which the stepped client resolves on its first
   polls."
  [handle]
  (let [r (stream/cursor handle stream/anchor-newest)]
    (if (= :dao.stream/ok (:dao.stream/outcome r))
      (:dao.stream/cursor r)
      stream/anchor-newest)))


(defn- open-remote
  "Attach both descriptors through the composition's :dao.jing/attach
   entry (host composition data: the transport's own attach function or
   dispatch closure -- a coordinate never carries one, being data), and
   resolve to the blocking driver on the JVM and to the stepped client
   elsewhere. :dao.jing/options carries the driver's timing options."
  [{:dao.jing/keys [requests answers]} opts]
  (let [attach! (get opts :dao.jing/attach)]
    (when-not (ifn? attach!)
      (throw (ex-info
               "a remote DaoJing coordinate needs a :dao.jing/attach entry
                in the open options"
               {:opts opts})))
    (let [requests-handle (attach-open! attach! requests :requests)
          answers-handle (attach-open! attach! answers :answers)
          client-state (step/client-state requests-handle answers-handle
                                          (newest answers-handle))]
      #?(:clj (driver/driver client-state
                             (or (get opts :dao.jing/options) {}))
         :default client-state))))


(defn open!
  "Open a serializable content-store coordinate as a live DaoJing handle,
   with the composition's open `opts`. Unsupported coordinate types fail
   closed; no runtime registry is consulted. A :dao.jing/remote
   coordinate opens through opts: {:dao.jing/attach f} attaches its two
   descriptors, and {:dao.jing/options o} carries the JVM driver's
   timing options; on the JVM the resolution is the blocking driver,
   elsewhere the stepped client state the caller owns and steps."
  [{coordinate-type :dao.jing/type, :as coordinate} opts]
  (case coordinate-type
    :dao.jing/file (file/create-content-file (:path (validate-file!
                                                      coordinate)))
    :dao.jing/remote (open-remote (validate-remote! coordinate) opts)
    (throw (ex-info "unsupported DaoJing content-store coordinate"
                    {:coordinate coordinate}))))
