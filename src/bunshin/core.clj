(ns bunshin.core
  (:require [clojure.set :as cs]
            [ketamine.core :as ketama]
            [clj-time.core :as ct]
            [bunshin.datastores.redis :refer [redis-backend]]
            [bunshin.datastores.datastore :refer [BunshinDataStorage]]
            [bunshin.datastores.datastore :as bdd]))


(defn gen-ts-set-key
  [key]
  (format "bunshin-ids:%s" key))


(defn gen-val-key
  [key ts]
  (format "%s:%.0f" key (double ts)))


(defn gen-ts
  []
  (.getMillis (ct/now)))


(defn get-servers
  [ring id n]
  (take n (clojure.core/set (take (* n 2)
                                  (ketama/node-seq ring id)))))


(defn get-fresh-ts
  [server-with-ts-xs]
  (first (first (first (sort-by (comp - first first)
                                (filter (comp seq first)
                                        server-with-ts-xs))))))


(defn fetch-ts
  [{:keys [storage-backend]}
   server
   key]
  (when-let [ts-str-xs (bdd/get-id-xs storage-backend
                                      server
                                      (gen-ts-set-key key))]
    [(map (fn [i]
            (Double/parseDouble i))
          ts-str-xs)
     server]))


(defn fetch-ts-xs
  [{:keys [^BunshinDataStorage storage-backend
           submit-to-threadpool-fn]
    :as ctx}
   servers
   key]
  (let [fetch-ts-l (partial fetch-ts ctx)
        results (map #(submit-to-threadpool-fn (fn []
                                              (fetch-ts-l %
                                                          key)))
                     servers)]
    (doall (map deref
                results))))


(defn set*
  [{:keys [^BunshinDataStorage storage-backend
           running-set-operations submit-to-threadpool-fn]}
   servers-with-ts key val ts]
  (let [val-key (gen-val-key key ts)]
    (when-not (@running-set-operations val-key)
      (swap! running-set-operations conj val-key)
      (doseq [[ts-xs server] servers-with-ts]
        (bdd/set storage-backend
                 server
                 val-key
                 val
                 (gen-ts-set-key key)
                 ts)
        (when (seq ts-xs)
          (submit-to-threadpool-fn (fn []
                                  (bdd/prune-ids storage-backend
                                                 server
                                                 (gen-ts-set-key key))
                                  (bdd/del storage-backend
                                           server
                                           (map (partial gen-val-key key)
                                                ts-xs))))))
      (swap! running-set-operations disj val-key))))


(defn set
  [{:keys [^BunshinDataStorage storage-backend
           ^HashRing ring]
    :as ctx}
   key
   val
   & {:keys [replication-factor ts]
      :or {replication-factor 2
           ts (gen-ts)}}]
  (let [servers (get-servers ring key replication-factor)
        servers-with-ts (fetch-ts-xs ctx servers key)
        fresh-ts (get-fresh-ts servers-with-ts)]
    (if (or (nil? fresh-ts)
            (< fresh-ts ts))
      (do (set* ctx
                servers-with-ts
                key
                val
                ts)
          :ok)
      :stale-write)))


(defn get
  [{:keys [^BunshinDataStorage storage-backend
           ^HashRing ring
           load-distribution-fn
           submit-to-threadpool-fn]
    :as ctx}
   key
   & {:keys [replication-factor]
      :or {replication-factor 2}}]
  (let [servers (get-servers ring key replication-factor)]
    (let [servers-with-ts (filter (comp seq first)
                                  (fetch-ts-xs ctx servers key))]
      (when (seq servers-with-ts)
        (let [fresh-ts (get-fresh-ts servers-with-ts)]
          (when fresh-ts
            (let [in-sync-servers (map second
                                       (filter #(= fresh-ts (first (first %)))
                                               servers-with-ts))
                  fresh-data (let [server (load-distribution-fn in-sync-servers)]
                               (bdd/get storage-backend
                                        server
                                        (gen-val-key key fresh-ts)))]
              (submit-to-threadpool-fn
               (fn []
                 (let [out-of-sync-servers
                       (cs/difference (clojure.core/set servers)
                                      (clojure.core/set in-sync-servers))]
                   (set ctx
                        out-of-sync-servers
                        key
                        fresh-data
                        :ts fresh-ts
                        :replication-factor replication-factor))))
              fresh-data)))))))


