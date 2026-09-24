(ns yin.repl.host.jvm
  "JVM composition of the real WebSocket package into the Yin REPL seam."
  (:require [dao.stream.ws.jvm :as jvm]))


(defn websocket
  "Return the complete JVM `{:connect! :bind! :unbind!}` host seam."
  []
  {:connect! jvm/connect!
   :bind! jvm/listen!
   :unbind! (fn [listener deposit!]
              (jvm/stop-listening!
                listener
                #(deposit! :stopped
                           {:code :yin.repl.endpoint/stopped
                            :message "the host listener stopped"})))})
