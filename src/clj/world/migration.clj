(ns world.migration
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

(def fc1 (family->province-count "1"))

(defn person-id->family-id [person-id]
  (:family-id (first (filter (fn [person]
                               (= (:person-id person)
                                  person-id))
                             families))))


(defn family-subset
  "given a family-id return the data for that family
  "
  [agents family-id]
  ;;what is agents?
  ;;answer agents is the first paramter or the function which-province
  
  (filter (fn [fd]
            (= (:family_id fd)
               family-id
               ))
          agents)
  )
(defn which-province
  "given family subset, return provinces family members live in {:province 1 :people-in-family [1 2 3]}
  "
  [agents family-id]
 
  
  (map :province  (family-subset agents family-id)))




(comment
  ;;how do I get a value of a key in a map?
  (def m {:province 1 :age 10})

  (:age m)
  (:province m)
  
  (family-subset my-agents "2")

  (which-province my-agents "2")
  
  (def foo [{:person_id "5",
             :family_id "2",
             :age "62",
             :province "1",
             :female "0",
             :migrant "0",g
             :schooling "6"}
            {:person_id "6",
             :family_id "2",
             :age "63",
             :province "10",
             :female "1",
             :migrant "0",
             :schooling "7"}])

  (map :province foo)

  (map (fn [m]
         (:province m))
       foo)
  
  
  (let [family-data [{:person-id 1
                      :family-id "Su"}
                     {:person-id 2
                      :family-id "Su"}
                     {:person-id 3
                      :family-id "Su"}
                     {:person-id 4
                      :family-id "vo"}]]
    (filter (fn [fd]
              (= (:family-id fd)
                 "vo"
                 ))
            family-data))

  (which-province "vo")
  (which-province my-agents "1")
  )



(defn family-in-provinces
  "given a person-id, return family member in all provinces"
  [person-id]
  (let [family-id (person-id->family-id person-id)
        family-member-in-provinces (which-province family-id)
        ;;[ [:p1 [1 2 3] [:p2 [4 6]] ]
        ]
    ;;(keep (fn []) family-member-in-provinces)
    ))

(defn person->family-province-pair
  "take person id and return number of the person's family members in every province
  (person->province_family_count 1) => [ {:p1 1} {:p2 1} {:p3 2} ]
  "
  [person-id]

  )

(defn province-weight

  [person-id]
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



(defn load-agents []
  (let [agent_vec (file->vec "agent_1.csv" \,)]
    (map (fn [row]
           {:person_id   (nth row 9)
            :family_id   (nth row 1)
            :age         (nth row 3)
            :province    (nth row 4)
            :female      (nth row 5)
            :migrant     (nth row 6)
            :schooling   (nth row 7)})
         agent_vec)))

(def my-agents (take 100 (load-agents)))

(comment
  (count my-agents)
  (which-province my-agents "1")
  )
