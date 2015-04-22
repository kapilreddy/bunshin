(ns bunshin.datastores.redis
  (:require [taoensso.carmine :as r]
            [bunshin.datastores.datastore :refer [BunshinDataStorage]]))


(defmacro redis [server-conf & body] `(try
                                        (r/wcar ~server-conf
                                                ~@body)
                                        (catch Exception e#
                                          nil)))


(def redis-backend
  (reify BunshinDataStorage
    (get [this server-conf key]
      (redis server-conf
             (r/get key)))

    (get-id-xs [this server-conf key]
      (map second
           (partition 2
                      (redis server-conf
                             (r/zrevrange key 0 -1 "WITHSCORES")))))

    (set [this server-conf val-key val id-key id]
      (every? #(= "OK" %)
              (redis server-conf
                     (r/zadd id-key id 1)
                     (r/set val-key val))))

    (prune-ids [this server-conf id-key]
      (= "OK"
         (redis server-conf
                (r/zremrangebyrank key 1 -1))))

    (del [this server-conf keys]
      (every? #(= "OK" %)
              (redis server-conf
                     (doseq [key keys]
                       (r/del key)))))))
