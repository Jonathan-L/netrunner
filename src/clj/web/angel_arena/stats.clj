(ns web.angel-arena.stats
  (:require
    [clojure.string :refer [lower-case]]
    [monger.collection :as mc]
    [monger.operators :refer :all]
    [web.angel-arena.runs :refer [finish-run]]
    [web.angel-arena.utils :refer [get-deck-from-id get-losses get-runs]]
    [web.ws :as ws]))

(defonce max-losses 3)

(defn- enter-winner
  [db player {:keys [gameid ending-players state]}]
  (try
    (let [username (get-in player [:user :username])
          other-player (first (filter #(not= username (get-in % [:user :username])) ending-players))
          runs (get-runs db username)
          form (keyword (lower-case format))
          side (keyword (lower-case (:side player)))
          new-runs (update-in runs [form side :games]
                                     (partial map #(if (= (str gameid) (:game-id %))
                                                     {:game-id (str gameid)
                                                      :winner (:winner @state)
                                                      :reason (:reason @state)
                                                      :opponent {:username (get-in other-player [:user :username])
                                                                 :pronouns (get-in other-player [:user :options :pronouns])
                                                                 :identity (get-in @state [(keyword (lower-case (:side other-player))) :identity :title])}}
                                                     %)))]
      (mc/update db "users"
                 {:username username}
                 {"$set" {:angel-arena-runs new-runs}})
      new-runs)
    (catch Exception e
      (println "Caught exception entering winner: " (.getMessage e)))))

(defn game-finished
  [db {:keys [ending-players original-players] :as game}]
  (doall
    (for [player original-players]
      (let [username (get-in player [:user :username])
            end-player (first (filter #(= username (get-in % [:user :username])) ending-players))
            form (keyword (lower-case format))
            side (keyword (lower-case (:side player)))]
        (when-let [runs (enter-winner db player game)]
          (when-let [deck (get-deck-from-id db username (get-in runs [form side :deck-id]))]
            (when (<= max-losses (get-losses (get-in runs [form side])))
              (finish-run db username runs deck))
            (ws/broadcast-to! [(:uid end-player)] :angel-arena/run-update {})))))))