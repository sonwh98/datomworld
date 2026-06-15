(ns dao.stream.gemm
  "GPU GEMM kernel effect as a dao.stream transport.

   Descriptor:
     {:type :gpu/gemm
      :a    [[...]]   ; m×k matrix (sequence of rows)
      :b    [[...]]   ; k×n matrix (sequence of rows)
      :backend :cuda  ; optional, auto-detected}

   Returns a single-item ringbuffer stream that emits either
     {:result <flat-product> :shape [m n] :backend :cuda :device :cuda:0}
   or
     {:error {:kind ... :backend ... :message ...}}."
  (:require
    [dao.stream :as ds]
    [dao.stream.ringbuffer :as ringbuffer]
    [uncomplicate.clojurecuda.core :as cuda]
    [uncomplicate.commons.core :as commons]
    [uncomplicate.neanderthal.core :as nc]
    [uncomplicate.neanderthal.cuda :as ncuda]))


(defmulti gemm-backend
  "Interpreter seam: dispatches GEMM on (:backend descriptor).
   Default: :cuda."
  (fn [descriptor]
    (or (:backend descriptor) :cuda)))


(defn- rows->col-major-seq
  "Convert a sequence of rows (row-major) to a flat column-major sequence.
   Input:  [[a00 a01] [a10 a11]]  (2×2 row-major)
   Output: (a00 a10 a01 a11)       (column-major flat)"
  [rows m n]
  (let [arr (make-array Double/TYPE (* m n))]
    (doseq [i (range m)
            j (range n)]
      (aset arr (+ (* j m) i) (double (nth (nth rows i) j))))
    (seq arr)))


(defn- host-ge->row-major-vec
  "Flatten a Neanderthal GE host matrix to a row-major Clojure vector.
   Neanderthal stores GE matrices column-major. We read entries in
   row-major order via nc/entry."
  [ge-matrix m n]
  (vec (for [i (range m)
             j (range n)]
         (nc/entry ge-matrix i j))))


(defmethod gemm-backend :cuda
  [descriptor]
  (let [{:keys [a b]} descriptor
        m (count a)
        k (count (first a))
        n (count (first b))]
    (try
      (cuda/with-default
        (ncuda/with-default-engine
          (commons/with-release [gpu-a (ncuda/cuge m k (rows->col-major-seq a m k))
                                 gpu-b (ncuda/cuge k n (rows->col-major-seq b k n))
                                 gpu-c (nc/mm gpu-a gpu-b)]
                                (let [host-c (nc/transfer gpu-c)]
                                  {:result (host-ge->row-major-vec host-c m n)
                                   :shape  [m n]
                                   :backend :cuda
                                   :device  :cuda:0}))))
      (catch Exception e
        {:error {:kind    :cuda/runtime-error
                 :backend :cuda
                 :message (.getMessage e)}}))))


(defmethod gemm-backend :rocm
  [_descriptor]
  {:error {:kind    :backend/unimplemented
           :backend :rocm
           :message "rocBLAS backend not yet implemented"}})


(defmethod gemm-backend :default
  [descriptor]
  (gemm-backend (assoc descriptor :backend :cuda)))


(defmethod ds/open! :gpu/gemm
  [descriptor]
  (let [out (ringbuffer/make-ring-buffer-stream 1)]
    (ds/put! out (gemm-backend descriptor))
    (ds/close! out)
    out))
