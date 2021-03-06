(defproject world "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[com.taoensso/timbre "5.1.2"]
                 [http-kit "2.5.3"]
                 [mount "0.1.16"]
                 [org.clojure/clojure "1.10.3"]
                 [ring/ring-core "1.9.4"]
                 [stigmergy/wocket "0.1.6-SNAPSHOT"]]
  :repl-options {:init-ns world.server})
