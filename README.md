# Bunshin

Bunshin means clone in Japanese.

Bunshin is a redis based multi instance cache library that aims for high availability. The primary ideas used are consistent hashing and repair on read.


### Version
Bunshin is in early stage and it's not ready for production use.

####Leiningen
```
[me.kapilreddy/bunshin "0.1.0-SNAPSHOT"]
```

### External Dependencies

Redis version > 2.0.0

### How it works

Bunshin uses [consistent hashing](http://en.wikipedia.org/wiki/Consistent_hashing) for deciding which redis nodes to use for storing cache data. While storing a value bunshin will add a server unix timestamp as id, but this id can be provided by the app logic as well. As long as this number is monotonically increasing for each modification to the value it will work.


### Definations

key - An identifier used to a resource

id - A monotonically increasing number used to identify unique value for a resource. Ids are timestamps by default but custom ids can be provided.


### Getting started

```clojure
(require '[redusa.core :as rc])
(def ctx (gen-context [{:pool {}
                          :spec {:host "127.0.0.1"
                                 :port 6379}}]))
  (get  ctx "test1") ;; nil

  (set ctx "test1" "hello world3") ;; nil

  (get ctx "test1") ;; severed from 6379

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

  (get ctx "test1")
  ;; served either from 6379 or 6380/6381/6382

  (def ctx (gen-context [{:pool {}
                          :spec {:host "127.0.0.1"
                                 :port 6379}}]))


  (get ctx "test1") ;; served from 6379
```

### How it works
Bunshin uses redis sorted set to store ids related to a key. This avoids destroying latest data non-determinstically.


### API

[API Docs](http://kapilreddy.github.io/bunshin/bunshin.core.html)

### Benchmarks

![Bunshin commands benchmark](benchmarks/benchmarks.png?raw=true "Bunshin commands benchmark on in-memory backend")

These benchmarks run on in-memory backend. In memory backend has thread/sleeps which try to emulate production latency.

This benchmark aims to test performance of bunshin's model of running query. These results will vary with real redis instances but this gives a clearer idea of how bunshin will work

### Ops

If you are not setting ttl to your keys. Recovered nodes and new nodes in cluster should always start from a clean slate.

### Libraries
Bunshin uses these awesome libraries

- [ketamine](https://github.com/ghoseb/ketamine)
- [carmine](https://github.com/ptaoussanis/carmine)
- [clj-time](https://github.com/clj-time/clj-time)
- [test.check](https://github.com/clojure/test.check)
- [criterium](https://github.com/hugoduncan/criterium)

### TODO
- Publish benchmark results with redis machines
- Add doc for implementing custom backend storage
- Add metric endpoints

### Acknowledgements
Thank you @ghoseb, @vedang and @kiran_kulkarni for the feedback.


##License

Copyright © 2015 Kapil Reddy

Licensed under the Eclipse Public License (the same as Clojure)
