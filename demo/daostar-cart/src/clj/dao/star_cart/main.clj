(ns dao.star-cart.main
  "Boot the dao.star cart: the catalog store, the shop and grid compositions
   (one `assemble` function, called twice), the host edge, one tick loop and
   one listener.  Environment: PORT (default 3004), CATALOG_PATH (where saves go;
   the baked seed is copied there on first boot), ASSET_ROOT."
  (:require [dao.star-cart.assemble :as assemble]
            [dao.star-cart.edge :as edge]
            [dao.star-cart.grid-effects :as grid-effects]
            [dao.star-cart.grid-program :as grid-program]
            [dao.star-cart.grid-view :as grid-view]
            [dao.star-cart.program :as program]
            [dao.star-cart.store :as store]
            [dao.star-cart.view :as view]
            [org.httpkit.server :as kit]))


(def seed-path "resources/catalog.edn")
(def default-asset-root "resources/public")


(def default-catalog-path
  "Where saves go when CATALOG_PATH is unset: a writable local file, so edits
   survive a restart.  The baked seed is copied there on first boot."
  "target/cart-catalog.edn")


(defn start!
  "Start the site.  Options: `:port` (0 for ephemeral), `:catalog-path`,
   `:asset-root`, `:timing`.  Answers `{:port :store :shop :grid :stop!}`."
  [{:keys [port catalog-path asset-root timing] :or {asset-root default-asset-root}}]
  (let [st (store/open {:path catalog-path :seed seed-path})
        catalog (store/catalog-fns st)
        shop (assemble/assemble
               {:program program/shop-program
                :project-fn (fn [occ model] (view/shop-project catalog occ model))
                :begin-fn view/shop-begin :app-id "app-body" :timing timing})
        grid (assemble/assemble
               {:program grid-program/grid-program
                :project-fn (fn [occ model] (grid-view/grid-project catalog occ model))
                :begin-fn grid-view/grid-begin :app-id "app" :timing timing})
        fx (grid-effects/make-effects {:driver (:driver grid) :store st :asset-root asset-root})
        tick-ms (get (merge assemble/default-timing timing) :tick-ms)
        edge-handler (edge/make-edge {:asset-root asset-root
                                      :shop (:handler shop) :grid (:handler grid)})
        server (kit/run-server edge-handler {:ip "0.0.0.0" :port (or port 3004)
                                             :max-body 65536 :legacy-return-value? false})
        running (atom true)
        started (System/currentTimeMillis)
        loop-thread (doto (Thread.
                            (fn []
                              (while @running
                                (try
                                  (let [now (- (System/currentTimeMillis) started)]
                                    ((:step! shop) now)
                                    ((:step! grid) now)
                                    (grid-effects/effects-step! fx))
                                  (catch Throwable t
                                    (binding [*out* *err*] (println "star-cart tick:" t))))
                                (Thread/sleep (long tick-ms)))))
                      (.setDaemon true)
                      (.start))]
    {:port (kit/server-port server)
     :store st :shop shop :grid grid :effects fx
     :stop! (fn []
              (reset! running false)
              (.join ^Thread loop-thread 2000)
              ((:stop! shop))
              ((:stop! grid))
              (kit/server-stop! server {:timeout 100}))}))


(defn -main
  [& _]
  (let [port (Long/parseLong (or (System/getenv "PORT") "3004"))
        site (start! {:port port
                      :catalog-path (or (System/getenv "CATALOG_PATH") default-catalog-path)
                      :asset-root (or (System/getenv "ASSET_ROOT") default-asset-root)})]
    (println "dao.star cart on port" (:port site))
    (.join (Thread/currentThread))))
