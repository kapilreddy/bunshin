# Bunshin

Bunshin means clone in Japanese.

Bunshin is a Redis based multi instance cache library that aims for high availability, partition tolerance and eventual consistency. The primary ideas used are consistent hashing, CRDTs and repair on read.


### Version
Bunshin is in the early stage of development and is not ready for production use.

### Rationale

Bunshin primarily aims for

- High availability
- Distributing query load across multiple machines

Distributing query load is important because even though Redis is capable of a high number of queries per second, network bandwidth becomes a bottleneck for a single machine cache. (Refer: [here](http://redis.io/topics/benchmarks#factors-impacting-redis-performance))


#### Leiningen
```
[me.kapilreddy/bunshin "0.1.0-SNAPSHOT"]
```

### External Dependencies

Redis version > 2.0.0


### Definitions

key - An identifier used for a resource

id - A monotonically increasing number used to identify unique value for a resource. Ids are timestamps by default but custom ids can be provided.


### How it works

1. Bunshin uses [consistent hashing](http://en.wikipedia.org/wiki/Consistent_hashing) to decide which Redis nodes to use for storing cache data.
2. It adds a server unix timestamp as `id` when storing a value, but this id can be provided by the app logic as well.
3. It maintains a G-Set CRDT for each resource key where elements are ids (timestamps).
4. Since we always want the latest value, older elements are pruned on write.

![Architecture](https://rawgithub.com/kapilreddy/bunshin/gh-pages/images/arch.svg "Bunshin Architecture")

App servers use bunshin to request cache data from redis servers listed. Another way to use bunshin would be write a bunshin server with REST API. More details found [here](https://github.com/kapilreddy/bunshin/wiki/Architecture)

### Getting started

```clojure
  (require '[bunshin.core :as bc])
  ;; Let's build a cache with a single redis node
  (def ctx (bc/gen-context [{:pool {}
                             :spec {:host "127.0.0.1"
                                    :port 6379}}]))

  ;; The default replication factor is 2. However, we've provided only one
  ;; server in the ring. Therefore, it'll be the one that is always selected.
  (bc/get! ctx "test1") ;; nil

  (bc/store! ctx "test1" "hello world3") ;; nil

  (bc/get! ctx "test1") ;; "hello world3" served from 6379

  ;; Let us expand the cache to spread across 4 nodes.
  (def ctx (bc/gen-context [{:pool {}
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

  ;; Since the replication factor is 2, two servers will be selected from
  ;; the ring. (Say 6379 and 6380)

  ;; First `get` request will be served from 6379 since data is already
  ;; present. A repair on read operation will be done for 6380
  ;; asynchronously
  (bc/get! ctx "test1") ;; "hello world3" served from 6379

  (bc/get! ctx "test1") ;; served either from 6379 or 6380

  ;; Cache is again re-sized and this time it has shrunk.
  (def ctx (bc/gen-context [{:pool {}
                             :spec {:host "127.0.0.1"
                                    :port 6379}}]))


  (bc/get! ctx "test1") ;; served from 6379
```

### API

[API Docs](http://kapilreddy.github.io/bunshin/bunshin.core.html)

### Benchmarks

![Bunshin commands benchmark](benchmarks/benchmarks.png?raw=true "Bunshin commands benchmark on in-memory backend")

These benchmarks run on in-memory backend. In memory backend has Thread/sleeps which try to emulate production latency.

This benchmark aims to test performance of bunshin's model of running query. These results will vary with real Redis instances but this gives a clearer idea of how bunshin will work

### Notes for Ops and Maintenance

Wherever possible, set TTL expiry for your cache data. This allows auto-cleanup of stale data. If you cannot change application logic to use TTL, then Bunshin requires that any re-size operation should use clean, empty nodes. ("clean-slate" start)

### Libraries
Bunshin uses these awesome libraries

- [ketamine](https://github.com/ghoseb/ketamine)
- [carmine](https://github.com/ptaoussanis/carmine)
- [clj-time](https://github.com/clj-time/clj-time)
- [test.check](https://github.com/clojure/test.check)
- [criterium](https://github.com/hugoduncan/criterium)

### TODO
- Publish benchmark results with Redis machines
- Add doc for implementing custom backend storage
- Add metric endpoints

### Acknowledgements
Thanks to [@ghoseb](https://twitter.com/ghoseb), [@vedang](https://twitter.com/vedang), [@samuel](https://twitter.com/samebchase) and [@kiran](https://twitter.com/kiran_kulkarni) for the feedback.

### Links and papers
These links and papers have provided inspiration and knowledge to build bunshin.

- [http://antirez.com/news/33](http://antirez.com/news/33)
- [CRDTs: Consistency without concurrency control - Letia, Mihai; Preguiça, Nuno and Shapiro, MarcMihai](http://pagesperso-systeme.lip6.fr/Marc.Shapiro/papers/RR-6956.pdf)
- [Roshi by SoundCloud](https://github.com/soundcloud/roshi)
- [https://github.com/aphyr/meangirls](https://github.com/aphyr/meangirls)


##License

Copyright © 2015 Kapil Reddy

Licensed under the [Eclipse Public License](http://www.eclipse.org/legal/epl-v10.html) (the same as Clojure)
