(require '[datomic.client.api :as d])
(def cl (d/client {:server-type :datomic-local :storage-dir :mem :system "probe"}))
(d/create-database cl {:db-name "p"})
(def db (d/db (d/connect cl {:db-name "p"})))
(defn q* [find vs]
  (try (d/q {:query (list :find find :in '$ '[?v ...]) :args [db vs]})
       (catch Throwable e (ex-message e))))
(doseq [vs [[1 2] [3 4] [1 2 3 4] [37 40] [1.0 2.0] [1 2.0] [-3 -4] [1 2 3]]]
  (println vs "median ->" (pr-str (q* '(median ?v) vs))))
(println "min 0 ->" (pr-str (q* '(min 0 ?v) [3 1 2])))
(println "min -1 ->" (pr-str (q* '(min -1 ?v) [3 1 2])))
(println "rand ->" (pr-str (q* '(rand ?v) [3 1 2])))
