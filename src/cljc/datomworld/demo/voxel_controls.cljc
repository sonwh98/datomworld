(ns datomworld.demo.voxel-controls
  "On-screen controls for the voxel demo: a movement pad on the left and
   up/down flight buttons on the right. This namespace owns the layout and the
   pressed-button state; dao.gui.event owns hit-testing (each button is a node
   in the presented geometry), so a finger on a button never becomes a look
   drag. The frontends draw the buttons as inert chrome from the same layout.")


(def actions [:forward :back :left :right :up :down])


(defn node-id
  [action]
  (keyword "voxel.control" (name action)))


(def ^:private action-by-node
  (into {} (map (juxt node-id identity)) actions))


(defn action-of
  "The motion action of a button node, or nil for any other node."
  [node]
  (get action-by-node node))


(def ^:private labels
  {:forward "▲",
   :back "▼",
   :left "◀",
   :right "▶",
   :up "UP",
   :down "DN"})


;; [column row] on the 3x3 movement pad
(def ^:private pad {:forward [1 0], :left [0 1], :right [2 1], :back [1 2]})


(defn layout
  "Button rects in surface-local pixels (origin top-left, y down), sized so
   each stays a finger-sized target: the movement pad in the bottom-left
   corner and the flight pair in the bottom-right."
  [{:keys [width height]}]
  (let [size (-> (* 0.12 (min width height))
                 (max 44.0)
                 (min 72.0))
        gap (* 0.12 size)
        margin (* 0.4 size)
        pad-top (- height margin (* 3 size) (* 2 gap))
        button (fn [action x y]
                 {:action action,
                  :node-id (node-id action),
                  :label (get labels action),
                  :x x,
                  :y y,
                  :width size,
                  :height size})]
    (into (mapv (fn [[action [col row]]]
                  (button action
                          (+ margin (* col (+ size gap)))
                          (+ pad-top (* row (+ size gap)))))
                pad)
          [(button :up
                   (- width margin size)
                   (- height margin (* 2 size) gap))
           (button :down (- width margin size) (- height margin size))])))


(defn reduce-pressed
  "Folds one raw pointer event delivered for a button node into the map of
   pressed buttons, pointer id -> action. A press holds until the same
   pointer lifts or is cancelled, wherever it has slid to."
  [pressed event]
  (if-let [action (action-of (:node-id event))]
    (let [pointer-id (get-in event [:pointer :id])]
      (case (:phase event)
        :down (assoc pressed pointer-id action)
        (:up :cancel) (dissoc pressed pointer-id)
        pressed))
    pressed))


(defn pressed-actions
  [pressed]
  (set (vals pressed)))
