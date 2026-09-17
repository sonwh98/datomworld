(ns yin.repl.host.common
  "The portable half of the host WebSocket seam.

   A `.cljs` or `.cljd` shadow replaces `yin.repl.host` wholesale rather than
   merging with it, so anything portable written there has to be written once
   per build.  Three copies of one string is exactly how `missing-text` drifted:
   the shadows reported an unmet host differently from the portable namespace,
   and the tests asserted only a shared prefix, so nothing caught it.

   What every build shares therefore lives here — the shape of a conforming
   adapter, and the diagnostic for a host that composed none.  `yin.repl.host`
   keeps only `websocket`, the one thing that genuinely differs per build.")


(def missing-code
  "Qualified to `yin.repl.host`, not to this namespace: the code names the
   seam that is unmet rather than the file the constant sits in, and
   `yin.repl.serve` renders it into text that `v2_serve_test` asserts."
  :yin.repl.host/no-websocket-package)


(def missing-text
  (str "no host WebSocket package is composed for this build: the v2 REPL "
       "boundary needs {:connect! …} for (connect …) and {:bind! … :unbind! …} "
       "for --port"))


(defn adapter?
  "True for a host adapter this slice can compose a client boundary from.
   `:bind!` and `:unbind!` are additionally required to serve."
  [x]
  (and (map? x) (fn? (:connect! x))))


(defn binder?
  [x]
  (and (map? x) (fn? (:bind! x)) (fn? (:unbind! x))))


(defn missing-message
  [what]
  (str what ": " missing-text))
