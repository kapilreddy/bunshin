(ns bunshin.core-test
  (:require [bunshin.datastores.datastore :refer [BunshinDataStorage]]
            [bunshin.datastores.in-memory :refer [gen-in-memory-backend shutdown start]]
            [ketamine.core :as ketama]
            [clojure.test :refer :all]
            [bunshin.core :as bc]))


(deftest single-server
  (let [ctx (bc/gen-context [{:pool {}
                              :spec {:host "127.0.0.1"
                                     :port 6379}}]
                            (gen-in-memory-backend))]
    (is (nil? (bc/get ctx "foo")))
    (is (= (bc/set ctx "foo" "hello world" :ts 10) :ok))
    (is (= (bc/set ctx "foo" "hello world" :ts 10) :stale-write))
    (is (= (bc/set ctx "foo" "hello new world" :ts 11) :ok))
    (is (= (bc/get ctx "foo") "hello new world"))))


(deftest multi-server
  (let [ctx (bc/gen-context [{:pool {}
                              :spec {:host "127.0.0.1"
                                     :port 6379}}
                             {:pool {}
                              :spec {:host "127.0.0.1"
                                     :port 6380}}
                             {:pool {}
                              :spec {:host "127.0.0.1"
                                     :port 6381}}
                             {:pool {}
                              :spec {:host "127.0.0.1"
                                     :port 6382}}]
                            (gen-in-memory-backend))]
    (is (nil? (bc/get ctx "foo")))
    (is (= (bc/set ctx "foo" "hello world" :ts 10) :ok))
    (is (= (bc/set ctx "foo" "hello world" :ts 10) :stale-write))
    (is (= (bc/set ctx "foo" "hello new world" :ts 11) :ok))
    (is (= (bc/get ctx "foo") "hello new world"))))


(deftest multi-server-fail-scenario-1
  (let [ctx (bc/gen-context [{:pool {}
                              :spec {:host "127.0.0.1"
                                     :port 6379}}
                             {:pool {}
                              :spec {:host "127.0.0.1"
                                     :port 6380}}
                             {:pool {}
                              :spec {:host "127.0.0.1"
                                     :port 6381}}
                             {:pool {}
                              :spec {:host "127.0.0.1"
                                     :port 6382}}]
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


(deftest multi-server-scenario-2
  (let [ctx (bc/gen-context [{:pool {}
                              :spec {:host "127.0.0.1"
                                     :port 6379}}
                             {:pool {}
                              :spec {:host "127.0.0.1"
                                     :port 6380}}
                             {:pool {}
                              :spec {:host "127.0.0.1"
                                     :port 6381}}
                             {:pool {}
                              :spec {:host "127.0.0.1"
                                     :port 6382}}]
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

    ;; All servers are not running=
    (let [nodes (take (dec replication-factor) (ketama/node-seq ring key))]
      (doseq [node nodes]
        (start storage-backend node true))
      (shutdown storage-backend (last (take replication-factor
                                            (ketama/node-seq ring key))))
      (is (= (bc/get ctx key
                     :replication-factor replication-factor)
             "hello world")))))
