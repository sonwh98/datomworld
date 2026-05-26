(ns datomworld.demo.responsive)


(defn auto-fit-grid
  [min-column-px]
  (str "repeat(auto-fit, minmax(min(100%, " min-column-px "px), 1fr))"))


(defn fluid-height
  [min-px viewport-height max-px]
  (str "clamp(" min-px "px, " viewport-height "vh, " max-px "px)"))


(defn canvas-frame-style
  [{:keys [max-width min-height height-vh max-height border-color border-radius
           background box-shadow]
    :or {max-width 900,
         min-height 300,
         height-vh 60,
         max-height 720,
         border-color "rgba(210,220,255,0.24)",
         border-radius 20,
         background "#050711",
         box-shadow "0 28px 90px rgba(0,0,0,0.48)"}}]
  {:width "100%",
   :max-width (str max-width "px"),
   :height (fluid-height min-height height-vh max-height),
   :display "block",
   :border (str "1px solid " border-color),
   :border-radius (str border-radius "px"),
   :background background,
   :box-shadow box-shadow})
