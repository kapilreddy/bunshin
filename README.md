# Bunshin

Bunshin is a redis based multi instance cache system library that aims for high availability. The primary ideas used are consistent hashing and repair on read.
Bunshin means clone in Japanese.

### Version
Hosted on clojars.og
0.1.0

### Dependencies

Redis version > 2.6.12

### Architecture

Bunshin uses [consistent hashing](http://en.wikipedia.org/wiki/Consistent_hashing) for deciding which redis nodes to use for storing cache data. While storing a value bunshin will add a server unix timestamp, but this timestamp can be provided by the app logic as well. As long as this number is monotonically increasing number for each modification to the value it will work. Any new writes on same key will check


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


  (get ctx "test1") ;; served either from 6379
```

### How it works
Bunshin uses redis sorted set to store ids related to a set. This avoids destroying information of latest information non-determinstically.


### API

get - Get either returns value for specified key or nil. Nil should be treated as miss.
set - Set value to cache. Replication factor will decide how many nodes will have this data. Even though set
del - Delete data from all nodes with replicated values


### Scenarios

###Successful write
###Stale write
###Successful read
For key x of replication factor 3 there are three nodes selected [A, B, C]

- Timestamp reads go to [A, B, C]
- One node is selected from  [A, B, C] for fetching data

### concurrent writes to same key and same id from same bunshin machine
All writes after first one will be dropped. This helps reduce surge of load introduced when a new redis is added to cluster

### concurrent writes to same key with different ids
- M1 - Id reads go to [A, B, C]
- M1 - If id is fresh then writes go to [A, B, C] writes update data and id set.
- M2 - Id reads go to [A, B, C]
- M2 - If id is fresh then writes go to [A, B, C] writes update data and id set.


####Node failure / Network failure #1
For key x of replication factor 3 there are three nodes selected [A, B, C]

- Node A fails
- Timestamp reads go to [A, B, C] and read for A fails
- Reads go to one of [B, C]


####Node failure / Network failure #2
For key x of replication factor 3 there are three nodes selected [A, B, C]
- Timestamp reads go to [A, B, C]
- Node A fails
- A is selected for fetching data. Fetching data fails.
- This will be a cache miss

#### Node failure / Network failure #3
- [N1, N2, N3, N4] nodes have received data
- [N2, N3, N4] fail
- [N1] nodes have received data
- [N2] recovered and [N1] failed
- Get now will result in stale data
This can be avoided by always adding new nodes in cluster with data
removed. This included recovered nodes as well. Check Ops section

#### Information destruction no


### Benchmarks

### Ops

Recovered nodes and new nodes in cluster should always start from a
clean slate.

While shrinking cluster it is always a good idea to start fresh machine
redis instances instead of reusing the ones already in ring. This will
take care of problems related to reassigning id to old machines which
might have stale data.

### Libraries
Bunshin uses these awesome libraries

- https://github.com/ghoseb/ketamine
- https://github.com/ptaoussanis/carmine
- https://github.com/clj-time/clj-time

### Acknowledgements
Thank you @ghoseb, @vedang and @kiran_kulkarni for the feedback.


##License

Copyright © 2015 Kapil Reddy

Licensed under the Eclipse Public License (the same as Clojure)
