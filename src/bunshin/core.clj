(ns bunshin.core
  (:require [clojure.set :as cs]
            [ketamine.core :as ketama]
            [clj-time.core :as ct]
            [bunshin.datastores.redis :refer [redis-backend]]
            [bunshin.datastores.datastore :refer [BunshinDataStorage]]
            [bunshin.datastores.datastore :as bdd]))


(defn- gen-id-set-key
  [key]
  (format "bunshin-ids:%s" key))


(defn- gen-val-key
  [key id]
  (format "%s:%.0f" key (double id)))


(defn- gen-id
  []
  (.getMillis (ct/now)))


(defn- get-servers
  [ring id n]
  (take n (clojure.core/set (take (* n 2)
                                  (ketama/node-seq ring id)))))


(defn- get-fresh-id
  [server-with-id-xs]
  (first (first (first (sort-by (comp - first first)
                                (filter (comp seq first)
                                        server-with-id-xs))))))


(defn- fetch-id
  [{:keys [storage-backend]}
   server
   key]
  (when-let [id-str-xs (bdd/get-id-xs storage-backend
                                      server
                                      (gen-id-set-key key))]
    [(map (fn [i]
            (Double/parseDouble i))
          id-str-xs)
     server]))


(defn- fetch-id-xs
  [{:keys [^BunshinDataStorage storage-backend
           submit-to-threadpool-fn]
    :as ctx}
   servers
   key]
  (let [fetch-id-l (partial fetch-id ctx)
        results (map #(submit-to-threadpool-fn (fn []
                                              (fetch-id-l %
                                                          key)))
                     servers)]
    (doall (map deref
                results))))


(defn- set*
  [{:keys [^BunshinDataStorage storage-backend
           running-set-operations submit-to-threadpool-fn]}
   servers-with-id key val id
   & {:keys [ttl]}]
  (let [val-key (gen-val-key key id)]
    (when-not (@running-set-operations val-key)
      (swap! running-set-operations conj val-key)
      (doseq [[id-xs server] servers-with-id]
        (bdd/set storage-backend
                 server
                 val-key
                 val
                 (gen-id-set-key key)
                 id
                 ttl)
        (let [extra-ids (remove #(= (double id) %)
                                id-xs)]
          (when (seq extra-ids)
            (submit-to-threadpool-fn (fn []
                                       (bdd/prune-ids storage-backend
                                                      server
                                                      (gen-id-set-key key))
                                       (bdd/del storage-backend
                                                server
                                                (map (partial gen-val-key key)
                                                     extra-ids)))))))
      (swap! running-set-operations disj val-key))))


(defn set
  [{:keys [^BunshinDataStorage storage-backend
           ^HashRing ring]
    :as ctx}
   key
   val
   & {:keys [replication-factor id ttl]
      :or {replication-factor 2
           ttl -1
           id (gen-id)}}]
  (let [servers (get-servers ring key replication-factor)
        servers-with-id (fetch-id-xs ctx servers key)
        fresh-id (get-fresh-id servers-with-id)]
    (if (or (nil? fresh-id)
            (<= fresh-id id))
      (do (set* ctx
                servers-with-id
                key
                val
                id
                :ttl ttl)
          :ok)
      :stale-write)))


(defn get
  [{:keys [^BunshinDataStorage storage-backend
           ^HashRing ring
           load-distribution-fn
           submit-to-threadpool-fn]
    :as ctx}
   key
   & {:keys [replication-factor ttl]
      :or {replication-factor 2
           ttl -1}}]
  (let [servers (get-servers ring key replication-factor)]
    (let [servers-with-id (filter (comp seq first)
                                  (fetch-id-xs ctx servers key))]
      (when (seq servers-with-id)
        (let [fresh-id (get-fresh-id servers-with-id)]
          (when fresh-id
            (let [in-sync-servers (map second
                                       (filter #(= fresh-id (first (first %)))
                                               servers-with-id))
                  fresh-data (let [server (load-distribution-fn in-sync-servers)]
                               (bdd/get storage-backend
                                        server
                                        (gen-val-key key fresh-id)))]
              (submit-to-threadpool-fn
               (fn []
                 (let [out-of-sync-servers
                       (cs/difference (clojure.core/set servers)
                                      (clojure.core/set in-sync-servers))]
                   (set ctx
                        out-of-sync-servers
                        key
                        fresh-data
                        :id fresh-id
                        :ttl ttl
                        :replication-factor replication-factor))))
              fresh-data)))))))


(defn del
  [{:keys [storage-backend ring] :as ctx}
   key & {:keys [replication-factor]
          :or {replication-factor 2}}]
  (let [servers (get-servers ring key replication-factor)
        servers-with-id (fetch-id-xs ctx servers key)]
    (doseq [[id-xs server] servers-with-id]
      (when (seq id-xs)
        (bdd/del storage-backend
                 server
                 (concat (map (partial gen-val-key key)
                              id-xs)
                         (gen-id-set-key key)))))))


(defn gen-context
  ([servers-conf-list]
     (gen-context servers-conf-list
                  redis-backend))
  ([servers-conf-list server-backend]
     (gen-context server-backend
                  (fn [thunk]
                    (future (thunk)))
                  (comp first shuffle)
                  (ketama/make-ring servers-conf-list)))
  ([storage-backend submit-to-threadpool-fn
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
  (set ctx "foo" "hello world" :id 20 :ttl 10) ;; :ok

  ;;; Request 1 to 127.0.0.1:6379
  ;;; zrevrange "bunshinids:foo" 0 -1 "withscores"
  (set ctx "foo" "hello world new" :id 20) ;; :stale-write

  ;;; Request 1 to 127.0.0.1:6379
  ;;; zrevrange "bunshinids:foo" 0 -1 "withscores"

  ;;; Request 2 to 127.0.0.1:6379
  ;;; zadd "bunshinids:foo" 21 1
  ;;; set "foo:21" "hello worl new"
  ;;; zremrangebyrank "bunshin:foo" 1 -1
  ;;; del "foo:20"
  (set ctx "foo" "hello world new" :id 21) ;; :ok

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
  (set ctx "foo" "hello world" :id 20) ;; :ok

  ;;; Request 1 to 127.0.0.1:6379
  ;;; zrevrange "bunshinids:foo" 0 -1 "withscores"
  (set ctx "foo" "hello world new" :id 20) ;; :stale-write

  ;;; Request 1 to 127.0.0.1:6379
  ;;; zrevrange "bunshinids:foo" 0 -1 "withscores"

  ;;; Request 2 to 127.0.0.1:6379
  ;;; zadd "bunshinids:foo" 21 1
  ;;; set "foo:21" "hello worl new"
  ;;; zremrangebyrank "bunshin:foo" 1 -1
  ;;; del "foo:20"
  (set ctx "foo" "hello world new" :id 21) ;; :ok

  (def ctx (gen-context [{:pool {}
                          :spec {:host "127.0.0.1"
                                 :port 6379}}]))


  (get ctx "foo") ;; served either from 6379
  )
