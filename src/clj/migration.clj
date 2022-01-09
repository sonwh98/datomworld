(ns migration.core
  (:require [clojure.pprint :refer :all]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]))

; calculate provincial weight based on family network

(def families [{:person-id 1
                :family-id 1}
               {:person-id 2
                :family-id 1}
               {:person-id 3
                :family-id 1}
               {:person-id 4
                :family-id 1}

               {:person-id 5
                :family-id 2}
               {:person-id 6
                :family-id 2}
               {:person-id 7
                :family-id 2}
               {:person-id 8
                :family-id 2}])

(def person->province {1 :lam-dong
                       2 :quang-nam
                       3 :hanoi
                       4 :hanoi

                       5 :hanoi
                       6 :lam-dong
                       7 :lam-dong
                       8 :lam-dong})

(def family-id->family (group-by :family-id families))

(def vo-family (family-id->family 1))
(def su-family (family-id->family 2))

(defn family->province-count [family]
  (let [province-group-province (group-by first (for [person family]
                                                  (let [person-id (:person-id person)
                                                        province (person->province person-id)]
                                                    [province person-id])))]
    (into {} (map (fn [[province province-person]]
                    [province (mapv second province-person)])
                  province-group-province))))

(defn person-id->family-id [person-id]
  (:family-id (first (filter (fn [person]
                               (= (:person-id person)
                                  person-id))
                             families))))


(defn province-weight [person-id]
  (let [
        family-id (person-id->family-id person-id)
        family (family-id->family family-id)
        family-except-me (remove (fn [p]
                                   (= (:person-id p)
                                      person-id))
                                 family)
        province->person-ids (family->province-count family-except-me)]
    (for [[province family-member] province->person-ids]
      [province (/ (count family-member) (count family-except-me))]
      )
    )
  )

(defn file->vec [file separator]
  (let [reader (io/reader file)]
    (rest (csv/read-csv reader :separator separator))))



; load regional data
(def province_vec (file->vec "/Volumes/GoogleDrive/My Drive/Self study/Econometrics study/R/My model/region(1).csv" \;))

(def province (mapv (fn [row]
                      {:province (nth row 0)
                       :name     (nth row 1)
                       :lat      (nth row 2)
                       :long     (nth row 3)
                       :year     (nth row 4)
                       :income   (nth row 13)
                       :urban    (nth row 15)})
                    province_vec))

(def agent_vec (file->vec "/Volumes/GoogleDrive/My Drive/Self study/Econometrics study/R/My model/agent_1.csv" \,))
(def families  (map (fn [row]
                      { :person_id   (nth row 9)
                       :family_id   (nth row 1)
                       :age         (nth row 3)
                       :province    (nth row 4)
                       :female      (nth row 5)
                       :migrant     (nth row 6)
                       :schooling   (nth row 7)})
                    agent_vec))

(def ten (take 10 agent_init))

(def my_agent (map (fn [row]
                     { :person_id   (nth row 9)
                       :family_id   (nth row 1)
                       :age         (nth row 3)
                       :province    (nth row 4)
                       :female      (nth row 5)
                       :migrant     (nth row 6)
                       :schooling   (nth row 7)})
                    agent_init)
  )


(def m {:agent_id "1", :hh_id "1", :age "41", :province "1", :female "1", :migrant "0", :schooling "10"})


