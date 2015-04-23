(ns bunshin.core-test
  (:require [bunshin.datastores.datastore :refer [BunshinDataStorage]]
            [bunshin.datastores.in-memory :refer [gen-in-memory-backend
                                                  shutdown start partial-fail
                                                  get-data]]
            [ketamine.core :as ketama]
            [clojure.test :refer :all]
            [bunshin.core :as bc]))


(deftest single-server-normal
  (let [ctx (bc/gen-context [6379]
                            (gen-in-memory-backend))]
    (is (nil? (bc/get ctx "foo")))
    (is (= (bc/set ctx "foo" "hello world" :ts 10) :ok))
    (is (= (bc/set ctx "foo" "hello world" :ts 10) :stale-write))
    (is (= (bc/set ctx "foo" "hello new world" :ts 11) :ok))
    (is (= (bc/get ctx "foo") "hello new world"))))


(deftest multi-server-normal
  (let [ctx (bc/gen-context [6379 6380 6381 6382]
                            (gen-in-memory-backend))]
    (is (nil? (bc/get ctx "foo")))
    (is (= (bc/set ctx "foo" "hello world" :ts 10) :ok))
    (is (= (bc/set ctx "foo" "hello world" :ts 10) :stale-write))
    (is (= (bc/set ctx "foo" "hello new world" :ts 11) :ok))
    (is (= (bc/get ctx "foo") "hello new world"))))


(deftest multi-server-fail-scenario-1
  (let [ctx (bc/gen-context [6379 6380 6381 6382]
                            (gen-in-memory-backend))
        {:keys [storage-backend ring]} ctx
        key "foo"
        replication-factor 4]
    (is (nil? (bc/get ctx key :replication-factor replication-factor)))
    (is (= (bc/set ctx key "hello world"
                   :ts 10 :replication-factor replication-factor)
           :ok))
    (is (= (bc/set ctx key "hello world"
                   :ts 10 :replication-factor replication-factor)
           :stale-write))

    ;; All but one servers is running
    (let [nodes (take replication-factor (ketama/node-seq ring key))
          nodes-to-shutdown (take (dec replication-factor) (shuffle nodes))]
      (doseq [node nodes-to-shutdown]
        (shutdown storage-backend node))
      (is (= (bc/set ctx key "hello new world"
                     :ts 11 :replication-factor replication-factor)
             :ok))
      (is (= (bc/get ctx key
                     :replication-factor replication-factor)
             "hello new world")))

    ;; All servers are running again
    (let [nodes (take replication-factor (ketama/node-seq ring key))]
      (doseq [node nodes]
        (start storage-backend node true))
      (is (= (bc/get ctx key
                     :replication-factor replication-factor)
             "hello new world")))

    ;; No servers are running
    (let [nodes (take replication-factor (ketama/node-seq ring key))]
      (doseq [node nodes]
        (shutdown storage-backend node))
      (is (nil? (bc/get ctx key
                        :replication-factor replication-factor))))))


(deftest multi-server-fail-scenario-2
  (let [ctx (bc/gen-context [6379 6380 6381 6382]
                            (gen-in-memory-backend))
        {:keys [storage-backend ring]} ctx
        key "foo"
        replication-factor 4]
    (is (nil? (bc/get ctx key
                      :replication-factor replication-factor)))
    (is (= (bc/set ctx key "hello world"
                   :ts 10 :replication-factor replication-factor)
           :ok))

    ;; All but one servers is running
    (let [nodes (take (dec replication-factor) (ketama/node-seq ring key))]
      (doseq [node nodes]
        (shutdown storage-backend node))
      (is (= (bc/set ctx key "hello new world"
                     :ts 11 :replication-factor replication-factor)
             :ok))
      (is (= (bc/get ctx key
                     :replication-factor replication-factor)
             "hello new world")))

    ;; All servers are running again except the server running
    ;; previously
    (let [nodes (take (dec replication-factor) (ketama/node-seq ring key))]
      (doseq [node nodes]
        (start storage-backend node true))
      (shutdown storage-backend (last (take replication-factor
                                            (ketama/node-seq ring key))))
      (is (= (bc/get ctx key
                     :replication-factor replication-factor)
             "hello world")))))


(deftest concurrent-writes
  (let [ctx (bc/gen-context [6379 6380 6381 6382]
                            (gen-in-memory-backend))
        {:keys [storage-backend ring]} ctx
        key "foo"
        replication-factor 4]
    (is (nil? (bc/get ctx key
                      :replication-factor replication-factor)))

    (is (every? #{:stale-write :ok}
                (map deref
                     (map (fn [n]
                            (future (Thread/sleep (rand-int 100))
                                    (bc/set ctx key (str "hello world" n)
                                            :ts n
                                            :replication-factor replication-factor)))
                          (range 100)))))

    (is (= (bc/get ctx key
                   :replication-factor replication-factor)
           (str "hello world" 99)))))


(deftest partial-failures-scenario-1
  (let [mem (gen-in-memory-backend)
        server-list [6379
                     6380]
        ctx (bc/gen-context mem
                            (fn [thunk]
                              (future (thunk)))
                            ;; Always select first server to fetch data
                            first
                            (ketama/make-ring [6379
                                               6380]))
        {:keys [storage-backend ring]} ctx
        key "foo"
        replication-factor 4]

    (is (nil? (bc/get ctx "foo")))
    (is (= (bc/set ctx "foo" "hello world" :ts 10) :ok))
    (is (= (bc/set ctx "foo" "hello world" :ts 10) :stale-write))

    ;; For 6379 id list will succeed but next get request will fails.
    (partial-fail mem
                  6379
                  {:get false})
    (is (nil? (bc/get ctx "foo")))

    (partial-fail mem
                  6379
                  {:get true})
    (partial-fail mem
                  6380
                  {:get false})
    (is (= (bc/get ctx "foo") "hello world"))))
