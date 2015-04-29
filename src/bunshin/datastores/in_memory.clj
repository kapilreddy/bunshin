(ns bunshin.datastores.in-memory
  (:require [bunshin.datastores.datastore :refer [BunshinDataStorage]]
            [clojure.test.check.generators :as gen]
            [clj-time.core :as ctc]))

(let [sleep-seq (gen/sample (gen/frequency [[1 (gen/return 300)]
                                            [4 (gen/return 4)]
                                            [95 (gen/return 1)]])
                            10000)]
  (defn rand-sleep
    []
    (nth sleep-seq (rand-int (dec 10000)))))


(defprotocol TestableServer
  (start [this server fresh?])
  (partial-fail [this server map])
  (shutdown [this server])
  (get-data [this]))


(defn gen-in-memory-backend
  []
  (let [r-stores (atom {})
        store-states (atom {})
        offline-stores (atom {})
        default-state-map {:get true
                           :get-id-xs true
                           :set true
                           :prune-ids true
                           :del true}
        get-server-conf (fn [server-conf]
                          (if-let [r-store (get @r-stores
                                                server-conf)]
                            r-store
                            (let [r-store (atom {})]
                              (swap! r-stores
                                     assoc
                                     server-conf
                                     r-store)
                              r-store)))
        get-server-state (fn [server-conf k]
                           (get (get @store-states
                                     server-conf
                                     default-state-map)
                                k))]
    (reify
      BunshinDataStorage
      (get [this server-conf k]
        (when (get-server-state server-conf :get)
          (try
            (Thread/sleep (rand-sleep))
            (let [{:keys [val expire_at]} (get @(get-server-conf server-conf) k)]
              (if expire_at
                (when (ctc/after? expire_at (ctc/now))
                  val)
                val))
               (catch Exception _))))

      (get-id-xs [this server-conf k]
        (when (get-server-state server-conf :get-id-xs)
          (Thread/sleep (rand-sleep))
          (try
            (let [r-store (get-server-conf server-conf)]
              (if-let [xs (get @r-store k)]
                (map (comp str first) (sort-by (comp - first) xs))
                []))
            (catch Exception _))))

      (set [this server-conf val-key val id-key id ttl]
        (when (get-server-state server-conf :set)
          (try
            (Thread/sleep (rand-sleep))
            (let [d (ctc/plus (ctc/now)
                              (ctc/seconds ttl))
                  val-map (if (and ttl
                                   (pos? ttl))
                            {:val val
                             :expire_at d}
                            {:val val})]
              (swap! (get-server-conf server-conf)
                     (fn [v]
                       (-> v
                           (update-in [id-key] (fn [s]
                                                 (assoc s id 1)))
                           (assoc val-key val-map)))))
            (catch Exception _))))

      (prune-ids [this server-conf id-key]
        (when (get-server-state server-conf :prune-ids)
          (try
            (Thread/sleep (rand-sleep))
            (swap!(get-server-conf server-conf)
                  update-in
                  [id-key]
                  (fn [s]
                    (into {} (take 1 (sort-by (comp - first) s)))))
            (catch Exception _))))

      (del [this server-conf keys]
        (when (get-server-state server-conf :del)
          (try
            (Thread/sleep (rand-sleep))
            (let [r-store (get-server-conf server-conf)]
              (doseq [key keys]
                (swap! r-store dissoc key)))
            (catch Exception _))))
      TestableServer
      (start [this server fresh?]
        (if fresh?
          (when-let [r-store (get @offline-stores server)]
            (do (swap! r-stores assoc server r-store)
                (swap! offline-stores dissoc server)))
          (swap! r-stores assoc server (atom {}))))
      (shutdown [this server]
        (let [r-store (get @r-stores server)]
          (swap! offline-stores assoc server r-store))
        (swap! r-stores assoc server true))

      (get-data [this]
        r-stores)

      (partial-fail [this server state-map]
        (swap! store-states
               assoc
               server
               (merge default-state-map
                      state-map))))))
