(ns yin.repl.v2.host.jvm
  "JVM composition of the real WebSocket package into the Yin REPL v2 seam."
  (:require [dao.stream.v2.ws.jvm :as jvm]))


(defn websocket
  "Return the complete JVM `{:connect! :bind! :unbind!}` host seam."
  []
  {:connect! jvm/connect!
   :bind! jvm/listen!
   :unbind! (fn [listener deposit!]
              (jvm/stop-listening!
                listener
                #(deposit! :stopped
                           {:code :yin.repl.v2.endpoint/stopped
                            :message "the host listener stopped"})))})
