(ns bunshin.redis
  (:require [taoensso.carmine :as r]
            [bunshin.backend :refer [BunshinStorageBackend]]))


(defmacro redis [server-conf & body] `(try
                                        (r/wcar ~server-conf
                                                ~@body)
                                        (catch Exception e#
                                          nil)))


(def redis-backend
  (reify BunshinStorageBackend
    (pipeline [this server-conf thunk-fn]
      (redis server-conf
             (thunk-fn)))

    (get [this key]
      (r/get key))

    (get-id-xs [this key]
      (r/zrevrange key 0 -1 "WITHSCORES"))

    (set [this val-key val id-key id]
      (r/zadd id-key id 1)
      (r/set val-key val))

    (prune-ids [this id-key]
      (r/zremrangebyrank key 1 -1))

    (del [this keys]
      (doseq [key keys]
        (r/del key)))))
