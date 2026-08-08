(ns dao.jing.mem
  "An ephemeral, thread-safe in-memory stream materializer.
   Returns a handle map {:stream s :target a :cas-fn f :get-fn g} for
   direct atomic operations. The stream captures appends for durability
   but reads and CAS updates bypass it for performance."
  (:require [dao.jing :as jing]
            [dao.stream :as ds]
            [dao.stream.ringbuffer]))


(defn create-kv-mem
  "Create an ephemeral in-memory storage handle. The stream is an unbounded
   ringbuffer; the target is a thread-safe atom. CAS operations update the
   atom directly with a compare-and-swap loop; the stream appends are
   fire-and-forget best-effort (the atom IS the authority for mem handles)."
  []
  (let [stream (ds/open! {:type :ringbuffer, :capacity 1024})
        target (atom {})]
    {:stream stream,
     :target target,
     :cas-fn (fn mem-cas
               [k expected v]
               (loop []
                 (let [state @target
                       current (if (contains? state k)
                                 (clojure.core/get state k)
                                 jing/absent)]
                   (if (not= expected current)
                     (or (= (clojure.core/get state k jing/absent) expected)
                         (and (= expected jing/absent)
                              (= (clojure.core/get state k jing/absent) v)))
                     (if (compare-and-set! target
                                           state
                                           (if (= v jing/absent)
                                             (dissoc state k)
                                             (assoc state k v)))
                       (do
                         ;; Best-effort durable append (fire-and-forget)
                         (try (ds/append! stream [k :cas expected v])
                              (catch #?(:clj Exception
                                        :cljs :default
                                        :cljd Object)
                                     _))
                         true)
                       (recur)))))),
     :get-fn (fn mem-get
               [k not-found]
               (let [s @target]
                 (if (contains? s k) (clojure.core/get s k) not-found))),
     :delete-fn (fn mem-delete
                  [k]
                  (loop []
                    (let [state @target
                          current (clojure.core/get state k jing/absent)]
                      (if (= current jing/absent)
                        true
                        (if (compare-and-set! target state (dissoc state k))
                          (do (try (ds/append! stream
                                               [k :cas current jing/absent])
                                   (catch #?(:clj Exception
                                             :cljs :default
                                             :cljd Object)
                                          _))
                              true)
                          (recur)))))),
     :close-fn (fn mem-close [] (ds/close! stream) nil)}))
