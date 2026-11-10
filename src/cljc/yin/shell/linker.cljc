(ns yin.shell.linker
  "Linker progression, composition-side
   (docs/design/yin.repl.dht-composition.md section 7).

   The engine drives the links it can serve itself — a local `dao.jing`
   content store, a remote content client, a bare name environment —
   through `yin.vm.linker.interpreter/round-local`, which takes no node.
   A signed-name source instead reads its content from a node its
   composition is handed each round, so this namespace owns that round:
   it is the only place `round`'s `[linker node]` interface survives, and
   no node crosses inward to the engine from here.

   The composition value is plain data, `{:linker … :link-source … :node
   …}`.  `:linker` is the interpreter value whose generic protocol state —
   link pair, request cursor, admission — the engine carries too, and
   `:node` is the transport's alone.  `serve-links` answers its successor;
   `install` writes back only the generic half, so an engine reading the
   result sees correlated decisions and generic pending-work identities
   and never names a node."
  (:require [yin.vm.linker.interpreter :as link]))


(defn from-engine
  "The composition value an `engine` state and its transport `node`
   compose: the linker and the source it serves read off the engine, the
   node supplied.  A composition with no transport answers `node` nil, and
   the round then serves only what the interpreter serves itself."
  [engine node]
  {:linker (:linker engine)
   :link-source (:link-source engine)
   :node node})


(defn serve-links
  "One composition round of linker progression, the section 7
   `serve-links`: consume the link request media the engine's VMs appended
   to, and answer `{:composition c' :pending p :answered n :progress?
   bool}`.

   `c'` carries the successor linker and the successor node, `p` the
   generic pending-work identities the round could not settle — each
   naming the manifest it waits on under `:manifest`, or the principals it
   waits on under `:principals`, or the admission question under
   `:admission` — and `:progress?` says the round moved something, the
   fact a composition pairs with a pending run's re-check.  The engine is
   not an argument and is not returned: only `install` crosses back."
  ([composition] (serve-links composition link/serve-budget))
  ([{:keys [linker link-source node] :as composition} budget]
   (let [served (link/round (cond-> linker
                              link-source (assoc :source link-source))
                            node
                            budget)]
     {:composition (assoc composition
                          :linker (:linker served)
                          :node (:dht served))
      :pending (:pending served)
      :answered (:answered served)
      :progress? (:progress? served)})))


(defn install
  "`engine` with the composition's served linker successor installed, and
   with nothing else: the generic protocol state crosses back, the node
   stays the composition's."
  [engine composition]
  (assoc engine :linker (:linker composition)))


(defn retire
  "The composition side of an abandoned run: retire the run's requests
   still unread on the link pair, so a later require on the same pair is
   served and a response already in flight is skipped as late.  `run` is
   the generic descriptor the engine mints
   (`yin.vm.linker.interpreter/run-descriptor`); this namespace adds no
   identity of its own."
  [composition run]
  (update composition :linker link/retire run))
