(ns dao.space.schema-fixtures
  "Stream fixtures for dao.space.schema tests.

   This namespace must stay free of deftest forms. On ClojureDart a deftype
   emitted by a test namespace cannot be referenced from another namespace:
   test libraries are renamed under test/ while multimethod contribution
   tables (dao.stream/open!) point at the canonical lib/cljd-out path. An
   open! registration (ds/defopen) therefore belongs in a plain namespace
   that test namespaces require, not in a test namespace itself."
  (:require [dao.stream :as ds])
  #?(:cljs (:require-macros [dao.stream])))


(deftype RecordingStream
  [rows close-count closed?]

  ds/IDaoStreamReader

  (next
    [_ cursor]
    (let [pos (:position cursor)]
      (if (< pos (count @rows))
        {:ok (nth @rows pos) :cursor {:position (inc pos)}}
        :end)))


  ds/IDaoStreamBound

  (close!
    [_]
    (swap! close-count inc)
    (reset! closed? true)
    {:woke []})


  (closed? [_] @closed?))


(def recording-rows
  "Atom holding the tuple vector the next :schema.test/recording open! serves."
  (atom nil))


(def recording-close-count
  "Atom rebound by each :schema.test/recording open! to that stream's
   close-count atom, so tests can assert how often close! fired."
  (atom nil))


(def recording-closed?
  "Atom rebound by each :schema.test/recording open! to that stream's
   closed? atom."
  (atom nil))


#_{:clj-kondo/ignore [:unresolved-symbol :unresolved-var]}


(ds/defopen :schema.test/recording
            [_d]
            (let [rows (atom @recording-rows)
                  close-count (atom 0)
                  closed? (atom false)]
              (reset! recording-close-count close-count)
              (reset! recording-closed? closed?)
              (->RecordingStream rows close-count closed?)))
