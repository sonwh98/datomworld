(ns dao.jing.coordinate
  "Explicit interpreter from serializable DaoJing coordinates to local live
   content-store handles. Coordinates are data; this namespace owns the
   closed-world interpretation of the backends implemented in this release."
  (:require [dao.jing.file :as file]
            #?(:clj [dao.jing.remote :as remote])))


(defn- validate-file!
  [{:keys [path], :as coordinate}]
  (when-not (and (= #{:dao.jing/type :path} (set (keys coordinate)))
                 (string? path)
                 (not (empty? path)))
    (throw (ex-info "invalid file-backed DaoJing coordinate"
                    {:coordinate coordinate})))
  coordinate)


#?(:clj (defn- validate-remote!
          [{:keys [url options], :as coordinate}]
          (when-not (and (string? url)
                         (not (empty? url))
                         (or (nil? options) (map? options))
                         (= (set (keys coordinate))
                            (cond-> #{:dao.jing/type :url}
                              (contains? coordinate :options) (conj :options))))
            (throw (ex-info "invalid remote DaoJing coordinate"
                            {:coordinate coordinate})))
          coordinate))


(defn open!
  "Open a serializable content-store coordinate as a live DaoJing handle.
   Unsupported coordinate types fail closed; no runtime registry is consulted."
  [{coordinate-type :dao.jing/type, :as coordinate}]
  (case coordinate-type
    :dao.jing/file (file/create-content-file (:path (validate-file!
                                                      coordinate)))
    #?@(:clj [:dao.jing/remote (let [{:keys [url options]} (validate-remote!
                                                             coordinate)]
                                 (remote/connect-content! url (or options {})))])
    (throw (ex-info "unsupported DaoJing content-store coordinate"
                    {:coordinate coordinate}))))