(defn del
  [{:keys [storage-backend ring] :as ctx}
   key & {:keys [replication-factor]
          :or {replication-factor 2}}]
  (let [servers (get-servers ring key replication-factor)
        servers-with-ts (fetch-ts-xs ctx servers key)]
    (doseq [[ts-xs server] servers-with-ts]
      (when (seq ts-xs)
        (bdd/del storage-backend
                 server
                 (concat (map (partial gen-val-key key)
                              ts-xs)
                         (gen-ts-set-key key)))))))


(defn gen-context
  ([servers-conf-list]
     (gen-context servers-conf-list
                  redis-backend))
  ([servers-conf-list server-backend]
     (gen-context servers-conf-list
                  server-backend
                  (fn [thunk]
                    (future (thunk)))
                  (comp first shuffle)
                  (ketama/make-ring servers-conf-list)))
  ([servers-conf-list storage-backend submit-to-threadpool-fn
    load-distribution-fn ring]
     {:storage-backend storage-backend
      :submit-to-threadpool-fn submit-to-threadpool-fn
      :load-distribution-fn load-distribution-fn
      :running-set-operations (atom #{})
      :ring ring}))


(comment
  (def ctx (gen-context [{:pool {}
                          :spec {:host "127.0.0.1"
                                 :port 6379}}]))

  ;;; Request 1 to 127.0.0.1:6379
  ;;; zrevrange "bunshinids:foo" 0 -1 "withscores"
  (get  ctx "foo") ;; nil

  ;;; Request 1 to 127.0.0.1:6379
  ;;; zrevrange "bunshinids:foo" 0 -1 "withscores"
  ;;; Request 2 to 127.0.0.1:6379
  ;;; zadd "bunshinids:foo" 20 1
  ;;; set "foo:20" "hello world"
  (set ctx "foo" "hello world" :ts 20) ;; :ok

  ;;; Request 1 to 127.0.0.1:6379
  ;;; zrevrange "bunshinids:foo" 0 -1 "withscores"
  (set ctx "foo" "hello world new" :ts 20) ;; :stale-write

  ;;; Request 1 to 127.0.0.1:6379
  ;;; zrevrange "bunshinids:foo" 0 -1 "withscores"

  ;;; Request 2 to 127.0.0.1:6379
  ;;; zadd "bunshinids:foo" 21 1
  ;;; set "foo:21" "hello worl new"
  ;;; zremrangebyrank "bunshin:foo" 1 -1
  ;;; del "foo:20"
  (set ctx "foo" "hello world new" :ts 21) ;; :ok

  ;;; Request 1 to 127.0.0.1:6379
  ;;; zrevrange "bunshinids:foo" 0 -1 "withscores"
  ;;; Request 2 to 127.0.0.1:6379
  ;;; get "foo:21"
  (get ctx "foo") ;; "hello world new"

  (def ctx (gen-context [{:pool {}
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
                                 :port 6382}}]))


  ;; Assume that mapping for id foo is 127.0.0.1:6380 and 127.0.0.1:6381

  ;;; Request phase 1
  ;;; Requests to 127.0.0.1:6380, 127.0.0.1:6381
  ;;; zrevrange "bunshinids:foo" 0 -1 "withscores"
  (get ctx "foo") ;; nil

  ;;; Request phase 1
  ;;; Requests to 127.0.0.1:6380, 127.0.0.1:6381
  ;;; zrevrange "bunshinids:foo" 0 -1 "withscores"

  ;;; Request phase 2
  ;;; Requests to 127.0.0.1:6380, 127.0.0.1:6381
  ;;; zadd "bunshinids:foo" 20 1
  ;;; set "foo:20" "hello world"
  (set ctx "foo" "hello world" :ts 20) ;; :ok

  ;;; Request 1 to 127.0.0.1:6379
  ;;; zrevrange "bunshinids:foo" 0 -1 "withscores"
  (set ctx "foo" "hello world new" :ts 20) ;; :stale-write

  ;;; Request 1 to 127.0.0.1:6379
  ;;; zrevrange "bunshinids:foo" 0 -1 "withscores"

  ;;; Request 2 to 127.0.0.1:6379
  ;;; zadd "bunshinids:foo" 21 1
  ;;; set "foo:21" "hello worl new"
  ;;; zremrangebyrank "bunshin:foo" 1 -1
  ;;; del "foo:20"
  (set ctx "foo" "hello world new" :ts 21) ;; :ok

  (def ctx (gen-context [{:pool {}
                          :spec {:host "127.0.0.1"
                                 :port 6379}}]))


  (get ctx "foo") ;; served either from 6379
  )
