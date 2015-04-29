(ns bunshin.benchmarks
  (:require [bunshin.benchmarks :refer :all]
            [bunshin.core :as bc]
            [bunshin.datastores.in-memory :refer [gen-in-memory-backend
                                                  shutdown start partial-fail
                                                  get-data]]
            [criterium.core :as cc]
            [clojure.test :refer :all]))

;;; These benchmarks run on in-memory backend. In memory backend has
;;; thread/sleeps which try to emulate production latency

;;; This benchmark aims to test performance of bunshin's model of
;;; running query. These results will vary with real redis instances but
;;; this gives a clearer idea of how bunshin will work

;; (1 10 20 30 40 50 60 70 80 90 100)
;; (0.009077299428315413 0.07632339347222222 0.17755765050000002 0.25530742226666664 0.36088783383333334 0.4146356505 0.5087279088333333 0.5478025367777778 0.6030042421666667 0.6842258505000001 0.7761572588333334)
(defn bench-set!
  [replication-factor]
  (let [ctx (bc/gen-context (take replication-factor (range))
                            (gen-in-memory-backend))
        n (atom 0)]
    (cc/benchmark (bc/set! ctx "foo" "hello world"
                           :id (swap! n inc)
                           :replication-factor replication-factor)
                  {})))


;; (1 10 20 30 40 50 60 70 80 90 100)
;; (0.006859095753333334 0.03977656643589744 0.08460825524561405 0.1337506301904762 0.1409151505 0.1914606556 0.2606408255 0.26275291300000003 0.30316741925 0.3500710201111112 0.40578012550000003)

(defn bench-get!
  [replication-factor]
  (let [ctx (bc/gen-context (take replication-factor (range))
                            (gen-in-memory-backend))]
    (bc/set! ctx "foo" "hello world" :replication-factor replication-factor)
    (cc/benchmark (bc/get! ctx "foo" :replication-factor replication-factor)
                  {})))


;; (1 10 20 30 40 50 60 70 80 90 100)
;; (0.004199259339285714, 0.004231066232900434 0.004461683482269503 0.0042526224437500005 0.004651562815891473 0.004357024291666667 0.004586260432624114 0.0040047271763668435 0.004570258455426357 0.004137806738993711 0.004332861722789115)
(defn bench-get-fast
  [replication-factor]
  (let [ctx (bc/gen-context (take replication-factor (range))
                            (gen-in-memory-backend))]
    (bc/set! ctx "foo" "hello world" :replication-factor replication-factor)
    (let [{:keys [servers id]}
          (bc/get-with-meta! ctx "foo" :replication-factor replication-factor)]
      (cc/benchmark (bc/get-fast ctx "foo" id servers) {}))))
