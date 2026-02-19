(ns daodb.index)


(defprotocol IIndex

  (index-tx
    [this tx-datoms]
    "Accepts a transaction (sequence of datoms) and returns the updated index state."))
