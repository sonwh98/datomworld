(ns yin.stream
  "Stream effect descriptors for Yin VM programs.
   Self-registers into the 'stream module on require.

   Functions return effect descriptors; the engine's built-in case handlers
   in engine/handle-effect interpret them."
  (:require
    [yin.module :as module]))


(module/register-module!
  'yin.stream
  {'make   (fn
             ([] {:effect :stream/make :capacity nil})
             ([capacity] {:effect :stream/make :capacity capacity}))
   'cursor (fn [stream-ref] {:effect :stream/cursor :stream stream-ref})
   'next   (fn [cursor-ref] {:effect :stream/next :cursor cursor-ref})
   'put    (fn [stream-ref val] {:effect :stream/put :stream stream-ref :val val})
   'close  (fn [stream-ref] {:effect :stream/close :stream stream-ref})})
